package com.commandbus.process;

import java.time.Instant;
import java.util.UUID;

/**
 * Metadata for a process instance.
 *
 * @param <TState> The typed process state class
 * @param <TStep> The step enum type (must extend Enum)
 * @param domain The domain this process belongs to
 * @param processId Unique process identifier
 * @param processType Type of process (e.g., "ORDER_FULFILLMENT")
 * @param state Current process state
 * @param status Current process status
 * @param currentStep Current step being executed (nullable)
 * @param createdAt Creation timestamp
 * @param updatedAt Last update timestamp
 * @param completedAt Completion timestamp (nullable)
 * @param errorCode Application error code (nullable)
 * @param errorMessage Error message (nullable)
 */
public record ProcessMetadata<TState extends ProcessState, TStep extends Enum<TStep>>(
    String domain,
    UUID processId,
    String processType,
    TState state,
    ProcessStatus status,
    TStep currentStep,
    Instant createdAt,
    Instant updatedAt,
    Instant completedAt,
    String errorCode,
    String errorMessage
) {
    /**
     * Create a new process in PENDING status.
     */
    public static <TState extends ProcessState, TStep extends Enum<TStep>>
    ProcessMetadata<TState, TStep> create(
            String domain,
            UUID processId,
            String processType,
            TState state) {
        Instant now = Instant.now();
        return new ProcessMetadata<>(
            domain,
            processId,
            processType,
            state,
            ProcessStatus.PENDING,
            null,           // currentStep
            now,            // createdAt
            now,            // updatedAt
            null,           // completedAt
            null,           // errorCode
            null            // errorMessage
        );
    }

    /**
     * Create updated copy with new status.
     */
    public ProcessMetadata<TState, TStep> withStatus(ProcessStatus newStatus) {
        return new ProcessMetadata<>(
            domain, processId, processType, state, newStatus, currentStep,
            createdAt, Instant.now(), completedAt, errorCode, errorMessage
        );
    }

    /**
     * Create updated copy with new state.
     */
    public ProcessMetadata<TState, TStep> withState(TState newState) {
        return new ProcessMetadata<>(
            domain, processId, processType, newState, status, currentStep,
            createdAt, Instant.now(), completedAt, errorCode, errorMessage
        );
    }

    /**
     * Create updated copy with current step.
     */
    public ProcessMetadata<TState, TStep> withCurrentStep(TStep step) {
        return new ProcessMetadata<>(
            domain, processId, processType, state, status, step,
            createdAt, Instant.now(), completedAt, errorCode, errorMessage
        );
    }

    /**
     * Create updated copy with new status and step.
     */
    public ProcessMetadata<TState, TStep> withStatusAndStep(ProcessStatus newStatus, TStep step) {
        return new ProcessMetadata<>(
            domain, processId, processType, state, newStatus, step,
            createdAt, Instant.now(), completedAt, errorCode, errorMessage
        );
    }

    /**
     * Create updated copy with error information.
     */
    public ProcessMetadata<TState, TStep> withError(String code, String message) {
        return new ProcessMetadata<>(
            domain, processId, processType, state, status, currentStep,
            createdAt, Instant.now(), completedAt, code, message
        );
    }

    /**
     * Create updated copy marking completion.
     */
    public ProcessMetadata<TState, TStep> withCompletion(ProcessStatus finalStatus) {
        return new ProcessMetadata<>(
            domain, processId, processType, state, finalStatus, currentStep,
            createdAt, Instant.now(), Instant.now(), errorCode, errorMessage
        );
    }
}
