package com.ivamare.commandbus.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Metadata stored for each command.
 *
 * <p>Unlike Command (immutable), CommandMetadata is mutable and tracks
 * the evolving state of command processing.
 *
 * @param domain The domain this command belongs to
 * @param commandId Unique identifier
 * @param commandType Type of command
 * @param status Current status
 * @param attempts Number of processing attempts
 * @param maxAttempts Maximum allowed attempts
 * @param msgId Current PGMQ message ID (nullable)
 * @param correlationId Correlation ID for tracing (nullable)
 * @param replyTo Reply queue (nullable)
 * @param lastErrorType Type of last error (TRANSIENT/PERMANENT, nullable)
 * @param lastErrorCode Application error code (nullable)
 * @param lastErrorMessage Error message (nullable)
 * @param createdAt Creation timestamp
 * @param updatedAt Last update timestamp
 * @param batchId Optional batch ID (nullable)
 */
public record CommandMetadata(
    String domain,
    UUID commandId,
    String commandType,
    CommandStatus status,
    int attempts,
    int maxAttempts,
    Long msgId,
    UUID correlationId,
    String replyTo,
    String lastErrorType,
    String lastErrorCode,
    String lastErrorMessage,
    Instant createdAt,
    Instant updatedAt,
    UUID batchId
) {
    /**
     * Creates a new command metadata with default values.
     */
    public static CommandMetadata create(
            String domain,
            UUID commandId,
            String commandType,
            int maxAttempts) {
        var now = Instant.now();
        return new CommandMetadata(
            domain, commandId, commandType,
            CommandStatus.PENDING,
            0, maxAttempts,
            null, null, null,
            null, null, null,
            now, now, null
        );
    }

    /**
     * Returns a copy with updated status.
     */
    public CommandMetadata withStatus(CommandStatus newStatus) {
        return new CommandMetadata(
            domain, commandId, commandType,
            newStatus,
            attempts, maxAttempts,
            msgId, correlationId, replyTo,
            lastErrorType, lastErrorCode, lastErrorMessage,
            createdAt, Instant.now(), batchId
        );
    }

    /**
     * Returns a copy with error information.
     */
    public CommandMetadata withError(String errorType, String errorCode, String errorMessage) {
        return new CommandMetadata(
            domain, commandId, commandType,
            status,
            attempts, maxAttempts,
            msgId, correlationId, replyTo,
            errorType, errorCode, errorMessage,
            createdAt, Instant.now(), batchId
        );
    }

    /**
     * Returns a copy with updated reply queue.
     */
    public CommandMetadata withReplyTo(String newReplyTo) {
        return new CommandMetadata(
            domain, commandId, commandType,
            status,
            attempts, maxAttempts,
            msgId, correlationId, newReplyTo,
            lastErrorType, lastErrorCode, lastErrorMessage,
            createdAt, Instant.now(), batchId
        );
    }
}
