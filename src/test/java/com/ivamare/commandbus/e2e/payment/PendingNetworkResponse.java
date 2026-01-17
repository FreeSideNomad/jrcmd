package com.ivamare.commandbus.e2e.payment;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents a pending L3 or L4 network confirmation response.
 *
 * <p>When the payment network simulation determines that an L3 or L4 response
 * should require operator intervention, it is placed in this queue instead
 * of being automatically sent. An operator must approve or reject to send
 * the network response and continue the process.
 *
 * @param id             Unique queue item identifier
 * @param paymentId      Payment (optional, for display)
 * @param processId      Process awaiting this response
 * @param correlationId  For routing reply back to process
 * @param commandId      Original SubmitPayment command ID
 * @param level          Network confirmation level (3 or 4)
 * @param executionModel Execution model (STEP_BASED or PROCESS_STEP)
 * @param status         Queue item status (PENDING, APPROVED, REJECTED)
 * @param createdAt      When item was queued
 * @param resolvedAt     When operator took action
 * @param resolvedBy     Operator identity
 * @param resolutionNotes Optional notes from operator
 */
public record PendingNetworkResponse(
    UUID id,
    UUID paymentId,
    UUID processId,
    UUID correlationId,
    UUID commandId,
    int level,
    String executionModel,
    ResponseStatus status,
    Instant createdAt,
    Instant resolvedAt,
    String resolvedBy,
    String resolutionNotes
) {
    /**
     * Status of a pending network response item.
     */
    public enum ResponseStatus {
        PENDING,
        SUCCESS,
        FAILED
    }

    /**
     * Create a new pending network response entry for STEP_BASED execution model (default).
     */
    public static PendingNetworkResponse create(
            UUID paymentId,
            UUID processId,
            UUID correlationId,
            UUID commandId,
            int level) {
        return create(paymentId, processId, correlationId, commandId, level, "STEP_BASED");
    }

    /**
     * Create a new pending network response entry with specified execution model.
     */
    public static PendingNetworkResponse create(
            UUID paymentId,
            UUID processId,
            UUID correlationId,
            UUID commandId,
            int level,
            String executionModel) {
        return new PendingNetworkResponse(
            UUID.randomUUID(),
            paymentId,
            processId,
            correlationId,
            commandId,
            level,
            executionModel,
            ResponseStatus.PENDING,
            Instant.now(),
            null,
            null,
            null
        );
    }

    /**
     * Create resolved response.
     */
    public PendingNetworkResponse withResolution(ResponseStatus status, String operator, String notes) {
        return new PendingNetworkResponse(
            id,
            paymentId,
            processId,
            correlationId,
            commandId,
            level,
            executionModel,
            status,
            createdAt,
            Instant.now(),
            operator,
            notes
        );
    }

    /**
     * Check if this response uses the PROCESS_STEP execution model.
     */
    public boolean isProcessStepModel() {
        return "PROCESS_STEP".equals(executionModel);
    }

    /**
     * Check if this response is still pending.
     */
    public boolean isPending() {
        return status == ResponseStatus.PENDING;
    }

    /**
     * Get display name for the level.
     */
    public String levelDisplay() {
        return "L" + level;
    }
}
