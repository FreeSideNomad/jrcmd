package com.ivamare.commandbus.exception;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExceptionTest {

    @Nested
    class CommandBusExceptionTest {

        @Test
        void shouldCreateWithMessage() {
            CommandBusException exception = new CommandBusException("Test message");
            assertEquals("Test message", exception.getMessage());
            assertNull(exception.getCause());
        }

        @Test
        void shouldCreateWithMessageAndCause() {
            RuntimeException cause = new RuntimeException("Original error");
            CommandBusException exception = new CommandBusException("Test message", cause);
            assertEquals("Test message", exception.getMessage());
            assertEquals(cause, exception.getCause());
        }

        @Test
        void shouldBeRuntimeException() {
            CommandBusException exception = new CommandBusException("Test");
            assertInstanceOf(RuntimeException.class, exception);
        }
    }

    @Nested
    class TransientCommandExceptionTest {

        @Test
        void shouldCreateWithCodeAndMessage() {
            TransientCommandException exception = new TransientCommandException("TIMEOUT", "Connection timed out");

            assertEquals("TIMEOUT", exception.getCode());
            assertEquals("Connection timed out", exception.getErrorMessage());
            assertEquals("[TIMEOUT] Connection timed out", exception.getMessage());
            assertEquals(Map.of(), exception.getDetails());
        }

        @Test
        void shouldCreateWithDetails() {
            Map<String, Object> details = Map.of("retryAfter", 30, "endpoint", "api.example.com");
            TransientCommandException exception = new TransientCommandException("TIMEOUT", "Connection timed out", details);

            assertEquals("TIMEOUT", exception.getCode());
            assertEquals("Connection timed out", exception.getErrorMessage());
            assertEquals(details, exception.getDetails());
        }

        @Test
        void shouldMakeDetailsImmutable() {
            Map<String, Object> mutableDetails = new HashMap<>();
            mutableDetails.put("key", "value");

            TransientCommandException exception = new TransientCommandException("CODE", "msg", mutableDetails);

            // Original map modification should not affect exception
            mutableDetails.put("another", "entry");
            assertEquals(1, exception.getDetails().size());

            // Exception details should be immutable
            assertThrows(UnsupportedOperationException.class,
                () -> exception.getDetails().put("new", "entry"));
        }

        @Test
        void shouldHandleNullDetails() {
            TransientCommandException exception = new TransientCommandException("CODE", "msg", null);
            assertEquals(Map.of(), exception.getDetails());
        }

        @Test
        void shouldBeCommandBusException() {
            TransientCommandException exception = new TransientCommandException("CODE", "msg");
            assertInstanceOf(CommandBusException.class, exception);
        }
    }

    @Nested
    class PermanentCommandExceptionTest {

        @Test
        void shouldCreateWithCodeAndMessage() {
            PermanentCommandException exception = new PermanentCommandException("INVALID", "Invalid account");

            assertEquals("INVALID", exception.getCode());
            assertEquals("Invalid account", exception.getErrorMessage());
            assertEquals("[INVALID] Invalid account", exception.getMessage());
            assertEquals(Map.of(), exception.getDetails());
        }

        @Test
        void shouldCreateWithDetails() {
            Map<String, Object> details = Map.of("field", "accountId", "reason", "not found");
            PermanentCommandException exception = new PermanentCommandException("INVALID", "Invalid account", details);

            assertEquals("INVALID", exception.getCode());
            assertEquals("Invalid account", exception.getErrorMessage());
            assertEquals(details, exception.getDetails());
        }

        @Test
        void shouldMakeDetailsImmutable() {
            Map<String, Object> mutableDetails = new HashMap<>();
            mutableDetails.put("key", "value");

            PermanentCommandException exception = new PermanentCommandException("CODE", "msg", mutableDetails);

            // Original map modification should not affect exception
            mutableDetails.put("another", "entry");
            assertEquals(1, exception.getDetails().size());

            // Exception details should be immutable
            assertThrows(UnsupportedOperationException.class,
                () -> exception.getDetails().put("new", "entry"));
        }

        @Test
        void shouldHandleNullDetails() {
            PermanentCommandException exception = new PermanentCommandException("CODE", "msg", null);
            assertEquals(Map.of(), exception.getDetails());
        }

        @Test
        void shouldBeCommandBusException() {
            PermanentCommandException exception = new PermanentCommandException("CODE", "msg");
            assertInstanceOf(CommandBusException.class, exception);
        }
    }

    @Nested
    class HandlerNotFoundExceptionTest {

        @Test
        void shouldCreateWithDomainAndCommandType() {
            HandlerNotFoundException exception = new HandlerNotFoundException("payments", "DebitAccount");

            assertEquals("payments", exception.getDomain());
            assertEquals("DebitAccount", exception.getCommandType());
            assertEquals("No handler registered for payments.DebitAccount", exception.getMessage());
        }

        @Test
        void shouldBeCommandBusException() {
            HandlerNotFoundException exception = new HandlerNotFoundException("domain", "type");
            assertInstanceOf(CommandBusException.class, exception);
        }
    }

    @Nested
    class HandlerAlreadyRegisteredExceptionTest {

        @Test
        void shouldCreateWithDomainAndCommandType() {
            HandlerAlreadyRegisteredException exception = new HandlerAlreadyRegisteredException("payments", "DebitAccount");

            assertEquals("payments", exception.getDomain());
            assertEquals("DebitAccount", exception.getCommandType());
            assertEquals("Handler already registered for payments.DebitAccount", exception.getMessage());
        }

        @Test
        void shouldBeCommandBusException() {
            HandlerAlreadyRegisteredException exception = new HandlerAlreadyRegisteredException("domain", "type");
            assertInstanceOf(CommandBusException.class, exception);
        }
    }

    @Nested
    class DuplicateCommandExceptionTest {

        @Test
        void shouldCreateWithDomainAndCommandId() {
            DuplicateCommandException exception = new DuplicateCommandException("payments", "commandbus-123");

            assertEquals("payments", exception.getDomain());
            assertEquals("commandbus-123", exception.getCommandId());
            assertEquals("Duplicate command_id commandbus-123 in domain payments", exception.getMessage());
        }

        @Test
        void shouldBeCommandBusException() {
            DuplicateCommandException exception = new DuplicateCommandException("domain", "id");
            assertInstanceOf(CommandBusException.class, exception);
        }
    }

    @Nested
    class CommandNotFoundExceptionTest {

        @Test
        void shouldCreateWithDomainAndCommandId() {
            CommandNotFoundException exception = new CommandNotFoundException("payments", "commandbus-456");

            assertEquals("payments", exception.getDomain());
            assertEquals("commandbus-456", exception.getCommandId());
            assertEquals("Command commandbus-456 not found in domain payments", exception.getMessage());
        }

        @Test
        void shouldBeCommandBusException() {
            CommandNotFoundException exception = new CommandNotFoundException("domain", "id");
            assertInstanceOf(CommandBusException.class, exception);
        }
    }

    @Nested
    class BatchNotFoundExceptionTest {

        @Test
        void shouldCreateWithDomainAndBatchId() {
            BatchNotFoundException exception = new BatchNotFoundException("payments", "batch-789");

            assertEquals("payments", exception.getDomain());
            assertEquals("batch-789", exception.getBatchId());
            assertEquals("Batch batch-789 not found in domain payments", exception.getMessage());
        }

        @Test
        void shouldBeCommandBusException() {
            BatchNotFoundException exception = new BatchNotFoundException("domain", "id");
            assertInstanceOf(CommandBusException.class, exception);
        }
    }

    @Nested
    class InvalidOperationExceptionTest {

        @Test
        void shouldCreateWithMessage() {
            InvalidOperationException exception = new InvalidOperationException("Cannot cancel completed command");
            assertEquals("Cannot cancel completed command", exception.getMessage());
        }

        @Test
        void shouldBeCommandBusException() {
            InvalidOperationException exception = new InvalidOperationException("msg");
            assertInstanceOf(CommandBusException.class, exception);
        }
    }

    @Nested
    class BusinessRuleExceptionTest {

        @Test
        void shouldCreateWithCodeAndMessage() {
            BusinessRuleException exception = new BusinessRuleException("ACCOUNT_CLOSED", "Account is closed");

            assertEquals("ACCOUNT_CLOSED", exception.getCode());
            assertEquals("Account is closed", exception.getErrorMessage());
            assertEquals("[ACCOUNT_CLOSED] Account is closed", exception.getMessage());
            assertEquals(Map.of(), exception.getDetails());
        }

        @Test
        void shouldCreateWithDetails() {
            Map<String, Object> details = Map.of("accountId", "acc-123", "closedAt", "2024-01-15");
            BusinessRuleException exception = new BusinessRuleException("ACCOUNT_CLOSED", "Account is closed", details);

            assertEquals("ACCOUNT_CLOSED", exception.getCode());
            assertEquals("Account is closed", exception.getErrorMessage());
            assertEquals(details, exception.getDetails());
        }

        @Test
        void shouldMakeDetailsImmutable() {
            Map<String, Object> mutableDetails = new HashMap<>();
            mutableDetails.put("key", "value");

            BusinessRuleException exception = new BusinessRuleException("CODE", "msg", mutableDetails);

            // Original map modification should not affect exception
            mutableDetails.put("another", "entry");
            assertEquals(1, exception.getDetails().size());

            // Exception details should be immutable
            assertThrows(UnsupportedOperationException.class,
                () -> exception.getDetails().put("new", "entry"));
        }

        @Test
        void shouldHandleNullDetails() {
            BusinessRuleException exception = new BusinessRuleException("CODE", "msg", null);
            assertEquals(Map.of(), exception.getDetails());
        }

        @Test
        void shouldBeCommandBusException() {
            BusinessRuleException exception = new BusinessRuleException("CODE", "msg");
            assertInstanceOf(CommandBusException.class, exception);
        }
    }
}
