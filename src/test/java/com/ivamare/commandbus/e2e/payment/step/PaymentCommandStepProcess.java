package com.ivamare.commandbus.e2e.payment.step;

import com.ivamare.commandbus.CommandBusProperties;
import com.ivamare.commandbus.api.CommandBus;
import com.ivamare.commandbus.e2e.payment.*;
import com.ivamare.commandbus.process.ProcessRepository;
import com.ivamare.commandbus.process.step.DeadlineAction;
import com.ivamare.commandbus.process.step.ExceptionType;
import com.ivamare.commandbus.process.step.StepOptions;
import com.ivamare.commandbus.process.step.TestProcessStepManager;
import com.ivamare.commandbus.process.step.exceptions.StepBusinessRuleException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Payment processing workflow using commandStep() for external service calls.
 *
 * <p>This is an alternative implementation to PaymentStepProcess that demonstrates
 * the commandStep() pattern for calling external domains (FX service, Payment network)
 * via PGMQ queues, providing natural rate limiting through queue concurrency control.
 *
 * <p>Workflow:
 * <pre>{@code
 * execute(state):
 *     step("updateStatusProcessing")  // Local step
 *     step("bookRisk")                 // Local step with risk assessment
 *     commandStep("bookFx")            // External call to FX domain via PGMQ
 *     commandStep("submitPayment")     // External call to network domain via PGMQ
 *     wait("awaitL4")                  // Wait for L4 settlement confirmation
 * }</pre>
 *
 * <p>The key difference from PaymentStepProcess is that bookFx and submitPayment
 * are executed as commands sent to external domain workers, rather than local
 * simulations. This provides:
 * <ul>
 *   <li>Natural rate limiting via worker concurrency (replicas × concurrent-messages)</li>
 *   <li>Decoupled execution - external services run independently</li>
 *   <li>True async response handling via reply queues</li>
 * </ul>
 *
 * <p>Configuration required in application.yml:
 * <pre>
 * commandbus:
 *   command-steps:
 *     bookFx:
 *       domain: fx
 *       command-type: BookFx
 *       timeout: 30s
 *     submitPayment:
 *       domain: network
 *       command-type: SubmitPayment
 *       timeout: 60s
 * </pre>
 */
public class PaymentCommandStepProcess extends TestProcessStepManager<PaymentStepState> {

    private static final Logger log = LoggerFactory.getLogger(PaymentCommandStepProcess.class);

    private static final String DOMAIN = "payments";
    private static final String PROCESS_TYPE = "PAYMENT_COMMAND_STEP";

    private final Random random = new Random();
    private final PaymentRepository paymentRepository;
    private final PendingApprovalRepository pendingApprovalRepository;

    // Network simulator for async L1-L4 responses
    private StepPaymentNetworkSimulator networkSimulator;

    public PaymentCommandStepProcess(
            ProcessRepository processRepo,
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            CommandBus commandBus,
            CommandBusProperties properties,
            PaymentRepository paymentRepository,
            PendingApprovalRepository pendingApprovalRepository) {
        super(processRepo, jdbcTemplate, transactionTemplate, commandBus, properties);
        this.paymentRepository = paymentRepository;
        this.pendingApprovalRepository = pendingApprovalRepository;
    }

    public void setNetworkSimulator(StepPaymentNetworkSimulator networkSimulator) {
        this.networkSimulator = networkSimulator;
    }

    @Override
    public String getProcessType() {
        return PROCESS_TYPE;
    }

    @Override
    public String getDomain() {
        return DOMAIN;
    }

    @Override
    public Class<PaymentStepState> getStateClass() {
        return PaymentStepState.class;
    }

    @Override
    protected Duration getDefaultWaitTimeout() {
        return Duration.ofHours(4);
    }

    @Override
    protected DeadlineAction getDeadlineAction() {
        return DeadlineAction.TSQ;
    }

    @Override
    protected ExceptionType classifyException(Exception e) {
        if (e instanceof StepBusinessRuleException) {
            return ExceptionType.BUSINESS;
        }
        if (e.getMessage() != null && (
            e.getMessage().contains("timeout") ||
            e.getMessage().contains("connection") ||
            e.getMessage().contains("temporarily"))) {
            return ExceptionType.TRANSIENT;
        }
        return ExceptionType.PERMANENT;
    }

