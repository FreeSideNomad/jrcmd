package com.ivamare.commandbus.process.step;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ExceptionType")
class ExceptionTypeTest {

    @Test
    @DisplayName("should have all 3 required types")
    void shouldHaveAllThreeRequiredTypes() {
        ExceptionType[] values = ExceptionType.values();

        assertEquals(3, values.length);
        assertNotNull(ExceptionType.TRANSIENT);
        assertNotNull(ExceptionType.BUSINESS);
        assertNotNull(ExceptionType.PERMANENT);
    }

    @Test
    @DisplayName("should convert to and from string")
    void shouldConvertToAndFromString() {
        for (ExceptionType type : ExceptionType.values()) {
            String name = type.name();
            ExceptionType parsed = ExceptionType.valueOf(name);
            assertEquals(type, parsed);
        }
    }

    @Test
    @DisplayName("should have correct ordinal values")
    void shouldHaveCorrectOrdinalValues() {
        assertEquals(0, ExceptionType.TRANSIENT.ordinal());
        assertEquals(1, ExceptionType.BUSINESS.ordinal());
        assertEquals(2, ExceptionType.PERMANENT.ordinal());
    }
}
