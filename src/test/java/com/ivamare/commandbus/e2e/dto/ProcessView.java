package com.ivamare.commandbus.e2e.dto;

import com.ivamare.commandbus.process.ProcessStatus;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * View model for process details.
 */
public record ProcessView(
    UUID processId,
    String domain,
    String processType,
    ProcessStatus status,
    String currentStep,
    Map<String, Object> state,
    String errorCode,
    String errorMessage,
    Instant createdAt,
    Instant updatedAt,
    Instant completedAt
) {}
