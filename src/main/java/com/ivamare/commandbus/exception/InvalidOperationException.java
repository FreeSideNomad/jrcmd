package com.ivamare.commandbus.exception;

/**
 * Thrown when an invalid state transition or operation is attempted.
 */
public class InvalidOperationException extends CommandBusException {

    public InvalidOperationException(String message) {
        super(message);
    }
}
