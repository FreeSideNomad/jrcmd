package com.ivamare.commandbus.process.step;

import java.util.UUID;

/**
 * Response from a command step execution.
 *
 * <p>Contains the result of a command step, including success/failure status
 * and strongly typed result data or error information.
 *
 * <p>This record is sent back to the process domain's reply queue and
 * processed by the CommandStepResponseHandler to resume the waiting process.
 *
 * @param <T> the type of the result data
 */
public record CommandStepResponse<T>(
    UUID processId,
    String stepName,
    boolean success,
    T result,              // Strongly typed result if success
    String errorCode,      // Error code if failure
    String errorMessage,   // Error message if failure
    ErrorType errorType    // Type of error for error handling decisions
) {

    /**
     * Type of error encountered during command step execution.
     */
    public enum ErrorType {
        /**
         * Transient error (network timeout, service unavailable) - will retry.
         */
        TRANSIENT,

        /**
         * Permanent error (invalid data, rejected) - goes to TSQ.
         */
        PERMANENT,

        /**
         * Business rule violation - fails immediately with no retry.
         */
        BUSINESS,

        /**
         * Timeout waiting for response - will retry.
         */
        TIMEOUT
    }

    /**
     * Create a successful response with result.
     *
     * @param processId the process ID
     * @param stepName the step name
     * @param result the result data
     * @param <T> the type of the result
     * @return successful response
     */
    public static <T> CommandStepResponse<T> success(UUID processId, String stepName, T result) {
        return new CommandStepResponse<>(processId, stepName, true, result, null, null, null);
    }

    /**
     * Create a failure response.
     *
     * @param processId the process ID
     * @param stepName the step name
     * @param errorType the type of error
     * @param errorCode the error code
     * @param errorMessage the error message
     * @param <T> the type of the result (will be null)
     * @return failure response
     */
    public static <T> CommandStepResponse<T> failure(UUID processId, String stepName,
                                                      ErrorType errorType, String errorCode,
                                                      String errorMessage) {
        return new CommandStepResponse<>(processId, stepName, false, null, errorCode, errorMessage, errorType);
    }

    /**
     * Create a transient failure response.
     *
     * @param processId the process ID
     * @param stepName the step name
     * @param errorCode the error code
     * @param errorMessage the error message
     * @param <T> the type of the result
     * @return transient failure response
     */
    public static <T> CommandStepResponse<T> transientFailure(UUID processId, String stepName,
                                                               String errorCode, String errorMessage) {
        return failure(processId, stepName, ErrorType.TRANSIENT, errorCode, errorMessage);
    }

    /**
     * Create a permanent failure response.
     *
     * @param processId the process ID
     * @param stepName the step name
     * @param errorCode the error code
     * @param errorMessage the error message
     * @param <T> the type of the result
     * @return permanent failure response
     */
    public static <T> CommandStepResponse<T> permanentFailure(UUID processId, String stepName,
                                                               String errorCode, String errorMessage) {
        return failure(processId, stepName, ErrorType.PERMANENT, errorCode, errorMessage);
    }

    /**
     * Create a business error response.
     *
     * @param processId the process ID
     * @param stepName the step name
     * @param errorCode the error code
     * @param errorMessage the error message
     * @param <T> the type of the result
     * @return business error response
     */
    public static <T> CommandStepResponse<T> businessError(UUID processId, String stepName,
                                                            String errorCode, String errorMessage) {
        return failure(processId, stepName, ErrorType.BUSINESS, errorCode, errorMessage);
    }

    /**
     * Create a timeout failure response.
     *
     * @param processId the process ID
     * @param stepName the step name
     * @param errorMessage the error message
     * @param <T> the type of the result
     * @return timeout failure response
     */
    public static <T> CommandStepResponse<T> timeout(UUID processId, String stepName, String errorMessage) {
        return failure(processId, stepName, ErrorType.TIMEOUT, "TIMEOUT", errorMessage);
    }

    /**
     * Check if this is a transient error that should be retried.
     *
     * @return true if transient or timeout error
     */
    public boolean isRetryable() {
        return errorType == ErrorType.TRANSIENT || errorType == ErrorType.TIMEOUT;
    }

    /**
     * Check if this should go to TSQ.
     *
     * @return true if permanent error
     */
    public boolean shouldMoveToTsq() {
        return errorType == ErrorType.PERMANENT;
    }

    /**
     * Check if this is a business error.
     *
     * @return true if business error
     */
    public boolean isBusinessError() {
        return errorType == ErrorType.BUSINESS;
    }
}
