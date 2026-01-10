package com.commandbus.exception;

/**
 * Base exception for all Command Bus errors.
 */
public class CommandBusException extends RuntimeException {

    public CommandBusException(String message) {
        super(message);
    }

    public CommandBusException(String message, Throwable cause) {
        super(message, cause);
    }
}
