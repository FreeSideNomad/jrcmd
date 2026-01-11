package com.ivamare.commandbus.exception;

/**
 * Thrown when attempting to send a command with a duplicate command_id.
 */
public class DuplicateCommandException extends CommandBusException {

    private final String domain;
    private final String commandId;

    public DuplicateCommandException(String domain, String commandId) {
        super("Duplicate command_id " + commandId + " in domain " + domain);
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
