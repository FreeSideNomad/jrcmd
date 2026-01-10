package com.commandbus.repository.impl;

import com.commandbus.model.CommandMetadata;
import com.commandbus.model.CommandStatus;
import com.commandbus.repository.CommandRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/**
 * JDBC implementation of CommandRepository.
 */
@Repository
public class JdbcCommandRepository implements CommandRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<CommandMetadata> METADATA_MAPPER = (rs, rowNum) -> {
        return new CommandMetadata(
            rs.getString("domain"),
            UUID.fromString(rs.getString("command_id")),
            rs.getString("command_type"),
            CommandStatus.fromValue(rs.getString("status")),
            rs.getInt("attempts"),
            rs.getInt("max_attempts"),
            rs.getObject("msg_id") != null ? rs.getLong("msg_id") : null,
            rs.getString("correlation_id") != null ?
                UUID.fromString(rs.getString("correlation_id")) : null,
            rs.getString("reply_queue"),
            rs.getString("last_error_type"),
            rs.getString("last_error_code"),
            rs.getString("last_error_msg"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at")),
            rs.getString("batch_id") != null ?
                UUID.fromString(rs.getString("batch_id")) : null
        );
    };

    public JdbcCommandRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void save(CommandMetadata metadata, String queueName) {
        jdbcTemplate.update("""
            INSERT INTO commandbus.command (
                domain, queue_name, msg_id, command_id, command_type,
                status, attempts, max_attempts, correlation_id, reply_queue,
                batch_id, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            metadata.domain(),
            queueName,
            metadata.msgId(),
            metadata.commandId(),
            metadata.commandType(),
            metadata.status().getValue(),
            metadata.attempts(),
            metadata.maxAttempts(),
            metadata.correlationId(),
            metadata.replyTo() != null ? metadata.replyTo() : "",
            metadata.batchId(),
            Timestamp.from(metadata.createdAt()),
            Timestamp.from(metadata.updatedAt())
        );
    }

    @Override
    public void saveBatch(List<CommandMetadata> metadataList, String queueName) {
        if (metadataList.isEmpty()) return;

        String sql = """
            INSERT INTO commandbus.command (
                domain, queue_name, msg_id, command_id, command_type,
                status, attempts, max_attempts, correlation_id, reply_queue,
                batch_id, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        List<Object[]> batchArgs = metadataList.stream()
            .map(m -> new Object[]{
                m.domain(), queueName, m.msgId(), m.commandId(), m.commandType(),
                m.status().getValue(), m.attempts(), m.maxAttempts(),
                m.correlationId(), m.replyTo() != null ? m.replyTo() : "", m.batchId(),
                Timestamp.from(m.createdAt()), Timestamp.from(m.updatedAt())
            })
            .toList();

        jdbcTemplate.batchUpdate(sql, batchArgs);
    }

    @Override
    public Optional<CommandMetadata> get(String domain, UUID commandId) {
        List<CommandMetadata> results = jdbcTemplate.query(
            "SELECT * FROM commandbus.command WHERE domain = ? AND command_id = ?",
            METADATA_MAPPER,
            domain, commandId
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public boolean exists(String domain, UUID commandId) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM commandbus.command WHERE domain = ? AND command_id = ?",
            Integer.class,
            domain, commandId
        );
        return count != null && count > 0;
    }

    @Override
    public Set<UUID> existsBatch(String domain, List<UUID> commandIds) {
        if (commandIds.isEmpty()) return Set.of();

        String placeholders = String.join(",", Collections.nCopies(commandIds.size(), "?"));
        Object[] params = new Object[commandIds.size() + 1];
        params[0] = domain;
        for (int i = 0; i < commandIds.size(); i++) {
            params[i + 1] = commandIds.get(i);
        }

        List<UUID> existing = jdbcTemplate.query(
            "SELECT command_id FROM commandbus.command WHERE domain = ? AND command_id IN (" + placeholders + ")",
            (rs, rowNum) -> UUID.fromString(rs.getString("command_id")),
            params
        );

        return new HashSet<>(existing);
    }

    @Override
    public void updateStatus(String domain, UUID commandId, CommandStatus status) {
        jdbcTemplate.update(
            "UPDATE commandbus.command SET status = ?, updated_at = NOW() WHERE domain = ? AND command_id = ?",
            status.getValue(), domain, commandId
        );
    }

    @Override
    public List<CommandMetadata> query(
            CommandStatus status,
            String domain,
            String commandType,
            Instant createdAfter,
            Instant createdBefore,
            int limit,
            int offset) {

        StringBuilder sql = new StringBuilder("SELECT * FROM commandbus.command WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (status != null) {
            sql.append(" AND status = ?");
            params.add(status.getValue());
        }
        if (domain != null) {
            sql.append(" AND domain = ?");
            params.add(domain);
        }
        if (commandType != null) {
            sql.append(" AND command_type = ?");
            params.add(commandType);
        }
        if (createdAfter != null) {
            sql.append(" AND created_at >= ?");
            params.add(Timestamp.from(createdAfter));
        }
        if (createdBefore != null) {
            sql.append(" AND created_at <= ?");
            params.add(Timestamp.from(createdBefore));
        }

        sql.append(" ORDER BY created_at DESC LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);

        return jdbcTemplate.query(sql.toString(), METADATA_MAPPER, params.toArray());
    }

    @Override
    public List<CommandMetadata> listByBatch(
            String domain,
            UUID batchId,
            CommandStatus status,
            int limit,
            int offset) {

        StringBuilder sql = new StringBuilder(
            "SELECT * FROM commandbus.command WHERE domain = ? AND batch_id = ?"
        );
        List<Object> params = new ArrayList<>(List.of(domain, batchId));

        if (status != null) {
            sql.append(" AND status = ?");
            params.add(status.getValue());
        }

        sql.append(" ORDER BY created_at ASC LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);

        return jdbcTemplate.query(sql.toString(), METADATA_MAPPER, params.toArray());
    }

    @Override
    public Optional<CommandMetadata> spReceiveCommand(
            String domain,
            UUID commandId,
            Long msgId,
            Integer maxAttempts) {

        List<CommandMetadata> results = jdbcTemplate.query(
            "SELECT * FROM commandbus.sp_receive_command(?, ?, 'IN_PROGRESS', ?, ?)",
            METADATA_MAPPER,
            domain, commandId, msgId, maxAttempts
        );

        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public boolean spFinishCommand(
            String domain,
            UUID commandId,
            CommandStatus status,
            String eventType,
            String errorType,
            String errorCode,
            String errorMessage,
            String details,
            UUID batchId) {

        Boolean result = jdbcTemplate.queryForObject(
            "SELECT commandbus.sp_finish_command(?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)",
            Boolean.class,
            domain, commandId, status.getValue(), eventType,
            errorType, errorCode, errorMessage, details, batchId
        );

        return Boolean.TRUE.equals(result);
    }

    @Override
    public boolean spFailCommand(
            String domain,
            UUID commandId,
            String errorType,
            String errorCode,
            String errorMessage,
            int attempt,
            int maxAttempts,
            long msgId) {

        Boolean result = jdbcTemplate.queryForObject(
            "SELECT commandbus.sp_fail_command(?, ?, ?, ?, ?, ?, ?, ?)",
            Boolean.class,
            domain, commandId, errorType, errorCode, errorMessage,
            attempt, maxAttempts, msgId
        );

        return Boolean.TRUE.equals(result);
    }

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }
}
