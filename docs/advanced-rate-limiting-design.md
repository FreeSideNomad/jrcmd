# Adaptive Rate Limiting Design: Using Existing Command Infrastructure

## Problem Statement

### Scenario
- FX booking API sustains **5 TPS** on average day (total across all consumers)
- Multiple Java services, multiple instances per service
- On "bad days", downstream responds slower, reducing effective throughput
- Processes may have **multiple rate-limited steps** (e.g., bookFx, bookRisk)
- Need automatic backpressure without creating waiting threads

### Requirements
1. Limit concurrent calls to downstream APIs (not just rate per second)
2. Support multiple rate-limited steps per process
3. No thread blocking - processes should suspend while waiting
4. Natural adaptation to downstream latency
5. Crash recovery without leaked resources
6. **Leverage existing CommandBus, Worker, and Handler infrastructure**

---

## Solution: Existing Commands with Dedicated Domains

### Key Insight

**Reuse existing Command/Handler/Worker infrastructure!**

Instead of creating custom rate-limiting messages and workers, use the existing CommandBus:
- Each rate-limited step = separate domain (e.g., `fx`, `risk`)
- Send command with `replyTo` pointing back to process domain
- Worker pool size (replicas) = concurrency limit
- Reply handler calls `processAsyncResponse()` to resume waiting process

### Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         Process Execution                                   │
│                                                                             │
│  PaymentWorkflow                                                            │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │                                                                     │    │
│  │  step("validate")      →  inline execution                          │    │
│  │          ↓                                                          │    │
│  │  commandStep("bookFx", BookFxCommand, FxResult.class)               │    │
│  │          │                                                          │    │
│  │          ├──► CommandBus.send("fx", "BookFx", cmd, replyTo="payments") │
│  │          └──► Process SUSPENDS (WAITING_FOR_ASYNC)                  │    │
│  │                    ↓                                                │    │
│  │              [Reply received via processAsyncResponse()]            │    │
│  │                    ↓                                                │    │
│  │  step("enrichData")    →  inline execution                          │    │
│  │          ↓                                                          │    │
│  │  commandStep("bookRisk", BookRiskCommand, RiskResult.class)         │    │
│  │          │                                                          │    │
│  │          ├──► CommandBus.send("risk", "BookRisk", cmd, replyTo="payments")
│  │          └──► Process SUSPENDS (WAITING_FOR_ASYNC)                  │    │
│  │                    ↓                                                │    │
│  │              [Reply received via processAsyncResponse()]            │    │
│  │                    ↓                                                │    │
│  │  step("executePayment") →  inline execution                         │    │
│  │          ↓                                                          │    │
│  │  COMPLETED                                                          │    │
│  │                                                                     │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
                │                                    │
                │                                    │
                ▼                                    ▼
┌───────────────────────────────┐    ┌───────────────────────────────┐
│  fx__commands queue (PGMQ)    │    │  risk__commands queue (PGMQ)  │
│  (existing command queue)     │    │  (existing command queue)     │
│                               │    │                               │
│  ┌─────┐ ┌─────┐ ┌─────┐      │    │  ┌─────┐ ┌─────┐ ┌─────┐      │
│  │cmd1 │ │cmd2 │ │cmd3 │ ...  │    │  │cmd1 │ │cmd2 │ │cmd3 │ ...  │
│  └─────┘ └─────┘ └─────┘      │    │  └─────┘ └─────┘ └─────┘      │
│                               │    │                               │
└───────────────────────────────┘    └───────────────────────────────┘
                │                                    │
                ▼                                    ▼
┌───────────────────────────────┐    ┌───────────────────────────────┐
│  FX Domain Worker Pool        │    │  Risk Domain Worker Pool      │
│  (5 worker replicas = 5 TPS)  │    │  (10 worker replicas)         │
│  Uses existing Worker class   │    │  Uses existing Worker class   │
│                               │    │                               │
│  ┌────────┐  ┌────────┐       │    │  ┌────────┐  ┌────────┐       │
│  │Worker 1│  │Worker 2│       │    │  │Worker 1│  │Worker 2│       │
│  │(BookFx │  │(BookFx │       │    │  │(BookRisk│ │(BookRisk│      │
│  │Handler)│  │Handler)│       │    │  │Handler) │ │Handler) │      │
│  └───┬────┘  └───┬────┘       │    │  └───┬────┘  └───┬────┘       │
│      │           │            │    │      │           │            │
│  ┌────────┐  ┌────────┐       │    │  ... (10 total) ...           │
│  │Worker 3│  │Worker 4│       │    │                               │
│  └───┬────┘  └───┬────┘       │    │                               │
│      │           │            │    │                               │
│  ┌────────┐      │            │    │                               │
│  │Worker 5│      │            │    │                               │
│  └───┬────┘      │            │    │                               │
└──────┼───────────┼────────────┘    └───────────────────────────────┘
       │           │                        │           │
       ▼           ▼                        ▼           ▼
┌─────────────────────────┐        ┌─────────────────────────┐
│      FX Booking API     │        │      Risk API           │
│   (max 5 concurrent)    │        │   (max 10 concurrent)   │
└─────────────────────────┘        └─────────────────────────┘
       │           │                        │           │
       │  Handler returns Reply            │  Handler returns Reply
       ▼           ▼                        ▼           ▼
