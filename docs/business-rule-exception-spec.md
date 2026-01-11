# BusinessRuleException Implementation Specification

## Overview

Port Python F016 (BusinessRuleException) to Java commandbus-spring. This introduces a new exception type that enables immediate command failure without operator intervention, triggering auto-compensation for processes.

**Reference**: `/Users/igormusic/code/rcmd/docs/features/F016-business-rule-exception.md`

---

## Exception Hierarchy (Updated)

```
CommandBusException (base)
├── TransientCommandException
│   └── Behavior: Retry → (exhausted) → TSQ → Operator
│
├── PermanentCommandException
│   └── Behavior: Immediate → TSQ → Operator
│
└── BusinessRuleException (NEW)
    └── Behavior: Immediate → FAILED → Auto-compensate (for processes)
```

| Exception | Retry | TSQ | Operator | Auto-Compensate | Command Status |
|-----------|-------|-----|----------|-----------------|----------------|
| TransientCommandException | Yes | Yes (exhausted) | Yes | No | IN_TROUBLESHOOTING_QUEUE |
| PermanentCommandException | No | Yes | Yes | No | IN_TROUBLESHOOTING_QUEUE |
| BusinessRuleException | No | No | No | Yes | FAILED |

---

## Part 1: Core Library Changes

### 1.1 BusinessRuleException Class

**File**: `src/main/java/com/commandbus/exception/BusinessRuleException.java`

```java
package com.commandbus.exception;

import java.util.Map;

/**
 * Raised for business rule violations that should fail immediately without TSQ.
 *
 * <p>When a handler throws this exception:
 * <ul>
 *   <li>Command status → FAILED (bypasses TSQ)</li>
 *   <li>Reply sent with outcome=FAILED, errorType=BUSINESS_RULE</li>
 *   <li>Process auto-compensates (no operator intervention)</li>
 * </ul>
 *
 * <p>Use cases:
 * <ul>
 *   <li>Account closed before statement generation</li>
 *   <li>Invalid date range in report request</li>
 *   <li>Missing required data that won't appear later</li>
 *   <li>Business validation rules that cannot be overridden</li>
 * </ul>
 */
public class BusinessRuleException extends CommandBusException {

    private final String code;
    private final String errorMessage;
    private final Map<String, Object> details;

    public BusinessRuleException(String code, String message) {
        this(code, message, Map.of());
    }

    public BusinessRuleException(String code, String message, Map<String, Object> details) {
        super("[" + code + "] " + message);
        this.code = code;
        this.errorMessage = message;
        this.details = details != null ? Map.copyOf(details) : Map.of();
    }

    public String getCode() { return code; }
    public String getErrorMessage() { return errorMessage; }
    public Map<String, Object> getDetails() { return details; }
}
```

### 1.2 Reply Model Enhancement

**File**: `src/main/java/com/commandbus/model/Reply.java`

Add `errorType` field to distinguish failure types:

```java
public record Reply(
    UUID commandId,
    UUID correlationId,
    ReplyOutcome outcome,
    Map<String, Object> data,
    String errorCode,
    String errorMessage,
    String errorType      // NEW: "PERMANENT", "TRANSIENT", "BUSINESS_RULE"
) {
    // Add factory method
    public static Reply businessRuleFailed(UUID commandId, UUID correlationId,
                                           String errorCode, String errorMessage) {
        return new Reply(commandId, correlationId, ReplyOutcome.FAILED,
                        null, errorCode, errorMessage, "BUSINESS_RULE");
    }
}
```

### 1.3 Worker Exception Handling

**File**: `src/main/java/com/commandbus/worker/impl/DefaultWorker.java`

Add catch block for BusinessRuleException in `processMessageInternal()`:

```java
try {
    Object result = handlerRegistry.dispatch(command, context);
    complete(metadata, pgmqMessage.msgId(), result);

} catch (BusinessRuleException e) {
    handleBusinessRuleError(commandId, pgmqMessage.msgId(), e);  // NEW
} catch (TransientCommandException e) {
    handleTransientError(commandId, pgmqMessage.msgId(), e);
} catch (PermanentCommandException e) {
    handlePermanentError(commandId, pgmqMessage.msgId(), e);
} catch (Exception e) {
    handleTransientError(commandId, pgmqMessage.msgId(),
        new TransientCommandException("INTERNAL_ERROR", e.getMessage()));
}
```

