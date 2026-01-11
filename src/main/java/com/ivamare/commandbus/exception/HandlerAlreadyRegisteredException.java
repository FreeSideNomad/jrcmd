package com.ivamare.commandbus.exception;

/**
 * Thrown when attempting to register a handler for a command type that already has one.
 */
public class HandlerAlreadyRegisteredException extends CommandBusException {

    private final String domain;
    private final String commandType;

    public HandlerAlreadyRegisteredException(String domain, String commandType) {
        super("Handler already registered for " + domain + "." + commandType);
        this.domain = domain;
        this.commandType = commandType;
    }

    public String getDomain() {
        return domain;
    }

    public String getCommandType() {
        return commandType;
    }
}
