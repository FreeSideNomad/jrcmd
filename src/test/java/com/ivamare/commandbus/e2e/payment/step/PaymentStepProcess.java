package com.ivamare.commandbus.e2e.payment.step;

import com.ivamare.commandbus.e2e.payment.Payment;
import com.ivamare.commandbus.e2e.payment.PaymentRepository;
import com.ivamare.commandbus.e2e.payment.PaymentStatus;
import com.ivamare.commandbus.e2e.payment.PendingApproval;
import com.ivamare.commandbus.e2e.payment.PendingApprovalRepository;
import com.ivamare.commandbus.e2e.payment.RiskBehavior;
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
import java.util.Random;
import java.util.UUID;

/**
 * Payment processing workflow using ProcessStepManager.
 *
 * <p>Demonstrates the Temporal.io-inspired approach where workflows are written
 * as sequential code with deterministic replay support:
 *
 * <pre>{@code
 * execute(state):
 *     step("bookRisk")        // Compensable step
 *     step("bookFx")          // Optional step with compensation
 *     step("submitPayment")   // Submit to network (triggers L1-L4 confirmations)
 *     wait("awaitL4")         // Wait only for L4 (settlement) - L1-L3 arrive async
 * }</pre>
 *
 * <p>Network confirmations (L1-L4):
 * <ul>
 *   <li>L1-L4 confirmations arrive asynchronously in potentially random order</li>
 *   <li>L1-L3 update both process state and Payment entity when received</li>
 *   <li>L4 (settlement) is the completion trigger - payment status becomes COMPLETE</li>
 *   <li>L4 failure triggers compensation regardless of L1-L3 status</li>
 *   <li>L1-L3 can still arrive after L4 and will update Payment entity</li>
 * </ul>
 *
 * <p>Each step automatically:
 * <ul>
 *   <li>Records execution for replay (deterministic re-execution)</li>
 *   <li>Handles retries with exponential backoff</li>
 *   <li>Registers compensation for saga rollback</li>
 *   <li>Moves to TSQ on permanent failure</li>
 * </ul>
 */
public class PaymentStepProcess extends TestProcessStepManager<PaymentStepState> {

    private static final Logger log = LoggerFactory.getLogger(PaymentStepProcess.class);

    private static final String DOMAIN = "payments";
    private static final String PROCESS_TYPE = "PAYMENT_STEP";

    // Services for step execution (simulated)
    private final Random random = new Random();

    // Payment repository for status updates and payment lookup
    private final PaymentRepository paymentRepository;

    // Pending approval repository for manual review queue
    private final PendingApprovalRepository pendingApprovalRepository;

    // Network simulator for async responses (optional, set if testing)
    private StepPaymentNetworkSimulator networkSimulator;

    public PaymentStepProcess(
            ProcessRepository processRepo,
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            PaymentRepository paymentRepository,
            PendingApprovalRepository pendingApprovalRepository) {
        super(processRepo, jdbcTemplate, transactionTemplate);
        this.paymentRepository = paymentRepository;
        this.pendingApprovalRepository = pendingApprovalRepository;
    }

