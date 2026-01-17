package com.ivamare.commandbus.process.step;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ivamare.commandbus.model.ReplyOutcome;
import com.ivamare.commandbus.process.ProcessAuditEntry;
import com.ivamare.commandbus.process.ProcessRepository;
import com.ivamare.commandbus.process.ProcessStatus;
import com.ivamare.commandbus.process.ratelimit.Bucket4jRateLimiter;
import com.ivamare.commandbus.process.step.exceptions.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Base class for implementing process managers with deterministic replay.
 *
 * <p>ProcessStepManager uses a Temporal.io-inspired approach where workflows
 * are written as sequential code with deterministic replay support. Key features:
 *
 * <ul>
 *   <li><b>step()</b> - Execute an operation with automatic retry and compensation</li>
 *   <li><b>wait()</b> - Pause execution until a condition is met or async response arrives</li>
 *   <li><b>sideEffect()</b> - Execute non-deterministic operations with cached results</li>
 *   <li><b>Deterministic replay</b> - Re-execution returns cached results, not re-executing steps</li>
 *   <li><b>Saga compensation</b> - Automatic rollback on business rule failures</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * public class PaymentProcess extends ProcessStepManager<PaymentState> {
 *
 *     protected void execute(PaymentState state) {
 *         // Step 1: Book risk (with compensation)
 *         String riskRef = step("bookRisk",
 *             s -> riskService.book(s.getAmount()),
 *             3, // maxRetries
 *             s -> riskService.release(s.getRiskReference()));
 *         state.setRiskReference(riskRef);
 *
 *         // Step 2: Submit to network
 *         String networkRef = step("submitPayment",
 *             s -> networkService.submit(s));
 *         state.setNetworkReference(networkRef);
 *
 *         // Wait for L1 confirmation
 *         wait("awaitL1", () -> state.getL1ReceivedAt() != null);
 *
 *         // L4 wait with timeout
 *         wait("awaitL4", () -> state.getL4ReceivedAt() != null,
 *              Duration.ofHours(4));
 *     }
 * }
 * }</pre>
 *
 * @param <TState> The typed process state class extending ProcessStepState
 */
public abstract class ProcessStepManager<TState extends ProcessStepState> {

    private static final Logger log = LoggerFactory.getLogger(ProcessStepManager.class);

    protected final ProcessRepository processRepo;
    protected final JdbcTemplate jdbcTemplate;
    protected final TransactionTemplate transactionTemplate;
    protected final ObjectMapper objectMapper;
    protected final Bucket4jRateLimiter rateLimiter;  // nullable for backward compatibility

    // Thread-local execution context (set during execute())
    protected final ThreadLocal<ExecutionContext<TState>> currentContext = new ThreadLocal<>();

    // ========== Constructor ==========

    protected ProcessStepManager(
            ProcessRepository processRepo,
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate) {
        this(processRepo, jdbcTemplate, transactionTemplate, null, createObjectMapper());
    }

    protected ProcessStepManager(
            ProcessRepository processRepo,
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            ObjectMapper objectMapper) {
        this(processRepo, jdbcTemplate, transactionTemplate, null, objectMapper);
    }

    protected ProcessStepManager(
            ProcessRepository processRepo,
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            Bucket4jRateLimiter rateLimiter) {
        this(processRepo, jdbcTemplate, transactionTemplate, rateLimiter, createObjectMapper());
    }

