package com.commandbus.repository;

import com.commandbus.model.BatchMetadata;
import com.commandbus.model.BatchStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for batch metadata.
 */
public interface BatchRepository {

    /**
     * Save batch metadata.
     *
     * @param metadata The batch metadata to save
     */
    void save(BatchMetadata metadata);

    /**
     * Get batch by domain and batch ID.
     *
     * @param domain The domain
     * @param batchId The batch ID
     * @return Optional containing metadata if found
     */
    Optional<BatchMetadata> get(String domain, UUID batchId);

    /**
     * Check if batch exists.
     *
     * @param domain The domain
     * @param batchId The batch ID
     * @return true if batch exists
     */
    boolean exists(String domain, UUID batchId);

    /**
     * List batches for a domain.
     *
     * @param domain The domain
     * @param status Filter by status (nullable)
     * @param limit Maximum results
     * @param offset Results to skip
     * @return List of batches ordered by created_at DESC
     */
    List<BatchMetadata> listBatches(String domain, BatchStatus status, int limit, int offset);

    /**
     * Update batch counters when command moves to TSQ and operator retries.
     *
     * @param domain The domain
     * @param batchId The batch ID
     * @return false (retry never completes a batch)
     */
    boolean tsqRetry(String domain, UUID batchId);

    /**
     * Update batch counters when operator cancels from TSQ.
     *
     * @param domain The domain
     * @param batchId The batch ID
     * @return true if batch is now complete
     */
    boolean tsqCancel(String domain, UUID batchId);

    /**
     * Update batch counters when operator completes from TSQ.
     *
     * @param domain The domain
     * @param batchId The batch ID
     * @return true if batch is now complete
     */
    boolean tsqComplete(String domain, UUID batchId);
}
