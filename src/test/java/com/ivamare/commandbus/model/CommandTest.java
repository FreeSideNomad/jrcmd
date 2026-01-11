package com.ivamare.commandbus.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CommandTest {

    @Test
    void shouldCreateCommandWithAllFields() {
        UUID commandId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        Instant createdAt = Instant.now();
        Map<String, Object> data = Map.of("key", "value");

        Command command = new Command(
            "payments",
            "DebitAccount",
            commandId,
            data,
            correlationId,
            "reply-queue",
            createdAt
        );

        assertEquals("payments", command.domain());
        assertEquals("DebitAccount", command.commandType());
        assertEquals(commandId, command.commandId());
        assertEquals(Map.of("key", "value"), command.data());
        assertEquals(correlationId, command.correlationId());
        assertEquals("reply-queue", command.replyTo());
        assertEquals(createdAt, command.createdAt());
    }

    @Test
    void shouldCreateCommandWithNullOptionalFields() {
        UUID commandId = UUID.randomUUID();
        Instant createdAt = Instant.now();

        Command command = new Command(
            "payments",
            "DebitAccount",
            commandId,
            null,
            null,
            null,
            createdAt
        );

        assertEquals("payments", command.domain());
        assertEquals("DebitAccount", command.commandType());
        assertEquals(commandId, command.commandId());
        assertEquals(Map.of(), command.data());
        assertNull(command.correlationId());
        assertNull(command.replyTo());
    }

    @Test
    void shouldDefaultCreatedAtToNow() {
        UUID commandId = UUID.randomUUID();
        Instant before = Instant.now();

        Command command = new Command(
            "payments",
            "DebitAccount",
            commandId,
            null,
            null,
            null,
            null
        );

        Instant after = Instant.now();
        assertNotNull(command.createdAt());
        assertFalse(command.createdAt().isBefore(before));
        assertFalse(command.createdAt().isAfter(after));
    }

    @Test
    void shouldMakeDataImmutable() {
        UUID commandId = UUID.randomUUID();
        Map<String, Object> mutableData = new HashMap<>();
        mutableData.put("key", "value");

        Command command = new Command(
            "payments",
            "DebitAccount",
            commandId,
            mutableData,
            null,
            null,
            Instant.now()
        );

        // Original map modification should not affect command
        mutableData.put("another", "entry");
        assertEquals(1, command.data().size());

        // Command data should be immutable
        assertThrows(UnsupportedOperationException.class,
            () -> command.data().put("new", "entry"));
    }

    @Test
    void shouldThrowWhenDomainIsNull() {
        UUID commandId = UUID.randomUUID();

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> new Command(null, "DebitAccount", commandId, null, null, null, null)
        );
        assertEquals("domain is required", exception.getMessage());
    }

    @Test
    void shouldThrowWhenDomainIsBlank() {
        UUID commandId = UUID.randomUUID();

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> new Command("  ", "DebitAccount", commandId, null, null, null, null)
        );
        assertEquals("domain is required", exception.getMessage());
    }

    @Test
    void shouldThrowWhenCommandTypeIsNull() {
        UUID commandId = UUID.randomUUID();

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> new Command("payments", null, commandId, null, null, null, null)
        );
        assertEquals("commandType is required", exception.getMessage());
    }

    @Test
    void shouldThrowWhenCommandTypeIsBlank() {
        UUID commandId = UUID.randomUUID();

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> new Command("payments", "", commandId, null, null, null, null)
        );
        assertEquals("commandType is required", exception.getMessage());
    }

    @Test
    void shouldThrowWhenCommandIdIsNull() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> new Command("payments", "DebitAccount", null, null, null, null, null)
        );
        assertEquals("commandId is required", exception.getMessage());
    }

    @Test
    void shouldSupportEqualsAndHashCode() {
        UUID commandId = UUID.randomUUID();
        Instant createdAt = Instant.now();
        Map<String, Object> data = Map.of("key", "value");

        Command command1 = new Command("payments", "DebitAccount", commandId, data, null, null, createdAt);
        Command command2 = new Command("payments", "DebitAccount", commandId, data, null, null, createdAt);
        Command command3 = new Command("orders", "DebitAccount", commandId, data, null, null, createdAt);

        assertEquals(command1, command2);
        assertEquals(command1.hashCode(), command2.hashCode());
        assertNotEquals(command1, command3);
    }
}
