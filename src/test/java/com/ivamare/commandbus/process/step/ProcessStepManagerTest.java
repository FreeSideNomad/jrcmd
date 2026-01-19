package com.ivamare.commandbus.process.step;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ivamare.commandbus.process.ProcessRepository;
import com.ivamare.commandbus.process.ProcessStatus;
import com.ivamare.commandbus.process.ratelimit.Bucket4jRateLimiter;
import com.ivamare.commandbus.process.step.exceptions.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("ProcessStepManager")
class ProcessStepManagerTest {

    @Mock
    private ProcessRepository processRepo;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private TransactionTemplate transactionTemplate;

    private TestProcessStepManager manager;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        manager = new TestProcessStepManager(processRepo, jdbcTemplate, transactionTemplate);

        // Default mock for transaction template - execute synchronously
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });

        doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("should create with default ObjectMapper")
        void shouldCreateWithDefaultObjectMapper() {
            TestProcessStepManager mgr = new TestProcessStepManager(processRepo, jdbcTemplate, transactionTemplate);
            assertNotNull(mgr);
        }

        @Test
        @DisplayName("should create with custom ObjectMapper")
        void shouldCreateWithCustomObjectMapper() {
            ObjectMapper customMapper = new ObjectMapper();
            TestProcessStepManager mgr = new TestProcessStepManager(
                processRepo, jdbcTemplate, transactionTemplate, customMapper);
            assertNotNull(mgr);
        }
    }

    @Nested
    @DisplayName("start()")
    class StartTests {

        @Test
        @DisplayName("should create process with default options")
        void shouldCreateProcessWithDefaultOptions() {
            when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

            TestState initialState = new TestState();
            initialState.setData("test-data");

            UUID processId = manager.start(initialState);

            assertNotNull(processId);
            verify(jdbcTemplate).update(anyString(),
                eq("test-domain"),
                any(UUID.class),
                eq("test-process"),
                eq("PENDING"),
                isNull(),
                anyString(),
                isNull(),
                isNull(),
                any(),
                any(),
                isNull(),
                isNull(),
                isNull()  // batch_id
            );
        }

        @Test
        @DisplayName("should execute immediately when option set")
        void shouldExecuteImmediatelyWhenOptionSet() {
            when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
            when(jdbcTemplate.queryForObject(anyString(), eq(String.class), anyString(), any(UUID.class)))
                .thenReturn("PENDING");
            when(processRepo.getStateJson(anyString(), any(UUID.class), any(JdbcTemplate.class)))
                .thenReturn("{\"data\":\"test\",\"stepHistory\":[],\"waitHistory\":[],\"sideEffects\":[]}");

            TestState initialState = new TestState();
            manager.setExecuteAction(state -> {
                // Simple workflow that completes
            });

            UUID processId = manager.start(initialState, StartOptions.builder()
                .executeImmediately(true)
                .build());

            assertNotNull(processId);
            // Verify status was updated to EXECUTING and then COMPLETED
            verify(processRepo, atLeastOnce()).updateStateAtomicStep(
                anyString(), any(UUID.class), any(),
                any(), eq("EXECUTING"),
                any(), any(), any(), any(), any(),
                any(JdbcTemplate.class)
            );
        }

        @Test
        @DisplayName("should defer execution when option set")
        void shouldDeferExecutionWhenOptionSet() {
            when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

            TestState initialState = new TestState();

            UUID processId = manager.start(initialState, StartOptions.builder()
                .executeImmediately(false)
                .build());

            assertNotNull(processId);
            // Should not have called updateStateAtomicStep for execution
            verify(processRepo, never()).updateStateAtomicStep(
                anyString(), any(UUID.class), any(),
                any(), eq("EXECUTING"),
                any(), any(), any(), any(), any(),
                any(JdbcTemplate.class)
            );
        }
    }

    @Nested
    @DisplayName("startBatch()")
    class StartBatchTests {

        @Test
        @DisplayName("should create multiple processes")
        void shouldCreateMultipleProcesses() {
            when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

            List<TestState> states = List.of(
                new TestState(),
                new TestState(),
                new TestState()
            );

            List<UUID> processIds = manager.startBatch(states);

            assertEquals(3, processIds.size());
            verify(jdbcTemplate, times(3)).update(anyString(), any(Object[].class));
        }

        @Test
        @DisplayName("should return empty list for empty input")
        void shouldReturnEmptyListForEmptyInput() {
            List<UUID> processIds = manager.startBatch(List.of());

            assertTrue(processIds.isEmpty());
            verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
        }

        @Test
        @DisplayName("should execute immediately when batch option set")
        void shouldExecuteImmediatelyWhenBatchOptionSet() {
            when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

            AtomicInteger executeCount = new AtomicInteger(0);
            manager.setExecuteAction(state -> executeCount.incrementAndGet());

            List<TestState> states = List.of(new TestState(), new TestState());

            manager.startBatch(states, BatchOptions.builder()
                .executeImmediately(true)
                .build());

            assertEquals(2, executeCount.get());
        }
    }

    @Nested
    @DisplayName("step()")
    class StepTests {

        @Test
        @DisplayName("should execute step and return result")
        void shouldExecuteStepAndReturnResult() {
            when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
            when(processRepo.getStateJson(anyString(), any(UUID.class), any(JdbcTemplate.class)))
                .thenReturn("{\"data\":\"test\",\"stepHistory\":[],\"waitHistory\":[],\"sideEffects\":[]}");

            AtomicBoolean stepExecuted = new AtomicBoolean(false);
            manager.setExecuteAction(state -> {
                String result = manager.testStep("myStep", s -> {
                    stepExecuted.set(true);
                    return "step-result";
                });
                assertEquals("step-result", result);
            });

            TestState initialState = new TestState();
            manager.start(initialState, StartOptions.builder().executeImmediately(true).build());

            assertTrue(stepExecuted.get());
        }

        @Test
        @DisplayName("should replay completed step")
        void shouldReplayCompletedStep() {
            // For replay testing, we need to set up the state with completed step
            // and then call resume() which loads from repository
            String stateJson = """
                {
                    "data": "test",
                    "stepHistory": [{
                        "name": "myStep",
                        "status": "COMPLETED",
                        "attemptCount": 1,
                        "maxRetries": 3,
                        "startedAt": "2024-01-01T00:00:00Z",
                        "completedAt": "2024-01-01T00:00:01Z",
                        "requestJson": "{}",
                        "responseJson": "\\"cached-result\\""
                    }],
                    "waitHistory": [],
                    "sideEffects": []
                }
                """;

            when(processRepo.getStateJson(anyString(), any(UUID.class), any(JdbcTemplate.class)))
                .thenReturn(stateJson);

            AtomicBoolean actionCalled = new AtomicBoolean(false);
            manager.setExecuteAction(state -> {
                String result = manager.testStep("myStep", s -> {
                    actionCalled.set(true);
                    return "new-result";
                });
                // Should return cached result, not execute action
                assertEquals("cached-result", result);
            });

            UUID processId = UUID.randomUUID();
            manager.resume(processId);

            assertFalse(actionCalled.get(), "Action should not be called during replay");
        }

        @Test
        @DisplayName("should throw when called outside execute context")
        void shouldThrowWhenCalledOutsideExecuteContext() {
            assertThrows(IllegalStateException.class, () ->
                manager.testStep("step", s -> "result")
            );
        }

        @Test
        @DisplayName("should register and execute compensation on business failure")
        void shouldRegisterAndExecuteCompensationOnBusinessFailure() {
            when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
            when(processRepo.getStateJson(anyString(), any(UUID.class), any(JdbcTemplate.class)))
                .thenReturn("{\"data\":\"test\",\"stepHistory\":[],\"waitHistory\":[],\"sideEffects\":[]}");

            AtomicBoolean compensationExecuted = new AtomicBoolean(false);
            manager.setExecuteAction(state -> {
                // First step succeeds
                manager.testStep("step1", s -> {
                    return "result1";
                }, 3, s -> compensationExecuted.set(true));

                // Second step throws business exception
                manager.testStep("step2", s -> {
                    throw new StepBusinessRuleException("Business rule violated");
                });
            });

            TestState initialState = new TestState();

            // The start() method catches exceptions and moves to TSQ/COMPENSATED,
            // so it won't throw. Verify the status was set to COMPENSATED instead.
            manager.start(initialState, StartOptions.builder().executeImmediately(true).build());

            assertTrue(compensationExecuted.get(), "Compensation should be executed for business failures");
            verify(processRepo).updateStateAtomicStep(
                anyString(), any(UUID.class), any(),
                any(), eq("COMPENSATED"),
                any(), any(), any(), any(), any(),
                any(JdbcTemplate.class)
            );
        }
    }

    @Nested
    @DisplayName("step() crash recovery")
    class CrashRecoveryTests {

        @Test
        @DisplayName("should move to TSQ when crash recovery detects exhausted retries")
        void shouldMoveToTsqWhenCrashRecoveryDetectsExhaustedRetries() {
            // State with STARTED step (incomplete due to crash) and attemptCount=2, maxRetries=1
            // maxRetries=1 means: initial attempt + 1 retry = 2 attempts total
            // attemptCount=2 means both attempts used, so retries are exhausted
            String stateJson = """
                {
                    "data": "test",
                    "stepHistory": [{
                        "name": "myStep",
                        "status": "STARTED",
                        "attemptCount": 2,
                        "maxRetries": 1,
                        "startedAt": "2024-01-01T00:00:00Z"
                    }],
                    "waitHistory": [],
                    "sideEffects": []
                }
                """;

            when(processRepo.getStateJson(anyString(), any(UUID.class), any(JdbcTemplate.class)))
                .thenReturn(stateJson);

            AtomicBoolean actionCalled = new AtomicBoolean(false);
            manager.setExecuteAction(state -> {
                // Step has maxRetries=1 (2 attempts total), attemptCount=2 (both used)
                // Should NOT execute action, should fail to TSQ
                manager.testStep("myStep", s -> {
                    actionCalled.set(true);
                    return "result";
                }, 1, null);  // maxRetries=1
            });

            UUID processId = UUID.randomUUID();
            manager.resume(processId);

            assertFalse(actionCalled.get(), "Action should not be called when retries exhausted during crash recovery");
            // Verify process moved to TSQ
            verify(processRepo).updateStateAtomicStep(
                anyString(), any(UUID.class), any(),
                any(), eq("WAITING_FOR_TSQ"),
                eq("RETRIES_EXHAUSTED_CRASH_RECOVERY"), any(), any(), any(), any(),
                any(JdbcTemplate.class)
            );
        }

        @Test
        @DisplayName("should re-execute step when crash recovery still has retries")
        void shouldReExecuteWhenCrashRecoveryHasRetries() {
            // State with STARTED step (incomplete due to crash) and attemptCount=1, maxRetries=2
            String stateJson = """
                {
                    "data": "test",
                    "stepHistory": [{
                        "name": "myStep",
                        "status": "STARTED",
                        "attemptCount": 1,
                        "maxRetries": 2,
                        "startedAt": "2024-01-01T00:00:00Z"
                    }],
                    "waitHistory": [],
                    "sideEffects": []
                }
                """;

            when(processRepo.getStateJson(anyString(), any(UUID.class), any(JdbcTemplate.class)))
                .thenReturn(stateJson);
            when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

            AtomicInteger executionCount = new AtomicInteger(0);
            manager.setExecuteAction(state -> {
                // Step has maxRetries=2 (3 attempts), attemptCount=1 (still has retries)
                // Should execute action
                manager.testStep("myStep", s -> {
                    executionCount.incrementAndGet();
                    return "result";
                }, 2, null);  // maxRetries=2
            });

            UUID processId = UUID.randomUUID();
            manager.resume(processId);

            assertEquals(1, executionCount.get(), "Action should be called during crash recovery with remaining retries");
            // Process should complete, not go to TSQ
            verify(processRepo).updateStateAtomicStep(
                anyString(), any(UUID.class), any(),
                any(), eq("COMPLETED"),
                any(), any(), any(), any(), any(),
                any(JdbcTemplate.class)
            );
        }

        @Test
        @DisplayName("should keep same attemptCount when re-executing after crash")
        void shouldKeepSameAttemptCountWhenReExecutingAfterCrash() {
            // State with STARTED step, attemptCount=2, maxRetries=3
            String stateJson = """
                {
                    "data": "test",
                    "stepHistory": [{
                        "name": "myStep",
                        "status": "STARTED",
                        "attemptCount": 2,
                        "maxRetries": 3,
                        "startedAt": "2024-01-01T00:00:00Z"
                    }],
                    "waitHistory": [],
                    "sideEffects": []
                }
                """;

            when(processRepo.getStateJson(anyString(), any(UUID.class), any(JdbcTemplate.class)))
                .thenReturn(stateJson);
            when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

            manager.setExecuteAction(state -> {
                manager.testStep("myStep", s -> "result", 3, null);
            });

            UUID processId = UUID.randomUUID();
            manager.resume(processId);

            // Verify that step was recorded with attemptCount=2 (not incremented to 3)
            ArgumentCaptor<String> stateCaptor = ArgumentCaptor.forClass(String.class);
            verify(processRepo, atLeastOnce()).updateState(
                anyString(), any(UUID.class), stateCaptor.capture(), any(JdbcTemplate.class));

            // Last persisted state should have attemptCount=2 for the completed step
            String lastState = stateCaptor.getValue();
            assertTrue(lastState.contains("\"attemptCount\":2"),
                "attemptCount should remain 2 during crash recovery, not increment");
        }
    }

    @Nested
    @DisplayName("wait()")
    class WaitTests {

        @Test
        @DisplayName("should continue when condition is met immediately")
        void shouldContinueWhenConditionIsMetImmediately() {
            when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
            when(processRepo.getStateJson(anyString(), any(UUID.class), any(JdbcTemplate.class)))
                .thenReturn("{\"data\":\"test\",\"stepHistory\":[],\"waitHistory\":[],\"sideEffects\":[]}");

            AtomicBoolean afterWait = new AtomicBoolean(false);
            manager.setExecuteAction(state -> {
                manager.testWait("waitCondition", () -> true);
                afterWait.set(true);
            });

            TestState initialState = new TestState();
            manager.start(initialState, StartOptions.builder().executeImmediately(true).build());

            assertTrue(afterWait.get(), "Should continue after wait when condition is met");
        }

        @Test
        @DisplayName("should pause when condition is not met")
        void shouldPauseWhenConditionIsNotMet() {
            when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
            when(processRepo.getStateJson(anyString(), any(UUID.class), any(JdbcTemplate.class)))
                .thenReturn("{\"data\":\"test\",\"stepHistory\":[],\"waitHistory\":[],\"sideEffects\":[]}");

            AtomicBoolean afterWait = new AtomicBoolean(false);
            manager.setExecuteAction(state -> {
                manager.testWait("waitCondition", () -> false);
                afterWait.set(true);
            });

            TestState initialState = new TestState();
            // Should not throw - just pause
            manager.start(initialState, StartOptions.builder().executeImmediately(true).build());

            assertFalse(afterWait.get(), "Should not continue after wait when condition is not met");
            // Verify status was set to WAITING_FOR_ASYNC
            verify(processRepo).updateStateAtomicStep(
                anyString(), any(UUID.class), any(),
                any(), eq("WAITING_FOR_ASYNC"),
                any(), any(), any(), any(), eq("waitCondition"),
                any(JdbcTemplate.class)
            );
        }

        @Test
        @DisplayName("should replay satisfied wait")
        void shouldReplaySatisfiedWait() {
            // For replay testing, use resume() which loads from repository
            // WaitRecord fields: name, satisfied, recordedAt, timeout, timeoutAt
            String stateJson = """
                {
                    "data": "test",
                    "stepHistory": [],
                    "waitHistory": [{
                        "name": "waitCondition",
                        "satisfied": true,
                        "recordedAt": "2024-01-01T00:00:00Z",
                        "timeout": null,
                        "timeoutAt": null
                    }],
                    "sideEffects": []
                }
                """;

            when(processRepo.getStateJson(anyString(), any(UUID.class), any(JdbcTemplate.class)))
                .thenReturn(stateJson);

            AtomicBoolean conditionEvaluated = new AtomicBoolean(false);
            manager.setExecuteAction(state -> {
                manager.testWait("waitCondition", () -> {
                    conditionEvaluated.set(true);
                    return false;  // Would fail if evaluated
                });
            });

            UUID processId = UUID.randomUUID();
            manager.resume(processId);

            assertFalse(conditionEvaluated.get(), "Condition should not be evaluated during replay");
        }

        @Test
        @DisplayName("should throw when called outside execute context")
        void shouldThrowWhenCalledOutsideExecuteContext() {
            assertThrows(IllegalStateException.class, () ->
                manager.testWait("wait", () -> true)
            );
        }
    }

    @Nested
    @DisplayName("sideEffect()")
    class SideEffectTests {

        @Test
        @DisplayName("should execute and cache side effect")
        void shouldExecuteAndCacheSideEffect() {
            when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
            when(processRepo.getStateJson(anyString(), any(UUID.class), any(JdbcTemplate.class)))
                .thenReturn("{\"data\":\"test\",\"stepHistory\":[],\"waitHistory\":[],\"sideEffects\":[]}");

            AtomicInteger executionCount = new AtomicInteger(0);
            manager.setExecuteAction(state -> {
                String result = manager.testSideEffect("randomId", () -> {
                    executionCount.incrementAndGet();
                    return "generated-value";
                });
                assertEquals("generated-value", result);
            });

            TestState initialState = new TestState();
            manager.start(initialState, StartOptions.builder().executeImmediately(true).build());

            assertEquals(1, executionCount.get());
        }

        @Test
        @DisplayName("should replay cached side effect")
        void shouldReplayCachedSideEffect() {
            // For replay testing, use resume() which loads from repository
            String stateJson = """
                {
                    "data": "test",
                    "stepHistory": [],
                    "waitHistory": [],
                    "sideEffects": [{
                        "name": "randomId",
                        "valueJson": "\\"cached-value\\"",
                        "recordedAt": "2024-01-01T00:00:00Z"
                    }]
                }
                """;

            when(processRepo.getStateJson(anyString(), any(UUID.class), any(JdbcTemplate.class)))
                .thenReturn(stateJson);

            AtomicBoolean operationCalled = new AtomicBoolean(false);
            manager.setExecuteAction(state -> {
                String result = manager.testSideEffect("randomId", () -> {
                    operationCalled.set(true);
                    return "new-value";
                });
                assertEquals("cached-value", result);
            });

            UUID processId = UUID.randomUUID();
            manager.resume(processId);

            assertFalse(operationCalled.get(), "Operation should not be called during replay");
        }

        @Test
        @DisplayName("should throw when called outside execute context")
        void shouldThrowWhenCalledOutsideExecuteContext() {
            assertThrows(IllegalStateException.class, () ->
                manager.testSideEffect("effect", () -> "value")
            );
        }
    }

    @Nested
    @DisplayName("processAsyncResponse()")
    class ProcessAsyncResponseTests {

        @Test
        @DisplayName("should update state and resume if waiting for async")
        void shouldUpdateStateAndResumeIfWaitingForAsync() {
            // WaitRecord fields: name, satisfied, recordedAt, timeout, timeoutAt
            String stateJson = """
                {
                    "data": "test",
                    "stepHistory": [],
                    "waitHistory": [{
                        "name": "awaitL1",
                        "satisfied": false,
                        "recordedAt": "2024-01-01T00:00:00Z",
                        "timeout": null,
                        "timeoutAt": null
                    }],
                    "sideEffects": []
                }
                """;

            when(processRepo.getStateJson(anyString(), any(UUID.class), any(JdbcTemplate.class)))
                .thenReturn(stateJson);
            when(jdbcTemplate.queryForObject(anyString(), eq(String.class), anyString(), any(UUID.class)))
                .thenReturn("WAITING_FOR_ASYNC");

            AtomicBoolean stateUpdated = new AtomicBoolean(false);
            UUID processId = UUID.randomUUID();

            manager.processAsyncResponse(processId, state -> {
                state.setData("updated-via-async");
                stateUpdated.set(true);
            });

            assertTrue(stateUpdated.get());
            verify(processRepo).updateState(anyString(), eq(processId), anyString(), any(JdbcTemplate.class));
        }

        @Test
        @DisplayName("should not resume if not waiting for async")
        void shouldNotResumeIfNotWaitingForAsync() {
            // Include all required fields for TestState deserialization
            String stateJson = """
                {
                    "data": "test",
                    "stepHistory": [],
                    "waitHistory": [],
                    "sideEffects": []
                }
                """;

            when(processRepo.getStateJson(anyString(), any(UUID.class), any(JdbcTemplate.class)))
                .thenReturn(stateJson);
            when(jdbcTemplate.queryForObject(anyString(), eq(String.class), anyString(), any(UUID.class)))
                .thenReturn("COMPLETED");

            UUID processId = UUID.randomUUID();

            manager.processAsyncResponse(processId, state -> state.setData("updated"));

            // Should not call updateStateAtomicStep for EXECUTING status
            verify(processRepo, never()).updateStateAtomicStep(
                anyString(), any(UUID.class), any(),
                any(), eq("EXECUTING"),
                any(), any(), any(), any(), any(),
                any(JdbcTemplate.class)
            );
        }
    }

    @Nested
    @DisplayName("TSQ Operations")
    class TsqOperationsTests {

        @Test
        @DisplayName("retry() should clear error and resume")
        void retryShouldClearErrorAndResume() {
            String stateJson = """
                {
                    "data": "test",
                    "stepHistory": [],
                    "waitHistory": [],
                    "sideEffects": [],
                    "errorCode": "SOME_ERROR",
                    "errorMessage": "Some error occurred"
                }
                """;

            when(processRepo.getStateJson(anyString(), any(UUID.class), any(JdbcTemplate.class)))
                .thenReturn(stateJson);

            UUID processId = UUID.randomUUID();
            manager.retry(processId);

            verify(processRepo).updateStateAtomicStep(
                anyString(), eq(processId), any(),
                any(), eq("PENDING"),
                any(), any(), any(), any(), any(),
                any(JdbcTemplate.class)
            );
        }

        @Test
        @DisplayName("cancelOverride() should cancel without compensations")
        void cancelOverrideShouldCancelWithoutCompensations() {
            UUID processId = UUID.randomUUID();

            // State is now always loaded for hooks (onProcessCanceled)
            when(processRepo.getStateJson(anyString(), eq(processId), any(JdbcTemplate.class)))
                .thenReturn("{\"stepHistory\":[],\"waitHistory\":[],\"sideEffects\":[]}");

            manager.cancelOverride(processId, false);

            verify(processRepo).updateStateAtomicStep(
                anyString(), eq(processId), any(),
                any(), eq("CANCELED"),
                any(), any(), any(), any(), any(),
                any(JdbcTemplate.class)
            );
        }

        @Test
        @DisplayName("cancelOverride() should run compensations when requested")
        void cancelOverrideShouldRunCompensationsWhenRequested() {
            String stateJson = """
                {
                    "data": "test",
                    "stepHistory": [{
                        "name": "step1",
                        "status": "COMPLETED",
                        "attemptCount": 1,
                        "maxRetries": 3,
                        "startedAt": "2024-01-01T00:00:00Z",
                        "completedAt": "2024-01-01T00:00:01Z"
                    }],
                    "waitHistory": [],
                    "sideEffects": []
                }
                """;

            when(processRepo.getStateJson(anyString(), any(UUID.class), any(JdbcTemplate.class)))
                .thenReturn(stateJson);

            UUID processId = UUID.randomUUID();

            manager.cancelOverride(processId, true);

            verify(processRepo).updateStateAtomicStep(
                anyString(), eq(processId), any(),
                any(), eq("CANCELED"),
                any(), any(), any(), any(), any(),
                any(JdbcTemplate.class)
            );
        }

        @Test
        @DisplayName("completeOverride() should complete with state overrides")
        void completeOverrideShouldCompleteWithStateOverrides() {
            UUID processId = UUID.randomUUID();
            Map<String, Object> overrides = Map.of("completedManually", true, "overrideReason", "TSQ action");

            // State is now loaded after update for hooks (onProcessCompleted)
            when(processRepo.getStateJson(anyString(), eq(processId), any(JdbcTemplate.class)))
                .thenReturn("{\"completedManually\":true,\"overrideReason\":\"TSQ action\",\"stepHistory\":[],\"waitHistory\":[],\"sideEffects\":[]}");

            manager.completeOverride(processId, overrides);

            ArgumentCaptor<String> patchCaptor = ArgumentCaptor.forClass(String.class);
            verify(processRepo).updateStateAtomicStep(
                anyString(), eq(processId), patchCaptor.capture(),
                any(), eq("COMPLETED"),
                any(), any(), any(), any(), any(),
                any(JdbcTemplate.class)
            );

            String patch = patchCaptor.getValue();
            assertNotNull(patch);
            assertTrue(patch.contains("completedManually"));
        }

        @Test
        @DisplayName("completeOverride() should complete without state overrides")
        void completeOverrideShouldCompleteWithoutStateOverrides() {
            UUID processId = UUID.randomUUID();

            // State is now loaded after update for hooks (onProcessCompleted)
            when(processRepo.getStateJson(anyString(), eq(processId), any(JdbcTemplate.class)))
                .thenReturn("{\"stepHistory\":[],\"waitHistory\":[],\"sideEffects\":[]}");

            manager.completeOverride(processId, null);

            verify(processRepo).updateStateAtomicStep(
                anyString(), eq(processId), isNull(),
                any(), eq("COMPLETED"),
                any(), any(), any(), any(), any(),
                any(JdbcTemplate.class)
            );
        }
    }

    @Nested
    @DisplayName("classifyException()")
    class ClassifyExceptionTests {

        @Test
        @DisplayName("should classify StepBusinessRuleException as BUSINESS")
        void shouldClassifyBusinessRuleExceptionAsBusiness() {
            assertEquals(ExceptionType.BUSINESS,
                manager.testClassifyException(new StepBusinessRuleException("Business rule violated")));
        }

        @Test
        @DisplayName("should classify other exceptions as PERMANENT by default")
        void shouldClassifyOtherExceptionsAsPermanent() {
            assertEquals(ExceptionType.PERMANENT,
                manager.testClassifyException(new RuntimeException("Something failed")));
        }
    }

    @Nested
    @DisplayName("calculateBackoff()")
    class CalculateBackoffTests {

        @Test
        @DisplayName("should calculate exponential backoff")
        void shouldCalculateExponentialBackoff() {
            Duration base = Duration.ofSeconds(1);

            Duration attempt1 = manager.testCalculateBackoff(1, base);
            Duration attempt2 = manager.testCalculateBackoff(2, base);
            Duration attempt3 = manager.testCalculateBackoff(3, base);

            // Allow for jitter (up to 500ms)
            assertTrue(attempt1.toMillis() >= 1000 && attempt1.toMillis() <= 1500);
            assertTrue(attempt2.toMillis() >= 2000 && attempt2.toMillis() <= 2500);
            assertTrue(attempt3.toMillis() >= 4000 && attempt3.toMillis() <= 4500);
        }

        @Test
        @DisplayName("should cap backoff at 5 minutes")
        void shouldCapBackoffAtFiveMinutes() {
            Duration base = Duration.ofMinutes(1);

            Duration attempt10 = manager.testCalculateBackoff(10, base);

            assertTrue(attempt10.toMinutes() <= 5);
        }
    }

    @Nested
    @DisplayName("Abstract Method Defaults")
    class AbstractMethodDefaultsTests {

        @Test
        @DisplayName("getDefaultWaitTimeout() should return 1 hour")
        void getDefaultWaitTimeoutShouldReturnOneHour() {
            assertEquals(Duration.ofHours(1), manager.getDefaultWaitTimeout());
        }

        @Test
        @DisplayName("getDeadlineAction() should return TSQ")
        void getDeadlineActionShouldReturnTsq() {
            assertEquals(DeadlineAction.TSQ, manager.getDeadlineAction());
        }
    }

    @Nested
    @DisplayName("Serialization")
    class SerializationTests {

        @Test
        @DisplayName("should serialize and deserialize state")
        void shouldSerializeAndDeserializeState() throws JsonProcessingException {
            TestState state = new TestState();
            state.setData("test-data");
            state.setProcessDeadline(Instant.parse("2024-12-31T23:59:59Z"));

            String json = manager.testSerializeState(state);
            assertNotNull(json);
            assertTrue(json.contains("test-data"));

            // Verify it's valid JSON
            objectMapper.readTree(json);
        }

        @Test
        @DisplayName("should serialize null result as null")
        void shouldSerializeNullResultAsNull() {
            assertNull(manager.testSerializeResult(null));
        }

        @Test
        @DisplayName("should deserialize null result as null")
        void shouldDeserializeNullResultAsNull() {
            assertNull(manager.testDeserializeResult(null));
        }
    }

    @Nested
    @DisplayName("Exception Handling")
    class ExceptionHandlingTests {

        @Test
        @DisplayName("should move to TSQ on unexpected error")
        void shouldMoveToTsqOnUnexpectedError() {
            when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
            when(processRepo.getStateJson(anyString(), any(UUID.class), any(JdbcTemplate.class)))
                .thenReturn("{\"data\":\"test\",\"stepHistory\":[],\"waitHistory\":[],\"sideEffects\":[]}");

            manager.setExecuteAction(state -> {
                throw new RuntimeException("Unexpected error");
            });

            TestState initialState = new TestState();
            // Should not throw - error is caught and process moved to TSQ
            // RuntimeException is classified as PERMANENT by default
            manager.start(initialState, StartOptions.builder().executeImmediately(true).build());

            verify(processRepo).updateStateAtomicStep(
                anyString(), any(UUID.class), any(),
                any(), eq("WAITING_FOR_TSQ"),
                eq("PERMANENT_ERROR"), anyString(), any(), any(), any(),
                any(JdbcTemplate.class)
            );
        }

        @Test
        @DisplayName("should move to TSQ on step failure")
        void shouldMoveToTsqOnStepFailure() {
            when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
            when(processRepo.getStateJson(anyString(), any(UUID.class), any(JdbcTemplate.class)))
                .thenReturn("{\"data\":\"test\",\"stepHistory\":[],\"waitHistory\":[],\"sideEffects\":[]}");

            manager.setExecuteAction(state -> {
                throw new StepFailedException("failedStep", "ERROR_CODE", "Step failed");
            });

            TestState initialState = new TestState();
            manager.start(initialState, StartOptions.builder().executeImmediately(true).build());

            verify(processRepo).updateStateAtomicStep(
                anyString(), any(UUID.class), any(),
                any(), eq("WAITING_FOR_TSQ"),
                eq("ERROR_CODE"), anyString(), any(), any(), any(),
                any(JdbcTemplate.class)
            );
        }
    }

    @Nested
    @DisplayName("Rate Limiting")
    class RateLimitingTests {

        private Bucket4jRateLimiter rateLimiterMock;
        private TestProcessStepManager managerWithRateLimiter;

        @BeforeEach
        void setUpRateLimiter() {
            rateLimiterMock = mock(Bucket4jRateLimiter.class);
            managerWithRateLimiter = new TestProcessStepManager(
                processRepo, jdbcTemplate, transactionTemplate, rateLimiterMock);
        }

        @Test
        @DisplayName("should call rate limiter when rateLimitKey is configured")
        void shouldCallRateLimiterWhenConfigured() {
            when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
            when(processRepo.getStateJson(anyString(), any(UUID.class), any(JdbcTemplate.class)))
                .thenReturn("{\"data\":\"test\",\"stepHistory\":[],\"waitHistory\":[],\"sideEffects\":[]}");
            when(rateLimiterMock.acquire(anyString(), any(Duration.class))).thenReturn(true);

            AtomicBoolean stepExecuted = new AtomicBoolean(false);
            managerWithRateLimiter.setExecuteAction(state -> {
                StepOptions<TestState, String> options = StepOptions.<TestState, String>builder()
                    .action(s -> {
                        stepExecuted.set(true);
                        return "result";
                    })
                    .rateLimitKey("api-calls")
                    .rateLimitTimeout(Duration.ofSeconds(10))
                    .build();
                managerWithRateLimiter.testStepWithOptions("rateLimitedStep", options);
            });

            TestState initialState = new TestState();
            managerWithRateLimiter.start(initialState, StartOptions.builder().executeImmediately(true).build());

            assertTrue(stepExecuted.get());
            verify(rateLimiterMock).acquire("api-calls", Duration.ofSeconds(10));
        }

        @Test
        @DisplayName("should schedule retry when rate limit exceeded and retries available")
        void shouldScheduleRetryWhenRateLimitExceeded() {
            when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
            when(processRepo.getStateJson(anyString(), any(UUID.class), any(JdbcTemplate.class)))
                .thenReturn("{\"data\":\"test\",\"stepHistory\":[],\"waitHistory\":[],\"sideEffects\":[]}");
            when(rateLimiterMock.acquire(anyString(), any(Duration.class))).thenReturn(false);

            AtomicBoolean stepExecuted = new AtomicBoolean(false);
            managerWithRateLimiter.setExecuteAction(state -> {
                StepOptions<TestState, String> options = StepOptions.<TestState, String>builder()
                    .action(s -> {
                        stepExecuted.set(true);
                        return "result";
                    })
                    .maxRetries(3)  // Enable retries
                    .rateLimitKey("api-calls")
                    .rateLimitTimeout(Duration.ofSeconds(10))
                    .build();
                managerWithRateLimiter.testStepWithOptions("rateLimitedStep", options);
            });

            TestState initialState = new TestState();
            managerWithRateLimiter.start(initialState, StartOptions.builder().executeImmediately(true).build());

            assertFalse(stepExecuted.get(), "Step action should not execute when rate limited");
            // Rate limit exceeded is TRANSIENT - should schedule retry
            verify(processRepo).updateStateAtomicStep(
                anyString(), any(UUID.class), any(),
                any(), eq("WAITING_FOR_RETRY"),
                isNull(), isNull(), any(Instant.class), isNull(), isNull(),
                any(JdbcTemplate.class)
            );
        }

        @Test
        @DisplayName("should move to TSQ when rate limit exceeded with no retries configured")
        void shouldMoveToTsqWhenRateLimitExceededNoRetries() {
            when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
            when(processRepo.getStateJson(anyString(), any(UUID.class), any(JdbcTemplate.class)))
                .thenReturn("{\"data\":\"test\",\"stepHistory\":[],\"waitHistory\":[],\"sideEffects\":[]}");
            when(rateLimiterMock.acquire(anyString(), any(Duration.class))).thenReturn(false);

            AtomicBoolean stepExecuted = new AtomicBoolean(false);
            managerWithRateLimiter.setExecuteAction(state -> {
                StepOptions<TestState, String> options = StepOptions.<TestState, String>builder()
                    .action(s -> {
                        stepExecuted.set(true);
                        return "result";
                    })
                    // Default maxRetries=1 means no retries
                    .rateLimitKey("api-calls")
                    .rateLimitTimeout(Duration.ofSeconds(10))
                    .build();
                managerWithRateLimiter.testStepWithOptions("rateLimitedStep", options);
            });

            TestState initialState = new TestState();
            managerWithRateLimiter.start(initialState, StartOptions.builder().executeImmediately(true).build());

            assertFalse(stepExecuted.get(), "Step action should not execute when rate limited");
            // With no retries configured, should move to TSQ
            verify(processRepo).updateStateAtomicStep(
                anyString(), any(UUID.class), any(),
                any(), eq("WAITING_FOR_TSQ"),
                eq("RETRIES_EXHAUSTED"), anyString(), isNull(), isNull(), isNull(),
                any(JdbcTemplate.class)
            );
        }

        @Test
        @DisplayName("should skip rate limiting when rateLimiter is null")
        void shouldSkipRateLimitingWhenRateLimiterIsNull() {
            when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
            when(processRepo.getStateJson(anyString(), any(UUID.class), any(JdbcTemplate.class)))
                .thenReturn("{\"data\":\"test\",\"stepHistory\":[],\"waitHistory\":[],\"sideEffects\":[]}");

            AtomicBoolean stepExecuted = new AtomicBoolean(false);
            // Use manager without rate limiter (from parent setUp)
            manager.setExecuteAction(state -> {
                StepOptions<TestState, String> options = StepOptions.<TestState, String>builder()
                    .action(s -> {
                        stepExecuted.set(true);
                        return "result";
                    })
                    .rateLimitKey("api-calls")
                    .rateLimitTimeout(Duration.ofSeconds(10))
                    .build();
                manager.testStepWithOptions("rateLimitedStep", options);
            });

            TestState initialState = new TestState();
            manager.start(initialState, StartOptions.builder().executeImmediately(true).build());

            assertTrue(stepExecuted.get(), "Step should execute when rateLimiter is null");
        }

        @Test
        @DisplayName("should skip rate limiting when rateLimitKey is not configured")
        void shouldSkipRateLimitingWhenKeyNotConfigured() {
            when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
            when(processRepo.getStateJson(anyString(), any(UUID.class), any(JdbcTemplate.class)))
                .thenReturn("{\"data\":\"test\",\"stepHistory\":[],\"waitHistory\":[],\"sideEffects\":[]}");

            AtomicBoolean stepExecuted = new AtomicBoolean(false);
            managerWithRateLimiter.setExecuteAction(state -> {
                // Using step without rate limit options
                String result = managerWithRateLimiter.testStep("normalStep", s -> {
                    stepExecuted.set(true);
                    return "result";
                });
                assertEquals("result", result);
            });

            TestState initialState = new TestState();
            managerWithRateLimiter.start(initialState, StartOptions.builder().executeImmediately(true).build());

            assertTrue(stepExecuted.get());
            // Rate limiter should not be called
            verify(rateLimiterMock, never()).acquire(anyString(), any(Duration.class));
        }
    }

    @Nested
    @DisplayName("resume()")
    class ResumeTests {

        @Test
        @DisplayName("should load state and execute")
        void shouldLoadStateAndExecute() {
            String stateJson = "{\"data\":\"resumed-data\",\"stepHistory\":[],\"waitHistory\":[],\"sideEffects\":[]}";

            when(processRepo.getStateJson(anyString(), any(UUID.class), any(JdbcTemplate.class)))
                .thenReturn(stateJson);

            AtomicBoolean executed = new AtomicBoolean(false);
            manager.setExecuteAction(state -> {
                assertEquals("resumed-data", state.getData());
                executed.set(true);
            });

            UUID processId = UUID.randomUUID();
            manager.resume(processId);

            assertTrue(executed.get());
        }
    }

    @Nested
    @DisplayName("Audit Logging")
    class AuditLogging {

        @Test
        @DisplayName("logStepSuccess should handle null responseJson")
        void logStepSuccessWithNullResponse() {
            UUID processId = UUID.randomUUID();
            manager.testLogStepSuccess(processId, "testStep", Instant.now(), null);
            verify(processRepo).logStep(eq("test-domain"), eq(processId), any(), any());
        }

        @Test
        @DisplayName("logStepSuccess should handle non-null responseJson")
        void logStepSuccessWithResponse() {
            UUID processId = UUID.randomUUID();
            manager.testLogStepSuccess(processId, "testStep", Instant.now(), "{\"result\":\"ok\"}");
            verify(processRepo).logStep(eq("test-domain"), eq(processId), any(), any());
        }

        @Test
        @DisplayName("logStepFailure should handle null errorMessage")
        void logStepFailureWithNullMessage() {
            UUID processId = UUID.randomUUID();
            manager.testLogStepFailure(processId, "testStep", Instant.now(), "ERROR_CODE", null);
            verify(processRepo).logStep(eq("test-domain"), eq(processId), any(), any());
        }

        @Test
        @DisplayName("logStepFailure should handle non-null errorMessage")
        void logStepFailureWithMessage() {
            UUID processId = UUID.randomUUID();
            manager.testLogStepFailure(processId, "testStep", Instant.now(), "ERROR_CODE", "Error message");
            verify(processRepo).logStep(eq("test-domain"), eq(processId), any(), any());
        }

        @Test
        @DisplayName("logStepRetry should handle null errorMessage")
        void logStepRetryWithNullMessage() {
            UUID processId = UUID.randomUUID();
            manager.testLogStepRetry(processId, "testStep", Instant.now(), 1, Instant.now().plusSeconds(60), null);
            verify(processRepo).logStep(eq("test-domain"), eq(processId), any(), any());
        }

        @Test
        @DisplayName("logStepRetry should handle non-null errorMessage")
        void logStepRetryWithMessage() {
            UUID processId = UUID.randomUUID();
            manager.testLogStepRetry(processId, "testStep", Instant.now(), 1, Instant.now().plusSeconds(60), "Retry reason");
            verify(processRepo).logStep(eq("test-domain"), eq(processId), any(), any());
        }

        @Test
        @DisplayName("logCompensation should handle success")
        void logCompensationSuccess() {
            UUID processId = UUID.randomUUID();
            manager.testLogCompensation(processId, "testStep", Instant.now(), true, null);
            verify(processRepo).logStep(eq("test-domain"), eq(processId), any(), any());
        }

        @Test
        @DisplayName("logCompensation should handle failure with error")
        void logCompensationFailureWithError() {
            UUID processId = UUID.randomUUID();
            Exception error = new RuntimeException("Compensation failed");
            manager.testLogCompensation(processId, "testStep", Instant.now(), false, error);
            verify(processRepo).logStep(eq("test-domain"), eq(processId), any(), any());
        }

        @Test
        @DisplayName("logCompensation should handle failure with null message exception")
        void logCompensationFailureWithNullMessageException() {
            UUID processId = UUID.randomUUID();
            Exception error = new RuntimeException((String) null);
            manager.testLogCompensation(processId, "testStep", Instant.now(), false, error);
            verify(processRepo).logStep(eq("test-domain"), eq(processId), any(), any());
        }

        @Test
        @DisplayName("logAsyncResponse should handle null waitName")
        void logAsyncResponseWithNullWaitName() {
            UUID processId = UUID.randomUUID();
            manager.testLogAsyncResponse(processId, null, Instant.now());
            verify(processRepo).logStep(eq("test-domain"), eq(processId), any(), any());
        }

        @Test
        @DisplayName("logAsyncResponse should handle non-null waitName")
        void logAsyncResponseWithWaitName() {
            UUID processId = UUID.randomUUID();
            manager.testLogAsyncResponse(processId, "waitForApproval", Instant.now());
            verify(processRepo).logStep(eq("test-domain"), eq(processId), any(), any());
        }

        @Test
        @DisplayName("logWaitTimeout should handle null errorMessage")
        void logWaitTimeoutWithNullMessage() {
            UUID processId = UUID.randomUUID();
            manager.testLogWaitTimeout(processId, "testWait", Instant.now(), null);
            verify(processRepo).logStep(eq("test-domain"), eq(processId), any(), any());
        }

        @Test
        @DisplayName("logWaitTimeout should handle non-null errorMessage")
        void logWaitTimeoutWithMessage() {
            UUID processId = UUID.randomUUID();
            manager.testLogWaitTimeout(processId, "testWait", Instant.now(), "Wait timed out");
            verify(processRepo).logStep(eq("test-domain"), eq(processId), any(), any());
        }

        @Test
        @DisplayName("logCompleteOverride should handle null stateOverrides")
        void logCompleteOverrideWithNullOverrides() {
            UUID processId = UUID.randomUUID();
            manager.testLogCompleteOverride(processId, Instant.now(), null);
            verify(processRepo).logStep(eq("test-domain"), eq(processId), any(), any());
        }

        @Test
        @DisplayName("logCompleteOverride should handle empty stateOverrides")
        void logCompleteOverrideWithEmptyOverrides() {
            UUID processId = UUID.randomUUID();
            manager.testLogCompleteOverride(processId, Instant.now(), Map.of());
            verify(processRepo).logStep(eq("test-domain"), eq(processId), any(), any());
        }

        @Test
        @DisplayName("logCompleteOverride should handle non-empty stateOverrides")
        void logCompleteOverrideWithOverrides() {
            UUID processId = UUID.randomUUID();
            manager.testLogCompleteOverride(processId, Instant.now(), Map.of("key", "value"));
            verify(processRepo).logStep(eq("test-domain"), eq(processId), any(), any());
        }

        @Test
        @DisplayName("logMoveToTsq should handle all null values")
        void logMoveToTsqWithNulls() {
            UUID processId = UUID.randomUUID();
            manager.testLogMoveToTsq(processId, Instant.now(), null, null, null);
            verify(processRepo).logStep(eq("test-domain"), eq(processId), any(), any());
        }

        @Test
        @DisplayName("logMoveToTsq should handle all non-null values")
        void logMoveToTsqWithValues() {
            UUID processId = UUID.randomUUID();
            manager.testLogMoveToTsq(processId, Instant.now(), "timeout", "TIMEOUT", "Wait timed out");
            verify(processRepo).logStep(eq("test-domain"), eq(processId), any(), any());
        }

        @Test
        @DisplayName("logWaitStarted should log with timeout info")
        void logWaitStarted() {
            UUID processId = UUID.randomUUID();
            Instant now = Instant.now();
            Duration timeout = Duration.ofMinutes(5);
            manager.testLogWaitStarted(processId, "testWait", now, timeout, now.plus(timeout));
            verify(processRepo).logStep(eq("test-domain"), eq(processId), any(), any());
        }

        @Test
        @DisplayName("logCancelOverride should log with runCompensations flag")
        void logCancelOverride() {
            UUID processId = UUID.randomUUID();
            manager.testLogCancelOverride(processId, Instant.now(), true);
            verify(processRepo).logStep(eq("test-domain"), eq(processId), any(), any());
        }
    }

    // ========== Test Implementations ==========

    /**
     * Concrete test implementation of ProcessStepManager.
     */
    static class TestProcessStepManager extends ProcessStepManager<TestState> {

        private Consumer<TestState> executeAction = state -> {};

        public TestProcessStepManager(ProcessRepository processRepo,
                                      JdbcTemplate jdbcTemplate,
                                      TransactionTemplate transactionTemplate) {
            super(processRepo, jdbcTemplate, transactionTemplate);
        }

        public TestProcessStepManager(ProcessRepository processRepo,
                                      JdbcTemplate jdbcTemplate,
                                      TransactionTemplate transactionTemplate,
                                      ObjectMapper objectMapper) {
            super(processRepo, jdbcTemplate, transactionTemplate, objectMapper);
        }

        public TestProcessStepManager(ProcessRepository processRepo,
                                      JdbcTemplate jdbcTemplate,
                                      TransactionTemplate transactionTemplate,
                                      Bucket4jRateLimiter rateLimiter) {
            super(processRepo, jdbcTemplate, transactionTemplate, rateLimiter);
        }

        void setExecuteAction(Consumer<TestState> action) {
            this.executeAction = action;
        }

        @Override
        public String getProcessType() {
            return "test-process";
        }

        @Override
        public String getDomain() {
            return "test-domain";
        }

        @Override
        public Class<TestState> getStateClass() {
            return TestState.class;
        }

        @Override
        protected void execute(TestState state) {
            executeAction.accept(state);
        }

        // Test accessors for protected methods
        public <R> R testStep(String name, java.util.function.Function<TestState, R> action) {
            return step(name, action);
        }

        public <R> R testStep(String name, java.util.function.Function<TestState, R> action,
                              int maxRetries, Consumer<TestState> compensation) {
            return step(name, action, maxRetries, compensation);
        }

        public <R> R testStepWithOptions(String name, StepOptions<TestState, R> options) {
            return step(name, options);
        }

        public void testWait(String name, java.util.function.Supplier<Boolean> condition) {
            wait(name, condition);
        }

        public void testWait(String name, java.util.function.Supplier<Boolean> condition, Duration timeout) {
            wait(name, condition, timeout);
        }

        public <R> R testSideEffect(String name, java.util.function.Supplier<R> operation) {
            return sideEffect(name, operation);
        }

        public ExceptionType testClassifyException(Exception e) {
            return classifyException(e);
        }

        public Duration testCalculateBackoff(int attempt, Duration baseDelay) {
            return calculateBackoff(attempt, baseDelay);
        }

        public String testSerializeState(TestState state) {
            return serializeState(state);
        }

        public String testSerializeResult(Object result) {
            return serializeResult(result);
        }

        public Object testDeserializeResult(String json) {
            return deserializeResult(json, StepOptions.<TestState, Object>builder()
                .action(s -> null)
                .build());
        }

        // Test accessors for audit logging methods
        public void testLogStepSuccess(UUID processId, String stepName, Instant startedAt, String responseJson) {
            logStepSuccess(processId, stepName, startedAt, responseJson);
        }

        public void testLogStepFailure(UUID processId, String stepName, Instant startedAt, String errorCode, String errorMessage) {
            logStepFailure(processId, stepName, startedAt, errorCode, errorMessage);
        }

        public void testLogStepRetry(UUID processId, String stepName, Instant startedAt, int attemptCount, Instant nextRetryAt, String errorMessage) {
            logStepRetry(processId, stepName, startedAt, attemptCount, nextRetryAt, errorMessage);
        }

        public void testLogCompensation(UUID processId, String stepName, Instant startedAt, boolean success, Exception error) {
            logCompensation(processId, stepName, startedAt, success, error);
        }

        public void testLogAsyncResponse(UUID processId, String waitName, Instant recordedAt) {
            logAsyncResponse(processId, waitName, recordedAt);
        }

        public void testLogWaitStarted(UUID processId, String waitName, Instant recordedAt, Duration timeout, Instant timeoutAt) {
            logWaitStarted(processId, waitName, recordedAt, timeout, timeoutAt);
        }

        public void testLogWaitTimeout(UUID processId, String waitName, Instant recordedAt, String errorMessage) {
            logWaitTimeout(processId, waitName, recordedAt, errorMessage);
        }

        public void testLogCompleteOverride(UUID processId, Instant recordedAt, Map<String, Object> stateOverrides) {
            logCompleteOverride(processId, recordedAt, stateOverrides);
        }

        public void testLogCancelOverride(UUID processId, Instant recordedAt, boolean runCompensations) {
            logCancelOverride(processId, recordedAt, runCompensations);
        }

        public void testLogMoveToTsq(UUID processId, Instant recordedAt, String reason, String errorCode, String errorMessage) {
            logMoveToTsq(processId, recordedAt, reason, errorCode, errorMessage);
        }
    }

    /**
     * Test state class.
     */
    static class TestState extends ProcessStepState {

        private String data;

        public String getData() {
            return data;
        }

        public void setData(String data) {
            this.data = data;
        }
    }
}