    // ========== Workflow Execution ==========

    @Override
    protected void execute(PaymentStepState state) {
        // Step 1: Update payment status to PROCESSING
        step("updateStatusProcessing", StepOptions.<PaymentStepState, Void>builder()
            .action(this::updateStatusToProcessing)
            .compensation(this::updateStatusToCancelled)
            .build());

        // Step 2: Book risk (local step with risk assessment)
        step("bookRisk", StepOptions.<PaymentStepState, String>builder()
            .action(this::executeBookRisk)
            .maxRetries(3)
            .retryDelay(Duration.ofSeconds(1))
            .compensation(this::releaseRiskBooking)
            .build());

        // Wait for risk approval if decision was PENDING
        if (state.isRiskPending() || state.findWait("awaitRiskApproval").isPresent()) {
            log.info("Risk decision PENDING for payment {} - waiting for manual approval", state.getPaymentId());
            wait("awaitRiskApproval", state::isRiskResolved, Duration.ofSeconds(30));
        }

        // Check for DECLINED risk
        if ("DECLINED".equals(state.getRiskDecision())) {
            log.info("Risk DECLINED for payment {} - triggering compensation", state.getPaymentId());
            throw new StepBusinessRuleException("Risk declined for payment " + state.getPaymentId());
        }

        // Step 3: Book FX via commandStep() - sends command to FX domain
        if (shouldBookFx(state)) {
            Payment payment = loadPayment(state.getPaymentId());
            FxResponse fxResponse = commandStep("bookFx",
                FxRequest.from(payment, state),
                FxResponse.class,
                this::releaseFxBooking);

            // Update state with FX response
            state.setFxContractId(fxResponse.contractId());
            state.setFxRate(fxResponse.rate());
            state.setCreditAmount(fxResponse.convertedAmount());
            log.info("FX booked for payment {}: contract={}, rate={}",
                state.getPaymentId(), fxResponse.contractId(), fxResponse.rate());
        }

        // Step 4: Submit payment via commandStep() - sends command to network domain
        Payment paymentForSubmit = loadPayment(state.getPaymentId());
        SubmitPaymentResponse submitResponse = commandStep("submitPayment",
            SubmitPaymentRequest.from(paymentForSubmit, state),
            SubmitPaymentResponse.class);

        // Update state with submission response
        state.setSubmissionReference(submitResponse.submissionReference());
        state.setSubmissionCommandId(submitResponse.commandId());
        log.info("Payment {} submitted to network with ref {}",
            state.getPaymentId(), submitResponse.submissionReference());

        // Trigger network simulator for L1-L4 confirmations (if configured)
        if (networkSimulator != null) {
            UUID processId = getCurrentProcessId();
            log.info("Triggering network simulator for process {} (payment {})",
                processId, state.getPaymentId());
            networkSimulator.simulatePaymentConfirmations(
                submitResponse.commandId(),
                processId,
                state.getBehavior()
            );
        }

        // Step 5: Wait for L4 confirmation (final settlement)
        wait("awaitL4", () -> state.getL4CompletedAt() != null, Duration.ofSeconds(30));
        if (state.getL4ErrorCode() != null) {
            throw new StepBusinessRuleException("L4 failed: " + state.getL4ErrorCode());
        }

        log.info("Payment {} completed successfully with L4 confirmation", state.getPaymentId());
    }

    // ========== Local Step Implementations ==========

    private Void updateStatusToProcessing(PaymentStepState state) {
        updatePaymentStatus(state.getPaymentId(), PaymentStatus.PROCESSING);
        return null;
    }

    private void updateStatusToCancelled(PaymentStepState state) {
        updatePaymentStatus(state.getPaymentId(), PaymentStatus.CANCELLED);
    }

