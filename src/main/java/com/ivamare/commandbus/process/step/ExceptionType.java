package com.ivamare.commandbus.process.step;

/**
 * Classification of exceptions to determine handling behavior.
 *
 * <p>Used by ProcessStepManager's {@code classifyException(Exception)} method to
 * determine how failures should be handled:
 * <ul>
 *   <li>TRANSIENT - Retry with exponential backoff</li>
 *   <li>BUSINESS - Auto-compensate using saga pattern</li>
 *   <li>PERMANENT - Move to TSQ immediately</li>
 * </ul>
 */
public enum ExceptionType {
    /**
     * Transient failure that should be retried with backoff.
     * Examples: timeouts, service unavailable, network errors.
     */
    TRANSIENT,

    /**
     * Business rule failure that triggers automatic compensation (saga rollback).
     * Examples: insufficient funds, risk declined, validation failed.
     */
    BUSINESS,

    /**
     * Permanent failure that moves process to TSQ immediately.
     * Examples: invalid configuration, data corruption, unrecoverable errors.
     */
    PERMANENT,

    /**
     * Terminal failure that ends the process with FAILED status.
     * No compensations run. Used for definitive business decisions.
     * Examples: risk declined, KYC rejected.
     */
    TERMINAL
}