    /**
     * Set the network simulator for async response testing.
     */
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
        return Duration.ofHours(4);  // Default 4 hour timeout for network confirmations
    }

    @Override
    protected DeadlineAction getDeadlineAction() {
        return DeadlineAction.TSQ;  // Move to TSQ on deadline exceeded
    }

    @Override
    protected ExceptionType classifyException(Exception e) {
        if (e instanceof StepBusinessRuleException) {
            return ExceptionType.BUSINESS;
        }
        // Transient for network/database errors
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
        // Behavior injection is now handled automatically by TestProcessStepManager
        // based on state.getBehaviorForStep(stepName) which delegates to PaymentStepBehavior

        // Step 1: Update payment status to PROCESSING (with compensation to CANCELLED)
        step("updateStatusProcessing", StepOptions.<PaymentStepState, Void>builder()
            .action(this::updateStatusToProcessing)
            .compensation(this::updateStatusToCancelled)
            .build());

        // Step 2: Book risk (with compensation)
        // Records riskType and riskDecision in state
        // DECLINED → throws StepBusinessRuleException (triggers compensation)
        // PENDING → creates pending_approval record, then we wait for approval
        // APPROVED → continues to next step
        step("bookRisk", StepOptions.<PaymentStepState, String>builder()
            .action(this::executeBookRisk)
            .maxRetries(3)
            .retryDelay(Duration.ofSeconds(1))
            .compensation(this::releaseRiskBooking)
            .build());

        // Wait for risk approval if decision was PENDING
        // Check both current state AND if we previously entered this wait (for replay after state mutation)
        if (state.isRiskPending() || state.findWait("awaitRiskApproval").isPresent()) {
            log.info("Risk decision PENDING for payment {} - waiting for manual approval", state.getPaymentId());
            // Use isRiskResolved() which checks riskApprovedAt OR riskDecision=="DECLINED"
            wait("awaitRiskApproval", state::isRiskResolved, Duration.ofSeconds(30));
        }

        // Check for DECLINED risk - either from immediate decline in bookRisk,
        // or from manual decline after PENDING approval.
        // StepBusinessRuleException triggers compensations at execute level (not just step level)
        if ("DECLINED".equals(state.getRiskDecision())) {
            log.info("Risk DECLINED for payment {} - triggering compensation", state.getPaymentId());
            throw new StepBusinessRuleException(
                "Risk declined for payment " + state.getPaymentId());
        }

        // Step 3: Book FX if needed (with compensation)
        if (shouldBookFx(state)) {
            step("bookFx", StepOptions.<PaymentStepState, Long>builder()
                .action(this::executeFxBooking)
                .maxRetries(2)
                .retryDelay(Duration.ofMillis(500))
                .compensation(this::releaseFxBooking)
                .build());
        }

        // Step 4: Submit to payment network
        // This triggers the network simulator to start sending L1-L4 confirmations
        // in potentially random order. L1-L3 update Payment entity directly.
        step("submitPayment", StepOptions.<PaymentStepState, String>builder()
            .action(this::executeSubmission)
            .maxRetries(3)
            .retryDelay(Duration.ofSeconds(2))
            .build());

        // Wait for L4 confirmation only (final settlement)
        // L1-L3 confirmations arrive asynchronously and update Payment entity directly.
        // L4 is the completion trigger - when it arrives, Payment status becomes COMPLETE.
        wait("awaitL4", () -> state.getL4CompletedAt() != null, Duration.ofSeconds(30));
        if (state.getL4ErrorCode() != null) {
            // L4 failure triggers compensation regardless of L1-L3 status
            throw new StepBusinessRuleException("L4 failed: " + state.getL4ErrorCode());
        }

        // Payment status is already updated to COMPLETE by confirmL4()
        log.info("Payment {} completed successfully with L4 confirmation (L1-L3 may still be pending)",
            state.getPaymentId());
    }

    // ========== Step Implementations ==========

    /**
     * Update payment status to PROCESSING.
     */
    private Void updateStatusToProcessing(PaymentStepState state) {
        updatePaymentStatus(state.getPaymentId(), PaymentStatus.PROCESSING);
        return null;
    }

    /**
     * Update payment status to CANCELLED (compensation).
     */
    private void updateStatusToCancelled(PaymentStepState state) {
        updatePaymentStatus(state.getPaymentId(), PaymentStatus.CANCELLED);
    }

    /**
     * Execute risk booking with assessment.
     * Records riskType and riskDecision in state.
     * DECLINED → throws StepBusinessRuleException (triggers compensation)
     * PENDING → creates pending_approval record for UI
     * APPROVED → returns risk reference
     */
    private String executeBookRisk(PaymentStepState state) {
        RiskBehavior riskBehavior = state.getBehavior() != null
            ? state.getBehavior().bookRisk()
            : RiskBehavior.defaults();

        // Assess risk and record result
        double roll = random.nextDouble() * 100;
        RiskBehavior.RiskAssessmentResult result = riskBehavior.assess(roll);

        state.setRiskType(result.type().name());
        state.setRiskDecision(result.decision().name());

        log.info("Risk assessment for payment {}: type={}, decision={}",
            state.getPaymentId(), result.type(), result.decision());

        if (result.isDeclined()) {
            // DECLINED - throw business exception to trigger compensation
            log.info("Risk check DECLINED for payment {} - triggering compensation",
                state.getPaymentId());
            throw new StepBusinessRuleException(
                "Risk assessment declined for payment " + state.getPaymentId());
        }

        // For PENDING, create pending approval record for UI
        if (result.isPending()) {
            createPendingApproval(state);
        }

        // For APPROVED or PENDING, execute the risk booking
        return executeRiskBooking(state);
    }

    /**
     * Execute risk booking for the payment.
     * Risk type and decision are already set by executeBookRisk.
     */
    private String executeRiskBooking(PaymentStepState state) {
        log.debug("Executing risk booking for payment {} (type={}, decision={})",
            state.getPaymentId(), state.getRiskType(), state.getRiskDecision());

        // Generate risk reference
        String riskRef = "RISK-" + UUID.randomUUID().toString().substring(0, 8);
        state.setRiskReference(riskRef);

        log.info("Risk booking completed for payment {} with ref {} (decision={})",
            state.getPaymentId(), riskRef, state.getRiskDecision());
        return riskRef;
    }

    private void releaseRiskBooking(PaymentStepState state) {
        log.info("Releasing risk booking {} for payment {}", state.getRiskReference(), state.getPaymentId());
        // In production, this would call the risk service to release the booking
    }

    private boolean shouldBookFx(PaymentStepState state) {
        // In production, check if currencies differ
        return state.isFxRequired();
    }

    /**
     * Execute FX booking for the payment.
     * Behavior injection is handled by TestProcessStepManager.
     */
    private Long executeFxBooking(PaymentStepState state) {
        log.debug("Executing FX booking for payment {}", state.getPaymentId());

        // Generate FX contract
        Long contractId = Math.abs(random.nextLong()) % 1000000;
        BigDecimal rate = new BigDecimal("1.2345");
        BigDecimal creditAmount = new BigDecimal("1234.56");

        state.setFxContractId(contractId);
        state.setFxRate(rate);
        state.setCreditAmount(creditAmount);

        log.info("FX booking completed for payment {} with contract {}", state.getPaymentId(), contractId);
        return contractId;
    }

    private void releaseFxBooking(PaymentStepState state) {
        log.info("Releasing FX contract {} for payment {}", state.getFxContractId(), state.getPaymentId());
        // In production, this would call the FX service to release the contract
    }

    /**
     * Submit payment to the network.
     * Behavior injection is handled by TestProcessStepManager.
     */
    private String executeSubmission(PaymentStepState state) {
        log.debug("Submitting payment {} to network", state.getPaymentId());

        // Generate submission reference
        String submissionRef = "NET-" + UUID.randomUUID().toString().substring(0, 8);
        UUID commandId = UUID.randomUUID();

        state.setSubmissionReference(submissionRef);
        state.setSubmissionCommandId(commandId);

        log.info("Payment {} submitted to network with ref {}", state.getPaymentId(), submissionRef);

        // Trigger network simulator to start sending async responses
        // Use the actual process ID from execution context, not the payment ID
        if (networkSimulator != null) {
            UUID processId = getCurrentProcessId();
            log.info("Triggering network simulator for process {} (payment {})", processId, state.getPaymentId());
            networkSimulator.simulatePaymentConfirmations(
                commandId,
                processId,  // Use actual process ID, not payment ID
                state.getBehavior()
            );
        }

        return submissionRef;
    }

    /**
     * Get the current process ID from execution context.
     */
    private UUID getCurrentProcessId() {
        return currentContext.get().processId();
    }

    /**
     * Update payment status in the database.
     */
    private void updatePaymentStatus(UUID paymentId, PaymentStatus status) {
        log.debug("Updating payment {} status to {}", paymentId, status);
        paymentRepository.updateStatus(paymentId, status, jdbcTemplate);
        log.info("Payment {} status updated to {}", paymentId, status);
    }

    // ========== Pending Approval Management ==========

    /**
     * Create a pending approval record for the manual review queue.
     * Called when risk decision is PENDING.
     */
    private void createPendingApproval(PaymentStepState state) {
        UUID processId = getCurrentProcessId();
        UUID paymentId = state.getPaymentId();

        // Fetch payment details for display in approval queue
        Payment payment = paymentRepository.findById(paymentId, jdbcTemplate)
            .orElseThrow(() -> new IllegalStateException("Payment not found: " + paymentId));

        PendingApproval approval = PendingApproval.create(
            paymentId,
            processId,
            processId,  // correlationId - use processId for PROCESS_STEP model
            UUID.randomUUID(),  // commandId - generate new one since no command
            payment.debitAmount(),
            payment.debitCurrency().name(),
            payment.debitAccount().transit() + "-" + payment.debitAccount().accountNumber(),
            payment.creditAccount().bic() + "/" + payment.creditAccount().iban(),
            "PROCESS_STEP"
        );

        pendingApprovalRepository.save(approval);
        log.info("Created pending approval {} for payment {} (process {})",
            approval.id(), paymentId, processId);
    }

    // ========== Risk Approval Resolution ==========

    /**
     * Resolve a pending risk approval from the UI.
     * Called when an operator approves or declines a payment in the manual approval queue.
     *
     * @param processId The process ID waiting for risk approval
     * @param approved True if approved, false if declined
     */
    public void resolveRiskApproval(UUID processId, boolean approved) {
        log.info("Resolving risk approval for process {}: approved={}", processId, approved);

        // Update the pending_approval record
        pendingApprovalRepository.findByProcessId(processId).ifPresent(approval -> {
            PendingApproval.ApprovalStatus status = approved
                ? PendingApproval.ApprovalStatus.APPROVED
                : PendingApproval.ApprovalStatus.REJECTED;
            PendingApproval resolved = approval.withResolution(status, "system", null);
            pendingApprovalRepository.update(resolved);
            log.info("Updated pending approval {} to {} for process {}", approval.id(), status, processId);
        });

        processAsyncResponse(processId, state -> {
            if (approved) {
                state.setRiskDecision("APPROVED");
                state.setRiskApprovedAt(Instant.now());
                log.info("Risk approval granted for process {} (payment {})", processId, state.getPaymentId());
            } else {
                state.setRiskDecision("DECLINED");
                log.info("Risk approval declined for process {} (payment {})", processId, state.getPaymentId());
            }
        });
    }

    // ========== Network Response Resolution ==========

    /**
     * Resolve a network response (L1-L4) from the UI.
     * Called when an operator manually approves or rejects a network confirmation.
     *
     * @param processId The process ID waiting for network response
     * @param level The confirmation level (1-4)
     * @param success True if approved, false if rejected
     * @param reason Rejection reason (only used if success=false)
     */
    public void resolveNetworkResponse(UUID processId, int level, boolean success, String reason) {
        log.info("Resolving L{} network response for process {}: success={}", level, processId, success);

        processAsyncResponse(processId, state -> {
            String reference = success ? "MANUAL-" + UUID.randomUUID().toString().substring(0, 8) : null;
            String errorCode = success ? null : "OPERATOR_REJECTED";
            String errorMessage = success ? null : reason;

            switch (level) {
                case 1 -> {
                    if (success) {
                        state.recordL1Success(reference);
                    } else {
                        state.recordL1Error(errorCode, errorMessage);
                    }
                }
                case 2 -> {
                    if (success) {
                        state.recordL2Success(reference);
                    } else {
                        state.recordL2Error(errorCode, errorMessage);
                    }
                }
                case 3 -> {
                    if (success) {
                        state.recordL3Success(reference);
                    } else {
                        state.recordL3Error(errorCode, errorMessage);
                    }
                }
                case 4 -> {
                    if (success) {
                        state.recordL4Success(reference);
                    } else {
                        state.recordL4Error(errorCode, errorMessage);
                    }
                }
                default -> log.warn("Unknown level {} for process {}", level, processId);
            }

            log.info("L{} {} for process {} (payment {})",
                level, success ? "approved" : "rejected", processId, state.getPaymentId());
        });
    }

    // ========== Simple Network Confirmation Methods ==========

    /**
     * Confirm L1 (acknowledgment) for a payment process.
     * Updates both process state and Payment entity.
     *
     * @param processId The process ID
     * @param reference The L1 reference from the network
     */
    public void confirmL1(UUID processId, String reference) {
        Instant receivedAt = Instant.now();

        // Update Payment entity (may arrive after process completes)
        UUID paymentId = getPaymentIdFromProcess(processId);
        if (paymentId != null) {
            paymentRepository.updateNetworkConfirmation(paymentId, 1, reference, receivedAt, jdbcTemplate);
            log.info("Updated Payment {} with L1 confirmation (ref={})", paymentId, reference);
        }

        // Update process state
        processAsyncResponse(processId, state -> state.recordL1Success(reference));
    }

    /**
     * Report L1 failure for a payment process.
     *
     * @param processId The process ID
     * @param errorCode The error code
     * @param errorMessage The error message
     */
    public void failL1(UUID processId, String errorCode, String errorMessage) {
        processAsyncResponse(processId, state -> state.recordL1Error(errorCode, errorMessage));
    }

    /**
     * Confirm L2 (validation) for a payment process.
     * Updates both process state and Payment entity.
     *
     * @param processId The process ID
     * @param reference The L2 reference from the network
     */
    public void confirmL2(UUID processId, String reference) {
        Instant receivedAt = Instant.now();

        // Update Payment entity (may arrive after process completes)
        UUID paymentId = getPaymentIdFromProcess(processId);
        if (paymentId != null) {
            paymentRepository.updateNetworkConfirmation(paymentId, 2, reference, receivedAt, jdbcTemplate);
            log.info("Updated Payment {} with L2 confirmation (ref={})", paymentId, reference);
        }

        // Update process state
        processAsyncResponse(processId, state -> state.recordL2Success(reference));
    }

    /**
     * Report L2 failure for a payment process.
     */
    public void failL2(UUID processId, String errorCode, String errorMessage) {
        processAsyncResponse(processId, state -> state.recordL2Error(errorCode, errorMessage));
    }

    /**
     * Confirm L3 (clearing) for a payment process.
     * Updates both process state and Payment entity.
     *
     * @param processId The process ID
     * @param reference The L3 reference from the network
     */
    public void confirmL3(UUID processId, String reference) {
        Instant receivedAt = Instant.now();

        // Update Payment entity (may arrive after process completes)
        UUID paymentId = getPaymentIdFromProcess(processId);
        if (paymentId != null) {
            paymentRepository.updateNetworkConfirmation(paymentId, 3, reference, receivedAt, jdbcTemplate);
            log.info("Updated Payment {} with L3 confirmation (ref={})", paymentId, reference);
        }

        // Update process state
        processAsyncResponse(processId, state -> state.recordL3Success(reference));
    }

    /**
     * Report L3 failure for a payment process.
     */
    public void failL3(UUID processId, String errorCode, String errorMessage) {
        processAsyncResponse(processId, state -> state.recordL3Error(errorCode, errorMessage));
    }

    /**
     * Confirm L4 (settlement) for a payment process.
     * Updates both process state and Payment entity.
     * L4 confirmation triggers payment completion (status → COMPLETE).
     *
     * @param processId The process ID
     * @param reference The L4 reference from the network
     */
    public void confirmL4(UUID processId, String reference) {
        Instant receivedAt = Instant.now();

        // Update Payment entity with L4 confirmation and status COMPLETE
        UUID paymentId = getPaymentIdFromProcess(processId);
        if (paymentId != null) {
            paymentRepository.updateNetworkConfirmationAndStatus(
                paymentId, 4, reference, receivedAt, PaymentStatus.COMPLETE, jdbcTemplate);
            log.info("Updated Payment {} with L4 confirmation (ref={}) - status COMPLETE", paymentId, reference);
        }

        // Update process state
        processAsyncResponse(processId, state -> state.recordL4Success(reference));
    }

    /**
     * Report L4 failure for a payment process.
     */
    public void failL4(UUID processId, String errorCode, String errorMessage) {
        processAsyncResponse(processId, state -> state.recordL4Error(errorCode, errorMessage));
    }

    /**
     * Get the paymentId from a process by loading its state.
     * Returns null if process not found or state doesn't have paymentId.
     */
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

    /**
     * Called when process is canceled via TSQ.
     * Updates payment status to CANCELLED.
     */
    @Override
    protected void onProcessCanceled(UUID processId, PaymentStepState state) {
        if (state.getPaymentId() != null) {
            log.info("TSQ cancel: updating payment {} to CANCELLED", state.getPaymentId());
            updatePaymentStatus(state.getPaymentId(), PaymentStatus.CANCELLED);
        }
    }

    /**
     * Called when process is completed via TSQ override.
     * Updates payment status to COMPLETE.
     */
    @Override
    protected void onProcessCompleted(UUID processId, PaymentStepState state) {
        if (state.getPaymentId() != null) {
            log.info("TSQ complete: updating payment {} to COMPLETE", state.getPaymentId());
            updatePaymentStatus(state.getPaymentId(), PaymentStatus.COMPLETE);
        }
    }
}
