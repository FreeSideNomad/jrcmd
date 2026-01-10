package com.commandbus.exception;

/**
 * Thrown when no handler is registered for a command type.
 */
public class HandlerNotFoundException extends CommandBusException {

    private final String domain;
    private final String commandType;

    public HandlerNotFoundException(String domain, String commandType) {
        super("No handler registered for " + domain + "." + commandType);
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
