package com.commandbus.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

class CommandStatusTest {

    @Test
    void shouldHaveAllExpectedValues() {
        assertEquals(6, CommandStatus.values().length);
        assertNotNull(CommandStatus.PENDING);
        assertNotNull(CommandStatus.IN_PROGRESS);
        assertNotNull(CommandStatus.COMPLETED);
        assertNotNull(CommandStatus.FAILED);
        assertNotNull(CommandStatus.CANCELED);
        assertNotNull(CommandStatus.IN_TROUBLESHOOTING_QUEUE);
    }

    @ParameterizedTest
    @EnumSource(CommandStatus.class)
    void getValueShouldReturnNonNullString(CommandStatus status) {
        assertNotNull(status.getValue());
        assertFalse(status.getValue().isEmpty());
    }

    @ParameterizedTest
    @EnumSource(CommandStatus.class)
    void fromValueShouldReturnCorrectEnum(CommandStatus status) {
        assertEquals(status, CommandStatus.fromValue(status.getValue()));
    }

    @Test
    void fromValueShouldThrowForUnknownValue() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> CommandStatus.fromValue("UNKNOWN")
        );
        assertTrue(exception.getMessage().contains("Unknown CommandStatus"));
    }

    @Test
    void valuesShouldMatchDatabaseValues() {
        assertEquals("PENDING", CommandStatus.PENDING.getValue());
        assertEquals("IN_PROGRESS", CommandStatus.IN_PROGRESS.getValue());
        assertEquals("COMPLETED", CommandStatus.COMPLETED.getValue());
        assertEquals("FAILED", CommandStatus.FAILED.getValue());
        assertEquals("CANCELED", CommandStatus.CANCELED.getValue());
        assertEquals("IN_TROUBLESHOOTING_QUEUE", CommandStatus.IN_TROUBLESHOOTING_QUEUE.getValue());
    }
}
