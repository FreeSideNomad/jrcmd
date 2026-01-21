# Rate Limiting Implementation Plan

## Overview

This document outlines the detailed implementation plan for command-based rate limiting using existing Command/Handler/Worker infrastructure. The approach replaces bucket4j with worker pool concurrency control.

**Key principle:** `Total concurrent API calls = replicas × concurrent-messages-per-domain`

---

## Phase 1: Configuration Infrastructure

### 1.1 Extend CommandBusProperties

**File:** `src/main/java/com/ivamare/commandbus/CommandBusProperties.java`

**Changes:**
- Add `FeaturesProperties` inner class with feature toggles
- Add `DomainsProperties` with per-domain configuration
- Add `CommandStepProperties` for command step mappings

```yaml
commandbus:
  features:
    command-handlers: true
    process-execution: true
    reply-processing: true

  domains:
    fx:
      enabled: true
      concurrent-messages: 2
    payments:
      enabled: true
      concurrent-messages: 5

  command-steps:
    bookFx:
      domain: fx
      command-type: BookFx
      timeout: 30s
    submitPayment:
      domain: payments
      command-type: SubmitPayment
      timeout: 30s
```

**Acceptance Criteria:**
- [x] `FeaturesProperties` class with `commandHandlers`, `processExecution`, `replyProcessing` booleans
- [x] `DomainConfig` class with `enabled` and `concurrentMessages` fields
- [x] `CommandStepConfig` class with `domain`, `commandType`, and `timeout` fields
- [x] Configuration binds correctly from YAML
- [ ] Unit tests for configuration binding

### 1.2 Modify DefaultWorker for Per-Domain Concurrency

**File:** `src/main/java/com/ivamare/commandbus/worker/impl/DefaultWorker.java`

**Changes:**
- Worker reads concurrency from domain-specific configuration
- Semaphore limit set based on `concurrent-messages` for the domain

**Acceptance Criteria:**
- [ ] Worker respects `concurrent-messages` setting for its domain
- [ ] Semaphore properly limits concurrent message processing
- [ ] Unit test verifies concurrency limit is enforced

---

## Phase 2: Command Step Infrastructure

### 2.1 Create CommandStepRequest/Response Records

**Files:**
- `src/main/java/com/ivamare/commandbus/process/step/CommandStepRequest.java`
- `src/main/java/com/ivamare/commandbus/process/step/CommandStepResponse.java`

```java
/**
 * Request wrapper for command step execution.
 * Contains processId and stepName for correlation.
 */
public record CommandStepRequest<T>(
    UUID processId,
    String stepName,
    T data,
    ExpectedOutcome expectedOutcome  // For testing: SUCCESS, TIMEOUT, TRANSIENT_FAILURE, etc.
) {}

/**
 * Response from command step execution.
 */
public record CommandStepResponse<T>(
    UUID processId,
    String stepName,
    boolean success,
    T result,              // Strongly typed result if success
    String errorCode,      // Error code if failure
    String errorMessage,   // Error message if failure
    ErrorType errorType    // TRANSIENT, PERMANENT, BUSINESS, TIMEOUT
) {
    public enum ErrorType { TRANSIENT, PERMANENT, BUSINESS, TIMEOUT }

    public static <T> CommandStepResponse<T> success(UUID processId, String stepName, T result) {...}
    public static <T> CommandStepResponse<T> failure(UUID processId, String stepName,
                                                      ErrorType type, String code, String message) {...}
}
```

**Acceptance Criteria:**
- [x] Records created with proper fields
- [x] Factory methods for success/failure responses
- [x] JSON serialization/deserialization works correctly

### 2.2 Add commandStep() Method to ProcessStepManager

**File:** `src/main/java/com/ivamare/commandbus/process/step/ProcessStepManager.java`

**New method:**

