package com.commandbus.exception;

import java.util.Map;

/**
 * Raised for retryable failures (network, timeout, temporary unavailability).
 *
 * <p>When a handler throws this exception, the command will be retried
 * according to the retry policy. After max attempts, it moves to TSQ.
 */
public class TransientCommandException extends CommandBusException {

    private final String code;
    private final String errorMessage;
    private final Map<String, Object> details;

    public TransientCommandException(String code, String message) {
        this(code, message, Map.of());
    }

    public TransientCommandException(String code, String message, Map<String, Object> details) {
        super("[" + code + "] " + message);
        this.code = code;
        this.errorMessage = message;
        this.details = details != null ? Map.copyOf(details) : Map.of();
    }

    public String getCode() {
        return code;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Map<String, Object> getDetails() {
        return details;
    }
}
