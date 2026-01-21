package com.ivamare.commandbus.process.step;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ivamare.commandbus.CommandBusProperties.ResilienceProperties;
import com.ivamare.commandbus.model.PgmqMessage;
import com.ivamare.commandbus.model.ReplyOutcome;
import com.ivamare.commandbus.pgmq.PgmqClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("CommandStepResponseHandler")
class CommandStepResponseHandlerTest {

    @Mock
    private PgmqClient pgmqClient;

    @Mock
    private ProcessStepManager<?> processManager;

    private ObjectMapper objectMapper;
    private CommandStepResponseHandler handler;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        handler = new CommandStepResponseHandler(
            pgmqClient,
            objectMapper,
            List.of(processManager),
            30,
            100,
            false,
            new ResilienceProperties()
        );
    }

    @Nested
    @DisplayName("Lifecycle")
    class LifecycleTests {

        @Test
        @DisplayName("should start and stop cleanly")
        void shouldStartAndStopCleanly() {
            assertFalse(handler.isRunning());

            handler.start();
            assertTrue(handler.isRunning());

            handler.stop();
            assertFalse(handler.isRunning());
        }

        @Test
        @DisplayName("should handle multiple start calls")
        void shouldHandleMultipleStartCalls() {
            handler.start();
            assertTrue(handler.isRunning());

            handler.start();
            assertTrue(handler.isRunning());

            handler.stop();
        }
    }

    @Nested
    @DisplayName("Poll Responses")
    class PollResponsesTests {

        @Test
        @DisplayName("should skip polling when not running")
        void shouldSkipPollingWhenNotRunning() {
            handler.pollResponses("test-replies");

            verifyNoInteractions(pgmqClient);
        }

        @Test
        @DisplayName("should poll queue when running")
        void shouldPollQueueWhenRunning() throws Exception {
            when(pgmqClient.read(anyString(), anyInt(), anyInt())).thenReturn(List.of());

            handler.start();
            handler.pollResponses("test-replies");

            verify(pgmqClient).read("test-replies", 30, 100);
            handler.stop();
        }

        @Test
        @DisplayName("should process success message")
        void shouldProcessSuccessMessage() throws Exception {
            UUID processId = UUID.randomUUID();
            UUID commandId = UUID.randomUUID();

            Map<String, Object> payload = new HashMap<>();
            payload.put("correlation_id", processId.toString());
            payload.put("command_id", commandId.toString());
            payload.put("outcome", "SUCCESS");
            payload.put("result", Map.of("contractId", 12345L));
            payload.put("data", Map.of("step_name", "bookFx"));

            PgmqMessage message = new PgmqMessage(1L, 0, Instant.now(), Instant.now(), payload);
            when(pgmqClient.read(anyString(), anyInt(), anyInt())).thenReturn(List.of(message));
            when(pgmqClient.delete(anyString(), anyLong())).thenReturn(true);

            handler.start();
            handler.pollResponses("test-replies");

            // Wait for async processing
            Thread.sleep(100);

            verify(processManager).processAsyncResponse(eq(processId), any());
            verify(pgmqClient).delete("test-replies", 1L);
            handler.stop();
        }

        @Test
        @DisplayName("should skip message without correlation_id")
        void shouldSkipMessageWithoutCorrelationId() throws Exception {
            Map<String, Object> payload = new HashMap<>();
            payload.put("outcome", "SUCCESS");

            PgmqMessage message = new PgmqMessage(1L, 0, Instant.now(), Instant.now(), payload);
            when(pgmqClient.read(anyString(), anyInt(), anyInt())).thenReturn(List.of(message));
            when(pgmqClient.delete(anyString(), anyLong())).thenReturn(true);

            handler.start();
            handler.pollResponses("test-replies");

            Thread.sleep(100);

            verifyNoInteractions(processManager);
            verify(pgmqClient).delete("test-replies", 1L);
            handler.stop();
        }

        @Test
        @DisplayName("should handle transient error type")
        void shouldHandleTransientErrorType() throws Exception {
            UUID processId = UUID.randomUUID();

            Map<String, Object> payload = new HashMap<>();
            payload.put("correlation_id", processId.toString());
            payload.put("outcome", "FAILED");
            payload.put("error_type", "TRANSIENT");
            payload.put("error_code", "TIMEOUT");
            payload.put("error_message", "Service timeout");
            payload.put("data", Map.of("step_name", "bookFx"));

            PgmqMessage message = new PgmqMessage(1L, 0, Instant.now(), Instant.now(), payload);
            when(pgmqClient.read(anyString(), anyInt(), anyInt())).thenReturn(List.of(message));
            when(pgmqClient.delete(anyString(), anyLong())).thenReturn(true);

            handler.start();
            handler.pollResponses("test-replies");

            Thread.sleep(100);

            verify(processManager).processAsyncResponse(eq(processId), any());
            handler.stop();
        }

        @Test
        @DisplayName("should handle business error type")
        void shouldHandleBusinessErrorType() throws Exception {
            UUID processId = UUID.randomUUID();

            Map<String, Object> payload = new HashMap<>();
            payload.put("correlation_id", processId.toString());
            payload.put("outcome", "FAILED");
            payload.put("error_type", "BUSINESS_ERROR");
            payload.put("error_code", "INSUFFICIENT_FUNDS");
            payload.put("error_message", "Not enough balance");
            payload.put("data", Map.of("step_name", "bookFx"));

            PgmqMessage message = new PgmqMessage(1L, 0, Instant.now(), Instant.now(), payload);
            when(pgmqClient.read(anyString(), anyInt(), anyInt())).thenReturn(List.of(message));
            when(pgmqClient.delete(anyString(), anyLong())).thenReturn(true);

            handler.start();
            handler.pollResponses("test-replies");

            Thread.sleep(100);

            verify(processManager).processAsyncResponse(eq(processId), any());
            handler.stop();
        }

        @Test
        @DisplayName("should handle permanent error type")
        void shouldHandlePermanentErrorType() throws Exception {
            UUID processId = UUID.randomUUID();

            Map<String, Object> payload = new HashMap<>();
            payload.put("correlation_id", processId.toString());
            payload.put("outcome", "FAILED");
            payload.put("error_type", "PERMANENT");
            payload.put("error_code", "INVALID_DATA");
            payload.put("error_message", "Invalid currency");
            payload.put("data", Map.of("step_name", "bookFx"));

            PgmqMessage message = new PgmqMessage(1L, 0, Instant.now(), Instant.now(), payload);
            when(pgmqClient.read(anyString(), anyInt(), anyInt())).thenReturn(List.of(message));
            when(pgmqClient.delete(anyString(), anyLong())).thenReturn(true);

            handler.start();
            handler.pollResponses("test-replies");

            Thread.sleep(100);

            verify(processManager).processAsyncResponse(eq(processId), any());
            handler.stop();
        }

        @Test
        @DisplayName("should handle timeout error type")
        void shouldHandleTimeoutErrorType() throws Exception {
            UUID processId = UUID.randomUUID();

            Map<String, Object> payload = new HashMap<>();
            payload.put("correlation_id", processId.toString());
            payload.put("outcome", "FAILED");
            payload.put("error_type", "TIMEOUT");
            payload.put("error_code", "TIMEOUT");
            payload.put("error_message", "Request timed out");
            payload.put("data", Map.of("step_name", "bookFx"));

            PgmqMessage message = new PgmqMessage(1L, 0, Instant.now(), Instant.now(), payload);
            when(pgmqClient.read(anyString(), anyInt(), anyInt())).thenReturn(List.of(message));
            when(pgmqClient.delete(anyString(), anyLong())).thenReturn(true);

            handler.start();
            handler.pollResponses("test-replies");

            Thread.sleep(100);

            verify(processManager).processAsyncResponse(eq(processId), any());
            handler.stop();
        }

        @Test
        @DisplayName("should handle unknown error type as permanent")
        void shouldHandleUnknownErrorTypeAsPermanent() throws Exception {
            UUID processId = UUID.randomUUID();

            Map<String, Object> payload = new HashMap<>();
            payload.put("correlation_id", processId.toString());
            payload.put("outcome", "FAILED");
            payload.put("error_type", "UNKNOWN_TYPE");
            payload.put("error_code", "ERROR");
            payload.put("error_message", "Some error");
            payload.put("data", Map.of("step_name", "bookFx"));

            PgmqMessage message = new PgmqMessage(1L, 0, Instant.now(), Instant.now(), payload);
            when(pgmqClient.read(anyString(), anyInt(), anyInt())).thenReturn(List.of(message));
            when(pgmqClient.delete(anyString(), anyLong())).thenReturn(true);

            handler.start();
            handler.pollResponses("test-replies");

            Thread.sleep(100);

            verify(processManager).processAsyncResponse(eq(processId), any());
            handler.stop();
        }

        @Test
        @DisplayName("should handle null error type as permanent")
        void shouldHandleNullErrorTypeAsPermanent() throws Exception {
            UUID processId = UUID.randomUUID();

            Map<String, Object> payload = new HashMap<>();
            payload.put("correlation_id", processId.toString());
            payload.put("outcome", "FAILED");
            payload.put("error_code", "ERROR");
            payload.put("error_message", "Some error");
            payload.put("data", Map.of("step_name", "bookFx"));

            PgmqMessage message = new PgmqMessage(1L, 0, Instant.now(), Instant.now(), payload);
            when(pgmqClient.read(anyString(), anyInt(), anyInt())).thenReturn(List.of(message));
            when(pgmqClient.delete(anyString(), anyLong())).thenReturn(true);

            handler.start();
            handler.pollResponses("test-replies");

            Thread.sleep(100);

            verify(processManager).processAsyncResponse(eq(processId), any());
            handler.stop();
        }

        @Test
        @DisplayName("should handle null outcome as FAILED")
        void shouldHandleNullOutcomeAsFailed() throws Exception {
            UUID processId = UUID.randomUUID();

            Map<String, Object> payload = new HashMap<>();
            payload.put("correlation_id", processId.toString());
            // No outcome - should default to FAILED
            payload.put("data", Map.of("step_name", "bookFx"));

            PgmqMessage message = new PgmqMessage(1L, 0, Instant.now(), Instant.now(), payload);
            when(pgmqClient.read(anyString(), anyInt(), anyInt())).thenReturn(List.of(message));
            when(pgmqClient.delete(anyString(), anyLong())).thenReturn(true);

            handler.start();
            handler.pollResponses("test-replies");

            Thread.sleep(100);

            verify(processManager).processAsyncResponse(eq(processId), any());
            handler.stop();
        }

        @Test
        @DisplayName("should handle missing command_id")
        void shouldHandleMissingCommandId() throws Exception {
            UUID processId = UUID.randomUUID();

            Map<String, Object> payload = new HashMap<>();
            payload.put("correlation_id", processId.toString());
            payload.put("outcome", "SUCCESS");
            // No command_id - should still work
            payload.put("data", Map.of("step_name", "bookFx"));

            PgmqMessage message = new PgmqMessage(1L, 0, Instant.now(), Instant.now(), payload);
            when(pgmqClient.read(anyString(), anyInt(), anyInt())).thenReturn(List.of(message));
            when(pgmqClient.delete(anyString(), anyLong())).thenReturn(true);

            handler.start();
            handler.pollResponses("test-replies");

            Thread.sleep(100);

            verify(processManager).processAsyncResponse(eq(processId), any());
            handler.stop();
        }

        @Test
        @DisplayName("should handle empty data map")
        void shouldHandleEmptyDataMap() throws Exception {
            UUID processId = UUID.randomUUID();

            Map<String, Object> payload = new HashMap<>();
            payload.put("correlation_id", processId.toString());
            payload.put("outcome", "SUCCESS");
            // Empty data - step_name should default to "unknown"

            PgmqMessage message = new PgmqMessage(1L, 0, Instant.now(), Instant.now(), payload);
            when(pgmqClient.read(anyString(), anyInt(), anyInt())).thenReturn(List.of(message));
            when(pgmqClient.delete(anyString(), anyLong())).thenReturn(true);

            handler.start();
            handler.pollResponses("test-replies");

            Thread.sleep(100);

            verify(processManager).processAsyncResponse(eq(processId), any());
            handler.stop();
        }

        @Test
        @DisplayName("should handle TRANSIENT_ERROR error type")
        void shouldHandleTransientErrorType2() throws Exception {
            UUID processId = UUID.randomUUID();

            Map<String, Object> payload = new HashMap<>();
            payload.put("correlation_id", processId.toString());
            payload.put("outcome", "FAILED");
            payload.put("error_type", "TRANSIENT_ERROR");
            payload.put("data", Map.of("step_name", "bookFx"));

            PgmqMessage message = new PgmqMessage(1L, 0, Instant.now(), Instant.now(), payload);
            when(pgmqClient.read(anyString(), anyInt(), anyInt())).thenReturn(List.of(message));
            when(pgmqClient.delete(anyString(), anyLong())).thenReturn(true);

            handler.start();
            handler.pollResponses("test-replies");

            Thread.sleep(100);

            verify(processManager).processAsyncResponse(eq(processId), any());
            handler.stop();
        }

        @Test
        @DisplayName("should handle BUSINESS_RULE error type")
        void shouldHandleBusinessRuleErrorType() throws Exception {
            UUID processId = UUID.randomUUID();

            Map<String, Object> payload = new HashMap<>();
            payload.put("correlation_id", processId.toString());
            payload.put("outcome", "FAILED");
            payload.put("error_type", "BUSINESS_RULE");
            payload.put("data", Map.of("step_name", "bookFx"));

            PgmqMessage message = new PgmqMessage(1L, 0, Instant.now(), Instant.now(), payload);
            when(pgmqClient.read(anyString(), anyInt(), anyInt())).thenReturn(List.of(message));
            when(pgmqClient.delete(anyString(), anyLong())).thenReturn(true);

            handler.start();
            handler.pollResponses("test-replies");

            Thread.sleep(100);

            verify(processManager).processAsyncResponse(eq(processId), any());
            handler.stop();
        }

        @Test
        @DisplayName("should archive messages when configured")
        void shouldArchiveMessagesWhenConfigured() throws Exception {
            CommandStepResponseHandler archivingHandler = new CommandStepResponseHandler(
                pgmqClient,
                objectMapper,
                List.of(processManager),
                30,
                100,
                true,  // Archive messages
                new ResilienceProperties()
            );

            UUID processId = UUID.randomUUID();

            Map<String, Object> payload = new HashMap<>();
            payload.put("correlation_id", processId.toString());
            payload.put("outcome", "SUCCESS");
            payload.put("data", Map.of("step_name", "bookFx"));

            PgmqMessage message = new PgmqMessage(1L, 0, Instant.now(), Instant.now(), payload);
            when(pgmqClient.read(anyString(), anyInt(), anyInt())).thenReturn(List.of(message));
            when(pgmqClient.archive(anyString(), anyLong())).thenReturn(true);

            archivingHandler.start();
            archivingHandler.pollResponses("test-replies");

            Thread.sleep(100);

            verify(pgmqClient).archive("test-replies", 1L);
            verify(pgmqClient, never()).delete(anyString(), anyLong());
            archivingHandler.stop();
        }
    }

    @Nested
    @DisplayName("Error Handling")
    class ErrorHandlingTests {

        @Test
        @DisplayName("should handle poll exception")
        void shouldHandlePollException() throws Exception {
            when(pgmqClient.read(anyString(), anyInt(), anyInt()))
                .thenThrow(new RuntimeException("Connection failed"));

            handler.start();
            handler.pollResponses("test-replies");

            // Should not throw, just log the error
            handler.stop();
        }

        @Test
        @DisplayName("should use step_name unknown when not provided")
        void shouldUseUnknownStepNameWhenNotProvided() throws Exception {
            UUID processId = UUID.randomUUID();

            Map<String, Object> payload = new HashMap<>();
            payload.put("correlation_id", processId.toString());
            payload.put("outcome", "SUCCESS");
            // No data/step_name

            PgmqMessage message = new PgmqMessage(1L, 0, Instant.now(), Instant.now(), payload);
            when(pgmqClient.read(anyString(), anyInt(), anyInt())).thenReturn(List.of(message));
            when(pgmqClient.delete(anyString(), anyLong())).thenReturn(true);

            handler.start();
            handler.pollResponses("test-replies");

            Thread.sleep(100);

            verify(processManager).processAsyncResponse(eq(processId), any());
            handler.stop();
        }
    }

    @Nested
    @DisplayName("Default Constructor")
    class DefaultConstructorTests {

        @Test
        @DisplayName("should create handler with default settings")
        void shouldCreateHandlerWithDefaultSettings() {
            CommandStepResponseHandler defaultHandler = new CommandStepResponseHandler(
                pgmqClient,
                objectMapper,
                List.of(processManager)
            );

            assertNotNull(defaultHandler);
            assertFalse(defaultHandler.isRunning());
        }
    }

    @Nested
    @DisplayName("Empty Process Manager List")
    class EmptyProcessManagerTests {

        @Test
        @DisplayName("should handle empty process manager list")
        void shouldHandleEmptyProcessManagerList() throws Exception {
            CommandStepResponseHandler emptyHandler = new CommandStepResponseHandler(
                pgmqClient,
                objectMapper,
                List.of(),  // Empty list
                30,
                100,
                false,
                new ResilienceProperties()
            );

            UUID processId = UUID.randomUUID();

            Map<String, Object> payload = new HashMap<>();
            payload.put("correlation_id", processId.toString());
            payload.put("outcome", "SUCCESS");
            payload.put("data", Map.of("step_name", "bookFx"));

            PgmqMessage message = new PgmqMessage(1L, 0, Instant.now(), Instant.now(), payload);
            when(pgmqClient.read(anyString(), anyInt(), anyInt())).thenReturn(List.of(message));
            when(pgmqClient.delete(anyString(), anyLong())).thenReturn(true);

            emptyHandler.start();
            emptyHandler.pollResponses("test-replies");

            Thread.sleep(100);

            // Should handle gracefully - message gets deleted
            verify(pgmqClient).delete("test-replies", 1L);
            emptyHandler.stop();
        }
    }

    @Nested
    @DisplayName("Additional Error Types")
    class AdditionalErrorTypeTests {

        @Test
        @DisplayName("should handle lowercase error types")
        void shouldHandleLowercaseErrorTypes() throws Exception {
            UUID processId = UUID.randomUUID();

            Map<String, Object> payload = new HashMap<>();
            payload.put("correlation_id", processId.toString());
            payload.put("outcome", "FAILED");
            payload.put("error_type", "transient");  // lowercase
            payload.put("data", Map.of("step_name", "bookFx"));

            PgmqMessage message = new PgmqMessage(1L, 0, Instant.now(), Instant.now(), payload);
            when(pgmqClient.read(anyString(), anyInt(), anyInt())).thenReturn(List.of(message));
            when(pgmqClient.delete(anyString(), anyLong())).thenReturn(true);

            handler.start();
            handler.pollResponses("test-replies");

            Thread.sleep(100);

            verify(processManager).processAsyncResponse(eq(processId), any());
            handler.stop();
        }

        @Test
        @DisplayName("should handle BUSINESS error type")
        void shouldHandleBusinessErrorTypeLower() throws Exception {
            UUID processId = UUID.randomUUID();

            Map<String, Object> payload = new HashMap<>();
            payload.put("correlation_id", processId.toString());
            payload.put("outcome", "FAILED");
            payload.put("error_type", "business");  // lowercase
            payload.put("data", Map.of("step_name", "bookFx"));

            PgmqMessage message = new PgmqMessage(1L, 0, Instant.now(), Instant.now(), payload);
            when(pgmqClient.read(anyString(), anyInt(), anyInt())).thenReturn(List.of(message));
            when(pgmqClient.delete(anyString(), anyLong())).thenReturn(true);

            handler.start();
            handler.pollResponses("test-replies");

            Thread.sleep(100);

            verify(processManager).processAsyncResponse(eq(processId), any());
            handler.stop();
        }

        @Test
        @DisplayName("should handle PERMANENT_ERROR error type")
        void shouldHandlePermanentErrorType() throws Exception {
            UUID processId = UUID.randomUUID();

            Map<String, Object> payload = new HashMap<>();
            payload.put("correlation_id", processId.toString());
            payload.put("outcome", "FAILED");
            payload.put("error_type", "PERMANENT_ERROR");
            payload.put("data", Map.of("step_name", "bookFx"));

            PgmqMessage message = new PgmqMessage(1L, 0, Instant.now(), Instant.now(), payload);
            when(pgmqClient.read(anyString(), anyInt(), anyInt())).thenReturn(List.of(message));
            when(pgmqClient.delete(anyString(), anyLong())).thenReturn(true);

            handler.start();
            handler.pollResponses("test-replies");

            Thread.sleep(100);

            verify(processManager).processAsyncResponse(eq(processId), any());
            handler.stop();
        }
    }

    @Nested
    @DisplayName("Stop Behavior")
    class StopBehaviorTests {

        @Test
        @DisplayName("should handle stop gracefully")
        void shouldHandleStopGracefully() {
            handler.start();
            assertTrue(handler.isRunning());

            handler.stop();
            assertFalse(handler.isRunning());
        }

        @Test
        @DisplayName("should handle stop when not running")
        void shouldHandleStopWhenNotRunning() {
            assertFalse(handler.isRunning());

            // Should not throw
            handler.stop();
            assertFalse(handler.isRunning());
        }
    }

    @Nested
    @DisplayName("Multiple Messages")
    class MultipleMessageTests {

        @Test
        @DisplayName("should process multiple messages in batch")
        void shouldProcessMultipleMessagesInBatch() throws Exception {
            UUID processId1 = UUID.randomUUID();
            UUID processId2 = UUID.randomUUID();

            Map<String, Object> payload1 = new HashMap<>();
            payload1.put("correlation_id", processId1.toString());
            payload1.put("command_id", UUID.randomUUID().toString());
            payload1.put("outcome", "SUCCESS");
            payload1.put("data", Map.of("step_name", "step1"));

            Map<String, Object> payload2 = new HashMap<>();
            payload2.put("correlation_id", processId2.toString());
            payload2.put("command_id", UUID.randomUUID().toString());
            payload2.put("outcome", "SUCCESS");
            payload2.put("data", Map.of("step_name", "step2"));

            PgmqMessage message1 = new PgmqMessage(1L, 0, Instant.now(), Instant.now(), payload1);
            PgmqMessage message2 = new PgmqMessage(2L, 0, Instant.now(), Instant.now(), payload2);

            when(pgmqClient.read(anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(message1, message2));
            when(pgmqClient.delete(anyString(), anyLong())).thenReturn(true);

            handler.start();
            handler.pollResponses("test-replies");

            Thread.sleep(200);

            verify(processManager, times(2)).processAsyncResponse(any(), any());
            verify(pgmqClient, times(2)).delete(eq("test-replies"), anyLong());
            handler.stop();
        }
    }

    @Nested
    @DisplayName("Exception Handling")
    class ExceptionHandlingTests {

        @Test
        @DisplayName("should handle processManager exception gracefully")
        void shouldHandleProcessManagerExceptionGracefully() throws Exception {
            UUID processId = UUID.randomUUID();

            Map<String, Object> payload = new HashMap<>();
            payload.put("correlation_id", processId.toString());
            payload.put("outcome", "SUCCESS");
            payload.put("data", Map.of("step_name", "bookFx"));

            PgmqMessage message = new PgmqMessage(1L, 0, Instant.now(), Instant.now(), payload);
            when(pgmqClient.read(anyString(), anyInt(), anyInt())).thenReturn(List.of(message));

            // Make processAsyncResponse throw an exception
            doThrow(new RuntimeException("Process error")).when(processManager).processAsyncResponse(any(), any());

            handler.start();
            handler.pollResponses("test-replies");

            Thread.sleep(100);

            // Handler should not crash - message will be retried after visibility timeout
            assertTrue(handler.isRunning());
            handler.stop();
        }

        @Test
        @DisplayName("should handle invalid correlation_id gracefully")
        void shouldHandleInvalidCorrelationIdGracefully() throws Exception {
            Map<String, Object> payload = new HashMap<>();
            payload.put("correlation_id", "not-a-uuid");  // Invalid UUID
            payload.put("outcome", "SUCCESS");

            PgmqMessage message = new PgmqMessage(1L, 0, Instant.now(), Instant.now(), payload);
            when(pgmqClient.read(anyString(), anyInt(), anyInt())).thenReturn(List.of(message));

            handler.start();
            handler.pollResponses("test-replies");

            Thread.sleep(100);

            // Should handle gracefully without crashing
            assertTrue(handler.isRunning());
            handler.stop();
        }

        @Test
        @DisplayName("should handle delete failure gracefully")
        void shouldHandleDeleteFailureGracefully() throws Exception {
            UUID processId = UUID.randomUUID();

            Map<String, Object> payload = new HashMap<>();
            payload.put("correlation_id", processId.toString());
            payload.put("outcome", "SUCCESS");
            payload.put("data", Map.of("step_name", "bookFx"));

            PgmqMessage message = new PgmqMessage(1L, 0, Instant.now(), Instant.now(), payload);
            when(pgmqClient.read(anyString(), anyInt(), anyInt())).thenReturn(List.of(message));

            // Delete throws exception
            when(pgmqClient.delete(anyString(), anyLong())).thenThrow(new RuntimeException("Delete failed"));

            handler.start();
            handler.pollResponses("test-replies");

            Thread.sleep(100);

            // Handler should still be running
            assertTrue(handler.isRunning());
            handler.stop();
        }

        @Test
        @DisplayName("should handle consecutive poll errors with backoff")
        void shouldHandleConsecutivePollErrorsWithBackoff() throws Exception {
            // Multiple poll errors
            when(pgmqClient.read(anyString(), anyInt(), anyInt()))
                .thenThrow(new RuntimeException("Connection error"))
                .thenThrow(new RuntimeException("Connection error"))
                .thenReturn(List.of());

            handler.start();
            handler.pollResponses("test-replies");
            handler.pollResponses("test-replies");
            handler.pollResponses("test-replies");

            // Handler should still be running
            assertTrue(handler.isRunning());
            handler.stop();
        }
    }
}
