package com.ivamare.commandbus.e2e.payment;

import com.ivamare.commandbus.e2e.payment.PendingApproval.ApprovalStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for managing pending approval queue items.
 */
@Repository
public class PendingApprovalRepository {

    private final JdbcTemplate jdbcTemplate;

    public PendingApprovalRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final String INSERT_SQL = """
        INSERT INTO e2e.pending_approval (
            id, payment_id, process_id, correlation_id, command_id,
            amount, currency, debit_account, credit_account,
            execution_model, status, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

    private static final String UPDATE_SQL = """
        UPDATE e2e.pending_approval SET
            status = ?, resolved_at = ?, resolved_by = ?, resolution_notes = ?
        WHERE id = ?
        """;

    private static final String SELECT_BASE = """
        SELECT id, payment_id, process_id, correlation_id, command_id,
               amount, currency, debit_account, credit_account,
               execution_model, status, created_at, resolved_at, resolved_by, resolution_notes
        FROM e2e.pending_approval
        """;

    private final RowMapper<PendingApproval> rowMapper = (rs, rowNum) -> mapRow(rs);

    private PendingApproval mapRow(ResultSet rs) throws SQLException {
        String correlationIdStr = rs.getString("correlation_id");
        String commandIdStr = rs.getString("command_id");
        return new PendingApproval(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("payment_id")),
            UUID.fromString(rs.getString("process_id")),
            correlationIdStr != null ? UUID.fromString(correlationIdStr) : null,
            commandIdStr != null ? UUID.fromString(commandIdStr) : null,
            rs.getBigDecimal("amount"),
            rs.getString("currency"),
            rs.getString("debit_account"),
            rs.getString("credit_account"),
            rs.getString("execution_model"),
            ApprovalStatus.valueOf(rs.getString("status")),
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
     * Save a new pending approval.
     */
    public void save(PendingApproval approval) {
        jdbcTemplate.update(INSERT_SQL,
            approval.id(),
            approval.paymentId(),
            approval.processId(),
            approval.correlationId(),
            approval.commandId(),
            approval.amount(),
            approval.currency(),
            approval.debitAccount(),
            approval.creditAccount(),
            approval.executionModel(),
            approval.status().name(),
            toTimestamp(approval.createdAt())
        );
    }

    /**
     * Update pending approval (for resolution).
     */
    public void update(PendingApproval approval) {
        jdbcTemplate.update(UPDATE_SQL,
            approval.status().name(),
            toTimestamp(approval.resolvedAt()),
            approval.resolvedBy(),
            approval.resolutionNotes(),
            approval.id()
        );
    }

    /**
     * Find pending approval by ID.
     */
    public Optional<PendingApproval> findById(UUID id) {
        List<PendingApproval> results = jdbcTemplate.query(
            SELECT_BASE + " WHERE id = ?",
            rowMapper,
            id
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Find all pending approvals (status = PENDING).
     */
    public List<PendingApproval> findAllPending() {
        return jdbcTemplate.query(
            SELECT_BASE + " WHERE status = 'PENDING' ORDER BY created_at ASC",
            rowMapper
        );
    }

    /**
     * Find pending approvals with pagination.
     */
    public List<PendingApproval> findAllPending(int limit, int offset) {
        return jdbcTemplate.query(
            SELECT_BASE + " WHERE status = 'PENDING' ORDER BY created_at ASC LIMIT ? OFFSET ?",
            rowMapper,
            limit, offset
        );
    }

    /**
     * Count pending approvals.
     */
    public int countPending() {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM e2e.pending_approval WHERE status = 'PENDING'",
            Integer.class
        );
        return count != null ? count : 0;
    }

    /**
     * Find pending approval by payment ID.
     */
    public Optional<PendingApproval> findByPaymentId(UUID paymentId) {
        List<PendingApproval> results = jdbcTemplate.query(
            SELECT_BASE + " WHERE payment_id = ? AND status = 'PENDING'",
            rowMapper,
            paymentId
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Find pending approval by process ID.
     */
    public Optional<PendingApproval> findByProcessId(UUID processId) {
        List<PendingApproval> results = jdbcTemplate.query(
            SELECT_BASE + " WHERE process_id = ? AND status = 'PENDING'",
            rowMapper,
            processId
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Delete pending approval by ID.
     */
    public void deleteById(UUID id) {
        jdbcTemplate.update("DELETE FROM e2e.pending_approval WHERE id = ?", id);
    }

    /**
     * Delete all resolved approvals (cleanup).
     */
    public int deleteResolved() {
        return jdbcTemplate.update("DELETE FROM e2e.pending_approval WHERE status != 'PENDING'");
    }
}
