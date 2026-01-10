package com.commandbus.repository.impl;

import com.commandbus.model.BatchMetadata;
import com.commandbus.model.BatchStatus;
import com.commandbus.repository.BatchRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * JDBC implementation of BatchRepository.
 */
@Repository
public class JdbcBatchRepository implements BatchRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RowMapper<BatchMetadata> batchMapper;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    public JdbcBatchRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.batchMapper = (rs, rowNum) -> {
            Map<String, Object> customData = null;
            String customDataJson = rs.getString("custom_data");
            if (customDataJson != null) {
                try {
                    customData = this.objectMapper.readValue(customDataJson, MAP_TYPE);
                } catch (JsonProcessingException e) {
                    // Ignore parse errors
                }
            }

            return new BatchMetadata(
                rs.getString("domain"),
                UUID.fromString(rs.getString("batch_id")),
                rs.getString("name"),
                customData,
                BatchStatus.fromValue(rs.getString("status")),
                rs.getInt("total_count"),
                rs.getInt("completed_count"),
                rs.getInt("canceled_count"),
                rs.getInt("in_troubleshooting_count"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("started_at")),
                toInstant(rs.getTimestamp("completed_at"))
            );
        };
    }

    @Override
    public void save(BatchMetadata metadata) {
        String customDataJson = null;
        if (metadata.customData() != null) {
            try {
                customDataJson = objectMapper.writeValueAsString(metadata.customData());
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to serialize custom_data", e);
            }
        }

        jdbcTemplate.update("""
            INSERT INTO commandbus.batch (
                domain, batch_id, name, custom_data, status,
                total_count, completed_count, canceled_count, in_troubleshooting_count,
                created_at, started_at, completed_at
            ) VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            metadata.domain(),
            metadata.batchId(),
            metadata.name(),
            customDataJson,
            metadata.status().getValue(),
            metadata.totalCount(),
            metadata.completedCount(),
            metadata.canceledCount(),
            metadata.inTroubleshootingCount(),
            Timestamp.from(metadata.createdAt()),
            metadata.startedAt() != null ? Timestamp.from(metadata.startedAt()) : null,
            metadata.completedAt() != null ? Timestamp.from(metadata.completedAt()) : null
        );
    }

    @Override
    public Optional<BatchMetadata> get(String domain, UUID batchId) {
        List<BatchMetadata> results = jdbcTemplate.query(
            "SELECT * FROM commandbus.batch WHERE domain = ? AND batch_id = ?",
            batchMapper,
            domain, batchId
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public boolean exists(String domain, UUID batchId) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM commandbus.batch WHERE domain = ? AND batch_id = ?",
            Integer.class,
            domain, batchId
        );
        return count != null && count > 0;
    }

    @Override
    public List<BatchMetadata> listBatches(String domain, BatchStatus status, int limit, int offset) {
        if (status != null) {
            return jdbcTemplate.query(
                "SELECT * FROM commandbus.batch WHERE domain = ? AND status = ? ORDER BY created_at DESC LIMIT ? OFFSET ?",
                batchMapper,
                domain, status.getValue(), limit, offset
            );
        } else {
            return jdbcTemplate.query(
                "SELECT * FROM commandbus.batch WHERE domain = ? ORDER BY created_at DESC LIMIT ? OFFSET ?",
                batchMapper,
                domain, limit, offset
            );
        }
    }

    @Override
    public boolean tsqRetry(String domain, UUID batchId) {
        Boolean result = jdbcTemplate.queryForObject(
            "SELECT commandbus.sp_tsq_retry(?, ?)",
            Boolean.class,
            domain, batchId
        );
        return Boolean.TRUE.equals(result);
    }

    @Override
    public boolean tsqCancel(String domain, UUID batchId) {
        Boolean result = jdbcTemplate.queryForObject(
            "SELECT commandbus.sp_tsq_cancel(?, ?)",
            Boolean.class,
            domain, batchId
        );
        return Boolean.TRUE.equals(result);
    }

    @Override
    public boolean tsqComplete(String domain, UUID batchId) {
        Boolean result = jdbcTemplate.queryForObject(
            "SELECT commandbus.sp_tsq_complete(?, ?)",
            Boolean.class,
            domain, batchId
        );
        return Boolean.TRUE.equals(result);
    }

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }
}
