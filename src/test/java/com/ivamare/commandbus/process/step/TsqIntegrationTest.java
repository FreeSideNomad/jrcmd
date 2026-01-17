package com.ivamare.commandbus.process.step;

import com.ivamare.commandbus.process.ProcessAuditEntry;
import com.ivamare.commandbus.process.ProcessRepository;
import com.ivamare.commandbus.process.ProcessStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for TSQ (Troubleshooting Queue) integration with ProcessStepManager.
 * Verifies retry, cancelOverride, and completeOverride operations.
 */
@DisplayName("TSQ Integration")
class TsqIntegrationTest {

    @Mock
    private ProcessRepository processRepo;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private TransactionTemplate transactionTemplate;

    private TestStepManager manager;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        // Configure transaction template to execute synchronously
        // Use lenient() since not all methods are called in every test
        lenient().doAnswer(invocation -> {
            var action = invocation.getArgument(0);
            if (action instanceof org.springframework.transaction.support.TransactionCallback<?> callback) {
                return callback.doInTransaction(null);
            }
            return null;
        }).when(transactionTemplate).execute(any());

        // Also mock executeWithoutResult since it's a final/default method that may not delegate properly
        lenient().doAnswer(invocation -> {
            var consumer = invocation.getArgument(0, java.util.function.Consumer.class);
            consumer.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        manager = new TestStepManager(processRepo, jdbcTemplate, transactionTemplate);
    }

    @Test
    @DisplayName("retry should clear error and resume execution")
    void retryShouldClearErrorAndResumeExecution() {
        UUID processId = UUID.randomUUID();
        TestState state = new TestState();
        state.setErrorCode("SOME_ERROR");
        state.setErrorMessage("Some error message");

        when(processRepo.getStateJson(eq("test-domain"), eq(processId), eq(jdbcTemplate)))
            .thenReturn("{\"errorCode\":\"SOME_ERROR\",\"errorMessage\":\"Some error message\",\"stepHistory\":[],\"waitHistory\":[],\"sideEffects\":[]}");

        manager.retry(processId);

        // Verify error was cleared via state update
        verify(processRepo).updateState(eq("test-domain"), eq(processId), argThat(json ->
            json.contains("\"errorCode\":null") || !json.contains("errorCode")
        ), eq(jdbcTemplate));

        // Verify status changed to PENDING then process resumed
        verify(processRepo).updateStateAtomicStep(
            eq("test-domain"), eq(processId), isNull(),
            isNull(), eq("PENDING"),
            isNull(), isNull(), isNull(), isNull(), isNull(),
            eq(jdbcTemplate)
        );
    }

    @Test
    @DisplayName("cancelOverride without compensations should just cancel")
    void cancelOverrideWithoutCompensationsShouldJustCancel() {
        UUID processId = UUID.randomUUID();

        // State is now always loaded for hooks (onProcessCanceled)
        when(processRepo.getStateJson(eq("test-domain"), eq(processId), eq(jdbcTemplate)))
            .thenReturn("{\"stepHistory\":[],\"waitHistory\":[],\"sideEffects\":[]}");

        manager.cancelOverride(processId, false);

        verify(processRepo).updateStateAtomicStep(
            eq("test-domain"), eq(processId), isNull(),
            isNull(), eq("CANCELED"),
            isNull(), isNull(), isNull(), isNull(), isNull(),
            eq(jdbcTemplate)
        );

        // State is loaded for hooks even when compensations not requested
        verify(processRepo).getStateJson(eq("test-domain"), eq(processId), eq(jdbcTemplate));
    }

    @Test
    @DisplayName("cancelOverride with compensations should run compensations")
    void cancelOverrideWithCompensationsShouldRunCompensations() {
        UUID processId = UUID.randomUUID();

        // State with completed steps that have compensations
        String stateJson = """
            {
                "stepHistory": [
                    {"name": "step1", "status": "COMPLETED", "attemptCount": 1, "maxRetries": 0, "startedAt": "2024-01-01T00:00:00Z"},
                    {"name": "step2", "status": "COMPLETED", "attemptCount": 1, "maxRetries": 0, "startedAt": "2024-01-01T00:01:00Z"}
                ],
                "waitHistory": [],
                "sideEffects": []
            }
            """;

        when(processRepo.getStateJson(eq("test-domain"), eq(processId), eq(jdbcTemplate)))
            .thenReturn(stateJson);

        manager.cancelOverride(processId, true);

        // Should load state for compensations
        verify(processRepo).getStateJson(eq("test-domain"), eq(processId), eq(jdbcTemplate));

        // Should update status to CANCELED
        verify(processRepo).updateStateAtomicStep(
            eq("test-domain"), eq(processId), isNull(),
            isNull(), eq("CANCELED"),
            isNull(), isNull(), isNull(), isNull(), isNull(),
            eq(jdbcTemplate)
        );
    }

    @Test
    @DisplayName("completeOverride without state overrides should complete process")
    void completeOverrideWithoutStateOverridesShouldCompleteProcess() {
        UUID processId = UUID.randomUUID();

        // State is loaded after update for hooks (onProcessCompleted)
        when(processRepo.getStateJson(eq("test-domain"), eq(processId), eq(jdbcTemplate)))
            .thenReturn("{\"stepHistory\":[],\"waitHistory\":[],\"sideEffects\":[]}");

        manager.completeOverride(processId, null);

        verify(processRepo).updateStateAtomicStep(
            eq("test-domain"), eq(processId), isNull(),
            isNull(), eq("COMPLETED"),
            isNull(), isNull(), isNull(), isNull(), isNull(),
            eq(jdbcTemplate)
        );

        // State is loaded for hooks
        verify(processRepo).getStateJson(eq("test-domain"), eq(processId), eq(jdbcTemplate));
    }

    @Test
    @DisplayName("completeOverride with empty state overrides should complete process")
    void completeOverrideWithEmptyStateOverridesShouldCompleteProcess() {
        UUID processId = UUID.randomUUID();

        // State is loaded after update for hooks (onProcessCompleted)
        when(processRepo.getStateJson(eq("test-domain"), eq(processId), eq(jdbcTemplate)))
            .thenReturn("{\"stepHistory\":[],\"waitHistory\":[],\"sideEffects\":[]}");

        manager.completeOverride(processId, Map.of());

        verify(processRepo).updateStateAtomicStep(
            eq("test-domain"), eq(processId), isNull(),
            isNull(), eq("COMPLETED"),
            isNull(), isNull(), isNull(), isNull(), isNull(),
            eq(jdbcTemplate)
        );

        // State is loaded for hooks
        verify(processRepo).getStateJson(eq("test-domain"), eq(processId), eq(jdbcTemplate));
    }

    @Test
    @DisplayName("completeOverride with state overrides should apply overrides")
    void completeOverrideWithStateOverridesShouldApplyOverrides() {
        UUID processId = UUID.randomUUID();
        Map<String, Object> overrides = Map.of(
            "status", "MANUALLY_COMPLETED",
            "completedBy", "operator"
        );

        // State is loaded after update for hooks (onProcessCompleted)
        when(processRepo.getStateJson(eq("test-domain"), eq(processId), eq(jdbcTemplate)))
            .thenReturn("{\"status\":\"MANUALLY_COMPLETED\",\"completedBy\":\"operator\",\"stepHistory\":[],\"waitHistory\":[],\"sideEffects\":[]}");

        manager.completeOverride(processId, overrides);

        ArgumentCaptor<String> statePatchCaptor = ArgumentCaptor.forClass(String.class);
        verify(processRepo).updateStateAtomicStep(
            eq("test-domain"), eq(processId), statePatchCaptor.capture(),
            isNull(), eq("COMPLETED"),
            isNull(), isNull(), isNull(), isNull(), isNull(),
            eq(jdbcTemplate)
        );

        String statePatch = statePatchCaptor.getValue();
        assertNotNull(statePatch);
        assertTrue(statePatch.contains("MANUALLY_COMPLETED"));
        assertTrue(statePatch.contains("operator"));

        // State is loaded for hooks
        verify(processRepo).getStateJson(eq("test-domain"), eq(processId), eq(jdbcTemplate));
    }

    @Test
    @DisplayName("handleWaitTimeout should move process to TSQ")
    void handleWaitTimeoutShouldMoveProcessToTsq() {
        UUID processId = UUID.randomUUID();

        String stateJson = "{\"stepHistory\":[],\"waitHistory\":[],\"sideEffects\":[]}";
        when(processRepo.getStateJson(eq("test-domain"), eq(processId), eq(jdbcTemplate)))
            .thenReturn(stateJson);

        manager.handleWaitTimeout(processId);

        // Verify atomic state + audit update was called
        verify(processRepo).updateStateWithAudit(
            eq("test-domain"), eq(processId),
            argThat(json -> json != null && json.contains("WAIT_TIMEOUT")),  // state JSON with error
            isNull(),  // no state patch
            isNull(),  // no step
            eq("WAITING_FOR_TSQ"),  // new status
            eq("WAIT_TIMEOUT"),  // error code
            argThat(msg -> msg != null && msg.contains("timed out")),  // error message
            isNull(), isNull(), isNull(),  // no retry/timeout/wait
            argThat(audit -> audit != null && "WAIT_TIMEOUT".equals(audit.commandType())),  // audit entry
            eq(jdbcTemplate)
        );
    }

    @Test
    @DisplayName("handleDeadlineExceeded with TSQ action should move to TSQ")
    void handleDeadlineExceededWithTsqActionShouldMoveToTsq() {
        UUID processId = UUID.randomUUID();

        String stateJson = "{\"stepHistory\":[],\"waitHistory\":[],\"sideEffects\":[]}";
        when(processRepo.getStateJson(eq("test-domain"), eq(processId), eq(jdbcTemplate)))
            .thenReturn(stateJson);

        // Default action is TSQ
        manager.handleDeadlineExceeded(processId);

        // Verify atomic state + audit update was called
        verify(processRepo).updateStateWithAudit(
            eq("test-domain"), eq(processId),
            argThat(json -> json != null && json.contains("DEADLINE_EXCEEDED")),  // state JSON with error
            isNull(),  // no state patch
            isNull(),  // no step
            eq("WAITING_FOR_TSQ"),  // new status
            eq("DEADLINE_EXCEEDED"),  // error code
            eq("Process deadline exceeded"),  // error message
            isNull(), isNull(), isNull(),  // no retry/timeout/wait
            argThat(audit -> audit != null && "DEADLINE_EXCEEDED".equals(audit.commandType())),  // audit entry
            eq(jdbcTemplate)
        );
    }

    @Test
    @DisplayName("handleDeadlineExceeded with FAIL action should fail process")
    void handleDeadlineExceededWithFailActionShouldFailProcess() {
        UUID processId = UUID.randomUUID();

        // Configure manager to use FAIL action
        TestStepManager failManager = new TestStepManager(processRepo, jdbcTemplate, transactionTemplate) {
            @Override
            protected DeadlineAction getDeadlineAction() {
                return DeadlineAction.FAIL;
            }
        };

        String stateJson = "{\"stepHistory\":[],\"waitHistory\":[],\"sideEffects\":[]}";
        when(processRepo.getStateJson(eq("test-domain"), eq(processId), eq(jdbcTemplate)))
            .thenReturn(stateJson);

        failManager.handleDeadlineExceeded(processId);

        // Verify atomic state + audit update was called
        verify(processRepo).updateStateWithAudit(
            eq("test-domain"), eq(processId),
            any(),  // state JSON
            isNull(),  // no state patch
            isNull(),  // no step
            eq("FAILED"),  // new status
            eq("DEADLINE_EXCEEDED"),  // error code
            eq("Process deadline exceeded"),  // error message
            isNull(), isNull(), isNull(),  // no retry/timeout/wait
            argThat(audit -> audit != null && "DEADLINE_EXCEEDED".equals(audit.commandType())),  // audit entry
            eq(jdbcTemplate)
        );
    }

    @Test
    @DisplayName("handleDeadlineExceeded with COMPENSATE action should run compensations")
    void handleDeadlineExceededWithCompensateActionShouldRunCompensations() {
        UUID processId = UUID.randomUUID();

        // Configure manager to use COMPENSATE action
        TestStepManager compensateManager = new TestStepManager(processRepo, jdbcTemplate, transactionTemplate) {
            @Override
            protected DeadlineAction getDeadlineAction() {
                return DeadlineAction.COMPENSATE;
            }
        };

        String stateJson = """
            {
                "stepHistory": [
                    {"name": "step1", "status": "COMPLETED", "attemptCount": 1, "maxRetries": 0, "startedAt": "2024-01-01T00:00:00Z"}
                ],
                "waitHistory": [],
                "sideEffects": []
            }
            """;
        when(processRepo.getStateJson(eq("test-domain"), eq(processId), eq(jdbcTemplate)))
            .thenReturn(stateJson);

        compensateManager.handleDeadlineExceeded(processId);

        // Verify atomic state + audit update was called
        verify(processRepo).updateStateWithAudit(
            eq("test-domain"), eq(processId),
            any(),  // state JSON
            isNull(),  // no state patch
            isNull(),  // no step
            eq("COMPENSATED"),  // new status
            eq("DEADLINE_EXCEEDED"),  // error code
            eq("Process deadline exceeded"),  // error message
            isNull(), isNull(), isNull(),  // no retry/timeout/wait
            argThat(audit -> audit != null && "DEADLINE_EXCEEDED".equals(audit.commandType())),  // audit entry
            eq(jdbcTemplate)
        );
    }

    @Test
    @DisplayName("getExecutionModel should return PROCESS_STEP")
    void getExecutionModelShouldReturnProcessStep() {
        assertEquals("PROCESS_STEP", manager.getExecutionModel());
    }

    // ========== Test State Class ==========

    static class TestState extends ProcessStepState {
        private String customField;

        public String getCustomField() {
            return customField;
        }

        public void setCustomField(String customField) {
            this.customField = customField;
        }
    }

    // ========== Test Manager Class ==========

    static class TestStepManager extends ProcessStepManager<TestState> {

        private boolean executeCalled = false;

        TestStepManager(ProcessRepository processRepo, JdbcTemplate jdbcTemplate,
                       TransactionTemplate transactionTemplate) {
            super(processRepo, jdbcTemplate, transactionTemplate);
        }

        @Override
        public String getProcessType() {
            return "TEST_PROCESS";
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
            executeCalled = true;
        }

        public boolean wasExecuteCalled() {
            return executeCalled;
        }
    }
}
