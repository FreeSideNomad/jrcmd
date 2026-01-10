package com.commandbus.process;

import com.commandbus.model.PgmqMessage;
import com.commandbus.model.ReplyOutcome;
import com.commandbus.pgmq.PgmqClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("ProcessReplyRouter")
class ProcessReplyRouterTest {

    private DataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private TransactionTemplate transactionTemplate;
    private ProcessRepository processRepo;
    private PgmqClient pgmqClient;
    private BaseProcessManager<?, ?> manager;
    private ProcessReplyRouter router;

    @BeforeEach
    void setUp() {
        dataSource = mock(DataSource.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        transactionTemplate = mock(TransactionTemplate.class);
        processRepo = mock(ProcessRepository.class);
        pgmqClient = mock(PgmqClient.class);
        manager = mock(BaseProcessManager.class);

        // Mock transaction template to execute callback without actual transaction
        doAnswer(invocation -> {
            var callback = invocation.getArgument(0, java.util.function.Consumer.class);
            callback.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        when(manager.getProcessType()).thenReturn("TEST_PROCESS");

        router = new ProcessReplyRouter(
            dataSource,
            jdbcTemplate,
            transactionTemplate,
            processRepo,
            Map.of("TEST_PROCESS", manager),
            pgmqClient,
            "test_replies",
            "test_domain",
            30,   // visibilityTimeout
            4,    // concurrency
            100,  // pollIntervalMs - short for tests
            false // useNotify
        );
    }

    @Test
    @DisplayName("should return correct configuration values")
    void shouldReturnCorrectConfigurationValues() {
        assertEquals("test_replies", router.getReplyQueue());
        assertEquals("test_domain", router.getDomain());
        assertFalse(router.isRunning());
        assertEquals(0, router.inFlightCount());
    }

    @Test
    @DisplayName("should start and stop router")
    void shouldStartAndStopRouter() throws Exception {
        // Empty queue to prevent infinite loop
        when(pgmqClient.read(anyString(), anyInt(), anyInt()))
            .thenReturn(List.of());

        router.start();
        assertTrue(router.isRunning());

        // Give it time to enter the loop
        Thread.sleep(50);

        router.stop(Duration.ofSeconds(5)).get(5, TimeUnit.SECONDS);
        assertFalse(router.isRunning());
    }

    @Test
    @DisplayName("should dispatch reply to correct manager")
    void shouldDispatchReplyToCorrectManager() throws Exception {
        UUID processId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        long msgId = 123L;

        // Create a mock process
        ProcessMetadata<?, ?> process = new ProcessMetadata<>(
            "test_domain", processId, "TEST_PROCESS",
            new MapProcessState(Map.of()),
            ProcessStatus.WAITING_FOR_REPLY,
            null,
            Instant.now(), Instant.now(), null, null, null
        );

        // Setup message
        PgmqMessage message = new PgmqMessage(
            msgId, 1, Instant.now(), Instant.now().plusSeconds(30),
            Map.of(
                "command_id", commandId.toString(),
                "correlation_id", processId.toString(),
                "outcome", "SUCCESS",
                "result", Map.of("key", "value")
            )
        );

        // First call returns message, second returns empty to exit loop
        when(pgmqClient.read(eq("test_replies"), eq(30), anyInt()))
            .thenReturn(List.of(message))
            .thenReturn(List.of());

        when(processRepo.getById("test_domain", processId, jdbcTemplate))
            .thenReturn(Optional.of(process));

        router.start();

        // Wait for processing
        Thread.sleep(300);

        router.stopNow();

        // Verify manager was called
        verify(manager, timeout(1000)).handleReply(
            argThat(reply ->
                reply.commandId().equals(commandId) &&
                reply.correlationId().equals(processId) &&
                reply.outcome() == ReplyOutcome.SUCCESS
            ),
            eq(process),
            eq(jdbcTemplate)
        );

        // Verify message was deleted
        verify(pgmqClient, timeout(1000)).delete("test_replies", msgId);
    }

    @Test
    @DisplayName("should discard reply with no correlation id")
    void shouldDiscardReplyWithNoCorrelationId() throws Exception {
        long msgId = 456L;

        PgmqMessage message = new PgmqMessage(
            msgId, 1, Instant.now(), Instant.now().plusSeconds(30),
            Map.of(
                "command_id", UUID.randomUUID().toString(),
                "outcome", "SUCCESS"
                // No correlation_id
            )
        );

        when(pgmqClient.read(eq("test_replies"), eq(30), anyInt()))
            .thenReturn(List.of(message))
            .thenReturn(List.of());

        router.start();
        Thread.sleep(300);
        router.stopNow();

        // Verify message was deleted without calling manager
        verify(manager, never()).handleReply(any(), any(), any());
        verify(pgmqClient, timeout(1000)).delete("test_replies", msgId);
    }

    @Test
    @DisplayName("should discard reply for unknown process")
    void shouldDiscardReplyForUnknownProcess() throws Exception {
        UUID processId = UUID.randomUUID();
        long msgId = 789L;

        PgmqMessage message = new PgmqMessage(
            msgId, 1, Instant.now(), Instant.now().plusSeconds(30),
            Map.of(
                "command_id", UUID.randomUUID().toString(),
                "correlation_id", processId.toString(),
                "outcome", "SUCCESS"
            )
        );

        when(pgmqClient.read(eq("test_replies"), eq(30), anyInt()))
            .thenReturn(List.of(message))
            .thenReturn(List.of());

        when(processRepo.getById("test_domain", processId, jdbcTemplate))
            .thenReturn(Optional.empty());  // Process not found

        router.start();
        Thread.sleep(300);
        router.stopNow();

        verify(manager, never()).handleReply(any(), any(), any());
        verify(pgmqClient, timeout(1000)).delete("test_replies", msgId);
    }

    @Test
    @DisplayName("should discard reply for unknown process type")
    void shouldDiscardReplyForUnknownProcessType() throws Exception {
        UUID processId = UUID.randomUUID();
        long msgId = 101L;

        // Process with unknown type
        ProcessMetadata<?, ?> process = new ProcessMetadata<>(
            "test_domain", processId, "UNKNOWN_PROCESS",  // Unknown type
            new MapProcessState(Map.of()),
            ProcessStatus.WAITING_FOR_REPLY,
            null,
            Instant.now(), Instant.now(), null, null, null
        );

        PgmqMessage message = new PgmqMessage(
            msgId, 1, Instant.now(), Instant.now().plusSeconds(30),
            Map.of(
                "command_id", UUID.randomUUID().toString(),
                "correlation_id", processId.toString(),
                "outcome", "SUCCESS"
            )
        );

        when(pgmqClient.read(eq("test_replies"), eq(30), anyInt()))
            .thenReturn(List.of(message))
            .thenReturn(List.of());

        when(processRepo.getById("test_domain", processId, jdbcTemplate))
            .thenReturn(Optional.of(process));

        router.start();
        Thread.sleep(300);
        router.stopNow();

        verify(manager, never()).handleReply(any(), any(), any());
        verify(pgmqClient, timeout(1000)).delete("test_replies", msgId);
    }

    @Test
    @DisplayName("should not start twice")
    void shouldNotStartTwice() throws Exception {
        when(pgmqClient.read(anyString(), anyInt(), anyInt()))
            .thenReturn(List.of());

        router.start();
        router.start(); // Second call should be ignored

        Thread.sleep(100);
        router.stopNow();
    }

    @Test
    @DisplayName("should handle stopNow")
    void shouldHandleStopNow() {
        when(pgmqClient.read(anyString(), anyInt(), anyInt()))
            .thenReturn(List.of());

        router.start();
        router.stopNow();

        assertFalse(router.isRunning());
    }

    @Test
    @DisplayName("should handle UUID type in correlation_id")
    void shouldHandleUUIDTypeInCorrelationId() throws Exception {
        UUID processId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        long msgId = 222L;

        ProcessMetadata<?, ?> process = new ProcessMetadata<>(
            "test_domain", processId, "TEST_PROCESS",
            new MapProcessState(Map.of()),
            ProcessStatus.WAITING_FOR_REPLY,
            null,
            Instant.now(), Instant.now(), null, null, null
        );

        // Use UUID objects directly instead of strings
        PgmqMessage message = new PgmqMessage(
            msgId, 1, Instant.now(), Instant.now().plusSeconds(30),
            Map.of(
                "command_id", commandId,  // UUID type
                "correlation_id", processId,  // UUID type
                "outcome", "SUCCESS",
                "result", Map.of("key", "value")
            )
        );

        when(pgmqClient.read(eq("test_replies"), eq(30), anyInt()))
            .thenReturn(List.of(message))
            .thenReturn(List.of());

        when(processRepo.getById("test_domain", processId, jdbcTemplate))
            .thenReturn(Optional.of(process));

        router.start();
        Thread.sleep(300);
        router.stopNow();

        verify(manager, timeout(1000)).handleReply(
            argThat(reply ->
                reply.commandId().equals(commandId) &&
                reply.correlationId().equals(processId)
            ),
            eq(process),
            eq(jdbcTemplate)
        );
    }

    @Test
    @DisplayName("should handle failed reply outcome")
    void shouldHandleFailedReplyOutcome() throws Exception {
        UUID processId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        long msgId = 333L;

        ProcessMetadata<?, ?> process = new ProcessMetadata<>(
            "test_domain", processId, "TEST_PROCESS",
            new MapProcessState(Map.of()),
            ProcessStatus.WAITING_FOR_REPLY,
            null,
            Instant.now(), Instant.now(), null, null, null
        );

        PgmqMessage message = new PgmqMessage(
            msgId, 1, Instant.now(), Instant.now().plusSeconds(30),
            Map.of(
                "command_id", commandId.toString(),
                "correlation_id", processId.toString(),
                "outcome", "FAILED",
                "error_code", "ERR001",
                "error_message", "Something went wrong"
            )
        );

        when(pgmqClient.read(eq("test_replies"), eq(30), anyInt()))
            .thenReturn(List.of(message))
            .thenReturn(List.of());

        when(processRepo.getById("test_domain", processId, jdbcTemplate))
            .thenReturn(Optional.of(process));

        router.start();
        Thread.sleep(300);
        router.stopNow();

        verify(manager, timeout(1000)).handleReply(
            argThat(reply ->
                reply.outcome() == ReplyOutcome.FAILED &&
                "ERR001".equals(reply.errorCode()) &&
                "Something went wrong".equals(reply.errorMessage())
            ),
            eq(process),
            eq(jdbcTemplate)
        );
    }

    @Test
    @DisplayName("should handle canceled reply outcome")
    void shouldHandleCanceledReplyOutcome() throws Exception {
        UUID processId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        long msgId = 444L;

        ProcessMetadata<?, ?> process = new ProcessMetadata<>(
            "test_domain", processId, "TEST_PROCESS",
            new MapProcessState(Map.of()),
            ProcessStatus.WAITING_FOR_TSQ,
            null,
            Instant.now(), Instant.now(), null, null, null
        );

        PgmqMessage message = new PgmqMessage(
            msgId, 1, Instant.now(), Instant.now().plusSeconds(30),
            Map.of(
                "command_id", commandId.toString(),
                "correlation_id", processId.toString(),
                "outcome", "CANCELED"
            )
        );

        when(pgmqClient.read(eq("test_replies"), eq(30), anyInt()))
            .thenReturn(List.of(message))
            .thenReturn(List.of());

        when(processRepo.getById("test_domain", processId, jdbcTemplate))
            .thenReturn(Optional.of(process));

        router.start();
        Thread.sleep(300);
        router.stopNow();

        verify(manager, timeout(1000)).handleReply(
            argThat(reply -> reply.outcome() == ReplyOutcome.CANCELED),
            eq(process),
            eq(jdbcTemplate)
        );
    }

    @Test
    @DisplayName("should handle stopNow before start")
    void shouldHandleStopNowBeforeStart() {
        // Should not throw exception when stopNow is called without starting
        router.stopNow();
        assertFalse(router.isRunning());
    }

    @Test
    @DisplayName("should return completed future when stop called on non-running router")
    void shouldReturnCompletedFutureWhenStopCalledOnNonRunningRouter() throws Exception {
        var future = router.stop(Duration.ofSeconds(5));
        assertNotNull(future);
        future.get(1, TimeUnit.SECONDS);  // Should complete immediately
    }

    @Test
    @DisplayName("should process multiple messages in batch")
    void shouldProcessMultipleMessagesInBatch() throws Exception {
        UUID processId1 = UUID.randomUUID();
        UUID processId2 = UUID.randomUUID();
        UUID commandId1 = UUID.randomUUID();
        UUID commandId2 = UUID.randomUUID();

        ProcessMetadata<?, ?> process1 = new ProcessMetadata<>(
            "test_domain", processId1, "TEST_PROCESS",
            new MapProcessState(Map.of()),
            ProcessStatus.WAITING_FOR_REPLY,
            null,
            Instant.now(), Instant.now(), null, null, null
        );
        ProcessMetadata<?, ?> process2 = new ProcessMetadata<>(
            "test_domain", processId2, "TEST_PROCESS",
            new MapProcessState(Map.of()),
            ProcessStatus.WAITING_FOR_REPLY,
            null,
            Instant.now(), Instant.now(), null, null, null
        );

        PgmqMessage message1 = new PgmqMessage(
            1L, 1, Instant.now(), Instant.now().plusSeconds(30),
            Map.of(
                "command_id", commandId1.toString(),
                "correlation_id", processId1.toString(),
                "outcome", "SUCCESS"
            )
        );
        PgmqMessage message2 = new PgmqMessage(
            2L, 1, Instant.now(), Instant.now().plusSeconds(30),
            Map.of(
                "command_id", commandId2.toString(),
                "correlation_id", processId2.toString(),
                "outcome", "SUCCESS"
            )
        );

        when(pgmqClient.read(eq("test_replies"), eq(30), anyInt()))
            .thenReturn(List.of(message1, message2))
            .thenReturn(List.of());

        when(processRepo.getById("test_domain", processId1, jdbcTemplate))
            .thenReturn(Optional.of(process1));
        when(processRepo.getById("test_domain", processId2, jdbcTemplate))
            .thenReturn(Optional.of(process2));

        router.start();
        Thread.sleep(500);
        router.stopNow();

        verify(manager, timeout(1000).times(2)).handleReply(any(), any(), any());
        verify(pgmqClient, timeout(1000)).delete("test_replies", 1L);
        verify(pgmqClient, timeout(1000)).delete("test_replies", 2L);
    }
}