    private String executeBookRisk(PaymentStepState state) {
        RiskBehavior riskBehavior = state.getBehavior() != null
            ? state.getBehavior().bookRisk()
            : RiskBehavior.defaults();

        double roll = random.nextDouble() * 100;
        RiskBehavior.RiskAssessmentResult result = riskBehavior.assess(roll);

        state.setRiskType(result.type().name());
        state.setRiskDecision(result.decision().name());

        log.info("Risk assessment for payment {}: type={}, decision={}",
            state.getPaymentId(), result.type(), result.decision());

        if (result.isDeclined()) {
            throw new StepBusinessRuleException(
                "Risk assessment declined for payment " + state.getPaymentId());
        }

        if (result.isPending()) {
            createPendingApproval(state);
        }

        String riskRef = "RISK-" + UUID.randomUUID().toString().substring(0, 8);
        state.setRiskReference(riskRef);
        log.info("Risk booking completed for payment {} with ref {}", state.getPaymentId(), riskRef);
        return riskRef;
    }

    private void releaseRiskBooking(PaymentStepState state) {
        log.info("Releasing risk booking {} for payment {}", state.getRiskReference(), state.getPaymentId());
    }

    private boolean shouldBookFx(PaymentStepState state) {
        return state.isFxRequired();
    }

    private void releaseFxBooking(PaymentStepState state) {
        log.info("Releasing FX contract {} for payment {}", state.getFxContractId(), state.getPaymentId());
    }

    private UUID getCurrentProcessId() {
        return currentContext.get().processId();
    }

    private Payment loadPayment(UUID paymentId) {
        return paymentRepository.findById(paymentId, jdbcTemplate)
            .orElseThrow(() -> new IllegalStateException("Payment not found: " + paymentId));
    }

    private void updatePaymentStatus(UUID paymentId, PaymentStatus status) {
        log.debug("Updating payment {} status to {}", paymentId, status);
        paymentRepository.updateStatus(paymentId, status, jdbcTemplate);
        log.info("Payment {} status updated to {}", paymentId, status);
    }

    private void createPendingApproval(PaymentStepState state) {
        UUID processId = getCurrentProcessId();
        UUID paymentId = state.getPaymentId();

        Payment payment = paymentRepository.findById(paymentId, jdbcTemplate)
            .orElseThrow(() -> new IllegalStateException("Payment not found: " + paymentId));

        PendingApproval approval = PendingApproval.create(
            paymentId,
            processId,
            processId,
            UUID.randomUUID(),
            payment.debitAmount(),
            payment.debitCurrency().name(),
            payment.debitAccount().transit() + "-" + payment.debitAccount().accountNumber(),
            payment.creditAccount().bic() + "/" + payment.creditAccount().iban(),
            "COMMAND_STEP"
        );

        pendingApprovalRepository.save(approval);
        log.info("Created pending approval {} for payment {} (process {})",
            approval.id(), paymentId, processId);
    }

    // ========== Async Response Resolution (for L1-L4) ==========

    public void resolveRiskApproval(UUID processId, boolean approved) {
        log.info("Resolving risk approval for process {}: approved={}", processId, approved);

        pendingApprovalRepository.findByProcessId(processId).ifPresent(approval -> {
            PendingApproval.ApprovalStatus status = approved
                ? PendingApproval.ApprovalStatus.APPROVED
                : PendingApproval.ApprovalStatus.REJECTED;
            PendingApproval resolved = approval.withResolution(status, "system", null);
            pendingApprovalRepository.update(resolved);
        });

        processAsyncResponse(processId, state -> {
            if (approved) {
                state.setRiskDecision("APPROVED");
                state.setRiskApprovedAt(Instant.now());
            } else {
                state.setRiskDecision("DECLINED");
            }
        });
    }

    public void confirmL1(UUID processId, String reference) {
        updatePaymentL(processId, 1, reference, null, null);
        processAsyncResponse(processId, state -> state.recordL1Success(reference));
    }

    public void failL1(UUID processId, String errorCode, String errorMessage) {
        processAsyncResponse(processId, state -> state.recordL1Error(errorCode, errorMessage));
    }

    public void confirmL2(UUID processId, String reference) {
        updatePaymentL(processId, 2, reference, null, null);
        processAsyncResponse(processId, state -> state.recordL2Success(reference));
    }

    public void failL2(UUID processId, String errorCode, String errorMessage) {
        processAsyncResponse(processId, state -> state.recordL2Error(errorCode, errorMessage));
    }

