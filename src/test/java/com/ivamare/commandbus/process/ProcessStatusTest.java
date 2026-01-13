package com.ivamare.commandbus.process;

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

    @Test
    @DisplayName("isTerminal should return true for terminal statuses")
    void isTerminalShouldReturnTrueForTerminalStatuses() {
        assertTrue(ProcessStatus.COMPLETED.isTerminal());
        assertTrue(ProcessStatus.COMPENSATED.isTerminal());
        assertTrue(ProcessStatus.FAILED.isTerminal());
        assertTrue(ProcessStatus.CANCELED.isTerminal());
    }

    @Test
    @DisplayName("isTerminal should return false for non-terminal statuses")
    void isTerminalShouldReturnFalseForNonTerminalStatuses() {
        assertFalse(ProcessStatus.PENDING.isTerminal());
        assertFalse(ProcessStatus.IN_PROGRESS.isTerminal());
        assertFalse(ProcessStatus.WAITING_FOR_REPLY.isTerminal());
        assertFalse(ProcessStatus.WAITING_FOR_TSQ.isTerminal());
        assertFalse(ProcessStatus.COMPENSATING.isTerminal());
    }
}
