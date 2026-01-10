package com.commandbus.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

class BatchStatusTest {

    @Test
    void shouldHaveAllExpectedValues() {
        assertEquals(4, BatchStatus.values().length);
        assertNotNull(BatchStatus.PENDING);
        assertNotNull(BatchStatus.IN_PROGRESS);
        assertNotNull(BatchStatus.COMPLETED);
        assertNotNull(BatchStatus.COMPLETED_WITH_FAILURES);
    }

    @ParameterizedTest
    @EnumSource(BatchStatus.class)
    void getValueShouldReturnNonNullString(BatchStatus status) {
        assertNotNull(status.getValue());
        assertFalse(status.getValue().isEmpty());
    }

    @ParameterizedTest
    @EnumSource(BatchStatus.class)
    void fromValueShouldReturnCorrectEnum(BatchStatus status) {
        assertEquals(status, BatchStatus.fromValue(status.getValue()));
    }

    @Test
    void fromValueShouldThrowForUnknownValue() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> BatchStatus.fromValue("UNKNOWN")
        );
        assertTrue(exception.getMessage().contains("Unknown BatchStatus"));
    }

    @Test
    void valuesShouldMatchDatabaseValues() {
        assertEquals("PENDING", BatchStatus.PENDING.getValue());
        assertEquals("IN_PROGRESS", BatchStatus.IN_PROGRESS.getValue());
        assertEquals("COMPLETED", BatchStatus.COMPLETED.getValue());
        assertEquals("COMPLETED_WITH_FAILURES", BatchStatus.COMPLETED_WITH_FAILURES.getValue());
    }
}
