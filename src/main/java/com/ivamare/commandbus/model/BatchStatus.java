package com.ivamare.commandbus.model;

/**
 * Status of a batch in its lifecycle.
 */
public enum BatchStatus {
    /** Batch created, no commands processed yet */
    PENDING("PENDING"),

    /** At least one command being processed */
    IN_PROGRESS("IN_PROGRESS"),

    /** All commands completed successfully */
    COMPLETED("COMPLETED"),

    /** All commands done, but some failed/canceled */
    COMPLETED_WITH_FAILURES("COMPLETED_WITH_FAILURES");

    private final String value;

    BatchStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static BatchStatus fromValue(String value) {
        for (BatchStatus status : values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown BatchStatus: " + value);
    }
}