┌───────────────────────────────┐    ┌───────────────────────────────┐
│  payments__replies queue      │    │  payments__replies queue      │
│  (replyTo from command)       │    │  (same reply queue)           │
└───────────────────────────────┘    └───────────────────────────────┘
                │                                    │
                └──────────────┬─────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│              Payments Domain Reply Handler                                  │
│                                                                             │
│  ProcessAsyncReplyHandler                                                   │
│  - Receives replies with correlationId = processId                          │
│  - Calls processManager.processAsyncResponse(processId, stateUpdater)       │
│  - Process resumes and continues execution                                  │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Why Reuse Existing Commands Over Custom Messages

| Aspect | Custom RateLimitedStep Messages | Existing Command Infrastructure |
|--------|--------------------------------|--------------------------------|
| Message format | New classes needed | Reuse Command/Reply |
| Worker | Custom worker class | Existing Worker class |
| Handler | Custom processing | Standard Handler interface |
| Retry logic | Must implement | Built-in with policies |
| TSQ integration | Must implement | Built-in |
| Auditing | Must implement | Built-in audit trail |
| Monitoring | Custom metrics | Existing command metrics |
| Code reuse | Low | High |

---

## Configuration (Application Config Files)

Rate limiting configuration belongs in application config files, not database:
- **Version controlled** - Configuration changes go through normal deployment process
- **No runtime changes** needed - Redeploy to change concurrency limits
- **Concurrency formula**: `replicas × concurrent-messages = max concurrent API calls`

### Worker Threading Model

Workers process messages **synchronously** - each virtual thread:
1. Picks up a message from the queue
2. Calls downstream API (e.g., FX service)
3. Blocks waiting for response (virtual thread yields efficiently)
4. Only when complete, thread is available for next message

This means `concurrent-messages` directly reflects how many API calls are in-flight per worker instance.

### Configuration Structure

**Features** = Global worker capabilities (what the worker CAN do)
**Domains** = Per-domain settings including concurrency limits

```yaml
# application.yml - Worker configuration
commandbus:
  # Global feature toggles - what this worker instance does
  features:
    command-handlers: true      # Process commands via handlers
    process-execution: true     # Run ProcessStepManager workflows
    reply-processing: true      # Handle replies from other domains

  # Per-domain settings - which domains and their concurrency
  domains:
    fx:
      enabled: true
      concurrent-messages: 2    # Max 2 concurrent FX API calls per instance
    risk:
      enabled: true
      concurrent-messages: 3    # Max 3 concurrent Risk API calls per instance
    payments:
      enabled: true
      concurrent-messages: 10   # Higher for general processing

  # Command step mappings (for ProcessStepManager.commandStep())
  command-steps:
    bookFx:
      domain: fx
      command-type: BookFx
      timeout: 30s
    bookRisk:
      domain: risk
      command-type: BookRisk
      timeout: 30s
```

### Configuration Properties Class

```java
@ConfigurationProperties(prefix = "commandbus")
public class CommandBusProperties {

    private Features features = new Features();
    private Map<String, DomainConfig> domains = new HashMap<>();
    private Map<String, CommandStepConfig> commandSteps = new HashMap<>();

    @Data
    public static class Features {
        private boolean commandHandlers = true;
        private boolean processExecution = true;
        private boolean replyProcessing = true;
    }

    @Data
    public static class DomainConfig {
        private boolean enabled = true;
        private int concurrentMessages = 5;  // Default concurrent messages per domain
    }

    @Data
    public static class CommandStepConfig {
        private String domain;
        private String commandType;
        private Duration timeout = Duration.ofSeconds(30);
    }

    // getters/setters
}
```

### Concurrency Calculation

**Total concurrent API calls = replicas × concurrent-messages**

Example for FX API (max 5 TPS supported):
- Option A: 3 replicas × 1 concurrent = 3 max (under-utilized but safe)
- Option B: 3 replicas × 2 concurrent = 6 max (slightly over, rely on API backpressure)
- Option C: 2 replicas × 2 concurrent = 4 max (good fit)

Choose based on:
- Downstream API's actual capacity
- Acceptable margin (under vs over)
- HA requirements (min 2-3 replicas recommended)

### No Database Tables Needed

The command-based approach requires **no additional database tables**:
- Uses existing PGMQ queues (created automatically per domain)
- Uses existing `commandbus.command_metadata` for audit trail
- Concurrency controlled by configuration, not database

---

## Java Implementation - Using Existing Command Infrastructure

### 1. Command Classes (Request/Response)

Use standard data classes that will be serialized as command data:

```java
package com.example.payments.commands;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Command data for FX booking.
 * Sent via CommandBus.send("fx", "BookFx", ...)
 */
public record BookFxCommand(
    UUID processId,          // For correlation back to process
    String stepName,         // "bookFx"
    BigDecimal amount,
    String fromCurrency,
    String toCurrency
) {}

/**
 * Command data for risk assessment.
 * Sent via CommandBus.send("risk", "BookRisk", ...)
 */
public record BookRiskCommand(
    UUID processId,          // For correlation back to process
    String stepName,         // "bookRisk"
    BigDecimal amount,
    String currency,
    String debitAccount,
    String creditAccount
) {}

/**
 * Reply data for command step results.
 * Returned via Handler.Reply
 */
public record CommandStepReply(
    UUID processId,
    String stepName,
    boolean success,
    String resultJson,       // Serialized result (if success)
    String errorCode,        // Error code (if failure)
    String errorMessage      // Error message (if failure)
) {
    public static CommandStepReply success(UUID processId, String stepName, String resultJson) {
        return new CommandStepReply(processId, stepName, true, resultJson, null, null);
    }

    public static CommandStepReply failure(UUID processId, String stepName, String errorCode, String errorMessage) {
        return new CommandStepReply(processId, stepName, false, null, errorCode, errorMessage);
    }
}
```

