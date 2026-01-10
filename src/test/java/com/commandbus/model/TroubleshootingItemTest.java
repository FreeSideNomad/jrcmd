package com.commandbus.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TroubleshootingItem")
class TroubleshootingItemTest {

    @Test
    @DisplayName("should identify permanent error")
    void shouldIdentifyPermanentError() {
        TroubleshootingItem item = createItem("PERMANENT", "ERROR_CODE", 1, 3);

        assertTrue(item.isPermanentError());
        assertFalse(item.isRetriesExhausted());
    }

    @Test
    @DisplayName("should identify retries exhausted")
    void shouldIdentifyRetriesExhausted() {
        TroubleshootingItem item = createItem("TRANSIENT", "ERROR_CODE", 3, 3);

        assertFalse(item.isPermanentError());
        assertTrue(item.isRetriesExhausted());
    }

    @Test
    @DisplayName("should not identify retries exhausted when attempts less than max")
    void shouldNotIdentifyRetriesExhaustedWhenAttemptsLessThanMax() {
        TroubleshootingItem item = createItem("TRANSIENT", "ERROR_CODE", 2, 3);

        assertFalse(item.isPermanentError());
        assertFalse(item.isRetriesExhausted());
    }

    @Test
    @DisplayName("should identify has reply_to when set")
    void shouldIdentifyHasReplyToWhenSet() {
        TroubleshootingItem item = new TroubleshootingItem(
            "test", UUID.randomUUID(), "TestCommand",
            1, 3, "PERMANENT", "CODE", "message",
            null, "reply_queue", null,
            Instant.now(), Instant.now()
        );

        assertTrue(item.hasReplyTo());
    }

    @Test
    @DisplayName("should not identify has reply_to when null")
    void shouldNotIdentifyHasReplyToWhenNull() {
        TroubleshootingItem item = createItem("PERMANENT", "CODE", 1, 3);

        assertFalse(item.hasReplyTo());
    }

    @Test
    @DisplayName("should not identify has reply_to when blank")
    void shouldNotIdentifyHasReplyToWhenBlank() {
        TroubleshootingItem item = new TroubleshootingItem(
            "test", UUID.randomUUID(), "TestCommand",
            1, 3, "PERMANENT", "CODE", "message",
            null, "   ", null,
            Instant.now(), Instant.now()
        );

        assertFalse(item.hasReplyTo());
    }

    @Test
    @DisplayName("should create item with all fields")
    void shouldCreateItemWithAllFields() {
        UUID commandId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        Instant now = Instant.now();
        Map<String, Object> payload = Map.of("key", "value");

        TroubleshootingItem item = new TroubleshootingItem(
            "payments",
            commandId,
            "DebitAccount",
            2,
            3,
            "TRANSIENT",
            "TIMEOUT",
            "Connection timed out",
            correlationId,
            "reply_queue",
            payload,
            now,
            now
        );

        assertEquals("payments", item.domain());
        assertEquals(commandId, item.commandId());
        assertEquals("DebitAccount", item.commandType());
        assertEquals(2, item.attempts());
        assertEquals(3, item.maxAttempts());
        assertEquals("TRANSIENT", item.lastErrorType());
        assertEquals("TIMEOUT", item.lastErrorCode());
        assertEquals("Connection timed out", item.lastErrorMessage());
        assertEquals(correlationId, item.correlationId());
        assertEquals("reply_queue", item.replyTo());
        assertEquals(payload, item.payload());
        assertEquals(now, item.createdAt());
        assertEquals(now, item.updatedAt());
    }

    private TroubleshootingItem createItem(String errorType, String errorCode, int attempts, int maxAttempts) {
        return new TroubleshootingItem(
            "test",
            UUID.randomUUID(),
            "TestCommand",
            attempts,
            maxAttempts,
            errorType,
            errorCode,
            "Error message",
            null,
            null,
            null,
            Instant.now(),
            Instant.now()
        );
    }
}
