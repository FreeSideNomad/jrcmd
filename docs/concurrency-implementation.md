# Concurrency Implementation Analysis

This document analyzes how race conditions are managed in the commandbus-spring library, covering both step-based (ProcessStepManager) and command-based (Worker) processing.

## Table of Contents

1. [Overview](#overview)
2. [Step-Based Processing (ProcessStepManager)](#step-based-processing-processstepmanager)
3. [Command-Based Processing (Worker)](#command-based-processing-worker)
4. [Atomic Operations Reference](#atomic-operations-reference)
5. [Potential Race Conditions](#potential-race-conditions)
6. [Suggested Improvements](#suggested-improvements)

---

## Overview

The library uses several mechanisms to ensure serialized access to shared resources:

| Mechanism | Layer | Purpose |
|-----------|-------|---------|
| `FOR UPDATE SKIP LOCKED` | Database | Claim processes/messages without blocking |
| Stored Procedures | Database | Atomic multi-table updates |
| Spring `TransactionTemplate` | Application | Transaction boundaries |
| `Semaphore` | Application | Concurrency throttling |
| PGMQ visibility timeout | Queue | Message claiming |

---

## Step-Based Processing (ProcessStepManager)

### 1. Process Claiming

Multiple workers can poll for pending processes simultaneously. Race conditions are prevented using `FOR UPDATE SKIP LOCKED`:

```sql
-- From V001__commandbus_schema.sql:876-888
CREATE OR REPLACE FUNCTION commandbus.sp_claim_pending_processes(
    p_domain TEXT,
    p_process_type TEXT,
    p_batch_size INTEGER,
    p_processing_timeout_seconds INTEGER DEFAULT 60
)
RETURNS TABLE(process_id UUID) AS $$
BEGIN
    -- Release stale claims first (crashed workers)
    UPDATE commandbus.process p
    SET status = 'PENDING', claimed_at = NULL, updated_at = NOW()
    WHERE p.domain = p_domain AND p.process_type = p_process_type
      AND p.execution_model = 'PROCESS_STEP' AND p.status = 'IN_PROGRESS'
      AND p.claimed_at IS NOT NULL
      AND p.claimed_at < NOW() - (p_processing_timeout_seconds || ' seconds')::INTERVAL;

    -- Claim pending processes atomically using FOR UPDATE SKIP LOCKED
    RETURN QUERY
    WITH claimed AS (
        SELECT p.process_id FROM commandbus.process p
        WHERE p.domain = p_domain AND p.process_type = p_process_type
          AND p.execution_model = 'PROCESS_STEP' AND p.status = 'PENDING'
        ORDER BY p.created_at ASC LIMIT p_batch_size
        FOR UPDATE SKIP LOCKED  -- Key: Skip already-locked rows
    )
    UPDATE commandbus.process p
    SET status = 'IN_PROGRESS', claimed_at = NOW(), updated_at = NOW()
    FROM claimed c WHERE p.process_id = c.process_id
    RETURNING p.process_id;
END;
$$ LANGUAGE plpgsql;
```

**Key Points:**
- `FOR UPDATE SKIP LOCKED` ensures workers don't block each other
- Each worker gets a distinct set of processes
- Stale claims (from crashed workers) are automatically released
- Status transitions from `PENDING` → `IN_PROGRESS` in the same atomic operation

### 2. Process Execution

Process execution is wrapped in a Spring transaction:

```java
// From ProcessStepManager.java:282-310
public void processAsyncResponse(UUID processId, Consumer<TState> stateUpdater) {
    transactionTemplate.executeWithoutResult(status -> {
        // Get current wait name before loading state (for audit)
        String currentWait = getCurrentWaitName(processId);

        // Load current state
        TState state = loadState(processId);
        // Apply the state update
        stateUpdater.accept(state);

        // Save updated state
        persistState(processId, state);

        // Log the async response
        logAsyncResponse(processId, currentWait, Instant.now());

        // Check if we should resume (wait condition may now be satisfied)
        ProcessStatus currentStatus = getProcessStatus(processId);

        // Only resume if waiting for async
        if (currentStatus == ProcessStatus.WAITING_FOR_ASYNC) {
            log.debug("Async response received for process {}, resuming execution", processId);
            executeProcess(processId, state);
        }
    });
}
```

### 3. Atomic State Updates

State updates use stored procedures with JSONB patch merging:

```sql
-- From V001__commandbus_schema.sql:727-767
CREATE OR REPLACE FUNCTION commandbus.sp_update_process_state_step(
    p_domain TEXT,
    p_process_id UUID,
    p_state_patch JSONB,
    p_new_step TEXT DEFAULT NULL,
    p_new_status TEXT DEFAULT NULL,
    -- ... other parameters
)
RETURNS VOID AS $$
DECLARE
    v_current_status TEXT;
BEGIN
    -- Check for terminal status (idempotency guard)
    SELECT status INTO v_current_status FROM commandbus.process
    WHERE domain = p_domain AND process_id = p_process_id;

    IF v_current_status IN ('COMPLETED', 'FAILED', 'COMPENSATED', 'CANCELED') THEN
        -- Only allow state patch on terminal status
        IF p_state_patch IS NOT NULL THEN
            UPDATE commandbus.process SET state = state || p_state_patch, updated_at = NOW()
            WHERE domain = p_domain AND process_id = p_process_id;
        END IF;
        RETURN;
    END IF;

    -- Full update for non-terminal status
    UPDATE commandbus.process
    SET state = CASE WHEN p_state_patch IS NOT NULL THEN state || p_state_patch ELSE state END,
        current_step = COALESCE(p_new_step, current_step),
        status = COALESCE(p_new_status, status),
        -- ... other fields
        updated_at = NOW()
    WHERE domain = p_domain AND process_id = p_process_id;
END;
$$ LANGUAGE plpgsql;
```

**Key Points:**
- Terminal status check prevents status regression
- JSONB merge (`||`) preserves existing state while applying patches
- Single atomic UPDATE statement

### 4. Retry Claiming

Similar pattern for retry-due processes:

```sql
-- From V001__commandbus_schema.sql:897-930
CREATE OR REPLACE FUNCTION commandbus.sp_claim_retry_processes(
    p_domain TEXT,
    p_process_type TEXT,
    p_batch_size INTEGER,
    p_processing_timeout_seconds INTEGER DEFAULT 60
)
RETURNS TABLE(process_id UUID) AS $$
BEGIN
    -- First, release any stale claims
    UPDATE commandbus.process p
    SET status = 'WAITING_FOR_RETRY', claimed_at = NULL, updated_at = NOW()
    WHERE p.domain = p_domain AND p.process_type = p_process_type
      AND p.execution_model = 'PROCESS_STEP' AND p.status = 'IN_PROGRESS'
      AND p.claimed_at IS NOT NULL
      AND p.claimed_at < NOW() - (p_processing_timeout_seconds || ' seconds')::INTERVAL
      AND p.next_retry_at IS NOT NULL;

    -- Claim retry-due processes atomically
    RETURN QUERY
    WITH claimed AS (
        SELECT p.process_id FROM commandbus.process p
        WHERE p.domain = p_domain AND p.process_type = p_process_type
          AND p.execution_model = 'PROCESS_STEP' AND p.status = 'WAITING_FOR_RETRY'
          AND p.next_retry_at IS NOT NULL AND p.next_retry_at <= NOW()
        ORDER BY p.next_retry_at ASC LIMIT p_batch_size
        FOR UPDATE SKIP LOCKED
    )
    UPDATE commandbus.process p
    SET status = 'IN_PROGRESS', claimed_at = NOW(), next_retry_at = NULL, updated_at = NOW()
    FROM claimed c WHERE p.process_id = c.process_id
    RETURNING p.process_id;
END;
$$ LANGUAGE plpgsql;
```

---

## Command-Based Processing (Worker)

### 1. Message Claiming via PGMQ

PGMQ's `read()` function provides atomic message claiming with visibility timeout:

```java
// From JdbcPgmqClient.java:135-141
@Override
public List<PgmqMessage> read(String queueName, int visibilityTimeoutSeconds, int batchSize) {
    return jdbcTemplate.query(
        "SELECT * FROM pgmq.read(?, ?, ?)",
        this::mapToPgmqMessage,
        queueName, visibilityTimeoutSeconds, batchSize
    );
}
```

**How PGMQ read() works:**
- Atomically selects and updates message visibility timestamp
- Messages become invisible to other readers for `visibilityTimeoutSeconds`
- If not deleted/archived within timeout, message becomes visible again

### 2. Command Reception with Status Guard

Before processing, commands are "received" atomically with status validation:

```sql
-- From V001__commandbus_schema.sql:297-375
CREATE OR REPLACE FUNCTION commandbus.sp_receive_command(
    p_domain TEXT,
    p_command_id UUID,
    p_new_status TEXT DEFAULT 'IN_PROGRESS',
    p_msg_id BIGINT DEFAULT NULL,
    p_max_attempts INT DEFAULT NULL
) RETURNS TABLE (...) AS $$
BEGIN
    UPDATE commandbus.command c
    SET attempts = c.attempts + 1,
        status = p_new_status,
        updated_at = NOW()
    WHERE c.domain = p_domain
      AND c.command_id = p_command_id
      AND c.status NOT IN ('COMPLETED', 'CANCELED')  -- Guard: only non-terminal
    RETURNING ... INTO ...;

    IF NOT FOUND THEN
        RETURN;  -- Command already terminal, return empty
    END IF;

    -- Insert audit record
    INSERT INTO commandbus.audit (domain, command_id, event_type, details_json)
    VALUES (p_domain, p_command_id, 'RECEIVED', ...);

    RETURN QUERY SELECT ...;
END;
$$ LANGUAGE plpgsql;
```

**Key Points:**
- `WHERE status NOT IN ('COMPLETED', 'CANCELED')` prevents duplicate processing
- Returns empty if command already processed (idempotency)
- Increments attempt counter atomically

### 3. Concurrency Throttling

Worker uses a Semaphore to limit concurrent processing:

```java
// From DefaultWorker.java:54-58
private final AtomicInteger inFlightCount = new AtomicInteger(0);
private final Semaphore semaphore;

// Constructor
this.semaphore = new Semaphore(concurrency);

// From DefaultWorker.java:516-533
private void processMessage(PgmqMessage pgmqMessage) {
    try {
        semaphore.acquire();  // Block if at capacity
        inFlightCount.incrementAndGet();

        executor.submit(() -> {
            try {
                processMessageInternal(pgmqMessage);
            } finally {
                inFlightCount.decrementAndGet();
                semaphore.release();
            }
        });
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
    }
}
```

### 4. Command Completion with Finish Guard

```sql
-- From V001__commandbus_schema.sql:378-430
CREATE OR REPLACE FUNCTION commandbus.sp_finish_command(
    p_domain TEXT,
    p_command_id UUID,
    p_status TEXT,
    p_event_type TEXT,
    -- ... error fields
) RETURNS BOOLEAN AS $$
DECLARE
    v_current_status TEXT;
BEGIN
    SELECT status INTO v_current_status
    FROM commandbus.command
    WHERE domain = p_domain AND command_id = p_command_id
    FOR UPDATE;  -- Lock the row

    IF v_current_status IS NULL THEN
        -- Command not found, log orphan audit
        INSERT INTO commandbus.audit ...;
        RETURN FALSE;
    END IF;

    -- Update command status
    UPDATE commandbus.command
    SET status = p_status, ...
    WHERE domain = p_domain AND command_id = p_command_id;

    -- Insert audit record
    INSERT INTO commandbus.audit ...;

    RETURN ...;
END;
$$ LANGUAGE plpgsql;
```

---

## Atomic Operations Reference

### Stored Procedures Summary

| Procedure | Purpose | Locking Strategy |
|-----------|---------|------------------|
| `sp_receive_command` | Claim command for processing | `WHERE status NOT IN (terminal)` |
| `sp_finish_command` | Complete/fail command | `FOR UPDATE` row lock |
| `sp_claim_pending_processes` | Claim batch of processes | `FOR UPDATE SKIP LOCKED` |
| `sp_claim_retry_processes` | Claim retry-due processes | `FOR UPDATE SKIP LOCKED` |
| `sp_update_process_state_step` | Update process state | Terminal status guard |
| `sp_release_stale_claims` | Cleanup crashed worker claims | Timeout-based |

### PGMQ Operations

| Operation | Atomicity | Notes |
|-----------|-----------|-------|
| `pgmq.send()` | Atomic | Returns message ID |
| `pgmq.read()` | Atomic | Sets visibility timeout |
| `pgmq.delete()` | Atomic | Removes message |
| `pgmq.archive()` | Atomic | Moves to archive table |
| `pgmq.set_vt()` | Atomic | Extends visibility |

---

## Potential Race Conditions

### 1. Async Response During Process Execution

**Scenario:** An async response arrives while the process is actively executing.

**Current Mitigation:**
```java
// ProcessStepManager.java:299-308
ProcessStatus currentStatus = getProcessStatus(processId);
if (currentStatus == ProcessStatus.WAITING_FOR_ASYNC) {
    executeProcess(processId, state);
} else {
    log.debug("... state updated but not resumed");
}
```

**Risk:** If status check and execution are not atomic, another worker could also resume.

**Analysis:** The entire `processAsyncResponse` runs in a transaction, so the status check and subsequent execution happen atomically. However, the `claimed_at` column isn't updated, so a duplicate execution could theoretically occur if the worker crashes mid-execution.

### 2. Wait Timeout vs Async Response Race

**Scenario:** Wait timeout fires at the same moment an async response arrives.

**Current Mitigation:** Both operations run in transactions. The timeout handler checks status:
```java
// handleWaitTimeout eventually calls executeProcess which checks status
```

**Risk:** Low - status transitions are serialized by database transactions.

### 3. Multiple Workers Polling Same Process Type

**Scenario:** Two workers poll for pending processes simultaneously.

**Current Mitigation:** `FOR UPDATE SKIP LOCKED` ensures each worker gets distinct processes.

**Risk:** None - this is correctly handled.

### 4. Message Redelivery After Handler Crash

**Scenario:** Worker crashes after `spReceiveCommand` but before completing.

**Current Mitigation:**
- PGMQ visibility timeout causes message to reappear
- `sp_receive_command` increments attempt counter
- Eventually moves to TSQ if max attempts exceeded

**Risk:** None - this is correctly handled (at-least-once delivery).

---

## Suggested Improvements

### 1. Add Optimistic Locking for Process State

**Problem:** Current implementation uses last-write-wins for state updates. Concurrent state modifications could lose data.

**Solution:** Add a `version` column with optimistic locking:

```sql
-- Add version column
ALTER TABLE commandbus.process ADD COLUMN version BIGINT DEFAULT 0;

-- Update sp_update_process_state_step
CREATE OR REPLACE FUNCTION commandbus.sp_update_process_state_step(
    p_domain TEXT,
    p_process_id UUID,
    p_expected_version BIGINT,  -- NEW: expected version
    p_state_patch JSONB,
    -- ...
)
RETURNS BOOLEAN AS $$  -- Return success/failure
BEGIN
    UPDATE commandbus.process
    SET state = ...,
        version = version + 1,  -- Increment version
        updated_at = NOW()
    WHERE domain = p_domain
      AND process_id = p_process_id
      AND version = p_expected_version;  -- Check version

    RETURN FOUND;  -- Return whether update succeeded
END;
```

**Impact:** Prevents lost updates; requires retry logic in application.

### 2. Add claimed_at Update During Resume

**Problem:** `resume()` doesn't update `claimed_at`, so stale claim detection doesn't work for long-running executions.

**Current Code:**
```java
// ProcessStepManager.resume() doesn't set claimed_at
public void resume(UUID processId) {
    transactionTemplate.executeWithoutResult(status -> {
        TState state = loadState(processId);
        executeProcess(processId, state);
    });
}
```

**Solution:**
```java
public void resume(UUID processId) {
    transactionTemplate.executeWithoutResult(status -> {
        // Update claimed_at to prevent stale claim detection
        processRepo.updateClaimedAt(getDomain(), processId, Instant.now());

        TState state = loadState(processId);
        executeProcess(processId, state);
    });
}
```

### 3. Add Distributed Lock for Async Response Processing

**Problem:** Multiple async responses for the same process could execute concurrently if they arrive simultaneously.

**Solution:** Use advisory locks:

```java
public void processAsyncResponse(UUID processId, Consumer<TState> stateUpdater) {
    transactionTemplate.executeWithoutResult(status -> {
        // Acquire advisory lock for this process
        jdbcTemplate.execute("SELECT pg_advisory_xact_lock(hashtext('" + processId + "'))");

        // ... existing logic
    });
}
```

### 4. Add Heartbeat for Long-Running Processes

**Problem:** Processes running longer than `processingTimeoutSeconds` are incorrectly released.

**Solution:** Implement heartbeat mechanism:

```java
// In executeProcess
ScheduledFuture<?> heartbeat = scheduler.scheduleAtFixedRate(() -> {
    processRepo.updateClaimedAt(domain, processId, Instant.now());
}, 30, 30, TimeUnit.SECONDS);

try {
    execute(state);
} finally {
    heartbeat.cancel(false);
}
```

### 5. Add Idempotency Keys for Steps

**Problem:** If a step succeeds but the process crashes before persisting, the step may execute again on retry.

**Solution:** Add idempotency tracking:

```sql
CREATE TABLE commandbus.step_execution (
    domain TEXT NOT NULL,
    process_id UUID NOT NULL,
    step_name TEXT NOT NULL,
    execution_id UUID NOT NULL,
    result JSONB,
    executed_at TIMESTAMPTZ DEFAULT NOW(),
    PRIMARY KEY (domain, process_id, step_name, execution_id)
);
```

```java
protected <R> R step(String name, Function<TState, R> action, ...) {
    UUID executionId = UUID.randomUUID();

    // Check for existing execution
    Optional<R> existing = stepRepo.getResult(processId, name);
    if (existing.isPresent()) {
        return existing.get();  // Replay recorded result
    }

    R result = action.apply(state);
    stepRepo.recordExecution(processId, name, executionId, result);
    return result;
}
```

### 6. Add Metrics for Concurrency Monitoring

**Solution:** Expose metrics for monitoring:

```java
@Component
public class ConcurrencyMetrics {
    private final MeterRegistry registry;

    public void recordClaimAttempt(String domain, boolean success) {
        registry.counter("commandbus.claim.attempts",
            "domain", domain,
            "success", String.valueOf(success)
        ).increment();
    }

    public void recordLockContention(String domain, long waitTimeMs) {
        registry.timer("commandbus.lock.wait",
            "domain", domain
        ).record(waitTimeMs, TimeUnit.MILLISECONDS);
    }
}
```

---

## Summary

The commandbus-spring library uses a robust combination of PostgreSQL features (`FOR UPDATE SKIP LOCKED`, stored procedures) and application-level controls (transactions, semaphores) to manage concurrency. The primary guarantees are:

1. **No duplicate process execution** - Claiming uses `FOR UPDATE SKIP LOCKED`
2. **No duplicate command processing** - Status guards in stored procedures
3. **At-least-once delivery** - PGMQ visibility timeout handles crashes
4. **Serialized state updates** - Database transactions

The suggested improvements would enhance:
- **Data integrity** - Optimistic locking prevents lost updates
- **Long-running process support** - Heartbeats prevent premature release
- **Exactly-once step execution** - Idempotency keys prevent duplicate side effects
- **Observability** - Metrics enable monitoring lock contention