    public void confirmL3(UUID processId, String reference) {
        updatePaymentL(processId, 3, reference, null, null);
        processAsyncResponse(processId, state -> state.recordL3Success(reference));
    }

    public void failL3(UUID processId, String errorCode, String errorMessage) {
        processAsyncResponse(processId, state -> state.recordL3Error(errorCode, errorMessage));
    }

    public void confirmL4(UUID processId, String reference) {
        updatePaymentL(processId, 4, reference, PaymentStatus.COMPLETE, null);
        processAsyncResponse(processId, state -> state.recordL4Success(reference));
    }

    public void failL4(UUID processId, String errorCode, String errorMessage) {
        processAsyncResponse(processId, state -> state.recordL4Error(errorCode, errorMessage));
    }

    private void updatePaymentL(UUID processId, int level, String reference,
                                 PaymentStatus newStatus, Instant receivedAt) {
        UUID paymentId = getPaymentIdFromProcess(processId);
        if (paymentId == null) return;

        Instant ts = receivedAt != null ? receivedAt : Instant.now();
        if (newStatus != null) {
            paymentRepository.updateNetworkConfirmationAndStatus(
                paymentId, level, reference, ts, newStatus, jdbcTemplate);
        } else {
            paymentRepository.updateNetworkConfirmation(paymentId, level, reference, ts, jdbcTemplate);
        }
        log.info("Updated Payment {} with L{} confirmation (ref={})", paymentId, level, reference);
    }

    private UUID getPaymentIdFromProcess(UUID processId) {
        try {
            return processRepo.getById(getDomain(), processId, jdbcTemplate)
                .map(process -> {
                    Object state = process.state();
                    if (state instanceof PaymentStepState paymentState) {
                        return paymentState.getPaymentId();
                    }
                    return null;
                })
                .orElse(null);
        } catch (Exception e) {
            log.warn("Failed to get paymentId from process {}: {}", processId, e.getMessage());
            return null;
        }
    }

    // ========== TSQ Callbacks ==========

    @Override
    protected void onProcessCanceled(UUID processId, PaymentStepState state) {
        if (state.getPaymentId() != null) {
            log.info("TSQ cancel: updating payment {} to CANCELLED", state.getPaymentId());
            updatePaymentStatus(state.getPaymentId(), PaymentStatus.CANCELLED);
        }
    }

    @Override
    protected void onProcessCompleted(UUID processId, PaymentStepState state) {
        if (state.getPaymentId() != null) {
            log.info("TSQ complete: updating payment {} to COMPLETE", state.getPaymentId());
            updatePaymentStatus(state.getPaymentId(), PaymentStatus.COMPLETE);
        }
    }

    // ========== Inner Classes for Command Step Request/Response ==========

    /**
     * Request sent to FX domain via commandStep("bookFx").
     */
    public record FxRequest(
        String sourceCurrency,
        String targetCurrency,
        BigDecimal amount,
        String paymentId,
        String expectedOutcome
    ) {
        public static FxRequest from(Payment payment, PaymentStepState state) {
            String expectedOutcome = null;
            if (state.getBehavior() != null && state.getBehavior().bookFx() != null) {
                // Map behavior to expected outcome for handler
                var behavior = state.getBehavior().bookFx();
                expectedOutcome = determineExpectedOutcome(behavior);
            }
            return new FxRequest(
                payment.debitCurrency().name(),
                payment.creditCurrency().name(),
                payment.debitAmount(),
                payment.paymentId() != null ? payment.paymentId().toString() : null,
                expectedOutcome
            );
        }

        private static String determineExpectedOutcome(
                com.ivamare.commandbus.e2e.process.ProbabilisticBehavior behavior) {
            if (behavior == null) return "SUCCESS";
            Random r = new Random();
            double roll = r.nextDouble() * 100;
            if (roll < behavior.failPermanentPct()) return "PERMANENT_FAILURE";
            roll -= behavior.failPermanentPct();
            if (roll < behavior.failTransientPct()) return "TRANSIENT_FAILURE";
            roll -= behavior.failTransientPct();
            if (roll < behavior.failBusinessRulePct()) return "BUSINESS_ERROR";
            roll -= behavior.failBusinessRulePct();
            if (roll < behavior.timeoutPct()) return "TIMEOUT";
            return "SUCCESS";
        }

        public Map<String, Object> toMap() {
            return Map.of(
                "sourceCurrency", sourceCurrency != null ? sourceCurrency : "USD",
                "targetCurrency", targetCurrency != null ? targetCurrency : "EUR",
                "amount", amount != null ? amount : BigDecimal.ZERO,
                "paymentId", paymentId != null ? paymentId : "",
                "expectedOutcome", expectedOutcome != null ? expectedOutcome : "SUCCESS"
            );
        }
    }

