package com.ivamare.commandbus.repository;

import com.ivamare.commandbus.model.AuditEvent;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Repository for audit events.
 */
public interface AuditRepository {

    /**
     * Log an audit event.
     *
     * @param domain The domain
     * @param commandId The command ID
     * @param eventType The event type
     * @param details Optional details (nullable)
     */
    void log(String domain, UUID commandId, String eventType, Map<String, Object> details);

    /**
     * Log multiple audit events.
     *
     * @param events List of audit event records
     */
    void logBatch(List<AuditEventRecord> events);

    /**
     * Get audit events for a command.
     *
     * @param commandId The command ID
     * @param domain Filter by domain (nullable)
     * @return List of events in chronological order
     */
    List<AuditEvent> getEvents(UUID commandId, String domain);

    /**
     * Record for batch audit logging.
     */
    record AuditEventRecord(
        String domain,
        UUID commandId,
        String eventType,
        Map<String, Object> details
    ) {}
}
