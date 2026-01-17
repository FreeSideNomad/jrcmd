package com.ivamare.commandbus.process.step;

/**
 * Action to take when a process deadline is exceeded.
 *
 * <p>Deadlines are set via {@code processDeadline} in the state and
 * checked by the ProcessStepWorker. When exceeded, the configured
 * action determines behavior.
 */
public enum DeadlineAction {
    /** Move the process to the troubleshooting queue for operator intervention */
    TSQ,

    /** Automatically run compensations (saga rollback) */
    COMPENSATE,

    /** Mark as FAILED without running compensation */
    FAIL
}
