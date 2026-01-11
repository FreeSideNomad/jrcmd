package com.ivamare.commandbus.model;

/**
 * Outcome of command processing.
 */
public enum ReplyOutcome {
    SUCCESS("SUCCESS"),
    FAILED("FAILED"),
    CANCELED("CANCELED");

    private final String value;

    ReplyOutcome(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static ReplyOutcome fromValue(String value) {
        for (ReplyOutcome outcome : values()) {
            if (outcome.value.equals(value)) {
                return outcome;
            }
        }
        throw new IllegalArgumentException("Unknown ReplyOutcome: " + value);
    }
}
