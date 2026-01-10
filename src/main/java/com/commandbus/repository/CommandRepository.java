package com.commandbus.repository;

import com.commandbus.model.CommandMetadata;
import com.commandbus.model.CommandStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Repository for command metadata.
 */
public interface CommandRepository {

    /**
     * Save command metadata.
     *
     * @param metadata The command metadata to save
     * @param queueName The queue name for this command
     */
    void save(CommandMetadata metadata, String queueName);

    /**
     * Save multiple command metadata records.
     *
     * @param metadataList List of metadata to save
     * @param queueName The queue name for these commands
     */
    void saveBatch(List<CommandMetadata> metadataList, String queueName);

    /**
     * Get command by domain and command ID.
     *
     * @param domain The domain
     * @param commandId The command ID
     * @return Optional containing metadata if found
     */
    Optional<CommandMetadata> get(String domain, UUID commandId);

    /**
     * Check if command exists.
     *
     * @param domain The domain
     * @param commandId The command ID
     * @return true if command exists
     */
    boolean exists(String domain, UUID commandId);

    /**
     * Check which command IDs exist from a list.
     *
     * @param domain The domain
     * @param commandIds List of command IDs to check
     * @return Set of command IDs that exist
     */
    Set<UUID> existsBatch(String domain, List<UUID> commandIds);

    /**
     * Update command status.
     *
     * @param domain The domain
     * @param commandId The command ID
     * @param status New status
     */
    void updateStatus(String domain, UUID commandId, CommandStatus status);

    /**
     * Query commands with filters.
     *
     * @param status Filter by status (nullable)
     * @param domain Filter by domain (nullable)
     * @param commandType Filter by command type (nullable)
     * @param createdAfter Filter by created_at greater than or equal (nullable)
     * @param createdBefore Filter by created_at less than or equal (nullable)
     * @param limit Maximum results
     * @param offset Results to skip
     * @return List of matching commands
     */
    List<CommandMetadata> query(
        CommandStatus status,
        String domain,
        String commandType,
        Instant createdAfter,
        Instant createdBefore,
        int limit,
        int offset
    );

    /**
     * List commands in a batch.
     *
     * @param domain The domain
     * @param batchId The batch ID
     * @param status Filter by status (nullable)
     * @param limit Maximum results
     * @param offset Results to skip
     * @return List of commands in the batch
     */
    List<CommandMetadata> listByBatch(
        String domain,
        UUID batchId,
        CommandStatus status,
        int limit,
        int offset
    );

    // --- Stored Procedure Wrappers ---

    /**
     * Atomically receive a command (stored procedure wrapper).
     *
     * <p>Combines: get metadata + increment attempts + update status + insert audit
     *
     * @param domain The domain
     * @param commandId The command ID
     * @param msgId The PGMQ message ID (nullable)
     * @param maxAttempts Max attempts override (nullable)
     * @return Optional containing updated metadata if found and receivable
     */
    Optional<CommandMetadata> spReceiveCommand(
        String domain,
        UUID commandId,
        Long msgId,
        Integer maxAttempts
    );

    /**
     * Atomically finish a command (stored procedure wrapper).
     *
     * <p>Combines: update status/error + insert audit + update batch counters
     *
     * @param domain The domain
     * @param commandId The command ID
     * @param status Target status
     * @param eventType Audit event type
     * @param errorType Error type (nullable)
     * @param errorCode Error code (nullable)
     * @param errorMessage Error message (nullable)
     * @param details Audit details (nullable)
     * @param batchId Batch ID (nullable)
     * @return true if batch is now complete
     */
    boolean spFinishCommand(
        String domain,
        UUID commandId,
        CommandStatus status,
        String eventType,
        String errorType,
        String errorCode,
        String errorMessage,
        String details,
        UUID batchId
    );

    /**
     * Record a transient failure (stored procedure wrapper).
     *
     * @param domain The domain
     * @param commandId The command ID
     * @param errorType Error type
     * @param errorCode Error code
     * @param errorMessage Error message
     * @param attempt Current attempt number
     * @param maxAttempts Max attempts
     * @param msgId PGMQ message ID
     * @return true if recorded
     */
    boolean spFailCommand(
        String domain,
        UUID commandId,
        String errorType,
        String errorCode,
        String errorMessage,
        int attempt,
        int maxAttempts,
        long msgId
    );
}
