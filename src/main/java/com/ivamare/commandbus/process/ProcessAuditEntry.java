package com.ivamare.commandbus.process;

import com.ivamare.commandbus.model.ReplyOutcome;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Audit trail entry for process step execution.
 *
 * @param stepName Name of the step being executed
 * @param commandId ID of the command sent
 * @param commandType Type of command
 * @param commandData Command payload data
 * @param sentAt When the command was sent
 * @param replyOutcome Outcome of command processing (nullable until reply received)
 * @param replyData Response data (nullable)
 * @param receivedAt When the reply was received (nullable)
 */
public record ProcessAuditEntry(
    String stepName,
    UUID commandId,
    String commandType,
    Map<String, Object> commandData,
    Instant sentAt,
    ReplyOutcome replyOutcome,
    Map<String, Object> replyData,
    Instant receivedAt
) {
    /**
     * Create entry for command being sent.
     */
    public static ProcessAuditEntry forCommand(
            String stepName,
            UUID commandId,
            String commandType,
            Map<String, Object> commandData) {
        return new ProcessAuditEntry(
            stepName, commandId, commandType, commandData,
            Instant.now(), null, null, null
        );
    }

    /**
     * Create updated entry with reply information.
     */
    public ProcessAuditEntry withReply(ReplyOutcome outcome, Map<String, Object> data) {
        return new ProcessAuditEntry(
            stepName, commandId, commandType, commandData,
            sentAt, outcome, data, Instant.now()
        );
    }
}
