package com.ivamare.commandbus.process.step;

import java.time.Instant;

/**
 * Record of a step execution within a ProcessStepManager workflow.
 *
 * <p>Step records track:
 * <ul>
 *   <li>Execution status and timing</li>
 *   <li>Retry attempts and limits</li>
 *   <li>Input/output for replay</li>
 *   <li>Error information for troubleshooting</li>
 * </ul>
 *
 * @param name Step identifier (unique within a process)
 * @param status Current execution status
 * @param attemptCount Number of execution attempts (1-based)
 * @param maxRetries Maximum retry attempts configured
 * @param startedAt When the step execution started
 * @param completedAt When the step completed (null if not completed)
 * @param requestJson Serialized input for debugging
 * @param responseJson Serialized result for replay
 * @param errorCode Error code if failed
 * @param errorMessage Error message if failed
 * @param nextRetryAt When retry is scheduled (for WAITING_RETRY status)
 */
public record StepRecord(
    String name,
    StepStatus status,
    int attemptCount,
    int maxRetries,
    Instant startedAt,
    Instant completedAt,
    String requestJson,
    String responseJson,
    String errorCode,
    String errorMessage,
    Instant nextRetryAt
) {

    /**
     * Create a new step record for the start of execution.
     */
    public static StepRecord started(String name, int maxRetries, String requestJson) {
        return new StepRecord(
            name,
            StepStatus.STARTED,
            1,
            maxRetries,
            Instant.now(),
            null,
            requestJson,
            null,
            null,
            null,
            null
        );
    }

    /**
     * Create an updated record marking the step as completed.
     */
    public StepRecord completed(String responseJson) {
        return new StepRecord(
            name,
            StepStatus.COMPLETED,
            attemptCount,
            maxRetries,
            startedAt,
            Instant.now(),
            requestJson,
            responseJson,
            null,
            null,
            null
        );
    }

    /**
     * Create an updated record marking the step as failed.
     */
    public StepRecord failed(String errorCode, String errorMessage) {
        return new StepRecord(
            name,
            StepStatus.FAILED,
            attemptCount,
            maxRetries,
            startedAt,
            Instant.now(),
            requestJson,
            null,
            errorCode,
            errorMessage,
            null
        );
    }

    /**
     * Create an updated record scheduling a retry.
     */
    public StepRecord waitingRetry(int newAttemptCount, Instant retryAt,
                                   String errorCode, String errorMessage) {
        return new StepRecord(
            name,
            StepStatus.WAITING_RETRY,
            newAttemptCount,
            maxRetries,
            startedAt,
            null,
            requestJson,
            null,
            errorCode,
            errorMessage,
            retryAt
        );
    }

    /**
     * Create an updated record for a retry attempt starting.
     */
    public StepRecord retryStarted() {
        return new StepRecord(
            name,
            StepStatus.STARTED,
            attemptCount,
            maxRetries,
            Instant.now(),
            null,
            requestJson,
            null,
            null,
            null,
            null
        );
    }

    /**
     * Check if retries are available.
     */
    public boolean hasRetriesRemaining() {
        return attemptCount < maxRetries;
    }
}
