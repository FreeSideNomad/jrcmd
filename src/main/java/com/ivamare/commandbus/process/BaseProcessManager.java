package com.ivamare.commandbus.process;

import com.ivamare.commandbus.api.CommandBus;
import com.ivamare.commandbus.model.Reply;
import com.ivamare.commandbus.model.ReplyOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Base class for implementing process managers with typed state and steps.
 *
 * <p>Provides a template pattern for multi-step workflow orchestration:
 * <ul>
 *   <li>Start a process with initial state</li>
 *   <li>Execute steps by sending commands</li>
 *   <li>Handle replies to advance state</li>
 *   <li>Support compensation/saga pattern for rollback</li>
 * </ul>
 *
 * @param <TState> The typed process state class
 * @param <TStep> The step enum type
 */
public abstract class BaseProcessManager<TState extends ProcessState, TStep extends Enum<TStep>> {

    private static final Logger log = LoggerFactory.getLogger(BaseProcessManager.class);

    protected final CommandBus commandBus;
    protected final ProcessRepository processRepo;
    protected final String replyQueue;
    protected final JdbcTemplate jdbcTemplate;
    protected final TransactionTemplate transactionTemplate;

    protected BaseProcessManager(
            CommandBus commandBus,
            ProcessRepository processRepo,
            String replyQueue,
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate) {
        this.commandBus = commandBus;
        this.processRepo = processRepo;
        this.replyQueue = replyQueue;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    // ========== Abstract Methods (Template Pattern) ==========

    /**
     * Return unique process type identifier.
     */
    public abstract String getProcessType();

    /**
     * Return the domain this process operates in.
     */
    public abstract String getDomain();

    /**
     * Return the class used for state to enable deserialization.
     */
    public abstract Class<TState> getStateClass();

    /**
     * Return the step enum class for deserialization.
     */
    public abstract Class<TStep> getStepClass();

    /**
     * Create typed state from initial input data.
     */
    public abstract TState createInitialState(Map<String, Object> initialData);

    /**
     * Determine the first step based on initial state.
     */
    public abstract TStep getFirstStep(TState state);

    /**
     * Build typed command for a step.
     */
    public abstract ProcessCommand<?> buildCommand(TStep step, TState state);

    /**
     * Update state with data from reply.
     * Should return a new state instance (immutable pattern).
     */
    public abstract TState updateState(TState state, TStep step, Reply reply);

    /**
     * Determine next step based on reply and state.
     *
     * @return Next step to execute, or null if process is complete
     */
    public abstract TStep getNextStep(TStep currentStep, Reply reply, TState state);

    // ========== Optional Override Methods ==========

    /**
     * Get compensation step for a given step.
     * Override to provide compensation mapping for saga pattern.
     *
     * @return Compensation step, or null if no compensation needed
     */
    public TStep getCompensationStep(TStep step) {
        return null;
    }

    /**
     * Hook called before sending command.
     * Override to perform side effects or state mutations.
     */
    protected void beforeSendCommand(
            ProcessMetadata<TState, TStep> process,
            TStep step,
            UUID commandId,
            Map<String, Object> commandPayload,
            JdbcTemplate jdbc) {
        // Default: no-op
    }

    // ========== Public API ==========

    /**
     * Start a new process instance.
     *
     * @param initialData Initial state data for the process
     * @return The process_id (UUID) of the new process
     */
    public UUID start(Map<String, Object> initialData) {
        return transactionTemplate.execute(status -> {
            UUID processId = UUID.randomUUID();
            TState state = createInitialState(initialData);

            ProcessMetadata<TState, TStep> process = ProcessMetadata.create(
                getDomain(),
                processId,
                getProcessType(),
                state
            );

            processRepo.save(process, jdbcTemplate);

            TStep firstStep = getFirstStep(state);
            executeStep(process, firstStep, jdbcTemplate);

            return processId;
        });
    }

    /**
     * Handle incoming reply and advance process.
     */
    public void handleReply(Reply reply, ProcessMetadata<?, ?> rawProcess) {
        transactionTemplate.executeWithoutResult(status -> {
            handleReplyInternal(reply, rawProcess, jdbcTemplate);
        });
    }

    /**
     * Handle incoming reply within an existing transaction.
     */
    public void handleReply(Reply reply, ProcessMetadata<?, ?> rawProcess, JdbcTemplate jdbc) {
        handleReplyInternal(reply, rawProcess, jdbc);
    }

    // ========== Internal Implementation ==========

    @SuppressWarnings("unchecked")
    private void handleReplyInternal(Reply reply, ProcessMetadata<?, ?> rawProcess, JdbcTemplate jdbc) {
        // Deserialize state from map if needed
        TState state;
        if (rawProcess.state() instanceof MapProcessState mapState) {
            state = deserializeState(mapState.toMap());
        } else {
            state = (TState) rawProcess.state();
        }

        // Deserialize current step
        TStep currentStep = null;
        if (rawProcess.currentStep() != null) {
            // The currentStep from DB comes as a string via MapProcessState pattern
            // But ProcessMetadata stores it as the enum, which may be null from DB
            String stepName = rawProcess.currentStep().toString();
            currentStep = Enum.valueOf(getStepClass(), stepName);
        }

        ProcessMetadata<TState, TStep> process = new ProcessMetadata<>(
            rawProcess.domain(),
            rawProcess.processId(),
            rawProcess.processType(),
            state,
            rawProcess.status(),
            currentStep,
            rawProcess.createdAt(),
            rawProcess.updatedAt(),
            rawProcess.completedAt(),
            rawProcess.errorCode(),
            rawProcess.errorMessage()
        );

        // Record reply in audit log
        recordReply(process, reply, jdbc);

        // Handle cancellation from TSQ - trigger compensation
        if (reply.outcome() == ReplyOutcome.CANCELED) {
            log.info("Process {} command canceled in TSQ, running compensations", process.processId());
            runCompensations(process, jdbc);
            return;
        }

        if (currentStep == null) {
            log.error("Received reply for process {} with no current step", process.processId());
            return;
        }

        // Update state
        TState updatedState = updateState(state, currentStep, reply);
        ProcessMetadata<TState, TStep> updatedProcess = new ProcessMetadata<>(
            process.domain(),
            process.processId(),
            process.processType(),
            updatedState,
            process.status(),
            currentStep,
            process.createdAt(),
            Instant.now(),
            process.completedAt(),
            process.errorCode(),
            process.errorMessage()
        );

        // Handle failure
        if (reply.outcome() == ReplyOutcome.FAILED) {
            if (reply.isBusinessRuleFailure()) {
                // Business rule failures auto-compensate without TSQ intervention
                log.info("Process {} command failed due to business rule, auto-compensating",
                    process.processId());
                runCompensations(updatedProcess, jdbc);
            } else {
                // Other failures go to TSQ, wait for operator
                handleFailure(updatedProcess, reply, jdbc);
            }
            return;
        }

        // Determine next step
        TStep nextStep = getNextStep(currentStep, reply, updatedState);

        if (nextStep == null) {
            completeProcess(updatedProcess, jdbc);
        } else {
            executeStep(updatedProcess, nextStep, jdbc);
        }
    }

    private void executeStep(ProcessMetadata<TState, TStep> process, TStep step, JdbcTemplate jdbc) {
        ProcessCommand<?> command = buildCommand(step, process.state());
        UUID commandId = UUID.randomUUID();

        Map<String, Object> commandPayload = command.toMap();

        beforeSendCommand(process, step, commandId, commandPayload, jdbc);

        // Send command with process_id as correlation_id for reply routing
        commandBus.send(
            getDomain(),
            command.commandType(),
            commandId,
            commandPayload,
            process.processId(),  // correlationId
            replyQueue,
            null  // use default maxAttempts
        );

        ProcessMetadata<TState, TStep> updated = new ProcessMetadata<>(
            process.domain(),
            process.processId(),
            process.processType(),
            process.state(),
            ProcessStatus.WAITING_FOR_REPLY,
            step,
            process.createdAt(),
            Instant.now(),
            process.completedAt(),
            process.errorCode(),
            process.errorMessage()
        );

        // Record in audit log
        recordCommand(process, step, commandId, command.commandType(), commandPayload, jdbc);
        processRepo.update(updated, jdbc);

        log.debug("Process {} executing step {} with command {}",
            process.processId(), step, commandId);
    }

    /**
     * Run compensations in reverse order for saga pattern.
     */
    protected void runCompensations(ProcessMetadata<TState, TStep> process, JdbcTemplate jdbc) {
        List<String> completedSteps = processRepo.getCompletedSteps(
            process.domain(), process.processId(), jdbc);

        // Update status to COMPENSATING
        ProcessMetadata<TState, TStep> compensating = process.withStatus(ProcessStatus.COMPENSATING);
        processRepo.update(compensating, jdbc);

        // Run compensations in reverse order
        for (int i = completedSteps.size() - 1; i >= 0; i--) {
            String stepName = completedSteps.get(i);
            TStep step = Enum.valueOf(getStepClass(), stepName);
            TStep compStep = getCompensationStep(step);

            if (compStep != null) {
                log.debug("Running compensation {} for step {} in process {}",
                    compStep, step, process.processId());

                ProcessMetadata<TState, TStep> updated = new ProcessMetadata<>(
                    process.domain(),
                    process.processId(),
                    process.processType(),
                    process.state(),
                    ProcessStatus.COMPENSATING,
                    compStep,
                    process.createdAt(),
                    Instant.now(),
                    process.completedAt(),
                    process.errorCode(),
                    process.errorMessage()
                );
                processRepo.update(updated, jdbc);

                executeStep(updated, compStep, jdbc);
                // Note: After sending compensation command, we wait for reply
                // The reply router will call handleReply for compensation replies
                // which continues the compensation chain
                return;  // Wait for compensation reply before continuing
            }
        }

        // No more compensations needed, mark as compensated
        ProcessMetadata<TState, TStep> compensated = process.withCompletion(ProcessStatus.COMPENSATED);
        processRepo.update(compensated, jdbc);

        log.info("Process {} compensation completed", process.processId());
    }

    private void completeProcess(ProcessMetadata<TState, TStep> process, JdbcTemplate jdbc) {
        ProcessMetadata<TState, TStep> completed = process.withCompletion(ProcessStatus.COMPLETED);
        processRepo.update(completed, jdbc);
        log.info("Process {} completed successfully", process.processId());
    }

    private void handleFailure(ProcessMetadata<TState, TStep> process, Reply reply, JdbcTemplate jdbc) {
        ProcessMetadata<TState, TStep> failed = new ProcessMetadata<>(
            process.domain(),
            process.processId(),
            process.processType(),
            process.state(),
            ProcessStatus.WAITING_FOR_TSQ,
            process.currentStep(),
            process.createdAt(),
            Instant.now(),
            process.completedAt(),
            reply.errorCode(),
            reply.errorMessage()
        );
        processRepo.update(failed, jdbc);
        log.warn("Process {} step {} failed, waiting for TSQ intervention",
            process.processId(), process.currentStep());
    }

    private void recordCommand(
            ProcessMetadata<TState, TStep> process,
            TStep step,
            UUID commandId,
            String commandType,
            Map<String, Object> commandData,
            JdbcTemplate jdbc) {
        ProcessAuditEntry entry = ProcessAuditEntry.forCommand(
            step.name(), commandId, commandType, commandData);
        processRepo.logStep(process.domain(), process.processId(), entry, jdbc);
    }

    private void recordReply(ProcessMetadata<TState, TStep> process, Reply reply, JdbcTemplate jdbc) {
        ProcessAuditEntry entry = new ProcessAuditEntry(
            process.currentStep() != null ? process.currentStep().name() : "",
            reply.commandId(),
            "",  // commandType will be looked up
            null,
            Instant.now(),
            reply.outcome(),
            reply.data(),
            Instant.now()
        );
        processRepo.updateStepReply(process.domain(), process.processId(), reply.commandId(), entry, jdbc);
    }

    private TState deserializeState(Map<String, Object> data) {
        // Use reflection to call static fromMap method
        try {
            var method = getStateClass().getMethod("fromMap", Map.class);
            return getStateClass().cast(method.invoke(null, data));
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize state. Ensure " +
                getStateClass().getSimpleName() + " has a static fromMap(Map<String, Object>) method", e);
        }
    }
}
