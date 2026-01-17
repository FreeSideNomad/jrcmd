package com.ivamare.commandbus.process;

import com.ivamare.commandbus.model.ReplyOutcome;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("JdbcProcessRepository")
class JdbcProcessRepositoryTest {

    private JdbcTemplate jdbcTemplate;
    private ObjectMapper objectMapper;
    private JdbcProcessRepository repository;

    enum TestStep { STEP_ONE, STEP_TWO }

    record TestState(String value) implements ProcessState {
        @Override
        public Map<String, Object> toMap() {
            return Map.of("value", value);
        }
    }

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        objectMapper = new ObjectMapper();
        repository = new JdbcProcessRepository(jdbcTemplate, objectMapper);
    }

    @Test
    @DisplayName("should save new process")
    void shouldSaveNewProcess() {
        UUID processId = UUID.randomUUID();
        TestState state = new TestState("initial");
        ProcessMetadata<TestState, TestStep> process = ProcessMetadata.create(
            "orders", processId, "ORDER_FULFILLMENT", state
        );

        repository.save(process);

        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(contains("INSERT INTO commandbus.process"), argsCaptor.capture());

        Object[] args = argsCaptor.getValue();
        assertEquals("orders", args[0]);
        assertEquals(processId, args[1]);
        assertEquals("ORDER_FULFILLMENT", args[2]);
        assertEquals("PENDING", args[3]);
    }

    @Test
    @DisplayName("should update existing process")
    void shouldUpdateExistingProcess() {
        UUID processId = UUID.randomUUID();
        ProcessMetadata<TestState, TestStep> process = ProcessMetadata.<TestState, TestStep>create(
            "orders", processId, "ORDER_FULFILLMENT", new TestState("initial")
        ).withStatus(ProcessStatus.IN_PROGRESS);

        repository.update(process);

        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(contains("UPDATE commandbus.process"), argsCaptor.capture());

        Object[] args = argsCaptor.getValue();
        assertEquals("IN_PROGRESS", args[0]);
    }

    @Test
    @DisplayName("should get process by ID")
    void shouldGetProcessById() {
        UUID processId = UUID.randomUUID();
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq("orders"), eq(processId)))
            .thenReturn(List.of());

        var result = repository.getById("orders", processId);

        assertTrue(result.isEmpty());
        verify(jdbcTemplate).query(contains("SELECT"), any(RowMapper.class), eq("orders"), eq(processId));
    }

    @Test
    @DisplayName("should find processes by status")
    void shouldFindProcessesByStatus() {
        // The query uses varargs, so we need to match flexibly
        when(jdbcTemplate.query(
            contains("status IN"),
            any(RowMapper.class),
            (Object[]) any()
        )).thenReturn(List.of());

        var result = repository.findByStatus("orders",
            List.of(ProcessStatus.PENDING, ProcessStatus.IN_PROGRESS));

        assertTrue(result.isEmpty());

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> paramsCaptor = ArgumentCaptor.forClass(Object[].class);

        verify(jdbcTemplate).query(
            sqlCaptor.capture(),
            any(RowMapper.class),
            paramsCaptor.capture()
        );

        assertTrue(sqlCaptor.getValue().contains("status IN (?,?)"));
        Object[] params = paramsCaptor.getValue();
        assertEquals("orders", params[0]);
        assertEquals("PENDING", params[1]);
        assertEquals("IN_PROGRESS", params[2]);
    }

    @Test
    @DisplayName("should return empty list for empty status list")
    void shouldReturnEmptyListForEmptyStatusList() {
        var result = repository.findByStatus("orders", List.of());

        assertTrue(result.isEmpty());
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("should find processes by type")
    void shouldFindProcessesByType() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq("orders"), eq("ORDER_FULFILLMENT")))
            .thenReturn(List.of());

        repository.findByType("orders", "ORDER_FULFILLMENT");

        verify(jdbcTemplate).query(
            contains("process_type = ?"),
            any(RowMapper.class),
            eq("orders"),
            eq("ORDER_FULFILLMENT")
        );
    }

    @Test
    @DisplayName("should log step execution")
    void shouldLogStepExecution() {
        UUID processId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        ProcessAuditEntry entry = ProcessAuditEntry.forCommand(
            "STEP_ONE", commandId, "DoSomething", Map.of("key", "value")
        );

        repository.logStep("orders", processId, entry);

        verify(jdbcTemplate).update(
            contains("INSERT INTO commandbus.process_audit"),
            eq("orders"),
            eq(processId),
            eq("STEP_ONE"),
            eq(commandId),
            eq("DoSomething"),
            anyString(),  // command_data JSON
            any(Timestamp.class),
            isNull(),  // reply_outcome
            isNull(),  // reply_data
            isNull()   // received_at
        );
    }

    @Test
    @DisplayName("should update step with reply")
    void shouldUpdateStepWithReply() {
        UUID processId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        ProcessAuditEntry entry = ProcessAuditEntry.forCommand(
            "STEP_ONE", commandId, "DoSomething", Map.of()
        ).withReply(ReplyOutcome.SUCCESS, Map.of("result", "ok"));

        repository.updateStepReply("orders", processId, commandId, entry);

        verify(jdbcTemplate).update(
            contains("UPDATE commandbus.process_audit"),
            eq("SUCCESS"),
            anyString(),  // reply_data JSON
            any(Timestamp.class),
            eq("orders"),
            eq(processId),
            eq(commandId)
        );
    }

    @Test
    @DisplayName("should get audit trail")
    void shouldGetAuditTrail() {
        UUID processId = UUID.randomUUID();
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq("orders"), eq(processId)))
            .thenReturn(List.of());

        repository.getAuditTrail("orders", processId);

        verify(jdbcTemplate).query(
            contains("ORDER BY sent_at ASC"),
            any(RowMapper.class),
            eq("orders"),
            eq(processId)
        );
    }

    @Test
    @DisplayName("should get completed steps")
    void shouldGetCompletedSteps() {
        UUID processId = UUID.randomUUID();
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq("orders"), eq(processId)))
            .thenReturn(List.of("STEP_ONE", "STEP_TWO"));

        List<String> result = repository.getCompletedSteps("orders", processId);

        assertEquals(List.of("STEP_ONE", "STEP_TWO"), result);
        verify(jdbcTemplate).queryForList(
            contains("reply_outcome = 'SUCCESS'"),
            eq(String.class),
            eq("orders"),
            eq(processId)
        );
    }

    @Test
    @DisplayName("should use provided JdbcTemplate for transactional operations")
    void shouldUseProvidedJdbcTemplateForTransactionalOperations() {
        JdbcTemplate transactionalJdbc = mock(JdbcTemplate.class);
        UUID processId = UUID.randomUUID();
        ProcessMetadata<TestState, TestStep> process = ProcessMetadata.create(
            "orders", processId, "ORDER_FULFILLMENT", new TestState("initial")
        );

        repository.save(process, transactionalJdbc);

        verify(transactionalJdbc).update(anyString(), any(Object[].class));
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("should save process with null current step")
    void shouldSaveProcessWithNullCurrentStep() {
        UUID processId = UUID.randomUUID();
        ProcessMetadata<TestState, TestStep> process = new ProcessMetadata<>(
            "orders", processId, "ORDER_FULFILLMENT",
            new TestState("initial"),
            ProcessStatus.PENDING,
            null,  // null current step
            Instant.now(), Instant.now(), null, null, null
        );

        repository.save(process);

        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(contains("INSERT INTO commandbus.process"), argsCaptor.capture());

        Object[] args = argsCaptor.getValue();
        assertNull(args[4]);  // current_step should be null
    }

    @Test
    @DisplayName("should save process with completed timestamp")
    void shouldSaveProcessWithCompletedTimestamp() {
        UUID processId = UUID.randomUUID();
        Instant completedAt = Instant.now();
        ProcessMetadata<TestState, TestStep> process = new ProcessMetadata<>(
            "orders", processId, "ORDER_FULFILLMENT",
            new TestState("done"),
            ProcessStatus.COMPLETED,
            TestStep.STEP_TWO,
            Instant.now(), Instant.now(), completedAt, null, null
        );

        repository.save(process);

        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(contains("INSERT INTO commandbus.process"), argsCaptor.capture());

        Object[] args = argsCaptor.getValue();
        assertNotNull(args[10]);  // completed_at should be set
    }

    @Test
    @DisplayName("should update process with null completed timestamp")
    void shouldUpdateProcessWithNullCompletedTimestamp() {
        UUID processId = UUID.randomUUID();
        ProcessMetadata<TestState, TestStep> process = new ProcessMetadata<>(
            "orders", processId, "ORDER_FULFILLMENT",
            new TestState("progress"),
            ProcessStatus.IN_PROGRESS,
            TestStep.STEP_ONE,
            Instant.now(), Instant.now(), null, null, null
        );

        repository.update(process);

        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(contains("UPDATE commandbus.process"), argsCaptor.capture());

        Object[] args = argsCaptor.getValue();
        assertNull(args[5]);  // completed_at should be null
    }

    @Test
    @DisplayName("should log step with null reply data")
    void shouldLogStepWithNullReplyData() {
        UUID processId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        ProcessAuditEntry entry = new ProcessAuditEntry(
            "STEP_ONE", commandId, "DoSomething",
            null,  // null command data
            Instant.now(), null, null, null
        );

        repository.logStep("orders", processId, entry);

        verify(jdbcTemplate).update(
            contains("INSERT INTO commandbus.process_audit"),
            eq("orders"),
            eq(processId),
            eq("STEP_ONE"),
            eq(commandId),
            eq("DoSomething"),
            isNull(),  // command_data should be null
            any(Timestamp.class),
            isNull(),
            isNull(),
            isNull()
        );
    }

    @Test
    @DisplayName("should update step reply with null outcome")
    void shouldUpdateStepReplyWithNullOutcome() {
        UUID processId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        ProcessAuditEntry entry = new ProcessAuditEntry(
            "STEP_ONE", commandId, "DoSomething",
            Map.of(), Instant.now(),
            null,  // null outcome
            null,  // null reply data
            null   // null received_at
        );

        repository.updateStepReply("orders", processId, commandId, entry);

        verify(jdbcTemplate).update(
            contains("UPDATE commandbus.process_audit"),
            isNull(),  // reply_outcome is null
            isNull(),  // reply_data is null
            isNull(),  // received_at is null
            eq("orders"),
            eq(processId),
            eq(commandId)
        );
    }

    @Test
    @DisplayName("should handle null state in save")
    void shouldHandleNullStateInSave() {
        UUID processId = UUID.randomUUID();
        ProcessMetadata<TestState, TestStep> process = new ProcessMetadata<>(
            "orders", processId, "ORDER_FULFILLMENT",
            null,  // null state
            ProcessStatus.PENDING,
            null,
            Instant.now(), Instant.now(), null, null, null
        );

        repository.save(process);

        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(contains("INSERT INTO commandbus.process"), argsCaptor.capture());

        Object[] args = argsCaptor.getValue();
        assertEquals("{}", args[5]);  // Should serialize to empty JSON
    }

    @Test
    @DisplayName("should save batch of processes")
    void shouldSaveBatchOfProcesses() {
        UUID processId1 = UUID.randomUUID();
        UUID processId2 = UUID.randomUUID();
        Instant now = Instant.now();

        List<ProcessMetadata<?, ?>> processes = List.of(
            new ProcessMetadata<>("orders", processId1, "ORDER", new TestState("state1"),
                ProcessStatus.WAITING_FOR_REPLY, TestStep.STEP_ONE, now, now, null, null, null),
            new ProcessMetadata<>("orders", processId2, "ORDER", new TestState("state2"),
                ProcessStatus.WAITING_FOR_REPLY, TestStep.STEP_TWO, now, now, null, null, null)
        );

        repository.saveBatch(processes, jdbcTemplate);

        verify(jdbcTemplate).batchUpdate(contains("INSERT INTO commandbus.process"), anyList());
    }

    @Test
    @DisplayName("should not call batchUpdate for empty process list")
    void shouldNotCallBatchUpdateForEmptyProcessList() {
        repository.saveBatch(List.of(), jdbcTemplate);

        verify(jdbcTemplate, never()).batchUpdate(anyString(), anyList());
    }

    @Test
    @DisplayName("should log batch of steps")
    void shouldLogBatchOfSteps() {
        UUID processId1 = UUID.randomUUID();
        UUID processId2 = UUID.randomUUID();
        UUID commandId1 = UUID.randomUUID();
        UUID commandId2 = UUID.randomUUID();

        ProcessAuditEntry entry1 = ProcessAuditEntry.forCommand("STEP_ONE", commandId1, "Command1", Map.of("key", "val1"));
        ProcessAuditEntry entry2 = ProcessAuditEntry.forCommand("STEP_TWO", commandId2, "Command2", Map.of("key", "val2"));

        List<JdbcProcessRepository.ProcessAuditBatchEntry> entries = List.of(
            new JdbcProcessRepository.ProcessAuditBatchEntry(processId1, entry1),
            new JdbcProcessRepository.ProcessAuditBatchEntry(processId2, entry2)
        );

        repository.logBatchSteps("orders", entries, jdbcTemplate);

        verify(jdbcTemplate).batchUpdate(contains("INSERT INTO commandbus.process_audit"), anyList());
    }

    @Test
    @DisplayName("should not call batchUpdate for empty audit entry list")
    void shouldNotCallBatchUpdateForEmptyAuditEntryList() {
        repository.logBatchSteps("orders", List.of(), jdbcTemplate);

        verify(jdbcTemplate, never()).batchUpdate(contains("process_audit"), anyList());
    }

    @Test
    @DisplayName("should get process by id using row mapper")
    void shouldGetProcessByIdUsingRowMapper() {
        UUID processId = UUID.randomUUID();
        Instant now = Instant.now();
        Timestamp timestamp = Timestamp.from(now);

        // Capture the row mapper that gets passed to query()
        @SuppressWarnings("unchecked")
        ArgumentCaptor<RowMapper<ProcessMetadata<?, ?>>> mapperCaptor =
            ArgumentCaptor.forClass(RowMapper.class);

        when(jdbcTemplate.query(contains("SELECT"), mapperCaptor.capture(), eq("orders"), eq(processId)))
            .thenReturn(List.of());

        repository.getById("orders", processId, jdbcTemplate);

        // Verify query was called with correct parameters
        verify(jdbcTemplate).query(contains("SELECT"), any(RowMapper.class), eq("orders"), eq(processId));
    }

    @Test
    @DisplayName("should get audit trail using row mapper")
    void shouldGetAuditTrailUsingRowMapper() {
        UUID processId = UUID.randomUUID();

        when(jdbcTemplate.query(contains("process_audit"), any(RowMapper.class), eq("orders"), eq(processId)))
            .thenReturn(List.of());

        repository.getAuditTrail("orders", processId);

        verify(jdbcTemplate).query(contains("process_audit"), any(RowMapper.class), eq("orders"), eq(processId));
    }

    @Test
    @DisplayName("should call sp_update_process_state for atomic update")
    void shouldCallSpUpdateProcessStateForAtomicUpdate() {
        UUID processId = UUID.randomUUID();

        repository.updateStateAtomic(
            "orders",
            processId,
            "{\"test\": true}",
            "STEP_ONE",
            "IN_PROGRESS",
            null,  // errorCode
            null,  // errorMessage
            jdbcTemplate
        );

        // The method uses queryForObject to call the stored procedure
        verify(jdbcTemplate).queryForObject(
            contains("sp_update_process_state"),
            any(RowMapper.class),
            eq("orders"),
            eq(processId),
            eq("{\"test\": true}"),
            eq("STEP_ONE"),
            eq("IN_PROGRESS"),
            isNull(),
            isNull()
        );
    }

    @Test
    @DisplayName("should insert new audit row when update finds no existing row")
    void shouldInsertNewAuditRowWhenUpdateFindsNoExistingRow() {
        UUID processId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        ProcessAuditEntry entry = ProcessAuditEntry.forCommand(
            "STEP_ONE", commandId, "DoSomething", Map.of("key", "value")
        ).withReply(ReplyOutcome.SUCCESS, Map.of("result", "ok"));

        // First update returns 0 (no rows updated)
        when(jdbcTemplate.update(contains("UPDATE commandbus.process_audit"),
            any(), any(), any(), any(), any(), any())).thenReturn(0);

        repository.updateStepReply("orders", processId, commandId, entry);

        // Verify UPDATE was called first
        verify(jdbcTemplate).update(contains("UPDATE commandbus.process_audit"),
            eq("SUCCESS"),
            anyString(),  // reply_data JSON
            any(Timestamp.class),
            eq("orders"),
            eq(processId),
            eq(commandId)
        );

        // Verify INSERT was called when update returned 0
        verify(jdbcTemplate).update(contains("INSERT INTO commandbus.process_audit"),
            eq("orders"),
            eq(processId),
            eq("STEP_ONE"),
            eq(commandId),
            eq("DoSomething"),
            anyString(),  // command_data
            any(Timestamp.class),
            eq("SUCCESS"),
            anyString(),  // reply_data
            any(Timestamp.class)
        );
    }

    @Test
    @DisplayName("should find processes due for retry by type")
    void shouldFindProcessesDueForRetryByType() {
        Instant now = Instant.now();
        when(jdbcTemplate.queryForList(
            contains("process_type = ?"),
            eq(UUID.class),
            eq("orders"),
            eq("ORDER_FULFILLMENT"),
            any(Timestamp.class)
        )).thenReturn(List.of(UUID.randomUUID()));

        List<UUID> result = repository.findDueForRetry("orders", "ORDER_FULFILLMENT", now);

        assertEquals(1, result.size());
        verify(jdbcTemplate).queryForList(
            contains("process_type = ?"),
            eq(UUID.class),
            eq("orders"),
            eq("ORDER_FULFILLMENT"),
            any(Timestamp.class)
        );
    }

    @Test
    @DisplayName("should find expired waits without process type")
    void shouldFindExpiredWaitsWithoutProcessType() {
        Instant now = Instant.now();
        when(jdbcTemplate.queryForList(
            contains("WAITING_FOR_ASYNC"),
            eq(UUID.class),
            eq("orders"),
            any(Timestamp.class)
        )).thenReturn(List.of());

        List<UUID> result = repository.findExpiredWaits("orders", now);

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("should find expired deadlines without process type")
    void shouldFindExpiredDeadlinesWithoutProcessType() {
        Instant now = Instant.now();
        when(jdbcTemplate.queryForList(
            contains("deadline_at"),
            eq(UUID.class),
            eq("orders"),
            any(Timestamp.class)
        )).thenReturn(List.of());

        List<UUID> result = repository.findExpiredDeadlines("orders", now);

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("should claim pending processes with custom timeout")
    void shouldClaimPendingProcessesWithCustomTimeout() {
        when(jdbcTemplate.queryForList(
            contains("sp_claim_pending_processes"),
            eq(UUID.class),
            eq("orders"),
            eq("ORDER"),
            eq(50),
            eq(120)
        )).thenReturn(List.of());

        repository.claimPendingProcesses("orders", "ORDER", 50, 120);

        verify(jdbcTemplate).queryForList(
            contains("sp_claim_pending_processes"),
            eq(UUID.class),
            eq("orders"),
            eq("ORDER"),
            eq(50),
            eq(120)
        );
    }

    @Test
    @DisplayName("should claim retry processes with custom timeout")
    void shouldClaimRetryProcessesWithCustomTimeout() {
        when(jdbcTemplate.queryForList(
            contains("sp_claim_retry_processes"),
            eq(UUID.class),
            eq("orders"),
            eq("ORDER"),
            eq(50),
            eq(120)
        )).thenReturn(List.of());

        repository.claimDueForRetry("orders", "ORDER", 50, 120);

        verify(jdbcTemplate).queryForList(
            contains("sp_claim_retry_processes"),
            eq(UUID.class),
            eq("orders"),
            eq("ORDER"),
            eq(50),
            eq(120)
        );
    }

    @Test
    @DisplayName("should update state with audit entry")
    void shouldUpdateStateWithAuditEntry() {
        UUID processId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        Instant now = Instant.now();

        ProcessAuditEntry auditEntry = new ProcessAuditEntry(
            "STEP_ONE", commandId, "DoSomething",
            Map.of("input", "value"), now,
            ReplyOutcome.SUCCESS, Map.of("output", "result"), now
        );

        repository.updateStateWithAudit(
            "orders",
            processId,
            "{\"full\": \"state\"}",
            "{\"patch\": true}",
            "STEP_ONE",
            "IN_PROGRESS",
            null,
            null,
            null,
            null,
            null,
            auditEntry,
            jdbcTemplate
        );

        verify(jdbcTemplate).queryForObject(
            contains("sp_update_process_with_audit"),
            any(RowMapper.class),
            eq("orders"),
            eq(processId),
            eq("{\"full\": \"state\"}"),
            eq("{\"patch\": true}"),
            eq("STEP_ONE"),
            eq("IN_PROGRESS"),
            isNull(),  // errorCode
            isNull(),  // errorMessage
            isNull(),  // nextRetryAt
            isNull(),  // nextWaitTimeoutAt
            isNull(),  // currentWait
            eq("STEP_ONE"),
            eq(commandId),
            eq("DoSomething"),
            anyString(),  // command_data JSON
            any(Timestamp.class),
            eq("SUCCESS"),
            anyString(),  // reply_data JSON
            any(Timestamp.class)
        );
    }

    @Test
    @DisplayName("should update state with null audit entry")
    void shouldUpdateStateWithNullAuditEntry() {
        UUID processId = UUID.randomUUID();

        repository.updateStateWithAudit(
            "orders",
            processId,
            "{\"full\": \"state\"}",
            null,  // no patch
            "STEP_ONE",
            "IN_PROGRESS",
            "ERROR_CODE",
            "Error message",
            Instant.now(),
            null,
            null,
            null,  // null audit entry
            jdbcTemplate
        );

        verify(jdbcTemplate).queryForObject(
            contains("sp_update_process_with_audit"),
            any(RowMapper.class),
            eq("orders"),
            eq(processId),
            eq("{\"full\": \"state\"}"),
            isNull(),  // statePatch
            eq("STEP_ONE"),
            eq("IN_PROGRESS"),
            eq("ERROR_CODE"),
            eq("Error message"),
            any(Timestamp.class),  // nextRetryAt
            isNull(),  // nextWaitTimeoutAt
            isNull(),  // currentWait
            isNull(),  // auditEntry.stepName
            isNull(),  // auditEntry.commandId
            isNull(),  // auditEntry.commandType
            isNull(),  // auditEntry.commandData
            isNull(),  // auditEntry.sentAt
            isNull(),  // auditEntry.replyOutcome
            isNull(),  // auditEntry.replyData
            isNull()   // auditEntry.receivedAt
        );
    }
}
