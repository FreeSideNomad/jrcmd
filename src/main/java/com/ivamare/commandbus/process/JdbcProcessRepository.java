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
        String sql = """
            UPDATE commandbus.process_audit SET
                reply_outcome = ?,
                reply_data = ?::jsonb,
                received_at = ?
            WHERE domain = ? AND process_id = ? AND command_id = ?
            """;

        jdbc.update(sql,
            entry.replyOutcome() != null ? entry.replyOutcome().name() : null,
            serializeMap(entry.replyData()),
            entry.receivedAt() != null ? Timestamp.from(entry.receivedAt()) : null,
            domain,
            processId,
            commandId
        );
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
}
