package com.ivamare.commandbus.model;

import java.util.Map;
import java.util.UUID;

/**
 * Request to send a single command (used in batch operations).
 *
 * @param domain The domain to send to
 * @param commandType The type of command
 * @param commandId Unique identifier for this command
 * @param data The command payload
 * @param correlationId Optional correlation ID (nullable)
 * @param replyTo Optional reply queue name (nullable)
 * @param maxAttempts Max retry attempts (nullable, uses default if null)
 */
public record SendRequest(
    String domain,
    String commandType,
    UUID commandId,
    Map<String, Object> data,
    UUID correlationId,
    String replyTo,
    Integer maxAttempts
) {
    /**
     * Creates a simple send request.
     *
     * @param domain The domain to send to
     * @param commandType The type of command
     * @param commandId Unique identifier for this command
     * @param data The command payload
     * @return A new SendRequest instance
     */
    public static SendRequest of(String domain, String commandType, UUID commandId, Map<String, Object> data) {
        return new SendRequest(domain, commandType, commandId, data, null, null, null);
    }
}