```java
/**
 * Execute a step via Command/Handler pattern with rate limiting.
 *
 * <p>Flow:
 * 1. Check if step already completed (replay from state)
 * 2. Check if response arrived (resume after suspend)
 * 3. If neither, send command and suspend process
 *
 * @param stepName Step name for tracking
 * @param request Request data (with expectedOutcome for testing)
 * @param responseType Expected response type
 * @return Strongly typed response after process resumes
 */
protected <TReq, TResp> TResp commandStep(
    String stepName,
    TReq request,
    Class<TResp> responseType
) throws StepBusinessRuleException;
```

**Acceptance Criteria:**
- [x] Method checks completed steps for replay
- [x] Method checks for pending response in state
- [x] Method sends command via CommandBus.send() with replyTo
- [x] Method throws `WaitConditionNotMetException` to suspend process
- [x] Process resumes when response arrives
- [x] Success response returns typed result
- [x] Error responses handled appropriately (transient retry, permanent TSQ, business exception)

### 2.3 Extend ProcessStepState Interface

**File:** `src/main/java/com/ivamare/commandbus/process/step/ProcessStepState.java`

**New methods:**

```java
/**
 * Store pending command step request.
 */
void storePendingCommandStep(String stepName, UUID commandId);

/**
 * Check if waiting for command step response.
 */
boolean isWaitingForCommandStep(String stepName);

/**
 * Get pending command ID for step.
 */
Optional<UUID> getPendingCommandId(String stepName);

/**
 * Store received command step response.
 */
void storeCommandStepResponse(String stepName, CommandStepResponse<?> response);

/**
 * Get stored response for command step.
 */
Optional<CommandStepResponse<?>> getCommandStepResponse(String stepName);

/**
 * Clear command step state after processing.
 */
void clearCommandStepState(String stepName);
```

**Acceptance Criteria:**
- [x] Interface extended with new methods
- [x] Default implementations provided
- [ ] PaymentStepState updated to implement new methods (deferred - ProcessStepState has implementations)

### 2.4 Create Command Step Response Handler

**File:** `src/main/java/com/ivamare/commandbus/process/step/CommandStepResponseHandler.java`

**Purpose:** Generic handler that receives command step responses and routes them to the appropriate ProcessStepManager.

```java
@Handler(domain = "${commandbus.domain}", commandType = "CommandStepResponse")
public class CommandStepResponseHandler {

    private final Map<String, ProcessStepManager<?>> processManagers;

    public Object handle(Command command, HandlerContext context) {
        // 1. Deserialize CommandStepResponse from command.data()
        // 2. Find process manager for the process
        // 3. Call processAsyncResponse with state updater
        // 4. State updater stores response and clears pending
    }
}
```

**Acceptance Criteria:**
- [x] Handler registered for CommandStepResponse commands
- [x] Correctly deserializes response
- [x] Routes to appropriate ProcessStepManager
- [x] Calls processAsyncResponse() with correct state update
- [x] Error handling for unknown processes

---

## Phase 3: FX and Payment Command Handlers

### 3.1 Create BookFxCommand and BookFxHandler

**Files:**
- `src/test/java/com/ivamare/commandbus/e2e/payment/commands/BookFxCommand.java`
- `src/test/java/com/ivamare/commandbus/e2e/payment/handlers/BookFxHandler.java`

**BookFxCommand:**
```java
public record BookFxCommand(
    UUID processId,
    String stepName,
    BigDecimal amount,
    String fromCurrency,
    String toCurrency,
    ExpectedOutcome expectedOutcome  // For testing probabilistic behavior
) {}
```

**BookFxHandler:**
```java
@Handler(domain = "fx", commandType = "BookFx")
public class BookFxHandler {

    public Object handle(Command command, HandlerContext context) {
        BookFxCommand fxCommand = deserialize(command.data());

        // Simulate behavior based on expectedOutcome
        switch (fxCommand.expectedOutcome()) {
            case SUCCESS -> return simulateSuccess(fxCommand);
            case TIMEOUT -> throw new TransientCommandException("TIMEOUT", "FX API timeout");
            case TRANSIENT_FAILURE -> throw new TransientCommandException("FX_UNAVAILABLE", "FX service unavailable");
            case PERMANENT_FAILURE -> throw new PermanentCommandException("FX_REJECTED", "FX booking rejected");
            case BUSINESS_ERROR -> throw new BusinessRuleException("INSUFFICIENT_BALANCE", "Insufficient balance");
        }
    }

    private FxResult simulateSuccess(BookFxCommand cmd) {
        // Return FxResult with contract ID, rate, etc.
    }
}
```

