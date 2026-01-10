package com.commandbus.process;

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
    CANCELED
}
