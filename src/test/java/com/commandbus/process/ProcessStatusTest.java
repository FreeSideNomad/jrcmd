package com.commandbus.process;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ProcessStatus")
class ProcessStatusTest {

    @Test
    @DisplayName("should have all 9 required states")
    void shouldHaveAllNineRequiredStates() {
        ProcessStatus[] values = ProcessStatus.values();

        assertEquals(9, values.length);
        assertNotNull(ProcessStatus.PENDING);
        assertNotNull(ProcessStatus.IN_PROGRESS);
        assertNotNull(ProcessStatus.WAITING_FOR_REPLY);
        assertNotNull(ProcessStatus.WAITING_FOR_TSQ);
        assertNotNull(ProcessStatus.COMPENSATING);
        assertNotNull(ProcessStatus.COMPLETED);
        assertNotNull(ProcessStatus.COMPENSATED);
        assertNotNull(ProcessStatus.FAILED);
        assertNotNull(ProcessStatus.CANCELED);
    }

    @Test
    @DisplayName("should convert to and from string")
    void shouldConvertToAndFromString() {
        for (ProcessStatus status : ProcessStatus.values()) {
            String name = status.name();
            ProcessStatus parsed = ProcessStatus.valueOf(name);
            assertEquals(status, parsed);
        }
    }
}