**Acceptance Criteria:**
- [x] Command record created with all fields including expectedOutcome
- [x] Handler simulates different outcomes based on expectedOutcome
- [x] SUCCESS returns FxResult with contract details
- [x] TIMEOUT throws TransientCommandException
- [x] TRANSIENT_FAILURE throws TransientCommandException (will retry)
- [x] PERMANENT_FAILURE throws PermanentCommandException (goes to TSQ)
- [x] BUSINESS_ERROR throws BusinessRuleException (fails immediately)
- [x] Response sent to replyTo queue

### 3.2 Create SubmitPaymentCommand and SubmitPaymentHandler

**Files:**
- `src/test/java/com/ivamare/commandbus/e2e/payment/commands/SubmitPaymentCommand.java`
- `src/test/java/com/ivamare/commandbus/e2e/payment/handlers/SubmitPaymentHandler.java`

Similar structure to BookFx but for payment submission.

**Acceptance Criteria:**
- [x] Command record with payment details and expectedOutcome
- [x] Handler simulates network submission
- [x] Returns submission reference on success
- [x] Error behaviors match BookFxHandler pattern

### 3.3 Create FxResult and SubmitPaymentResult Records

**Files:**
- `src/test/java/com/ivamare/commandbus/e2e/payment/commands/FxResult.java`
- `src/test/java/com/ivamare/commandbus/e2e/payment/commands/SubmitPaymentResult.java`

```java
public record FxResult(
    Long contractId,
    BigDecimal rate,
    BigDecimal creditAmount,
    String reference
) {}

public record SubmitPaymentResult(
    String submissionReference,
    UUID networkCommandId,
    Instant submittedAt
) {}
```

**Acceptance Criteria:**
- [x] Result records contain all necessary fields
- [x] JSON serialization works correctly

---

## Phase 4: Update PaymentStepProcess

### 4.1 Modify PaymentStepProcess to Use commandStep()

**File:** `src/test/java/com/ivamare/commandbus/e2e/payment/step/PaymentStepProcess.java`

**Changes:**
1. Replace inline `executeFxBooking()` with `commandStep("bookFx", ...)`
2. Replace inline `executeSubmission()` with `commandStep("submitPayment", ...)`
3. Map responses to state updates

```java
@Override
protected void execute(PaymentStepState state) {
    // Step 1: Update status to PROCESSING
    step("updateStatusProcessing", ...);

    // Step 2: Book risk (inline - no rate limiting needed)
    step("bookRisk", ...);

    // Wait for risk approval if PENDING
    if (state.isRiskPending()) {
        wait("awaitRiskApproval", ...);
    }

    // Step 3: Book FX via command (rate limited by fx domain workers)
    if (shouldBookFx(state)) {
        FxResult fxResult = commandStep(
            "bookFx",
            new BookFxCommand(
                getCurrentProcessId(),
                "bookFx",
                state.getDebitAmount(),
                state.getDebitCurrency(),
                state.getCreditCurrency(),
                state.getBehavior().bookFx().expectedOutcome()
            ),
            FxResult.class
        );
        // Map response to state
        state.setFxContractId(fxResult.contractId());
        state.setFxRate(fxResult.rate());
        state.setCreditAmount(fxResult.creditAmount());
    }

    // Step 4: Submit payment via command (rate limited by payments domain workers)
    SubmitPaymentResult submitResult = commandStep(
        "submitPayment",
        new SubmitPaymentCommand(
            getCurrentProcessId(),
            "submitPayment",
            state.getPaymentId(),
            state.getSubmissionData(),
            state.getBehavior().submitPayment().expectedOutcome()
        ),
        SubmitPaymentResult.class
    );
    // Map response to state
    state.setSubmissionReference(submitResult.submissionReference());
    state.setSubmissionCommandId(submitResult.networkCommandId());

    // Trigger network simulator for L1-L4 confirmations
    if (networkSimulator != null) {
        networkSimulator.simulatePaymentConfirmations(...);
    }

    // Step 5: Wait for L4 confirmation
    wait("awaitL4", () -> state.getL4CompletedAt() != null, ...);
}
```

