package com.ivamare.commandbus.e2e.dto;

import com.ivamare.commandbus.model.BatchStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * View model for batch details.
 */
public record BatchView(
    UUID batchId,
    String domain,
    String name,
    BatchStatus status,
    int totalCount,
    int completedCount,
    int canceledCount,
    int inTroubleshootingCount,
    Instant createdAt,
    Instant startedAt,
    Instant completedAt
) {
    public int progressPercent() {
        if (totalCount == 0) return 0;
        return (completedCount * 100) / totalCount;
    }

    public int failedCommands() {
        return canceledCount + inTroubleshootingCount;
    }
}
