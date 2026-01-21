package com.ivamare.commandbus.handler.impl;

import com.ivamare.commandbus.exception.HandlerAlreadyRegisteredException;
import com.ivamare.commandbus.exception.HandlerNotFoundException;
import com.ivamare.commandbus.exception.PermanentCommandException;
import com.ivamare.commandbus.exception.TransientCommandException;
import com.ivamare.commandbus.handler.CommandHandler;
import com.ivamare.commandbus.handler.Handler;
import com.ivamare.commandbus.handler.HandlerRegistry.HandlerKey;
import com.ivamare.commandbus.model.Command;
import com.ivamare.commandbus.model.HandlerContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DefaultHandlerRegistryTest {

    private DefaultHandlerRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DefaultHandlerRegistry();
    }

    private Command createTestCommand(String domain, String commandType) {
        return new Command(
            domain,
            commandType,
            UUID.randomUUID(),
            Map.of("key", "value"),
            null,
            null,
            Instant.now()
        );
    }

    private HandlerContext createTestContext() {
        return new HandlerContext(
            createTestCommand("test", "Test"),
            1,
            3,
            123L,
            null
        );
    }

    @Nested
    class RegisterTests {

        @Test
        void shouldRegisterHandler() {
            CommandHandler handler = (cmd, ctx) -> "result";

            registry.register("payments", "DebitAccount", handler);

            assertTrue(registry.hasHandler("payments", "DebitAccount"));
        }

        @Test
        void shouldThrowOnDuplicateRegistration() {
            CommandHandler handler = (cmd, ctx) -> "result";
            registry.register("payments", "DebitAccount", handler);

            assertThrows(HandlerAlreadyRegisteredException.class, () ->
                registry.register("payments", "DebitAccount", handler));
        }

        @Test
        void shouldAllowSameCommandTypeDifferentDomain() {
            CommandHandler handler = (cmd, ctx) -> "result";

            registry.register("payments", "Process", handler);
            registry.register("orders", "Process", handler);

            assertTrue(registry.hasHandler("payments", "Process"));
            assertTrue(registry.hasHandler("orders", "Process"));
        }
    }

    @Nested
    class GetTests {

        @Test
        void shouldReturnHandlerWhenExists() {
            CommandHandler handler = (cmd, ctx) -> "result";
            registry.register("payments", "DebitAccount", handler);

            var result = registry.get("payments", "DebitAccount");

            assertTrue(result.isPresent());
            assertEquals(handler, result.get());
        }

        @Test
        void shouldReturnEmptyWhenNotExists() {
            var result = registry.get("payments", "NonExistent");

            assertTrue(result.isEmpty());
        }

        @Test
        void shouldGetOrThrowWhenExists() {
            CommandHandler handler = (cmd, ctx) -> "result";
            registry.register("payments", "DebitAccount", handler);

            var result = registry.getOrThrow("payments", "DebitAccount");

            assertEquals(handler, result);
        }

        @Test
        void shouldThrowWhenNotFound() {
            assertThrows(HandlerNotFoundException.class, () ->
                registry.getOrThrow("payments", "NonExistent"));
        }
    }

    @Nested
    class DispatchTests {

        @Test
        void shouldDispatchToRegisteredHandler() throws Exception {
            CommandHandler handler = (cmd, ctx) -> Map.of("status", "processed");
            registry.register("payments", "DebitAccount", handler);

            Command command = createTestCommand("payments", "DebitAccount");
            HandlerContext context = createTestContext();

            Object result = registry.dispatch(command, context);

            assertEquals(Map.of("status", "processed"), result);
        }

        @Test
        void shouldThrowWhenNoHandlerForDispatch() {
            Command command = createTestCommand("payments", "NonExistent");
            HandlerContext context = createTestContext();

            assertThrows(HandlerNotFoundException.class, () ->
                registry.dispatch(command, context));
        }

        @Test
        void shouldPropagateTransientException() {
            CommandHandler handler = (cmd, ctx) -> {
                throw new TransientCommandException("TIMEOUT", "Connection timeout");
            };
            registry.register("payments", "DebitAccount", handler);

            Command command = createTestCommand("payments", "DebitAccount");
            HandlerContext context = createTestContext();

            assertThrows(TransientCommandException.class, () ->
                registry.dispatch(command, context));
        }

        @Test
        void shouldPropagatePermanentException() {
            CommandHandler handler = (cmd, ctx) -> {
                throw new PermanentCommandException("VALIDATION", "Invalid amount");
            };
            registry.register("payments", "DebitAccount", handler);

            Command command = createTestCommand("payments", "DebitAccount");
            HandlerContext context = createTestContext();

            assertThrows(PermanentCommandException.class, () ->
                registry.dispatch(command, context));
        }

        @Test
        void shouldReturnNullFromHandler() throws Exception {
            CommandHandler handler = (cmd, ctx) -> null;
            registry.register("payments", "DebitAccount", handler);

            Command command = createTestCommand("payments", "DebitAccount");
            HandlerContext context = createTestContext();

            Object result = registry.dispatch(command, context);

            assertNull(result);
        }
    }

    @Nested
    class HasHandlerTests {

        @Test
        void shouldReturnTrueWhenHandlerExists() {
            registry.register("payments", "DebitAccount", (cmd, ctx) -> null);

            assertTrue(registry.hasHandler("payments", "DebitAccount"));
        }

        @Test
        void shouldReturnFalseWhenHandlerNotExists() {
            assertFalse(registry.hasHandler("payments", "NonExistent"));
        }
    }

    @Nested
    class RegisteredHandlersTests {

        @Test
        void shouldReturnEmptyListWhenNoHandlers() {
            List<HandlerKey> handlers = registry.registeredHandlers();

            assertTrue(handlers.isEmpty());
        }

        @Test
        void shouldReturnAllRegisteredHandlers() {
            registry.register("payments", "Debit", (cmd, ctx) -> null);
            registry.register("payments", "Credit", (cmd, ctx) -> null);
            registry.register("orders", "Create", (cmd, ctx) -> null);

            List<HandlerKey> handlers = registry.registeredHandlers();

            assertEquals(3, handlers.size());
            assertTrue(handlers.contains(new HandlerKey("payments", "Debit")));
            assertTrue(handlers.contains(new HandlerKey("payments", "Credit")));
            assertTrue(handlers.contains(new HandlerKey("orders", "Create")));
        }
    }

    @Nested
    class ClearTests {

        @Test
        void shouldRemoveAllHandlers() {
            registry.register("payments", "Debit", (cmd, ctx) -> null);
            registry.register("orders", "Create", (cmd, ctx) -> null);

            registry.clear();

            assertTrue(registry.registeredHandlers().isEmpty());
            assertFalse(registry.hasHandler("payments", "Debit"));
            assertFalse(registry.hasHandler("orders", "Create"));
        }
    }

    @Nested
    class RegisterBeanTests {

        @Test
        void shouldRegisterAnnotatedMethods() {
            TestHandlerBean bean = new TestHandlerBean();

            List<HandlerKey> registered = registry.registerBean(bean);

            assertEquals(2, registered.size());
            assertTrue(registry.hasHandler("test", "Command1"));
            assertTrue(registry.hasHandler("test", "Command2"));
        }

        @Test
        void shouldThrowOnInvalidMethodSignature() {
            InvalidHandlerBean bean = new InvalidHandlerBean();

            assertThrows(IllegalArgumentException.class, () ->
                registry.registerBean(bean));
        }

        @Test
        void shouldThrowWhenFirstParamNotCommand() {
            WrongFirstParamBean bean = new WrongFirstParamBean();

            assertThrows(IllegalArgumentException.class, () ->
                registry.registerBean(bean));
        }

        @Test
        void shouldThrowWhenSecondParamNotHandlerContext() {
            WrongSecondParamBean bean = new WrongSecondParamBean();

            assertThrows(IllegalArgumentException.class, () ->
                registry.registerBean(bean));
        }

        @Test
        void shouldInvokeHandlerMethod() throws Exception {
            TestHandlerBean bean = new TestHandlerBean();
            registry.registerBean(bean);

            Command command = createTestCommand("test", "Command1");
            HandlerContext context = createTestContext();

            Object result = registry.dispatch(command, context);

            assertEquals("handled1", result);
        }

        @Test
        void shouldSkipMethodsWithoutAnnotation() {
            TestHandlerBean bean = new TestHandlerBean();

            registry.registerBean(bean);

            assertFalse(registry.hasHandler("test", "NotAHandler"));
        }
    }

    @Nested
    class BeanPostProcessorTests {

        @Test
        void shouldScanBeanWithHandlers() {
            TestHandlerBean bean = new TestHandlerBean();

            Object result = registry.postProcessAfterInitialization(bean, "testBean");

            assertEquals(bean, result);
            assertTrue(registry.hasHandler("test", "Command1"));
            assertTrue(registry.hasHandler("test", "Command2"));
        }

        @Test
        void shouldIgnoreBeanWithoutHandlers() {
            Object bean = new Object();

            Object result = registry.postProcessAfterInitialization(bean, "plainBean");

            assertEquals(bean, result);
            assertTrue(registry.registeredHandlers().isEmpty());
        }
    }

    @Nested
    class HandlerKeyTests {

        @Test
        void shouldBeEqualWithSameValues() {
            HandlerKey key1 = new HandlerKey("payments", "Debit");
            HandlerKey key2 = new HandlerKey("payments", "Debit");

            assertEquals(key1, key2);
            assertEquals(key1.hashCode(), key2.hashCode());
        }

        @Test
        void shouldNotBeEqualWithDifferentValues() {
            HandlerKey key1 = new HandlerKey("payments", "Debit");
            HandlerKey key2 = new HandlerKey("payments", "Credit");

            assertNotEquals(key1, key2);
        }
    }

    // Test helper classes

    static class TestHandlerBean {

        @Handler(domain = "test", commandType = "Command1")
        public String handleCommand1(Command command, HandlerContext context) {
            return "handled1";
        }

        @Handler(domain = "test", commandType = "Command2")
        public Map<String, Object> handleCommand2(Command command, HandlerContext context) {
            return Map.of("result", "handled2");
        }

        public String notAHandler(Command command, HandlerContext context) {
            return "not called";
        }
    }

    static class InvalidHandlerBean {

        @Handler(domain = "test", commandType = "Invalid")
        public String invalidHandler(String wrongParam) {
            return "invalid";
        }
    }

    static class WrongFirstParamBean {

        @Handler(domain = "test", commandType = "WrongFirst")
        public String handleWrongFirst(String notCommand, HandlerContext context) {
            return "invalid";
        }
    }

    static class WrongSecondParamBean {

        @Handler(domain = "test", commandType = "WrongSecond")
        public String handleWrongSecond(Command command, String notContext) {
            return "invalid";
        }
    }
}
