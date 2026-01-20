package com.ivamare.commandbus.e2e.payment;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * JDBC implementation of PaymentRepository.
 */
@Repository
public class JdbcPaymentRepository implements PaymentRepository {

    private final JdbcTemplate defaultJdbcTemplate;

    public JdbcPaymentRepository(JdbcTemplate jdbcTemplate) {
        this.defaultJdbcTemplate = jdbcTemplate;
    }

    private static final String INSERT_SQL = """
        INSERT INTO e2e.payment (
            payment_id, action_date, value_date, debit_currency, credit_currency,
            debit_transit, debit_account_number, credit_bic, credit_iban,
            debit_amount, credit_amount, fx_contract_id, fx_rate,
            status, cutoff_timestamp, created_at, updated_at,
            l1_reference, l1_received_at, l2_reference, l2_received_at,
            l3_reference, l3_received_at, l4_reference, l4_received_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

    private static final String UPDATE_SQL = """
        UPDATE e2e.payment SET
            action_date = ?, value_date = ?, debit_currency = ?, credit_currency = ?,
            debit_transit = ?, debit_account_number = ?, credit_bic = ?, credit_iban = ?,
            debit_amount = ?, credit_amount = ?, fx_contract_id = ?, fx_rate = ?,
            status = ?, cutoff_timestamp = ?, updated_at = ?,
            l1_reference = ?, l1_received_at = ?, l2_reference = ?, l2_received_at = ?,
            l3_reference = ?, l3_received_at = ?, l4_reference = ?, l4_received_at = ?
        WHERE payment_id = ?
        """;

    private static final String SELECT_BASE = """
        SELECT payment_id, action_date, value_date, debit_currency, credit_currency,
               debit_transit, debit_account_number, credit_bic, credit_iban,
               debit_amount, credit_amount, fx_contract_id, fx_rate,
               status, cutoff_timestamp, created_at, updated_at,
               l1_reference, l1_received_at, l2_reference, l2_received_at,
               l3_reference, l3_received_at, l4_reference, l4_received_at
        FROM e2e.payment
        """;

    private final RowMapper<Payment> rowMapper = (rs, rowNum) -> mapRow(rs);

    private Payment mapRow(ResultSet rs) throws SQLException {
        return Payment.builder()
            .paymentId(UUID.fromString(rs.getString("payment_id")))
            .actionDate(rs.getObject("action_date", LocalDate.class))
            .valueDate(rs.getObject("value_date", LocalDate.class))
            .debitCurrency(Currency.valueOf(rs.getString("debit_currency")))
            .creditCurrency(Currency.valueOf(rs.getString("credit_currency")))
            .debitAccount(DebitAccount.of(
                rs.getString("debit_transit"),
                rs.getString("debit_account_number")
            ))
            .creditAccount(CreditAccount.of(
                rs.getString("credit_bic"),
                rs.getString("credit_iban")
            ))
            .debitAmount(rs.getBigDecimal("debit_amount"))
            .creditAmount(rs.getBigDecimal("credit_amount"))
            .fxContractId(rs.getObject("fx_contract_id", Long.class))
            .fxRate(rs.getBigDecimal("fx_rate"))
            .status(PaymentStatus.valueOf(rs.getString("status")))
            .cutoffTimestamp(toInstant(rs.getTimestamp("cutoff_timestamp")))
            .createdAt(toInstant(rs.getTimestamp("created_at")))
            .updatedAt(toInstant(rs.getTimestamp("updated_at")))
            .l1Reference(rs.getString("l1_reference"))
            .l1ReceivedAt(toInstant(rs.getTimestamp("l1_received_at")))
            .l2Reference(rs.getString("l2_reference"))
            .l2ReceivedAt(toInstant(rs.getTimestamp("l2_received_at")))
            .l3Reference(rs.getString("l3_reference"))
            .l3ReceivedAt(toInstant(rs.getTimestamp("l3_received_at")))
            .l4Reference(rs.getString("l4_reference"))
            .l4ReceivedAt(toInstant(rs.getTimestamp("l4_received_at")))
            .build();
    }

    private Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }

    private Timestamp toTimestamp(Instant instant) {
        return instant != null ? Timestamp.from(instant) : null;
    }

    @Override
    public void save(Payment payment, JdbcTemplate jdbc) {
        jdbc.update(INSERT_SQL,
            payment.paymentId(),
            payment.actionDate(),
            payment.valueDate(),
            payment.debitCurrency().name(),
            payment.creditCurrency().name(),
            payment.debitAccount().transit(),
            payment.debitAccount().accountNumber(),
            payment.creditAccount().bic(),
            payment.creditAccount().iban(),
            payment.debitAmount(),
            payment.creditAmount(),
            payment.fxContractId(),
            payment.fxRate(),
            payment.status().name(),
            toTimestamp(payment.cutoffTimestamp()),
            toTimestamp(payment.createdAt()),
            toTimestamp(payment.updatedAt()),
            payment.l1Reference(),
            toTimestamp(payment.l1ReceivedAt()),
            payment.l2Reference(),
            toTimestamp(payment.l2ReceivedAt()),
            payment.l3Reference(),
            toTimestamp(payment.l3ReceivedAt()),
            payment.l4Reference(),
            toTimestamp(payment.l4ReceivedAt())
        );
    }

    @Override
    public void update(Payment payment, JdbcTemplate jdbc) {
        jdbc.update(UPDATE_SQL,
            payment.actionDate(),
            payment.valueDate(),
            payment.debitCurrency().name(),
            payment.creditCurrency().name(),
            payment.debitAccount().transit(),
            payment.debitAccount().accountNumber(),
            payment.creditAccount().bic(),
            payment.creditAccount().iban(),
            payment.debitAmount(),
            payment.creditAmount(),
            payment.fxContractId(),
            payment.fxRate(),
            payment.status().name(),
            toTimestamp(payment.cutoffTimestamp()),
            toTimestamp(Instant.now()),
            payment.l1Reference(),
            toTimestamp(payment.l1ReceivedAt()),
            payment.l2Reference(),
            toTimestamp(payment.l2ReceivedAt()),
            payment.l3Reference(),
            toTimestamp(payment.l3ReceivedAt()),
            payment.l4Reference(),
            toTimestamp(payment.l4ReceivedAt()),
            payment.paymentId()
        );
    }

    @Override
    public Optional<Payment> findById(UUID paymentId, JdbcTemplate jdbc) {
        List<Payment> results = jdbc.query(
            SELECT_BASE + " WHERE payment_id = ?",
            rowMapper,
            paymentId
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public List<Payment> findAll(int limit, int offset, JdbcTemplate jdbc) {
        return jdbc.query(
            SELECT_BASE + " ORDER BY created_at DESC LIMIT ? OFFSET ?",
            rowMapper,
            limit, offset
        );
    }

    @Override
    public List<Payment> findByStatus(PaymentStatus status, JdbcTemplate jdbc) {
        return jdbc.query(
            SELECT_BASE + " WHERE status = ? ORDER BY created_at DESC",
            rowMapper,
            status.name()
        );
    }

    @Override
    public List<Payment> findByStatuses(List<PaymentStatus> statuses, JdbcTemplate jdbc) {
        if (statuses.isEmpty()) {
            return List.of();
        }
        String placeholders = statuses.stream()
            .map(s -> "?")
            .collect(Collectors.joining(", "));
        Object[] args = statuses.stream()
            .map(PaymentStatus::name)
            .toArray();
        return jdbc.query(
            SELECT_BASE + " WHERE status IN (" + placeholders + ") ORDER BY created_at DESC",
            rowMapper,
            args
        );
    }

    @Override
    public List<Payment> findByBatchId(UUID batchId, JdbcTemplate jdbc) {
        return jdbc.query(
            SELECT_BASE + """
                WHERE payment_id IN (
                    SELECT payment_id FROM e2e.payment_batch_item WHERE batch_id = ?
                ) ORDER BY created_at DESC
                """,
            rowMapper,
            batchId
        );
    }

    @Override
    public int countByStatus(PaymentStatus status, JdbcTemplate jdbc) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM e2e.payment WHERE status = ?",
            Integer.class,
            status.name()
        );
        return count != null ? count : 0;
    }

    @Override
    public void updateStatus(UUID paymentId, PaymentStatus status, JdbcTemplate jdbc) {
        jdbc.update(
            "UPDATE e2e.payment SET status = ?, updated_at = ? WHERE payment_id = ?",
            status.name(),
            Timestamp.from(Instant.now()),
            paymentId
        );
    }

    @Override
    public void updateNetworkConfirmation(UUID paymentId, int level, String reference, Instant receivedAt, JdbcTemplate jdbc) {
        String refColumn = "l" + level + "_reference";
        String atColumn = "l" + level + "_received_at";
        String sql = String.format(
            "UPDATE e2e.payment SET %s = ?, %s = ?, updated_at = ? WHERE payment_id = ?",
            refColumn, atColumn
        );
        jdbc.update(sql, reference, toTimestamp(receivedAt), Timestamp.from(Instant.now()), paymentId);
    }

    @Override
    public void updateNetworkConfirmationAndStatus(UUID paymentId, int level, String reference, Instant receivedAt,
                                                   PaymentStatus status, JdbcTemplate jdbc) {
        String refColumn = "l" + level + "_reference";
        String atColumn = "l" + level + "_received_at";
        String sql = String.format(
            "UPDATE e2e.payment SET %s = ?, %s = ?, status = ?, updated_at = ? WHERE payment_id = ?",
            refColumn, atColumn
        );
        jdbc.update(sql, reference, toTimestamp(receivedAt), status.name(), Timestamp.from(Instant.now()), paymentId);
    }

    @Override
    public void deleteById(UUID paymentId, JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM e2e.payment WHERE payment_id = ?", paymentId);
    }

    // Convenience methods using default JdbcTemplate
    @Override
    public void save(Payment payment) {
        save(payment, defaultJdbcTemplate);
    }

    @Override
    public void update(Payment payment) {
        update(payment, defaultJdbcTemplate);
    }

    @Override
    public Optional<Payment> findById(UUID paymentId) {
        return findById(paymentId, defaultJdbcTemplate);
    }

    @Override
    public List<Payment> findAll(int limit, int offset) {
        return findAll(limit, offset, defaultJdbcTemplate);
    }

    /**
     * Save a batch of payments and link them to a batch.
     */
    public void saveBatch(UUID batchId, String name, List<Payment> payments) {
        // Insert batch header
        defaultJdbcTemplate.update(
            "INSERT INTO e2e.payment_batch (batch_id, name, total_count, created_at) VALUES (?, ?, ?, ?)",
            batchId, name, payments.size(), Timestamp.from(Instant.now())
        );

        // Insert payments and link to batch
        for (Payment payment : payments) {
            save(payment, defaultJdbcTemplate);
            defaultJdbcTemplate.update(
                "INSERT INTO e2e.payment_batch_item (batch_id, payment_id) VALUES (?, ?)",
                batchId, payment.paymentId()
            );
        }
    }
}