### 4.2 Update PaymentStepState for Command Step Tracking

**File:** `src/test/java/com/ivamare/commandbus/e2e/payment/step/PaymentStepState.java`

**Changes:**
- Add maps for pending command steps and responses
- Implement new interface methods

**Acceptance Criteria:**
- [ ] State tracks pending command steps with commandId
- [ ] State stores received responses
- [ ] State clears after processing

### 4.3 Update PaymentStepBehavior for Command Steps

**File:** `src/test/java/com/ivamare/commandbus/e2e/payment/PaymentStepBehavior.java`

**Changes:**
- Add `bookFx()` and `submitPayment()` behavior configurations
- Each behavior includes `expectedOutcome` field

```java
public record PaymentStepBehavior(
    RiskBehavior bookRisk,
    FxBehavior bookFx,
    SubmitPaymentBehavior submitPayment,
    NetworkBehavior network
) {
    public record FxBehavior(ExpectedOutcome expectedOutcome) {
        public static FxBehavior defaults() { return new FxBehavior(ExpectedOutcome.SUCCESS); }
    }

    public record SubmitPaymentBehavior(ExpectedOutcome expectedOutcome) {
        public static SubmitPaymentBehavior defaults() { return new SubmitPaymentBehavior(ExpectedOutcome.SUCCESS); }
    }
}

public enum ExpectedOutcome {
    SUCCESS,
    TIMEOUT,
    TRANSIENT_FAILURE,
    PERMANENT_FAILURE,
    BUSINESS_ERROR
}
```

**Acceptance Criteria:**
- [ ] Behavior records created for each command step
- [ ] ExpectedOutcome enum defined
- [ ] Default behaviors return SUCCESS

---

## Phase 5: Remove Bucket4j

### 5.1 Remove Bucket4j Dependencies

**File:** `pom.xml`

**Changes:**
- Remove `bucket4j-core` dependency
- Remove `bucket4j-postgresql` dependency

### 5.2 Remove Bucket4j Classes

**Files to delete:**
- `src/main/java/com/ivamare/commandbus/process/ratelimit/Bucket4jRateLimiter.java`
- `src/main/java/com/ivamare/commandbus/process/ratelimit/RateLimitConfig.java`
- `src/main/java/com/ivamare/commandbus/process/ratelimit/RateLimitConfigRepository.java`
- `src/main/java/com/ivamare/commandbus/process/ratelimit/JdbcRateLimitConfigRepository.java` (if exists)

### 5.3 Remove Rate Limiting from StepOptions

**File:** `src/main/java/com/ivamare/commandbus/process/step/StepOptions.java`

**Changes:**
- Remove `rateLimitKey` field
- Remove `rateLimitTimeout` field
- Remove `hasRateLimiting()` method
- Remove builder methods for rate limiting

### 5.4 Remove Rate Limit Database Tables

**Migration file:** `src/main/resources/db/migration/V00X__remove_rate_limit_tables.sql`

```sql
-- Remove bucket4j rate limiting tables
DROP TABLE IF EXISTS commandbus.rate_limit_bucket;
DROP TABLE IF EXISTS commandbus.rate_limit_config;
```

**Acceptance Criteria:**
- [x] All bucket4j dependencies removed from pom.xml
- [x] All bucket4j-related Java files deleted
- [ ] StepOptions no longer has rate limiting fields (kept for backward compatibility, unused)
- [ ] Database migration removes rate limit tables (not needed - no tables existed)
- [x] Build succeeds without bucket4j
- [x] All tests pass

---

## Phase 6: E2E Tests

### 6.1 Test: Successful FX and Payment Flow

**File:** `src/test/java/com/ivamare/commandbus/e2e/payment/step/PaymentStepProcessCommandStepTest.java`

