package com.ivamare.commandbus.process.step;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ProcessStepState")
class ProcessStepStateTest {

    private TestState state;

    @BeforeEach
    void setUp() {
        state = new TestState();
    }

    // ========== Step History Tests ==========

    @Test
    @DisplayName("should initialize with empty step history")
    void shouldInitializeWithEmptyStepHistory() {
        assertTrue(state.getStepHistory().isEmpty());
    }

    @Test
    @DisplayName("should record and find completed step")
    void shouldRecordAndFindCompletedStep() {
        StepRecord record = StepRecord.started("bookRisk", 3, "{}").completed("{}");
        state.recordStep(record);

        Optional<StepRecord> found = state.findCompletedStep("bookRisk");
        assertTrue(found.isPresent());
        assertEquals("bookRisk", found.get().name());
        assertEquals(StepStatus.COMPLETED, found.get().status());
    }

    @Test
    @DisplayName("should not find non-completed step as completed")
    void shouldNotFindNonCompletedStepAsCompleted() {
        StepRecord record = StepRecord.started("bookRisk", 3, "{}");
        state.recordStep(record);

        Optional<StepRecord> found = state.findCompletedStep("bookRisk");
        assertFalse(found.isPresent());
    }

    @Test
    @DisplayName("should find step by name regardless of status")
    void shouldFindStepByNameRegardlessOfStatus() {
        StepRecord record = StepRecord.started("bookRisk", 3, "{}");
        state.recordStep(record);

        Optional<StepRecord> found = state.findStep("bookRisk");
        assertTrue(found.isPresent());
        assertEquals(StepStatus.STARTED, found.get().status());
    }

    @Test
    @DisplayName("should update existing step record")
    void shouldUpdateExistingStepRecord() {
        StepRecord started = StepRecord.started("bookRisk", 3, "{}");
        state.recordStep(started);

        StepRecord completed = started.completed("{\"ref\": \"123\"}");
        state.recordStep(completed);

        assertEquals(1, state.getStepHistory().size());
        assertEquals(StepStatus.COMPLETED, state.findStep("bookRisk").get().status());
    }

    @Test
    @DisplayName("should get completed steps in order")
    void shouldGetCompletedStepsInOrder() {
        state.recordStep(StepRecord.started("step1", 1, "{}").completed("{}"));
        state.recordStep(StepRecord.started("step2", 1, "{}").completed("{}"));
        state.recordStep(StepRecord.started("step3", 1, "{}")); // Not completed

        List<StepRecord> completed = state.getCompletedStepsInOrder();
        assertEquals(2, completed.size());
        assertEquals("step1", completed.get(0).name());
        assertEquals("step2", completed.get(1).name());
    }

    @Test
    @DisplayName("should get completed steps in reverse order for compensation")
    void shouldGetCompletedStepsInReverseOrder() {
        state.recordStep(StepRecord.started("step1", 1, "{}").completed("{}"));
        state.recordStep(StepRecord.started("step2", 1, "{}").completed("{}"));

        List<StepRecord> reversed = state.getCompletedStepsReversed();
        assertEquals(2, reversed.size());
        assertEquals("step2", reversed.get(0).name());
        assertEquals("step1", reversed.get(1).name());
    }

    // ========== Wait History Tests ==========

    @Test
    @DisplayName("should initialize with empty wait history")
    void shouldInitializeWithEmptyWaitHistory() {
        assertTrue(state.getWaitHistory().isEmpty());
    }

    @Test
    @DisplayName("should record and find wait")
    void shouldRecordAndFindWait() {
        WaitRecord record = WaitRecord.pending("awaitL1", Duration.ofMinutes(5),
            Instant.now().plus(Duration.ofMinutes(5)));
        state.recordWait(record);

        Optional<WaitRecord> found = state.findWait("awaitL1");
        assertTrue(found.isPresent());
        assertEquals("awaitL1", found.get().name());
        assertFalse(found.get().satisfied());
    }

    @Test
    @DisplayName("should check if wait is satisfied")
    void shouldCheckIfWaitIsSatisfied() {
        state.recordWait(WaitRecord.pending("awaitL1", Duration.ofMinutes(5),
            Instant.now().plus(Duration.ofMinutes(5))));
        assertFalse(state.isWaitSatisfied("awaitL1"));

        state.recordWait(WaitRecord.satisfied("awaitL1"));
        assertTrue(state.isWaitSatisfied("awaitL1"));
    }

