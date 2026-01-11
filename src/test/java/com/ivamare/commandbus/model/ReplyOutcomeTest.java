package com.ivamare.commandbus.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

class ReplyOutcomeTest {

    @Test
    void shouldHaveAllExpectedValues() {
        assertEquals(3, ReplyOutcome.values().length);
        assertNotNull(ReplyOutcome.SUCCESS);
        assertNotNull(ReplyOutcome.FAILED);
        assertNotNull(ReplyOutcome.CANCELED);
    }

    @ParameterizedTest
    @EnumSource(ReplyOutcome.class)
    void getValueShouldReturnNonNullString(ReplyOutcome outcome) {
        assertNotNull(outcome.getValue());
        assertFalse(outcome.getValue().isEmpty());
    }

    @ParameterizedTest
    @EnumSource(ReplyOutcome.class)
    void fromValueShouldReturnCorrectEnum(ReplyOutcome outcome) {
        assertEquals(outcome, ReplyOutcome.fromValue(outcome.getValue()));
    }

    @Test
    void fromValueShouldThrowForUnknownValue() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> ReplyOutcome.fromValue("UNKNOWN")
        );
        assertTrue(exception.getMessage().contains("Unknown ReplyOutcome"));
    }

    @Test
    void valuesShouldMatchDatabaseValues() {
        assertEquals("SUCCESS", ReplyOutcome.SUCCESS.getValue());
        assertEquals("FAILED", ReplyOutcome.FAILED.getValue());
        assertEquals("CANCELED", ReplyOutcome.CANCELED.getValue());
    }
}
