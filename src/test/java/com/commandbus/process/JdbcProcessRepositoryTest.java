package com.commandbus.process;

import com.commandbus.model.ReplyOutcome;
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
}
