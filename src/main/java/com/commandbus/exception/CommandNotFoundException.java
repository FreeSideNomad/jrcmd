package com.commandbus.exception;

/**
 * Thrown when a command cannot be found.
 */
public class CommandNotFoundException extends CommandBusException {

    private final String domain;
    private final String commandId;

    public CommandNotFoundException(String domain, String commandId) {
        super("Command " + commandId + " not found in domain " + domain);
        this.domain = domain;
        this.commandId = commandId;
    }

    public String getDomain() {
        return domain;
    }

    public String getCommandId() {
        return commandId;
    }
}
