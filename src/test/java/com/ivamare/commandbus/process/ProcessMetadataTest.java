package com.ivamare.commandbus.process;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ProcessMetadata")
class ProcessMetadataTest {

    // Test step enum
    enum TestStep {
        STEP_ONE, STEP_TWO, STEP_THREE
    }

    // Test state implementation
    record TestState(String value) implements ProcessState {
        @Override
        public Map<String, Object> toMap() {
            return Map.of("value", value);
        }

        public static TestState fromMap(Map<String, Object> data) {
            return new TestState((String) data.get("value"));
        }
    }

    @Test
    @DisplayName("should create new process in PENDING status")
    void shouldCreateNewProcessInPendingStatus() {
        UUID processId = UUID.randomUUID();
        TestState state = new TestState("initial");

        ProcessMetadata<TestState, TestStep> process = ProcessMetadata.create(
            "orders",
            processId,
            "ORDER_FULFILLMENT",
            state
        );

        assertEquals("orders", process.domain());
        assertEquals(processId, process.processId());
        assertEquals("ORDER_FULFILLMENT", process.processType());
        assertEquals(state, process.state());
        assertEquals(ProcessStatus.PENDING, process.status());
        assertNull(process.currentStep());
        assertNotNull(process.createdAt());
        assertNotNull(process.updatedAt());
        assertNull(process.completedAt());
        assertNull(process.errorCode());
        assertNull(process.errorMessage());
    }

    @Test
    @DisplayName("should update status with withStatus")
    void shouldUpdateStatusWithWithStatus() {
        ProcessMetadata<TestState, TestStep> process = createTestProcess();
        Instant originalUpdatedAt = process.updatedAt();

        // Small delay to ensure different timestamp
        ProcessMetadata<TestState, TestStep> updated = process.withStatus(ProcessStatus.IN_PROGRESS);

        assertEquals(ProcessStatus.IN_PROGRESS, updated.status());
        assertEquals(process.processId(), updated.processId());
        assertEquals(process.state(), updated.state());
        assertTrue(updated.updatedAt().compareTo(originalUpdatedAt) >= 0);
    }

    @Test
    @DisplayName("should update state with withState")
    void shouldUpdateStateWithWithState() {
        ProcessMetadata<TestState, TestStep> process = createTestProcess();
        TestState newState = new TestState("updated");

        ProcessMetadata<TestState, TestStep> updated = process.withState(newState);

        assertEquals(newState, updated.state());
        assertEquals(process.processId(), updated.processId());
        assertEquals(process.status(), updated.status());
    }

    @Test
    @DisplayName("should update current step with withCurrentStep")
    void shouldUpdateCurrentStepWithWithCurrentStep() {
        ProcessMetadata<TestState, TestStep> process = createTestProcess();

        ProcessMetadata<TestState, TestStep> updated = process.withCurrentStep(TestStep.STEP_ONE);

        assertEquals(TestStep.STEP_ONE, updated.currentStep());
        assertEquals(process.processId(), updated.processId());
    }

    @Test
    @DisplayName("should update status and step together")
    void shouldUpdateStatusAndStepTogether() {
        ProcessMetadata<TestState, TestStep> process = createTestProcess();

        ProcessMetadata<TestState, TestStep> updated = process.withStatusAndStep(
            ProcessStatus.WAITING_FOR_REPLY,
            TestStep.STEP_TWO
        );

        assertEquals(ProcessStatus.WAITING_FOR_REPLY, updated.status());
        assertEquals(TestStep.STEP_TWO, updated.currentStep());
    }

    @Test
    @DisplayName("should set error with withError")
    void shouldSetErrorWithWithError() {
        ProcessMetadata<TestState, TestStep> process = createTestProcess();

        ProcessMetadata<TestState, TestStep> updated = process.withError("ERR001", "Something failed");

        assertEquals("ERR001", updated.errorCode());
        assertEquals("Something failed", updated.errorMessage());
        assertEquals(process.status(), updated.status());
    }

    @Test
    @DisplayName("should mark completion with withCompletion")
    void shouldMarkCompletionWithWithCompletion() {
        ProcessMetadata<TestState, TestStep> process = createTestProcess();

        ProcessMetadata<TestState, TestStep> updated = process.withCompletion(ProcessStatus.COMPLETED);

        assertEquals(ProcessStatus.COMPLETED, updated.status());
        assertNotNull(updated.completedAt());
        assertTrue(updated.completedAt().compareTo(updated.createdAt()) >= 0);
    }

    @Test
    @DisplayName("should be immutable")
    void shouldBeImmutable() {
        ProcessMetadata<TestState, TestStep> original = createTestProcess();
        ProcessMetadata<TestState, TestStep> updated = original.withStatus(ProcessStatus.IN_PROGRESS);

        // Original should remain unchanged
        assertEquals(ProcessStatus.PENDING, original.status());
        assertEquals(ProcessStatus.IN_PROGRESS, updated.status());
        assertNotSame(original, updated);
    }

    private ProcessMetadata<TestState, TestStep> createTestProcess() {
        return ProcessMetadata.create(
            "orders",
            UUID.randomUUID(),
            "ORDER_FULFILLMENT",
            new TestState("initial")
        );
    }
}
