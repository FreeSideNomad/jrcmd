package com.ivamare.commandbus.e2e.payment.step;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.ivamare.commandbus.e2e.payment.PaymentStepBehavior;
import com.ivamare.commandbus.process.step.ProcessStepState;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * State for step-based payment processing workflow.
 *
 * <p>Extends ProcessStepState to leverage framework-managed tracking
 * (stepHistory, waitHistory, sideEffects) while adding domain-specific
 * payment fields.
 *
 * <p>This state class is used with ProcessStepManager for deterministic
 * replay-style workflow execution.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PaymentStepState extends ProcessStepState {

    // Payment identity
    private UUID paymentId;

    // Risk step results
    private String riskStatus;           // APPROVED, DECLINED, or PENDING_APPROVAL
    private String riskMethod;           // AVAILABLE_BALANCE, DAILY_LIMIT, MANUAL
    private String riskReference;        // Risk booking reference
    private boolean pendingApproval;     // True if waiting for manual approval

    // FX step results
    private Long fxContractId;           // FX contract ID
    private BigDecimal fxRate;           // Exchange rate
    private BigDecimal creditAmount;     // Calculated credit amount
    private boolean fxRequired;          // Whether FX was needed

    // Submission results
    private String submissionReference;  // Payment network reference
    private UUID submissionCommandId;    // Command ID for tracking

    // L1-L4 confirmation tracking (independent fields for out-of-order handling)
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

    // Behavior configuration for testing
    private PaymentStepBehavior behavior;

    // ========== Constructors ==========

    public PaymentStepState() {
        // Default constructor for Jackson
    }

    public PaymentStepState(UUID paymentId) {
        this.paymentId = paymentId;
    }

    // ========== Payment ID ==========

    public UUID getPaymentId() {
        return paymentId;
    }

    public void setPaymentId(UUID paymentId) {
        this.paymentId = paymentId;
    }

    // ========== Risk ==========

    public String getRiskStatus() {
        return riskStatus;
    }

    public void setRiskStatus(String riskStatus) {
        this.riskStatus = riskStatus;
    }

    public String getRiskMethod() {
        return riskMethod;
    }

    public void setRiskMethod(String riskMethod) {
        this.riskMethod = riskMethod;
    }

    public String getRiskReference() {
        return riskReference;
    }

    public void setRiskReference(String riskReference) {
        this.riskReference = riskReference;
    }

    public boolean isPendingApproval() {
        return pendingApproval;
    }

    public void setPendingApproval(boolean pendingApproval) {
        this.pendingApproval = pendingApproval;
    }

    // ========== FX ==========

    public Long getFxContractId() {
        return fxContractId;
    }

    public void setFxContractId(Long fxContractId) {
        this.fxContractId = fxContractId;
    }

    public BigDecimal getFxRate() {
        return fxRate;
    }

    public void setFxRate(BigDecimal fxRate) {
        this.fxRate = fxRate;
    }

    public BigDecimal getCreditAmount() {
        return creditAmount;
    }

    public void setCreditAmount(BigDecimal creditAmount) {
        this.creditAmount = creditAmount;
    }

    public boolean isFxRequired() {
        return fxRequired;
    }

    public void setFxRequired(boolean fxRequired) {
        this.fxRequired = fxRequired;
    }

    // ========== Submission ==========

    public String getSubmissionReference() {
        return submissionReference;
    }

    public void setSubmissionReference(String submissionReference) {
        this.submissionReference = submissionReference;
    }

    public UUID getSubmissionCommandId() {
        return submissionCommandId;
    }

    public void setSubmissionCommandId(UUID submissionCommandId) {
        this.submissionCommandId = submissionCommandId;
    }

    // ========== L1-L4 Tracking ==========

    public Instant getL1CompletedAt() {
        return l1CompletedAt;
    }

    public void setL1CompletedAt(Instant l1CompletedAt) {
        this.l1CompletedAt = l1CompletedAt;
    }

    public String getL1Reference() {
        return l1Reference;
    }

    public void setL1Reference(String l1Reference) {
        this.l1Reference = l1Reference;
    }

    public String getL1ErrorCode() {
        return l1ErrorCode;
    }

    public void setL1ErrorCode(String l1ErrorCode) {
        this.l1ErrorCode = l1ErrorCode;
    }

    public String getL1ErrorMessage() {
        return l1ErrorMessage;
    }

    public void setL1ErrorMessage(String l1ErrorMessage) {
        this.l1ErrorMessage = l1ErrorMessage;
    }

    public Instant getL2CompletedAt() {
        return l2CompletedAt;
    }

    public void setL2CompletedAt(Instant l2CompletedAt) {
        this.l2CompletedAt = l2CompletedAt;
    }

    public String getL2Reference() {
        return l2Reference;
    }

    public void setL2Reference(String l2Reference) {
        this.l2Reference = l2Reference;
    }

    public String getL2ErrorCode() {
        return l2ErrorCode;
    }

    public void setL2ErrorCode(String l2ErrorCode) {
        this.l2ErrorCode = l2ErrorCode;
    }

    public String getL2ErrorMessage() {
        return l2ErrorMessage;
    }

    public void setL2ErrorMessage(String l2ErrorMessage) {
        this.l2ErrorMessage = l2ErrorMessage;
    }

    public Instant getL3CompletedAt() {
        return l3CompletedAt;
    }

    public void setL3CompletedAt(Instant l3CompletedAt) {
        this.l3CompletedAt = l3CompletedAt;
    }

    public String getL3Reference() {
        return l3Reference;
    }

    public void setL3Reference(String l3Reference) {
        this.l3Reference = l3Reference;
    }

    public String getL3ErrorCode() {
        return l3ErrorCode;
    }

    public void setL3ErrorCode(String l3ErrorCode) {
        this.l3ErrorCode = l3ErrorCode;
    }

    public String getL3ErrorMessage() {
        return l3ErrorMessage;
    }

    public void setL3ErrorMessage(String l3ErrorMessage) {
        this.l3ErrorMessage = l3ErrorMessage;
    }

    public Instant getL4CompletedAt() {
        return l4CompletedAt;
    }

    public void setL4CompletedAt(Instant l4CompletedAt) {
        this.l4CompletedAt = l4CompletedAt;
    }

    public String getL4Reference() {
        return l4Reference;
    }

    public void setL4Reference(String l4Reference) {
        this.l4Reference = l4Reference;
    }

    public String getL4ErrorCode() {
        return l4ErrorCode;
    }

    public void setL4ErrorCode(String l4ErrorCode) {
        this.l4ErrorCode = l4ErrorCode;
    }

    public String getL4ErrorMessage() {
        return l4ErrorMessage;
    }

    public void setL4ErrorMessage(String l4ErrorMessage) {
        this.l4ErrorMessage = l4ErrorMessage;
    }

    // ========== Behavior ==========

    public PaymentStepBehavior getBehavior() {
        return behavior;
    }

    public void setBehavior(PaymentStepBehavior behavior) {
        this.behavior = behavior;
    }

    // ========== Helper Methods ==========

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

    // ========== Fluent Setters for L1-L4 ==========

    public void recordL1Success(String reference) {
        this.l1CompletedAt = Instant.now();
        this.l1Reference = reference;
        this.l1ErrorCode = null;
        this.l1ErrorMessage = null;
    }

    public void recordL1Error(String errorCode, String errorMessage) {
        this.l1CompletedAt = Instant.now();
        this.l1Reference = null;
        this.l1ErrorCode = errorCode;
        this.l1ErrorMessage = errorMessage;
    }

    public void recordL2Success(String reference) {
        this.l2CompletedAt = Instant.now();
        this.l2Reference = reference;
        this.l2ErrorCode = null;
        this.l2ErrorMessage = null;
    }

    public void recordL2Error(String errorCode, String errorMessage) {
        this.l2CompletedAt = Instant.now();
        this.l2Reference = null;
        this.l2ErrorCode = errorCode;
        this.l2ErrorMessage = errorMessage;
    }

    public void recordL3Success(String reference) {
        this.l3CompletedAt = Instant.now();
        this.l3Reference = reference;
        this.l3ErrorCode = null;
        this.l3ErrorMessage = null;
    }

    public void recordL3Error(String errorCode, String errorMessage) {
        this.l3CompletedAt = Instant.now();
        this.l3Reference = null;
        this.l3ErrorCode = errorCode;
        this.l3ErrorMessage = errorMessage;
    }

    public void recordL4Success(String reference) {
        this.l4CompletedAt = Instant.now();
        this.l4Reference = reference;
        this.l4ErrorCode = null;
        this.l4ErrorMessage = null;
    }

    public void recordL4Error(String errorCode, String errorMessage) {
        this.l4CompletedAt = Instant.now();
        this.l4Reference = null;
        this.l4ErrorCode = errorCode;
        this.l4ErrorMessage = errorMessage;
    }

    // ========== Builder ==========

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private UUID paymentId;
        private PaymentStepBehavior behavior;
        private Instant processDeadline;

        public Builder paymentId(UUID paymentId) {
            this.paymentId = paymentId;
            return this;
        }

        public Builder behavior(PaymentStepBehavior behavior) {
            this.behavior = behavior;
            return this;
        }

        public Builder processDeadline(Instant deadline) {
            this.processDeadline = deadline;
            return this;
        }

        public PaymentStepState build() {
            PaymentStepState state = new PaymentStepState();
            state.setPaymentId(paymentId);
            state.setBehavior(behavior);
            if (processDeadline != null) {
                state.setProcessDeadline(processDeadline);
            }
            return state;
        }
    }
}
