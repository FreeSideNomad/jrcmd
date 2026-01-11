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
    /**
     * Returns progress percentage based on processed commands (completed + canceled + TSQ).
     */
    public int progressPercent() {
        if (totalCount == 0) return 0;
        int processed = completedCount + canceledCount + inTroubleshootingCount;
        return (processed * 100) / totalCount;
    }

    /**
     * Returns count of commands that failed (canceled + in TSQ).
     */
    public int failedCommands() {
        return canceledCount + inTroubleshootingCount;
    }

    /**
     * Returns count of commands still pending processing.
     */
    public int pendingCount() {
        return totalCount - completedCount - canceledCount - inTroubleshootingCount;
    }
}
