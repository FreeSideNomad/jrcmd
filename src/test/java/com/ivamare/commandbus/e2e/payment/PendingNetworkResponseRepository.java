package com.ivamare.commandbus.e2e.payment;

import com.ivamare.commandbus.e2e.payment.PendingNetworkResponse.ResponseStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for managing pending network response queue items.
 */
@Repository
public class PendingNetworkResponseRepository {

    private final JdbcTemplate jdbcTemplate;

    public PendingNetworkResponseRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final String INSERT_SQL = """
        INSERT INTO e2e.pending_network_response (
            id, payment_id, process_id, correlation_id, command_id,
            level, execution_model, status, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

    private static final String UPDATE_SQL = """
        UPDATE e2e.pending_network_response SET
            status = ?, resolved_at = ?, resolved_by = ?, resolution_notes = ?
        WHERE id = ?
        """;

    private static final String SELECT_BASE = """
        SELECT id, payment_id, process_id, correlation_id, command_id,
               level, execution_model, status, created_at, resolved_at, resolved_by, resolution_notes
        FROM e2e.pending_network_response
        """;

    private final RowMapper<PendingNetworkResponse> rowMapper = (rs, rowNum) -> mapRow(rs);

    private PendingNetworkResponse mapRow(ResultSet rs) throws SQLException {
        String paymentIdStr = rs.getString("payment_id");
        return new PendingNetworkResponse(
            UUID.fromString(rs.getString("id")),
            paymentIdStr != null ? UUID.fromString(paymentIdStr) : null,
            UUID.fromString(rs.getString("process_id")),
            UUID.fromString(rs.getString("correlation_id")),
            UUID.fromString(rs.getString("command_id")),
            rs.getInt("level"),
            rs.getString("execution_model"),
            ResponseStatus.valueOf(rs.getString("status")),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("resolved_at")),
            rs.getString("resolved_by"),
            rs.getString("resolution_notes")
        );
    }

    private Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }

    private Timestamp toTimestamp(Instant instant) {
        return instant != null ? Timestamp.from(instant) : null;
    }

    /**
     * Save a new pending network response.
     */
    public void save(PendingNetworkResponse response) {
        jdbcTemplate.update(INSERT_SQL,
            response.id(),
            response.paymentId(),
            response.processId(),
            response.correlationId(),
            response.commandId(),
            response.level(),
            response.executionModel(),
            response.status().name(),
            toTimestamp(response.createdAt())
        );
    }

    /**
     * Update pending network response (for resolution).
     */
    public void update(PendingNetworkResponse response) {
        jdbcTemplate.update(UPDATE_SQL,
            response.status().name(),
            toTimestamp(response.resolvedAt()),
            response.resolvedBy(),
            response.resolutionNotes(),
            response.id()
        );
    }

    /**
     * Find pending network response by ID.
     */
    public Optional<PendingNetworkResponse> findById(UUID id) {
        List<PendingNetworkResponse> results = jdbcTemplate.query(
            SELECT_BASE + " WHERE id = ?",
            rowMapper,
            id
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Find all pending responses (status = PENDING).
     */
    public List<PendingNetworkResponse> findAllPending() {
        return jdbcTemplate.query(
            SELECT_BASE + " WHERE status = 'PENDING' ORDER BY created_at ASC",
            rowMapper
        );
    }

    /**
     * Find pending responses by level.
     */
    public List<PendingNetworkResponse> findPendingByLevel(int level) {
        return jdbcTemplate.query(
            SELECT_BASE + " WHERE status = 'PENDING' AND level = ? ORDER BY created_at ASC",
            rowMapper,
            level
        );
    }

    /**
     * Find pending responses with pagination.
     */
    public List<PendingNetworkResponse> findAllPending(int limit, int offset) {
        return jdbcTemplate.query(
            SELECT_BASE + " WHERE status = 'PENDING' ORDER BY level ASC, created_at ASC LIMIT ? OFFSET ?",
            rowMapper,
            limit, offset
        );
    }

    /**
     * Count pending responses.
     */
    public int countPending() {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM e2e.pending_network_response WHERE status = 'PENDING'",
            Integer.class
        );
        return count != null ? count : 0;
    }

    /**
     * Count pending responses by level.
     */
    public int countPendingByLevel(int level) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM e2e.pending_network_response WHERE status = 'PENDING' AND level = ?",
            Integer.class,
            level
        );
        return count != null ? count : 0;
    }

    /**
     * Find pending response by process ID and level.
     */
    public Optional<PendingNetworkResponse> findByProcessIdAndLevel(UUID processId, int level) {
        List<PendingNetworkResponse> results = jdbcTemplate.query(
            SELECT_BASE + " WHERE process_id = ? AND level = ? AND status = 'PENDING'",
            rowMapper,
            processId, level
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Delete pending response by ID.
     */
    public void deleteById(UUID id) {
        jdbcTemplate.update("DELETE FROM e2e.pending_network_response WHERE id = ?", id);
    }

    /**
     * Delete all resolved responses (cleanup).
     */
    public int deleteResolved() {
        return jdbcTemplate.update("DELETE FROM e2e.pending_network_response WHERE status != 'PENDING'");
    }
}
