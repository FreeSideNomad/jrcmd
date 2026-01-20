package com.ivamare.commandbus.e2e.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * View model for processes in the Troubleshooting Queue (WAITING_FOR_TSQ status).
 * Used for displaying ProcessStepManager processes that need operator intervention.
 */
public record TsqProcessView(
    UUID processId,
    String domain,
    String processType,
    String currentStep,
    String currentWait,
    String errorCode,
    String errorMessage,
    Instant createdAt,
    Instant updatedAt,
    UUID paymentId
) {}
