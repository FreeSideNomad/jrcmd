package com.ivamare.commandbus.process.step;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("WaitRecord")
class WaitRecordTest {

    @Test
    @DisplayName("should create pending wait record with factory method")
    void shouldCreatePendingWaitRecord() {
        Duration timeout = Duration.ofMinutes(5);
        Instant timeoutAt = Instant.now().plus(timeout);
        WaitRecord record = WaitRecord.pending("awaitL1", timeout, timeoutAt);

        assertEquals("awaitL1", record.name());
        assertFalse(record.satisfied());
        assertNotNull(record.recordedAt());
        assertEquals(timeout, record.timeout());
        assertEquals(timeoutAt, record.timeoutAt());
    }

    @Test
    @DisplayName("should create satisfied wait record with factory method")
    void shouldCreateSatisfiedWaitRecord() {
        WaitRecord record = WaitRecord.satisfied("awaitL1");

        assertEquals("awaitL1", record.name());
        assertTrue(record.satisfied());
        assertNotNull(record.recordedAt());
        assertNull(record.timeout());
        assertNull(record.timeoutAt());
    }

    @Test
    @DisplayName("should mark pending wait as satisfied")
    void shouldMarkPendingWaitAsSatisfied() {
        Duration timeout = Duration.ofMinutes(5);
        Instant timeoutAt = Instant.now().plus(timeout);
        WaitRecord pending = WaitRecord.pending("awaitL1", timeout, timeoutAt);
        WaitRecord satisfied = pending.markSatisfied();

        assertEquals("awaitL1", satisfied.name());
        assertTrue(satisfied.satisfied());
        assertEquals(pending.recordedAt(), satisfied.recordedAt());
        assertEquals(timeout, satisfied.timeout());
        assertEquals(timeoutAt, satisfied.timeoutAt());
    }

    @Test
    @DisplayName("isTimedOut should return true when past timeout")
    void isTimedOutShouldReturnTrueWhenPastTimeout() {
        Duration timeout = Duration.ofMillis(1);
        Instant timeoutAt = Instant.now().minusSeconds(1);
        WaitRecord record = new WaitRecord("awaitL1", false, Instant.now(), timeout, timeoutAt);

        assertTrue(record.isTimedOut());
    }

    @Test
    @DisplayName("isTimedOut should return false when before timeout")
    void isTimedOutShouldReturnFalseWhenBeforeTimeout() {
        Duration timeout = Duration.ofHours(1);
        Instant timeoutAt = Instant.now().plus(Duration.ofHours(1));
        WaitRecord record = WaitRecord.pending("awaitL1", timeout, timeoutAt);

        assertFalse(record.isTimedOut());
    }

    @Test
    @DisplayName("isTimedOut should return false when satisfied")
    void isTimedOutShouldReturnFalseWhenSatisfied() {
        Duration timeout = Duration.ofMillis(1);
        Instant timeoutAt = Instant.now().minusSeconds(1);
        WaitRecord record = new WaitRecord("awaitL1", true, Instant.now(), timeout, timeoutAt);

        assertFalse(record.isTimedOut());
    }

    @Test
    @DisplayName("isTimedOut should return false when no timeout configured")
    void isTimedOutShouldReturnFalseWhenNoTimeoutConfigured() {
        WaitRecord record = new WaitRecord("awaitL1", false, Instant.now(), null, null);

        assertFalse(record.isTimedOut());
    }

    @Test
    @DisplayName("record equality should work correctly")
    void recordEqualityShouldWorkCorrectly() {
        Instant now = Instant.now();
        Duration timeout = Duration.ofMinutes(5);
        WaitRecord record1 = new WaitRecord("test", true, now, timeout, now);
        WaitRecord record2 = new WaitRecord("test", true, now, timeout, now);

        assertEquals(record1, record2);
        assertEquals(record1.hashCode(), record2.hashCode());
    }
}
