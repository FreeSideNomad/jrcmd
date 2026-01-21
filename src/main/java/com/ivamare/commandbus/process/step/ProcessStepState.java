package com.ivamare.commandbus.process.step;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.ivamare.commandbus.process.ProcessState;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Base class for all process step manager states.
 *
 * <p>Contains framework-managed execution tracking fields for:
 * <ul>
 *   <li>Step execution history (for replay)</li>
 *   <li>Wait condition history (for async responses)</li>
 *   <li>Side effect records (for deterministic replay)</li>
 *   <li>Error tracking</li>
 *   <li>Process deadline management</li>
 * </ul>
 *
 * <p>Subclasses should add domain-specific fields and can use
 * Jackson annotations for JSON serialization.
 */
public abstract class ProcessStepState implements ProcessState {

    // Process execution tracking (framework-managed)
    private List<StepRecord> stepHistory = new ArrayList<>();
    private List<WaitRecord> waitHistory = new ArrayList<>();
    private List<SideEffectRecord> sideEffects = new ArrayList<>();

    // Command step tracking (for commandStep() method)
    private Map<String, PendingCommandStep> pendingCommandSteps = new HashMap<>();
    private Map<String, CommandStepResponse<?>> commandStepResponses = new HashMap<>();

    // Error tracking
    private String errorCode;
    private String errorMessage;

    // Process deadline (set in initial state, checked by worker)
    private Instant processDeadline;

    // ========== Step History Methods ==========

    public List<StepRecord> getStepHistory() {
        return stepHistory;
    }

    public void setStepHistory(List<StepRecord> stepHistory) {
        this.stepHistory = stepHistory != null ? stepHistory : new ArrayList<>();
    }

    /**
     * Find a completed step by name for replay.
     */
    public Optional<StepRecord> findCompletedStep(String name) {
        return stepHistory.stream()
            .filter(s -> s.name().equals(name) && s.status() == StepStatus.COMPLETED)
            .findFirst();
    }

    /**
     * Find step record by name (any status).
     */
    public Optional<StepRecord> findStep(String name) {
        return stepHistory.stream()
            .filter(s -> s.name().equals(name))
            .findFirst();
    }

    /**
     * Add or update a step record.
     */
    public void recordStep(StepRecord record) {
        // Remove existing record for this step if present
        stepHistory.removeIf(s -> s.name().equals(record.name()));
        stepHistory.add(record);
    }

    /**
     * Get completed steps in execution order (for compensation).
     */
    @JsonIgnore
    public List<StepRecord> getCompletedStepsInOrder() {
        return stepHistory.stream()
            .filter(s -> s.status() == StepStatus.COMPLETED)
            .toList();
    }

    /**
     * Get completed steps in reverse order (for compensation).
     */
    @JsonIgnore
    public List<StepRecord> getCompletedStepsReversed() {
        List<StepRecord> completed = new ArrayList<>(getCompletedStepsInOrder());
        java.util.Collections.reverse(completed);
        return completed;
    }

    // ========== Wait History Methods ==========

    public List<WaitRecord> getWaitHistory() {
        return waitHistory;
    }

    public void setWaitHistory(List<WaitRecord> waitHistory) {
        this.waitHistory = waitHistory != null ? waitHistory : new ArrayList<>();
    }

    /**
     * Find a wait record by name.
     */
    public Optional<WaitRecord> findWait(String name) {
        return waitHistory.stream()
            .filter(w -> w.name().equals(name))
            .findFirst();
    }

    /**
     * Check if a wait condition has been satisfied.
     */
    public boolean isWaitSatisfied(String name) {
        return findWait(name)
            .map(WaitRecord::satisfied)
            .orElse(false);
    }

    /**
     * Record a wait condition.
     */
    public void recordWait(WaitRecord record) {
        // Remove existing record for this wait if present
        waitHistory.removeIf(w -> w.name().equals(record.name()));
        waitHistory.add(record);
    }

    // ========== Side Effect Methods ==========

    public List<SideEffectRecord> getSideEffects() {
        return sideEffects;
    }

    public void setSideEffects(List<SideEffectRecord> sideEffects) {
        this.sideEffects = sideEffects != null ? sideEffects : new ArrayList<>();
    }

    /**
     * Find a side effect record by name.
     */
    public Optional<SideEffectRecord> findSideEffect(String name) {
        return sideEffects.stream()
            .filter(s -> s.name().equals(name))
            .findFirst();
    }

    /**
     * Record a side effect.
     */
    public void recordSideEffect(SideEffectRecord record) {
        // Remove existing record if present (shouldn't happen in normal flow)
        sideEffects.removeIf(s -> s.name().equals(record.name()));
        sideEffects.add(record);
    }

    // ========== Command Step Tracking ==========

    public Map<String, PendingCommandStep> getPendingCommandSteps() {
        return pendingCommandSteps;
    }

