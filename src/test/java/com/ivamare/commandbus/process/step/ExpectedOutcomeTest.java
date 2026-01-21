package com.ivamare.commandbus.process.step;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ExpectedOutcome")
class ExpectedOutcomeTest {

    @Test
    @DisplayName("should have all expected values")
    void shouldHaveAllExpectedValues() {
        ExpectedOutcome[] values = ExpectedOutcome.values();

        assertEquals(5, values.length);
        assertNotNull(ExpectedOutcome.SUCCESS);
        assertNotNull(ExpectedOutcome.TIMEOUT);
        assertNotNull(ExpectedOutcome.TRANSIENT_FAILURE);
        assertNotNull(ExpectedOutcome.PERMANENT_FAILURE);
        assertNotNull(ExpectedOutcome.BUSINESS_ERROR);
    }

    @Test
    @DisplayName("valueOf should return correct value")
    void valueOfShouldReturnCorrectValue() {
        assertEquals(ExpectedOutcome.SUCCESS, ExpectedOutcome.valueOf("SUCCESS"));
        assertEquals(ExpectedOutcome.TIMEOUT, ExpectedOutcome.valueOf("TIMEOUT"));
        assertEquals(ExpectedOutcome.TRANSIENT_FAILURE, ExpectedOutcome.valueOf("TRANSIENT_FAILURE"));
        assertEquals(ExpectedOutcome.PERMANENT_FAILURE, ExpectedOutcome.valueOf("PERMANENT_FAILURE"));
        assertEquals(ExpectedOutcome.BUSINESS_ERROR, ExpectedOutcome.valueOf("BUSINESS_ERROR"));
    }

    @Test
    @DisplayName("ordinal values should be in expected order")
    void ordinalValuesShouldBeInExpectedOrder() {
        assertEquals(0, ExpectedOutcome.SUCCESS.ordinal());
        assertEquals(1, ExpectedOutcome.TIMEOUT.ordinal());
        assertEquals(2, ExpectedOutcome.TRANSIENT_FAILURE.ordinal());
        assertEquals(3, ExpectedOutcome.PERMANENT_FAILURE.ordinal());
        assertEquals(4, ExpectedOutcome.BUSINESS_ERROR.ordinal());
    }
}
