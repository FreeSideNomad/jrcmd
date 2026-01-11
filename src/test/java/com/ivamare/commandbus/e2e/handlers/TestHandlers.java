package com.ivamare.commandbus.e2e.handlers;

import com.ivamare.commandbus.exception.PermanentCommandException;
import com.ivamare.commandbus.exception.TransientCommandException;
import com.ivamare.commandbus.handler.CommandHandler;
import com.ivamare.commandbus.handler.HandlerRegistry;
import com.ivamare.commandbus.model.Command;
import com.ivamare.commandbus.model.HandlerContext;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Sample handlers for E2E testing scenarios.
 */
@Component
public class TestHandlers {

    private static final Logger log = LoggerFactory.getLogger(TestHandlers.class);

    private final HandlerRegistry handlerRegistry;
    private final String domain;

    public TestHandlers(
            HandlerRegistry handlerRegistry,
            @Value("${commandbus.domain:test}") String domain) {
        this.handlerRegistry = handlerRegistry;
        this.domain = domain;
    }

    @PostConstruct
    public void registerHandlers() {
        handlerRegistry.register(domain, "SuccessCommand", this::handleSuccess);
        handlerRegistry.register(domain, "TransientFailCommand", this::handleTransientFail);
        handlerRegistry.register(domain, "PermanentFailCommand", this::handlePermanentFail);
        handlerRegistry.register(domain, "SlowCommand", this::handleSlow);
        log.info("Registered test handlers for domain: {}", domain);
    }

    /**
     * Handler that always succeeds.
     */
    public Map<String, Object> handleSuccess(Command command, HandlerContext context) {
        log.info("Processing SuccessCommand: {}", command.commandId());
        return Map.of(
            "status", "processed",
            "input", command.data(),
            "processedAt", System.currentTimeMillis()
        );
    }

    /**
     * Handler that fails transiently for first few attempts, then succeeds.
     */
    public Map<String, Object> handleTransientFail(Command command, HandlerContext context) {
        log.info("Processing TransientFailCommand: {} (attempt {})", command.commandId(), context.attempt());
        if (context.attempt() < 3) {
            throw new TransientCommandException("TIMEOUT", "Simulated timeout on attempt " + context.attempt());
        }
        return Map.of("status", "recovered", "attempts", context.attempt());
    }

    /**
     * Handler that always fails permanently.
     */
    public Map<String, Object> handlePermanentFail(Command command, HandlerContext context) {
        log.info("Processing PermanentFailCommand: {}", command.commandId());
        throw new PermanentCommandException("INVALID_DATA", "Simulated permanent error - data is invalid");
    }

    /**
     * Handler that simulates a slow operation.
     */
    public Map<String, Object> handleSlow(Command command, HandlerContext context) {
        int delayMs = 5000;
        Object delayParam = command.data().get("delayMs");
        if (delayParam instanceof Number) {
            delayMs = ((Number) delayParam).intValue();
        }

        log.info("Processing SlowCommand: {} (delay: {}ms)", command.commandId(), delayMs);

        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TransientCommandException("INTERRUPTED", "Command was interrupted");
        }

        return Map.of("status", "completed", "delayMs", delayMs);
    }
}
