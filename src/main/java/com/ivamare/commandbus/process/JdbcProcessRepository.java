package com.ivamare.commandbus.process;

import com.ivamare.commandbus.model.ReplyOutcome;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC implementation of ProcessRepository.
 */
@Repository
public class JdbcProcessRepository implements ProcessRepository {

    private static final Logger log = LoggerFactory.getLogger(JdbcProcessRepository.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RowMapper<ProcessMetadata<?, ?>> processMapper;
    private final RowMapper<ProcessAuditEntry> auditMapper;

    public JdbcProcessRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.processMapper = createProcessMapper();
        this.auditMapper = createAuditMapper();
    }

    private RowMapper<ProcessMetadata<?, ?>> createProcessMapper() {
        return (rs, rowNum) -> {
            Map<String, Object> stateMap = deserializeJson(rs.getString("state"));
            // Store current_step in state map for later retrieval by process manager
            String currentStepName = rs.getString("current_step");
            if (currentStepName != null) {
                stateMap = new java.util.HashMap<>(stateMap != null ? stateMap : Map.of());
                stateMap.put("__current_step__", currentStepName);
            }
            ProcessState state = new MapProcessState(stateMap != null ? stateMap : Map.of());

            Timestamp completedAt = rs.getTimestamp("completed_at");

            return new ProcessMetadata<>(
                rs.getString("domain"),
                UUID.fromString(rs.getString("process_id")),
                rs.getString("process_type"),
                state,
                ProcessStatus.valueOf(rs.getString("status")),
                null,  // currentStep - retrieved from state map via __current_step__ key
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                completedAt != null ? completedAt.toInstant() : null,
                rs.getString("error_code"),
                rs.getString("error_message")
            );
        };
    }

    private RowMapper<ProcessAuditEntry> createAuditMapper() {
        return (rs, rowNum) -> {
            String outcomeStr = rs.getString("reply_outcome");
            Timestamp receivedAt = rs.getTimestamp("received_at");

            return new ProcessAuditEntry(
                rs.getString("step_name"),
                UUID.fromString(rs.getString("command_id")),
                rs.getString("command_type"),
                deserializeJson(rs.getString("command_data")),
                rs.getTimestamp("sent_at").toInstant(),
                outcomeStr != null ? ReplyOutcome.valueOf(outcomeStr) : null,
                deserializeJson(rs.getString("reply_data")),
                receivedAt != null ? receivedAt.toInstant() : null
            );
        };
    }

    @Override
    public void save(ProcessMetadata<?, ?> process) {
        save(process, jdbcTemplate);
    }

    @Override
    public void save(ProcessMetadata<?, ?> process, JdbcTemplate jdbc) {
        String sql = """
            INSERT INTO commandbus.process (
                domain, process_id, process_type, status, current_step,
                state, error_code, error_message,
                created_at, updated_at, completed_at
            ) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
            """;

        jdbc.update(sql,
            process.domain(),
            process.processId(),
            process.processType(),
            process.status().name(),
            process.currentStep() != null ? process.currentStep().name() : null,
            serializeState(process.state()),
            process.errorCode(),
            process.errorMessage(),
            Timestamp.from(process.createdAt()),
            Timestamp.from(process.updatedAt()),
            process.completedAt() != null ? Timestamp.from(process.completedAt()) : null
        );

        log.debug("Saved process {}.{}", process.domain(), process.processId());
    }

    /**
     * Save multiple processes in a single batch INSERT for improved performance.
     * Use this method when starting many processes at once.
     *
     * @param processes List of processes to save
     * @param jdbc JdbcTemplate to use for the operation
     */
    public void saveBatch(List<ProcessMetadata<?, ?>> processes, JdbcTemplate jdbc) {
        if (processes.isEmpty()) {
            return;
        }

        String sql = """
            INSERT INTO commandbus.process (
                domain, process_id, process_type, status, current_step,
                state, error_code, error_message,
                created_at, updated_at, completed_at
            ) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
            """;

        List<Object[]> batchArgs = processes.stream()
            .map(p -> new Object[] {
                p.domain(),
                p.processId(),
                p.processType(),
                p.status().name(),
                p.currentStep() != null ? p.currentStep().name() : null,
                serializeState(p.state()),
                p.errorCode(),
                p.errorMessage(),
                Timestamp.from(p.createdAt()),
                Timestamp.from(p.updatedAt()),
                p.completedAt() != null ? Timestamp.from(p.completedAt()) : null
            })
            .toList();

        jdbc.batchUpdate(sql, batchArgs);
        log.debug("Batch saved {} processes", processes.size());
    }

