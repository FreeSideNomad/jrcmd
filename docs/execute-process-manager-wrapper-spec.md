# Process Step Manager Specification

This document specifies the `ProcessStepManager`, a Temporal.io-inspired alternative to `BaseProcessManager` that enables writing process workflows as sequential code with deterministic replay support.

## Overview

The `ProcessStepManager` provides a programming model where developers write workflow logic as a single sequential `execute()` method. The framework handles:

- **Deterministic replay**: Re-executing the process skips already-completed steps
- **Async response handling**: External events update state and trigger replay
- **Automatic compensation**: Saga-style rollback on business failures
- **Retry with backoff**: Transient failures retry with exponential backoff
- **Rate limiting**: Distributed rate limiting for external API calls
- **TSQ integration**: Failed processes route to troubleshooting queue

### Key Operations

1. **`execute(state)`**: Start process, resume from TSQ, or resume after async response
2. **`processAsyncResponse(processId, stateUpdater)`**: Handle external events (L1-L4 confirmations, manual approvals)

### Inspiration: Temporal.io

This design is inspired by [Temporal.io's deterministic workflow execution](https://docs.temporal.io/workflows):

- Workflows remember state from previous executions
- Replay skips through already-executed steps by returning recorded results
- Side effects are recorded once and replayed
- Wait conditions pause execution until satisfied

## Architecture

### Component Layers

```
┌─────────────────────────────────────────────────────────────────┐
│                    Application Layer                             │
├─────────────────────────────────────────────────────────────────┤
│  PaymentStepProcess            OrderStepProcess                  │
│  extends TestProcessStepManager extends ProcessStepManager       │
│                                                                  │
│  - execute(state)              - execute(state)                  │
│  - onL1Response()              - onShipmentConfirmed()           │
│  - onRiskApproval()            - onPaymentReceived()             │
│  - class PaymentState          - class OrderState                │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Framework Layer                               │
├─────────────────────────────────────────────────────────────────┤
│  ProcessStepManager<TState>                                      │
│  - step(name, action, options)                                   │
│  - wait(name, condition, timeout)                                │
│  - sideEffect(name, operation)                                   │
│  - processAsyncResponse(processId, stateUpdater)                 │
│                                                                  │
│  TestProcessStepManager<TState> (e2e only)                       │
│  - Wraps step() with probabilistic behavior injection            │
│                                                                  │
│  ProcessStepWorker                                               │
│  - pollPendingProcesses()                                        │
│  - pollRetries()                                                 │
│  - checkWaitTimeouts()                                           │
│  - checkDeadlines()                                              │
│                                                                  │
│  ProcessStepState (base class)                                   │
│  - stepHistory, waitHistory, sideEffects                         │
│  - processDeadline, errorCode, errorMessage                      │
│                                                                  │
│  Bucket4jRateLimiter                                             │
│  - acquire(resourceKey, maxWait)                                 │
│  - tryAcquire(resourceKey)                                       │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Infrastructure Layer                          │
├─────────────────────────────────────────────────────────────────┤
│  ProcessRepository             Existing repository               │
│  - Advisory locks              - JSON state persistence          │
│  - Denormalized queries        - Audit logging                   │
│                                                                  │
│  Database: commandbus.process, commandbus.process_audit          │
│            commandbus.rate_limit_config, commandbus.rate_limit_bucket │
└─────────────────────────────────────────────────────────────────┘
```

### Relationship to BaseProcessManager

`ProcessStepManager` is a **complementary alternative** to `BaseProcessManager`:

| Aspect | BaseProcessManager | ProcessStepManager |
|--------|-------------------|----------------------|
| Workflow style | Step enum + state machine | Sequential code |
| Step definition | `getNextStep()` method | `step()` calls in `execute()` |
| Async handling | Reply routing by step | `wait()` + `processAsyncResponse()` |
| Message queue | PGMQ for commands | Direct service calls |
| Replay | Not supported | Full deterministic replay |
| Execution model | `STEP_BASED` | `PROCESS_STEP` |

Both can coexist in the same application for different process types.

## Execution Model

### Process Lifecycle

```
┌──────────┐     start()      ┌───────────┐
│  (new)   │─────────────────▶│  PENDING  │
└──────────┘                  └─────┬─────┘
                                    │ worker picks up
                                    ▼
                              ┌───────────┐
                              │ EXECUTING │
                              └─────┬─────┘
                                    │
                    ┌───────────────┼───────────────┐
                    │               │               │
                    ▼               ▼               ▼
            ┌──────────────┐ ┌──────────────┐ ┌──────────────┐
            │ WAITING_FOR_ │ │ WAITING_FOR_ │ │   COMPLETED  │
            │    ASYNC     │ │    RETRY     │ └──────────────┘
            └──────┬───────┘ └──────┬───────┘
                   │                │
    processAsync   │                │ scheduled retry
    Response()     │                │
                   ▼                ▼
            ┌──────────────────────────┐
            │       EXECUTING          │◀─────┐
            └────────────┬─────────────┘      │
                         │                    │
         ┌───────────────┼───────────────┐    │
         │               │               │    │
         ▼               ▼               ▼    │
  ┌──────────────┐ ┌──────────────┐ ┌────────┴───┐
  │ WAITING_FOR_ │ │  COMPLETED   │ │  Business  │
  │     TSQ      │ └──────────────┘ │  Failure   │
  └──────┬───────┘                  └──────┬─────┘
         │                                 │
         │ operator retry                  │ auto-compensate
         │                                 ▼
         │                          ┌──────────────┐
         └─────────────────────────▶│ COMPENSATING │
                                    └──────┬───────┘
                                           │
                                           ▼
                                    ┌──────────────┐
                                    │  COMPENSATED │
                                    └──────────────┘
```

### Deterministic Replay

When `execute()` runs:

1. **First execution**: Steps execute normally, results are recorded
2. **Replay (after async response)**: Completed steps return cached results, execution continues from where it left off
3. **Wait conditions**: Re-evaluated on each replay; if satisfied, continue; if not, pause

```java
// First execution:
execute(state) {
    step("bookRisk", ...);      // Executes, records result
    step("submitPayment", ...); // Executes, records result
    wait("awaitL1", ...);       // Condition false → pauses
}

// After L1 response arrives:
execute(state) {
    step("bookRisk", ...);      // Returns cached result (no execution)
    step("submitPayment", ...); // Returns cached result (no execution)
    wait("awaitL1", ...);       // Condition true → continues
    wait("awaitL4", ...);       // Condition false → pauses
}
```

### Immediate vs Deferred Execution

The `start()` method supports two execution modes:

**Immediate Execution (default):**
```java
// API call - execute immediately in caller thread
UUID processId = paymentProcess.start(initialState);  // Runs until completion/wait/retry
// OR explicitly:
UUID processId = paymentProcess.start(initialState, StartOptions.defaults());
```

When immediate execution encounters a `wait()` or needs a retry:
1. State is persisted with appropriate status (`WAITING_FOR_ASYNC` or `WAITING_FOR_RETRY`)
2. Control returns to caller (exception thrown and caught internally)
3. Worker picks up the process when condition is met or retry is due

**Deferred Execution:**
```java
// Background job - create PENDING, let worker execute
UUID processId = paymentProcess.start(initialState, StartOptions.deferred());
// Worker will pick up and execute the process
```

This design allows:
- **API handlers**: Start and partially execute payment immediately, return response to user
- **Batch jobs**: Create many processes for worker to process in parallel
- **Unified resume**: Same worker handles retries and async responses regardless of initial execution mode

## State Model

### ProcessStepState Base Class

All process step states extend this base class:

```java
/**
 * Base class for all process step manager states.
 * Contains framework-managed execution tracking fields.
 */
public abstract class ProcessStepState implements ProcessState {

    // Process execution tracking (framework-managed)
    private List<StepRecord> stepHistory = new ArrayList<>();
    private List<WaitRecord> waitHistory = new ArrayList<>();
    private List<SideEffectRecord> sideEffects = new ArrayList<>();

    // Error tracking
    private String errorCode;
    private String errorMessage;

    // Process deadline (set in initial state)
    private Instant processDeadline;

    // Getters and setters...
}
```

### TestProcessStepState (e2e only)

For e2e testing, states extend this class to include probabilistic behavior:

```java
/**
 * Base class for test states with probabilistic behavior configuration.
 * Used in e2e tests to simulate failures, timeouts, etc.
 */
public abstract class TestProcessStepState extends ProcessStepState {

    // Probabilistic behavior for each step (keyed by step name)
    private Map<String, ProbabilisticBehavior> stepBehaviors = new HashMap<>();

    public ProbabilisticBehavior getBehaviorForStep(String stepName) {
        return stepBehaviors.getOrDefault(stepName, ProbabilisticBehavior.defaults());
    }

    public void setStepBehavior(String stepName, ProbabilisticBehavior behavior) {
        stepBehaviors.put(stepName, behavior);
    }

    // Getters and setters...
}
```

### Step Record

```java
public record StepRecord(
    String name,
    StepStatus status,       // STARTED, COMPLETED, FAILED, WAITING_RETRY
    int attemptCount,
    int maxRetries,          // For TSQ UI: "Attempt 2 of 3"
    Instant startedAt,
    Instant completedAt,
    String requestJson,      // Serialized input (for debugging)
    String responseJson,     // Serialized result (for replay)
    String errorCode,
    String errorMessage,
    Instant nextRetryAt      // For WAITING_RETRY status
) {}

public enum StepStatus {
    STARTED,
    COMPLETED,
    FAILED,
    WAITING_RETRY
}
```

### Wait Record

```java
public record WaitRecord(
    String name,
    boolean satisfied,
    Instant recordedAt,
    Duration timeout,        // Configured timeout for this wait
    Instant timeoutAt        // When this wait expires (for scheduler)
) {}
```

### Side Effect Record

```java
public record SideEffectRecord(
    String name,
    String valueJson,        // Serialized result
    Instant recordedAt
) {}
```

### JSON Serialization

State is serialized directly to JSON (not via Map conversion) to support complex types:

```java
private final ObjectMapper objectMapper = new ObjectMapper()
    .registerModule(new JavaTimeModule())
    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

protected void persistState(UUID processId, TState state) {
    String stateJson = objectMapper.writeValueAsString(state);
    processRepo.updateState(getDomain(), processId, stateJson, jdbcTemplate);
}

protected TState loadState(UUID processId) {
    String stateJson = processRepo.getStateJson(getDomain(), processId, jdbcTemplate);
    return objectMapper.readValue(stateJson, getStateClass());
}
```

## Framework API

### ProcessStepManager Base Class

```java
public abstract class ProcessStepManager<TState extends ProcessStepState> {

    protected final ProcessRepository processRepo;
    protected final JdbcTemplate jdbcTemplate;
    protected final TransactionTemplate transactionTemplate;
    protected final Bucket4jRateLimiter rateLimiter;

    // ========== Abstract Methods ==========

    public abstract String getProcessType();
    public abstract String getDomain();
    public abstract Class<TState> getStateClass();
    protected abstract void execute(TState state);

    // ========== Optional Overrides ==========

    /**
     * Classify exceptions to determine handling behavior.
     */
    protected ExceptionType classifyException(Exception e) {
        return ExceptionType.PERMANENT;  // Default: fail fast
    }

    /**
     * Default timeout for wait() calls without explicit timeout.
     * Can be overridden per process type or via configuration.
     */
    protected Duration getDefaultWaitTimeout() {
        // Default from configuration: commandbus.process.<type>.default-wait-timeout
        return Duration.ofHours(1);
    }

    /**
     * Action when process deadline is exceeded.
     */
    protected DeadlineAction getDeadlineAction() {
        return DeadlineAction.TSQ;  // Default: move to TSQ
    }

    // ========== Public API ==========

    public UUID start(TState initialState);
    public UUID start(TState initialState, StartOptions options);
    public List<UUID> startBatch(List<TState> initialStates);
    public List<UUID> startBatch(List<TState> initialStates, BatchOptions options);
    public void processAsyncResponse(UUID processId, Consumer<TState> stateUpdater);
    public void resume(UUID processId);

    // ========== TSQ Operations ==========

    public void retry(UUID processId);
    public void cancelOverride(UUID processId, boolean runCompensations);
    public void completeOverride(UUID processId, Map<String, Object> stateOverrides);

    // ========== Step Methods ==========

    protected <R> R step(String name, Function<TState, R> action);
    protected <R> R step(String name, Function<TState, R> action, int maxRetries);
    protected <R> R step(String name, Function<TState, R> action, int maxRetries,
                         Consumer<TState> compensation);
    protected <R> R step(String name, StepOptions<TState, R> options);

    // ========== Wait Methods ==========

    protected void wait(String name, Supplier<Boolean> condition);
    protected void wait(String name, Supplier<Boolean> condition, Duration timeout);

    // ========== Side Effect Methods ==========

    protected <R> R sideEffect(String name, Supplier<R> operation);
}
```

### TestProcessStepManager (e2e only)

```java
/**
 * Process step manager with probabilistic behavior injection for e2e testing.
 * Wraps step execution to simulate failures, timeouts, and delays.
 */
public abstract class TestProcessStepManager<TState extends TestProcessStepState>
        extends ProcessStepManager<TState> {

    @Override
    protected <R> R step(String name, StepOptions<TState, R> options) {
        ExecutionContext<TState> ctx = currentContext.get();
        TState state = ctx.state();

        // Check if step already completed (replay) - skip behavior simulation
        StepRecord existing = ctx.getCompletedStep(name);
        if (existing != null && existing.status() == StepStatus.COMPLETED) {
            return super.step(name, options);  // Return cached result
        }

        // Apply probabilistic behavior BEFORE executing the step
        ProbabilisticBehavior behavior = state.getBehaviorForStep(name);
        if (behavior != null) {
            applyProbabilisticBehavior(name, behavior);
        }

        // Execute the actual step
        return super.step(name, options);
    }

    /**
     * Apply probabilistic behavior - may throw exceptions or add delays.
     */
    private void applyProbabilisticBehavior(String stepName, ProbabilisticBehavior behavior) {
        Random random = new Random();
        double roll = random.nextDouble() * 100;
        double cumulative = 0;

        // Check for permanent failure
        cumulative += behavior.failPermanentPct();
        if (roll < cumulative) {
            throw new PermanentException("Simulated permanent failure at step: " + stepName);
        }

        // Check for transient failure
        cumulative += behavior.failTransientPct();
        if (roll < cumulative) {
            throw new TransientException("Simulated transient failure at step: " + stepName);
        }

        // Check for business rule failure
        cumulative += behavior.failBusinessRulePct();
        if (roll < cumulative) {
            throw new BusinessRuleException("Simulated business rule failure at step: " + stepName);
        }

        // Check for timeout
        cumulative += behavior.timeoutPct();
        if (roll < cumulative) {
            throw new TimeoutException("Simulated timeout at step: " + stepName);
        }

        // Apply random delay
        int delay = randomDelay(behavior.minDurationMs(), behavior.maxDurationMs());
        if (delay > 0) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private int randomDelay(int min, int max) {
        if (min == 0 && max == 0) return 0;
        return min + new Random().nextInt(Math.max(1, max - min));
    }
}
```

### Exception Classification

```java
public enum ExceptionType {
    TRANSIENT,   // Retry with backoff
    BUSINESS,    // Auto-compensate (saga rollback)
    PERMANENT    // Move to TSQ immediately
}

// Example classifier:
@Override
protected ExceptionType classifyException(Exception e) {
    if (e instanceof TimeoutException) return ExceptionType.TRANSIENT;
    if (e instanceof ServiceUnavailableException) return ExceptionType.TRANSIENT;
    if (e instanceof BusinessRuleException) return ExceptionType.BUSINESS;
    if (e instanceof ValidationException) return ExceptionType.PERMANENT;
    return ExceptionType.PERMANENT;
}
```

### Deadline Actions

```java
public enum DeadlineAction {
    TSQ,         // Move to troubleshooting queue
    COMPENSATE,  // Auto-run compensations
    FAIL         // Mark as FAILED without compensation
}
```

### StepOptions Builder

```java
public class StepOptions<TState, R> {
    private final Function<TState, R> action;
    private final int maxRetries;
    private final Duration retryDelay;
    private final Duration timeout;
    private final Consumer<TState> compensation;
    private final String rateLimitKey;
    private final Duration rateLimitTimeout;

    public static <TState, R> Builder<TState, R> builder() {
        return new Builder<>();
    }

    public static class Builder<TState, R> {
        private Function<TState, R> action;
        private int maxRetries = 1;                    // Default: no retry
        private Duration retryDelay = Duration.ofSeconds(1);
        private Duration timeout = Duration.ofSeconds(30);
        private Consumer<TState> compensation;
        private String rateLimitKey;
        private Duration rateLimitTimeout = Duration.ofSeconds(5);

        public Builder<TState, R> action(Function<TState, R> action);
        public Builder<TState, R> maxRetries(int maxRetries);
        public Builder<TState, R> retryDelay(Duration retryDelay);
        public Builder<TState, R> timeout(Duration timeout);
        public Builder<TState, R> compensation(Consumer<TState> compensation);
        public Builder<TState, R> rateLimitKey(String rateLimitKey);
        public Builder<TState, R> rateLimitTimeout(Duration rateLimitTimeout);
        public StepOptions<TState, R> build();
    }
}
```

### StartOptions

```java
/**
 * Options for starting a single process.
 */
public class StartOptions {
    private final boolean executeImmediately;  // If true, execute in caller thread

    public static StartOptions defaults() {
        return new StartOptions(true);  // Default: immediate execution
    }

    public static StartOptions deferred() {
        return new StartOptions(false);  // Worker picks up PENDING process
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean isExecuteImmediately() {
        return executeImmediately;
    }

    public static class Builder {
        private boolean executeImmediately = true;  // Default: immediate execution

        public Builder executeImmediately(boolean executeImmediately) {
            this.executeImmediately = executeImmediately;
            return this;
        }

        public Builder deferred() {
            this.executeImmediately = false;
            return this;
        }

        public StartOptions build() {
            return new StartOptions(executeImmediately);
        }
    }
}
```

**Immediate vs Deferred Execution:**

| Mode | When to Use | Behavior |
|------|-------------|----------|
| `executeImmediately=true` (default) | API calls, single payments | Process executes in caller thread until completion, wait, or retry |
| `executeImmediately=false` | Background jobs, scheduling | Process created as PENDING; worker picks up later |

When immediate execution encounters a `wait()` or needs a retry:
1. State is persisted with appropriate status (`WAITING_FOR_ASYNC` or `WAITING_FOR_RETRY`)
2. Control returns to caller
3. Worker picks up the process when condition is met or retry is due

### BatchOptions

```java
public class BatchOptions {
    private final boolean executeImmediately;  // If false, worker picks up PENDING processes

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private boolean executeImmediately = false;  // Default: worker picks up (different from StartOptions)

        public Builder executeImmediately(boolean executeImmediately) {
            this.executeImmediately = executeImmediately;
            return this;
        }

        public BatchOptions build() {
            return new BatchOptions(executeImmediately);
        }
    }
}
```

## Control Flow

### Exception-Based Wait Implementation

The `wait()` method uses exceptions for control flow (inspired by coroutine suspension):

```java
protected void wait(String name, Supplier<Boolean> condition, Duration timeout) {
    ExecutionContext<TState> ctx = currentContext.get();

    // Check if wait already passed (replay)
    WaitRecord existing = ctx.getCompletedWait(name);
    if (existing != null && existing.satisfied()) {
        return;  // Already passed this wait
    }

    // Evaluate condition
    if (condition.get()) {
        // Condition met - record and continue
        ctx.recordWaitSatisfied(name);
        persistState(ctx.processId(), ctx.state());
        return;
    }

    // Condition not met - pause execution
    Duration effectiveTimeout = timeout != null ? timeout : getDefaultWaitTimeout();
    Instant timeoutAt = Instant.now().plus(effectiveTimeout);
    ctx.recordWaitPending(name, effectiveTimeout, timeoutAt);
    persistState(ctx.processId(), ctx.state());
    updateDenormalizedTimeout(ctx.processId(), timeoutAt);
    throw new WaitConditionNotMetException(name);
}
```

### Retry with Backoff

Retries are **not** executed in the same thread. Instead:

1. Step fails with transient error
2. Retry scheduled for later (persisted to DB)
3. Process pauses with status `WAITING_FOR_RETRY`
4. Scheduled worker picks up due retries
5. Process resumes (full replay)

```java
private <R> R handleStepException(String name, StepOptions<TState, R> options,
                                   ExecutionContext<TState> ctx, Exception e) {
    ExceptionType type = classifyException(e);
    int currentAttempt = ctx.getAttemptCount(name);
    int maxRetries = options.maxRetries();

    switch (type) {
        case TRANSIENT -> {
            if (currentAttempt < maxRetries) {
                // Schedule retry with exponential backoff
                Duration delay = calculateBackoff(currentAttempt, options.retryDelay());
                Instant nextRetry = Instant.now().plus(delay);
                ctx.recordStepRetry(name, currentAttempt + 1, maxRetries, nextRetry, e);
                persistState(ctx.processId(), ctx.state());
                updateDenormalizedRetry(ctx.processId(), nextRetry);
                throw new WaitingForRetryException(name, nextRetry);
            } else {
                // Retries exhausted - to TSQ
                ctx.recordStepFailed(name, e);
                throw new StepFailedException(name, "RETRIES_EXHAUSTED", e.getMessage());
            }
        }
        case BUSINESS -> {
            // Business rule failure - trigger compensation
            ctx.recordStepFailed(name, e);
            throw new BusinessRuleException(e.getMessage());
        }
        case PERMANENT -> {
            // Permanent failure - to TSQ immediately
            ctx.recordStepFailed(name, e);
            throw new StepFailedException(name, "PERMANENT_FAILURE", e.getMessage());
        }
    }
}

private Duration calculateBackoff(int attempt, Duration baseDelay) {
    // Exponential backoff: delay * 2^attempt, capped at 5 minutes
    long millis = baseDelay.toMillis() * (1L << attempt);
    return Duration.ofMillis(Math.min(millis, Duration.ofMinutes(5).toMillis()));
}
```

### Compensation (Saga Pattern)

When a business rule exception occurs, compensations run in reverse order:

```java
private void runCompensations(ExecutionContext<TState> ctx) {
    updateProcessStatus(ctx.processId(), ProcessStatus.COMPENSATING);

    // Get completed steps with compensations in reverse order
    List<StepRecord> completed = ctx.getCompletedStepsReversed();

    for (StepRecord step : completed) {
        Consumer<TState> compensation = ctx.getCompensation(step.name());
        if (compensation != null) {
            try {
                compensation.accept(ctx.state());
                logAudit(ctx.processId(), "COMPENSATION_SUCCESS", step.name());
            } catch (Exception e) {
                // Compensation failed - log but continue with remaining compensations
                logAudit(ctx.processId(), "COMPENSATION_FAILED", step.name(), e);
            }
        }
    }

    persistState(ctx.processId(), ctx.state());
    updateProcessStatus(ctx.processId(), ProcessStatus.COMPENSATED);
}
```

## Rate Limiting

### Bucket4j Integration

Rate limiting uses [Bucket4j](https://github.com/bucket4j/bucket4j) with PostgreSQL for distributed coordination:

```java
@Component
public class Bucket4jRateLimiter {

    private final PostgreSQLAdvisoryLockBasedProxyManager<String> proxyManager;
    private final RateLimitConfigRepository configRepository;
    private final ConcurrentHashMap<String, BucketConfiguration> configCache = new ConcurrentHashMap<>();

    public Bucket4jRateLimiter(DataSource dataSource, RateLimitConfigRepository configRepository) {
        this.configRepository = configRepository;

        this.proxyManager = Bucket4jPostgreSQL.advisoryLockBasedBuilder(dataSource)
            .table("commandbus.rate_limit_bucket")
            .idColumn("id")
            .stateColumn("state")
            .expiresAtColumn("expires_at")
            .primaryKeyMapper(PrimaryKeyMapper.STRING)
            .build();
    }

    /**
     * Acquire a rate limit ticket, waiting up to maxWait if necessary.
     *
     * @param resourceKey The resource to rate limit (e.g., "fx_booking_api")
     * @param maxWait Maximum time to wait for a ticket
     * @return true if ticket acquired, false if timed out
     */
    public boolean acquire(String resourceKey, Duration maxWait) {
        BucketConfiguration config = getOrCreateConfig(resourceKey);

        BucketProxy bucket = proxyManager.builder()
            .build(resourceKey, () -> config);

        try {
            return bucket.asBlocking().tryConsume(1, maxWait, BlockingStrategy.PARKING);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Try to acquire immediately (non-blocking).
     */
    public boolean tryAcquire(String resourceKey) {
        BucketConfiguration config = getOrCreateConfig(resourceKey);

        BucketProxy bucket = proxyManager.builder()
            .build(resourceKey, () -> config);

        return bucket.tryConsume(1);
    }

    private BucketConfiguration getOrCreateConfig(String resourceKey) {
        return configCache.computeIfAbsent(resourceKey, key -> {
            RateLimitConfig dbConfig = configRepository.findByResourceKey(key)
                .orElseThrow(() -> new IllegalArgumentException("Rate limit not configured: " + key));

            // Greedy refill distributes tokens evenly across the period
            // e.g., 5 TPS with greedy = 1 token every 200ms
            return BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                    .capacity(dbConfig.ticketsPerSecond())
                    .refillGreedy(dbConfig.ticketsPerSecond(), Duration.ofSeconds(1))
                    .build())
                .build();
        });
    }

    public void invalidateConfig(String resourceKey) {
        configCache.remove(resourceKey);
    }
}
```

### Rate Limiting in Steps

```java
protected <R> R step(String name, StepOptions<TState, R> options) {
    // ... replay check ...

    // Rate limiting (if configured)
    if (options.rateLimitKey() != null) {
        Duration timeout = options.rateLimitTimeout();

        if (!rateLimiter.acquire(options.rateLimitKey(), timeout)) {
            throw new RateLimitExceededException(
                "Rate limit exceeded for " + options.rateLimitKey());
        }
    }

    // Execute the step...
}
```

## Concurrency Control

### Advisory Locks

Database advisory locks ensure only one execution per process at a time:

### Start Method Implementation

```java
public UUID start(TState initialState) {
    return start(initialState, StartOptions.defaults());
}

public UUID start(TState initialState, StartOptions options) {
    // Create process record with initial state
    UUID processId = processRepo.create(
        getDomain(),
        getProcessType(),
        "PROCESS_STEP",
        ProcessStatus.PENDING,
        initialState
    );

    // Audit the creation
    logAudit(processId, "PROCESS_CREATED", null);

    if (options.isExecuteImmediately()) {
        // Execute immediately in caller thread
        try {
            runExecute(processId);
        } catch (WaitConditionNotMetException e) {
            // Expected - process is waiting for async response
            // Status already set to WAITING_FOR_ASYNC
        } catch (RetryScheduledException e) {
            // Expected - step needs retry
            // Status already set to WAITING_FOR_RETRY
        }
        // Other exceptions propagate to caller
    }
    // If not executeImmediately, process stays PENDING for worker to pick up

    return processId;
}
```

### RunExecute Implementation

```java
private void runExecute(UUID processId) {
    // Acquire advisory lock - blocks until available (queuing behavior)
    try (var lock = acquireAdvisoryLock(processId)) {
        runExecuteWithLock(processId);
    }
}

private AdvisoryLock acquireAdvisoryLock(UUID processId) {
    // Use PostgreSQL advisory lock with process_id hash
    long lockId = processId.getLeastSignificantBits();
    jdbcTemplate.execute("SELECT pg_advisory_lock(" + lockId + ")");
    return () -> jdbcTemplate.execute("SELECT pg_advisory_unlock(" + lockId + ")");
}
```

### Concurrent Async Responses

When multiple async responses arrive simultaneously (e.g., L1, L2, L3, L4):

```
T0: L1 arrives → acquires lock → starts replay
T1: L2 arrives → waits for lock (queued)
T2: L3 arrives → waits for lock (queued)
T3: L4 arrives → waits for lock (queued)
T4: L1 replay finishes → releases lock
T5: L2 acquires lock → starts replay (now with L1 + L2 in state)
...
```

Sequential processing is used for simplicity. Each replay incorporates all state updates from previous responses.

### Late-Arriving Responses for Completed Processes

If a response arrives after the process is completed, state is updated but no replay occurs:

```java
public void processAsyncResponse(UUID processId, Consumer<TState> stateUpdater) {
    try (var lock = acquireAdvisoryLock(processId)) {
        ProcessView process = processRepo.findById(getDomain(), processId);

        // Check if process is already completed
        if (process.status() == ProcessStatus.COMPLETED) {
            // Just update state, don't replay
            TState state = loadState(processId);
            stateUpdater.accept(state);
            persistState(processId, state);
            logAudit(processId, "LATE_ASYNC_RESPONSE", "Process already completed");
            return;
        }

        // Normal flow: update state and replay
        TState state = loadState(processId);
        stateUpdater.accept(state);
        persistState(processId, state);

        runExecuteWithLock(processId);
    }
}
```

## Worker Implementation

### ProcessStepWorker

A dedicated worker handles scheduled tasks using virtual threads:

```java
@Component
public class ProcessStepWorker {

    private final List<ProcessStepManager<?>> processManagers;
    private final ProcessRepository processRepo;
    private final ExecutorService virtualThreadExecutor =
        Executors.newVirtualThreadPerTaskExecutor();

    /**
     * Poll for PENDING processes and start execution.
     */
    @Scheduled(fixedRateString = "${commandbus.process.pending-poll-interval:1000}")
    void pollPendingProcesses() {
        for (var pm : processManagers) {
            if (!"PROCESS_STEP".equals(pm.getExecutionModel())) continue;

            List<UUID> pendingProcesses = processRepo.findByExecutionModelAndStatus(
                pm.getDomain(), "PROCESS_STEP", ProcessStatus.PENDING);

            for (UUID processId : pendingProcesses) {
                virtualThreadExecutor.submit(() -> {
                    try {
                        pm.resume(processId);
                    } catch (Exception e) {
                        log.error("Failed to start process {}: {}", processId, e.getMessage());
                    }
                });
            }
        }
    }

    /**
     * Poll for processes due for retry.
     */
    @Scheduled(fixedRateString = "${commandbus.process.retry-poll-interval:5000}")
    void pollRetries() {
        for (var pm : processManagers) {
            if (!"PROCESS_STEP".equals(pm.getExecutionModel())) continue;

            List<UUID> dueProcesses = processRepo.findDueForRetry(
                pm.getDomain(), Instant.now());

            for (UUID processId : dueProcesses) {
                virtualThreadExecutor.submit(() -> {
                    try {
                        pm.resume(processId);
                    } catch (Exception e) {
                        log.error("Failed to resume process {}: {}", processId, e.getMessage());
                    }
                });
            }
        }
    }

    /**
     * Check for expired wait timeouts.
     */
    @Scheduled(fixedRateString = "${commandbus.process.timeout-check-interval:60000}")
    void checkWaitTimeouts() {
        for (var pm : processManagers) {
            if (!"PROCESS_STEP".equals(pm.getExecutionModel())) continue;

            List<UUID> expiredProcesses = processRepo.findExpiredWaits(
                pm.getDomain(), Instant.now());

            for (UUID processId : expiredProcesses) {
                virtualThreadExecutor.submit(() -> {
                    try {
                        pm.handleWaitTimeout(processId);
                    } catch (Exception e) {
                        log.error("Failed to handle timeout for {}: {}", processId, e.getMessage());
                    }
                });
            }
        }
    }

    /**
     * Check for exceeded process deadlines.
     */
    @Scheduled(fixedRateString = "${commandbus.process.deadline-check-interval:60000}")
    void checkDeadlines() {
        for (var pm : processManagers) {
            if (!"PROCESS_STEP".equals(pm.getExecutionModel())) continue;

            List<UUID> expiredProcesses = processRepo.findExpiredDeadlines(
                pm.getDomain(), Instant.now());

            for (UUID processId : expiredProcesses) {
                virtualThreadExecutor.submit(() -> {
                    try {
                        pm.handleDeadlineExceeded(processId);
                    } catch (Exception e) {
                        log.error("Failed to handle deadline for {}: {}", processId, e.getMessage());
                    }
                });
            }
        }
    }
}
```

## Network Simulator (e2e only)

### StepPaymentNetworkSimulator

A background job that simulates async responses for step-based payment processes:

```java
@Component
@Profile("e2e")
public class StepPaymentNetworkSimulator {

    private final ProcessRepository processRepo;
    private final PaymentStepProcess paymentStepProcess;
    private final PendingApprovalRepository pendingApprovalRepository;
    private final PendingNetworkResponseRepository pendingNetworkResponseRepository;
    private final Random random = new Random();

    /**
     * Poll all waiting processes each cycle and simulate responses.
     */
    @Scheduled(fixedRateString = "${commandbus.simulator.poll-interval:1000}")
    void simulateAsyncResponses() {
        // Find step-based processes waiting for async responses
        List<ProcessView> waiting = processRepo.findByExecutionModelAndStatus(
            "payments", "PROCESS_STEP", ProcessStatus.WAITING_FOR_ASYNC);

        for (ProcessView process : waiting) {
            try {
                PaymentStepProcess.PaymentState state = loadState(process.processId());
                simulateResponsesForProcess(process.processId(), state);
            } catch (Exception e) {
                log.error("Failed to simulate responses for {}: {}", process.processId(), e.getMessage());
            }
        }
    }

    private void simulateResponsesForProcess(UUID processId, PaymentStepProcess.PaymentState state) {
        // Determine current wait based on state
        String currentWait = determineCurrentWait(state);
        if (currentWait == null) return;

        switch (currentWait) {
            case "awaitRiskApproval" -> simulateRiskApproval(processId, state);
            case "awaitL1" -> simulateL1(processId, state);
            case "awaitL2" -> simulateL2(processId, state);
            case "awaitL3" -> simulateL3(processId, state);
            case "awaitL4" -> simulateL4(processId, state);
        }
    }

    private String determineCurrentWait(PaymentStepProcess.PaymentState state) {
        if (state.isPendingApproval() && !state.isRiskApproved()) return "awaitRiskApproval";
        if (!state.isL1Received()) return "awaitL1";
        if (!state.isL2Received()) return "awaitL2";
        if (!state.isL3Received()) return "awaitL3";
        if (!state.isL4Received()) return "awaitL4";
        return null;
    }

    private void simulateL1(UUID processId, PaymentStepProcess.PaymentState state) {
        ProbabilisticBehavior behavior = state.getBehaviorForStep("awaitL1");
        SimulationResult result = applyBehavior(behavior);

        switch (result.outcome()) {
            case SUCCESS -> paymentStepProcess.onL1Response(processId, new L1Response(
                "L1-REF-" + UUID.randomUUID().toString().substring(0, 8)));
            case FAILURE -> paymentStepProcess.onL1Error(processId, new L1ErrorResponse(
                result.errorCode(), result.errorMessage()));
            case PENDING -> {
                // Add to pending queue for manual intervention
                PendingNetworkResponse pending = PendingNetworkResponse.create(
                    null, processId, processId, null, 1, "PROCESS_STEP");
                pendingNetworkResponseRepository.save(pending);
            }
            case TIMEOUT -> { /* Do nothing - will be picked up by timeout checker */ }
        }
    }

    // Similar methods for L2, L3, L4, RiskApproval...

    private SimulationResult applyBehavior(ProbabilisticBehavior behavior) {
        double roll = random.nextDouble() * 100;
        double cumulative = 0;

        cumulative += behavior.failPermanentPct();
        if (roll < cumulative) {
            return SimulationResult.failure("PERM_ERROR", "Simulated permanent failure");
        }

        cumulative += behavior.failTransientPct();
        if (roll < cumulative) {
            return SimulationResult.failure("TRANS_ERROR", "Simulated transient failure");
        }

        cumulative += behavior.failBusinessRulePct();
        if (roll < cumulative) {
            return SimulationResult.failure("BIZ_ERROR", "Simulated business rule failure");
        }

        cumulative += behavior.timeoutPct();
        if (roll < cumulative) {
            return SimulationResult.timeout();
        }

        cumulative += behavior.pendingPct();
        if (roll < cumulative) {
            return SimulationResult.pending();
        }

        return SimulationResult.success();
    }
}
```

## Database Schema

### Process Table Extensions

```sql
-- Add columns for ProcessStepManager support
ALTER TABLE commandbus.process
ADD COLUMN IF NOT EXISTS execution_model VARCHAR(20) DEFAULT 'STEP_BASED';
-- Values: 'STEP_BASED' (BaseProcessManager), 'PROCESS_STEP' (ProcessStepManager)

ALTER TABLE commandbus.process
ADD COLUMN IF NOT EXISTS next_retry_at TIMESTAMPTZ;

ALTER TABLE commandbus.process
ADD COLUMN IF NOT EXISTS next_wait_timeout_at TIMESTAMPTZ;

ALTER TABLE commandbus.process
ADD COLUMN IF NOT EXISTS deadline_at TIMESTAMPTZ;

ALTER TABLE commandbus.process
ADD COLUMN IF NOT EXISTS current_wait VARCHAR(100);
-- Name of wait we're blocked on (for TSQ UI display)

-- Indexes for scheduler queries
CREATE INDEX IF NOT EXISTS idx_process_pending_step
ON commandbus.process(domain, execution_model)
WHERE status = 'PENDING' AND execution_model = 'PROCESS_STEP';

CREATE INDEX IF NOT EXISTS idx_process_retry
ON commandbus.process(domain, next_retry_at)
WHERE status = 'WAITING_FOR_RETRY' AND next_retry_at IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_process_wait_timeout
ON commandbus.process(domain, next_wait_timeout_at)
WHERE status = 'WAITING_FOR_ASYNC' AND next_wait_timeout_at IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_process_deadline
ON commandbus.process(domain, deadline_at)
WHERE status NOT IN ('COMPLETED', 'FAILED', 'COMPENSATED', 'CANCELLED')
AND deadline_at IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_process_execution_model
ON commandbus.process(domain, execution_model);
```

### Process Audit Table Extensions

```sql
-- Add columns for ProcessStepManager audit entries
ALTER TABLE commandbus.process_audit
ADD COLUMN IF NOT EXISTS entry_type VARCHAR(30) DEFAULT 'COMMAND';
-- Values: COMMAND (existing), STEP, WAIT, SIDE_EFFECT, ASYNC_RESPONSE, COMPENSATION

ALTER TABLE commandbus.process_audit
ADD COLUMN IF NOT EXISTS step_status VARCHAR(20);
-- Values: STARTED, COMPLETED, FAILED, WAITING_RETRY

ALTER TABLE commandbus.process_audit
ADD COLUMN IF NOT EXISTS attempt_number INTEGER;

-- Make command_id nullable (not all entry types have commands)
ALTER TABLE commandbus.process_audit
ALTER COLUMN command_id DROP NOT NULL;

ALTER TABLE commandbus.process_audit
ALTER COLUMN command_type DROP NOT NULL;

ALTER TABLE commandbus.process_audit
ALTER COLUMN sent_at DROP NOT NULL;
```

### Pending Tables Extensions

```sql
-- Add execution_model to pending tables for UI routing
ALTER TABLE commandbus.pending_approval
ADD COLUMN IF NOT EXISTS execution_model VARCHAR(20) DEFAULT 'STEP_BASED';

ALTER TABLE commandbus.pending_network_response
ADD COLUMN IF NOT EXISTS execution_model VARCHAR(20) DEFAULT 'STEP_BASED';
```

### Rate Limiting Tables

```sql
-- Rate limit configuration
CREATE TABLE IF NOT EXISTS commandbus.rate_limit_config (
    resource_key VARCHAR(100) PRIMARY KEY,
    tickets_per_second INTEGER NOT NULL,
    description VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Bucket4j state storage
CREATE TABLE IF NOT EXISTS commandbus.rate_limit_bucket (
    id VARCHAR(255) PRIMARY KEY,
    state BYTEA NOT NULL,
    expires_at BIGINT
);

CREATE INDEX IF NOT EXISTS idx_rate_limit_bucket_expires
ON commandbus.rate_limit_bucket(expires_at)
WHERE expires_at IS NOT NULL;
```

### Process Statuses

```java
public enum ProcessStatus {
    PENDING,            // Created, not yet started (worker will pick up)
    EXECUTING,          // Currently running execute() - transient
    WAITING_FOR_ASYNC,  // Paused at wait(), waiting for async response
    WAITING_FOR_RETRY,  // Paused, scheduled for retry
    WAITING_FOR_TSQ,    // Failed, needs operator intervention
    COMPENSATING,       // Running compensations
    COMPENSATED,        // Compensations completed
    COMPLETED,          // Successfully finished
    CANCELLED,          // Cancelled by operator or deadline
    FAILED              // Permanently failed
}
```

## TSQ Integration

### Supported Operations

| Operation | Description | Behavior |
|-----------|-------------|----------|
| **Retry** | Resume execution | Full replay from start |
| **Cancel** | Cancel process | Optional: run compensations |
| **Complete** | Force complete | Optional: provide missing state values |

### Cancel Override

```java
/**
 * Cancel a process with optional compensation.
 *
 * @param processId Process to cancel
 * @param runCompensations If true, run compensations before marking cancelled
 */
public void cancelOverride(UUID processId, boolean runCompensations) {
    try (var lock = acquireAdvisoryLock(processId)) {
        if (runCompensations) {
            TState state = loadState(processId);
            ExecutionContext<TState> ctx = createContext(processId, state);
            runCompensations(ctx);
        }
        updateProcessStatus(processId, ProcessStatus.CANCELLED);
        logAudit(processId, "TSQ_CANCEL", runCompensations ? "with_compensation" : "without_compensation");
    }
}
```

### Complete Override

```java
/**
 * Force-complete a process with optional state overrides.
 *
 * @param processId Process to complete
 * @param stateOverrides Optional map of state field values to set
 */
public void completeOverride(UUID processId, Map<String, Object> stateOverrides) {
    try (var lock = acquireAdvisoryLock(processId)) {
        TState state = loadState(processId);

        if (stateOverrides != null && !stateOverrides.isEmpty()) {
            applyStateOverrides(state, stateOverrides);
            persistState(processId, state);
        }

        updateProcessStatus(processId, ProcessStatus.COMPLETED);
        logAudit(processId, "TSQ_COMPLETE_OVERRIDE", stateOverrides);
    }
}
```

## UI Integration

### Process Type Selector

The batch creation UI includes a process type selector:

```html
<select name="processType" class="form-select">
    <option value="STEP_BASED">Payment (Step-Based)</option>
    <option value="PROCESS_STEP">Payment (Process Step)</option>
</select>
```

### Separate TSQ Tabs

TSQ UI has separate tabs for commands vs processes:

```html
<ul class="nav nav-tabs">
    <li class="nav-item">
        <a class="nav-link" href="/tsq/commands">Command TSQ</a>
    </li>
    <li class="nav-item">
        <a class="nav-link" href="/tsq/processes">Process TSQ</a>
    </li>
</ul>
```

### Approval/Network Controller Dispatch

Controllers dispatch based on execution model:

```java
@PostMapping("/{id}/approve")
public String approve(@PathVariable UUID id, RedirectAttributes redirectAttributes) {
    PendingApproval approval = pendingApprovalRepository.findById(id).orElseThrow();

    if ("PROCESS_STEP".equals(approval.executionModel())) {
        // Call process step manager directly
        paymentStepProcess.onRiskApproval(approval.processId(),
            new RiskApprovalResponse("APPROVED", "MANUAL"));
    } else {
        // Use PGMQ for step-based process
        networkSimulator.sendApprovalApprovedReply(approval.commandId(), approval.correlationId());
    }

    // Update approval status
    pendingApprovalRepository.update(approval.withResolution(ApprovalStatus.APPROVED, "operator", null));

    redirectAttributes.addFlashAttribute("success", "Approval processed");
    return "redirect:/approvals";
}
```

## Configuration

### Application Properties

```yaml
commandbus:
  process:
    # Polling intervals
    pending-poll-interval: 1000      # ms - poll for PENDING processes
    retry-poll-interval: 5000        # ms - poll for retry-due processes
    timeout-check-interval: 60000    # ms - check wait timeouts
    deadline-check-interval: 60000   # ms - check process deadlines

    # Global defaults
    default-wait-timeout: PT1H       # ISO-8601 duration (1 hour)

    # Per-process-type overrides (by process type name)
    payment:
      default-wait-timeout: PT24H    # 24 hours for payments
      deadline-action: COMPENSATE    # COMPENSATE, TSQ, FAIL

  simulator:
    poll-interval: 1000              # ms - network simulator poll interval

  rate-limit:
    cleanup-interval: 3600000        # ms - cleanup expired buckets (1 hour)
```

### Rate Limit Configuration

```sql
INSERT INTO commandbus.rate_limit_config (resource_key, tickets_per_second, description)
VALUES
    ('fx_booking_api', 5, 'FX booking service - 5 TPS limit'),
    ('risk_api', 10, 'Risk assessment API - 10 TPS limit'),
    ('payment_gateway', 20, 'Payment gateway - 20 TPS limit');
```

## Complete Example: Payment Step Process

### PaymentStepProcess with Embedded State

```java
@Component
public class PaymentStepProcess extends TestProcessStepManager<PaymentStepProcess.PaymentState> {

    private static final String PROCESS_TYPE = "PaymentStep";
    private static final String DOMAIN = "payments";

    private final PaymentRepository paymentRepository;
    private final RiskService riskService;
    private final FxService fxService;
    private final PaymentGateway paymentGateway;

    public PaymentStepProcess(
            ProcessRepository processRepo,
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            Bucket4jRateLimiter rateLimiter,
            PaymentRepository paymentRepository,
            RiskService riskService,
            FxService fxService,
            PaymentGateway paymentGateway) {
        super(processRepo, jdbcTemplate, transactionTemplate, rateLimiter);
        this.paymentRepository = paymentRepository;
        this.riskService = riskService;
        this.fxService = fxService;
        this.paymentGateway = paymentGateway;
    }

    @Override
    public String getProcessType() {
        return PROCESS_TYPE;
    }

    @Override
    public String getDomain() {
        return DOMAIN;
    }

    @Override
    public String getExecutionModel() {
        return "PROCESS_STEP";
    }

    @Override
    public Class<PaymentState> getStateClass() {
        return PaymentState.class;
    }

    @Override
    protected ExceptionType classifyException(Exception e) {
        if (e instanceof TimeoutException) return ExceptionType.TRANSIENT;
        if (e instanceof ServiceUnavailableException) return ExceptionType.TRANSIENT;
        if (e instanceof RiskDeclinedException) return ExceptionType.BUSINESS;
        if (e instanceof ValidationException) return ExceptionType.PERMANENT;
        return ExceptionType.PERMANENT;
    }

    @Override
    protected DeadlineAction getDeadlineAction() {
        return DeadlineAction.COMPENSATE;
    }

    // ========== Main Execute Method ==========

    @Override
    protected void execute(PaymentState state) {

        // Step 1: Update payment status to PROCESSING
        step("updateStatusProcessing", s -> {
            paymentRepository.updateStatus(s.getPaymentId(), PaymentStatus.PROCESSING);
            return null;
        });

        // Step 2: Book transaction risk (with retry and compensation)
        var riskResult = step("bookRisk", StepOptions.<PaymentState, RiskResult>builder()
            .action(s -> riskService.bookRisk(s.getPaymentId(), s.getDebitAmount()))
            .maxRetries(3)
            .retryDelay(Duration.ofSeconds(5))
            .rateLimitKey("risk_api")
            .rateLimitTimeout(Duration.ofSeconds(10))
            .compensation(s -> riskService.unwindRisk(s.getRiskReference()))
            .build());

        state.setRiskReference(riskResult.reference());
        state.setRiskStatus(riskResult.status());

        // Step 3: Wait for manual approval if required
        if (riskResult.requiresManualApproval()) {
            state.setPendingApproval(true);
            wait("awaitRiskApproval", () -> state.isRiskApproved(), Duration.ofHours(2));
        }

        // Step 4: Book FX if cross-currency payment (with rate limiting)
        if (state.isFxRequired()) {
            var fxResult = step("bookFx", StepOptions.<PaymentState, FxResult>builder()
                .action(s -> fxService.bookFx(
                    s.getDebitCurrency(),
                    s.getCreditCurrency(),
                    s.getDebitAmount(),
                    s.getValueDate()))
                .maxRetries(2)
                .retryDelay(Duration.ofSeconds(3))
                .rateLimitKey("fx_booking_api")
                .rateLimitTimeout(Duration.ofSeconds(10))
                .compensation(s -> fxService.unwindFx(s.getFxContractId()))
                .build());

            state.setFxContractId(fxResult.contractId());
            state.setFxRate(fxResult.rate());
            state.setCreditAmount(fxResult.creditAmount());
        }

        // Step 5: Submit payment to external gateway (with rate limiting, no retry)
        var submitResult = step("submitPayment", StepOptions.<PaymentState, SubmitResult>builder()
            .action(s -> paymentGateway.submit(buildSubmission(s)))
            .maxRetries(1)
            .rateLimitKey("payment_gateway")
            .rateLimitTimeout(Duration.ofSeconds(5))
            .build());

        state.setSubmissionReference(submitResult.reference());

        // Steps 6-9: Wait for L1-L4 network confirmations
        wait("awaitL1", () -> state.isL1Received(), Duration.ofMinutes(5));
        wait("awaitL2", () -> state.isL2Received(), Duration.ofMinutes(5));
        wait("awaitL3", () -> state.isL3Received(), Duration.ofHours(3));
        wait("awaitL4", () -> state.isL4Received());  // Uses default timeout

        // Step 10: Mark payment complete
        step("updateStatusComplete", s -> {
            paymentRepository.updateStatus(s.getPaymentId(), PaymentStatus.COMPLETE);
            return null;
        });
    }

    // ========== Async Response Handlers ==========

    public void onRiskApproval(UUID processId, RiskApprovalResponse response) {
        processAsyncResponse(processId, state -> {
            state.setRiskApproved(true);
            state.setRiskStatus("APPROVED");
        });
    }

    public void onRiskDeclined(UUID processId, RiskDeclinedResponse response) {
        processAsyncResponse(processId, state -> {
            state.setRiskApproved(false);
            state.setRiskStatus("DECLINED");
            state.setErrorCode("RISK_DECLINED");
            state.setErrorMessage(response.reason());
        });
    }

    public void onL1Response(UUID processId, L1Response response) {
        processAsyncResponse(processId, state -> {
            state.setL1Received(true);
            state.setL1Reference(response.reference());
        });
    }

    public void onL1Error(UUID processId, L1ErrorResponse response) {
        processAsyncResponse(processId, state -> {
            state.setL1Received(true);
            state.setL1ErrorCode(response.errorCode());
            state.setL1ErrorMessage(response.errorMessage());
        });
    }

    public void onL2Response(UUID processId, L2Response response) {
        processAsyncResponse(processId, state -> {
            state.setL2Received(true);
            state.setL2Reference(response.reference());
        });
    }

    public void onL3Response(UUID processId, L3Response response) {
        processAsyncResponse(processId, state -> {
            state.setL3Received(true);
            state.setL3Reference(response.reference());
        });
    }

    public void onL4Response(UUID processId, L4Response response) {
        processAsyncResponse(processId, state -> {
            state.setL4Received(true);
            state.setL4Reference(response.reference());
        });
    }

    // ========== Public API ==========

    /**
     * Start a single payment - executes immediately in caller thread.
     * Returns after process completes, waits for async response, or needs retry.
     */
    public UUID startPayment(Payment payment) {
        PaymentState initialState = new PaymentState();
        initialState.setPaymentId(payment.paymentId());
        initialState.setProcessDeadline(payment.cutoffTimestamp());

        // Copy immutable payment details into state (avoids DB lookups during execution)
        initialState.setDebitAmount(payment.debitAmount());
        initialState.setOriginalCreditAmount(payment.creditAmount());
        initialState.setDebitCurrency(payment.debitCurrency().toString());
        initialState.setCreditCurrency(payment.creditCurrency().toString());
        initialState.setDebitAccount(payment.debitAccount());
        initialState.setCreditAccount(payment.creditAccount());
        initialState.setValueDate(payment.valueDate());
        initialState.setFxRequired(payment.requiresFx());

        // Default: immediate execution (good for API calls)
        return start(initialState);
    }

    /**
     * Start a payment without immediate execution - worker picks it up.
     */
    public UUID startPaymentDeferred(Payment payment) {
        PaymentState initialState = buildInitialState(payment);
        return start(initialState, StartOptions.deferred());
    }

    private PaymentState buildInitialState(Payment payment) {
        PaymentState state = new PaymentState();
        state.setPaymentId(payment.paymentId());
        state.setProcessDeadline(payment.cutoffTimestamp());
        state.setDebitAmount(payment.debitAmount());
        state.setOriginalCreditAmount(payment.creditAmount());
        state.setDebitCurrency(payment.debitCurrency().toString());
        state.setCreditCurrency(payment.creditCurrency().toString());
        state.setDebitAccount(payment.debitAccount());
        state.setCreditAccount(payment.creditAccount());
        state.setValueDate(payment.valueDate());
        state.setFxRequired(payment.requiresFx());
        return state;
    }

    public BatchResult startPaymentBatch(List<Payment> payments, PaymentStepBehavior behavior) {
        List<PaymentState> states = payments.stream()
            .map(p -> {
                PaymentState state = new PaymentState();
                state.setPaymentId(p.paymentId());
                state.setFxRequired(p.requiresFx());
                state.setProcessDeadline(p.cutoffTimestamp());

                // Set probabilistic behavior for each step
                if (behavior != null) {
                    state.setStepBehavior("bookRisk", behavior.bookRisk().toProbabilistic());
                    state.setStepBehavior("bookFx", behavior.bookFx());
                    state.setStepBehavior("submitPayment", behavior.submitPayment());
                    state.setStepBehavior("awaitL1", behavior.awaitL1());
                    state.setStepBehavior("awaitL2", behavior.awaitL2());
                    state.setStepBehavior("awaitL3", behavior.awaitL3());
                    state.setStepBehavior("awaitL4", behavior.awaitL4());
                }

                return state;
            })
            .toList();

        List<UUID> processIds = startBatch(states);
        return new BatchResult(UUID.randomUUID(), processIds, processIds.size(), List.of());
    }

    // ========== Helper Methods ==========

    /**
     * Build submission from state - no database access needed during execution.
     * All payment details were copied to state at process creation time.
     */
    private PaymentSubmission buildSubmission(PaymentState state) {
        return PaymentSubmission.builder()
            .paymentId(state.getPaymentId())
            .debitAccount(state.getDebitAccount())
            .creditAccount(state.getCreditAccount())
            .debitAmount(state.getDebitAmount())
            .creditAmount(state.getCreditAmount() != null
                ? state.getCreditAmount()
                : state.getOriginalCreditAmount())
            .fxContractId(state.getFxContractId())
            .fxRate(state.getFxRate())
            .valueDate(state.getValueDate())
            .build();
    }

    // ========== Embedded State Class ==========

    /**
     * Payment process state - embedded as nested class.
     */
    public static class PaymentState extends TestProcessStepState {

        private UUID paymentId;
        private PaymentStatus status;

        // Payment details (immutable - set at creation from Payment entity)
        private BigDecimal debitAmount;
        private BigDecimal originalCreditAmount;
        private String debitCurrency;
        private String creditCurrency;
        private String debitAccount;
        private String creditAccount;
        private LocalDate valueDate;

        // Risk
        private String riskReference;
        private String riskStatus;
        private boolean pendingApproval;
        private boolean riskApproved;

        // FX
        private boolean fxRequired;
        private Long fxContractId;
        private BigDecimal fxRate;
        private BigDecimal creditAmount;  // Calculated after FX if required

        // Submission
        private String submissionReference;

        // L1-L4 confirmations
        private boolean l1Received;
        private String l1Reference;
        private String l1ErrorCode;
        private String l1ErrorMessage;

        private boolean l2Received;
        private String l2Reference;
        private String l2ErrorCode;
        private String l2ErrorMessage;

        private boolean l3Received;
        private String l3Reference;
        private String l3ErrorCode;
        private String l3ErrorMessage;

        private boolean l4Received;
        private String l4Reference;
        private String l4ErrorCode;
        private String l4ErrorMessage;

        // Getters and setters...

        public UUID getPaymentId() { return paymentId; }
        public void setPaymentId(UUID paymentId) { this.paymentId = paymentId; }

        public PaymentStatus getStatus() { return status; }
        public void setStatus(PaymentStatus status) { this.status = status; }

        // Payment details (immutable - set at creation)
        public BigDecimal getDebitAmount() { return debitAmount; }
        public void setDebitAmount(BigDecimal debitAmount) { this.debitAmount = debitAmount; }

        public BigDecimal getOriginalCreditAmount() { return originalCreditAmount; }
        public void setOriginalCreditAmount(BigDecimal amt) { this.originalCreditAmount = amt; }

        public String getDebitCurrency() { return debitCurrency; }
        public void setDebitCurrency(String debitCurrency) { this.debitCurrency = debitCurrency; }

        public String getCreditCurrency() { return creditCurrency; }
        public void setCreditCurrency(String creditCurrency) { this.creditCurrency = creditCurrency; }

        public String getDebitAccount() { return debitAccount; }
        public void setDebitAccount(String debitAccount) { this.debitAccount = debitAccount; }

        public String getCreditAccount() { return creditAccount; }
        public void setCreditAccount(String creditAccount) { this.creditAccount = creditAccount; }

        public LocalDate getValueDate() { return valueDate; }
        public void setValueDate(LocalDate valueDate) { this.valueDate = valueDate; }

        public String getRiskReference() { return riskReference; }
        public void setRiskReference(String riskReference) { this.riskReference = riskReference; }

        public String getRiskStatus() { return riskStatus; }
        public void setRiskStatus(String riskStatus) { this.riskStatus = riskStatus; }

        public boolean isPendingApproval() { return pendingApproval; }
        public void setPendingApproval(boolean pendingApproval) { this.pendingApproval = pendingApproval; }

        public boolean isRiskApproved() { return riskApproved; }
        public void setRiskApproved(boolean riskApproved) { this.riskApproved = riskApproved; }

        public boolean isFxRequired() { return fxRequired; }
        public void setFxRequired(boolean fxRequired) { this.fxRequired = fxRequired; }

        public Long getFxContractId() { return fxContractId; }
        public void setFxContractId(Long fxContractId) { this.fxContractId = fxContractId; }

        public BigDecimal getFxRate() { return fxRate; }
        public void setFxRate(BigDecimal fxRate) { this.fxRate = fxRate; }

        public BigDecimal getCreditAmount() { return creditAmount; }
        public void setCreditAmount(BigDecimal creditAmount) { this.creditAmount = creditAmount; }

        public String getSubmissionReference() { return submissionReference; }
        public void setSubmissionReference(String submissionReference) { this.submissionReference = submissionReference; }

        public boolean isL1Received() { return l1Received; }
        public void setL1Received(boolean l1Received) { this.l1Received = l1Received; }

        public String getL1Reference() { return l1Reference; }
        public void setL1Reference(String l1Reference) { this.l1Reference = l1Reference; }

        public String getL1ErrorCode() { return l1ErrorCode; }
        public void setL1ErrorCode(String l1ErrorCode) { this.l1ErrorCode = l1ErrorCode; }

        public String getL1ErrorMessage() { return l1ErrorMessage; }
        public void setL1ErrorMessage(String l1ErrorMessage) { this.l1ErrorMessage = l1ErrorMessage; }

        public boolean isL2Received() { return l2Received; }
        public void setL2Received(boolean l2Received) { this.l2Received = l2Received; }

        public String getL2Reference() { return l2Reference; }
        public void setL2Reference(String l2Reference) { this.l2Reference = l2Reference; }

        public String getL2ErrorCode() { return l2ErrorCode; }
        public void setL2ErrorCode(String l2ErrorCode) { this.l2ErrorCode = l2ErrorCode; }

        public String getL2ErrorMessage() { return l2ErrorMessage; }
        public void setL2ErrorMessage(String l2ErrorMessage) { this.l2ErrorMessage = l2ErrorMessage; }

        public boolean isL3Received() { return l3Received; }
        public void setL3Received(boolean l3Received) { this.l3Received = l3Received; }

        public String getL3Reference() { return l3Reference; }
        public void setL3Reference(String l3Reference) { this.l3Reference = l3Reference; }

        public String getL3ErrorCode() { return l3ErrorCode; }
        public void setL3ErrorCode(String l3ErrorCode) { this.l3ErrorCode = l3ErrorCode; }

        public String getL3ErrorMessage() { return l3ErrorMessage; }
        public void setL3ErrorMessage(String l3ErrorMessage) { this.l3ErrorMessage = l3ErrorMessage; }

        public boolean isL4Received() { return l4Received; }
        public void setL4Received(boolean l4Received) { this.l4Received = l4Received; }

        public String getL4Reference() { return l4Reference; }
        public void setL4Reference(String l4Reference) { this.l4Reference = l4Reference; }

        public String getL4ErrorCode() { return l4ErrorCode; }
        public void setL4ErrorCode(String l4ErrorCode) { this.l4ErrorCode = l4ErrorCode; }

        public String getL4ErrorMessage() { return l4ErrorMessage; }
        public void setL4ErrorMessage(String l4ErrorMessage) { this.l4ErrorMessage = l4ErrorMessage; }

        // Helper methods
        public boolean hasAnyError() {
            return l1ErrorCode != null || l2ErrorCode != null ||
                   l3ErrorCode != null || l4ErrorCode != null;
        }

        public boolean isL4Success() {
            return l4Received && l4ErrorCode == null;
        }
    }
}
```

### API Controller Example

Shows how immediate execution integrates with REST endpoints:

```java
@RestController
@RequestMapping("/api/payments")
public class PaymentApiController {

    private final PaymentStepProcess paymentProcess;
    private final PaymentRepository paymentRepository;
    private final ProcessRepository processRepository;

    @PostMapping
    public ResponseEntity<PaymentResponse> submitPayment(@RequestBody PaymentRequest request) {
        // 1. Create payment record
        Payment payment = paymentRepository.save(Payment.builder()
            .debitAccount(request.debitAccount())
            .creditAccount(request.creditAccount())
            .debitAmount(request.amount())
            .debitCurrency(request.debitCurrency())
            .creditCurrency(request.creditCurrency())
            .valueDate(request.valueDate())
            .cutoffTimestamp(Instant.now().plus(Duration.ofHours(4)))
            .build());

        // 2. Start payment process - executes immediately until wait/retry
        UUID processId = paymentProcess.startPayment(payment);

        // 3. Check current status and return appropriate response
        ProcessStatus status = processRepository.getStatus(processId);
        return switch (status) {
            case COMPLETED -> ResponseEntity.ok(PaymentResponse.completed(processId));
            case WAITING_FOR_ASYNC -> ResponseEntity.accepted(
                PaymentResponse.inProgress(processId, "Awaiting confirmations"));
            case WAITING_FOR_RETRY -> ResponseEntity.accepted(
                PaymentResponse.inProgress(processId, "Processing (will retry)"));
            default -> ResponseEntity.accepted(
                PaymentResponse.inProgress(processId, "Processing"));
        };
    }

    @GetMapping("/{processId}/status")
    public ResponseEntity<PaymentStatusResponse> getStatus(@PathVariable UUID processId) {
        ProcessMetadata process = processRepository.findById(processId)
            .orElseThrow(() -> new NotFoundException("Process not found"));

        PaymentStepProcess.PaymentState state = objectMapper.readValue(
            process.state(), PaymentStepProcess.PaymentState.class);

        return ResponseEntity.ok(PaymentStatusResponse.builder()
            .processId(processId)
            .status(process.status())
            .riskApproved(state.isRiskApproved())
            .l1Received(state.isL1Received())
            .l2Received(state.isL2Received())
            .l3Received(state.isL3Received())
            .l4Received(state.isL4Received())
            .errorCode(state.getErrorCode())
            .errorMessage(state.getErrorMessage())
            .build());
    }
}
```

**Key Points:**
- `startPayment()` executes immediately in the HTTP request thread
- Process runs until completion, wait for async, or retry needed
- Response is returned with appropriate status code (200/202)
- Subsequent confirmations (L1-L4) arrive via async callbacks
- Worker handles retries and async resume automatically

## File Structure

```
src/main/java/com/ivamare/commandbus/
├── process/
│   ├── step/
│   │   ├── ProcessStepManager.java          # Base class
│   │   ├── ProcessStepState.java            # Base state class
│   │   ├── ProcessStepWorker.java           # Scheduled worker
│   │   ├── ExecutionContext.java            # Per-execution context
│   │   ├── StepOptions.java                 # Step configuration builder
│   │   ├── StartOptions.java                # Single process start options
│   │   ├── BatchOptions.java                # Batch configuration builder
│   │   ├── StepRecord.java                  # Step execution record
│   │   ├── WaitRecord.java                  # Wait execution record
│   │   ├── SideEffectRecord.java            # Side effect record
│   │   ├── StepStatus.java                  # Step status enum
│   │   ├── ExceptionType.java               # Exception classification
│   │   ├── DeadlineAction.java              # Deadline behavior enum
│   │   └── exceptions/
│   │       ├── WaitConditionNotMetException.java
│   │       ├── WaitingForRetryException.java
│   │       ├── StepFailedException.java
│   │       ├── BusinessRuleException.java
│   │       └── RateLimitExceededException.java
│   ├── ratelimit/
│   │   ├── Bucket4jRateLimiter.java         # Rate limiter
│   │   └── RateLimitConfig.java             # Config record
│   └── ... (existing BaseProcessManager files)

src/test/java/com/ivamare/commandbus/e2e/
├── process/
│   ├── TestProcessStepManager.java          # With probabilistic behavior
│   └── TestProcessStepState.java            # With behavior config
├── payment/
│   ├── PaymentStepProcess.java              # Payment implementation
│   ├── StepPaymentNetworkSimulator.java     # Async response simulator
│   └── ... (existing payment files)
├── controller/
│   ├── PaymentController.java               # Updated with process type selector
│   ├── TsqController.java                   # Updated with separate tabs
│   ├── ApprovalController.java              # Updated with dispatch
│   ├── PendingNetworkController.java        # Updated with dispatch
│   └── ...

src/main/resources/
├── db/migration/
│   └── V007__process_step_schema.sql        # Schema extensions
└── application.yml                          # Configuration

src/test/resources/
├── templates/e2e/pages/
│   ├── payment_batch_new.html               # Updated with process type selector
│   ├── tsq.html                             # Updated with tabs
│   └── ...
```

## Implementation Checklist

### Phase 1: Framework Core
- [ ] Create `ProcessStepState` base class
- [ ] Create `StepRecord`, `WaitRecord`, `SideEffectRecord` records
- [ ] Create `StepOptions` and `BatchOptions` builders
- [ ] Create exception classes
- [ ] Create `ExceptionType` and `DeadlineAction` enums
- [ ] Implement `ExecutionContext` class
- [ ] Implement `ProcessStepManager` base class
  - [ ] `step()` methods with replay logic
  - [ ] `wait()` methods with timeout support
  - [ ] `sideEffect()` method
  - [ ] `processAsyncResponse()` method (with late-response handling)
  - [ ] Compensation logic
  - [ ] Advisory lock implementation

### Phase 2: Rate Limiting
- [ ] Add Bucket4j dependency
- [ ] Create `Bucket4jRateLimiter` component
- [ ] Create rate limit config repository
- [ ] Integrate rate limiting into `step()` method

### Phase 3: Worker and Scheduling
- [ ] Implement `ProcessStepWorker`
  - [ ] Pending process polling with virtual threads
  - [ ] Retry polling with virtual threads
  - [ ] Wait timeout checking
  - [ ] Deadline checking
- [ ] Add configuration properties support

### Phase 4: Database Schema
- [ ] Create migration V007 with schema extensions
- [ ] Add indexes for scheduler queries
- [ ] Update `ProcessRepository` for new queries

### Phase 5: TSQ Integration
- [ ] Implement `retry()` method
- [ ] Implement `cancelOverride()` method
- [ ] Implement `completeOverride()` method

### Phase 6: E2E Testing Support
- [ ] Create `TestProcessStepState` with behavior config
- [ ] Create `TestProcessStepManager` with behavior injection
- [ ] Create `PaymentStepProcess` with embedded `PaymentState`
- [ ] Create `StepPaymentNetworkSimulator`

### Phase 7: UI Integration
- [ ] Add process type selector to batch creation
- [ ] Add separate TSQ tabs
- [ ] Update approval controller with dispatch
- [ ] Update pending network controller with dispatch
- [ ] Add execution_model to pending tables

### Phase 8: Testing
- [ ] Unit tests for `ProcessStepManager`
- [ ] Unit tests for replay logic
- [ ] Unit tests for compensation
- [ ] Unit tests for rate limiting
- [ ] Integration tests for full payment flow
- [ ] Integration tests for timeout scenarios
- [ ] Integration tests for concurrent async responses
- [ ] Integration tests for late-arriving responses
