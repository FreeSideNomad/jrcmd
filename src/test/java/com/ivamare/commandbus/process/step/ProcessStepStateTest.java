package com.ivamare.commandbus.process.step;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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

    // ========== Command Step Tracking Tests ==========

    @Nested
    @DisplayName("Command Step Tracking")
    class CommandStepTrackingTests {

        @Test
        @DisplayName("should store and retrieve pending command step")
        void shouldStoreAndRetrievePendingCommandStep() {
            UUID commandId = UUID.randomUUID();
            state.storePendingCommandStep("bookFx", commandId, 30);

            assertTrue(state.isWaitingForCommandStep("bookFx"));
            Optional<UUID> retrievedId = state.getPendingCommandId("bookFx");
            assertTrue(retrievedId.isPresent());
            assertEquals(commandId, retrievedId.get());

            Optional<PendingCommandStep> pendingStep = state.getPendingCommandStep("bookFx");
            assertTrue(pendingStep.isPresent());
            assertEquals("bookFx", pendingStep.get().stepName());
            assertEquals(commandId, pendingStep.get().commandId());
        }

        @Test
        @DisplayName("should return empty for non-existent pending command")
        void shouldReturnEmptyForNonExistentPendingCommand() {
            assertFalse(state.isWaitingForCommandStep("unknown"));
            assertTrue(state.getPendingCommandId("unknown").isEmpty());
            assertTrue(state.getPendingCommandStep("unknown").isEmpty());
        }

        @Test
        @DisplayName("should store and retrieve command step response")
        void shouldStoreAndRetrieveCommandStepResponse() {
            UUID processId = UUID.randomUUID();
            CommandStepResponse<String> response = CommandStepResponse.success(
                processId, "bookFx", "FX-12345");

            state.storeCommandStepResponse("bookFx", response);

            Optional<CommandStepResponse<String>> retrieved = state.getCommandStepResponse("bookFx");
            assertTrue(retrieved.isPresent());
            assertTrue(retrieved.get().success());
            assertEquals("FX-12345", retrieved.get().result());
        }

        @Test
        @DisplayName("should return empty for non-existent response")
        void shouldReturnEmptyForNonExistentResponse() {
            Optional<CommandStepResponse<Object>> retrieved = state.getCommandStepResponse("unknown");
            assertTrue(retrieved.isEmpty());
        }

        @Test
        @DisplayName("should clear command step state")
        void shouldClearCommandStepState() {
            UUID commandId = UUID.randomUUID();
            UUID processId = UUID.randomUUID();

            state.storePendingCommandStep("bookFx", commandId, 30);
            state.storeCommandStepResponse("bookFx", CommandStepResponse.success(processId, "bookFx", "result"));

            assertTrue(state.isWaitingForCommandStep("bookFx"));
            assertTrue(state.getCommandStepResponse("bookFx").isPresent());

            state.clearCommandStepState("bookFx");

            assertFalse(state.isWaitingForCommandStep("bookFx"));
            assertTrue(state.getCommandStepResponse("bookFx").isEmpty());
        }

        @Test
        @DisplayName("should detect timed out command step")
        void shouldDetectTimedOutCommandStep() {
            // Create with immediate timeout
            Instant now = Instant.now();
            Instant sentAt = now.minusSeconds(120);
            Instant timeoutAt = now.minusSeconds(60);
            PendingCommandStep pending = new PendingCommandStep("bookFx", UUID.randomUUID(), sentAt, timeoutAt);
            state.getPendingCommandSteps().put("bookFx", pending);

            assertTrue(state.isCommandStepTimedOut("bookFx"));
        }

        @Test
        @DisplayName("should not detect non-timed out command step")
        void shouldNotDetectNonTimedOutCommandStep() {
            state.storePendingCommandStep("bookFx", UUID.randomUUID(), 300);

            assertFalse(state.isCommandStepTimedOut("bookFx"));
        }

        @Test
        @DisplayName("should return false for timeout check on non-existent step")
        void shouldReturnFalseForTimeoutCheckOnNonExistentStep() {
            assertFalse(state.isCommandStepTimedOut("unknown"));
        }

        @Test
        @DisplayName("should handle null in setPendingCommandSteps")
        void shouldHandleNullInSetPendingCommandSteps() {
            state.setPendingCommandSteps(null);
            assertNotNull(state.getPendingCommandSteps());
            assertTrue(state.getPendingCommandSteps().isEmpty());
        }

        @Test
        @DisplayName("should handle null in setCommandStepResponses")
        void shouldHandleNullInSetCommandStepResponses() {
            state.setCommandStepResponses(null);
            assertNotNull(state.getCommandStepResponses());
            assertTrue(state.getCommandStepResponses().isEmpty());
        }
    }

    // ========== Error Tracking Additional Tests ==========

    @Test
    @DisplayName("hasError should return true with only error code")
    void hasErrorShouldReturnTrueWithOnlyErrorCode() {
        state.setErrorCode("ERR");
        assertTrue(state.hasError());
    }

    @Test
    @DisplayName("hasError should return true with only error message")
    void hasErrorShouldReturnTrueWithOnlyErrorMessage() {
        state.setErrorMessage("Some error");
        assertTrue(state.hasError());
    }

    // ========== toMap Additional Tests ==========

    @Test
    @DisplayName("toMap should include command step data when present")
    void toMapShouldIncludeCommandStepDataWhenPresent() {
        UUID commandId = UUID.randomUUID();
        UUID processId = UUID.randomUUID();
        state.storePendingCommandStep("bookFx", commandId, 30);
        state.storeCommandStepResponse("submitPayment",
            CommandStepResponse.success(processId, "submitPayment", "result"));

        Map<String, Object> map = state.toMap();

        assertNotNull(map.get("pendingCommandSteps"));
        assertNotNull(map.get("commandStepResponses"));
    }

    @Test
    @DisplayName("toMap should not include command step data when empty")
    void toMapShouldNotIncludeCommandStepDataWhenEmpty() {
        Map<String, Object> map = state.toMap();

        assertNull(map.get("pendingCommandSteps"));
        assertNull(map.get("commandStepResponses"));
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
