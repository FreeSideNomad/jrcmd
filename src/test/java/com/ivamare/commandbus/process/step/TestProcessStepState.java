package com.ivamare.commandbus.process.step;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.ivamare.commandbus.e2e.process.ProbabilisticBehavior;

import java.util.HashMap;
import java.util.Map;

/**
 * Base state class for test processes with probabilistic behavior injection.
 *
 * <p>Extends ProcessStepState to add per-step behavior configuration,
 * enabling controlled testing with configurable failure rates, delays,
 * and error conditions for each step.
 *
 * <p>This class is designed for E2E testing where steps need to simulate
 * various behaviors (success, transient failures, permanent failures,
 * business rule violations, timeouts).
 *
 * <p>Usage:
 * <pre>{@code
 * public class MyTestState extends TestProcessStepState {
 *     private String myData;
 *     // ... domain fields
 * }
 *
 * // Configure behavior for a specific step
 * state.setStepBehavior("bookRisk", ProbabilisticBehavior.builder()
 *     .failTransientPct(10)
 *     .build());
 * }</pre>
 */
public abstract class TestProcessStepState extends ProcessStepState {

    /**
     * Per-step behavior configuration.
     * Key is the step name, value is the probabilistic behavior for that step.
     */
    @JsonIgnore
    private Map<String, ProbabilisticBehavior> stepBehaviors = new HashMap<>();

    /**
     * Get the probabilistic behavior configured for a specific step.
     *
     * @param stepName The step name
     * @return The configured behavior, or defaults if not configured
     */
    public ProbabilisticBehavior getBehaviorForStep(String stepName) {
        return stepBehaviors.getOrDefault(stepName, ProbabilisticBehavior.defaults());
    }

    /**
     * Set the probabilistic behavior for a specific step.
     *
     * @param stepName The step name
     * @param behavior The behavior configuration
     */
    public void setStepBehavior(String stepName, ProbabilisticBehavior behavior) {
        stepBehaviors.put(stepName, behavior);
    }

    /**
     * Configure behaviors for multiple steps at once.
     *
     * @param behaviors Map of step name to behavior
     */
    public void setStepBehaviors(Map<String, ProbabilisticBehavior> behaviors) {
        this.stepBehaviors.putAll(behaviors);
    }

    /**
     * Clear all step behaviors (reset to defaults).
     */
    public void clearStepBehaviors() {
        this.stepBehaviors.clear();
    }

    /**
     * Check if a specific step has behavior configured.
     *
     * @param stepName The step name
     * @return true if behavior is configured for this step
     */
    public boolean hasStepBehavior(String stepName) {
        return stepBehaviors.containsKey(stepName);
    }

    /**
     * Get all configured step behaviors.
     *
     * @return Unmodifiable view of step behaviors
     */
    public Map<String, ProbabilisticBehavior> getStepBehaviors() {
        return Map.copyOf(stepBehaviors);
    }
}
