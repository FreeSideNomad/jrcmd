package com.ivamare.commandbus.e2e.dto;

import com.ivamare.commandbus.model.CommandStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * View model for command details.
 */
public record CommandView(
    UUID commandId,
    String domain,
    String commandType,
    CommandStatus status,
    UUID correlationId,
    String replyTo,
    UUID batchId,
    int attempts,
    int maxAttempts,
    Instant createdAt,
    Instant updatedAt,
    String errorCode,
    String errorMessage
) {
    /**
     * Returns the processing duration in milliseconds (updatedAt - createdAt).
     * Returns null if either timestamp is null.
     */
    public Long durationMs() {
        if (createdAt == null || updatedAt == null) {
            return null;
        }
        return Duration.between(createdAt, updatedAt).toMillis();
    }

    /**
     * Returns formatted duration string (e.g., "1.234s", "45ms").
     */
    public String durationFormatted() {
        Long ms = durationMs();
        if (ms == null) return "";
        if (ms >= 1000) {
            return String.format("%.3fs", ms / 1000.0);
        }
        return ms + "ms";
    }
}
