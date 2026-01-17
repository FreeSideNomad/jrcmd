package com.ivamare.commandbus.process.step;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ivamare.commandbus.e2e.process.ProbabilisticBehavior;
import com.ivamare.commandbus.process.ProcessRepository;
import com.ivamare.commandbus.process.ratelimit.Bucket4jRateLimiter;
import com.ivamare.commandbus.process.step.exceptions.StepBusinessRuleException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Random;

/**
 * Test process manager with probabilistic behavior injection.
 *
 * <p>Extends ProcessStepManager to inject configured behavior BEFORE
 * executing each step. This enables controlled testing of:
 * <ul>
 *   <li>Permanent failures</li>
 *   <li>Transient failures with retry</li>
 *   <li>Business rule violations with compensation</li>
 *   <li>Timeouts</li>
 *   <li>Delays</li>
 * </ul>
 *
 * <p>Behavior is applied for fresh executions only - replayed steps
 * skip behavior injection and return cached results.
 *
 * <p>Usage:
 * <pre>{@code
 * public class MyTestProcess extends TestProcessStepManager<MyTestState> {
 *     @Override
 *     protected void execute(MyTestState state) {
 *         // Steps automatically apply behavior from state.getBehaviorForStep(stepName)
 *         step("step1", s -> doSomething());
 *     }
 * }
 * }</pre>
 *
 * @param <TState> State type extending TestProcessStepState
 */
public abstract class TestProcessStepManager<TState extends TestProcessStepState>
        extends ProcessStepManager<TState> {

    private static final Logger log = LoggerFactory.getLogger(TestProcessStepManager.class);
    private static final Random random = new Random();

    // ========== Constructors ==========

    protected TestProcessStepManager(
            ProcessRepository processRepo,
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate) {
        super(processRepo, jdbcTemplate, transactionTemplate);
    }

    protected TestProcessStepManager(
            ProcessRepository processRepo,
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            ObjectMapper objectMapper) {
        super(processRepo, jdbcTemplate, transactionTemplate, objectMapper);
    }

    protected TestProcessStepManager(
            ProcessRepository processRepo,
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            Bucket4jRateLimiter rateLimiter) {
        super(processRepo, jdbcTemplate, transactionTemplate, rateLimiter);
    }

    protected TestProcessStepManager(
            ProcessRepository processRepo,
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            Bucket4jRateLimiter rateLimiter,
            ObjectMapper objectMapper) {
        super(processRepo, jdbcTemplate, transactionTemplate, rateLimiter, objectMapper);
    }

    // ========== Overridden step() with behavior injection ==========

    @Override
    protected <R> R step(String name, StepOptions<TState, R> options) {
        ExecutionContext<TState> ctx = currentContext.get();
        if (ctx == null) {
            throw new IllegalStateException("step() called outside of execute() context");
        }

        TState state = ctx.state();

        // Skip behavior injection for replayed steps
        var completedStep = ctx.getCompletedStep(name);
        if (completedStep.isPresent() && completedStep.get().status() == StepStatus.COMPLETED) {
            log.debug("Replaying step {} - skipping behavior injection", name);
            return super.step(name, options);
        }

        // Apply probabilistic behavior BEFORE executing step
        ProbabilisticBehavior behavior = state.getBehaviorForStep(name);
        applyProbabilisticBehavior(name, behavior);

        // Execute the step normally
        return super.step(name, options);
    }

    // ========== Behavior Application ==========

    /**
     * Apply probabilistic behavior for a step.
     *
     * <p>Evaluates probabilities in order:
     * <ol>
     *   <li>Permanent failure (throws RuntimeException)</li>
     *   <li>Transient failure (throws RuntimeException marked as transient)</li>
     *   <li>Business rule violation (throws StepBusinessRuleException)</li>
     *   <li>Timeout simulation (sleeps then throws)</li>
     *   <li>Normal delay (sleeps configured duration)</li>
     * </ol>
     *
     * @param stepName The step name (for logging)
     * @param behavior The behavior configuration
     */
    protected void applyProbabilisticBehavior(String stepName, ProbabilisticBehavior behavior) {
        if (behavior == null) {
            return;
        }

        double roll = random.nextDouble() * 100;

        // Permanent failure
        if (roll < behavior.failPermanentPct()) {
            log.debug("Step {} simulating permanent failure (roll={:.2f}, threshold={:.2f})",
                stepName, roll, behavior.failPermanentPct());
            throw new RuntimeException("Simulated permanent failure for step: " + stepName);
        }
        roll -= behavior.failPermanentPct();

        // Transient failure
        if (roll < behavior.failTransientPct()) {
            log.debug("Step {} simulating transient failure (roll={:.2f}, threshold={:.2f})",
                stepName, roll, behavior.failTransientPct());
            throw new TransientFailureException("Simulated transient failure for step: " + stepName);
        }
        roll -= behavior.failTransientPct();

        // Business rule violation
        if (roll < behavior.failBusinessRulePct()) {
            log.debug("Step {} simulating business rule violation (roll={:.2f}, threshold={:.2f})",
                stepName, roll, behavior.failBusinessRulePct());
            throw new StepBusinessRuleException("Simulated business rule violation for step: " + stepName);
        }
        roll -= behavior.failBusinessRulePct();

        // Timeout (sleep then throw)
        if (roll < behavior.timeoutPct()) {
            log.debug("Step {} simulating timeout (roll={:.2f}, threshold={:.2f})",
                stepName, roll, behavior.timeoutPct());
            sleepSafely(behavior.maxDurationMs());  // Sleep max duration before timeout
            throw new RuntimeException("Simulated timeout for step: " + stepName);
        }
        roll -= behavior.timeoutPct();

        // Normal operation - apply delay
        int duration = calculateDuration(behavior.minDurationMs(), behavior.maxDurationMs());
        if (duration > 0) {
            log.trace("Step {} simulating delay {}ms", stepName, duration);
            sleepSafely(duration);
        }
    }

    /**
     * Calculate a random duration between min and max.
     */
    private int calculateDuration(int minMs, int maxMs) {
        if (maxMs <= minMs) {
            return minMs;
        }
        return minMs + random.nextInt(maxMs - minMs);
    }

    /**
     * Sleep for specified duration, handling interruption.
     */
    private void sleepSafely(int ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during simulated delay", e);
        }
    }

    // ========== Override classifyException for transient failures ==========

    @Override
    protected ExceptionType classifyException(Exception e) {
        if (e instanceof TransientFailureException) {
            return ExceptionType.TRANSIENT;
        }
        return super.classifyException(e);
    }

    /**
     * Marker exception for transient failures injected by behavior.
     */
    public static class TransientFailureException extends RuntimeException {
        public TransientFailureException(String message) {
            super(message);
        }
    }
}
