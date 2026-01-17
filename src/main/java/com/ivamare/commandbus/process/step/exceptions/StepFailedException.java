package com.ivamare.commandbus.process.step.exceptions;

/**
 * Exception thrown when a step fails permanently.
 *
 * <p>This exception indicates that a step has failed and cannot be recovered
 * through automatic retry. The process will be moved to the troubleshooting
 * queue (TSQ) for operator intervention.
 *
 * <p>This can occur when:
 * <ul>
 *   <li>All retry attempts are exhausted</li>
 *   <li>A permanent error is classified</li>
 *   <li>The step encounters an unrecoverable condition</li>
 * </ul>
 */
public class StepFailedException extends RuntimeException {

    private final String stepName;
    private final String errorCode;

    /**
     * Create a step failed exception.
     *
     * @param stepName The name of the failed step
     * @param errorCode Error code for the failure
     * @param message Detailed error message
     */
    public StepFailedException(String stepName, String errorCode, String message) {
        super("Step " + stepName + " failed: " + message);
        this.stepName = stepName;
        this.errorCode = errorCode;
    }

    /**
     * Create a step failed exception with a cause.
     *
     * @param stepName The name of the failed step
     * @param errorCode Error code for the failure
     * @param message Detailed error message
     * @param cause The underlying exception
     */
    public StepFailedException(String stepName, String errorCode, String message, Throwable cause) {
        super("Step " + stepName + " failed: " + message, cause);
        this.stepName = stepName;
        this.errorCode = errorCode;
    }

    /**
     * Get the name of the failed step.
     */
    public String getStepName() {
        return stepName;
    }

    /**
     * Get the error code.
     */
    public String getErrorCode() {
        return errorCode;
    }
}
