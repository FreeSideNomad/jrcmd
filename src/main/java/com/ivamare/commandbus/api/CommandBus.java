package com.ivamare.commandbus.api;

import com.ivamare.commandbus.model.*;
import com.ivamare.commandbus.exception.BatchNotFoundException;
import com.ivamare.commandbus.exception.DuplicateCommandException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Command Bus for sending and managing commands.
 *
 * <p>The CommandBus provides the main API for:
 * <ul>
 *   <li>Sending commands to domain queues</li>
 *   <li>Managing command lifecycle</li>
 *   <li>Creating and tracking batches</li>
 *   <li>Querying command history</li>
 * </ul>
 */
public interface CommandBus {

    // --- Single Command Operations ---

    /**
     * Send a command to a domain queue.
     *
     * @param domain The domain to send to (e.g., "payments")
     * @param commandType The type of command (e.g., "DebitAccount")
     * @param commandId Unique identifier for this command
     * @param data The command payload
     * @return SendResult with command_id and msg_id
     * @throws DuplicateCommandException if command_id exists
     */
    SendResult send(String domain, String commandType, UUID commandId, Map<String, Object> data);

    /**
     * Send a command with additional options.
     *
     * @param domain The domain to send to
     * @param commandType The type of command
     * @param commandId Unique identifier for this command
     * @param data The command payload
     * @param correlationId Optional correlation ID for tracing (nullable)
     * @param replyTo Optional reply queue name (nullable)
     * @param maxAttempts Max retry attempts (nullable, uses default if null)
     * @return SendResult with command_id and msg_id
     * @throws DuplicateCommandException if command_id exists
     */
    SendResult send(
        String domain,
        String commandType,
        UUID commandId,
        Map<String, Object> data,
        UUID correlationId,
        String replyTo,
        Integer maxAttempts
    );

    /**
     * Send a command associated with a batch.
     *
     * @param domain The domain to send to
     * @param commandType The type of command
     * @param commandId Unique identifier for this command
     * @param data The command payload
     * @param batchId The batch to associate with
     * @return SendResult with command_id and msg_id
     * @throws DuplicateCommandException if command_id exists
     * @throws BatchNotFoundException if batch doesn't exist
     */
    SendResult sendToBatch(
        String domain,
        String commandType,
        UUID commandId,
        Map<String, Object> data,
        UUID batchId
    );

    // --- Batch Send Operations ---

    /**
     * Send multiple commands efficiently in batched transactions.
     *
     * <p>Each chunk is processed in a single transaction with one NOTIFY at the end.
     * This is significantly faster than calling send() repeatedly.
     *
     * @param requests List of SendRequest objects
     * @return BatchSendResult with all results and stats
     * @throws DuplicateCommandException if any command_id exists
     */
    BatchSendResult sendBatch(List<SendRequest> requests);

    /**
     * Send multiple commands with custom chunk size.
     *
     * @param requests List of SendRequest objects
     * @param chunkSize Max commands per transaction
     * @return BatchSendResult with all results and stats
     */
    BatchSendResult sendBatch(List<SendRequest> requests, int chunkSize);

    // --- Batch Management ---

    /**
     * Create a batch containing multiple commands atomically.
     *
     * <p>All commands are created in a single transaction - either all succeed or none.
     * The batch is closed immediately after creation (no commands can be added later).
     *
     * @param domain The domain for this batch
     * @param commands List of BatchCommand objects
     * @return CreateBatchResult with batch_id and command results
     * @throws IllegalArgumentException if commands list is empty
     * @throws DuplicateCommandException if any command_id exists
     */
    CreateBatchResult createBatch(String domain, List<BatchCommand> commands);

    /**
     * Create a batch with additional options.
     *
     * @param domain The domain for this batch
     * @param commands List of BatchCommand objects
     * @param batchId Optional batch ID (auto-generated if null)
     * @param name Optional human-readable name
     * @param customData Optional custom metadata
     * @param onComplete Optional callback when batch completes
     * @return CreateBatchResult with batch_id and command results
     */
    CreateBatchResult createBatch(
        String domain,
        List<BatchCommand> commands,
        UUID batchId,
        String name,
        Map<String, Object> customData,
        Consumer<BatchMetadata> onComplete
    );

    /**
     * Get batch metadata.
     *
     * @param domain The domain
     * @param batchId The batch ID
     * @return BatchMetadata or null if not found
     */
    BatchMetadata getBatch(String domain, UUID batchId);

    /**
     * List batches for a domain.
     *
     * @param domain The domain
     * @param status Filter by status (nullable)
     * @param limit Maximum results
     * @param offset Results to skip
     * @return List of BatchMetadata ordered by created_at DESC
     */
    List<BatchMetadata> listBatches(String domain, BatchStatus status, int limit, int offset);

    /**
     * List commands in a batch.
     *
     * @param domain The domain
     * @param batchId The batch ID
     * @param status Filter by status (nullable)
     * @param limit Maximum results
     * @param offset Results to skip
     * @return List of CommandMetadata in the batch
     */
    List<CommandMetadata> listBatchCommands(
        String domain,
        UUID batchId,
        CommandStatus status,
        int limit,
        int offset
    );

    // --- Query Operations ---

    /**
     * Get command metadata.
     *
     * @param domain The domain
     * @param commandId The command ID
     * @return CommandMetadata or null if not found
     */
    CommandMetadata getCommand(String domain, UUID commandId);

    /**
     * Check if a command exists.
     *
     * @param domain The domain
     * @param commandId The command ID
     * @return true if command exists
     */
    boolean commandExists(String domain, UUID commandId);

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
     * @return List of CommandMetadata matching filters
     */
    List<CommandMetadata> queryCommands(
        CommandStatus status,
        String domain,
        String commandType,
        Instant createdAfter,
        Instant createdBefore,
        int limit,
        int offset
    );

    /**
     * Get audit trail for a command.
     *
     * @param commandId The command ID
     * @param domain Optional domain filter (nullable)
     * @return List of AuditEvent in chronological order
     */
    List<AuditEvent> getAuditTrail(UUID commandId, String domain);
}
