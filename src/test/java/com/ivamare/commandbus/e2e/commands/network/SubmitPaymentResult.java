package com.ivamare.commandbus.e2e.commands.network;

import java.time.Instant;
import java.util.UUID;

/**
 * Result of a successful payment submission to the network.
 *
 * @param submissionReference The submission reference from the network
 * @param commandId The command ID used for tracking
 * @param status The submission status (SUBMITTED, PENDING, etc.)
 * @param submittedAt When the payment was submitted
 */
public record SubmitPaymentResult(
    String submissionReference,
    UUID commandId,
    String status,
    Instant submittedAt
) {
    /**
     * Create a new submission result.
     */
    public static SubmitPaymentResult of(
            String submissionReference,
            UUID commandId,
            String status,
            Instant submittedAt) {
        return new SubmitPaymentResult(submissionReference, commandId, status, submittedAt);
    }

    /**
     * Create a successful submission result.
     */
    public static SubmitPaymentResult submitted(String submissionReference, UUID commandId) {
        return new SubmitPaymentResult(submissionReference, commandId, "SUBMITTED", Instant.now());
    }
}