Add new handler method:

```java
private void handleBusinessRuleError(UUID commandId, Long msgId, BusinessRuleException e) {
    log.debug("Business rule error for command {}: {}", commandId, e.getMessage());

    CommandMetadata metadata = commandRepository.get(domain, commandId).orElse(null);
    if (metadata == null) return;

    // Archive message (remove from queue)
    pgmqClient.archive(queueName, msgId);

    // Update command status to FAILED (NOT IN_TROUBLESHOOTING_QUEUE)
    commandRepository.spFinishCommand(
        domain, commandId,
        CommandStatus.FAILED,           // Key difference from PermanentCommandException
        "BUSINESS_RULE_FAILED",
        "BUSINESS_RULE", e.getCode(), e.getErrorMessage(),
        null, metadata.batchId()
    );

    // Send reply with errorType=BUSINESS_RULE
    sendBusinessRuleReply(metadata, e);

    log.warn("Command {} failed due to business rule: [{}] {}",
        commandId, e.getCode(), e.getErrorMessage());
}

private void sendBusinessRuleReply(CommandMetadata metadata, BusinessRuleException e) {
    if (metadata.replyTo() == null || metadata.replyTo().isBlank()) return;

    Map<String, Object> reply = Map.of(
        "command_id", metadata.commandId().toString(),
        "correlation_id", metadata.correlationId() != null
            ? metadata.correlationId().toString() : "",
        "outcome", "FAILED",
        "error_type", "BUSINESS_RULE",    // Key for process manager
        "error_code", e.getCode(),
        "error_message", e.getErrorMessage()
    );

    pgmqClient.send(metadata.replyTo(), reply);
}
```

### 1.4 Process Manager Auto-Compensation

**File**: `src/main/java/com/commandbus/process/BaseProcessManager.java`

Modify `handleReplyInternal()` to check error_type:

```java
case FAILED -> {
    String errorType = getErrorType(reply);  // Extract from reply data

    if ("BUSINESS_RULE".equals(errorType)) {
        // Auto-compensate for business rule failures
        log.info("Process {} command failed due to business rule, auto-compensating",
            process.processId());
        runCompensations(typed, jdbc);
    } else {
        // Wait for TSQ intervention (existing behavior)
        handleFailure(typed, reply, jdbc);
    }
}
```

Add helper method:

```java
private String getErrorType(Reply reply) {
    // Check if errorType field exists
    return reply.errorType() != null ? reply.errorType() : "PERMANENT";
}
```

### 1.5 Audit Event Type

**File**: `src/main/java/com/commandbus/model/AuditEventType.java`

Add new event type:

```java
public enum AuditEventType {
    // ... existing types
    BUSINESS_RULE_FAILED   // NEW: Command failed due to business rule
}
```

---

## Part 2: E2E Test Application Changes

### 2.1 Test Handler for BusinessRuleException

**File**: `src/test/java/com/commandbus/e2e/handlers/TestHandlers.java`

Add new handler:

```java
@PostConstruct
public void registerHandlers() {
    // ... existing handlers
    handlerRegistry.register(domain, "BusinessRuleFailCommand", this::handleBusinessRuleFail);
}

/**
 * Handler that fails with BusinessRuleException.
 */
public Map<String, Object> handleBusinessRuleFail(Command command, HandlerContext context) {
    log.info("Processing BusinessRuleFailCommand: {}", command.commandId());
    throw new BusinessRuleException("ACCOUNT_CLOSED",
        "Cannot process - account is closed");
}
```

### 2.2 Probabilistic Handler Enhancement

**File**: `src/test/java/com/commandbus/e2e/handlers/ProbabilisticHandler.java` (new or extend existing)

Add `failBusinessRulePct` to behavior configuration:

```java
public record CommandBehavior(
    double failPermanentPct,
    double failTransientPct,
    double failBusinessRulePct,  // NEW
    double timeoutPct,
    int minDurationMs,
    int maxDurationMs
) {}

public Map<String, Object> handle(Command command, HandlerContext context) {
    double roll = random.nextDouble() * 100;
    double cumulative = 0;

    // Evaluation order: permanent → transient → business_rule → timeout → success
    cumulative += behavior.failPermanentPct();
    if (roll < cumulative) {
        throw new PermanentCommandException("PERM_ERROR", "Simulated permanent error");
    }

    cumulative += behavior.failTransientPct();
    if (roll < cumulative) {
        throw new TransientCommandException("TRANS_ERROR", "Simulated transient error");
    }

    cumulative += behavior.failBusinessRulePct();
    if (roll < cumulative) {
        throw new BusinessRuleException("BIZ_RULE", "Simulated business rule violation");
    }

    cumulative += behavior.timeoutPct();
    if (roll < cumulative) {
        simulateTimeout();
    }

    return Map.of("status", "success");
}
```

### 2.3 E2E UI Updates

**Files to modify**:
- `src/test/resources/templates/e2e/pages/batch_new.html`
- `src/test/resources/templates/e2e/pages/send_command.html`

Add `failBusinessRulePct` input field alongside existing probability fields:

```html
<div class="col-md-3">
    <label class="form-label">Business Rule Fail %</label>
    <input type="number" name="failBusinessRulePct" class="form-control"
           min="0" max="100" step="0.1" value="0">
</div>
```

Update expected outcome preview to include business rule failures.

---

## Part 3: Files to Modify/Create

### New Files
| File | Description |
|------|-------------|
| `src/main/java/com/commandbus/exception/BusinessRuleException.java` | New exception class |
| `src/test/java/com/commandbus/exception/BusinessRuleExceptionTest.java` | Unit tests |

### Modified Files (Core Library)
| File | Changes |
|------|---------|
| `src/main/java/com/commandbus/model/Reply.java` | Add errorType field |
| `src/main/java/com/commandbus/model/AuditEventType.java` | Add BUSINESS_RULE_FAILED |
| `src/main/java/com/commandbus/worker/impl/DefaultWorker.java` | Catch BusinessRuleException, new handler method |
| `src/main/java/com/commandbus/process/BaseProcessManager.java` | Auto-compensate on BUSINESS_RULE errorType |

### Modified Files (E2E)
| File | Changes |
|------|---------|
| `src/test/java/com/commandbus/e2e/handlers/TestHandlers.java` | Add BusinessRuleFailCommand handler |
| `src/test/resources/templates/e2e/pages/batch_new.html` | Add failBusinessRulePct field |
| `src/test/resources/templates/e2e/pages/send_command.html` | Add failBusinessRulePct field |

---

## Part 4: Implementation Order

1. **BusinessRuleException class** - Create exception with code, message, details
2. **Reply model update** - Add errorType field, factory method
3. **Worker handling** - Catch exception, set FAILED status, send reply with errorType
4. **Audit event type** - Add BUSINESS_RULE_FAILED
5. **Process Manager** - Check errorType, auto-compensate for BUSINESS_RULE
6. **Unit tests** - Exception, worker handling, process compensation
7. **E2E handlers** - BusinessRuleFailCommand
8. **E2E UI** - failBusinessRulePct in batch creation forms

---

## Part 5: Verification

### Unit Tests
- [ ] BusinessRuleException creation with code, message, details
- [ ] Worker catches BusinessRuleException → status FAILED
- [ ] Worker sends reply with errorType=BUSINESS_RULE
- [ ] ProcessManager auto-compensates on BUSINESS_RULE errorType
- [ ] ProcessManager waits for TSQ on PERMANENT errorType (existing behavior)

### Integration Tests
- [ ] Full flow: send command → BusinessRuleException → FAILED status → compensation
- [ ] Verify command NOT in TSQ
- [ ] Verify process ends in CANCELED status after compensation

### E2E Tests
- [ ] Create batch with failBusinessRulePct
- [ ] Verify correct distribution of failures
- [ ] Verify UI shows business rule failures distinctly

### Manual Verification
```bash
# Start E2E app
mvn spring-boot:test-run -Dspring-boot.run.profiles=e2e,ui

# Start worker
mvn spring-boot:test-run -Dspring-boot.run.profiles=e2e,worker

# Send BusinessRuleFailCommand via UI or curl
# Verify: command status = FAILED (not IN_TROUBLESHOOTING_QUEUE)
# Verify: not visible in TSQ page
# Verify: process auto-compensates if applicable
```