### 2. FX Booking Handler (Uses Existing Handler Interface)

```java
package com.example.fx.handlers;

import com.example.payments.commands.BookFxCommand;
import com.example.payments.commands.CommandStepReply;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ivamare.commandbus.handler.Handler;
import com.ivamare.commandbus.model.Command;

import java.math.BigDecimal;

/**
 * Handler for FX booking commands.
 *
 * Deploy in FX domain workers - worker replica count = concurrency limit.
 * 5 worker replicas = max 5 concurrent FX API calls.
 */
public class BookFxHandler implements Handler {

    private final FxService fxService;
    private final ObjectMapper objectMapper;

    public BookFxHandler(FxService fxService, ObjectMapper objectMapper) {
        this.fxService = fxService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getCommandType() {
        return "BookFx";
    }

    @Override
    public Reply handle(Command command) {
        try {
            // Deserialize command data
            BookFxCommand fxCommand = objectMapper.convertValue(
                command.data(), BookFxCommand.class);

            // Call the actual FX API (rate-limited by worker count)
            FxResult result = fxService.book(
                fxCommand.amount(),
                fxCommand.fromCurrency(),
                fxCommand.toCurrency()
            );

            // Return success reply with result
            CommandStepReply reply = CommandStepReply.success(
                fxCommand.processId(),
                fxCommand.stepName(),
                objectMapper.writeValueAsString(result)
            );

            return Reply.success(objectMapper.convertValue(reply, java.util.Map.class));

        } catch (Exception e) {
            BookFxCommand fxCommand = objectMapper.convertValue(
                command.data(), BookFxCommand.class);

            CommandStepReply reply = CommandStepReply.failure(
                fxCommand.processId(),
                fxCommand.stepName(),
                e.getClass().getSimpleName(),
                e.getMessage()
            );

            return Reply.businessRuleFailed(
                e.getClass().getSimpleName(),
                e.getMessage(),
                objectMapper.convertValue(reply, java.util.Map.class)
            );
        }
    }
}
```

### 3. Risk Handler (Same Pattern)

```java
package com.example.risk.handlers;

import com.example.payments.commands.BookRiskCommand;
import com.example.payments.commands.CommandStepReply;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ivamare.commandbus.handler.Handler;
import com.ivamare.commandbus.model.Command;

/**
 * Handler for risk assessment commands.
 *
 * Deploy in Risk domain workers - 10 replicas = max 10 concurrent risk calls.
 */
public class BookRiskHandler implements Handler {

    private final RiskService riskService;
    private final ObjectMapper objectMapper;

    public BookRiskHandler(RiskService riskService, ObjectMapper objectMapper) {
        this.riskService = riskService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getCommandType() {
        return "BookRisk";
    }

    @Override
    public Reply handle(Command command) {
        try {
            BookRiskCommand riskCommand = objectMapper.convertValue(
                command.data(), BookRiskCommand.class);

            RiskResult result = riskService.assess(
                riskCommand.amount(),
                riskCommand.currency(),
                riskCommand.debitAccount(),
                riskCommand.creditAccount()
            );

            CommandStepReply reply = CommandStepReply.success(
                riskCommand.processId(),
                riskCommand.stepName(),
                objectMapper.writeValueAsString(result)
            );

            return Reply.success(objectMapper.convertValue(reply, java.util.Map.class));

        } catch (Exception e) {
            BookRiskCommand riskCommand = objectMapper.convertValue(
                command.data(), BookRiskCommand.class);

            CommandStepReply reply = CommandStepReply.failure(
                riskCommand.processId(),
                riskCommand.stepName(),
                e.getClass().getSimpleName(),
                e.getMessage()
            );

            return Reply.businessRuleFailed(
                e.getClass().getSimpleName(),
                e.getMessage(),
                objectMapper.convertValue(reply, java.util.Map.class)
            );
        }
    }
}
```

### 4. Process Async Reply Handler (Handles Replies in Payments Domain)

```java
package com.example.payments.handlers;

import com.example.payments.commands.CommandStepReply;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ivamare.commandbus.handler.Handler;
import com.ivamare.commandbus.model.Command;
import com.ivamare.commandbus.process.step.ProcessStepManager;

import java.util.Map;
import java.util.UUID;

/**
 * Handler for replies from rate-limited command steps.
 *
 * Receives replies on the payments__replies queue and calls
 * processAsyncResponse() to resume waiting processes.
 */
public class ProcessAsyncReplyHandler implements Handler {

    private final Map<String, ProcessStepManager<?>> processManagers;
    private final ObjectMapper objectMapper;

    public ProcessAsyncReplyHandler(
            Map<String, ProcessStepManager<?>> processManagers,
            ObjectMapper objectMapper) {
        this.processManagers = processManagers;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getCommandType() {
        return "CommandStepReply";  // Reply type from FX/Risk handlers
    }

    @Override
    public Reply handle(Command command) {
        try {
            CommandStepReply stepReply = objectMapper.convertValue(
                command.data(), CommandStepReply.class);

            UUID processId = stepReply.processId();
            String stepName = stepReply.stepName();

            // Find the appropriate process manager
            // In practice, might look up by process type from process table
            ProcessStepManager<?> processManager = findProcessManager(processId);

            if (processManager == null) {
                return Reply.businessRuleFailed(
                    "PROCESS_NOT_FOUND",
                    "No process manager found for process: " + processId,
                    null
                );
            }

            // Call processAsyncResponse with the step result
            processManager.processAsyncResponse(processId, state -> {
                // Store the reply in process state for the step to pick up
                state.storeCommandStepReply(stepName, stepReply);
            });

            return Reply.success(Map.of(
                "processId", processId.toString(),
                "stepName", stepName,
                "resumed", true
            ));

        } catch (Exception e) {
            return Reply.businessRuleFailed(
                "REPLY_PROCESSING_FAILED",
                e.getMessage(),
                null
            );
        }
    }

    private ProcessStepManager<?> findProcessManager(UUID processId) {
        // Look up process type from database and return appropriate manager
        // For simplicity, assuming single manager
        return processManagers.values().stream().findFirst().orElse(null);
    }
}
```