    /**
     * Log multiple step entries in a single batch INSERT for improved performance.
     * Use this method when logging audit entries for many processes at once.
     *
     * @param domain The domain for all entries
     * @param entries List of (processId, auditEntry) pairs to save
     * @param jdbc JdbcTemplate to use for the operation
     */
    public void logBatchSteps(String domain, List<ProcessAuditBatchEntry> entries, JdbcTemplate jdbc) {
        if (entries.isEmpty()) {
            return;
        }

        String sql = """
            INSERT INTO commandbus.process_audit (
                domain, process_id, step_name, command_id, command_type,
                command_data, sent_at, reply_outcome, reply_data, received_at
            ) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?::jsonb, ?)
            """;

        List<Object[]> batchArgs = entries.stream()
            .map(e -> new Object[] {
                domain,
                e.processId(),
                e.entry().stepName(),
                e.entry().commandId(),
                e.entry().commandType(),
                serializeMap(e.entry().commandData()),
                Timestamp.from(e.entry().sentAt()),
                e.entry().replyOutcome() != null ? e.entry().replyOutcome().name() : null,
                serializeMap(e.entry().replyData()),
                e.entry().receivedAt() != null ? Timestamp.from(e.entry().receivedAt()) : null
            })
            .toList();

        jdbc.batchUpdate(sql, batchArgs);
        log.debug("Batch logged {} audit entries", entries.size());
    }

    /**
     * Entry for batch logging process audit entries.
     */
    public record ProcessAuditBatchEntry(UUID processId, ProcessAuditEntry entry) {}

    @Override
    public void update(ProcessMetadata<?, ?> process) {
        update(process, jdbcTemplate);
    }

    @Override
    public void update(ProcessMetadata<?, ?> process, JdbcTemplate jdbc) {
        String sql = """
            UPDATE commandbus.process SET
                status = ?,
                current_step = ?,
                state = ?::jsonb,
                error_code = ?,
                error_message = ?,
                updated_at = NOW(),
                completed_at = ?
            WHERE domain = ? AND process_id = ?
            """;

        jdbc.update(sql,
            process.status().name(),
            process.currentStep() != null ? process.currentStep().name() : null,
            serializeState(process.state()),
            process.errorCode(),
            process.errorMessage(),
            process.completedAt() != null ? Timestamp.from(process.completedAt()) : null,
            process.domain(),
            process.processId()
        );
    }

    @Override
    public Optional<ProcessMetadata<?, ?>> getById(String domain, UUID processId) {
        return getById(domain, processId, jdbcTemplate);
    }