**Test scenarios:**
1. Happy path: FX success → Submit success → L4 success
2. FX rate limiting: Multiple concurrent processes limited to 2 concurrent FX calls
3. Payment rate limiting: Multiple concurrent processes limited to 5 concurrent submissions

**Acceptance Criteria:**
- [ ] Test creates multiple concurrent payment processes
- [ ] Verifies FX concurrency limited to configured value
- [ ] Verifies payment concurrency limited to configured value
- [ ] All processes complete successfully

### 6.2 Test: FX Timeout and Retry

**Test scenarios:**
1. FX timeout on first attempt, success on retry
2. FX timeout exhausts retries → moves to TSQ

**Acceptance Criteria:**
- [ ] Test verifies retry on transient failure
- [ ] Test verifies TSQ on exhausted retries
- [ ] Audit trail shows retry attempts

### 6.3 Test: FX Permanent Failure → TSQ

**Test scenarios:**
1. FX permanent failure → immediately to TSQ
2. TSQ retry succeeds → process completes

**Acceptance Criteria:**
- [ ] Test verifies immediate TSQ on permanent failure
- [ ] Test verifies TSQ retry functionality
- [ ] Process can complete after TSQ retry

### 6.4 Test: FX Business Error → Process Fails

**Test scenarios:**
1. FX business error → triggers compensation
2. Payment status updated to CANCELLED

**Acceptance Criteria:**
- [ ] Test verifies business exception handling
- [ ] Test verifies compensation execution
- [ ] Test verifies final payment status

### 6.5 Test: Submit Payment Failures

Similar test scenarios for submitPayment step.

### 6.6 Test: Mixed Behaviors

**Test scenarios:**
1. FX success, submitPayment timeout → retry → success
2. FX timeout → retry → success, submitPayment business error → compensation

**Acceptance Criteria:**
- [ ] Complex multi-step failure scenarios work correctly
- [ ] Compensation executes for completed steps only
- [ ] State correctly tracked through retries

---

## Phase 7: Documentation

### 7.1 Update Design Document

**File:** `docs/advanced-rate-limiting-design.md`

**Changes:**
- Mark implementation status for each section
- Add any implementation notes or deviations

### 7.2 Update CLAUDE.md

**File:** `CLAUDE.md`

**Changes:**
- Document command step pattern
- Document configuration options
- Document testing approach

---

## Implementation Order

1. **Phase 1.1** - Configuration properties (foundation)
2. **Phase 2.1, 2.2** - Command step records (data structures)
3. **Phase 2.3** - ProcessStepState extensions (state management)
4. **Phase 2.4** - Response handler (reply processing)
5. **Phase 3.1, 3.2, 3.3** - FX and Payment handlers (command execution)
6. **Phase 4.1, 4.2, 4.3** - Update PaymentStepProcess (integration)
7. **Phase 6.1** - Basic E2E test (verify happy path)
8. **Phase 5** - Remove bucket4j (cleanup)
9. **Phase 6.2-6.6** - Failure scenario tests
10. **Phase 1.2** - Worker concurrency (final verification)
11. **Phase 7** - Documentation

---

## Testing Strategy

### Unit Tests
- Configuration binding
- CommandStepRequest/Response serialization
- ProcessStepState methods
- Handler logic for each outcome type

### Integration Tests
- CommandBus send with replyTo
- Worker reply processing
- processAsyncResponse state updates

### E2E Tests
- Full payment flow with command steps
- Concurrency verification
- Failure and retry scenarios
- TSQ operations

### Manual Verification
- Check queue depths during test runs
- Verify audit trail entries
- Monitor worker metrics

---

## Rollback Plan

If issues arise:
1. Revert bucket4j removal changes
2. Keep commandStep() but fallback to inline execution
3. Feature flag for new vs old behavior

---

## Success Metrics

- [ ] All existing tests pass
- [ ] New E2E tests pass
- [ ] Concurrency correctly limited per domain
- [ ] Retry behavior works correctly
- [ ] TSQ operations work correctly
- [ ] No bucket4j dependencies in final build
- [ ] Coverage maintains 80% threshold