### 5. ProcessStepManager Integration - commandStep() Method

Add to `ProcessStepManager.java`:

```java
/**
 * Execute a step via Command/Handler pattern with rate limiting.
 *
 * <p>Sends a command to a dedicated domain queue. The worker pool size
 * for that domain controls concurrency. Reply is received via the
 * ProcessAsyncReplyHandler which calls processAsyncResponse().
 *
 * @param stepName Step name (e.g., "bookFx")
 * @param targetDomain Domain to send command to (e.g., "fx", "risk")
 * @param commandType Command type for handler routing (e.g., "BookFx")
 * @param commandData Command data object
 * @param responseType Expected response type
 * @return Step result (after process resumes with reply)
 */
protected <TRequest, TResponse> TResponse commandStep(
        String stepName,
        String targetDomain,
        String commandType,
        TRequest commandData,
        Class<TResponse> responseType) {

    ExecutionContext<TState> ctx = currentContext.get();
    TState state = ctx.state();

    // 1. Check if step already completed (deterministic replay)
    Optional<StepRecord> completedStep = ctx.getCompletedStep(stepName);
    if (completedStep.isPresent()) {
        log.debug("Replaying completed command step: {}", stepName);
        return deserializeResult(completedStep.get().responseJson(), responseType);
    }

    // 2. Check if reply has arrived (resuming after suspend)
    Optional<CommandStepReply> reply = state.getCommandStepReply(stepName);
    if (reply.isPresent()) {
        CommandStepReply r = reply.get();
        state.clearCommandStepReply(stepName);

        if (r.success()) {
            // Record step completion
            StepRecord completedRecord = StepRecord.completed(
                stepName, Instant.now(), Instant.now(), r.resultJson());
            state.recordStep(completedRecord);
            persistState(ctx.processId(), state);

            log.info("Command step {} completed successfully for process {}",
                stepName, ctx.processId());

            return deserializeResult(r.resultJson(), responseType);
        } else {
            // Step failed
            log.warn("Command step {} failed for process {}: {}",
                stepName, ctx.processId(), r.errorMessage());
            throw new StepExecutionException(stepName, r.errorCode(), r.errorMessage());
        }
    }

    // 3. Check if already waiting (command sent, waiting for reply)
    if (state.isWaitingForCommandStep(stepName)) {
        // Still waiting for reply - throw to suspend
        log.debug("Still waiting for command step {} reply", stepName);
        throw new WaitConditionNotMetException(stepName);
    }

    // 4. First time - send command and suspend
    UUID commandId = UUID.randomUUID();
    String replyQueue = getDomain() + "__replies";

    // Send command via CommandBus
    commandBus.send(
        targetDomain,
        commandType,
        commandId,
        objectMapper.convertValue(commandData, Map.class),
        ctx.processId(),  // correlationId = processId
        replyQueue,       // replyTo
        null              // maxAttempts (use default)
    );

    // Mark as waiting
    state.markWaitingForCommandStep(stepName, commandId);
    persistState(ctx.processId(), state);

    // Update process status
    processRepo.updateStateAtomicStep(
        getDomain(), ctx.processId(), null,
        null, ProcessStatus.WAITING_FOR_ASYNC.name(),
        null, null, null, null, stepName,
        jdbcTemplate
    );

    log.info("Command step {} sent to {}, process {} suspended",
        stepName, targetDomain, ctx.processId());

    throw new WaitConditionNotMetException(stepName);
}
```

### 6. State Extensions for Command Steps

Add to `ProcessStepState.java`:

```java
/**
 * Track pending command steps and their replies.
 */
public interface ProcessStepState {

    // Existing methods...

    /**
     * Mark that we're waiting for a command step reply.
     */
    void markWaitingForCommandStep(String stepName, UUID commandId);

    /**
     * Check if we're waiting for a command step.
     */
    boolean isWaitingForCommandStep(String stepName);

    /**
     * Store a reply received for a command step.
     */
    void storeCommandStepReply(String stepName, CommandStepReply reply);

    /**
     * Get stored reply for a command step.
     */
    Optional<CommandStepReply> getCommandStepReply(String stepName);

    /**
     * Clear stored reply after processing.
     */
    void clearCommandStepReply(String stepName);
}
```

---

## Process Flow Example

### PaymentWorkflow with Command-Based Rate-Limited Steps

