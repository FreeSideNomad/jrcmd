package com.ivamare.commandbus.process.step;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DeadlineAction")
class DeadlineActionTest {

    @Test
    @DisplayName("should have all 3 required actions")
    void shouldHaveAllThreeRequiredActions() {
        DeadlineAction[] values = DeadlineAction.values();

        assertEquals(3, values.length);
        assertNotNull(DeadlineAction.TSQ);
        assertNotNull(DeadlineAction.COMPENSATE);
        assertNotNull(DeadlineAction.FAIL);
    }

    @Test
    @DisplayName("should convert to and from string")
    void shouldConvertToAndFromString() {
        for (DeadlineAction action : DeadlineAction.values()) {
            String name = action.name();
            DeadlineAction parsed = DeadlineAction.valueOf(name);
            assertEquals(action, parsed);
        }
    }

    @Test
    @DisplayName("should have correct ordinal values")
    void shouldHaveCorrectOrdinalValues() {
        assertEquals(0, DeadlineAction.TSQ.ordinal());
        assertEquals(1, DeadlineAction.COMPENSATE.ordinal());
        assertEquals(2, DeadlineAction.FAIL.ordinal());
    }
}
