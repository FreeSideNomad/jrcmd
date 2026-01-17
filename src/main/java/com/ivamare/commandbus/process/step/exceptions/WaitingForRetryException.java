package com.ivamare.commandbus.process.step.exceptions;

import java.time.Instant;

/**
 * Control flow exception thrown when a step needs to be retried later.
 *
 * <p>This exception is used for control flow in the ProcessStepManager
 * when a transient failure occurs and retries are available. It triggers:
 * <ul>
 *   <li>State persistence with WAITING_FOR_RETRY status</li>
 *   <li>Scheduling of next retry time</li>
 *   <li>Return of control to caller</li>
 *   <li>Process will resume when worker polls for due retries</li>
 * </ul>
 *
 * <p>The retry is not executed in the same thread - the process pauses
 * and a scheduled worker picks it up when the retry time is reached.
 */
public class WaitingForRetryException extends RuntimeException {

    private final String stepName;
    private final Instant nextRetryAt;

    /**
     * Create a waiting for retry exception.
     *
     * @param stepName The name of the step that will be retried
     * @param nextRetryAt When the retry is scheduled
     */
    public WaitingForRetryException(String stepName, Instant nextRetryAt) {
        super("Step " + stepName + " scheduled for retry at " + nextRetryAt);
        this.stepName = stepName;
        this.nextRetryAt = nextRetryAt;
    }

    /**
     * Get the name of the step being retried.
     */
    public String getStepName() {
        return stepName;
    }

    /**
     * Get when the retry is scheduled.
     */
    public Instant getNextRetryAt() {
        return nextRetryAt;
    }
}
