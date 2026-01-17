package com.ivamare.commandbus.process;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for process persistence.
 */
public interface ProcessRepository {

    /**
     * Save a new process.
     */
    void save(ProcessMetadata<?, ?> process);

    /**
     * Save a new process within an existing transaction.
     */
    void save(ProcessMetadata<?, ?> process, JdbcTemplate jdbcTemplate);

    /**
     * Update existing process.
     */
    void update(ProcessMetadata<?, ?> process);

    /**
     * Update existing process within an existing transaction.
     */
    void update(ProcessMetadata<?, ?> process, JdbcTemplate jdbcTemplate);

    /**
     * Get process by ID.
     */
    Optional<ProcessMetadata<?, ?>> getById(String domain, UUID processId);

    /**
     * Get process by ID within an existing transaction.
     */
    Optional<ProcessMetadata<?, ?>> getById(String domain, UUID processId, JdbcTemplate jdbcTemplate);

    /**
     * Find processes by status.
     */
    List<ProcessMetadata<?, ?>> findByStatus(String domain, List<ProcessStatus> statuses);

    /**
     * Find processes by type.
     */
    List<ProcessMetadata<?, ?>> findByType(String domain, String processType);

    /**
     * Log a step execution to audit trail.
     */
    void logStep(String domain, UUID processId, ProcessAuditEntry entry);

    /**
     * Log a step execution within an existing transaction.
     */
    void logStep(String domain, UUID processId, ProcessAuditEntry entry, JdbcTemplate jdbcTemplate);

    /**
     * Update step with reply information.
     */
    void updateStepReply(String domain, UUID processId, UUID commandId, ProcessAuditEntry entry);

    /**
     * Update step with reply within an existing transaction.
     */
    void updateStepReply(String domain, UUID processId, UUID commandId, ProcessAuditEntry entry,
                         JdbcTemplate jdbcTemplate);

    /**
     * Get full audit trail for a process.
     */
    List<ProcessAuditEntry> getAuditTrail(String domain, UUID processId);

    /**
     * Get list of completed step names (for compensation).
     */
    List<String> getCompletedSteps(String domain, UUID processId);

    /**
     * Get list of completed step names within an existing transaction.
     */
    List<String> getCompletedSteps(String domain, UUID processId, JdbcTemplate jdbcTemplate);

    /**
     * Atomically update process state using JSONB merge.
     * Avoids read-modify-write cycle by patching state directly in database.
     *
     * @param domain       Process domain
     * @param processId    Process ID
     * @param statePatch   JSONB patch to merge into existing state
     * @param newStep      New step name (null to keep current)
     * @param newStatus    New status (null to keep current)
     * @param errorCode    Error code (null to keep current)
     * @param errorMessage Error message (null to keep current)
     */
    void updateStateAtomic(String domain, UUID processId, String statePatch,
                           String newStep, String newStatus,
                           String errorCode, String errorMessage,
                           JdbcTemplate jdbcTemplate);

    // ========== ProcessStepManager Support ==========

    /**
     * Find processes by execution model and status.
     *
     * @param domain Process domain
     * @param executionModel Execution model (STEP_BASED or PROCESS_STEP)
     * @param status Process status to match
     * @return List of process IDs matching criteria
     */
    default List<UUID> findByExecutionModelAndStatus(String domain, String executionModel, ProcessStatus status) {
        throw new UnsupportedOperationException("Not implemented");
    }

    /**
     * Find processes due for retry.
     *
     * @param domain Process domain
     * @param now Current time - processes with next_retry_at at or before now are returned
     * @return List of process IDs due for retry
     */
    default List<UUID> findDueForRetry(String domain, Instant now) {
        throw new UnsupportedOperationException("Not implemented");
    }

    /**
     * Find processes with expired wait timeouts.
     *
     * @param domain Process domain
     * @param now Current time - processes with next_wait_timeout_at at or before now are returned
     * @return List of process IDs with expired waits
     */
    default List<UUID> findExpiredWaits(String domain, Instant now) {
        throw new UnsupportedOperationException("Not implemented");
    }

    /**
     * Find processes with exceeded deadlines.
     *
     * @param domain Process domain
     * @param now Current time - processes with deadline_at at or before now are returned
     * @return List of process IDs with exceeded deadlines
     */
    default List<UUID> findExpiredDeadlines(String domain, Instant now) {
        throw new UnsupportedOperationException("Not implemented");
    }

    /**
     * Atomically update process state for ProcessStepManager.
     * Extends updateStateAtomic with scheduler fields.
     *
     * @param domain              Process domain
     * @param processId           Process ID
     * @param statePatch          JSONB patch to merge into existing state
     * @param newStep             New step name (null to keep current)
     * @param newStatus           New status (null to keep current)
     * @param errorCode           Error code (null to keep current)
     * @param errorMessage        Error message (null to keep current)
     * @param nextRetryAt         Next retry time (for WAITING_FOR_RETRY)
     * @param nextWaitTimeoutAt   Wait timeout time (for WAITING_FOR_ASYNC)
     * @param currentWait         Current wait name (for WAITING_FOR_ASYNC)
     */
    default void updateStateAtomicStep(String domain, UUID processId, String statePatch,
                                       String newStep, String newStatus,
                                       String errorCode, String errorMessage,
                                       Instant nextRetryAt, Instant nextWaitTimeoutAt,
                                       String currentWait,
                                       JdbcTemplate jdbcTemplate) {
        throw new UnsupportedOperationException("Not implemented");
    }

    /**
     * Get raw state JSON for a process.
     *
     * @param domain Process domain
     * @param processId Process ID
     * @return State as JSON string
     */
    default String getStateJson(String domain, UUID processId, JdbcTemplate jdbcTemplate) {
        throw new UnsupportedOperationException("Not implemented");
    }

    /**
     * Update state JSON directly.
     *
     * @param domain Process domain
     * @param processId Process ID
     * @param stateJson Full state as JSON string
     */
    default void updateState(String domain, UUID processId, String stateJson, JdbcTemplate jdbcTemplate) {
        throw new UnsupportedOperationException("Not implemented");
    }
}
