package com.ivamare.commandbus.process.step;

/**
 * Expected outcome for command step execution.
 *
 * <p>Used for testing to inject specific behaviors into command handlers.
 * In production, handlers execute real logic; in tests, they use this enum
 * to simulate different failure scenarios.
 */
public enum ExpectedOutcome {
    /**
     * Command executes successfully and returns result.
     */
    SUCCESS,

    /**
     * Command times out (transient failure, will retry).
     */
    TIMEOUT,

    /**
     * Transient failure (network error, service unavailable - will retry).
     */
    TRANSIENT_FAILURE,

    /**
     * Permanent failure (invalid data, rejected - goes to TSQ).
     */
    PERMANENT_FAILURE,

    /**
     * Business rule violation (insufficient funds, limit exceeded - fails immediately).
     */
    BUSINESS_ERROR
}
