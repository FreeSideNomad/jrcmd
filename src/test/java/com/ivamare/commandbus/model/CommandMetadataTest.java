package com.ivamare.commandbus.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CommandMetadataTest {

    @Test
    void shouldCreateWithDefaults() {
        UUID commandId = UUID.randomUUID();
        Instant before = Instant.now();

        CommandMetadata metadata = CommandMetadata.create("payments", commandId, "DebitAccount", 5);

        Instant after = Instant.now();
        assertEquals("payments", metadata.domain());
        assertEquals(commandId, metadata.commandId());
        assertEquals("DebitAccount", metadata.commandType());
        assertEquals(CommandStatus.PENDING, metadata.status());
        assertEquals(0, metadata.attempts());
        assertEquals(5, metadata.maxAttempts());
        assertNull(metadata.msgId());
        assertNull(metadata.correlationId());
        assertNull(metadata.replyTo());
        assertNull(metadata.lastErrorType());
        assertNull(metadata.lastErrorCode());
        assertNull(metadata.lastErrorMessage());
        assertNotNull(metadata.createdAt());
        assertNotNull(metadata.updatedAt());
        assertFalse(metadata.createdAt().isBefore(before));
        assertFalse(metadata.createdAt().isAfter(after));
        assertNull(metadata.batchId());
    }

    @Test
    void shouldCreateWithAllFields() {
        UUID commandId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        Instant createdAt = Instant.now().minusSeconds(60);
        Instant updatedAt = Instant.now();

        CommandMetadata metadata = new CommandMetadata(
            "payments",
            commandId,
            "DebitAccount",
            CommandStatus.IN_PROGRESS,
            2,
            5,
            12345L,
            correlationId,
            "reply-queue",
            "TRANSIENT",
            "TIMEOUT",
            "Connection timeout",
            createdAt,
            updatedAt,
            batchId
        );

        assertEquals("payments", metadata.domain());
        assertEquals(commandId, metadata.commandId());
        assertEquals("DebitAccount", metadata.commandType());
        assertEquals(CommandStatus.IN_PROGRESS, metadata.status());
        assertEquals(2, metadata.attempts());
        assertEquals(5, metadata.maxAttempts());
        assertEquals(12345L, metadata.msgId());
        assertEquals(correlationId, metadata.correlationId());
        assertEquals("reply-queue", metadata.replyTo());
        assertEquals("TRANSIENT", metadata.lastErrorType());
        assertEquals("TIMEOUT", metadata.lastErrorCode());
        assertEquals("Connection timeout", metadata.lastErrorMessage());
        assertEquals(createdAt, metadata.createdAt());
        assertEquals(updatedAt, metadata.updatedAt());
        assertEquals(batchId, metadata.batchId());
    }

    @Test
    void shouldUpdateStatus() {
        UUID commandId = UUID.randomUUID();
        CommandMetadata original = CommandMetadata.create("payments", commandId, "DebitAccount", 5);
        Instant originalCreatedAt = original.createdAt();

        CommandMetadata updated = original.withStatus(CommandStatus.IN_PROGRESS);

        // Original should be unchanged
        assertEquals(CommandStatus.PENDING, original.status());

        // Updated should have new status
        assertEquals(CommandStatus.IN_PROGRESS, updated.status());
        assertEquals(originalCreatedAt, updated.createdAt());
        assertTrue(updated.updatedAt().isAfter(originalCreatedAt) ||
                   updated.updatedAt().equals(originalCreatedAt));

        // Other fields should be preserved
        assertEquals(original.domain(), updated.domain());
        assertEquals(original.commandId(), updated.commandId());
        assertEquals(original.commandType(), updated.commandType());
        assertEquals(original.attempts(), updated.attempts());
        assertEquals(original.maxAttempts(), updated.maxAttempts());
    }

    @Test
    void shouldUpdateError() {
        UUID commandId = UUID.randomUUID();
        CommandMetadata original = CommandMetadata.create("payments", commandId, "DebitAccount", 5);
        Instant originalCreatedAt = original.createdAt();

        CommandMetadata updated = original.withError("TRANSIENT", "TIMEOUT", "Connection timeout");

        // Original should be unchanged
        assertNull(original.lastErrorType());
        assertNull(original.lastErrorCode());
        assertNull(original.lastErrorMessage());

        // Updated should have error info
        assertEquals("TRANSIENT", updated.lastErrorType());
        assertEquals("TIMEOUT", updated.lastErrorCode());
        assertEquals("Connection timeout", updated.lastErrorMessage());
        assertEquals(originalCreatedAt, updated.createdAt());
        assertTrue(updated.updatedAt().isAfter(originalCreatedAt) ||
                   updated.updatedAt().equals(originalCreatedAt));

        // Other fields should be preserved
        assertEquals(original.domain(), updated.domain());
        assertEquals(original.commandId(), updated.commandId());
        assertEquals(original.status(), updated.status());
    }

    @Test
    void shouldSupportEqualsAndHashCode() {
        UUID commandId = UUID.randomUUID();
        Instant now = Instant.now();

        CommandMetadata metadata1 = new CommandMetadata(
            "payments", commandId, "DebitAccount", CommandStatus.PENDING,
            0, 5, null, null, null, null, null, null, now, now, null
        );
        CommandMetadata metadata2 = new CommandMetadata(
            "payments", commandId, "DebitAccount", CommandStatus.PENDING,
            0, 5, null, null, null, null, null, null, now, now, null
        );
        CommandMetadata metadata3 = new CommandMetadata(
            "orders", commandId, "DebitAccount", CommandStatus.PENDING,
            0, 5, null, null, null, null, null, null, now, now, null
        );

        assertEquals(metadata1, metadata2);
        assertEquals(metadata1.hashCode(), metadata2.hashCode());
        assertNotEquals(metadata1, metadata3);
    }

    @Test
    void withStatusShouldPreserveAllOtherFields() {
        UUID commandId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        Instant createdAt = Instant.now().minusSeconds(60);

        CommandMetadata original = new CommandMetadata(
            "payments", commandId, "DebitAccount", CommandStatus.PENDING,
            2, 5, 12345L, correlationId, "reply-queue",
            "TRANSIENT", "TIMEOUT", "Error msg", createdAt, createdAt, batchId
        );

        CommandMetadata updated = original.withStatus(CommandStatus.IN_PROGRESS);

        assertEquals(original.domain(), updated.domain());
        assertEquals(original.commandId(), updated.commandId());
        assertEquals(original.commandType(), updated.commandType());
        assertEquals(original.attempts(), updated.attempts());
        assertEquals(original.maxAttempts(), updated.maxAttempts());
        assertEquals(original.msgId(), updated.msgId());
        assertEquals(original.correlationId(), updated.correlationId());
        assertEquals(original.replyTo(), updated.replyTo());
        assertEquals(original.lastErrorType(), updated.lastErrorType());
        assertEquals(original.lastErrorCode(), updated.lastErrorCode());
        assertEquals(original.lastErrorMessage(), updated.lastErrorMessage());
        assertEquals(original.createdAt(), updated.createdAt());
        assertEquals(original.batchId(), updated.batchId());
    }

    @Test
    void withErrorShouldPreserveAllOtherFields() {
        UUID commandId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        Instant createdAt = Instant.now().minusSeconds(60);

        CommandMetadata original = new CommandMetadata(
            "payments", commandId, "DebitAccount", CommandStatus.IN_PROGRESS,
            2, 5, 12345L, correlationId, "reply-queue",
            null, null, null, createdAt, createdAt, batchId
        );

        CommandMetadata updated = original.withError("PERMANENT", "INVALID", "Invalid data");

        assertEquals(original.domain(), updated.domain());
        assertEquals(original.commandId(), updated.commandId());
        assertEquals(original.commandType(), updated.commandType());
        assertEquals(original.status(), updated.status());
        assertEquals(original.attempts(), updated.attempts());
        assertEquals(original.maxAttempts(), updated.maxAttempts());
        assertEquals(original.msgId(), updated.msgId());
        assertEquals(original.correlationId(), updated.correlationId());
        assertEquals(original.replyTo(), updated.replyTo());
        assertEquals(original.createdAt(), updated.createdAt());
        assertEquals(original.batchId(), updated.batchId());
    }
}
