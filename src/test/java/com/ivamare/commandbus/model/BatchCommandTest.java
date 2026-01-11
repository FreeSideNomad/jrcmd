package com.ivamare.commandbus.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("BatchCommand")
class BatchCommandTest {

    @Test
    @DisplayName("should create batch command with all fields")
    void shouldCreateBatchCommandWithAllFields() {
        UUID commandId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        Map<String, Object> data = Map.of("key", "value");

        BatchCommand cmd = new BatchCommand(
            "TestCommand", commandId, data, correlationId, "reply_queue", 5
        );

        assertEquals("TestCommand", cmd.commandType());
        assertEquals(commandId, cmd.commandId());
        assertEquals(data, cmd.data());
        assertEquals(correlationId, cmd.correlationId());
        assertEquals("reply_queue", cmd.replyTo());
        assertEquals(5, cmd.maxAttempts());
    }

    @Test
    @DisplayName("should throw on null command type")
    void shouldThrowOnNullCommandType() {
        UUID commandId = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class,
            () -> new BatchCommand(null, commandId, Map.of(), null, null, null));
    }

    @Test
    @DisplayName("should throw on blank command type")
    void shouldThrowOnBlankCommandType() {
        UUID commandId = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class,
            () -> new BatchCommand("   ", commandId, Map.of(), null, null, null));
    }

    @Test
    @DisplayName("should throw on null command id")
    void shouldThrowOnNullCommandId() {
        assertThrows(IllegalArgumentException.class,
            () -> new BatchCommand("TestCommand", null, Map.of(), null, null, null));
    }

    @Test
    @DisplayName("should default null data to empty map")
    void shouldDefaultNullDataToEmptyMap() {
        UUID commandId = UUID.randomUUID();

        BatchCommand cmd = new BatchCommand("TestCommand", commandId, null, null, null, null);

        assertEquals(Map.of(), cmd.data());
    }

    @Test
    @DisplayName("should create simple batch command via factory method")
    void shouldCreateSimpleBatchCommandViaFactoryMethod() {
        UUID commandId = UUID.randomUUID();
        Map<String, Object> data = Map.of("key", "value");

        BatchCommand cmd = BatchCommand.of("TestCommand", commandId, data);

        assertEquals("TestCommand", cmd.commandType());
        assertEquals(commandId, cmd.commandId());
        assertEquals(data, cmd.data());
        assertNull(cmd.correlationId());
        assertNull(cmd.replyTo());
        assertNull(cmd.maxAttempts());
    }
}
