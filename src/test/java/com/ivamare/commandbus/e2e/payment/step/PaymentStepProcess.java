package com.ivamare.commandbus.e2e.payment.step;

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
 *     step("submitPayment")   // Submit to network
 *     wait("awaitL1")         // Wait for async L1 response
 *     wait("awaitL2")         // Wait for async L2 response
 *     wait("awaitL3")         // Wait for async L3 response
 *     wait("awaitL4")         // Wait for async L4 response with timeout
 * }</pre>
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

    // Network simulator for async responses (optional, set if testing)
    private StepPaymentNetworkSimulator networkSimulator;

    public PaymentStepProcess(
            ProcessRepository processRepo,
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate) {
        super(processRepo, jdbcTemplate, transactionTemplate);
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

        // Step 1: Book risk (with compensation)
        step("bookRisk", StepOptions.<PaymentStepState, String>builder()
            .action(this::executeRiskBooking)
            .maxRetries(3)
            .retryDelay(Duration.ofSeconds(1))
            .compensation(this::releaseRiskBooking)
            .build());

        // Step 2: Book FX if needed (with compensation)
        if (shouldBookFx(state)) {
            step("bookFx", StepOptions.<PaymentStepState, Long>builder()
                .action(this::executeFxBooking)
                .maxRetries(2)
                .retryDelay(Duration.ofMillis(500))
                .compensation(this::releaseFxBooking)
                .build());
        }

        // Step 3: Submit to payment network
        step("submitPayment", StepOptions.<PaymentStepState, String>builder()
            .action(this::executeSubmission)
            .maxRetries(3)
            .retryDelay(Duration.ofSeconds(2))
            .build());

        // Wait for L1 confirmation
        wait("awaitL1", () -> state.getL1CompletedAt() != null, Duration.ofMinutes(30));
        if (state.getL1ErrorCode() != null) {
            throw new StepBusinessRuleException("L1 failed: " + state.getL1ErrorCode());
        }

        // Wait for L2 confirmation
        wait("awaitL2", () -> state.getL2CompletedAt() != null, Duration.ofHours(1));
        if (state.getL2ErrorCode() != null) {
            throw new StepBusinessRuleException("L2 failed: " + state.getL2ErrorCode());
        }

        // Wait for L3 confirmation
        wait("awaitL3", () -> state.getL3CompletedAt() != null, Duration.ofHours(2));
        if (state.getL3ErrorCode() != null) {
            throw new StepBusinessRuleException("L3 failed: " + state.getL3ErrorCode());
        }

        // Wait for L4 confirmation (final settlement)
        wait("awaitL4", () -> state.getL4CompletedAt() != null, Duration.ofHours(4));
        if (state.getL4ErrorCode() != null) {
            throw new StepBusinessRuleException("L4 failed: " + state.getL4ErrorCode());
        }

        log.info("Payment {} completed successfully with all L1-L4 confirmations", state.getPaymentId());
    }

    // ========== Step Implementations ==========

    /**
     * Execute risk booking for the payment.
     * Behavior injection is handled by TestProcessStepManager.
     */
    private String executeRiskBooking(PaymentStepState state) {
        log.debug("Executing risk booking for payment {}", state.getPaymentId());

        // Generate risk reference
        String riskRef = "RISK-" + UUID.randomUUID().toString().substring(0, 8);
        state.setRiskStatus("APPROVED");
        state.setRiskMethod("AVAILABLE_BALANCE");
        state.setRiskReference(riskRef);

        log.info("Risk booking approved for payment {} with ref {}", state.getPaymentId(), riskRef);
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
        if (networkSimulator != null) {
            networkSimulator.simulatePaymentConfirmations(
                commandId,
                state.getPaymentId(),
                state.getBehavior()
            );
        }

        return submissionRef;
    }
}
