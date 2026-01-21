package com.ivamare.commandbus.process.step;

import java.time.Instant;
import java.util.UUID;

/**
 * Tracks a pending command step that has been sent but not yet received a response.
 *
 * <p>Used by ProcessStepManager to track which command steps are awaiting responses.
 * When a response arrives via processAsyncResponse(), it can be matched to the
 * pending command step using the stepName.
 *
 * @param stepName the step name
 * @param commandId the command ID sent to the target domain
 * @param sentAt when the command was sent
 * @param timeoutAt when this command step should timeout
 */
public record PendingCommandStep(
    String stepName,
    UUID commandId,
    Instant sentAt,
    Instant timeoutAt
) {

    /**
     * Create a new pending command step with default timeout (30 seconds).
     *
     * @param stepName the step name
     * @param commandId the command ID
     * @return new pending command step
     */
    public static PendingCommandStep create(String stepName, UUID commandId) {
        Instant now = Instant.now();
        return new PendingCommandStep(stepName, commandId, now, now.plusSeconds(30));
    }

    /**
     * Create a new pending command step with custom timeout.
     *
     * @param stepName the step name
     * @param commandId the command ID
     * @param timeoutSeconds timeout in seconds
     * @return new pending command step
     */
    public static PendingCommandStep create(String stepName, UUID commandId, long timeoutSeconds) {
        Instant now = Instant.now();
        return new PendingCommandStep(stepName, commandId, now, now.plusSeconds(timeoutSeconds));
    }

    /**
     * Check if this command step has timed out.
     *
     * @return true if timed out
     */
    public boolean isTimedOut() {
        return Instant.now().isAfter(timeoutAt);
    }
}
