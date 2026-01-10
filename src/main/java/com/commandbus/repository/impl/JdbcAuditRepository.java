package com.commandbus.repository.impl;

import com.commandbus.model.AuditEvent;
import com.commandbus.repository.AuditRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * JDBC implementation of AuditRepository.
 */
@Repository
public class JdbcAuditRepository implements AuditRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RowMapper<AuditEvent> auditMapper;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    public JdbcAuditRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.auditMapper = (rs, rowNum) -> {
            Map<String, Object> details = null;
            String detailsJson = rs.getString("details_json");
            if (detailsJson != null) {
                try {
                    details = this.objectMapper.readValue(detailsJson, MAP_TYPE);
                } catch (JsonProcessingException e) {
                    // Ignore parse errors
                }
            }

            return new AuditEvent(
                rs.getLong("audit_id"),
                rs.getString("domain"),
                UUID.fromString(rs.getString("command_id")),
                rs.getString("event_type"),
                rs.getTimestamp("ts").toInstant(),
                details
            );
        };
    }

    @Override
    public void log(String domain, UUID commandId, String eventType, Map<String, Object> details) {
        String detailsJson = serializeDetails(details);

        jdbcTemplate.update(
            "INSERT INTO commandbus.audit (domain, command_id, event_type, details_json) VALUES (?, ?, ?, ?::jsonb)",
            domain, commandId, eventType, detailsJson
        );
    }

    @Override
    public void logBatch(List<AuditEventRecord> events) {
        if (events.isEmpty()) return;

        String sql = "INSERT INTO commandbus.audit (domain, command_id, event_type, details_json) VALUES (?, ?, ?, ?::jsonb)";

        List<Object[]> batchArgs = events.stream()
            .map(e -> new Object[]{
                e.domain(),
                e.commandId(),
                e.eventType(),
                serializeDetails(e.details())
            })
            .toList();

        jdbcTemplate.batchUpdate(sql, batchArgs);
    }

    @Override
    public List<AuditEvent> getEvents(UUID commandId, String domain) {
        if (domain != null) {
            return jdbcTemplate.query(
                "SELECT * FROM commandbus.audit WHERE command_id = ? AND domain = ? ORDER BY ts ASC",
                auditMapper,
                commandId, domain
            );
        } else {
            return jdbcTemplate.query(
                "SELECT * FROM commandbus.audit WHERE command_id = ? ORDER BY ts ASC",
                auditMapper,
                commandId
            );
        }
    }

    private String serializeDetails(Map<String, Object> details) {
        if (details == null || details.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize audit details", e);
        }
    }
}
