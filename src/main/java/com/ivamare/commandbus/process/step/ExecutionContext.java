package com.ivamare.commandbus.process.step;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Per-execution context for ProcessStepManager.
 *
 * <p>Tracks execution state within a single invocation of execute():
 * <ul>
 *   <li>Process ID and state reference</li>
 *   <li>Compensation actions registered during execution</li>
 *   <li>Helper methods for replay detection</li>
 * </ul>
 *
 * <p>This context is stored in a ThreadLocal during execution and
 * provides convenience methods for the framework and step implementations.
 *
 * @param <TState> The process state type
 */
public class ExecutionContext<TState extends ProcessStepState> {

    private final UUID processId;
    private final TState state;
    private final Map<String, Consumer<TState>> compensations = new HashMap<>();

    /**
     * Create a new execution context.
     *
     * @param processId The process ID
     * @param state The current process state
     */
    public ExecutionContext(UUID processId, TState state) {
        this.processId = processId;
        this.state = state;
    }

    /**
     * Get the process ID.
     */
    public UUID processId() {
        return processId;
    }

    /**
     * Get the current state.
     */
    public TState state() {
        return state;
    }

    // ========== Step Operations ==========

    /**
     * Get a completed step for replay.
     */
    public Optional<StepRecord> getCompletedStep(String name) {
        return state.findCompletedStep(name);
    }

    /**
     * Check if a step has been completed (for replay detection).
     */
    public boolean isStepCompleted(String name) {
        return state.findCompletedStep(name).isPresent();
    }

    /**
     * Get attempt count for a step.
     */
    public int getAttemptCount(String name) {
        return state.findStep(name)
            .map(StepRecord::attemptCount)
            .orElse(0);
    }

    /**
     * Record step start.
     */
    public void recordStepStart(String name, int maxRetries, String requestJson) {
        StepRecord record = StepRecord.started(name, maxRetries, requestJson);
        state.recordStep(record);
    }

    /**
     * Record step completion.
     */
    public void recordStepCompleted(String name, String responseJson) {
        state.findStep(name).ifPresent(existing -> {
            StepRecord updated = existing.completed(responseJson);
            state.recordStep(updated);
        });
    }

    /**
     * Record step failure.
     */
    public void recordStepFailed(String name, String errorCode, String errorMessage) {
        state.findStep(name).ifPresent(existing -> {
            StepRecord updated = existing.failed(errorCode, errorMessage);
            state.recordStep(updated);
        });
    }

    /**
     * Record step retry scheduled.
     */
    public void recordStepRetry(String name, int newAttemptCount, int maxRetries,
                                Instant nextRetryAt, String errorCode, String errorMessage) {
        state.findStep(name).ifPresent(existing -> {
            StepRecord updated = existing.waitingRetry(newAttemptCount, nextRetryAt,
                errorCode, errorMessage);
            state.recordStep(updated);
        });
    }

    // ========== Wait Operations ==========

    /**
     * Get a wait record.
     */
    public Optional<WaitRecord> getWait(String name) {
        return state.findWait(name);
    }

    /**
     * Check if a wait condition has been satisfied (for replay detection).
     */
    public boolean isWaitSatisfied(String name) {
        return state.isWaitSatisfied(name);
    }

    /**
     * Record wait pending (condition not met).
     */
    public void recordWaitPending(String name, Duration timeout, Instant timeoutAt) {
        WaitRecord record = WaitRecord.pending(name, timeout, timeoutAt);
        state.recordWait(record);
    }

    /**
     * Record wait satisfied.
     */
    public void recordWaitSatisfied(String name) {
        Optional<WaitRecord> existing = state.findWait(name);
        if (existing.isPresent()) {
            state.recordWait(existing.get().markSatisfied());
        } else {
            state.recordWait(WaitRecord.satisfied(name));
        }
    }

    // ========== Side Effect Operations ==========

    /**
     * Get a side effect record.
     */
    public Optional<SideEffectRecord> getSideEffect(String name) {
        return state.findSideEffect(name);
    }

    /**
     * Record a side effect.
     */
    public void recordSideEffect(String name, String valueJson) {
        SideEffectRecord record = SideEffectRecord.create(name, valueJson);
        state.recordSideEffect(record);
    }

    // ========== Compensation Operations ==========

    /**
     * Register a compensation action for a step.
     */
    public void registerCompensation(String stepName, Consumer<TState> compensation) {
        if (compensation != null) {
            compensations.put(stepName, compensation);
        }
    }

    /**
     * Get compensation action for a step.
     */
    public Consumer<TState> getCompensation(String stepName) {
        return compensations.get(stepName);
    }

    /**
     * Get all registered compensations.
     */
    public Map<String, Consumer<TState>> getAllCompensations() {
        return new HashMap<>(compensations);
    }

    /**
     * Get completed steps in reverse order for compensation.
     */
    public List<StepRecord> getCompletedStepsReversed() {
        return state.getCompletedStepsReversed();
    }

    // ========== Error Operations ==========

    /**
     * Record an error in the state.
     */
    public void recordError(String errorCode, String errorMessage) {
        state.setErrorCode(errorCode);
        state.setErrorMessage(errorMessage);
    }

    /**
     * Clear error from the state.
     */
    public void clearError() {
        state.clearError();
    }
}
