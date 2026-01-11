package com.ivamare.commandbus.exception;

import java.util.Map;

/**
 * Raised for non-retryable failures (validation, business rule violations).
 *
 * <p>When a handler throws this exception, the command immediately moves
 * to the troubleshooting queue without retrying.
 */
public class PermanentCommandException extends CommandBusException {

    private final String code;
    private final String errorMessage;
    private final Map<String, Object> details;

    public PermanentCommandException(String code, String message) {
        this(code, message, Map.of());
    }

    public PermanentCommandException(String code, String message, Map<String, Object> details) {
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
