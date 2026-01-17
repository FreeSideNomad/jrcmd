package com.ivamare.commandbus.process.step;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("StepStatus")
class StepStatusTest {

    @Test
    @DisplayName("should have all 4 required states")
    void shouldHaveAllFourRequiredStates() {
        StepStatus[] values = StepStatus.values();

        assertEquals(4, values.length);
        assertNotNull(StepStatus.STARTED);
        assertNotNull(StepStatus.COMPLETED);
        assertNotNull(StepStatus.FAILED);
        assertNotNull(StepStatus.WAITING_RETRY);
    }

    @Test
    @DisplayName("should convert to and from string")
    void shouldConvertToAndFromString() {
        for (StepStatus status : StepStatus.values()) {
            String name = status.name();
            StepStatus parsed = StepStatus.valueOf(name);
            assertEquals(status, parsed);
        }
    }

    @Test
    @DisplayName("should have correct ordinal values")
    void shouldHaveCorrectOrdinalValues() {
        assertEquals(0, StepStatus.STARTED.ordinal());
        assertEquals(1, StepStatus.COMPLETED.ordinal());
        assertEquals(2, StepStatus.FAILED.ordinal());
        assertEquals(3, StepStatus.WAITING_RETRY.ordinal());
    }
}
