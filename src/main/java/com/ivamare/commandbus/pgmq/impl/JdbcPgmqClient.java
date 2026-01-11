package com.ivamare.commandbus.pgmq.impl;

import com.ivamare.commandbus.model.PgmqMessage;
import com.ivamare.commandbus.pgmq.PgmqClient;
import com.ivamare.commandbus.pgmq.QueueNames;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JdbcTemplate-based implementation of PgmqClient.
 */
@Component
public class JdbcPgmqClient implements PgmqClient {

    private static final Logger log = LoggerFactory.getLogger(JdbcPgmqClient.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcPgmqClient(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void createQueue(String queueName) {
        jdbcTemplate.execute("SELECT pgmq.create('" + escapeIdentifier(queueName) + "')");
        log.debug("Created queue: {}", queueName);
    }

    @Override
    public long send(String queueName, Map<String, Object> message) {
        return send(queueName, message, 0);
    }

    @Override
    public long send(String queueName, Map<String, Object> message, int delaySeconds) {
        String json = toJson(message);

        Long msgId = jdbcTemplate.queryForObject(
            "SELECT pgmq.send(?, ?::jsonb, ?)",
            Long.class,
            queueName, json, delaySeconds
        );

        if (msgId == null) {
            throw new RuntimeException("Failed to send message to queue " + queueName);
        }

        // Send NOTIFY to wake up listeners
        String channel = QueueNames.notifyChannel(queueName);
        jdbcTemplate.execute("NOTIFY " + escapeIdentifier(channel));

        log.debug("Sent message to {}: msgId={}", queueName, msgId);
        return msgId;
    }

    @Override
    public List<Long> sendBatch(String queueName, List<Map<String, Object>> messages) {
        return sendBatch(queueName, messages, 0);
    }

    @Override
    public List<Long> sendBatch(String queueName, List<Map<String, Object>> messages, int delaySeconds) {
        if (messages.isEmpty()) {
            return List.of();
        }

        // Convert messages to JSON array
        String[] jsonArray = messages.stream()
            .map(this::toJson)
            .toArray(String[]::new);

        // Use ARRAY constructor for PostgreSQL array
        StringBuilder sql = new StringBuilder("SELECT * FROM pgmq.send_batch(?, ARRAY[");
        for (int i = 0; i < jsonArray.length; i++) {
            if (i > 0) sql.append(",");
            sql.append("?::jsonb");
        }
        sql.append("], ?)");

        Object[] params = new Object[jsonArray.length + 2];
        params[0] = queueName;
        System.arraycopy(jsonArray, 0, params, 1, jsonArray.length);
        params[params.length - 1] = delaySeconds;

        List<Long> msgIds = jdbcTemplate.query(sql.toString(), (rs, rowNum) -> rs.getLong(1), params);

        log.debug("Sent {} messages to {}", msgIds.size(), queueName);
        return msgIds;
    }

    @Override
    public void notify(String queueName) {
        String channel = QueueNames.notifyChannel(queueName);
        jdbcTemplate.execute("NOTIFY " + escapeIdentifier(channel));
        log.debug("Notified channel {}", channel);
    }

    @Override
    public List<PgmqMessage> read(String queueName, int visibilityTimeoutSeconds, int batchSize) {
        return jdbcTemplate.query(
            "SELECT * FROM pgmq.read(?, ?, ?)",
            this::mapToPgmqMessage,
            queueName, visibilityTimeoutSeconds, batchSize
        );
    }

    @Override
    public boolean delete(String queueName, long msgId) {
        Boolean result = jdbcTemplate.queryForObject(
            "SELECT pgmq.delete(?, ?)",
            Boolean.class,
            queueName, msgId
        );
        return Boolean.TRUE.equals(result);
    }

    @Override
    public boolean archive(String queueName, long msgId) {
        Boolean result = jdbcTemplate.queryForObject(
            "SELECT pgmq.archive(?, ?)",
            Boolean.class,
            queueName, msgId
        );
        return Boolean.TRUE.equals(result);
    }

    @Override
    public boolean setVisibilityTimeout(String queueName, long msgId, int visibilityTimeoutSeconds) {
        // pgmq.set_vt returns the updated message row if successful
        List<PgmqMessage> result = jdbcTemplate.query(
            "SELECT * FROM pgmq.set_vt(?, ?, ?)",
            this::mapToPgmqMessage,
            queueName, msgId, visibilityTimeoutSeconds
        );
        return !result.isEmpty();
    }

    @Override
    public Optional<PgmqMessage> getFromArchive(String queueName, String commandId) {
        String archiveTable = QueueNames.archiveTable(queueName);

        List<PgmqMessage> results = jdbcTemplate.query(
            "SELECT * FROM " + archiveTable +
            " WHERE message->>'command_id' = ?" +
            " ORDER BY msg_id DESC LIMIT 1",
            this::mapToPgmqMessage,
            commandId
        );

        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    // --- Helper Methods ---

    private PgmqMessage mapToPgmqMessage(ResultSet rs, int rowNum) throws SQLException {
        long msgId = rs.getLong("msg_id");
        int readCount = rs.getInt("read_ct");

        Timestamp enqueuedAt = rs.getTimestamp("enqueued_at");
        Timestamp vt = rs.getTimestamp("vt");

        String messageJson = rs.getString("message");
        Map<String, Object> message = fromJson(messageJson);

        return new PgmqMessage(
            msgId,
            readCount,
            enqueuedAt != null ? enqueuedAt.toInstant() : null,
            vt != null ? vt.toInstant() : null,
            message
        );
    }

    private String toJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize message to JSON", e);
        }
    }

    private Map<String, Object> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize message from JSON", e);
        }
    }

    /**
     * Escape a SQL identifier to prevent injection.
     * Only allows alphanumeric characters and underscores.
     */
    String escapeIdentifier(String identifier) {
        return identifier.replaceAll("[^a-zA-Z0-9_]", "_");
    }
}
