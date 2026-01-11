package com.ivamare.commandbus.model;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AuditEventTest {

    @Nested
    class RecordTests {

        @Test
        void shouldCreateAuditEvent() {
            UUID commandId = UUID.randomUUID();
            Instant timestamp = Instant.now();
            Map<String, Object> details = Map.of("key", "value");

            AuditEvent event = new AuditEvent(
                1L, "payments", commandId, "SENT", timestamp, details
            );

            assertEquals(1L, event.auditId());
            assertEquals("payments", event.domain());
            assertEquals(commandId, event.commandId());
            assertEquals("SENT", event.eventType());
            assertEquals(timestamp, event.timestamp());
            assertEquals(details, event.details());
        }

        @Test
        void shouldCreateWithNullDetails() {
            UUID commandId = UUID.randomUUID();

            AuditEvent event = new AuditEvent(
                1L, "payments", commandId, "COMPLETED", Instant.now(), null
            );

            assertNull(event.details());
        }
    }

    @Nested
    class EqualityTests {

        @Test
        void shouldBeEqualWithSameValues() {
            UUID commandId = UUID.randomUUID();
            Instant timestamp = Instant.now();

            AuditEvent e1 = new AuditEvent(
                1L, "payments", commandId, "SENT", timestamp, null
            );

            AuditEvent e2 = new AuditEvent(
                1L, "payments", commandId, "SENT", timestamp, null
            );

            assertEquals(e1, e2);
            assertEquals(e1.hashCode(), e2.hashCode());
        }

        @Test
        void shouldNotBeEqualWithDifferentAuditId() {
            UUID commandId = UUID.randomUUID();
            Instant timestamp = Instant.now();

            AuditEvent e1 = new AuditEvent(
                1L, "payments", commandId, "SENT", timestamp, null
            );

            AuditEvent e2 = new AuditEvent(
                2L, "payments", commandId, "SENT", timestamp, null
            );

            assertNotEquals(e1, e2);
        }

        @Test
        void shouldNotBeEqualWithDifferentEventType() {
            UUID commandId = UUID.randomUUID();
            Instant timestamp = Instant.now();

            AuditEvent e1 = new AuditEvent(
                1L, "payments", commandId, "SENT", timestamp, null
            );

            AuditEvent e2 = new AuditEvent(
                1L, "payments", commandId, "COMPLETED", timestamp, null
            );

            assertNotEquals(e1, e2);
        }
    }

    @Nested
    class ToStringTests {

        @Test
        void shouldHaveToString() {
            UUID commandId = UUID.randomUUID();

            AuditEvent event = new AuditEvent(
                1L, "payments", commandId, "SENT", Instant.now(), null
            );

            String str = event.toString();
            assertTrue(str.contains("AuditEvent"));
            assertTrue(str.contains("payments"));
            assertTrue(str.contains("SENT"));
        }
    }
}