```java
public class PaymentWorkflow extends ProcessStepManager<PaymentState> {

    private final CommandBus commandBus;

    @Override
    protected void execute(PaymentState state) {

        // Step 1: Validate payment (inline, no rate limit)
        var validation = step("validate", StepOptions.<PaymentState, ValidationResult>builder()
            .action(s -> validationService.validate(s.getPayment()))
            .build());

        if (!validation.isValid()) {
            throw new BusinessRuleException("INVALID_PAYMENT", validation.getErrors());
        }

        // Step 2: Book FX via Command (rate limited by FX domain worker count)
        // Sends command to "fx" domain, reply comes back to "payments__replies"
        var fxResult = commandStep(
            "bookFx",                    // stepName
            "fx",                        // targetDomain (5 workers = 5 concurrent max)
            "BookFx",                    // commandType
            new BookFxCommand(           // commandData
                getCurrentProcessId(),
                "bookFx",
                state.getAmount(),
                state.getFromCurrency(),
                state.getToCurrency()
            ),
            FxResult.class               // responseType
        );
        // Process SUSPENDS here until FX handler completes and reply is received

        state.setFxRate(fxResult.rate());
        state.setFxReference(fxResult.reference());

        // Step 3: Enrich with market data (inline - no rate limit needed)
        var enriched = step("enrichData", StepOptions.<PaymentState, EnrichedData>builder()
            .action(s -> enrichmentService.enrich(s))
            .build());

        // Step 4: Book risk via Command (rate limited by Risk domain worker count)
        var riskResult = commandStep(
            "bookRisk",                  // stepName
            "risk",                      // targetDomain (10 workers = 10 concurrent max)
            "BookRisk",                  // commandType
            new BookRiskCommand(         // commandData
                getCurrentProcessId(),
                "bookRisk",
                state.getAmount(),
                state.getCurrency(),
                state.getDebitAccount(),
                state.getCreditAccount()
            ),
            RiskResult.class             // responseType
        );
        // Process SUSPENDS here until Risk handler completes

        state.setRiskScore(riskResult.score());

        // Step 5: Execute payment (inline)
        step("executePayment", StepOptions.<PaymentState, Void>builder()
            .action(s -> {
                paymentService.execute(s);
                return null;
            })
            .build());
    }
}
```

### Flow Diagram (Using Existing Command Infrastructure)

```
Process Created → PENDING
        │
        ▼
Process Worker claims → EXECUTING
        │
        ▼
execute() called
        │
        ├── step("validate") → inline → success
        │
        ├── commandStep("bookFx", "fx", "BookFx", data)
        │       │
        │       ├── Check completed? No
        │       ├── Check reply arrived? No
        │       ├── Check already waiting? No
        │       ├── CommandBus.send("fx", "BookFx", data, replyTo="payments__replies")
        │       ├── Mark waiting for step "bookFx"
        │       ├── Set status = WAITING_FOR_ASYNC
        │       └── throw WaitConditionNotMetException
        │
        ▼
Process SUSPENDED (WAITING_FOR_ASYNC, current_wait="bookFx")
        │
        │   ... FX Domain Worker picks up command from fx__commands queue ...
        │   ... BookFxHandler calls FX API (one of max 5 concurrent workers) ...
        │   ... Handler returns Reply which goes to payments__replies queue ...
        │
        ▼
Payments Domain Worker picks up reply from payments__replies
        │
        ├── ProcessAsyncReplyHandler.handle()
        │       │
        │       ├── Deserialize CommandStepReply
        │       ├── Find process manager
        │       └── processAsyncResponse(processId, state -> state.storeCommandStepReply(...))
        │               │
        │               ├── Update state with reply
        │               ├── Status = WAITING_FOR_ASYNC (was), resume execution
        │               └── executeProcess() called
        │
        ▼
execute() called (replay from state)
        │
        ├── step("validate") → replay cached result
        │
        ├── commandStep("bookFx", "fx", "BookFx", data)
        │       │
        │       ├── Check completed? No
        │       ├── Check reply arrived? Yes, found!
        │       ├── Reply success? Yes
        │       ├── Record step completed
        │       └── Return FxResult
        │
        ├── step("enrichData") → inline → success
        │
        ├── commandStep("bookRisk", "risk", "BookRisk", data)
        │       │
        │       └── Same pattern: send to risk domain, suspend, resume...
        │
        ├── step("executePayment") → inline → success
        │
        └── Process COMPLETED
```

---

## Adaptive Behavior

The queue-based approach **naturally adapts** to downstream performance:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  FX API Healthy (200ms average response time)                               │
│                                                                             │
│  5 workers × (1000ms / 200ms) = 5 × 5 = 25 effective TPS                    │
│                                                                             │
│  Queue depth: low (workers keep up)                                         │
│  Process wait time: ~200-400ms                                              │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│  FX API Degraded (2000ms average response time)                             │
│                                                                             │
│  5 workers × (1000ms / 2000ms) = 5 × 0.5 = 2.5 effective TPS                │
│                                                                             │
│  Queue depth: growing (workers slower than incoming)                        │
│  Process wait time: 2-4 seconds                                             │
│  Natural backpressure: no more than 5 concurrent API calls                  │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│  FX API Severely Degraded (10000ms average response time)                   │
│                                                                             │
│  5 workers × (1000ms / 10000ms) = 5 × 0.1 = 0.5 effective TPS               │
│                                                                             │
│  Queue depth: high (significant backlog)                                    │
│  Process wait time: 10+ seconds                                             │
│  But still only 5 concurrent API calls - not overwhelming downstream!       │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Crash Recovery

