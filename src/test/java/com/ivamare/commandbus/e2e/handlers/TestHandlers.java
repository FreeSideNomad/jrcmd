package com.ivamare.commandbus.e2e.handlers;

import com.ivamare.commandbus.exception.BusinessRuleException;
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
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Random;

/**
 * Sample handlers for E2E testing scenarios.
 * Only active when running with the 'worker' profile.
 */
@Component
@Profile("worker")
public class TestHandlers {

    private static final Logger log = LoggerFactory.getLogger(TestHandlers.class);

    private final HandlerRegistry handlerRegistry;
    private final String domain;
    private final Random random = new Random();

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
        handlerRegistry.register(domain, "BusinessRuleFailCommand", this::handleBusinessRuleFail);
        handlerRegistry.register(domain, "SlowCommand", this::handleSlow);
        handlerRegistry.register(domain, "TestCommand", this::handleTestCommand);
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

    /**
     * Handler that fails with BusinessRuleException.
     */
    public Map<String, Object> handleBusinessRuleFail(Command command, HandlerContext context) {
        log.info("Processing BusinessRuleFailCommand: {}", command.commandId());
        throw new BusinessRuleException("ACCOUNT_CLOSED", "Cannot process - account is closed");
    }

    /**
     * Probabilistic handler for batch testing.
     * Reads behavior from command payload and simulates failures based on probabilities.
     * Probabilities are evaluated sequentially (not independently).
     */
    public Map<String, Object> handleTestCommand(Command command, HandlerContext context) {
        log.debug("Processing TestCommand: {}", command.commandId());

        // Extract behavior from payload
        double failPermanentPct = getDouble(command.data(), "failPermanentPct", 0);
        double failTransientPct = getDouble(command.data(), "failTransientPct", 0);
        double failBusinessRulePct = getDouble(command.data(), "failBusinessRulePct", 0);
        double timeoutPct = getDouble(command.data(), "timeoutPct", 0);
        int minDurationMs = getInt(command.data(), "minDurationMs", 0);
        int maxDurationMs = getInt(command.data(), "maxDurationMs", 100);

        // Simulate duration
        if (maxDurationMs > minDurationMs) {
            int duration = minDurationMs + random.nextInt(maxDurationMs - minDurationMs);
            try {
                Thread.sleep(duration);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // Sequential probability evaluation
        double roll = random.nextDouble() * 100;
        double cumulative = 0;

        // Permanent failure
        cumulative += failPermanentPct;
        if (roll < cumulative) {
            throw new PermanentCommandException("PERM_ERROR", "Simulated permanent error");
        }

        // Transient failure
        cumulative += failTransientPct;
        if (roll < cumulative) {
            throw new TransientCommandException("TRANS_ERROR", "Simulated transient error");
        }

        // Business rule failure
        cumulative += failBusinessRulePct;
        if (roll < cumulative) {
            throw new BusinessRuleException("BIZ_RULE", "Simulated business rule violation");
        }

        // Timeout (simulated by long sleep)
        cumulative += timeoutPct;
        if (roll < cumulative) {
            try {
                Thread.sleep(60000); // 60 second timeout
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // Success
        return Map.of("status", "success", "commandId", command.commandId().toString());
    }

    private double getDouble(Map<String, Object> data, String key, double defaultValue) {
        Object value = data.get(key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return defaultValue;
    }

    private int getInt(Map<String, Object> data, String key, int defaultValue) {
        Object value = data.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return defaultValue;
    }
}
