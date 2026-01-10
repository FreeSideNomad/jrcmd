package com.commandbus.model;

import java.util.Map;
import java.util.UUID;

/**
 * A command to be included in a batch.
 *
 * @param commandType The type of command
 * @param commandId Unique identifier for this command
 * @param data The command payload
 * @param correlationId Optional correlation ID (nullable)
 * @param replyTo Optional reply queue name (nullable)
 * @param maxAttempts Max retry attempts (nullable, uses default if null)
 */
public record BatchCommand(
    String commandType,
    UUID commandId,
    Map<String, Object> data,
    UUID correlationId,
    String replyTo,
    Integer maxAttempts
) {
    /**
     * Creates a BatchCommand with validation.
     */
    public BatchCommand {
        if (commandType == null || commandType.isBlank()) {
            throw new IllegalArgumentException("commandType is required");
        }
        if (commandId == null) {
            throw new IllegalArgumentException("commandId is required");
        }
        if (data == null) {
            data = Map.of();
        }
    }

    /**
     * Creates a simple batch command.
     *
     * @param commandType The type of command
     * @param commandId Unique identifier for this command
     * @param data The command payload
     * @return A new BatchCommand instance
     */
    public static BatchCommand of(String commandType, UUID commandId, Map<String, Object> data) {
        return new BatchCommand(commandType, commandId, data, null, null, null);
    }
}
