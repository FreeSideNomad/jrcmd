package com.ivamare.commandbus.process.step;

/**
 * Status of a step within a ProcessStepManager execution.
 */
public enum StepStatus {
    /** Step execution has started but not yet completed */
    STARTED,

    /** Step completed successfully */
    COMPLETED,

    /** Step failed permanently or after exhausting retries */
    FAILED,

    /** Step failed with transient error, waiting for scheduled retry */
    WAITING_RETRY
}
