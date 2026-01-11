package com.commandbus.model;

import java.util.Map;
import java.util.UUID;

/**
 * Reply message from command processing.
 *
 * @param commandId The command this reply is for
 * @param correlationId Optional correlation ID for tracing/process routing
 * @param outcome The processing outcome
 * @param data Result data (nullable)
 * @param errorCode Application error code (nullable)
 * @param errorMessage Error message (nullable)
 * @param errorType Type of error: "PERMANENT", "TRANSIENT", or "BUSINESS_RULE" (nullable)
 */
public record Reply(
    UUID commandId,
    UUID correlationId,
    ReplyOutcome outcome,
    Map<String, Object> data,
    String errorCode,
    String errorMessage,
    String errorType
) {
    /**
     * Create a success reply with result data.
     */
    public static Reply success(UUID commandId, UUID correlationId, Map<String, Object> data) {
        return new Reply(commandId, correlationId, ReplyOutcome.SUCCESS, data, null, null, null);
    }

    /**
     * Create a failed reply with error information (permanent error type).
     */
    public static Reply failed(UUID commandId, UUID correlationId, String errorCode, String errorMessage) {
        return new Reply(commandId, correlationId, ReplyOutcome.FAILED, null, errorCode, errorMessage, "PERMANENT");
    }

    /**
     * Create a failed reply for business rule violation.
     * Process managers will auto-compensate for this error type.
     */
    public static Reply businessRuleFailed(UUID commandId, UUID correlationId, String errorCode, String errorMessage) {
        return new Reply(commandId, correlationId, ReplyOutcome.FAILED, null, errorCode, errorMessage, "BUSINESS_RULE");
    }

    /**
     * Create a canceled reply (from TSQ).
     */
    public static Reply canceled(UUID commandId, UUID correlationId) {
        return new Reply(commandId, correlationId, ReplyOutcome.CANCELED, null, null, null, null);
    }

    /**
     * Check if this reply indicates success.
     */
    public boolean isSuccess() {
        return outcome == ReplyOutcome.SUCCESS;
    }

    /**
     * Check if this reply indicates failure.
     */
    public boolean isFailed() {
        return outcome == ReplyOutcome.FAILED;
    }

    /**
     * Check if this reply indicates cancellation.
     */
    public boolean isCanceled() {
        return outcome == ReplyOutcome.CANCELED;
    }

    /**
     * Check if this is a business rule failure.
     */
    public boolean isBusinessRuleFailure() {
        return "BUSINESS_RULE".equals(errorType);
    }
}
