package com.ivamare.commandbus.process.step;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("StepRecord")
class StepRecordTest {

    @Test
    @DisplayName("should create started step record with factory method")
    void shouldCreateStartedStepRecord() {
        StepRecord record = StepRecord.started("bookRisk", 3, "{\"amount\": 100}");

        assertEquals("bookRisk", record.name());
        assertEquals(StepStatus.STARTED, record.status());
        assertEquals(1, record.attemptCount());
        assertEquals(3, record.maxRetries());
        assertNotNull(record.startedAt());
        assertNull(record.completedAt());
        assertEquals("{\"amount\": 100}", record.requestJson());
        assertNull(record.responseJson());
        assertNull(record.errorCode());
        assertNull(record.errorMessage());
        assertNull(record.nextRetryAt());
    }

    @Test
    @DisplayName("should mark step as completed")
    void shouldMarkStepAsCompleted() {
        StepRecord started = StepRecord.started("bookRisk", 3, "{\"amount\": 100}");
        StepRecord completed = started.completed("{\"reference\": \"RISK-123\"}");

        assertEquals("bookRisk", completed.name());
        assertEquals(StepStatus.COMPLETED, completed.status());
        assertEquals(1, completed.attemptCount());
        assertEquals(3, completed.maxRetries());
        assertEquals(started.startedAt(), completed.startedAt());
        assertNotNull(completed.completedAt());
        assertEquals("{\"amount\": 100}", completed.requestJson());
        assertEquals("{\"reference\": \"RISK-123\"}", completed.responseJson());
        assertNull(completed.errorCode());
        assertNull(completed.errorMessage());
        assertNull(completed.nextRetryAt());
    }

    @Test
    @DisplayName("should mark step as failed")
    void shouldMarkStepAsFailed() {
        StepRecord started = StepRecord.started("bookRisk", 3, "{\"amount\": 100}");
        StepRecord failed = started.failed("RISK_DECLINED", "Insufficient credit limit");

        assertEquals("bookRisk", failed.name());
        assertEquals(StepStatus.FAILED, failed.status());
        assertEquals(1, failed.attemptCount());
        assertNotNull(failed.completedAt());
        assertNull(failed.responseJson());
        assertEquals("RISK_DECLINED", failed.errorCode());
        assertEquals("Insufficient credit limit", failed.errorMessage());
    }

    @Test
    @DisplayName("should schedule retry for transient failure")
    void shouldScheduleRetry() {
        StepRecord started = StepRecord.started("bookRisk", 3, "{\"amount\": 100}");
        Instant retryAt = Instant.now().plusSeconds(30);
        StepRecord waiting = started.waitingRetry(2, retryAt, "TIMEOUT", "Service timeout");

        assertEquals("bookRisk", waiting.name());
        assertEquals(StepStatus.WAITING_RETRY, waiting.status());
        assertEquals(2, waiting.attemptCount());
        assertEquals(3, waiting.maxRetries());
        assertNull(waiting.completedAt());
        assertEquals("TIMEOUT", waiting.errorCode());
        assertEquals("Service timeout", waiting.errorMessage());
        assertEquals(retryAt, waiting.nextRetryAt());
    }

    @Test
    @DisplayName("should restart after retry")
    void shouldRestartAfterRetry() {
        StepRecord started = StepRecord.started("bookRisk", 3, "{\"amount\": 100}");
        Instant retryAt = Instant.now().plusSeconds(30);
        StepRecord waiting = started.waitingRetry(2, retryAt, "TIMEOUT", "Service timeout");
        StepRecord restarted = waiting.retryStarted();

        assertEquals("bookRisk", restarted.name());
        assertEquals(StepStatus.STARTED, restarted.status());
        assertEquals(2, restarted.attemptCount());
        assertEquals(3, restarted.maxRetries());
        assertNotNull(restarted.startedAt());
        assertNull(restarted.completedAt());
        assertNull(restarted.errorCode());
        assertNull(restarted.errorMessage());
        assertNull(restarted.nextRetryAt());
    }

    @Test
    @DisplayName("hasRetriesRemaining should return true when retries available")
    void hasRetriesRemainingShouldReturnTrueWhenRetriesAvailable() {
        StepRecord record = StepRecord.started("test", 3, null);
        assertTrue(record.hasRetriesRemaining());

        StepRecord afterRetry = record.waitingRetry(2, Instant.now(), "ERR", "msg");
        assertTrue(afterRetry.hasRetriesRemaining());
    }

    @Test
    @DisplayName("hasRetriesRemaining should return false when retries exhausted")
    void hasRetriesRemainingShouldReturnFalseWhenRetriesExhausted() {
        StepRecord record = new StepRecord(
            "test", StepStatus.WAITING_RETRY, 3, 3,
            Instant.now(), null, null, null, "ERR", "msg", Instant.now()
        );
        assertFalse(record.hasRetriesRemaining());
    }

    @Test
    @DisplayName("record equality should work correctly")
    void recordEqualityShouldWorkCorrectly() {
        Instant now = Instant.now();
        StepRecord record1 = new StepRecord(
            "test", StepStatus.COMPLETED, 1, 3,
            now, now, "{}", "{}", null, null, null
        );
        StepRecord record2 = new StepRecord(
            "test", StepStatus.COMPLETED, 1, 3,
            now, now, "{}", "{}", null, null, null
        );

        assertEquals(record1, record2);
        assertEquals(record1.hashCode(), record2.hashCode());
    }
}
