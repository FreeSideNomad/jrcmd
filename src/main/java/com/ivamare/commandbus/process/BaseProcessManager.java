package com.ivamare.commandbus.process;

import com.ivamare.commandbus.api.CommandBus;
import com.ivamare.commandbus.model.Reply;
import com.ivamare.commandbus.model.ReplyOutcome;
import com.ivamare.commandbus.model.SendRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
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
    protected final ObjectMapper objectMapper;

    protected BaseProcessManager(
            CommandBus commandBus,
            ProcessRepository processRepo,
            String replyQueue,
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate) {
        this(commandBus, processRepo, replyQueue, jdbcTemplate, transactionTemplate, new ObjectMapper());
    }

    protected BaseProcessManager(
            CommandBus commandBus,
            ProcessRepository processRepo,
            String replyQueue,
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            ObjectMapper objectMapper) {
        this.commandBus = commandBus;
        this.processRepo = processRepo;
        this.replyQueue = replyQueue;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
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
     * Check if a step is wait-only (doesn't send a command).
     * Wait-only steps are used for multi-reply patterns where an external system
     * sends multiple replies to a single command. The process transitions to the
     * wait-only step and waits for the next external reply.
     *
     * <p>Override this method to indicate which steps should not send commands.
     * For wait-only steps, {@link #buildCommand(Enum, ProcessState)} is not called.
     *
     * @param step The step to check
     * @return true if this step should wait without sending a command
     */
    public boolean isWaitOnlyStep(TStep step) {
        return false;
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
     * Start multiple process instances in a single transaction.
     * Uses batch inserts and batch command sending for dramatically improved performance
     * when starting many processes at once.
     *
     * <p>This method is optimized for high-volume scenarios where starting thousands
     * of processes one-by-one would be prohibitively slow. It:
     * <ul>
     *   <li>Creates all process metadata in memory</li>
     *   <li>Batch inserts all processes in a single DB operation</li>
     *   <li>Batch sends all initial commands via PGMQ</li>
     *   <li>Batch inserts all audit log entries</li>
     * </ul>
     *
     * @param initialDataList List of initial data maps, one per process to start
     * @return List of process IDs (UUIDs) for all started processes, in same order as input
     */
    public List<UUID> startBatch(List<Map<String, Object>> initialDataList) {
        if (initialDataList.isEmpty()) {
            return List.of();
        }

        return transactionTemplate.execute(status -> {
            List<ProcessMetadata<TState, TStep>> processes = new ArrayList<>(initialDataList.size());
            List<SendRequest> commandRequests = new ArrayList<>(initialDataList.size());
            List<JdbcProcessRepository.ProcessAuditBatchEntry> auditEntries = new ArrayList<>(initialDataList.size());

            Instant now = Instant.now();

            // Phase 1: Create all process metadata and commands in memory
            for (Map<String, Object> initialData : initialDataList) {
                UUID processId = UUID.randomUUID();
                TState state = createInitialState(initialData);
                TStep firstStep = getFirstStep(state);

                // Create process metadata with WAITING_FOR_REPLY status
                ProcessMetadata<TState, TStep> process = new ProcessMetadata<>(
                    getDomain(),
                    processId,
                    getProcessType(),
                    state,
                    ProcessStatus.WAITING_FOR_REPLY,
                    firstStep,
                    now,
                    now,
                    null,
                    null,
                    null
                );
                processes.add(process);

                // Build command for first step (skip if wait-only)
                if (!isWaitOnlyStep(firstStep)) {
                    ProcessCommand<?> command = buildCommand(firstStep, state);
                    UUID commandId = UUID.randomUUID();
                    Map<String, Object> commandPayload = command.toMap();

                    // Create send request
                    SendRequest request = new SendRequest(
                        getDomain(),
                        command.commandType(),
                        commandId,
                        commandPayload,
                        processId,  // correlationId for reply routing
                        replyQueue,
                        null  // use default maxAttempts
                    );
                    commandRequests.add(request);

                    // Create audit entry
                    ProcessAuditEntry auditEntry = ProcessAuditEntry.forCommand(
                        firstStep.name(), commandId, command.commandType(), commandPayload
                    );
                    auditEntries.add(new JdbcProcessRepository.ProcessAuditBatchEntry(processId, auditEntry));
                }
            }

            // Phase 2: Batch insert all processes
            if (processRepo instanceof JdbcProcessRepository jdbcRepo) {
                jdbcRepo.saveBatch(new ArrayList<>(processes), jdbcTemplate);
            } else {
                // Fallback to individual saves
                for (ProcessMetadata<TState, TStep> process : processes) {
                    processRepo.save(process, jdbcTemplate);
                }
            }

            // Phase 3: Batch send all commands
            if (!commandRequests.isEmpty()) {
                commandBus.sendBatch(commandRequests);
            }

            // Phase 4: Batch insert all audit entries
            if (!auditEntries.isEmpty() && processRepo instanceof JdbcProcessRepository jdbcRepo) {
                jdbcRepo.logBatchSteps(getDomain(), auditEntries, jdbcTemplate);
            } else {
                // Fallback to individual audit logs
                for (var entry : auditEntries) {
                    processRepo.logStep(getDomain(), entry.processId(), entry.entry(), jdbcTemplate);
                }
            }

            log.info("Batch started {} processes for domain {}", processes.size(), getDomain());

            return processes.stream().map(ProcessMetadata::processId).toList();
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

    /**
     * Update state only, without changing process status or step.
     * Used for replies arriving after process reaches terminal state (COMPLETE/CANCELLED).
     * This allows late-arriving replies to still update state for audit purposes.
     */
    public void updateStateOnly(Reply reply, ProcessMetadata<?, ?> rawProcess, JdbcTemplate jdbc) {
        updateStateOnlyInternal(reply, rawProcess, jdbc);
    }

    @SuppressWarnings("unchecked")
    private void updateStateOnlyInternal(Reply reply, ProcessMetadata<?, ?> rawProcess, JdbcTemplate jdbc) {
        // Deserialize state from map if needed
        TState state;
        TStep currentStep = null;

        // First, check if currentStep is directly available
        if (rawProcess.currentStep() != null) {
            currentStep = (TStep) rawProcess.currentStep();
        }

        if (rawProcess.state() instanceof MapProcessState mapState) {
            Map<String, Object> stateMap = mapState.toMap();

            // Extract current step from state map if not already set
            if (currentStep == null) {
                String stepName = (String) stateMap.get("__current_step__");
                if (stepName != null) {
                    currentStep = Enum.valueOf(getStepClass(), stepName);
                }
            }

            // Remove internal key before deserializing state
            Map<String, Object> cleanStateMap = new java.util.HashMap<>(stateMap);
            cleanStateMap.remove("__current_step__");
            state = deserializeState(cleanStateMap);
        } else {
            state = (TState) rawProcess.state();
        }

        if (currentStep == null) {
            log.warn("Cannot update state for process {} - no current step available", rawProcess.processId());
            return;
        }

        // Update state using the standard updateState method
        TState updatedState = updateState(state, currentStep, reply);

        // Only save if state actually changed
        if (!updatedState.equals(state)) {
            ProcessMetadata<TState, TStep> updated = new ProcessMetadata<>(
                rawProcess.domain(),
                rawProcess.processId(),
                rawProcess.processType(),
                updatedState,
                rawProcess.status(),  // Keep current status (COMPLETE/CANCELLED)
                currentStep,          // Keep current step
                rawProcess.createdAt(),
                Instant.now(),        // Update timestamp
                rawProcess.completedAt(),
                rawProcess.errorCode(),
                rawProcess.errorMessage()
            );

            // Use atomic update to avoid overwriting concurrent changes
            // Pass null for step/status to preserve existing values
            updateStateAtomic(updated, updatedState, null, null, null, null, jdbc);
            log.debug("Updated state for terminal process {} with reply (status={})",
                rawProcess.processId(), rawProcess.status());
        }
    }

    // ========== Internal Implementation ==========

    /**
     * Serialize state to JSON for atomic updates.
     */
    private String serializeStateToJson(ProcessState state) {
        try {
            return objectMapper.writeValueAsString(state.toMap());
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize state for atomic update", e);
        }
    }

    /**
     * Perform atomic state update using stored procedure.
     * This avoids read-modify-write cycles and reduces lock contention.
     */
    protected void updateStateAtomic(ProcessMetadata<TState, TStep> process, TState newState,
                                      TStep newStep, ProcessStatus newStatus,
                                      String errorCode, String errorMessage, JdbcTemplate jdbc) {
        String statePatch = serializeStateToJson(newState);
        processRepo.updateStateAtomic(
            process.domain(),
            process.processId(),
            statePatch,
            newStep != null ? newStep.name() : null,
            newStatus != null ? newStatus.name() : null,
            errorCode,
            errorMessage,
            jdbc
        );
    }

    @SuppressWarnings("unchecked")
    private void handleReplyInternal(Reply reply, ProcessMetadata<?, ?> rawProcess, JdbcTemplate jdbc) {
        // Deserialize state from map if needed
        TState state;
        TStep currentStep = null;

        // First, check if currentStep is directly available (e.g., in tests)
        if (rawProcess.currentStep() != null) {
            currentStep = (TStep) rawProcess.currentStep();
        }

        if (rawProcess.state() instanceof MapProcessState mapState) {
            Map<String, Object> stateMap = mapState.toMap();

            // Extract current step from state map if not already set (stored by JdbcProcessRepository)
            if (currentStep == null) {
                String stepName = (String) stateMap.get("__current_step__");
                if (stepName != null) {
                    currentStep = Enum.valueOf(getStepClass(), stepName);
                }
            }

            // Remove the internal key before deserializing state
            Map<String, Object> cleanStateMap = new java.util.HashMap<>(stateMap);
            cleanStateMap.remove("__current_step__");
            state = deserializeState(cleanStateMap);
        } else {
            state = (TState) rawProcess.state();
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
        } else if (nextStep == currentStep && isWaitOnlyStep(nextStep)) {
            // Staying on same wait-only step (e.g., accumulating L1-L4 confirmations)
            // Just update state without resetting status - preserves COMPLETED if set by concurrent L4
            updateStateAtomic(updatedProcess, updatedState, null, null, null, null, jdbc);
            log.debug("Process {} staying on wait-only step {}, state updated",
                updatedProcess.processId(), currentStep);
        } else {
            executeStep(updatedProcess, nextStep, jdbc);
        }
    }

    private void executeStep(ProcessMetadata<TState, TStep> process, TStep step, JdbcTemplate jdbc) {
        // Check if this is a wait-only step (no command to send)
        if (isWaitOnlyStep(step)) {
            executeWaitOnlyStep(process, step, jdbc);
            return;
        }

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

        // Record in audit log
        recordCommand(process, step, commandId, command.commandType(), commandPayload, jdbc);

        // Use atomic update to avoid read-modify-write cycle
        updateStateAtomic(process, process.state(), step, ProcessStatus.WAITING_FOR_REPLY,
            null, null, jdbc);

        log.debug("Process {} executing step {} with command {}",
            process.processId(), step, commandId);
    }

    /**
     * Execute a wait-only step - transitions to the step without sending a command.
     * Used for multi-reply patterns where external systems send progressive replies.
     */
    private void executeWaitOnlyStep(ProcessMetadata<TState, TStep> process, TStep step, JdbcTemplate jdbc) {
        // Set WAITING_FOR_REPLY - the SP protects terminal status from being overwritten,
        // handling race conditions where L4 completes before SUBMIT_PAYMENT reply arrives.
        updateStateAtomic(process, process.state(), step, ProcessStatus.WAITING_FOR_REPLY,
            null, null, jdbc);

        log.debug("Process {} transitioned to wait-only step {}, waiting for external reply",
            process.processId(), step);
    }

    /**
     * Run compensations in reverse order for saga pattern.
     */
    protected void runCompensations(ProcessMetadata<TState, TStep> process, JdbcTemplate jdbc) {
        List<String> completedSteps = processRepo.getCompletedSteps(
            process.domain(), process.processId(), jdbc);

        // Update status to COMPENSATING using atomic update
        updateStateAtomic(process, process.state(), null, ProcessStatus.COMPENSATING,
            null, null, jdbc);

        // Run compensations in reverse order
        for (int i = completedSteps.size() - 1; i >= 0; i--) {
            String stepName = completedSteps.get(i);
            TStep step = Enum.valueOf(getStepClass(), stepName);
            TStep compStep = getCompensationStep(step);

            if (compStep != null) {
                log.debug("Running compensation {} for step {} in process {}",
                    compStep, step, process.processId());

                // Use atomic update for compensation step
                updateStateAtomic(process, process.state(), compStep, ProcessStatus.COMPENSATING,
                    null, null, jdbc);

                // Create updated metadata for executeStep (needed for audit logging)
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

                executeStep(updated, compStep, jdbc);
                // Note: After sending compensation command, we wait for reply
                // The reply router will call handleReply for compensation replies
                // which continues the compensation chain
                return;  // Wait for compensation reply before continuing
            }
        }

        // No more compensations needed, mark as compensated using atomic update
        updateStateAtomic(process, process.state(), null, ProcessStatus.COMPENSATED,
            null, null, jdbc);

        log.info("Process {} compensation completed", process.processId());
    }

    private void completeProcess(ProcessMetadata<TState, TStep> process, JdbcTemplate jdbc) {
        // Use atomic update - status change triggers completed_at in SP
        updateStateAtomic(process, process.state(), null, ProcessStatus.COMPLETED,
            null, null, jdbc);
        log.info("Process {} completed successfully", process.processId());
    }

    private void handleFailure(ProcessMetadata<TState, TStep> process, Reply reply, JdbcTemplate jdbc) {
        // Use atomic update with error info
        updateStateAtomic(process, process.state(), process.currentStep(), ProcessStatus.WAITING_FOR_TSQ,
            reply.errorCode(), reply.errorMessage(), jdbc);
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