    /**
     * Response from FX domain.
     */
    public record FxResponse(
        Long contractId,
        BigDecimal rate,
        BigDecimal convertedAmount,
        String sourceCurrency,
        String targetCurrency
    ) {
        @SuppressWarnings("unchecked")
        public static FxResponse fromMap(Map<String, Object> map) {
            return new FxResponse(
                map.get("contractId") instanceof Number n ? n.longValue() : null,
                parseDecimal(map.get("rate")),
                parseDecimal(map.get("convertedAmount")),
                (String) map.get("sourceCurrency"),
                (String) map.get("targetCurrency")
            );
        }

        private static BigDecimal parseDecimal(Object value) {
            if (value == null) return null;
            if (value instanceof BigDecimal bd) return bd;
            if (value instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
            if (value instanceof String s) return new BigDecimal(s);
            return null;
        }
    }

    /**
     * Request sent to network domain via commandStep("submitPayment").
     */
    public record SubmitPaymentRequest(
        String paymentId,
        String submissionReference,
        BigDecimal amount,
        String currency,
        String expectedOutcome
    ) {
        public static SubmitPaymentRequest from(Payment payment, PaymentStepState state) {
            String expectedOutcome = null;
            if (state.getBehavior() != null && state.getBehavior().submitPayment() != null) {
                var behavior = state.getBehavior().submitPayment();
                expectedOutcome = determineExpectedOutcome(behavior);
            }
            return new SubmitPaymentRequest(
                payment.paymentId() != null ? payment.paymentId().toString() : null,
                state.getSubmissionReference(),
                payment.debitAmount(),
                payment.debitCurrency().name(),
                expectedOutcome
            );
        }

        private static String determineExpectedOutcome(
                com.ivamare.commandbus.e2e.process.ProbabilisticBehavior behavior) {
            if (behavior == null) return "SUCCESS";
            Random r = new Random();
            double roll = r.nextDouble() * 100;
            if (roll < behavior.failPermanentPct()) return "PERMANENT_FAILURE";
            roll -= behavior.failPermanentPct();
            if (roll < behavior.failTransientPct()) return "TRANSIENT_FAILURE";
            roll -= behavior.failTransientPct();
            if (roll < behavior.failBusinessRulePct()) return "BUSINESS_ERROR";
            roll -= behavior.failBusinessRulePct();
            if (roll < behavior.timeoutPct()) return "TIMEOUT";
            return "SUCCESS";
        }

        public Map<String, Object> toMap() {
            return Map.of(
                "paymentId", paymentId != null ? paymentId : "",
                "submissionReference", submissionReference != null ? submissionReference : "",
                "amount", amount != null ? amount : BigDecimal.ZERO,
                "currency", currency != null ? currency : "USD",
                "expectedOutcome", expectedOutcome != null ? expectedOutcome : "SUCCESS"
            );
        }
    }

    /**
     * Response from network domain.
     */
    public record SubmitPaymentResponse(
        String submissionReference,
        UUID commandId,
        String status,
        Instant submittedAt
    ) {
        @SuppressWarnings("unchecked")
        public static SubmitPaymentResponse fromMap(Map<String, Object> map) {
            return new SubmitPaymentResponse(
                (String) map.get("submissionReference"),
                map.get("commandId") != null ? UUID.fromString((String) map.get("commandId")) : null,
                (String) map.get("status"),
                map.get("submittedAt") != null ? Instant.parse((String) map.get("submittedAt")) : null
            );
        }
    }
}
