package com.ivamare.commandbus.process.step.exceptions;

/**
 * Exception for terminal failures that should result in FAILED process status
 * without running compensations.
 *
 * <p>Use this for business decisions that definitively end the process
 * (e.g., risk declined) rather than technical errors or compensable failures.
 *
 * <p>Unlike {@link StepBusinessRuleException} which triggers compensation,
 * this exception sets the process to FAILED directly.
 */
public class TerminalFailureException extends RuntimeException {

    private final String errorCode;

    public TerminalFailureException(String message) {
        super(message);
        this.errorCode = "TERMINAL_FAILURE";
    }

    public TerminalFailureException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public TerminalFailureException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
