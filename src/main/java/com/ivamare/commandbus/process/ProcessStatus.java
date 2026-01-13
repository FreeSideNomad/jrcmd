package com.ivamare.commandbus.process;

/**
 * Status of a process instance.
 */
public enum ProcessStatus {
    /** Process created but not yet started */
    PENDING,

    /** Currently executing a step */
    IN_PROGRESS,

    /** Waiting for command reply */
    WAITING_FOR_REPLY,

    /** Command failed and is in TSQ awaiting operator action */
    WAITING_FOR_TSQ,

    /** Running compensation steps after failure */
    COMPENSATING,

    /** Process completed successfully */
    COMPLETED,

    /** All compensation steps completed */
    COMPENSATED,

    /** Process failed permanently (no compensation or compensation failed) */
    FAILED,

    /** Process was canceled by operator */
    CANCELED;

    /**
     * Check if this is a terminal status (process will not change status again).
     * Terminal statuses are: COMPLETED, COMPENSATED, FAILED, CANCELED.
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == COMPENSATED || this == FAILED || this == CANCELED;
    }
}
