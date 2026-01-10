package com.commandbus.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A command to be processed by a handler.
 *
 * <p>Commands are immutable value objects representing work to be done.
 * They contain the command payload and metadata needed for routing and tracing.
 *
 * @param domain The domain this command belongs to (e.g., "payments")
 * @param commandType The type of command (e.g., "DebitAccount")
 * @param commandId Unique identifier for this command
 * @param data The command payload as a map
 * @param correlationId ID for tracing related commands (nullable)
 * @param replyTo Queue to send reply to (nullable)
 * @param createdAt When the command was created
 */
public record Command(
    String domain,
    String commandType,
    UUID commandId,
    Map<String, Object> data,
    UUID correlationId,
    String replyTo,
    Instant createdAt
) {
    /**
     * Creates a command with validation.
     */
    public Command {
        if (domain == null || domain.isBlank()) {
            throw new IllegalArgumentException("domain is required");
        }
        if (commandType == null || commandType.isBlank()) {
            throw new IllegalArgumentException("commandType is required");
        }
        if (commandId == null) {
            throw new IllegalArgumentException("commandId is required");
        }
        if (data == null) {
            data = Map.of();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        // Make data immutable
        data = Map.copyOf(data);
    }
}
