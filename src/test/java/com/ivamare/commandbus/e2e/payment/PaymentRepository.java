package com.ivamare.commandbus.e2e.payment;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository interface for Payment entities.
 */
public interface PaymentRepository {

    /**
     * Save a new payment.
     */
    void save(Payment payment, JdbcTemplate jdbc);

    /**
     * Update an existing payment.
     */
    void update(Payment payment, JdbcTemplate jdbc);

    /**
     * Find payment by ID.
     */
    Optional<Payment> findById(UUID paymentId, JdbcTemplate jdbc);

    /**
     * Find all payments with pagination.
     */
    List<Payment> findAll(int limit, int offset, JdbcTemplate jdbc);

    /**
     * Find payments by status.
     */
    List<Payment> findByStatus(PaymentStatus status, JdbcTemplate jdbc);

    /**
     * Find payments by status (multiple statuses).
     */
    List<Payment> findByStatuses(List<PaymentStatus> statuses, JdbcTemplate jdbc);

    /**
     * Find payments in a batch.
     */
    List<Payment> findByBatchId(UUID batchId, JdbcTemplate jdbc);

    /**
     * Count payments by status.
     */
    int countByStatus(PaymentStatus status, JdbcTemplate jdbc);

    /**
     * Update just the status of a payment.
     */
    void updateStatus(UUID paymentId, PaymentStatus status, JdbcTemplate jdbc);

    /**
     * Update network confirmation for a specific level (L1-L4).
     *
     * @param paymentId  Payment ID
     * @param level      Confirmation level (1-4)
     * @param reference  Network reference
     * @param receivedAt Timestamp when confirmation was received
     * @param jdbc       JdbcTemplate to use
     */
    void updateNetworkConfirmation(UUID paymentId, int level, String reference, Instant receivedAt, JdbcTemplate jdbc);

    /**
     * Update network confirmation and status atomically (for L4 completion).
     *
     * @param paymentId  Payment ID
     * @param level      Confirmation level (1-4)
     * @param reference  Network reference
     * @param receivedAt Timestamp when confirmation was received
     * @param status     New payment status
     * @param jdbc       JdbcTemplate to use
     */
    void updateNetworkConfirmationAndStatus(UUID paymentId, int level, String reference, Instant receivedAt,
                                            PaymentStatus status, JdbcTemplate jdbc);

    /**
     * Delete payment by ID.
     */
    void deleteById(UUID paymentId, JdbcTemplate jdbc);

    // Convenience methods that use a default JdbcTemplate
    default void save(Payment payment) {
        throw new UnsupportedOperationException("Default implementation requires JdbcTemplate");
    }

    default void update(Payment payment) {
        throw new UnsupportedOperationException("Default implementation requires JdbcTemplate");
    }

    default Optional<Payment> findById(UUID paymentId) {
        throw new UnsupportedOperationException("Default implementation requires JdbcTemplate");
    }

    default List<Payment> findAll(int limit, int offset) {
        throw new UnsupportedOperationException("Default implementation requires JdbcTemplate");
    }
}
