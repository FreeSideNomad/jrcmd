package com.commandbus.e2e.dto;

import com.commandbus.model.CommandStatus;

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
) {}