    @Override
    public Optional<ProcessMetadata<?, ?>> getById(String domain, UUID processId, JdbcTemplate jdbc) {
        String sql = """
            SELECT domain, process_id, process_type, status, current_step,
                   state, error_code, error_message,
                   created_at, updated_at, completed_at
            FROM commandbus.process
            WHERE domain = ? AND process_id = ?
            """;

        List<ProcessMetadata<?, ?>> results = jdbc.query(sql, processMapper, domain, processId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public List<ProcessMetadata<?, ?>> findByStatus(String domain, List<ProcessStatus> statuses) {
        if (statuses.isEmpty()) {
            return List.of();
        }

        String placeholders = String.join(",", statuses.stream().map(s -> "?").toList());
        String sql = """
            SELECT domain, process_id, process_type, status, current_step,
                   state, error_code, error_message,
                   created_at, updated_at, completed_at
            FROM commandbus.process
            WHERE domain = ? AND status IN (%s)
            ORDER BY created_at DESC
            """.formatted(placeholders);

        Object[] params = new Object[statuses.size() + 1];
        params[0] = domain;
        for (int i = 0; i < statuses.size(); i++) {
            params[i + 1] = statuses.get(i).name();
        }

        return jdbcTemplate.query(sql, processMapper, params);
    }

    @Override
    public List<ProcessMetadata<?, ?>> findByType(String domain, String processType) {
        String sql = """
            SELECT domain, process_id, process_type, status, current_step,
                   state, error_code, error_message,
                   created_at, updated_at, completed_at
            FROM commandbus.process
            WHERE domain = ? AND process_type = ?
            ORDER BY created_at DESC
            """;

        return jdbcTemplate.query(sql, processMapper, domain, processType);
    }

    @Override
    public void logStep(String domain, UUID processId, ProcessAuditEntry entry) {
        logStep(domain, processId, entry, jdbcTemplate);
    }

    @Override
    public void logStep(String domain, UUID processId, ProcessAuditEntry entry, JdbcTemplate jdbc) {
        String sql = """
            INSERT INTO commandbus.process_audit (
                domain, process_id, step_name, command_id, command_type,
                command_data, sent_at, reply_outcome, reply_data, received_at
            ) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?::jsonb, ?)
            """;

        jdbc.update(sql,
            domain,
            processId,
            entry.stepName(),
            entry.commandId(),
            entry.commandType(),
            serializeMap(entry.commandData()),
            Timestamp.from(entry.sentAt()),
            entry.replyOutcome() != null ? entry.replyOutcome().name() : null,
            serializeMap(entry.replyData()),
            entry.receivedAt() != null ? Timestamp.from(entry.receivedAt()) : null
        );
    }

    @Override
    public void updateStepReply(String domain, UUID processId, UUID commandId, ProcessAuditEntry entry) {
        updateStepReply(domain, processId, commandId, entry, jdbcTemplate);
    }

    @Override
    public void updateStepReply(String domain, UUID processId, UUID commandId,
                                ProcessAuditEntry entry, JdbcTemplate jdbc) {
        // First try to update existing audit row (for replies to commands we sent)
        String updateSql = """
            UPDATE commandbus.process_audit SET
                reply_outcome = ?,
                reply_data = ?::jsonb,
                received_at = ?
            WHERE domain = ? AND process_id = ? AND command_id = ?
            """;

        int updated = jdbc.update(updateSql,
            entry.replyOutcome() != null ? entry.replyOutcome().name() : null,
            serializeMap(entry.replyData()),
            entry.receivedAt() != null ? Timestamp.from(entry.receivedAt()) : null,
            domain,
            processId,
            commandId
        );

        // If no row was updated, this is an external reply (e.g., L1-L4 confirmations).
        // Insert a new audit entry to track it.
        if (updated == 0) {
            String insertSql = """
                INSERT INTO commandbus.process_audit (domain, process_id, step_name, command_id,
                    command_type, command_data, sent_at, reply_outcome, reply_data, received_at)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?::jsonb, ?)
                """;

            jdbc.update(insertSql,
                domain,
                processId,
                entry.stepName(),
                commandId,
                entry.commandType(),
                serializeMap(entry.commandData()),
                entry.sentAt() != null ? Timestamp.from(entry.sentAt()) : null,
                entry.replyOutcome() != null ? entry.replyOutcome().name() : null,
                serializeMap(entry.replyData()),
                entry.receivedAt() != null ? Timestamp.from(entry.receivedAt()) : null
            );
        }
    }

    @Override
    public List<ProcessAuditEntry> getAuditTrail(String domain, UUID processId) {
        String sql = """
            SELECT step_name, command_id, command_type, command_data,
                   sent_at, reply_outcome, reply_data, received_at
            FROM commandbus.process_audit
            WHERE domain = ? AND process_id = ?
            ORDER BY sent_at ASC
            """;

        return jdbcTemplate.query(sql, auditMapper, domain, processId);
    }

    @Override
    public List<String> getCompletedSteps(String domain, UUID processId) {
        return getCompletedSteps(domain, processId, jdbcTemplate);
    }

    @Override
    public List<String> getCompletedSteps(String domain, UUID processId, JdbcTemplate jdbc) {
        String sql = """
            SELECT step_name
            FROM commandbus.process_audit
            WHERE domain = ? AND process_id = ? AND reply_outcome = 'SUCCESS'
            ORDER BY sent_at ASC
            """;

        return jdbc.queryForList(sql, String.class, domain, processId);
    }

    private String serializeState(ProcessState state) {
        if (state == null) return "{}";
        try {
            return objectMapper.writeValueAsString(state.toMap());
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize process state", e);
        }
    }

    private String serializeMap(Map<String, Object> map) {
        if (map == null) return null;
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize map", e);
        }
    }

    private Map<String, Object> deserializeJson(String json) {
        if (json == null) return null;
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize JSON", e);
        }
    }