PGMQ handles crash recovery automatically via **visibility timeout**:

1. Worker reads message → message becomes invisible for `step_timeout` seconds
2. Worker crashes before completing
3. After visibility timeout expires, message becomes visible again
4. Another worker picks it up

No special cleanup jobs or token tables needed!

---

## Monitoring and Observability

Use existing command infrastructure for monitoring - no custom tables needed.

### Queue Depth Monitoring (Backpressure Detection)

```sql
-- Check queue depths per domain to detect backpressure
SELECT
    queue_name,
    (SELECT COUNT(*) FROM pgmq.q_fx__commands) AS fx_queue_depth,
    (SELECT COUNT(*) FROM pgmq.q_risk__commands) AS risk_queue_depth;

-- Or use PGMQ's built-in metrics
SELECT * FROM pgmq.metrics('fx__commands');
SELECT * FROM pgmq.metrics('risk__commands');
```

### Command Metrics (Using Existing Audit Trail)

```sql
-- Command success/failure rates by domain and type (last hour)
SELECT
    domain,
    command_type,
    COUNT(*) FILTER (WHERE status = 'COMPLETED') AS success_count,
    COUNT(*) FILTER (WHERE status = 'FAILED') AS failure_count,
    COUNT(*) FILTER (WHERE status IN ('PENDING', 'IN_PROGRESS')) AS in_progress,
    ROUND(
        COUNT(*) FILTER (WHERE status = 'FAILED')::NUMERIC /
        NULLIF(COUNT(*), 0) * 100, 2
    ) AS error_rate_pct
FROM commandbus.command_metadata
WHERE created_at >= NOW() - INTERVAL '1 hour'
GROUP BY domain, command_type
ORDER BY domain, command_type;

-- Average processing time by command type
SELECT
    domain,
    command_type,
    COUNT(*) AS completed_count,
    ROUND(AVG(EXTRACT(EPOCH FROM (completed_at - created_at)) * 1000)::NUMERIC, 2) AS avg_latency_ms,
    MIN(EXTRACT(EPOCH FROM (completed_at - created_at)) * 1000)::INTEGER AS min_latency_ms,
    MAX(EXTRACT(EPOCH FROM (completed_at - created_at)) * 1000)::INTEGER AS max_latency_ms
FROM commandbus.command_metadata
WHERE status = 'COMPLETED'
  AND completed_at >= NOW() - INTERVAL '1 hour'
GROUP BY domain, command_type;
```

### Process Wait Time Monitoring

```sql
-- Processes waiting for command steps
SELECT
    process_type,
    current_wait AS waiting_for_step,
    COUNT(*) AS waiting_count,
    MIN(updated_at) AS oldest_waiting_since
FROM commandbus.process
WHERE status = 'WAITING_FOR_ASYNC'
  AND current_wait IS NOT NULL
GROUP BY process_type, current_wait
ORDER BY waiting_count DESC;
```

---

## Configuration Summary

### Spring Configuration (Using Existing Command Infrastructure)

```java
@Configuration
@EnableConfigurationProperties(CommandBusProperties.class)
public class CommandBusWorkerConfiguration {

    // Handlers are registered based on domain enablement
    @Bean
    @ConditionalOnProperty(name = "commandbus.domains.fx.enabled", havingValue = "true")
    public BookFxHandler bookFxHandler(FxService fxService, ObjectMapper objectMapper) {
        return new BookFxHandler(fxService, objectMapper);
    }

    @Bean
    @ConditionalOnProperty(name = "commandbus.domains.risk.enabled", havingValue = "true")
    public BookRiskHandler bookRiskHandler(RiskService riskService, ObjectMapper objectMapper) {
        return new BookRiskHandler(riskService, objectMapper);
    }

    // Reply handler enabled when reply-processing feature is on
    @Bean
    @ConditionalOnProperty(name = "commandbus.features.reply-processing", havingValue = "true")
    public ProcessAsyncReplyHandler processAsyncReplyHandler(
            Map<String, ProcessStepManager<?>> processManagers,
            ObjectMapper objectMapper) {
        return new ProcessAsyncReplyHandler(processManagers, objectMapper);
    }

    // Worker thread pool configured per domain based on concurrent-messages setting
}
```

### Deployment Configurations

**Option 1: Dedicated Rate-Limited Workers (FX/Risk only)**

```yaml
# application-ratelimited-worker.yml
# Deploy 3 replicas for HA
# Total FX concurrency: 3 replicas × 2 = 6 concurrent
# Total Risk concurrency: 3 replicas × 2 = 6 concurrent
commandbus:
  features:
    command-handlers: true
    process-execution: false     # No process execution
    reply-processing: false      # No reply handling

  domains:
    fx:
      enabled: true
      concurrent-messages: 2     # 2 concurrent per instance
    risk:
      enabled: true
      concurrent-messages: 2     # 2 concurrent per instance
    payments:
      enabled: false             # Not handling payments
```

**Option 2: Dedicated Payments Worker (Process Execution + Replies)**

