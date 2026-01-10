package com.commandbus.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PgmqMessageTest {

    @Test
    void shouldCreateWithAllFields() {
        Instant enqueuedAt = Instant.now().minusSeconds(60);
        Instant visibilityTimeout = Instant.now().plusSeconds(30);
        Map<String, Object> message = Map.of("key", "value");

        PgmqMessage pgmqMessage = new PgmqMessage(
            123L,
            2,
            enqueuedAt,
            visibilityTimeout,
            message
        );

        assertEquals(123L, pgmqMessage.msgId());
        assertEquals(2, pgmqMessage.readCount());
        assertEquals(enqueuedAt, pgmqMessage.enqueuedAt());
        assertEquals(visibilityTimeout, pgmqMessage.visibilityTimeout());
        assertEquals(Map.of("key", "value"), pgmqMessage.message());
    }

    @Test
    void shouldCreateWithNullTimestamps() {
        Map<String, Object> message = Map.of("key", "value");

        PgmqMessage pgmqMessage = new PgmqMessage(
            1L,
            0,
            null,
            null,
            message
        );

        assertNull(pgmqMessage.enqueuedAt());
        assertNull(pgmqMessage.visibilityTimeout());
    }

    @Test
    void shouldCreateWithNullMessage() {
        PgmqMessage pgmqMessage = new PgmqMessage(
            1L,
            0,
            null,
            null,
            null
        );

        assertNull(pgmqMessage.message());
    }

    @Test
    void shouldMakeMessageImmutable() {
        Map<String, Object> mutableMessage = new HashMap<>();
        mutableMessage.put("key", "value");

        PgmqMessage pgmqMessage = new PgmqMessage(
            1L,
            0,
            null,
            null,
            mutableMessage
        );

        // Original map modification should not affect the record
        mutableMessage.put("another", "entry");
        assertEquals(1, pgmqMessage.message().size());

        // Record's message should be immutable
        assertThrows(UnsupportedOperationException.class,
            () -> pgmqMessage.message().put("new", "entry"));
    }

    @Test
    void shouldSupportEqualsAndHashCode() {
        Instant now = Instant.now();
        Map<String, Object> message = Map.of("key", "value");

        PgmqMessage msg1 = new PgmqMessage(1L, 0, now, now, message);
        PgmqMessage msg2 = new PgmqMessage(1L, 0, now, now, message);
        PgmqMessage msg3 = new PgmqMessage(2L, 0, now, now, message);

        assertEquals(msg1, msg2);
        assertEquals(msg1.hashCode(), msg2.hashCode());
        assertNotEquals(msg1, msg3);
    }

    @Test
    void shouldHaveCorrectToString() {
        PgmqMessage pgmqMessage = new PgmqMessage(
            123L,
            2,
            null,
            null,
            Map.of("key", "value")
        );

        String toString = pgmqMessage.toString();
        assertTrue(toString.contains("123"));
        assertTrue(toString.contains("2"));
        assertTrue(toString.contains("key"));
    }
}
