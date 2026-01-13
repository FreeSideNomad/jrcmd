package com.ivamare.commandbus.process;

import org.springframework.jdbc.core.JdbcTemplate;

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
}
