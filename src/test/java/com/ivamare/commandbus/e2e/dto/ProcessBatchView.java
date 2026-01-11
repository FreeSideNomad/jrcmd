package com.ivamare.commandbus.e2e.dto;

import com.ivamare.commandbus.model.BatchStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * View model for process batch display.
 */
public record ProcessBatchView(
    UUID batchId,
    String domain,
    String name,
    BatchStatus status,
    int totalCount,
    int completedCount,
    int failedCount,
    int inProgressCount,
    int waitingForTsqCount,
    Instant createdAt,
    Instant completedAt
) {
    /**
     * Calculate progress percentage based on terminal states.
     */
    public int progressPercent() {
        if (totalCount == 0) return 0;
        int terminal = completedCount + failedCount + waitingForTsqCount;
        return (int) Math.round((terminal * 100.0) / totalCount);
    }

    /**
     * Check if batch is complete (all processes in terminal state).
     */
    public boolean isComplete() {
        return completedCount + failedCount + waitingForTsqCount >= totalCount;
    }

    /**
     * Get count of successful processes.
     */
    public int successCount() {
        return completedCount;
    }

    /**
     * Get count of processes that need attention.
     */
    public int needsAttentionCount() {
        return failedCount + waitingForTsqCount;
    }
}
