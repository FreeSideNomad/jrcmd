package com.ivamare.commandbus.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Metadata stored for a batch of commands.
 *
 * @param domain The domain this batch belongs to
 * @param batchId Unique identifier
 * @param name Optional human-readable name (nullable)
 * @param customData Optional custom metadata (nullable)
 * @param status Current batch status
 * @param totalCount Total number of commands in the batch
 * @param completedCount Number of successfully completed commands
 * @param canceledCount Number of canceled commands
 * @param inTroubleshootingCount Number of commands currently in TSQ
 * @param createdAt Batch creation timestamp
 * @param startedAt When first command was processed (nullable)
 * @param completedAt When all commands reached terminal state (nullable)
 * @param batchType Type of batch: "COMMAND" or "PROCESS" (nullable, defaults to COMMAND)
 */
public record BatchMetadata(
    String domain,
    UUID batchId,
    String name,
    Map<String, Object> customData,
    BatchStatus status,
    int totalCount,
    int completedCount,
    int canceledCount,
    int inTroubleshootingCount,
    Instant createdAt,
    Instant startedAt,
    Instant completedAt,
    String batchType
) {
    /**
     * Creates a new batch metadata with default values (COMMAND type).
     */
    public static BatchMetadata create(
            String domain,
            UUID batchId,
            String name,
            Map<String, Object> customData,
            int totalCount) {
        return create(domain, batchId, name, customData, totalCount, "COMMAND");
    }

    /**
     * Creates a new batch metadata with specified type.
     */
    public static BatchMetadata create(
            String domain,
            UUID batchId,
            String name,
            Map<String, Object> customData,
            int totalCount,
            String batchType) {
        return new BatchMetadata(
            domain, batchId, name, customData,
            BatchStatus.PENDING,
            totalCount, 0, 0, 0,
            Instant.now(), null, null,
            batchType
        );
    }

    /**
     * Checks if the batch is complete (all commands in terminal state).
     */
    public boolean isComplete() {
        return status == BatchStatus.COMPLETED || status == BatchStatus.COMPLETED_WITH_FAILURES;
    }

    /**
     * Checks if all commands finished successfully.
     */
    public boolean isFullySuccessful() {
        return status == BatchStatus.COMPLETED && canceledCount == 0 && inTroubleshootingCount == 0;
    }
}
