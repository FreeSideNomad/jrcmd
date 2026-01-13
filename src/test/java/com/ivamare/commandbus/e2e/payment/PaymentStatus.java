package com.ivamare.commandbus.e2e.payment;

/**
 * Payment lifecycle status.
 */
public enum PaymentStatus {
    /** Payment created but not submitted for processing. */
    DRAFT,

    /** Risk approved, ready for FX booking or submission. */
    APPROVED,

    /** Currently being processed (FX booking, submission, or awaiting confirmations). */
    PROCESSING,

    /** Successfully completed with all confirmations received. */
    COMPLETE,

    /** Failed and cannot be retried. */
    FAILED,

    /** Cancelled (cutoff exceeded, manual cancellation, or compensation completed). */
    CANCELLED
}
