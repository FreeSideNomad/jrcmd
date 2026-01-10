package com.commandbus.e2e.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * View model for TSQ (troubleshooting queue) commands.
 */
public record TsqCommandView(
    UUID commandId,
    String domain,
    String commandType,
    Map<String, Object> payload,
    String errorCode,
    String errorMessage,
    int attempts,
    int maxAttempts,
    Instant createdAt,
    Instant updatedAt
) {}
