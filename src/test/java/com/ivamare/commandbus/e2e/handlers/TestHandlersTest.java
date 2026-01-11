package com.ivamare.commandbus.e2e.handlers;

import com.ivamare.commandbus.exception.BusinessRuleException;
import com.ivamare.commandbus.exception.PermanentCommandException;
import com.ivamare.commandbus.exception.TransientCommandException;
import com.ivamare.commandbus.handler.HandlerRegistry;
import com.ivamare.commandbus.model.Command;
import com.ivamare.commandbus.model.HandlerContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TestHandlersTest {

    private HandlerRegistry handlerRegistry;
    private TestHandlers handlers;

    @BeforeEach
    void setUp() {
        handlerRegistry = mock(HandlerRegistry.class);
        handlers = new TestHandlers(handlerRegistry, "test");
    }

    @Nested
    class TestCommandHandler {

        @Test
        void shouldSucceedWithNoFailureRates() {
            Command command = createCommand(Map.of(
                "index", 0,
                "failPermanentPct", 0.0,
                "failTransientPct", 0.0,
                "failBusinessRulePct", 0.0,
                "timeoutPct", 0.0
            ));
            HandlerContext context = createContext(command, 1);

            Map<String, Object> result = handlers.handleTestCommand(command, context);

            assertEquals("success", result.get("status"));
        }

        @Test
        void shouldThrowPermanentExceptionAt100Percent() {
            Command command = createCommand(Map.of(
                "failPermanentPct", 100.0,
                "failTransientPct", 0.0,
                "failBusinessRulePct", 0.0,
                "timeoutPct", 0.0
            ));
            HandlerContext context = createContext(command, 1);

            assertThrows(PermanentCommandException.class, () ->
                handlers.handleTestCommand(command, context));
        }

        @Test
        void shouldThrowTransientExceptionAt100PercentAfterPermanent() {
            // With 0% permanent and 100% transient, should throw transient
            Command command = createCommand(Map.of(
                "failPermanentPct", 0.0,
                "failTransientPct", 100.0,
                "failBusinessRulePct", 0.0,
                "timeoutPct", 0.0
            ));
            HandlerContext context = createContext(command, 1);

            assertThrows(TransientCommandException.class, () ->
                handlers.handleTestCommand(command, context));
        }

        @Test
        void shouldThrowBusinessRuleExceptionAt100PercentAfterOthers() {
            Command command = createCommand(Map.of(
                "failPermanentPct", 0.0,
                "failTransientPct", 0.0,
                "failBusinessRulePct", 100.0,
                "timeoutPct", 0.0
            ));
            HandlerContext context = createContext(command, 1);

            assertThrows(BusinessRuleException.class, () ->
                handlers.handleTestCommand(command, context));
        }

        @Test
        void shouldHandleDurationRange() {
            Command command = createCommand(Map.of(
                "failPermanentPct", 0.0,
                "failTransientPct", 0.0,
                "failBusinessRulePct", 0.0,
                "timeoutPct", 0.0,
                "minDurationMs", 1,
                "maxDurationMs", 10
            ));
            HandlerContext context = createContext(command, 1);

            Map<String, Object> result = handlers.handleTestCommand(command, context);

            assertEquals("success", result.get("status"));
        }

        @Test
        void shouldHandleMissingBehaviorValues() {
            Command command = createCommand(Map.of("index", 0));
            HandlerContext context = createContext(command, 1);

            Map<String, Object> result = handlers.handleTestCommand(command, context);

            assertEquals("success", result.get("status"));
        }
    }

    @Nested
    class BusinessRuleFailHandler {

        @Test
        void shouldAlwaysThrowBusinessRuleException() {
            Command command = createCommand(Map.of());
            HandlerContext context = createContext(command, 1);

            BusinessRuleException ex = assertThrows(BusinessRuleException.class, () ->
                handlers.handleBusinessRuleFail(command, context));

            assertEquals("ACCOUNT_CLOSED", ex.getCode());
        }
    }

    private Command createCommand(Map<String, Object> data) {
        return new Command(
            "test",
            "TestCommand",
            UUID.randomUUID(),
            data,
            null,
            null,
            Instant.now()
        );
    }

    private HandlerContext createContext(Command command, int attempt) {
        return new HandlerContext(command, attempt, 3, 1L, null);
    }
}