```yaml
# application-payments-worker.yml
# Deploy 3 replicas for HA
commandbus:
  features:
    command-handlers: true
    process-execution: true      # Run workflows
    reply-processing: true       # Handle replies from FX/Risk

  domains:
    fx:
      enabled: false             # FX handled by dedicated workers
    risk:
      enabled: false             # Risk handled by dedicated workers
    payments:
      enabled: true
      concurrent-messages: 10    # Higher for general processing

  command-steps:
    bookFx:
      domain: fx
      command-type: BookFx
      timeout: 30s
    bookRisk:
      domain: risk
      command-type: BookRisk
      timeout: 30s
```

**Option 3: Multi-Purpose Worker (All Features)**

```yaml
# application-multipurpose-worker.yml
# Single deployment handling everything
# Use when rate limiting is less critical or for dev/test
commandbus:
  features:
    command-handlers: true
    process-execution: true
    reply-processing: true

  domains:
    fx:
      enabled: true
      concurrent-messages: 2
    risk:
      enabled: true
      concurrent-messages: 3
    payments:
      enabled: true
      concurrent-messages: 10
```

### Kubernetes Deployment Examples

```yaml
# Dedicated Rate-Limited Worker (handles FX and Risk domains)
# 3 replicas × 2 concurrent = 6 max concurrent FX calls
# 3 replicas × 2 concurrent = 6 max concurrent Risk calls
apiVersion: apps/v1
kind: Deployment
metadata:
  name: ratelimited-worker
spec:
  replicas: 3  # HA: min 2-3 replicas
  selector:
    matchLabels:
      app: ratelimited-worker
  template:
    metadata:
      labels:
        app: ratelimited-worker
    spec:
      containers:
      - name: worker
        image: payments-service:latest
        env:
        - name: SPRING_PROFILES_ACTIVE
          value: "ratelimited-worker"
        # Or use individual env vars:
        - name: COMMANDBUS_FEATURES_COMMAND_HANDLERS
          value: "true"
        - name: COMMANDBUS_FEATURES_PROCESS_EXECUTION
          value: "false"
        - name: COMMANDBUS_FEATURES_REPLY_PROCESSING
          value: "false"
        - name: COMMANDBUS_DOMAINS_FX_ENABLED
          value: "true"
        - name: COMMANDBUS_DOMAINS_FX_CONCURRENT_MESSAGES
          value: "2"
        - name: COMMANDBUS_DOMAINS_RISK_ENABLED
          value: "true"
        - name: COMMANDBUS_DOMAINS_RISK_CONCURRENT_MESSAGES
          value: "2"
        - name: COMMANDBUS_DOMAINS_PAYMENTS_ENABLED
          value: "false"
---
# Payments Domain Worker (process execution + replies)
apiVersion: apps/v1
kind: Deployment
metadata:
  name: payments-worker
spec:
  replicas: 3  # Scale based on process throughput needs
  selector:
    matchLabels:
      app: payments-worker
  template:
    metadata:
      labels:
        app: payments-worker
    spec:
      containers:
      - name: worker
        image: payments-service:latest
        env:
        - name: SPRING_PROFILES_ACTIVE
          value: "payments-worker"
        - name: COMMANDBUS_FEATURES_COMMAND_HANDLERS
          value: "true"
        - name: COMMANDBUS_FEATURES_PROCESS_EXECUTION
          value: "true"
        - name: COMMANDBUS_FEATURES_REPLY_PROCESSING
          value: "true"
        - name: COMMANDBUS_DOMAINS_FX_ENABLED
          value: "false"
        - name: COMMANDBUS_DOMAINS_RISK_ENABLED
          value: "false"
        - name: COMMANDBUS_DOMAINS_PAYMENTS_ENABLED
          value: "true"
        - name: COMMANDBUS_DOMAINS_PAYMENTS_CONCURRENT_MESSAGES
          value: "10"
```

---

## Code Changes Required

### Files to Remove (Bucket4j Rate Limiting)

The command-based approach replaces bucket4j entirely. Remove these files:

| File | Reason |
|------|--------|
| `Bucket4jRateLimiter.java` | Replaced by worker pool concurrency control |
| `RateLimitConfig.java` | No longer needed - config in application.yml |
| `RateLimitConfigRepository.java` | No database config tables |
| `JdbcRateLimitConfigRepository.java` | No database config tables |

### Database Objects to Remove

```sql
-- Remove bucket4j rate limiting tables (if they exist)
DROP TABLE IF EXISTS commandbus.rate_limit_bucket;
DROP TABLE IF EXISTS commandbus.rate_limit_config;

-- Note: Keep commandbus.bucket4j_state if bucket4j was using it for distributed state
-- (check if any other features depend on it first)
```

### Dependencies to Remove

```xml
<!-- Remove from pom.xml -->
<dependency>
    <groupId>com.bucket4j</groupId>
    <artifactId>bucket4j-core</artifactId>
</dependency>
<dependency>
    <groupId>com.bucket4j</groupId>
    <artifactId>bucket4j-postgresql</artifactId>
</dependency>
```

### Code References to Remove

Update `ProcessStepManager.java` to remove bucket4j integration:

```java
// REMOVE these fields
private final Bucket4jRateLimiter rateLimiter;  // Remove

// REMOVE these constructor parameters
public ProcessStepManager(..., Bucket4jRateLimiter rateLimiter) {
    this.rateLimiter = rateLimiter;  // Remove
}

// REMOVE rate limiting in step() method
if (options.hasRateLimiting() && rateLimiter != null) {
    if (!rateLimiter.acquire(options.rateLimitKey(), options.rateLimitTimeout())) {
        throw new RateLimitExceededException(...);
    }
}
```

