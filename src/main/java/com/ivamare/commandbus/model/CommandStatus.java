package com.ivamare.commandbus.model;

/**
 * Status of a command in its lifecycle.
 */
public enum CommandStatus {
    /** Command queued, awaiting processing */
    PENDING("PENDING"),

    /** Currently being processed by a worker */
    IN_PROGRESS("IN_PROGRESS"),

    /** Successfully completed */
    COMPLETED("COMPLETED"),

    /** Failed (legacy status, use IN_TROUBLESHOOTING_QUEUE) */
    FAILED("FAILED"),

    /** Canceled by operator */
    CANCELED("CANCELED"),

    /** Failed after retries exhausted or permanent error */
    IN_TROUBLESHOOTING_QUEUE("IN_TROUBLESHOOTING_QUEUE");

    private final String value;

    CommandStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static CommandStatus fromValue(String value) {
        for (CommandStatus status : values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown CommandStatus: " + value);
    }
}
