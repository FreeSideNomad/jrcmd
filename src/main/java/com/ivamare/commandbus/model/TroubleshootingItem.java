package com.ivamare.commandbus.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Represents a command in the troubleshooting queue.
 *
 * @param domain The domain of the command
 * @param commandId Unique identifier of the command
 * @param commandType Type of command
 * @param attempts Number of processing attempts made
 * @param maxAttempts Maximum allowed attempts
 * @param lastErrorType Type of last error (TRANSIENT/PERMANENT)
 * @param lastErrorCode Application error code
 * @param lastErrorMessage Error message
 * @param correlationId Correlation ID for tracing (nullable)
 * @param replyTo Reply queue name (nullable)
 * @param payload Original command payload (nullable)
 * @param createdAt When the command was created
 * @param updatedAt When the command was last updated
 */
public record TroubleshootingItem(
    String domain,
    UUID commandId,
    String commandType,
    int attempts,
    int maxAttempts,
    String lastErrorType,
    String lastErrorCode,
    String lastErrorMessage,
    UUID correlationId,
    String replyTo,
    Map<String, Object> payload,
    Instant createdAt,
    Instant updatedAt
) {
    /**
     * Check if this failure was due to a permanent error.
     *
     * @return true if the error was permanent
     */
    public boolean isPermanentError() {
        return "PERMANENT".equals(lastErrorType);
    }

    /**
     * Check if this failure was due to exhausted retries.
     *
     * @return true if retries were exhausted
     */
    public boolean isRetriesExhausted() {
        return "TRANSIENT".equals(lastErrorType) && attempts >= maxAttempts;
    }

    /**
     * Check if a reply is expected for this command.
     *
     * @return true if reply_to is configured
     */
    public boolean hasReplyTo() {
        return replyTo != null && !replyTo.isBlank();
    }
}
