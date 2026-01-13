package com.ivamare.commandbus.e2e.payment;

import com.ivamare.commandbus.api.CommandBus;
import com.ivamare.commandbus.model.Reply;
import com.ivamare.commandbus.model.ReplyOutcome;
import com.ivamare.commandbus.process.BaseProcessManager;
import com.ivamare.commandbus.process.ProcessCommand;
import com.ivamare.commandbus.process.ProcessMetadata;
import com.ivamare.commandbus.process.ProcessRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Process manager for payment processing workflow.
 *
 * <p>Workflow: BOOK_RISK → [AWAIT_RISK_APPROVAL] → [BOOK_FX] → SUBMIT_PAYMENT → AWAIT_CONFIRMATIONS
 *
 * <p>The AWAIT_CONFIRMATIONS step accumulates L1-L4 network replies in any order.
 * Process completes when L4 success is received, or cancels on any error.
 *
 * <p>Features:
 * <ul>
 *   <li>Conditional FX booking (only for cross-currency payments)</li>
 *   <li>Out-of-order L1-L4 confirmation handling</li>
 *   <li>Per-level error tracking</li>
 *   <li>Saga-style compensation (UNWIND_RISK, UNWIND_FX)</li>
 * </ul>
 */
@Component
public class PaymentProcessManager
        extends BaseProcessManager<PaymentProcessState, PaymentStep> {

    private static final Logger log = LoggerFactory.getLogger(PaymentProcessManager.class);

    private static final String PROCESS_TYPE = "Payment";
    private static final String DOMAIN = "payments";

    private final PaymentRepository paymentRepository;

    public PaymentProcessManager(
            CommandBus commandBus,
            ProcessRepository processRepo,
            PaymentRepository paymentRepository,
            @Value("${commandbus.process.payment.reply-queue:payments__process_replies}") String replyQueue,
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate) {
        super(commandBus, processRepo, replyQueue, jdbcTemplate, transactionTemplate);
        this.paymentRepository = paymentRepository;
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
    public Class<PaymentProcessState> getStateClass() {
        return PaymentProcessState.class;
    }

    @Override
    public Class<PaymentStep> getStepClass() {
        return PaymentStep.class;
    }

    @Override
    public boolean isWaitOnlyStep(PaymentStep step) {
        return step.isWaitOnly();
    }

    @Override
    @SuppressWarnings("unchecked")
    public PaymentProcessState createInitialState(Map<String, Object> initialData) {
        UUID paymentId = initialData.get("payment_id") != null
            ? UUID.fromString((String) initialData.get("payment_id"))
            : UUID.randomUUID();

        boolean fxRequired = initialData.get("fx_required") != null
            ? (Boolean) initialData.get("fx_required")
            : false;

        PaymentStepBehavior behavior = initialData.get("behavior") != null
            ? PaymentStepBehavior.fromMap((Map<String, Object>) initialData.get("behavior"))
            : PaymentStepBehavior.defaults();

        return PaymentProcessState.builder()
            .paymentId(paymentId)
            .fxRequired(fxRequired)
            .behavior(behavior)
            .build();
    }

    @Override
    public PaymentStep getFirstStep(PaymentProcessState state) {
        return PaymentStep.UPDATE_STATUS_PROCESSING;
    }

    @Override
    public ProcessCommand<?> buildCommand(PaymentStep step, PaymentProcessState state) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("payment_id", state.paymentId().toString());

        switch (step) {
            case UPDATE_STATUS_PROCESSING -> {
                payload.put("target_status", PaymentStatus.PROCESSING.name());
            }
            case UPDATE_STATUS_COMPLETE -> {
                payload.put("target_status", PaymentStatus.COMPLETE.name());
            }
            case UPDATE_STATUS_FAILED -> {
                payload.put("target_status", PaymentStatus.FAILED.name());
            }
            case UPDATE_STATUS_CANCELLED -> {
                payload.put("target_status", PaymentStatus.CANCELLED.name());
            }
            case BOOK_RISK -> {
                // Load payment details for risk assessment
                paymentRepository.findById(state.paymentId()).ifPresent(payment -> {
                    payload.put("debit_account", payment.debitAccount().toMap());
                    payload.put("debit_amount", payment.debitAmount().toString());
                    payload.put("debit_currency", payment.debitCurrency().name());
                });
            }
            case BOOK_FX -> {
                // FX booking needs currency details
                paymentRepository.findById(state.paymentId()).ifPresent(payment -> {
                    payload.put("debit_currency", payment.debitCurrency().name());
                    payload.put("credit_currency", payment.creditCurrency().name());
                    payload.put("debit_amount", payment.debitAmount().toString());
                    payload.put("value_date", payment.valueDate().toString());
                });
            }
            case SUBMIT_PAYMENT -> {
                // Full payment details for submission
                paymentRepository.findById(state.paymentId()).ifPresent(payment -> {
                    payload.put("debit_account", payment.debitAccount().toMap());
                    payload.put("credit_account", payment.creditAccount().toMap());
                    payload.put("debit_amount", payment.debitAmount().toString());
                    payload.put("debit_currency", payment.debitCurrency().name());
                    payload.put("credit_amount", state.creditAmount() != null
                        ? state.creditAmount().toString()
                        : payment.debitAmount().toString());
                    payload.put("credit_currency", payment.creditCurrency().name());
                    payload.put("value_date", payment.valueDate().toString());
                    if (state.fxContractId() != null) {
                        payload.put("fx_contract_id", state.fxContractId());
                        payload.put("fx_rate", state.fxRate().toString());
                    }
                });
            }
            case UNWIND_RISK -> {
                // Reverse risk booking
                payload.put("risk_reference", state.riskReference());
            }
            case UNWIND_FX -> {
                // Reverse FX booking
                payload.put("fx_contract_id", state.fxContractId());
            }
            default -> {
                // Wait-only steps don't build commands
            }
        }

        // Add behavior for this step if configured
        if (state.behavior() != null && step.getCommandType() != null) {
            payload.put("behavior", state.behavior().forStep(step).toMap());
            // Special handling for risk behavior (includes approval method details)
            if (step == PaymentStep.BOOK_RISK) {
                payload.put("risk_behavior", state.behavior().bookRisk().toMap());
            }
            // SUBMIT_PAYMENT needs full behavior for L1-L4 network simulation
            if (step == PaymentStep.SUBMIT_PAYMENT) {
                payload.put("step_behavior", state.behavior().toMap());
            }
        }

        log.debug("Building command for step {} with payload: {}", step, payload);
        return new ProcessCommand<>(step.getCommandType(), payload);
    }

    @Override
    protected void beforeSendCommand(
            ProcessMetadata<PaymentProcessState, PaymentStep> process,
            PaymentStep step,
            UUID commandId,
            Map<String, Object> commandPayload,
            JdbcTemplate jdbc) {
        // Store the submission command ID for reply routing in wait-only steps
        if (step == PaymentStep.SUBMIT_PAYMENT) {
            log.debug("Storing submission command ID {} for process {}", commandId, process.processId());
            // The command ID is stored via state update in updateState()
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public PaymentProcessState updateState(PaymentProcessState state, PaymentStep step, Reply reply) {
        Map<String, Object> data = reply.data();
        if (data == null) {
            return state;
        }

        // Check for L1-L4 confirmations FIRST - they can arrive while still on SUBMIT_PAYMENT step
        // due to race conditions (network simulator sends confirmations immediately after SUBMIT)
        if (data.containsKey("level")) {
            return handleConfirmation(state, reply, data);
        }

        return switch (step) {
            case BOOK_RISK -> {
                String status = (String) data.get("status");
                String method = (String) data.get("method");
                String reference = (String) data.get("risk_reference");
                yield state.withRiskApproval(status, method, reference);
            }
            case AWAIT_RISK_APPROVAL -> {
                // Operator approved the risk - clear pending flag
                yield state.withApprovalCleared();
            }
            case BOOK_FX -> {
                Long contractId = data.get("fx_contract_id") != null
                    ? ((Number) data.get("fx_contract_id")).longValue() : null;
                BigDecimal rate = data.get("fx_rate") != null
                    ? new BigDecimal(data.get("fx_rate").toString()) : null;
                BigDecimal creditAmount = data.get("credit_amount") != null
                    ? new BigDecimal(data.get("credit_amount").toString()) : null;
                yield state.withFxResult(contractId, rate, creditAmount);
            }
            case SUBMIT_PAYMENT -> {
                String reference = (String) data.get("submission_reference");
                UUID commandId = data.get("command_id") != null
                    ? UUID.fromString((String) data.get("command_id")) : reply.commandId();
                yield state.withSubmissionResult(reference, commandId);
            }
            case AWAIT_CONFIRMATIONS -> state;  // L1-L4 handled above
            case UNWIND_RISK, UNWIND_FX -> state;  // No state changes for compensation
            // Status update steps don't change process state
            case UPDATE_STATUS_PROCESSING, UPDATE_STATUS_COMPLETE,
                 UPDATE_STATUS_FAILED, UPDATE_STATUS_CANCELLED -> state;
        };
    }

    /**
     * Handle L1-L4 confirmation replies. Extracted to handle confirmations regardless of current step.
     */
    private PaymentProcessState handleConfirmation(PaymentProcessState state, Reply reply, Map<String, Object> data) {
        int level = data.get("level") != null
            ? ((Number) data.get("level")).intValue() : 0;
        boolean isError = reply.outcome() == ReplyOutcome.FAILED;

        if (isError) {
            String errorCode = reply.errorCode();
            String errorMessage = reply.errorMessage();
            log.info("L{} FAILED for process {} - code: {}, message: {}",
                level, state.paymentId(), errorCode, errorMessage);
            return switch (level) {
                case 1 -> state.withL1Error(errorCode, errorMessage);
                case 2 -> state.withL2Error(errorCode, errorMessage);
                case 3 -> state.withL3Error(errorCode, errorMessage);
                case 4 -> state.withL4Error(errorCode, errorMessage);
                default -> state;
            };
        } else {
            String reference = (String) data.get("reference");
            log.info("L{} SUCCESS for process {} - reference: {}",
                level, state.paymentId(), reference);
            return switch (level) {
                case 1 -> state.withL1Success(reference);
                case 2 -> state.withL2Success(reference);
                case 3 -> state.withL3Success(reference);
                case 4 -> state.withL4Success(reference);
                default -> state;
            };
        }
    }

    @Override
    public PaymentStep getNextStep(PaymentStep currentStep, Reply reply, PaymentProcessState state) {
        return switch (currentStep) {
            case UPDATE_STATUS_PROCESSING -> PaymentStep.BOOK_RISK;
            case BOOK_RISK -> {
                // Check if risk was declined (handled elsewhere via business rule failure)
                String status = reply.data() != null ? (String) reply.data().get("status") : null;
                if ("DECLINED".equals(status)) {
                    yield PaymentStep.UPDATE_STATUS_FAILED;  // Mark failed after declined
                }
                // Check if manual approval is pending
                if ("PENDING_APPROVAL".equals(status)) {
                    yield PaymentStep.AWAIT_RISK_APPROVAL;
                }
                // If FX required, go to BOOK_FX, otherwise skip to SUBMIT_PAYMENT
                yield state.fxRequired() ? PaymentStep.BOOK_FX : PaymentStep.SUBMIT_PAYMENT;
            }
            case AWAIT_RISK_APPROVAL -> {
                // After approval, continue to FX or SUBMIT_PAYMENT
                yield state.fxRequired() ? PaymentStep.BOOK_FX : PaymentStep.SUBMIT_PAYMENT;
            }
            case BOOK_FX -> PaymentStep.SUBMIT_PAYMENT;
            case SUBMIT_PAYMENT -> {
                // L1-L4 can arrive before SUBMIT reply due to race conditions.
                // Check if already complete or has errors.
                if (state.hasAnyError()) {
                    log.info("Process {} has error during SUBMIT, will be cancelled", state.paymentId());
                    yield PaymentStep.UPDATE_STATUS_CANCELLED;
                }
                if (state.isL4Success()) {
                    log.info("Process {} L4 already received during SUBMIT, completing", state.paymentId());
                    yield PaymentStep.UPDATE_STATUS_COMPLETE;
                }
                yield PaymentStep.AWAIT_CONFIRMATIONS;
            }
            case AWAIT_CONFIRMATIONS -> {
                // Check if any error occurred - process should be cancelled
                if (state.hasAnyError()) {
                    log.info("Process {} has error, will be cancelled", state.paymentId());
                    yield PaymentStep.UPDATE_STATUS_CANCELLED;
                }
                // Check if L4 success received - process is complete
                if (state.isL4Success()) {
                    log.info("Process {} L4 success, completing. Levels received: {}",
                        state.paymentId(), state.receivedLevelCount());
                    yield PaymentStep.UPDATE_STATUS_COMPLETE;
                }
                // Keep waiting for more confirmations
                log.debug("Process {} waiting for confirmations. Levels received: {}",
                    state.paymentId(), state.receivedLevelCount());
                yield PaymentStep.AWAIT_CONFIRMATIONS;
            }
            case UNWIND_FX -> PaymentStep.UNWIND_RISK;  // Continue compensation
            case UNWIND_RISK -> PaymentStep.UPDATE_STATUS_CANCELLED;  // Mark cancelled after compensation
            // Terminal status steps - process ends after these
            case UPDATE_STATUS_COMPLETE, UPDATE_STATUS_FAILED, UPDATE_STATUS_CANCELLED -> null;
        };
    }

    @Override
    public PaymentStep getCompensationStep(PaymentStep step) {
        return switch (step) {
            case BOOK_RISK -> PaymentStep.UNWIND_RISK;
            case BOOK_FX -> PaymentStep.UNWIND_FX;
            default -> null;  // Other steps don't have compensation
        };
    }

    /**
     * Start a payment process from a Payment entity.
     */
    public UUID startPayment(Payment payment, PaymentStepBehavior behavior) {
        Map<String, Object> initialData = new HashMap<>();
        initialData.put("payment_id", payment.paymentId().toString());
        initialData.put("fx_required", payment.requiresFx());
        if (behavior != null) {
            initialData.put("behavior", behavior.toMap());
        }
        return start(initialData);
    }

    /**
     * Start payment processes for multiple payments in a single batch operation.
     * This is significantly faster than calling startPayment() for each payment individually.
     *
     * @param payments List of payments to start processes for
     * @param behavior Optional behavior configuration applied to all payments
     * @return List of process IDs, in same order as input payments
     */
    public List<UUID> startPaymentBatch(List<Payment> payments, PaymentStepBehavior behavior) {
        List<Map<String, Object>> initialDataList = new ArrayList<>(payments.size());

        for (Payment payment : payments) {
            Map<String, Object> initialData = new HashMap<>();
            initialData.put("payment_id", payment.paymentId().toString());
            initialData.put("fx_required", payment.requiresFx());
            if (behavior != null) {
                initialData.put("behavior", behavior.toMap());
            }
            initialDataList.add(initialData);
        }

        return startBatch(initialDataList);
    }
}