    @Override
    public void updateStateAtomic(String domain, UUID processId, String statePatch,
                                   String newStep, String newStatus,
                                   String errorCode, String errorMessage,
                                   JdbcTemplate jdbc) {
        // Use queryForObject to handle the VOID return from the function
        // SELECT on a VOID function returns a single row with null
        String sql = "SELECT commandbus.sp_update_process_state(?, ?, ?::jsonb, ?, ?, ?, ?)";

        jdbc.queryForObject(sql, (rs, rowNum) -> null,
            domain,
            processId,
            statePatch,
            newStep,
            newStatus,
            errorCode,
            errorMessage
        );

        log.debug("Atomic state update for process {}.{} - step={}, status={}",
            domain, processId, newStep, newStatus);
    }

    // ========== ProcessStepManager Support ==========

    @Override
    public List<UUID> findByExecutionModelAndStatus(String domain, String executionModel, ProcessStatus status) {
        String sql = """
            SELECT process_id
            FROM commandbus.process
            WHERE domain = ? AND execution_model = ? AND status = ?
            ORDER BY created_at ASC
            """;

        return jdbcTemplate.queryForList(sql, UUID.class, domain, executionModel, status.name());
    }

    @Override
    public List<UUID> findDueForRetry(String domain, Instant now) {
        String sql = """
            SELECT process_id
            FROM commandbus.process
            WHERE domain = ? AND status = 'WAITING_FOR_RETRY'
              AND next_retry_at IS NOT NULL AND next_retry_at <= ?
            ORDER BY next_retry_at ASC
            """;

        return jdbcTemplate.queryForList(sql, UUID.class, domain, Timestamp.from(now));
    }

    @Override
    public List<UUID> findExpiredWaits(String domain, Instant now) {
        String sql = """
            SELECT process_id
            FROM commandbus.process
            WHERE domain = ? AND status = 'WAITING_FOR_ASYNC'
              AND next_wait_timeout_at IS NOT NULL AND next_wait_timeout_at <= ?
            ORDER BY next_wait_timeout_at ASC
            """;

        return jdbcTemplate.queryForList(sql, UUID.class, domain, Timestamp.from(now));
    }

    @Override
    public List<UUID> findExpiredDeadlines(String domain, Instant now) {
        String sql = """
            SELECT process_id
            FROM commandbus.process
            WHERE domain = ?
              AND status NOT IN ('COMPLETED', 'FAILED', 'COMPENSATED', 'CANCELED')
              AND deadline_at IS NOT NULL AND deadline_at <= ?
            ORDER BY deadline_at ASC
            """;

        return jdbcTemplate.queryForList(sql, UUID.class, domain, Timestamp.from(now));
    }

    @Override
    public void updateStateAtomicStep(String domain, UUID processId, String statePatch,
                                      String newStep, String newStatus,
                                      String errorCode, String errorMessage,
                                      Instant nextRetryAt, Instant nextWaitTimeoutAt,
                                      String currentWait,
                                      JdbcTemplate jdbc) {
        String sql = "SELECT commandbus.sp_update_process_state_step(?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?)";

        jdbc.queryForObject(sql, (rs, rowNum) -> null,
            domain,
            processId,
            statePatch,
            newStep,
            newStatus,
            errorCode,
            errorMessage,
            nextRetryAt != null ? Timestamp.from(nextRetryAt) : null,
            nextWaitTimeoutAt != null ? Timestamp.from(nextWaitTimeoutAt) : null,
            currentWait
        );

        log.debug("Atomic step state update for process {}.{} - step={}, status={}, wait={}",
            domain, processId, newStep, newStatus, currentWait);
    }

    @Override
    public String getStateJson(String domain, UUID processId, JdbcTemplate jdbc) {
        String sql = "SELECT state::text FROM commandbus.process WHERE domain = ? AND process_id = ?";
        return jdbc.queryForObject(sql, String.class, domain, processId);
    }

    @Override
    public void updateState(String domain, UUID processId, String stateJson, JdbcTemplate jdbc) {
        String sql = "UPDATE commandbus.process SET state = ?::jsonb, updated_at = NOW() WHERE domain = ? AND process_id = ?";
        jdbc.update(sql, stateJson, domain, processId);
    }
}
