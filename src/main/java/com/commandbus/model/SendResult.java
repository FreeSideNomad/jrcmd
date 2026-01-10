package com.commandbus.model;

import java.util.UUID;

/**
 * Result of sending a command.
 *
 * @param commandId The unique ID of the sent command
 * @param msgId The PGMQ message ID assigned
 */
public record SendResult(
    UUID commandId,
    long msgId
) {}
