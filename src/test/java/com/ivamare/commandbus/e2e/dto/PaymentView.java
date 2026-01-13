package com.ivamare.commandbus.e2e.dto;

import com.ivamare.commandbus.e2e.payment.*;
import com.ivamare.commandbus.process.MapProcessState;
import com.ivamare.commandbus.process.ProcessMetadata;
import com.ivamare.commandbus.process.ProcessStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * View DTO for displaying payment information.
 */
public record PaymentView(
    UUID paymentId,
    LocalDate actionDate,
    LocalDate valueDate,
    Currency debitCurrency,
    Currency creditCurrency,
    String debitAccount,
    String creditAccount,
    BigDecimal debitAmount,
    BigDecimal creditAmount,
    Long fxContractId,
    BigDecimal fxRate,
    PaymentStatus paymentStatus,
    Instant cutoffTimestamp,
    Instant createdAt,
    Instant updatedAt,
    // Process info
    UUID processId,
    ProcessStatus processStatus,
    String currentStep,
    int completedLevel,
    // L1-L4 confirmation status
    LevelStatus l1Status,
    LevelStatus l2Status,
    LevelStatus l3Status,
    LevelStatus l4Status
) {

    /**
     * Status of a network confirmation level.
     */
    public record LevelStatus(
        Instant completedAt,
        String reference,
        String errorCode,
        String errorMessage
    ) {
        public boolean isComplete() {
            return completedAt != null;
        }

        public boolean isSuccess() {
            return completedAt != null && errorCode == null;
        }

        public boolean isError() {
            return completedAt != null && errorCode != null;
        }

        public boolean isPending() {
            return completedAt == null;
        }
    }
    /**
     * Create view from Payment and optional ProcessMetadata.
     */
    public static PaymentView from(Payment payment, ProcessMetadata<?, ?> process) {
        UUID processId = null;
        ProcessStatus processStatus = null;
        String currentStep = null;
        int completedLevel = 0;
        LevelStatus l1Status = null;
        LevelStatus l2Status = null;
        LevelStatus l3Status = null;
        LevelStatus l4Status = null;

        // FX data - start with payment values, overlay from process state if available
        BigDecimal creditAmount = payment.creditAmount();
        Long fxContractId = payment.fxContractId();
        BigDecimal fxRate = payment.fxRate();

        if (process != null) {
            processId = process.processId();
            processStatus = process.status();

            // Extract current step - check currentStep field first, then state map
            if (process.currentStep() != null) {
                currentStep = process.currentStep().toString();
            } else if (process.state() instanceof MapProcessState mapState) {
                // JdbcProcessRepository stores current step in __current_step__ key
                Map<String, Object> stateMap = mapState.toMap();
                currentStep = (String) stateMap.get("__current_step__");
            }

            // Extract state data
            if (process.state() instanceof PaymentProcessState state) {
                completedLevel = state.completedLevel();
                l1Status = new LevelStatus(state.l1CompletedAt(), state.l1Reference(), state.l1ErrorCode(), state.l1ErrorMessage());
                l2Status = new LevelStatus(state.l2CompletedAt(), state.l2Reference(), state.l2ErrorCode(), state.l2ErrorMessage());
                l3Status = new LevelStatus(state.l3CompletedAt(), state.l3Reference(), state.l3ErrorCode(), state.l3ErrorMessage());
                l4Status = new LevelStatus(state.l4CompletedAt(), state.l4Reference(), state.l4ErrorCode(), state.l4ErrorMessage());
                // FX data from process state
                if (state.fxContractId() != null) {
                    fxContractId = state.fxContractId();
                }
                if (state.fxRate() != null) {
                    fxRate = state.fxRate();
                }
                if (state.creditAmount() != null) {
                    creditAmount = state.creditAmount();
                }
            } else if (process.state() instanceof MapProcessState mapState) {
                // Extract from map when state is not deserialized
                Map<String, Object> stateMap = mapState.toMap();
                Object level = stateMap.get("completedLevel");
                if (level instanceof Number) {
                    completedLevel = ((Number) level).intValue();
                }
                // Extract L1-L4 status from map
                l1Status = extractLevelStatus(stateMap, "l1");
                l2Status = extractLevelStatus(stateMap, "l2");
                l3Status = extractLevelStatus(stateMap, "l3");
                l4Status = extractLevelStatus(stateMap, "l4");
                // FX data from map
                if (stateMap.get("fx_contract_id") != null) {
                    fxContractId = ((Number) stateMap.get("fx_contract_id")).longValue();
                }
                if (stateMap.get("fx_rate") != null) {
                    Object rateObj = stateMap.get("fx_rate");
                    fxRate = rateObj instanceof String ? new BigDecimal((String) rateObj) : new BigDecimal(rateObj.toString());
                }
                if (stateMap.get("credit_amount") != null) {
                    Object amtObj = stateMap.get("credit_amount");
                    creditAmount = amtObj instanceof String ? new BigDecimal((String) amtObj) : new BigDecimal(amtObj.toString());
                }
            }
        }

        return new PaymentView(
            payment.paymentId(),
            payment.actionDate(),
            payment.valueDate(),
            payment.debitCurrency(),
            payment.creditCurrency(),
            payment.debitAccount().toString(),
            payment.creditAccount().toString(),
            payment.debitAmount(),
            creditAmount,
            fxContractId,
            fxRate,
            payment.status(),
            payment.cutoffTimestamp(),
            payment.createdAt(),
            payment.updatedAt(),
            processId,
            processStatus,
            currentStep,
            completedLevel,
            l1Status,
            l2Status,
            l3Status,
            l4Status
        );
    }

    private static LevelStatus extractLevelStatus(Map<String, Object> stateMap, String prefix) {
        String completedAtStr = (String) stateMap.get(prefix + "_completed_at");
        Instant completedAt = completedAtStr != null ? Instant.parse(completedAtStr) : null;
        String reference = (String) stateMap.get(prefix + "_reference");
        String errorCode = (String) stateMap.get(prefix + "_error_code");
        String errorMessage = (String) stateMap.get(prefix + "_error_message");
        return new LevelStatus(completedAt, reference, errorCode, errorMessage);
    }

    /**
     * Check if this payment requires FX.
     */
    public boolean requiresFx() {
        return debitCurrency != creditCurrency;
    }

    /**
     * Get progress percentage (based on completed levels for payment processing).
     */
    public int progressPercent() {
        // Payment processing has stages: Risk -> FX (optional) -> Submit -> L1 -> L2 -> L3 -> L4
        // Simplified: use completedLevel (0-4) as progress indicator
        // 0 = submitting, 1 = L1 done, 2 = L2 done, 3 = L3 done, 4 = L4 done (complete)
        if (processStatus == ProcessStatus.COMPLETED) {
            return 100;
        }
        if (processStatus == ProcessStatus.FAILED || processStatus == ProcessStatus.COMPENSATED) {
            return 0;
        }
        // Each level is ~20% of progress after submission (which is ~20%)
        return Math.min(20 + (completedLevel * 20), 100);
    }

    /**
     * Get a display-friendly status combining payment and process status.
     */
    public String displayStatus() {
        if (processStatus == null) {
            return paymentStatus.name();
        }
        return switch (processStatus) {
            case PENDING, IN_PROGRESS, WAITING_FOR_REPLY -> currentStep != null ? currentStep : "PROCESSING";
            case COMPLETED -> "COMPLETE";
            case COMPENSATING, COMPENSATED, CANCELED -> "CANCELLED";
            case WAITING_FOR_TSQ -> "NEEDS_ATTENTION";
            case FAILED -> "FAILED";
        };
    }
}
