package com.ivamare.commandbus.process.step;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ExecutionContext")
class ExecutionContextTest {

    private UUID processId;
    private TestState state;
    private ExecutionContext<TestState> context;

    @BeforeEach
    void setUp() {
        processId = UUID.randomUUID();
        state = new TestState();
        context = new ExecutionContext<>(processId, state);
    }

    // ========== Basic Accessors ==========

    @Test
    @DisplayName("should return process ID")
    void shouldReturnProcessId() {
        assertEquals(processId, context.processId());
    }

    @Test
    @DisplayName("should return state")
    void shouldReturnState() {
        assertSame(state, context.state());
    }

    // ========== Step Operations ==========

    @Test
    @DisplayName("should record and get completed step")
    void shouldRecordAndGetCompletedStep() {
        context.recordStepStart("step1", 3, "{}");
        context.recordStepCompleted("step1", "{\"result\": true}");

        Optional<StepRecord> found = context.getCompletedStep("step1");
        assertTrue(found.isPresent());
        assertEquals(StepStatus.COMPLETED, found.get().status());
    }

    @Test
    @DisplayName("should check if step is completed")
    void shouldCheckIfStepIsCompleted() {
        assertFalse(context.isStepCompleted("step1"));

        context.recordStepStart("step1", 1, "{}");
        assertFalse(context.isStepCompleted("step1"));

        context.recordStepCompleted("step1", "{}");
        assertTrue(context.isStepCompleted("step1"));
    }

    @Test
    @DisplayName("should get attempt count for step")
    void shouldGetAttemptCountForStep() {
        assertEquals(0, context.getAttemptCount("step1"));

        context.recordStepStart("step1", 3, "{}");
        assertEquals(1, context.getAttemptCount("step1"));

        context.recordStepRetry("step1", 2, 3, Instant.now().plusSeconds(30), "ERR", "msg");
        assertEquals(2, context.getAttemptCount("step1"));
    }

    @Test
    @DisplayName("should record step failure")
    void shouldRecordStepFailure() {
        context.recordStepStart("step1", 3, "{}");
        context.recordStepFailed("step1", "ERROR_CODE", "Error message");

        Optional<StepRecord> found = state.findStep("step1");
        assertTrue(found.isPresent());
        assertEquals(StepStatus.FAILED, found.get().status());
        assertEquals("ERROR_CODE", found.get().errorCode());
        assertEquals("Error message", found.get().errorMessage());
    }

    @Test
    @DisplayName("should record step retry")
    void shouldRecordStepRetry() {
        context.recordStepStart("step1", 3, "{}");
        Instant retryAt = Instant.now().plusSeconds(30);
        context.recordStepRetry("step1", 2, 3, retryAt, "TIMEOUT", "Service timeout");

        Optional<StepRecord> found = state.findStep("step1");
        assertTrue(found.isPresent());
        assertEquals(StepStatus.WAITING_RETRY, found.get().status());
        assertEquals(2, found.get().attemptCount());
        assertEquals(retryAt, found.get().nextRetryAt());
    }

    // ========== Wait Operations ==========

    @Test
    @DisplayName("should record and get wait")
    void shouldRecordAndGetWait() {
        Duration timeout = Duration.ofMinutes(5);
        Instant timeoutAt = Instant.now().plus(timeout);
        context.recordWaitPending("awaitL1", timeout, timeoutAt);

        Optional<WaitRecord> found = context.getWait("awaitL1");
        assertTrue(found.isPresent());
        assertFalse(found.get().satisfied());
    }

    @Test
    @DisplayName("should check if wait is satisfied")
    void shouldCheckIfWaitIsSatisfied() {
        assertFalse(context.isWaitSatisfied("awaitL1"));

        context.recordWaitPending("awaitL1", Duration.ofMinutes(5),
            Instant.now().plus(Duration.ofMinutes(5)));
        assertFalse(context.isWaitSatisfied("awaitL1"));

        context.recordWaitSatisfied("awaitL1");
        assertTrue(context.isWaitSatisfied("awaitL1"));
    }

    @Test
    @DisplayName("should record wait satisfied without prior pending record")
    void shouldRecordWaitSatisfiedWithoutPriorPendingRecord() {
        context.recordWaitSatisfied("awaitL1");

        Optional<WaitRecord> found = context.getWait("awaitL1");
        assertTrue(found.isPresent());
        assertTrue(found.get().satisfied());
    }

    // ========== Side Effect Operations ==========

    @Test
    @DisplayName("should record and get side effect")
    void shouldRecordAndGetSideEffect() {
        context.recordSideEffect("generateId", "\"ID-123\"");

        Optional<SideEffectRecord> found = context.getSideEffect("generateId");
        assertTrue(found.isPresent());
        assertEquals("\"ID-123\"", found.get().valueJson());
    }

    @Test
    @DisplayName("should return empty for unknown side effect")
    void shouldReturnEmptyForUnknownSideEffect() {
        Optional<SideEffectRecord> found = context.getSideEffect("unknown");
        assertFalse(found.isPresent());
    }

    // ========== Compensation Operations ==========

    @Test
    @DisplayName("should register and get compensation")
    void shouldRegisterAndGetCompensation() {
        Consumer<TestState> compensation = s -> {};
        context.registerCompensation("step1", compensation);

        assertSame(compensation, context.getCompensation("step1"));
    }

    @Test
    @DisplayName("should return null for unregistered compensation")
    void shouldReturnNullForUnregisteredCompensation() {
        assertNull(context.getCompensation("step1"));
    }

    @Test
    @DisplayName("should not register null compensation")
    void shouldNotRegisterNullCompensation() {
        context.registerCompensation("step1", null);
        assertNull(context.getCompensation("step1"));
    }

    @Test
    @DisplayName("should get all compensations")
    void shouldGetAllCompensations() {
        Consumer<TestState> comp1 = s -> {};
        Consumer<TestState> comp2 = s -> {};
        context.registerCompensation("step1", comp1);
        context.registerCompensation("step2", comp2);

        Map<String, Consumer<TestState>> all = context.getAllCompensations();
        assertEquals(2, all.size());
        assertSame(comp1, all.get("step1"));
        assertSame(comp2, all.get("step2"));
    }

    @Test
    @DisplayName("should get completed steps in reverse order")
    void shouldGetCompletedStepsInReverseOrder() {
        context.recordStepStart("step1", 1, "{}");
        context.recordStepCompleted("step1", "{}");
        context.recordStepStart("step2", 1, "{}");
        context.recordStepCompleted("step2", "{}");

        var reversed = context.getCompletedStepsReversed();
        assertEquals(2, reversed.size());
        assertEquals("step2", reversed.get(0).name());
        assertEquals("step1", reversed.get(1).name());
    }

    // ========== Error Operations ==========

    @Test
    @DisplayName("should record error")
    void shouldRecordError() {
        context.recordError("ERR_CODE", "Error message");

        assertEquals("ERR_CODE", state.getErrorCode());
        assertEquals("Error message", state.getErrorMessage());
    }

    @Test
    @DisplayName("should clear error")
    void shouldClearError() {
        state.setErrorCode("ERR");
        state.setErrorMessage("msg");
        assertTrue(state.hasError());

        context.clearError();
        assertFalse(state.hasError());
    }

    // Test implementation of abstract class
    private static class TestState extends ProcessStepState {
    }
}