    protected ProcessStepManager(
            ProcessRepository processRepo,
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            Bucket4jRateLimiter rateLimiter,
            ObjectMapper objectMapper) {
        this.processRepo = processRepo;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    private static ObjectMapper createObjectMapper() {
        return new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    // ========== Abstract Methods ==========

    /**
     * Return unique process type identifier.
     */
    public abstract String getProcessType();

    /**
     * Return the domain this process operates in.
     */
    public abstract String getDomain();

    /**
     * Return the state class for deserialization.
     */
    public abstract Class<TState> getStateClass();

    /**
     * Execute the process workflow.
     * Called for both initial execution and replay.
     */
    protected abstract void execute(TState state);

    // ========== Optional Overrides ==========

    /**
     * Classify exceptions to determine handling behavior.
     * Override to customize exception handling.
     *
     * @param e The exception to classify
     * @return The exception type (TRANSIENT, BUSINESS, or PERMANENT)
     */
    protected ExceptionType classifyException(Exception e) {
        if (e instanceof TerminalFailureException) {
            return ExceptionType.TERMINAL;
        }
        if (e instanceof StepBusinessRuleException) {
            return ExceptionType.BUSINESS;
        }
        if (e instanceof RateLimitExceededException) {
            return ExceptionType.TRANSIENT;
        }
        // Default: fail fast to TSQ
        return ExceptionType.PERMANENT;
    }

    /**
     * Default timeout for wait() calls without explicit timeout.
     */
    protected Duration getDefaultWaitTimeout() {
        return Duration.ofHours(1);
    }

    /**
     * Action when process deadline is exceeded.
     */
    protected DeadlineAction getDeadlineAction() {
        return DeadlineAction.TSQ;
    }

    // ========== Public API ==========

    /**
     * Start a new process with immediate execution.
     *
     * @param initialState Initial state for the process
     * @return The process ID (UUID)
     */
    public UUID start(TState initialState) {
        return start(initialState, StartOptions.defaults());
    }

    /**
     * Start a new process with options.
     *
     * @param initialState Initial state for the process
     * @param options Start options (immediate or deferred)
     * @return The process ID (UUID)
     */
    public UUID start(TState initialState, StartOptions options) {
        return transactionTemplate.execute(status -> {
            UUID processId = UUID.randomUUID();
            Instant now = Instant.now();

            // Save process with execution_model = PROCESS_STEP
            saveProcessDirect(processId, initialState, now);

            log.info("Created ProcessStepManager process {} type={}", processId, getProcessType());

            // Execute immediately if requested
            if (options.isExecuteImmediately()) {
                executeProcess(processId, initialState);
            }

            return processId;
        });
    }

    /**
     * Start multiple processes in a batch.
     *
     * @param initialStates List of initial states for each process
     * @return List of process IDs
     */
    public List<UUID> startBatch(List<TState> initialStates) {
        return startBatch(initialStates, BatchOptions.defaults());
    }

    /**
     * Start multiple processes in a batch with options.
     *
     * @param initialStates List of initial states for each process
     * @param options Batch options
     * @return List of process IDs
     */
    public List<UUID> startBatch(List<TState> initialStates, BatchOptions options) {
        if (initialStates.isEmpty()) {
            return List.of();
        }

        return transactionTemplate.execute(status -> {
            List<UUID> processIds = new ArrayList<>(initialStates.size());
            Instant now = Instant.now();
            UUID batchId = options.getBatchId();

            for (TState initialState : initialStates) {
                UUID processId = UUID.randomUUID();
                processIds.add(processId);
                saveProcessDirect(processId, initialState, now, batchId);
            }

            log.info("Batch created {} ProcessStepManager processes type={} batchId={}",
                processIds.size(), getProcessType(), batchId);

            // Execute immediately if requested (rare for batches)
            if (options.isExecuteImmediately()) {
                for (int i = 0; i < processIds.size(); i++) {
                    executeProcess(processIds.get(i), initialStates.get(i));
                }
            }

            return processIds;
        });
    }

    /**
     * Process an async response and resume the process.
     *
     * @param processId The process ID
     * @param stateUpdater Function to update state with async response data
     */
    public void processAsyncResponse(UUID processId, Consumer<TState> stateUpdater) {
        transactionTemplate.executeWithoutResult(status -> {
            // Load current state
            TState state = loadState(processId);

            // Apply the state update
            stateUpdater.accept(state);

            // Save updated state
            persistState(processId, state);

            // Check if we should resume (wait condition may now be satisfied)
            ProcessStatus currentStatus = getProcessStatus(processId);

            // Only resume if waiting for async
            if (currentStatus == ProcessStatus.WAITING_FOR_ASYNC) {
                log.debug("Async response received for process {}, resuming execution", processId);
                executeProcess(processId, state);
            } else {
                log.debug("Async response received for process {} (status={}), state updated but not resumed",
                    processId, currentStatus);
            }
        });
    }

    /**
     * Get the current status of a process.
     */
    protected ProcessStatus getProcessStatus(UUID processId) {
        String sql = "SELECT status FROM commandbus.process WHERE domain = ? AND process_id = ?";
        String statusStr = jdbcTemplate.queryForObject(sql, String.class, getDomain(), processId);
        return ProcessStatus.valueOf(statusStr);
    }

    /**
     * Resume a process (for worker pickup).
     *
     * @param processId The process ID to resume
     */
    public void resume(UUID processId) {
        transactionTemplate.executeWithoutResult(status -> {
            TState state = loadState(processId);
            executeProcess(processId, state);
        });
    }

    // ========== Timeout and Deadline Handling ==========

    /**
     * Handle wait timeout for a process.
     * Called by the worker when a wait condition times out.
     *
     * @param processId The process ID with expired wait
     */
    public void handleWaitTimeout(UUID processId) {
        transactionTemplate.executeWithoutResult(status -> {
            TState state = loadState(processId);

            // Get the current wait name for audit logging
            String currentWait = getCurrentWaitName(processId);
            String errorMessage = "Wait condition '" + currentWait + "' timed out for process " + processId;

            // Record the timeout in state
            state.setErrorCode("WAIT_TIMEOUT");
            state.setErrorMessage(errorMessage);

            // Create audit entry for timeout
            ProcessAuditEntry auditEntry = createAuditEntry(
                currentWait, "WAIT_TIMEOUT", Instant.now(), ReplyOutcome.FAILED,
                Map.of("errorCode", "WAIT_TIMEOUT", "errorMessage", errorMessage)
            );

            // Atomic update: state + status + audit in single stored procedure call
            updateStateWithAudit(processId, state,
                ProcessStatus.WAITING_FOR_TSQ.name(),
                "WAIT_TIMEOUT", errorMessage,
                null, null, null,
                auditEntry
            );

            log.warn("Process {} wait '{}' timed out, moved to TSQ", processId, currentWait);
        });
    }

    /**
     * Get the current wait name for a process.
     */
    protected String getCurrentWaitName(UUID processId) {
        String sql = "SELECT current_wait FROM commandbus.process WHERE domain = ? AND process_id = ?";
        try {
            return jdbcTemplate.queryForObject(sql, String.class, getDomain(), processId);
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * Handle deadline exceeded for a process.
     * Called by the worker when a process deadline is exceeded.
     *
     * @param processId The process ID with exceeded deadline
     */
    public void handleDeadlineExceeded(UUID processId) {
        transactionTemplate.executeWithoutResult(status -> {
            TState state = loadState(processId);

            DeadlineAction action = getDeadlineAction();
            log.info("Process {} deadline exceeded, action: {}", processId, action);

            String errorMessage = "Process deadline exceeded";
            ProcessAuditEntry auditEntry = createAuditEntry(
                "DEADLINE", "DEADLINE_EXCEEDED", Instant.now(), ReplyOutcome.FAILED,
                Map.of("errorCode", "DEADLINE_EXCEEDED", "errorMessage", errorMessage, "action", action.name())
            );

            switch (action) {
                case TSQ -> {
                    state.setErrorCode("DEADLINE_EXCEEDED");
                    state.setErrorMessage(errorMessage);

                    updateStateWithAudit(processId, state,
                        ProcessStatus.WAITING_FOR_TSQ.name(),
                        "DEADLINE_EXCEEDED", errorMessage,
                        null, null, null,
                        auditEntry
                    );
                }
                case COMPENSATE -> {
                    runCompensations(processId, state);

                    updateStateWithAudit(processId, state,
                        ProcessStatus.COMPENSATED.name(),
                        "DEADLINE_EXCEEDED", errorMessage,
                        null, null, null,
                        auditEntry
                    );
                }
                case FAIL -> {
                    updateStateWithAudit(processId, state,
                        ProcessStatus.FAILED.name(),
                        "DEADLINE_EXCEEDED", errorMessage,
                        null, null, null,
                        auditEntry
                    );
                }
            }
        });
    }

    /**
     * Get the execution model for this manager.
     * Used by the worker to filter processes.
     *
     * @return "PROCESS_STEP"
     */
    public String getExecutionModel() {
        return "PROCESS_STEP";
    }

    // ========== TSQ Operations ==========

    /**
     * Retry a failed process from TSQ.
     *
     * @param processId The process ID to retry
     */
    public void retry(UUID processId) {
        transactionTemplate.executeWithoutResult(status -> {
            TState state = loadState(processId);
            state.clearError();
            persistState(processId, state);

            // Update status and resume
            processRepo.updateStateAtomicStep(
                getDomain(), processId, null,
                null, ProcessStatus.PENDING.name(),
                null, null, null, null, null,
                jdbcTemplate
            );

            executeProcess(processId, state);
        });
    }

    /**
     * Cancel a process from TSQ.
     *
     * @param processId The process ID to cancel
     * @param runCompensations Whether to run compensation actions
     */
    public void cancelOverride(UUID processId, boolean runCompensations) {
        transactionTemplate.executeWithoutResult(status -> {
            TState state = loadState(processId);

            if (runCompensations) {
                runCompensations(processId, state);
            }

            processRepo.updateStateAtomicStep(
                getDomain(), processId, null,
                null, ProcessStatus.CANCELED.name(),
                null, null, null, null, null,
                jdbcTemplate
            );

            // Call hook for subclasses to update external state
            onProcessCanceled(processId, state);

            log.info("Process {} canceled (compensations={})", processId, runCompensations);
        });
    }

    /**
     * Complete a process from TSQ with optional state overrides.
     *
     * @param processId The process ID to complete
     * @param stateOverrides Optional state overrides to apply
     */
    public void completeOverride(UUID processId, Map<String, Object> stateOverrides) {
        transactionTemplate.executeWithoutResult(status -> {
            String statePatch = null;
            if (stateOverrides != null && !stateOverrides.isEmpty()) {
                try {
                    statePatch = objectMapper.writeValueAsString(stateOverrides);
                } catch (JsonProcessingException e) {
                    throw new RuntimeException("Failed to serialize state overrides", e);
                }
            }

            processRepo.updateStateAtomicStep(
                getDomain(), processId, statePatch,
                null, ProcessStatus.COMPLETED.name(),
                null, null, null, null, null,
                jdbcTemplate
            );

            // Load state after update to include any state patches
            TState state = loadState(processId);

            // Call hook for subclasses to update external state
            onProcessCompleted(processId, state);

            log.info("Process {} completed via override", processId);
        });
    }

    /**
     * Hook called when a process is canceled via TSQ.
     * Override to update external state (e.g., payment status).
     *
     * @param processId The canceled process ID
     * @param state The process state at cancellation time
     */
    protected void onProcessCanceled(UUID processId, TState state) {
        // Default: no-op. Override in subclass to update external state.
    }

    /**
     * Hook called when a process is completed via TSQ override.
     * Override to update external state (e.g., payment status).
     *
     * @param processId The completed process ID
     * @param state The process state at completion time
     */
    protected void onProcessCompleted(UUID processId, TState state) {
        // Default: no-op. Override in subclass to update external state.
    }

    // ========== Step Methods ==========

    /**
     * Execute a step with automatic retry and replay support.
     *
     * @param name Unique step name within this process
     * @param action The action to execute
     * @return The result of the action
     */
    protected <R> R step(String name, java.util.function.Function<TState, R> action) {
        return step(name, StepOptions.<TState, R>builder()
            .action(action)
            .build());
    }

    /**
     * Execute a step with retry support.
     *
     * @param name Unique step name within this process
     * @param action The action to execute
     * @param maxRetries Maximum retry attempts
     * @return The result of the action
     */
    protected <R> R step(String name, java.util.function.Function<TState, R> action, int maxRetries) {
        return step(name, StepOptions.<TState, R>builder()
            .action(action)
            .maxRetries(maxRetries)
            .build());
    }

    /**
     * Execute a step with retry and compensation support.
     *
     * @param name Unique step name within this process
     * @param action The action to execute
     * @param maxRetries Maximum retry attempts
     * @param compensation Compensation action for saga rollback
     * @return The result of the action
     */
    protected <R> R step(String name, java.util.function.Function<TState, R> action,
                         int maxRetries, Consumer<TState> compensation) {
        return step(name, StepOptions.<TState, R>builder()
            .action(action)
            .maxRetries(maxRetries)
            .compensation(compensation)
            .build());
    }

    /**
     * Execute a step with full options.
     *
     * @param name Unique step name within this process
     * @param options Step configuration options
     * @return The result of the action
     */
    protected <R> R step(String name, StepOptions<TState, R> options) {
        ExecutionContext<TState> ctx = currentContext.get();
        if (ctx == null) {
            throw new IllegalStateException("step() called outside of execute() context");
        }

        TState state = ctx.state();

        // Check if step already completed (replay)
        var completedStep = ctx.getCompletedStep(name);
        if (completedStep.isPresent()) {
            log.debug("Replaying completed step {} for process {}", name, ctx.processId());
            return deserializeResult(completedStep.get().responseJson(), options);
        }

        // Check if step is waiting for retry
        var existingStep = state.findStep(name);
        if (existingStep.isPresent() && existingStep.get().status() == StepStatus.WAITING_RETRY) {
            // This is a retry - check if it's due
            StepRecord stepRecord = existingStep.get();
            if (stepRecord.nextRetryAt() != null && Instant.now().isBefore(stepRecord.nextRetryAt())) {
                // Not due yet - throw to pause
                throw new WaitingForRetryException(name, stepRecord.nextRetryAt());
            }
        }

        // Crash recovery: Check for incomplete step execution (status=STARTED means worker crashed mid-execution)
        if (existingStep.isPresent() && existingStep.get().status() == StepStatus.STARTED) {
            StepRecord incompleteStep = existingStep.get();
            int existingAttempts = incompleteStep.attemptCount();

            // Check if retries are exhausted
            // maxRetries=0 means 1 attempt allowed, maxRetries=1 means 2 attempts, etc.
            // If attemptCount > maxRetries, we've exceeded all allowed attempts
            if (existingAttempts > options.maxRetries()) {
                log.warn("Step {} in process {} has exhausted retries after crash (attempts={}, maxRetries={}), moving to TSQ",
                    name, ctx.processId(), existingAttempts, options.maxRetries());
                ctx.recordStepFailed(name, "RETRIES_EXHAUSTED_CRASH_RECOVERY",
                    "Previous execution did not complete and retries exhausted");
                logStepFailure(ctx.processId(), name, incompleteStep.startedAt(),
                    "RETRIES_EXHAUSTED_CRASH_RECOVERY", "Previous step execution did not complete (possible crash) and no retries remaining");
                throw new StepFailedException(name, "RETRIES_EXHAUSTED_CRASH_RECOVERY",
                    "Previous step execution did not complete (possible crash) and no retries remaining", null);
            }

            // Still have retries - proceed with re-execution
            // Don't increment attemptCount since we're re-trying the same attempt that failed
            log.info("Step {} in process {} was incomplete (crash recovery), re-executing (attempt={}/{})",
                name, ctx.processId(), existingAttempts, options.maxRetries() + 1);
        }

        // Register compensation
        if (options.hasCompensation()) {
            ctx.registerCompensation(name, options.compensation());
        }

        // Record step start
        String requestJson = serializeRequest(state, options);
        // For crash recovery (STARTED status), keep same attemptCount; otherwise increment
        boolean isCrashRecovery = existingStep.isPresent() && existingStep.get().status() == StepStatus.STARTED;
        int attemptCount = isCrashRecovery
            ? existingStep.get().attemptCount()  // Same attempt (re-try after crash)
            : existingStep.map(s -> s.attemptCount()).orElse(0) + 1;  // New attempt
        StepRecord startedRecord = new StepRecord(
            name, StepStatus.STARTED, attemptCount, options.maxRetries(),
            Instant.now(), null, requestJson, null, null, null, null
        );
        state.recordStep(startedRecord);
        persistState(ctx.processId(), state);

        try {
            // Rate limiting (if configured) - inside try so exceptions are handled
            if (options.hasRateLimiting() && rateLimiter != null) {
                if (!rateLimiter.acquire(options.rateLimitKey(), options.rateLimitTimeout())) {
                    throw new RateLimitExceededException(
                        "Rate limit exceeded for " + options.rateLimitKey(),
                        options.rateLimitKey());
                }
            }

            // Execute the action
            R result = options.action().apply(state);

            // Record success
            String responseJson = serializeResult(result);
            ctx.recordStepCompleted(name, responseJson);
            persistState(ctx.processId(), state);

            // Log to audit trail
            logStepSuccess(ctx.processId(), name, startedRecord.startedAt(), responseJson);

            log.debug("Step {} completed for process {}", name, ctx.processId());
            return result;

        } catch (Exception e) {
            return handleStepException(name, options, ctx, e, startedRecord.startedAt());
        }
    }

    // ========== Wait Methods ==========

    /**
     * Wait until a condition is met.
     *
     * @param name Unique wait name within this process
     * @param condition Supplier that returns true when condition is met
     */
    protected void wait(String name, Supplier<Boolean> condition) {
        wait(name, condition, null);
    }

    /**
     * Wait until a condition is met, with timeout.
     *
     * @param name Unique wait name within this process
     * @param condition Supplier that returns true when condition is met
     * @param timeout Maximum time to wait (null uses default)
     */
    protected void wait(String name, Supplier<Boolean> condition, Duration timeout) {
        ExecutionContext<TState> ctx = currentContext.get();
        if (ctx == null) {
            throw new IllegalStateException("wait() called outside of execute() context");
        }

        TState state = ctx.state();

        // Check if wait already satisfied (replay)
        if (ctx.isWaitSatisfied(name)) {
            log.debug("Replaying satisfied wait {} for process {}", name, ctx.processId());
            return;
        }

        // Evaluate condition
        if (condition.get()) {
            // Condition met - record and continue
            ctx.recordWaitSatisfied(name);
            persistState(ctx.processId(), state);
            // Log to audit trail
            logWaitSatisfied(ctx.processId(), name, Instant.now());
            log.debug("Wait {} satisfied for process {}", name, ctx.processId());
            return;
        }

        // Condition not met - pause execution
        Duration effectiveTimeout = timeout != null ? timeout : getDefaultWaitTimeout();
        Instant timeoutAt = Instant.now().plus(effectiveTimeout);
        ctx.recordWaitPending(name, effectiveTimeout, timeoutAt);
        persistState(ctx.processId(), state);

        // Update denormalized columns for scheduler
        processRepo.updateStateAtomicStep(
            getDomain(), ctx.processId(), null,
            null, ProcessStatus.WAITING_FOR_ASYNC.name(),
            null, null, null, timeoutAt, name,
            jdbcTemplate
        );

        log.debug("Process {} waiting at {} (timeout={})", ctx.processId(), name, effectiveTimeout);
        throw new WaitConditionNotMetException(name);
    }

    // ========== Side Effect Methods ==========

    /**
     * Execute a side effect with cached result.
     * Side effects are non-deterministic operations (random, timestamps, etc.)
     * that should return the same value on replay.
     *
     * @param name Unique side effect name within this process
     * @param operation The operation to execute
     * @return The result (cached on replay)
     */
    protected <R> R sideEffect(String name, Supplier<R> operation) {
        ExecutionContext<TState> ctx = currentContext.get();
        if (ctx == null) {
            throw new IllegalStateException("sideEffect() called outside of execute() context");
        }

        TState state = ctx.state();

        // Check if side effect already recorded (replay)
        var existing = ctx.getSideEffect(name);
        if (existing.isPresent()) {
            log.debug("Replaying side effect {} for process {}", name, ctx.processId());
            return deserializeSideEffect(existing.get().valueJson());
        }

        // Execute and record
        R result = operation.get();
        String valueJson = serializeResult(result);
        ctx.recordSideEffect(name, valueJson);
        persistState(ctx.processId(), state);

        log.debug("Recorded side effect {} for process {}", name, ctx.processId());
        return result;
    }

    // ========== Internal Methods ==========

    /**
     * Execute the process workflow.
     */
    protected void executeProcess(UUID processId, TState state) {
        ExecutionContext<TState> ctx = new ExecutionContext<>(processId, state);
        currentContext.set(ctx);

        try {
            // Update status to EXECUTING
            processRepo.updateStateAtomicStep(
                getDomain(), processId, null,
                null, ProcessStatus.EXECUTING.name(),
                null, null, null, null, null,
                jdbcTemplate
            );

            // Run the workflow
            execute(state);

            // Workflow completed successfully
            processRepo.updateStateAtomicStep(
                getDomain(), processId, null,
                null, ProcessStatus.COMPLETED.name(),
                null, null, null, null, null,
                jdbcTemplate
            );

            log.info("Process {} completed successfully", processId);

        } catch (WaitConditionNotMetException e) {
            // Process is waiting for async response - already persisted
            log.debug("Process {} paused at wait: {}", processId, e.getWaitName());

        } catch (WaitingForRetryException e) {
            // Process is waiting for retry - already persisted
            log.debug("Process {} scheduled for retry at {}", processId, e.getNextRetryAt());

        } catch (StepFailedException e) {
            // Step failed permanently - move to TSQ
            ctx.recordError(e.getErrorCode(), e.getMessage());
            persistState(processId, state);

            processRepo.updateStateAtomicStep(
                getDomain(), processId, null,
                null, ProcessStatus.WAITING_FOR_TSQ.name(),
                e.getErrorCode(), e.getMessage(), null, null, null,
                jdbcTemplate
            );

            log.warn("Process {} step {} failed: {}", processId, e.getStepName(), e.getMessage());

        } catch (Exception e) {
            // Unexpected error - move to TSQ
            ctx.recordError("UNEXPECTED_ERROR", e.getMessage());
            persistState(processId, state);

            processRepo.updateStateAtomicStep(
                getDomain(), processId, null,
                null, ProcessStatus.WAITING_FOR_TSQ.name(),
                "UNEXPECTED_ERROR", e.getMessage(), null, null, null,
                jdbcTemplate
            );

            log.error("Process {} failed with unexpected error", processId, e);

        } finally {
            currentContext.remove();
        }
    }

    /**
     * Handle step exception based on classification.
     */
    private <R> R handleStepException(String name, StepOptions<TState, R> options,
                                      ExecutionContext<TState> ctx, Exception e, Instant startedAt) {
        ExceptionType type = classifyException(e);
        TState state = ctx.state();
        int currentAttempt = ctx.getAttemptCount(name);

        switch (type) {
            case TRANSIENT -> {
                if (currentAttempt < options.maxRetries()) {
                    // Schedule retry with exponential backoff
                    Duration delay = calculateBackoff(currentAttempt, options.retryDelay());
                    Instant nextRetry = Instant.now().plus(delay);

                    ctx.recordStepRetry(name, currentAttempt + 1, options.maxRetries(),
                        nextRetry, e.getClass().getSimpleName(), e.getMessage());
                    persistState(ctx.processId(), state);

                    // Log retry to audit trail
                    logStepRetry(ctx.processId(), name, startedAt, currentAttempt + 1, nextRetry, e.getMessage());

                    // Update denormalized columns
                    processRepo.updateStateAtomicStep(
                        getDomain(), ctx.processId(), null,
                        null, ProcessStatus.WAITING_FOR_RETRY.name(),
                        null, null, nextRetry, null, null,
                        jdbcTemplate
                    );

                    throw new WaitingForRetryException(name, nextRetry);
                } else {
                    // Retries exhausted - to TSQ
                    ctx.recordStepFailed(name, "RETRIES_EXHAUSTED", e.getMessage());
                    // Log failure to audit trail
                    logStepFailure(ctx.processId(), name, startedAt, "RETRIES_EXHAUSTED", e.getMessage());
                    throw new StepFailedException(name, "RETRIES_EXHAUSTED",
                        "All retry attempts failed: " + e.getMessage(), e);
                }
            }

            case BUSINESS -> {
                // Business rule failure - run compensations
                ctx.recordStepFailed(name, "BUSINESS_RULE", e.getMessage());
                persistState(ctx.processId(), state);
                // Log failure to audit trail
                logStepFailure(ctx.processId(), name, startedAt, "BUSINESS_RULE", e.getMessage());
                runCompensations(ctx.processId(), state);

                processRepo.updateStateAtomicStep(
                    getDomain(), ctx.processId(), null,
                    null, ProcessStatus.COMPENSATED.name(),
                    "BUSINESS_RULE", e.getMessage(), null, null, null,
                    jdbcTemplate
                );

                throw new StepFailedException(name, "BUSINESS_RULE", e.getMessage(), e);
            }

            case TERMINAL -> {
                // Terminal failure - FAILED status, no compensations
                String errorCode = e instanceof TerminalFailureException tfe
                    ? tfe.getErrorCode() : "TERMINAL_FAILURE";
                ctx.recordStepFailed(name, errorCode, e.getMessage());
                persistState(ctx.processId(), state);
                // Log failure to audit trail
                logStepFailure(ctx.processId(), name, startedAt, errorCode, e.getMessage());

                processRepo.updateStateAtomicStep(
                    getDomain(), ctx.processId(), null,
                    null, ProcessStatus.FAILED.name(),
                    errorCode, e.getMessage(), null, null, null,
                    jdbcTemplate
                );

                throw new StepFailedException(name, errorCode, e.getMessage(), e);
            }

            case PERMANENT -> {
                // Permanent failure - to TSQ immediately
                ctx.recordStepFailed(name, "PERMANENT_FAILURE", e.getMessage());
                // Log failure to audit trail
                logStepFailure(ctx.processId(), name, startedAt, "PERMANENT_FAILURE", e.getMessage());
                throw new StepFailedException(name, "PERMANENT_FAILURE", e.getMessage(), e);
            }

            default -> {
                logStepFailure(ctx.processId(), name, startedAt, "UNKNOWN", e.getMessage());
                throw new StepFailedException(name, "UNKNOWN", e.getMessage(), e);
            }
        }
    }

    /**
     * Run compensations in reverse order.
     */
    protected void runCompensations(UUID processId, TState state) {
        ExecutionContext<TState> ctx = currentContext.get();
        if (ctx == null) {
            ctx = new ExecutionContext<>(processId, state);
            currentContext.set(ctx);
        }

        try {
            List<StepRecord> completedSteps = state.getCompletedStepsReversed();
            Map<String, Consumer<TState>> compensations = ctx.getAllCompensations();

            for (StepRecord step : completedSteps) {
                Consumer<TState> compensation = compensations.get(step.name());
                if (compensation != null) {
                    log.debug("Running compensation for step {} in process {}", step.name(), processId);
                    Instant compStartedAt = Instant.now();
                    try {
                        compensation.accept(state);
                        // Log successful compensation to audit trail
                        logCompensation(processId, step.name(), compStartedAt, true);
                    } catch (Exception e) {
                        log.warn("Compensation for step {} failed: {}", step.name(), e.getMessage());
                        // Log failed compensation to audit trail
                        logCompensation(processId, step.name(), compStartedAt, false);
                        // Continue with other compensations
                    }
                }
            }

            log.info("Compensations completed for process {}", processId);
        } finally {
            if (currentContext.get() != null && ctx.processId().equals(processId)) {
                // Only remove if we set it
            }
        }
    }

    /**
     * Calculate exponential backoff delay.
     */
    protected Duration calculateBackoff(int attempt, Duration baseDelay) {
        // Exponential backoff with jitter: base * 2^attempt + random(0-500ms)
        long baseMs = baseDelay.toMillis();
        long delayMs = (long) (baseMs * Math.pow(2, attempt - 1));
        delayMs += (long) (Math.random() * 500);  // Add jitter
        return Duration.ofMillis(Math.min(delayMs, Duration.ofMinutes(5).toMillis()));  // Cap at 5 minutes
    }

    // ========== State Persistence ==========

    /**
     * Save a new process directly to the database.
     */
    protected void saveProcessDirect(UUID processId, TState state, Instant now) {
        saveProcessDirect(processId, state, now, null);
    }

    /**
     * Save a new process directly to the database with optional batch ID.
     */
    protected void saveProcessDirect(UUID processId, TState state, Instant now, UUID batchId) {
        String sql = """
            INSERT INTO commandbus.process (
                domain, process_id, process_type, status, current_step,
                state, error_code, error_message, execution_model,
                created_at, updated_at, completed_at, deadline_at, batch_id
            ) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, 'PROCESS_STEP', ?, ?, ?, ?, ?)
            """;

        jdbcTemplate.update(sql,
            getDomain(),
            processId,
            getProcessType(),
            ProcessStatus.PENDING.name(),
            null,  // No step enum
            serializeState(state),
            null,  // No error code
            null,  // No error message
            java.sql.Timestamp.from(now),
            java.sql.Timestamp.from(now),
            null,  // Not completed
            state.getProcessDeadline() != null ? java.sql.Timestamp.from(state.getProcessDeadline()) : null,
            batchId
        );
    }

    protected void persistState(UUID processId, TState state) {
        String stateJson = serializeState(state);
        processRepo.updateState(getDomain(), processId, stateJson, jdbcTemplate);
    }

    protected TState loadState(UUID processId) {
        String stateJson = processRepo.getStateJson(getDomain(), processId, jdbcTemplate);
        return deserializeState(stateJson);
    }

    // ========== Serialization ==========

    protected String serializeState(TState state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize state", e);
        }
    }

    protected TState deserializeState(String json) {
        try {
            return objectMapper.readValue(json, getStateClass());
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize state", e);
        }
    }

    protected String serializeRequest(TState state, StepOptions<TState, ?> options) {
        // Serialize minimal request info for debugging
        return "{}";  // Placeholder
    }

    protected <R> String serializeResult(R result) {
        if (result == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize result", e);
        }
    }

    @SuppressWarnings("unchecked")
    protected <R> R deserializeResult(String json, StepOptions<TState, R> options) {
        if (json == null) {
            return null;
        }
        try {
            // Try to infer type - this is a limitation of Java generics
            return (R) objectMapper.readValue(json, Object.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize result", e);
        }
    }

    @SuppressWarnings("unchecked")
    protected <R> R deserializeSideEffect(String json) {
        if (json == null) {
            return null;
        }
        try {
            return (R) objectMapper.readValue(json, Object.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize side effect", e);
        }
    }

    // ========== Atomic State + Audit Updates ==========

    /**
     * Atomically update process state and insert audit entry.
     * Uses stored procedure to ensure consistency between process state and audit trail.
     *
     * @param processId Process ID
     * @param state Current state (will be serialized)
     * @param newStatus New process status (null to keep current)
     * @param errorCode Error code (null to keep current)
     * @param errorMessage Error message (null to keep current)
     * @param nextRetryAt Next retry time (for WAITING_FOR_RETRY)
     * @param nextWaitTimeoutAt Wait timeout time (for WAITING_FOR_ASYNC)
     * @param currentWait Current wait name (for WAITING_FOR_ASYNC)
     * @param auditEntry Audit entry to insert (null to skip audit logging)
     */
    protected void updateStateWithAudit(UUID processId, TState state,
                                         String newStatus,
                                         String errorCode, String errorMessage,
                                         Instant nextRetryAt, Instant nextWaitTimeoutAt,
                                         String currentWait,
                                         ProcessAuditEntry auditEntry) {
        String stateJson = serializeState(state);
        processRepo.updateStateWithAudit(
            getDomain(), processId,
            stateJson, null,  // Use full state JSON, not patch
            null, newStatus,
            errorCode, errorMessage,
            nextRetryAt, nextWaitTimeoutAt, currentWait,
            auditEntry,
            jdbcTemplate
        );
    }

    /**
     * Create an audit entry for step execution.
     */
    protected ProcessAuditEntry createAuditEntry(String stepName, String commandType,
                                                   Instant startedAt, ReplyOutcome outcome,
                                                   Map<String, Object> responseData) {
        return new ProcessAuditEntry(
            stepName,
            UUID.randomUUID(),  // Synthetic command ID
            commandType,
            Map.of(),  // No command data for inline steps
            startedAt,
            outcome,
            responseData,
            Instant.now()
        );
    }

    // ========== Audit Logging (non-atomic, for backward compatibility) ==========

    /**
     * Log a step execution to the audit trail.
     *
     * @param processId Process ID
     * @param stepName Step name
     * @param commandType Type of step operation (STEP_EXECUTE, STEP_RETRY, WAIT_SATISFIED, COMPENSATION)
     * @param startedAt When the step started
     * @param outcome The outcome (SUCCESS, FAILED, BUSINESS_RULE_FAILED)
     * @param responseData Response data (may be null)
     */
    protected void logStepAudit(UUID processId, String stepName, String commandType,
                                 Instant startedAt, ReplyOutcome outcome, Map<String, Object> responseData) {
        ProcessAuditEntry entry = createAuditEntry(stepName, commandType, startedAt, outcome, responseData);
        processRepo.logStep(getDomain(), processId, entry, jdbcTemplate);
    }

    /**
     * Log a successful step execution.
     */
    protected void logStepSuccess(UUID processId, String stepName, Instant startedAt, String responseJson) {
        Map<String, Object> responseData = responseJson != null
            ? Map.of("result", responseJson)
            : Map.of();
        logStepAudit(processId, stepName, "STEP_EXECUTE", startedAt, ReplyOutcome.SUCCESS, responseData);
    }

    /**
     * Log a step failure.
     */
    protected void logStepFailure(UUID processId, String stepName, Instant startedAt,
                                   String errorCode, String errorMessage) {
        logStepAudit(processId, stepName, "STEP_EXECUTE", startedAt, ReplyOutcome.FAILED,
            Map.of("errorCode", errorCode, "errorMessage", errorMessage != null ? errorMessage : ""));
    }

    /**
     * Log a step scheduled for retry.
     */
    protected void logStepRetry(UUID processId, String stepName, Instant startedAt,
                                 int attemptCount, Instant nextRetryAt, String errorMessage) {
        logStepAudit(processId, stepName, "STEP_RETRY", startedAt, ReplyOutcome.FAILED,
            Map.of("attemptCount", attemptCount, "nextRetryAt", nextRetryAt.toString(),
                   "errorMessage", errorMessage != null ? errorMessage : "", "willRetry", true));
    }

    /**
     * Log a wait condition satisfied.
     */
    protected void logWaitSatisfied(UUID processId, String waitName, Instant recordedAt) {
        logStepAudit(processId, waitName, "WAIT_SATISFIED", recordedAt, ReplyOutcome.SUCCESS, Map.of());
    }

    /**
     * Log a wait condition timeout (process moved to TSQ).
     */
    protected void logWaitTimeout(UUID processId, String waitName, Instant recordedAt, String errorMessage) {
        logStepAudit(processId, waitName, "WAIT_TIMEOUT", recordedAt, ReplyOutcome.FAILED,
            Map.of("errorCode", "WAIT_TIMEOUT", "errorMessage", errorMessage != null ? errorMessage : ""));
    }

    /**
     * Log a compensation execution.
     */
    protected void logCompensation(UUID processId, String stepName, Instant startedAt, boolean success) {
        logStepAudit(processId, stepName, "COMPENSATION", startedAt,
            success ? ReplyOutcome.SUCCESS : ReplyOutcome.FAILED, Map.of());
    }
}
