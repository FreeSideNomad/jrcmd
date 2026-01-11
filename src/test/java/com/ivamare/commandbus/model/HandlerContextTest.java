package com.ivamare.commandbus.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class HandlerContextTest {

    private Command createTestCommand() {
        return new Command(
            "payments",
            "DebitAccount",
            UUID.randomUUID(),
            Map.of("amount", 100),
            null,
            null,
            Instant.now()
        );
    }

    @Test
    void shouldCreateHandlerContext() {
        Command command = createTestCommand();
        HandlerContext.VisibilityExtender extender = seconds -> {};

        HandlerContext context = new HandlerContext(command, 1, 5, 12345L, extender);

        assertEquals(command, context.command());
        assertEquals(1, context.attempt());
        assertEquals(5, context.maxAttempts());
        assertEquals(12345L, context.msgId());
        assertNotNull(context.visibilityExtender());
    }

    @Test
    void shouldCreateHandlerContextWithNullExtender() {
        Command command = createTestCommand();

        HandlerContext context = new HandlerContext(command, 1, 5, 12345L, null);

        assertNull(context.visibilityExtender());
    }

    @Test
    void shouldExtendVisibility() {
        Command command = createTestCommand();
        AtomicInteger extendedSeconds = new AtomicInteger(0);
        HandlerContext.VisibilityExtender extender = extendedSeconds::set;

        HandlerContext context = new HandlerContext(command, 1, 5, 12345L, extender);
        context.extendVisibility(30);

        assertEquals(30, extendedSeconds.get());
    }

    @Test
    void shouldThrowWhenExtendingWithNullExtender() {
        Command command = createTestCommand();
        HandlerContext context = new HandlerContext(command, 1, 5, 12345L, null);

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> context.extendVisibility(30)
        );
        assertEquals("Visibility extender not available", exception.getMessage());
    }

    @Test
    void shouldIdentifyLastAttempt() {
        Command command = createTestCommand();

        HandlerContext lastAttempt = new HandlerContext(command, 5, 5, 12345L, null);
        HandlerContext beyondMax = new HandlerContext(command, 6, 5, 12345L, null);
        HandlerContext notLast = new HandlerContext(command, 3, 5, 12345L, null);
        HandlerContext firstAttempt = new HandlerContext(command, 1, 5, 12345L, null);

        assertTrue(lastAttempt.isLastAttempt());
        assertTrue(beyondMax.isLastAttempt());
        assertFalse(notLast.isLastAttempt());
        assertFalse(firstAttempt.isLastAttempt());
    }

    @Test
    void shouldSupportEqualsAndHashCode() {
        Command command = createTestCommand();

        HandlerContext context1 = new HandlerContext(command, 1, 5, 12345L, null);
        HandlerContext context2 = new HandlerContext(command, 1, 5, 12345L, null);
        HandlerContext context3 = new HandlerContext(command, 2, 5, 12345L, null);

        assertEquals(context1, context2);
        assertEquals(context1.hashCode(), context2.hashCode());
        assertNotEquals(context1, context3);
    }
}
