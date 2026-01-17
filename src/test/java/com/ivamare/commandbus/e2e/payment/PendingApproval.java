package com.ivamare.commandbus.e2e.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Represents a payment pending manual approval.
 *
 * <p>When a payment requires manual review during risk assessment, it is placed
 * in the pending approval queue instead of blocking the worker thread. An operator
 * must approve or reject the payment to continue the process.
 *
 * @param id             Unique queue item identifier
 * @param paymentId      Payment being approved
 * @param processId      Process orchestrating this payment
 * @param correlationId  For routing reply back to process
 * @param commandId      Original BookTransactionRisk command ID
 * @param amount         Debit amount for display
 * @param currency       Debit currency for display
 * @param debitAccount   Source account for display
 * @param creditAccount  Destination account for display
 * @param executionModel Execution model (STEP_BASED or PROCESS_STEP)
 * @param status         Queue item status (PENDING, APPROVED, REJECTED)
 * @param createdAt      When item was queued
 * @param resolvedAt     When operator took action
 * @param resolvedBy     Operator identity
 * @param resolutionNotes Optional notes from operator
 */
public record PendingApproval(
    UUID id,
    UUID paymentId,
    UUID processId,
    UUID correlationId,
    UUID commandId,
    BigDecimal amount,
    String currency,
    String debitAccount,
    String creditAccount,
    String executionModel,
    ApprovalStatus status,
    Instant createdAt,
    Instant resolvedAt,
    String resolvedBy,
    String resolutionNotes
) {
    /**
     * Status of a pending approval item.
     */
    public enum ApprovalStatus {
        PENDING,
        APPROVED,
        REJECTED
    }

    /**
     * Create a new pending approval entry for STEP_BASED execution model (default).
     */
    public static PendingApproval create(
            UUID paymentId,
            UUID processId,
            UUID correlationId,
            UUID commandId,
            BigDecimal amount,
            String currency,
            String debitAccount,
            String creditAccount) {
        return create(paymentId, processId, correlationId, commandId,
            amount, currency, debitAccount, creditAccount, "STEP_BASED");
    }

    /**
     * Create a new pending approval entry with specified execution model.
     */
    public static PendingApproval create(
            UUID paymentId,
            UUID processId,
            UUID correlationId,
            UUID commandId,
            BigDecimal amount,
            String currency,
            String debitAccount,
            String creditAccount,
            String executionModel) {
        return new PendingApproval(
            UUID.randomUUID(),
            paymentId,
            processId,
            correlationId,
            commandId,
            amount,
            currency,
            debitAccount,
            creditAccount,
            executionModel,
            ApprovalStatus.PENDING,
            Instant.now(),
            null,
            null,
            null
        );
    }

    /**
     * Create resolved approval.
     */
    public PendingApproval withResolution(ApprovalStatus status, String operator, String notes) {
        return new PendingApproval(
            id,
            paymentId,
            processId,
            correlationId,
            commandId,
            amount,
            currency,
            debitAccount,
            creditAccount,
            executionModel,
            status,
            createdAt,
            Instant.now(),
            operator,
            notes
        );
    }

    /**
     * Check if this approval uses the PROCESS_STEP execution model.
     */
    public boolean isProcessStepModel() {
        return "PROCESS_STEP".equals(executionModel);
    }

    /**
     * Check if this approval is still pending.
     */
    public boolean isPending() {
        return status == ApprovalStatus.PENDING;
    }
}