    @Test
    @DisplayName("should return false for unknown wait")
    void shouldReturnFalseForUnknownWait() {
        assertFalse(state.isWaitSatisfied("unknownWait"));
    }

    // ========== Side Effect Tests ==========

    @Test
    @DisplayName("should initialize with empty side effects")
    void shouldInitializeWithEmptySideEffects() {
        assertTrue(state.getSideEffects().isEmpty());
    }

    @Test
    @DisplayName("should record and find side effect")
    void shouldRecordAndFindSideEffect() {
        SideEffectRecord record = SideEffectRecord.create("generateId", "\"ID-123\"");
        state.recordSideEffect(record);

        Optional<SideEffectRecord> found = state.findSideEffect("generateId");
        assertTrue(found.isPresent());
        assertEquals("generateId", found.get().name());
        assertEquals("\"ID-123\"", found.get().valueJson());
    }

    // ========== Error Tracking Tests ==========

    @Test
    @DisplayName("should track error code and message")
    void shouldTrackErrorCodeAndMessage() {
        assertNull(state.getErrorCode());
        assertNull(state.getErrorMessage());
        assertFalse(state.hasError());

        state.setErrorCode("RISK_DECLINED");
        state.setErrorMessage("Insufficient credit limit");

        assertEquals("RISK_DECLINED", state.getErrorCode());
        assertEquals("Insufficient credit limit", state.getErrorMessage());
        assertTrue(state.hasError());
    }

    @Test
    @DisplayName("should clear error")
    void shouldClearError() {
        state.setErrorCode("ERR");
        state.setErrorMessage("msg");
        assertTrue(state.hasError());

        state.clearError();
        assertFalse(state.hasError());
        assertNull(state.getErrorCode());
        assertNull(state.getErrorMessage());
    }

    // ========== Process Deadline Tests ==========

    @Test
    @DisplayName("should track process deadline")
    void shouldTrackProcessDeadline() {
        assertNull(state.getProcessDeadline());
        assertFalse(state.isDeadlineExceeded());

        Instant deadline = Instant.now().plus(Duration.ofHours(4));
        state.setProcessDeadline(deadline);

        assertEquals(deadline, state.getProcessDeadline());
        assertFalse(state.isDeadlineExceeded());
    }

    @Test
    @DisplayName("should detect exceeded deadline")
    void shouldDetectExceededDeadline() {
        state.setProcessDeadline(Instant.now().minusSeconds(1));
        assertTrue(state.isDeadlineExceeded());
    }

    // ========== toMap Tests ==========

    @Test
    @DisplayName("should serialize to map with all fields")
    void shouldSerializeToMapWithAllFields() {
        state.setErrorCode("ERR");
        state.setErrorMessage("msg");
        state.setProcessDeadline(Instant.parse("2024-01-15T10:30:00Z"));
        state.recordStep(StepRecord.started("step1", 1, "{}").completed("{}"));
        state.recordWait(WaitRecord.satisfied("wait1"));
        state.recordSideEffect(SideEffectRecord.create("effect1", "{}"));

        Map<String, Object> map = state.toMap();

        assertNotNull(map.get("stepHistory"));
        assertNotNull(map.get("waitHistory"));
        assertNotNull(map.get("sideEffects"));
        assertEquals("ERR", map.get("errorCode"));
        assertEquals("msg", map.get("errorMessage"));
        assertEquals("2024-01-15T10:30:00Z", map.get("processDeadline"));
    }

    // ========== Setter Tests ==========

    @Test
    @DisplayName("should handle null values in setters")
    void shouldHandleNullValuesInSetters() {
        state.setStepHistory(null);
        assertNotNull(state.getStepHistory());
        assertTrue(state.getStepHistory().isEmpty());

        state.setWaitHistory(null);
        assertNotNull(state.getWaitHistory());
        assertTrue(state.getWaitHistory().isEmpty());

        state.setSideEffects(null);
        assertNotNull(state.getSideEffects());
        assertTrue(state.getSideEffects().isEmpty());
    }

    // Test implementation of abstract class
    private static class TestState extends ProcessStepState {
        // Minimal implementation for testing
    }
}
