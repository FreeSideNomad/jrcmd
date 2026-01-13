package com.ivamare.commandbus.e2e.payment;

import com.ivamare.commandbus.process.ProcessState;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * State for payment processing workflow.
 *
 * <p>Tracks the progress through the multi-step workflow and stores
 * intermediate results from each step.
 *
 * <p>L1-L4 confirmations are tracked independently to handle out-of-order
 * processing due to concurrency.
 */
public record PaymentProcessState(
    UUID paymentId,

    // Risk step results
    String riskStatus,           // APPROVED, DECLINED, or PENDING_APPROVAL
    String riskMethod,           // AVAILABLE_BALANCE, DAILY_LIMIT, MANUAL
    String riskReference,        // Risk booking reference
    boolean pendingApproval,     // True if waiting for manual approval

    // FX step results
    Long fxContractId,           // FX contract ID
    BigDecimal fxRate,           // Exchange rate
    BigDecimal creditAmount,     // Calculated credit amount
    boolean fxRequired,          // Whether FX was needed

    // Submission results
    String submissionReference,  // Payment network reference
    UUID submissionCommandId,    // Command ID for original SubmitPayment (for reply routing)

    // L1-L4 confirmation tracking (independent fields for out-of-order handling)
    // Each level has: completedAt (timestamp), reference (on success), errorCode/errorMessage (on failure)
    Instant l1CompletedAt,
    String l1Reference,
    String l1ErrorCode,
    String l1ErrorMessage,

    Instant l2CompletedAt,
    String l2Reference,
    String l2ErrorCode,
    String l2ErrorMessage,

    Instant l3CompletedAt,
    String l3Reference,
    String l3ErrorCode,
    String l3ErrorMessage,

    Instant l4CompletedAt,
    String l4Reference,
    String l4ErrorCode,
    String l4ErrorMessage,

    // Behavior for testing
    PaymentStepBehavior behavior
) implements ProcessState {

    /**
     * Check if any level has an error.
     */
    public boolean hasAnyError() {
        return l1ErrorCode != null || l2ErrorCode != null ||
               l3ErrorCode != null || l4ErrorCode != null;
    }

    /**
     * Check if L4 completed successfully (no error).
     */
    public boolean isL4Success() {
        return l4CompletedAt != null && l4ErrorCode == null;
    }

    /**
     * Count how many levels have been received (success or error).
     */
    public int receivedLevelCount() {
        int count = 0;
        if (l1CompletedAt != null) count++;
        if (l2CompletedAt != null) count++;
        if (l3CompletedAt != null) count++;
        if (l4CompletedAt != null) count++;
        return count;
    }

    /**
     * Get the highest completed level (0-4).
     * Returns the highest level where all previous levels are also complete.
     */
    public int completedLevel() {
        if (l4CompletedAt != null && l3CompletedAt != null && l2CompletedAt != null && l1CompletedAt != null) return 4;
        if (l3CompletedAt != null && l2CompletedAt != null && l1CompletedAt != null) return 3;
        if (l2CompletedAt != null && l1CompletedAt != null) return 2;
        if (l1CompletedAt != null) return 1;
        return 0;
    }

    /**
     * Check if all levels are complete.
     */
    public boolean allLevelsComplete() {
        return l1CompletedAt != null && l2CompletedAt != null && l3CompletedAt != null && l4CompletedAt != null;
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("payment_id", paymentId != null ? paymentId.toString() : null);
        map.put("risk_status", riskStatus);
        map.put("risk_method", riskMethod);
        map.put("risk_reference", riskReference);
        map.put("pending_approval", pendingApproval);
        map.put("fx_contract_id", fxContractId);
        map.put("fx_rate", fxRate != null ? fxRate.toString() : null);
        map.put("credit_amount", creditAmount != null ? creditAmount.toString() : null);
        map.put("fx_required", fxRequired);
        map.put("submission_reference", submissionReference);
        map.put("submission_command_id", submissionCommandId != null ? submissionCommandId.toString() : null);
        // L1-L4 tracking (including error fields)
        map.put("l1_completed_at", l1CompletedAt != null ? l1CompletedAt.toString() : null);
        map.put("l1_reference", l1Reference);
        map.put("l1_error_code", l1ErrorCode);
        map.put("l1_error_message", l1ErrorMessage);
        map.put("l2_completed_at", l2CompletedAt != null ? l2CompletedAt.toString() : null);
        map.put("l2_reference", l2Reference);
        map.put("l2_error_code", l2ErrorCode);
        map.put("l2_error_message", l2ErrorMessage);
        map.put("l3_completed_at", l3CompletedAt != null ? l3CompletedAt.toString() : null);
        map.put("l3_reference", l3Reference);
        map.put("l3_error_code", l3ErrorCode);
        map.put("l3_error_message", l3ErrorMessage);
        map.put("l4_completed_at", l4CompletedAt != null ? l4CompletedAt.toString() : null);
        map.put("l4_reference", l4Reference);
        map.put("l4_error_code", l4ErrorCode);
        map.put("l4_error_message", l4ErrorMessage);
        // Computed fields
        map.put("completedLevel", completedLevel());
        map.put("hasAnyError", hasAnyError());
        map.put("isL4Success", isL4Success());
        if (behavior != null) {
            map.put("behavior", behavior.toMap());
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    public static PaymentProcessState fromMap(Map<String, Object> map) {
        UUID paymentId = map.get("payment_id") != null
            ? UUID.fromString((String) map.get("payment_id")) : null;
        String riskStatus = (String) map.get("risk_status");
        String riskMethod = (String) map.get("risk_method");
        String riskReference = (String) map.get("risk_reference");
        boolean pendingApproval = map.get("pending_approval") != null
            ? (Boolean) map.get("pending_approval") : false;
        Long fxContractId = map.get("fx_contract_id") != null
            ? ((Number) map.get("fx_contract_id")).longValue() : null;
        BigDecimal fxRate = map.get("fx_rate") != null
            ? new BigDecimal((String) map.get("fx_rate")) : null;
        BigDecimal creditAmount = map.get("credit_amount") != null
            ? new BigDecimal((String) map.get("credit_amount")) : null;
        boolean fxRequired = map.get("fx_required") != null
            ? (Boolean) map.get("fx_required") : false;
        String submissionReference = (String) map.get("submission_reference");
        UUID submissionCommandId = map.get("submission_command_id") != null
            ? UUID.fromString((String) map.get("submission_command_id")) : null;
        // L1-L4 tracking (including error fields)
        Instant l1CompletedAt = map.get("l1_completed_at") != null
            ? Instant.parse((String) map.get("l1_completed_at")) : null;
        String l1Reference = (String) map.get("l1_reference");
        String l1ErrorCode = (String) map.get("l1_error_code");
        String l1ErrorMessage = (String) map.get("l1_error_message");

        Instant l2CompletedAt = map.get("l2_completed_at") != null
            ? Instant.parse((String) map.get("l2_completed_at")) : null;
        String l2Reference = (String) map.get("l2_reference");
        String l2ErrorCode = (String) map.get("l2_error_code");
        String l2ErrorMessage = (String) map.get("l2_error_message");

        Instant l3CompletedAt = map.get("l3_completed_at") != null
            ? Instant.parse((String) map.get("l3_completed_at")) : null;
        String l3Reference = (String) map.get("l3_reference");
        String l3ErrorCode = (String) map.get("l3_error_code");
        String l3ErrorMessage = (String) map.get("l3_error_message");

        Instant l4CompletedAt = map.get("l4_completed_at") != null
            ? Instant.parse((String) map.get("l4_completed_at")) : null;
        String l4Reference = (String) map.get("l4_reference");
        String l4ErrorCode = (String) map.get("l4_error_code");
        String l4ErrorMessage = (String) map.get("l4_error_message");

        PaymentStepBehavior behavior = map.get("behavior") != null
            ? PaymentStepBehavior.fromMap((Map<String, Object>) map.get("behavior")) : null;

        return new PaymentProcessState(
            paymentId, riskStatus, riskMethod, riskReference, pendingApproval,
            fxContractId, fxRate, creditAmount, fxRequired,
            submissionReference, submissionCommandId,
            l1CompletedAt, l1Reference, l1ErrorCode, l1ErrorMessage,
            l2CompletedAt, l2Reference, l2ErrorCode, l2ErrorMessage,
            l3CompletedAt, l3Reference, l3ErrorCode, l3ErrorMessage,
            l4CompletedAt, l4Reference, l4ErrorCode, l4ErrorMessage,
            behavior
        );
    }

    /**
     * Create state with risk approval result.
     */
    public PaymentProcessState withRiskApproval(String status, String method, String reference) {
        boolean pending = "PENDING_APPROVAL".equals(status);
        return new PaymentProcessState(
            paymentId, status, method, reference, pending,
            fxContractId, fxRate, creditAmount, fxRequired,
            submissionReference, submissionCommandId,
            l1CompletedAt, l1Reference, l1ErrorCode, l1ErrorMessage,
            l2CompletedAt, l2Reference, l2ErrorCode, l2ErrorMessage,
            l3CompletedAt, l3Reference, l3ErrorCode, l3ErrorMessage,
            l4CompletedAt, l4Reference, l4ErrorCode, l4ErrorMessage,
            behavior
        );
    }

    /**
     * Create state with approval cleared (after operator approves).
     */
    public PaymentProcessState withApprovalCleared() {
        return new PaymentProcessState(
            paymentId, "APPROVED", riskMethod, riskReference, false,
            fxContractId, fxRate, creditAmount, fxRequired,
            submissionReference, submissionCommandId,
            l1CompletedAt, l1Reference, l1ErrorCode, l1ErrorMessage,
            l2CompletedAt, l2Reference, l2ErrorCode, l2ErrorMessage,
            l3CompletedAt, l3Reference, l3ErrorCode, l3ErrorMessage,
            l4CompletedAt, l4Reference, l4ErrorCode, l4ErrorMessage,
            behavior
        );
    }

    /**
     * Create state with FX booking result.
     */
    public PaymentProcessState withFxResult(Long contractId, BigDecimal rate, BigDecimal amount) {
        return new PaymentProcessState(
            paymentId, riskStatus, riskMethod, riskReference, pendingApproval,
            contractId, rate, amount, fxRequired,
            submissionReference, submissionCommandId,
            l1CompletedAt, l1Reference, l1ErrorCode, l1ErrorMessage,
            l2CompletedAt, l2Reference, l2ErrorCode, l2ErrorMessage,
            l3CompletedAt, l3Reference, l3ErrorCode, l3ErrorMessage,
            l4CompletedAt, l4Reference, l4ErrorCode, l4ErrorMessage,
            behavior
        );
    }

    /**
     * Create state with submission result.
     */
    public PaymentProcessState withSubmissionResult(String reference, UUID commandId) {
        return new PaymentProcessState(
            paymentId, riskStatus, riskMethod, riskReference, pendingApproval,
            fxContractId, fxRate, creditAmount, fxRequired,
            reference, commandId,
            l1CompletedAt, l1Reference, l1ErrorCode, l1ErrorMessage,
            l2CompletedAt, l2Reference, l2ErrorCode, l2ErrorMessage,
            l3CompletedAt, l3Reference, l3ErrorCode, l3ErrorMessage,
            l4CompletedAt, l4Reference, l4ErrorCode, l4ErrorMessage,
            behavior
        );
    }

    // ========== L1-L4 Success Methods ==========

    /**
     * Create state with L1 success.
     */
    public PaymentProcessState withL1Success(String reference) {
        return new PaymentProcessState(
            paymentId, riskStatus, riskMethod, riskReference, pendingApproval,
            fxContractId, fxRate, creditAmount, fxRequired,
            submissionReference, submissionCommandId,
            Instant.now(), reference, null, null,
            l2CompletedAt, l2Reference, l2ErrorCode, l2ErrorMessage,
            l3CompletedAt, l3Reference, l3ErrorCode, l3ErrorMessage,
            l4CompletedAt, l4Reference, l4ErrorCode, l4ErrorMessage,
            behavior
        );
    }

    /**
     * Create state with L2 success.
     */
    public PaymentProcessState withL2Success(String reference) {
        return new PaymentProcessState(
            paymentId, riskStatus, riskMethod, riskReference, pendingApproval,
            fxContractId, fxRate, creditAmount, fxRequired,
            submissionReference, submissionCommandId,
            l1CompletedAt, l1Reference, l1ErrorCode, l1ErrorMessage,
            Instant.now(), reference, null, null,
            l3CompletedAt, l3Reference, l3ErrorCode, l3ErrorMessage,
            l4CompletedAt, l4Reference, l4ErrorCode, l4ErrorMessage,
            behavior
        );
    }

    /**
     * Create state with L3 success.
     */
    public PaymentProcessState withL3Success(String reference) {
        return new PaymentProcessState(
            paymentId, riskStatus, riskMethod, riskReference, pendingApproval,
            fxContractId, fxRate, creditAmount, fxRequired,
            submissionReference, submissionCommandId,
            l1CompletedAt, l1Reference, l1ErrorCode, l1ErrorMessage,
            l2CompletedAt, l2Reference, l2ErrorCode, l2ErrorMessage,
            Instant.now(), reference, null, null,
            l4CompletedAt, l4Reference, l4ErrorCode, l4ErrorMessage,
            behavior
        );
    }

    /**
     * Create state with L4 success.
     */
    public PaymentProcessState withL4Success(String reference) {
        return new PaymentProcessState(
            paymentId, riskStatus, riskMethod, riskReference, pendingApproval,
            fxContractId, fxRate, creditAmount, fxRequired,
            submissionReference, submissionCommandId,
            l1CompletedAt, l1Reference, l1ErrorCode, l1ErrorMessage,
            l2CompletedAt, l2Reference, l2ErrorCode, l2ErrorMessage,
            l3CompletedAt, l3Reference, l3ErrorCode, l3ErrorMessage,
            Instant.now(), reference, null, null,
            behavior
        );
    }

    // ========== L1-L4 Error Methods ==========

    /**
     * Create state with L1 error.
     */
    public PaymentProcessState withL1Error(String errorCode, String errorMessage) {
        return new PaymentProcessState(
            paymentId, riskStatus, riskMethod, riskReference, pendingApproval,
            fxContractId, fxRate, creditAmount, fxRequired,
            submissionReference, submissionCommandId,
            Instant.now(), null, errorCode, errorMessage,
            l2CompletedAt, l2Reference, l2ErrorCode, l2ErrorMessage,
            l3CompletedAt, l3Reference, l3ErrorCode, l3ErrorMessage,
            l4CompletedAt, l4Reference, l4ErrorCode, l4ErrorMessage,
            behavior
        );
    }

    /**
     * Create state with L2 error.
     */
    public PaymentProcessState withL2Error(String errorCode, String errorMessage) {
        return new PaymentProcessState(
            paymentId, riskStatus, riskMethod, riskReference, pendingApproval,
            fxContractId, fxRate, creditAmount, fxRequired,
            submissionReference, submissionCommandId,
            l1CompletedAt, l1Reference, l1ErrorCode, l1ErrorMessage,
            Instant.now(), null, errorCode, errorMessage,
            l3CompletedAt, l3Reference, l3ErrorCode, l3ErrorMessage,
            l4CompletedAt, l4Reference, l4ErrorCode, l4ErrorMessage,
            behavior
        );
    }

    /**
     * Create state with L3 error.
     */
    public PaymentProcessState withL3Error(String errorCode, String errorMessage) {
        return new PaymentProcessState(
            paymentId, riskStatus, riskMethod, riskReference, pendingApproval,
            fxContractId, fxRate, creditAmount, fxRequired,
            submissionReference, submissionCommandId,
            l1CompletedAt, l1Reference, l1ErrorCode, l1ErrorMessage,
            l2CompletedAt, l2Reference, l2ErrorCode, l2ErrorMessage,
            Instant.now(), null, errorCode, errorMessage,
            l4CompletedAt, l4Reference, l4ErrorCode, l4ErrorMessage,
            behavior
        );
    }

    /**
     * Create state with L4 error.
     */
    public PaymentProcessState withL4Error(String errorCode, String errorMessage) {
        return new PaymentProcessState(
            paymentId, riskStatus, riskMethod, riskReference, pendingApproval,
            fxContractId, fxRate, creditAmount, fxRequired,
            submissionReference, submissionCommandId,
            l1CompletedAt, l1Reference, l1ErrorCode, l1ErrorMessage,
            l2CompletedAt, l2Reference, l2ErrorCode, l2ErrorMessage,
            l3CompletedAt, l3Reference, l3ErrorCode, l3ErrorMessage,
            Instant.now(), null, errorCode, errorMessage,
            behavior
        );
    }

    // ========== Legacy methods (for backward compatibility) ==========

    /**
     * @deprecated Use withL1Success instead
     */
    @Deprecated
    public PaymentProcessState withL1Completed(String reference) {
        return withL1Success(reference);
    }

    /**
     * @deprecated Use withL2Success instead
     */
    @Deprecated
    public PaymentProcessState withL2Completed(String reference) {
        return withL2Success(reference);
    }

    /**
     * @deprecated Use withL3Success instead
     */
    @Deprecated
    public PaymentProcessState withL3Completed(String reference) {
        return withL3Success(reference);
    }

    /**
     * @deprecated Use withL4Success instead
     */
    @Deprecated
    public PaymentProcessState withL4Completed(String reference) {
        return withL4Success(reference);
    }

    /**
     * Builder for creating PaymentProcessState instances.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private UUID paymentId;
        private String riskStatus;
        private String riskMethod;
        private String riskReference;
        private boolean pendingApproval;
        private Long fxContractId;
        private BigDecimal fxRate;
        private BigDecimal creditAmount;
        private boolean fxRequired;
        private String submissionReference;
        private UUID submissionCommandId;
        // L1-L4 fields with error support
        private Instant l1CompletedAt;
        private String l1Reference;
        private String l1ErrorCode;
        private String l1ErrorMessage;
        private Instant l2CompletedAt;
        private String l2Reference;
        private String l2ErrorCode;
        private String l2ErrorMessage;
        private Instant l3CompletedAt;
        private String l3Reference;
        private String l3ErrorCode;
        private String l3ErrorMessage;
        private Instant l4CompletedAt;
        private String l4Reference;
        private String l4ErrorCode;
        private String l4ErrorMessage;
        private PaymentStepBehavior behavior;

        public Builder paymentId(UUID paymentId) {
            this.paymentId = paymentId;
            return this;
        }

        public Builder riskStatus(String riskStatus) {
            this.riskStatus = riskStatus;
            return this;
        }

        public Builder riskMethod(String riskMethod) {
            this.riskMethod = riskMethod;
            return this;
        }

        public Builder riskReference(String riskReference) {
            this.riskReference = riskReference;
            return this;
        }

        public Builder pendingApproval(boolean pendingApproval) {
            this.pendingApproval = pendingApproval;
            return this;
        }

        public Builder fxContractId(Long fxContractId) {
            this.fxContractId = fxContractId;
            return this;
        }

        public Builder fxRate(BigDecimal fxRate) {
            this.fxRate = fxRate;
            return this;
        }

        public Builder creditAmount(BigDecimal creditAmount) {
            this.creditAmount = creditAmount;
            return this;
        }

        public Builder fxRequired(boolean fxRequired) {
            this.fxRequired = fxRequired;
            return this;
        }

        public Builder submissionReference(String submissionReference) {
            this.submissionReference = submissionReference;
            return this;
        }

        public Builder submissionCommandId(UUID submissionCommandId) {
            this.submissionCommandId = submissionCommandId;
            return this;
        }

        public Builder behavior(PaymentStepBehavior behavior) {
            this.behavior = behavior;
            return this;
        }

        public PaymentProcessState build() {
            return new PaymentProcessState(
                paymentId, riskStatus, riskMethod, riskReference, pendingApproval,
                fxContractId, fxRate, creditAmount, fxRequired,
                submissionReference, submissionCommandId,
                l1CompletedAt, l1Reference, l1ErrorCode, l1ErrorMessage,
                l2CompletedAt, l2Reference, l2ErrorCode, l2ErrorMessage,
                l3CompletedAt, l3Reference, l3ErrorCode, l3ErrorMessage,
                l4CompletedAt, l4Reference, l4ErrorCode, l4ErrorMessage,
                behavior
            );
        }
    }
}