    public void setPendingCommandSteps(Map<String, PendingCommandStep> pendingCommandSteps) {
        this.pendingCommandSteps = pendingCommandSteps != null ? pendingCommandSteps : new HashMap<>();
    }

    public Map<String, CommandStepResponse<?>> getCommandStepResponses() {
        return commandStepResponses;
    }

    public void setCommandStepResponses(Map<String, CommandStepResponse<?>> commandStepResponses) {
        this.commandStepResponses = commandStepResponses != null ? commandStepResponses : new HashMap<>();
    }

    /**
     * Store a pending command step request.
     *
     * @param stepName the step name
     * @param commandId the command ID sent to the target domain
     * @param timeoutSeconds timeout in seconds
     */
    public void storePendingCommandStep(String stepName, java.util.UUID commandId, long timeoutSeconds) {
        pendingCommandSteps.put(stepName, PendingCommandStep.create(stepName, commandId, timeoutSeconds));
    }

    /**
     * Check if waiting for a command step response.
     *
     * @param stepName the step name
     * @return true if a command has been sent for this step
     */
    public boolean isWaitingForCommandStep(String stepName) {
        return pendingCommandSteps.containsKey(stepName);
    }

    /**
     * Get the pending command ID for a step.
     *
     * @param stepName the step name
     * @return the command ID, or empty if not waiting
     */
    public Optional<java.util.UUID> getPendingCommandId(String stepName) {
        PendingCommandStep pending = pendingCommandSteps.get(stepName);
        return pending != null ? Optional.of(pending.commandId()) : Optional.empty();
    }

    /**
     * Get the pending command step record.
     *
     * @param stepName the step name
     * @return the pending command step, or empty if not waiting
     */
    public Optional<PendingCommandStep> getPendingCommandStep(String stepName) {
        return Optional.ofNullable(pendingCommandSteps.get(stepName));
    }

    /**
     * Store a received command step response.
     *
     * @param stepName the step name
     * @param response the response received
     */
    public void storeCommandStepResponse(String stepName, CommandStepResponse<?> response) {
        commandStepResponses.put(stepName, response);
    }

    /**
     * Get stored response for a command step.
     *
     * @param stepName the step name
     * @return the response, or empty if not yet received
     */
    @SuppressWarnings("unchecked")
    public <T> Optional<CommandStepResponse<T>> getCommandStepResponse(String stepName) {
        CommandStepResponse<?> response = commandStepResponses.get(stepName);
        return response != null ? Optional.of((CommandStepResponse<T>) response) : Optional.empty();
    }

    /**
     * Clear command step state after processing.
     * Called after response has been processed (success or final failure).
     *
     * @param stepName the step name
     */
    public void clearCommandStepState(String stepName) {
        pendingCommandSteps.remove(stepName);
        commandStepResponses.remove(stepName);
    }

    /**
     * Check if a command step has timed out.
     *
     * @param stepName the step name
     * @return true if the command step has timed out
     */
    public boolean isCommandStepTimedOut(String stepName) {
        PendingCommandStep pending = pendingCommandSteps.get(stepName);
        return pending != null && pending.isTimedOut();
    }

    // ========== Error Tracking ==========

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    /**
     * Check if the state has an error recorded.
     */
    @JsonIgnore
    public boolean hasError() {
        return errorCode != null || errorMessage != null;
    }

    /**
     * Clear error information.
     */
    public void clearError() {
        this.errorCode = null;
        this.errorMessage = null;
    }

    // ========== Process Deadline ==========

    public Instant getProcessDeadline() {
        return processDeadline;
    }

    public void setProcessDeadline(Instant processDeadline) {
        this.processDeadline = processDeadline;
    }

    /**
     * Check if the process deadline has been exceeded.
     */
    @JsonIgnore
    public boolean isDeadlineExceeded() {
        return processDeadline != null && Instant.now().isAfter(processDeadline);
    }

    // ========== ProcessState Implementation ==========

    /**
     * Serialize state to JSON-compatible map.
     *
     * <p>Note: The ProcessStepManager uses Jackson ObjectMapper directly
     * for JSON serialization (not this method) to support complex types.
     * This implementation is provided for compatibility with the ProcessState
     * interface and basic use cases.
     */
    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();

        // Framework-managed fields
        map.put("stepHistory", stepHistory);
        map.put("waitHistory", waitHistory);
        map.put("sideEffects", sideEffects);

        // Command step tracking
        if (!pendingCommandSteps.isEmpty()) {
            map.put("pendingCommandSteps", pendingCommandSteps);
        }
        if (!commandStepResponses.isEmpty()) {
            map.put("commandStepResponses", commandStepResponses);
        }

        if (errorCode != null) {
            map.put("errorCode", errorCode);
        }
        if (errorMessage != null) {
            map.put("errorMessage", errorMessage);
        }
        if (processDeadline != null) {
            map.put("processDeadline", processDeadline.toString());
        }

        return map;
    }
}
