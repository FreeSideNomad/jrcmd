package com.commandbus.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * An audit event in a command's lifecycle.
 *
 * @param auditId Unique identifier for this audit event
 * @param domain The domain of the command
 * @param commandId The command ID
 * @param eventType Type of event (SENT, RECEIVED, FAILED, etc.)
 * @param timestamp When the event occurred
 * @param details Optional additional details (nullable)
 */
public record AuditEvent(
    long auditId,
    String domain,
    UUID commandId,
    String eventType,
    Instant timestamp,
    Map<String, Object> details
) {}
