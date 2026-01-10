package com.commandbus.worker.impl;

import com.commandbus.exception.HandlerNotFoundException;
import com.commandbus.exception.PermanentCommandException;
import com.commandbus.exception.TransientCommandException;
import com.commandbus.handler.HandlerRegistry;
import com.commandbus.model.*;
import com.commandbus.pgmq.PgmqClient;
import com.commandbus.policy.RetryPolicy;
import com.commandbus.repository.CommandRepository;
import com.commandbus.worker.Worker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DefaultWorker")
class DefaultWorkerTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private HandlerRegistry handlerRegistry;

    @Mock
    private PgmqClient pgmqClient;

    @Mock
    private CommandRepository commandRepository;

    private ObjectMapper objectMapper;

    private DefaultWorker worker;

    private static final String DOMAIN = "test";
    private static final String QUEUE_NAME = "test__commands";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        worker = new DefaultWorker(
            jdbcTemplate,
            objectMapper,
            DOMAIN,
            handlerRegistry,
            30, // visibilityTimeout
            50,  // pollIntervalMs - short for testing
            2,  // concurrency
            false, // useNotify
            RetryPolicy.defaultPolicy(),
            pgmqClient,
            commandRepository
        );
    }

    @Nested
    @DisplayName("Lifecycle Tests")
    class LifecycleTests {

        @Test
        @DisplayName("should return correct domain")
        void shouldReturnCorrectDomain() {
            assertEquals(DOMAIN, worker.domain());
        }

        @Test
        @DisplayName("should not be running initially")
        void shouldNotBeRunningInitially() {
            assertFalse(worker.isRunning());
        }

        @Test
        @DisplayName("should have zero in-flight count initially")
        void shouldHaveZeroInFlightCountInitially() {
            assertEquals(0, worker.inFlightCount());
        }

        @Test
        @DisplayName("should be running after start")
        void shouldBeRunningAfterStart() throws Exception {
            // Return empty list to avoid processing
            when(pgmqClient.read(anyString(), anyInt(), anyInt())).thenReturn(List.of());

            try {
                worker.start();
                Thread.sleep(100); // Give time for loop to start
                assertTrue(worker.isRunning());
            } finally {
                worker.stopNow();
            }
        }

        @Test
        @DisplayName("should not start twice")
        void shouldNotStartTwice() throws Exception {
            when(pgmqClient.read(anyString(), anyInt(), anyInt())).thenReturn(List.of());

            try {
                worker.start();
                worker.start(); // Second call should be ignored
                Thread.sleep(100);
                assertTrue(worker.isRunning());
            } finally {
                worker.stopNow();
            }
        }

        @Test
        @DisplayName("should stop immediately with stopNow")
        void shouldStopImmediatelyWithStopNow() throws Exception {
            when(pgmqClient.read(anyString(), anyInt(), anyInt())).thenReturn(List.of());

            worker.start();
            Thread.sleep(100);

            worker.stopNow();

            assertFalse(worker.isRunning());
        }

        @Test
        @DisplayName("should stop gracefully with timeout")
        void shouldStopGracefullyWithTimeout() throws Exception {
            when(pgmqClient.read(anyString(), anyInt(), anyInt())).thenReturn(List.of());

            worker.start();
            Thread.sleep(100);

            var future = worker.stop(Duration.ofSeconds(5));
            future.get(10, TimeUnit.SECONDS);

            assertFalse(worker.isRunning());
        }

        @Test
        @DisplayName("should complete immediately when not running")
        void shouldCompleteImmediatelyWhenNotRunning() throws Exception {
            var future = worker.stop(Duration.ofSeconds(5));

            future.get(1, TimeUnit.SECONDS);
            // Should complete without error
        }

        @Test
        @DisplayName("should not be running when stopping")
        void shouldNotBeRunningWhenStopping() throws Exception {
            when(pgmqClient.read(anyString(), anyInt(), anyInt())).thenReturn(List.of());

            worker.start();
            Thread.sleep(100);
            worker.stop(Duration.ofSeconds(5));

            assertFalse(worker.isRunning());
        }
    }

    @Nested
    @DisplayName("Message Processing Tests")
    class MessageProcessingTests {

        @Test
        @DisplayName("should process message successfully")
        void shouldProcessMessageSuccessfully() throws Exception {
            UUID commandId = UUID.randomUUID();
            long msgId = 123L;

            Map<String, Object> payload = Map.of(
                "domain", DOMAIN,
                "command_type", "TestCommand",
                "command_id", commandId.toString(),
                "data", Map.of("key", "value")
            );

            PgmqMessage message = new PgmqMessage(msgId, 0, Instant.now(), Instant.now(), payload);
            CommandMetadata metadata = new CommandMetadata(
                DOMAIN, commandId, "TestCommand", CommandStatus.IN_PROGRESS,
                1, 3, msgId, null, null, null, null, null,
                Instant.now(), Instant.now(), null
            );

            // First call returns the message, subsequent calls return empty
            when(pgmqClient.read(eq(QUEUE_NAME), anyInt(), anyInt()))
                .thenReturn(List.of(message))
                .thenReturn(List.of());

            when(commandRepository.spReceiveCommand(eq(DOMAIN), eq(commandId), eq(msgId), isNull()))
                .thenReturn(Optional.of(metadata));

            when(handlerRegistry.dispatch(any(), any())).thenReturn("result");

            when(commandRepository.spFinishCommand(
                eq(DOMAIN), eq(commandId), eq(CommandStatus.COMPLETED),
                eq(AuditEventType.COMPLETED), isNull(), isNull(), isNull(),
                anyString(), isNull()
            )).thenReturn(false);

            when(pgmqClient.delete(eq(QUEUE_NAME), eq(msgId))).thenReturn(true);

            try {
                worker.start();
                Thread.sleep(300); // Give time for processing
            } finally {
                worker.stopNow();
            }

            verify(handlerRegistry).dispatch(any(), any());
            verify(pgmqClient).delete(eq(QUEUE_NAME), eq(msgId));
            verify(commandRepository).spFinishCommand(
                eq(DOMAIN), eq(commandId), eq(CommandStatus.COMPLETED),
                eq(AuditEventType.COMPLETED), isNull(), isNull(), isNull(),
                anyString(), isNull()
            );
        }

        @Test
        @DisplayName("should skip command in terminal state")
        void shouldSkipCommandInTerminalState() throws Exception {
            UUID commandId = UUID.randomUUID();
            long msgId = 123L;

            Map<String, Object> payload = Map.of(
                "domain", DOMAIN,
                "command_type", "TestCommand",
                "command_id", commandId.toString()
            );

            PgmqMessage message = new PgmqMessage(msgId, 0, Instant.now(), Instant.now(), payload);

            when(pgmqClient.read(eq(QUEUE_NAME), anyInt(), anyInt()))
                .thenReturn(List.of(message))
                .thenReturn(List.of());

            when(commandRepository.spReceiveCommand(eq(DOMAIN), eq(commandId), eq(msgId), isNull()))
                .thenReturn(Optional.empty()); // Not receivable

            try {
                worker.start();
                Thread.sleep(300);
            } finally {
                worker.stopNow();
            }

            verify(handlerRegistry, never()).dispatch(any(), any());
        }

        @Test
        @DisplayName("should handle transient error with retry")
        void shouldHandleTransientErrorWithRetry() throws Exception {
            UUID commandId = UUID.randomUUID();
            long msgId = 123L;

            Map<String, Object> payload = Map.of(
                "domain", DOMAIN,
                "command_type", "TestCommand",
                "command_id", commandId.toString()
            );

            PgmqMessage message = new PgmqMessage(msgId, 0, Instant.now(), Instant.now(), payload);
            CommandMetadata metadata = new CommandMetadata(
                DOMAIN, commandId, "TestCommand", CommandStatus.IN_PROGRESS,
                1, 3, msgId, null, null, null, null, null,
                Instant.now(), Instant.now(), null
            );

            when(pgmqClient.read(eq(QUEUE_NAME), anyInt(), anyInt()))
                .thenReturn(List.of(message))
                .thenReturn(List.of());

            when(commandRepository.spReceiveCommand(eq(DOMAIN), eq(commandId), eq(msgId), isNull()))
                .thenReturn(Optional.of(metadata));

            when(handlerRegistry.dispatch(any(), any()))
                .thenThrow(new TransientCommandException("TEMP_ERROR", "Temporary failure"));

            when(commandRepository.get(eq(DOMAIN), eq(commandId)))
                .thenReturn(Optional.of(metadata));

            when(commandRepository.spFailCommand(
                eq(DOMAIN), eq(commandId), eq("TRANSIENT"), eq("TEMP_ERROR"),
                eq("Temporary failure"), eq(1), eq(3), eq(msgId)
            )).thenReturn(true);

            when(pgmqClient.setVisibilityTimeout(eq(QUEUE_NAME), eq(msgId), anyInt()))
                .thenReturn(true);

            try {
                worker.start();
                Thread.sleep(300);
            } finally {
                worker.stopNow();
            }

            verify(commandRepository).spFailCommand(
                eq(DOMAIN), eq(commandId), eq("TRANSIENT"), eq("TEMP_ERROR"),
                eq("Temporary failure"), eq(1), eq(3), eq(msgId)
            );
            verify(pgmqClient).setVisibilityTimeout(eq(QUEUE_NAME), eq(msgId), anyInt());
        }

        @Test
        @DisplayName("should handle transient error with retries exhausted")
        void shouldHandleTransientErrorWithRetriesExhausted() throws Exception {
            UUID commandId = UUID.randomUUID();
            long msgId = 123L;

            Map<String, Object> payload = Map.of(
                "domain", DOMAIN,
                "command_type", "TestCommand",
                "command_id", commandId.toString()
            );

            PgmqMessage message = new PgmqMessage(msgId, 0, Instant.now(), Instant.now(), payload);
            CommandMetadata metadata = new CommandMetadata(
                DOMAIN, commandId, "TestCommand", CommandStatus.IN_PROGRESS,
                3, 3, msgId, null, null, null, null, null, // attempts = maxAttempts
                Instant.now(), Instant.now(), null
            );

            when(pgmqClient.read(eq(QUEUE_NAME), anyInt(), anyInt()))
                .thenReturn(List.of(message))
                .thenReturn(List.of());

            when(commandRepository.spReceiveCommand(eq(DOMAIN), eq(commandId), eq(msgId), isNull()))
                .thenReturn(Optional.of(metadata));

            when(handlerRegistry.dispatch(any(), any()))
                .thenThrow(new TransientCommandException("TEMP_ERROR", "Temporary failure"));

            when(commandRepository.get(eq(DOMAIN), eq(commandId)))
                .thenReturn(Optional.of(metadata));

            when(pgmqClient.archive(eq(QUEUE_NAME), eq(msgId))).thenReturn(true);

            when(commandRepository.spFinishCommand(
                eq(DOMAIN), eq(commandId), eq(CommandStatus.IN_TROUBLESHOOTING_QUEUE),
                eq(AuditEventType.MOVED_TO_TSQ), eq("TRANSIENT"), eq("TEMP_ERROR"),
                eq("Temporary failure"), isNull(), isNull()
            )).thenReturn(false);

            try {
                worker.start();
                Thread.sleep(300);
            } finally {
                worker.stopNow();
            }

            verify(pgmqClient).archive(eq(QUEUE_NAME), eq(msgId));
            verify(commandRepository).spFinishCommand(
                eq(DOMAIN), eq(commandId), eq(CommandStatus.IN_TROUBLESHOOTING_QUEUE),
                eq(AuditEventType.MOVED_TO_TSQ), eq("TRANSIENT"), eq("TEMP_ERROR"),
                eq("Temporary failure"), isNull(), isNull()
            );
        }

        @Test
        @DisplayName("should handle permanent error")
        void shouldHandlePermanentError() throws Exception {
            UUID commandId = UUID.randomUUID();
            long msgId = 123L;

            Map<String, Object> payload = Map.of(
                "domain", DOMAIN,
                "command_type", "TestCommand",
                "command_id", commandId.toString()
            );

            PgmqMessage message = new PgmqMessage(msgId, 0, Instant.now(), Instant.now(), payload);
            CommandMetadata metadata = new CommandMetadata(
                DOMAIN, commandId, "TestCommand", CommandStatus.IN_PROGRESS,
                1, 3, msgId, null, null, null, null, null,
                Instant.now(), Instant.now(), null
            );

            when(pgmqClient.read(eq(QUEUE_NAME), anyInt(), anyInt()))
                .thenReturn(List.of(message))
                .thenReturn(List.of());

            when(commandRepository.spReceiveCommand(eq(DOMAIN), eq(commandId), eq(msgId), isNull()))
                .thenReturn(Optional.of(metadata));

            when(handlerRegistry.dispatch(any(), any()))
                .thenThrow(new PermanentCommandException("PERM_ERROR", "Permanent failure"));

            when(commandRepository.get(eq(DOMAIN), eq(commandId)))
                .thenReturn(Optional.of(metadata));

            when(pgmqClient.archive(eq(QUEUE_NAME), eq(msgId))).thenReturn(true);

            when(commandRepository.spFinishCommand(
                eq(DOMAIN), eq(commandId), eq(CommandStatus.IN_TROUBLESHOOTING_QUEUE),
                eq(AuditEventType.MOVED_TO_TSQ), eq("PERMANENT"), eq("PERM_ERROR"),
                eq("Permanent failure"), isNull(), isNull()
            )).thenReturn(false);

            try {
                worker.start();
                Thread.sleep(300);
            } finally {
                worker.stopNow();
            }

            verify(pgmqClient).archive(eq(QUEUE_NAME), eq(msgId));
            verify(commandRepository).spFinishCommand(
                eq(DOMAIN), eq(commandId), eq(CommandStatus.IN_TROUBLESHOOTING_QUEUE),
                eq(AuditEventType.MOVED_TO_TSQ), eq("PERMANENT"), eq("PERM_ERROR"),
                eq("Permanent failure"), isNull(), isNull()
            );
        }

        @Test
        @DisplayName("should handle unknown exception as transient")
        void shouldHandleUnknownExceptionAsTransient() throws Exception {
            UUID commandId = UUID.randomUUID();
            long msgId = 123L;

            Map<String, Object> payload = Map.of(
                "domain", DOMAIN,
                "command_type", "TestCommand",
                "command_id", commandId.toString()
            );

            PgmqMessage message = new PgmqMessage(msgId, 0, Instant.now(), Instant.now(), payload);
            CommandMetadata metadata = new CommandMetadata(
                DOMAIN, commandId, "TestCommand", CommandStatus.IN_PROGRESS,
                1, 3, msgId, null, null, null, null, null,
                Instant.now(), Instant.now(), null
            );

            when(pgmqClient.read(eq(QUEUE_NAME), anyInt(), anyInt()))
                .thenReturn(List.of(message))
                .thenReturn(List.of());

            when(commandRepository.spReceiveCommand(eq(DOMAIN), eq(commandId), eq(msgId), isNull()))
                .thenReturn(Optional.of(metadata));

            when(handlerRegistry.dispatch(any(), any()))
                .thenThrow(new RuntimeException("Unknown error"));

            when(commandRepository.get(eq(DOMAIN), eq(commandId)))
                .thenReturn(Optional.of(metadata));

            when(commandRepository.spFailCommand(
                eq(DOMAIN), eq(commandId), eq("TRANSIENT"), eq("INTERNAL_ERROR"),
                eq("Unknown error"), eq(1), eq(3), eq(msgId)
            )).thenReturn(true);

            when(pgmqClient.setVisibilityTimeout(eq(QUEUE_NAME), eq(msgId), anyInt()))
                .thenReturn(true);

            try {
                worker.start();
                Thread.sleep(300);
            } finally {
                worker.stopNow();
            }

            verify(commandRepository).spFailCommand(
                eq(DOMAIN), eq(commandId), eq("TRANSIENT"), eq("INTERNAL_ERROR"),
                eq("Unknown error"), eq(1), eq(3), eq(msgId)
            );
        }

        @Test
        @DisplayName("should send reply on success with reply_to")
        void shouldSendReplyOnSuccessWithReplyTo() throws Exception {
            UUID commandId = UUID.randomUUID();
            long msgId = 123L;

            Map<String, Object> payload = Map.of(
                "domain", DOMAIN,
                "command_type", "TestCommand",
                "command_id", commandId.toString(),
                "reply_to", "reply_queue"
            );

            PgmqMessage message = new PgmqMessage(msgId, 0, Instant.now(), Instant.now(), payload);
            CommandMetadata metadata = new CommandMetadata(
                DOMAIN, commandId, "TestCommand", CommandStatus.IN_PROGRESS,
                1, 3, msgId, null, "reply_queue", null, null, null,
                Instant.now(), Instant.now(), null
            );

            when(pgmqClient.read(eq(QUEUE_NAME), anyInt(), anyInt()))
                .thenReturn(List.of(message))
                .thenReturn(List.of());

            when(commandRepository.spReceiveCommand(eq(DOMAIN), eq(commandId), eq(msgId), isNull()))
                .thenReturn(Optional.of(metadata));

            when(handlerRegistry.dispatch(any(), any())).thenReturn("result");

            when(commandRepository.spFinishCommand(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(false);

            when(pgmqClient.delete(eq(QUEUE_NAME), eq(msgId))).thenReturn(true);
            when(pgmqClient.send(eq("reply_queue"), anyMap())).thenReturn(1L);

            try {
                worker.start();
                Thread.sleep(300);
            } finally {
                worker.stopNow();
            }

            verify(pgmqClient).send(eq("reply_queue"), argThat(reply -> {
                return reply.get("command_id").equals(commandId.toString())
                    && reply.get("outcome").equals("SUCCESS");
            }));
        }

        @Test
        @DisplayName("should send failure reply on permanent error with reply_to")
        void shouldSendFailureReplyOnPermanentErrorWithReplyTo() throws Exception {
            UUID commandId = UUID.randomUUID();
            long msgId = 123L;

            Map<String, Object> payload = Map.of(
                "domain", DOMAIN,
                "command_type", "TestCommand",
                "command_id", commandId.toString()
            );

            PgmqMessage message = new PgmqMessage(msgId, 0, Instant.now(), Instant.now(), payload);
            CommandMetadata metadata = new CommandMetadata(
                DOMAIN, commandId, "TestCommand", CommandStatus.IN_PROGRESS,
                1, 3, msgId, null, "reply_queue", null, null, null,
                Instant.now(), Instant.now(), null
            );

            when(pgmqClient.read(eq(QUEUE_NAME), anyInt(), anyInt()))
                .thenReturn(List.of(message))
                .thenReturn(List.of());

            when(commandRepository.spReceiveCommand(eq(DOMAIN), eq(commandId), eq(msgId), isNull()))
                .thenReturn(Optional.of(metadata));

            when(handlerRegistry.dispatch(any(), any()))
                .thenThrow(new PermanentCommandException("PERM_ERROR", "Permanent failure"));

            when(commandRepository.get(eq(DOMAIN), eq(commandId)))
                .thenReturn(Optional.of(metadata));

            when(pgmqClient.archive(eq(QUEUE_NAME), eq(msgId))).thenReturn(true);
            when(commandRepository.spFinishCommand(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(false);
            when(pgmqClient.send(eq("reply_queue"), anyMap())).thenReturn(1L);

            try {
                worker.start();
                Thread.sleep(300);
            } finally {
                worker.stopNow();
            }

            verify(pgmqClient).send(eq("reply_queue"), argThat(reply -> {
                return reply.get("command_id").equals(commandId.toString())
                    && reply.get("outcome").equals("FAILED")
                    && reply.get("error_code").equals("PERM_ERROR");
            }));
        }

        @Test
        @DisplayName("should handle batch completion callback")
        void shouldHandleBatchCompletionCallback() throws Exception {
            UUID commandId = UUID.randomUUID();
            UUID batchId = UUID.randomUUID();
            long msgId = 123L;

            Map<String, Object> payload = Map.of(
                "domain", DOMAIN,
                "command_type", "TestCommand",
                "command_id", commandId.toString()
            );

            PgmqMessage message = new PgmqMessage(msgId, 0, Instant.now(), Instant.now(), payload);
            CommandMetadata metadata = new CommandMetadata(
                DOMAIN, commandId, "TestCommand", CommandStatus.IN_PROGRESS,
                1, 3, msgId, null, null, null, null, null,
                Instant.now(), Instant.now(), batchId
            );

            when(pgmqClient.read(eq(QUEUE_NAME), anyInt(), anyInt()))
                .thenReturn(List.of(message))
                .thenReturn(List.of());

            when(commandRepository.spReceiveCommand(eq(DOMAIN), eq(commandId), eq(msgId), isNull()))
                .thenReturn(Optional.of(metadata));

            when(handlerRegistry.dispatch(any(), any())).thenReturn("result");

            // Return true to indicate batch is complete
            when(commandRepository.spFinishCommand(
                eq(DOMAIN), eq(commandId), eq(CommandStatus.COMPLETED),
                eq(AuditEventType.COMPLETED), isNull(), isNull(), isNull(),
                anyString(), eq(batchId)
            )).thenReturn(true);

            when(pgmqClient.delete(eq(QUEUE_NAME), eq(msgId))).thenReturn(true);

            try {
                worker.start();
                Thread.sleep(300);
            } finally {
                worker.stopNow();
            }

            // Batch completion callback is invoked (logs debug message)
            verify(commandRepository).spFinishCommand(
                eq(DOMAIN), eq(commandId), eq(CommandStatus.COMPLETED),
                eq(AuditEventType.COMPLETED), isNull(), isNull(), isNull(),
                anyString(), eq(batchId)
            );
        }

        @Test
        @DisplayName("should handle missing command in error handling")
        void shouldHandleMissingCommandInErrorHandling() throws Exception {
            UUID commandId = UUID.randomUUID();
            long msgId = 123L;

            Map<String, Object> payload = Map.of(
                "domain", DOMAIN,
                "command_type", "TestCommand",
                "command_id", commandId.toString()
            );

            PgmqMessage message = new PgmqMessage(msgId, 0, Instant.now(), Instant.now(), payload);
            CommandMetadata metadata = new CommandMetadata(
                DOMAIN, commandId, "TestCommand", CommandStatus.IN_PROGRESS,
                1, 3, msgId, null, null, null, null, null,
                Instant.now(), Instant.now(), null
            );

            when(pgmqClient.read(eq(QUEUE_NAME), anyInt(), anyInt()))
                .thenReturn(List.of(message))
                .thenReturn(List.of());

            when(commandRepository.spReceiveCommand(eq(DOMAIN), eq(commandId), eq(msgId), isNull()))
                .thenReturn(Optional.of(metadata));

            when(handlerRegistry.dispatch(any(), any()))
                .thenThrow(new TransientCommandException("TEMP_ERROR", "Temporary failure"));

            when(commandRepository.get(eq(DOMAIN), eq(commandId)))
                .thenReturn(Optional.empty()); // Command not found

            try {
                worker.start();
                Thread.sleep(300);
            } finally {
                worker.stopNow();
            }

            // Should not call spFailCommand when command not found
            verify(commandRepository, never()).spFailCommand(any(), any(), any(), any(), any(), anyInt(), anyInt(), anyLong());
        }

        @Test
        @DisplayName("should handle correlation ID in message")
        void shouldHandleCorrelationIdInMessage() throws Exception {
            UUID commandId = UUID.randomUUID();
            UUID correlationId = UUID.randomUUID();
            long msgId = 123L;

            Map<String, Object> payload = Map.of(
                "domain", DOMAIN,
                "command_type", "TestCommand",
                "command_id", commandId.toString(),
                "correlation_id", correlationId.toString(),
                "data", Map.of("key", "value")
            );

            PgmqMessage message = new PgmqMessage(msgId, 0, Instant.now(), Instant.now(), payload);
            CommandMetadata metadata = new CommandMetadata(
                DOMAIN, commandId, "TestCommand", CommandStatus.IN_PROGRESS,
                1, 3, msgId, correlationId, "reply_queue", null, null, null,
                Instant.now(), Instant.now(), null
            );

            when(pgmqClient.read(eq(QUEUE_NAME), anyInt(), anyInt()))
                .thenReturn(List.of(message))
                .thenReturn(List.of());

            when(commandRepository.spReceiveCommand(eq(DOMAIN), eq(commandId), eq(msgId), isNull()))
                .thenReturn(Optional.of(metadata));

            when(handlerRegistry.dispatch(any(), any())).thenReturn("result");
            when(commandRepository.spFinishCommand(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(false);
            when(pgmqClient.delete(eq(QUEUE_NAME), eq(msgId))).thenReturn(true);
            when(pgmqClient.send(eq("reply_queue"), anyMap())).thenReturn(1L);

            try {
                worker.start();
                Thread.sleep(300);
            } finally {
                worker.stopNow();
            }

            verify(pgmqClient).send(eq("reply_queue"), argThat(reply ->
                reply.get("correlation_id").equals(correlationId.toString())
            ));
        }
    }

    @Nested
    @DisplayName("Builder Tests")
    class BuilderTests {

        @Test
        @DisplayName("should create worker via builder")
        void shouldCreateWorkerViaBuilder() {
            Worker builderWorker = Worker.builder()
                .jdbcTemplate(jdbcTemplate)
                .domain("payments")
                .handlerRegistry(handlerRegistry)
                .objectMapper(objectMapper)
                .visibilityTimeout(45)
                .pollIntervalMs(500)
                .concurrency(4)
                .build();

            assertEquals("payments", builderWorker.domain());
        }

        @Test
        @DisplayName("should use default retry policy")
        void shouldUseDefaultRetryPolicy() {
            Worker builderWorker = Worker.builder()
                .jdbcTemplate(jdbcTemplate)
                .domain("payments")
                .handlerRegistry(handlerRegistry)
                .build();

            assertNotNull(builderWorker);
        }

        @Test
        @DisplayName("should handle custom retry policy")
        void shouldHandleCustomRetryPolicy() {
            Worker builderWorker = Worker.builder()
                .jdbcTemplate(jdbcTemplate)
                .domain("payments")
                .handlerRegistry(handlerRegistry)
                .retryPolicy(RetryPolicy.noRetry())
                .build();

            assertNotNull(builderWorker);
        }
    }
}