Update `StepOptions.java` to remove rate limiting fields:

```java
// REMOVE these fields
private String rateLimitKey;
private Duration rateLimitTimeout;

// REMOVE these methods
public boolean hasRateLimiting() { ... }
public String rateLimitKey() { ... }
public Duration rateLimitTimeout() { ... }
```

### Files to Create

| File | Type | Description |
|------|------|-------------|
| `CommandStepReply.java` | NEW | Generic reply wrapper for command steps |
| `ProcessAsyncReplyHandler.java` | NEW | Reply handler that calls processAsyncResponse() |

### Files to Modify

| File | Change |
|------|--------|
| `CommandBusProperties.java` | Add `features`, `domains`, and `commandSteps` configuration |
| `ProcessStepManager.java` | Add `commandStep()` method, remove bucket4j references |
| `ProcessStepState.java` | Add command step tracking methods |
| `StepOptions.java` | Remove rate limiting fields (`rateLimitKey`, `rateLimitTimeout`) |
| `Worker.java` (or impl) | Respect `concurrent-messages` per domain setting |
| `pom.xml` | Remove bucket4j dependencies |

### Configuration Files to Add

| File | Purpose |
|------|---------|
| `application-ratelimited-worker.yml` | Dedicated rate-limited worker profile |
| `application-payments-worker.yml` | Payments domain worker profile |
| `application-multipurpose-worker.yml` | All-in-one worker profile (dev/test) |

**Note:** The command-based approach is simpler - no custom workers, no bucket4j, no database config tables. Rate limiting is controlled by `replicas × concurrent-messages` per domain!

---

## Summary

| Aspect | Solution |
|--------|----------|
| **Concurrency control** | `replicas × concurrent-messages` per domain |
| **Threading model** | Synchronous I/O - virtual threads block on API calls |
| **Multiple rate-limited steps** | Each step = separate domain with configurable concurrency |
| **Thread blocking** | None - processes suspend via `WaitConditionNotMetException` |
| **Crash recovery** | PGMQ visibility timeout + existing Worker retry logic |
| **Latency adaptation** | Natural - workers block on slow downstream API |
| **Scaling** | Adjust replicas and/or `concurrent-messages` per domain |
| **Retry logic** | Built-in via existing command retry policies |
| **TSQ integration** | Built-in via existing Worker infrastructure |
| **Auditing** | Built-in via existing command audit trail |
| **Observability** | Existing command metrics + queue depth |
| **Configuration** | Application YAML files (version controlled) |
| **Worker flexibility** | Features (global) + Domains (per-domain concurrency) |
| **Infrastructure** | **100% reuse of existing CommandBus/Worker/Handler** |

### Configuration Model

| Setting | Scope | Purpose |
|---------|-------|---------|
| `features.command-handlers` | Global | Enable/disable command processing |
| `features.process-execution` | Global | Enable/disable ProcessStepManager |
| `features.reply-processing` | Global | Enable/disable reply handling |
| `domains.{name}.enabled` | Per-domain | Enable/disable specific domain |
| `domains.{name}.concurrent-messages` | Per-domain | Max concurrent API calls per instance |

### Concurrency Formula

```
Total concurrent API calls = replicas × concurrent-messages
```

Example configurations for FX API (max 5 TPS):
| Replicas | concurrent-messages | Total Concurrent | Notes |
|----------|---------------------|------------------|-------|
| 3 | 1 | 3 | Safe, under-utilized |
| 3 | 2 | 6 | Slightly over, rely on API backpressure |
| 2 | 2 | 4 | Good fit with HA |

### What Gets Removed

| Item | Reason |
|------|--------|
| **Bucket4j dependency** | Worker pool replaces token bucket rate limiting |
| **bucket4j-postgresql** | No distributed token state needed |
| `Bucket4jRateLimiter.java` | Replaced by command step pattern |
| `RateLimitConfig.java` | Config moved to application.yml |
| `rate_limit_config` table | No database config needed |
| `rate_limit_bucket` table | No token bucket state needed |
| `StepOptions.rateLimitKey/Timeout` | Use `commandStep()` instead |

### Key Benefits

1. **No new infrastructure** - Reuses CommandBus, Worker, Handler, Reply
2. **No external dependencies** - Remove bucket4j entirely
3. **No database config tables** - YAML config is version-controlled
4. **Flexible deployment** - Dedicated or multi-purpose workers via config
5. **Fine-grained concurrency** - Per-domain `concurrent-messages` setting
6. **Built-in retry** - Existing retry policies apply automatically
7. **Built-in TSQ** - Failed commands go to troubleshooting queue
8. **Built-in auditing** - Full audit trail for every command
9. **Familiar pattern** - Same handler pattern already used elsewhere
10. **Unified monitoring** - Same metrics and dashboards

### Migration Path

1. Add `CommandBusProperties` with features and domains configuration
2. Add `commandStep()` method to `ProcessStepManager`
3. Create domain-specific handlers (e.g., `BookFxHandler`)
4. Create `ProcessAsyncReplyHandler` for reply processing
5. Update workflows to use `commandStep()` instead of inline steps with rate limiting
6. Remove bucket4j dependency and related code
7. Configure deployment profiles (dedicated vs multi-purpose workers)
8. Deploy with appropriate replica counts and `concurrent-messages` per domain

This design provides robust, scalable rate limiting that naturally adapts to downstream performance while **simplifying the codebase** by removing bucket4j and database-managed configuration.
