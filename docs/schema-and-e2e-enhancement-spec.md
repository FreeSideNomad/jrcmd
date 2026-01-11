# E2E Test Application v2 Specification

## Overview

This specification documents the requirements for implementing E2E Test Application v2 features, addressing issues from `docs/issuues-01.md`. Requirements are split between core library changes and E2E test application changes.

**IMPORTANT**: Java and Python implementations MUST use identical database schema and stored procedures to ensure wire compatibility. Copy migrations from Python rcmd.

---

## Part 1: Core Library Requirements

### 1.1 Database Schema Alignment ✅ COMPLETED

**Status**: Migrations copied directly from Python rcmd. Java and Python now use identical schema.

**Migration Files** (copied from `/Users/igormusic/code/rcmd/migrations/`):
- `V001__commandbus_schema.sql` - Core schema with all tables and stored procedures
- `V002__e2e_schema.sql` - E2E test schema with test_command, config, batch_summary, and queues
- `V003__process_batch_tsq_stats.sql` - Updated sp_refresh_batch_stats with TSQ detection

**Key Schema Features Now Included:**
- `batch.batch_type` column (COMMAND or PROCESS)
- `process.batch_id` column for process batch linking
- `ix_command_batch_status` index for efficient batch queries
- `ix_process_batch_id` and `ix_process_batch_status` indexes
- `sp_refresh_batch_stats()` with PROCESS batch TSQ detection
- All stored procedures: sp_receive_command, sp_finish_command, sp_fail_command, etc.

### 1.2 Repository Compatibility Matrix

**Python CommandRepository Methods** (must implement in Java):

| Python Method | Java Equivalent | Status |
|---------------|-----------------|--------|
| `save(metadata, queue_name, conn)` | `save(metadata, queueName)` | EXISTS |
| `get(domain, command_id, conn)` | `get(domain, commandId)` | EXISTS |
| `update_status(domain, command_id, status)` | `updateStatus(domain, commandId, status)` | EXISTS |
| `exists(domain, command_id)` | `exists(domain, commandId)` | EXISTS |
| `query(status, domain, command_type, created_after, created_before, limit, offset)` | `query(...)` | EXISTS |
| `save_batch(metadata_list, queue_name)` | `saveBatch(metadataList, queueName)` | EXISTS |
| `exists_batch(domain, command_ids)` | `existsBatch(domain, commandIds)` | EXISTS |
| `list_by_batch(domain, batch_id, status, limit, offset)` | `listByBatch(...)` | EXISTS |
| `sp_receive_command(...)` | `spReceiveCommand(...)` | EXISTS |
| `sp_finish_command(...)` | `spFinishCommand(...)` | EXISTS |
| `sp_fail_command(...)` | `spFailCommand(...)` | EXISTS |
| **NEW: `get_distinct_command_types(domain)`** | `getDistinctCommandTypes(domain)` | **ADD** |
| **NEW: `get_distinct_domains()`** | `getDistinctDomains()` | **ADD** |

**Python BatchRepository Methods**:

| Python Method | Java Equivalent | Status |
|---------------|-----------------|--------|
| `save(metadata)` | `save(metadata)` | EXISTS |
| `get(domain, batch_id)` | `get(domain, batchId)` | EXISTS |
| `exists(domain, batch_id)` | `exists(domain, batchId)` | EXISTS |
| `list_batches(domain, status, limit, offset)` | `list(domain, status, limit, offset)` | EXISTS |
| `tsq_complete(domain, batch_id)` | `tsqComplete(domain, batchId)` | CHECK |
| `tsq_cancel(domain, batch_id)` | `tsqCancel(domain, batchId)` | CHECK |
| `tsq_retry(domain, batch_id)` | `tsqRetry(domain, batchId)` | CHECK |
| `refresh_stats(domain, batch_id)` | `refreshStats(domain, batchId)` | **ADD** |
| **NEW: `list_by_type(domain, batch_type, status, limit, offset)`** | `listByType(...)` | **ADD** |

**Python ProcessRepository Methods**:

| Python Method | Java Equivalent | Status |
|---------------|-----------------|--------|
| `save(process)` | `save(process)` | EXISTS |
| `update(process)` | `update(process)` | EXISTS |
| `get_by_id(domain, process_id)` | `getById(domain, processId)` | EXISTS |
| `find_by_status(domain, statuses)` | `findByStatus(domain, statuses)` | EXISTS |
| `log_step(domain, process_id, entry)` | `logStep(...)` | EXISTS |
| `update_step_reply(domain, process_id, command_id, entry)` | `updateStepReply(...)` | EXISTS |
| `get_audit_trail(domain, process_id)` | `getAuditTrail(...)` | EXISTS |
| `get_completed_steps(domain, process_id)` | `getCompletedSteps(...)` | EXISTS |
| **NEW: `list_by_batch(domain, batch_id, status, limit, offset)`** | `listByBatch(...)` | **ADD** |

**Python AuditLogger Methods**:

| Python Method | Java Equivalent | Status |
|---------------|-----------------|--------|
| `log(domain, command_id, event_type, details)` | `log(...)` | EXISTS |
| `log_batch(events)` | `logBatch(events)` | CHECK |
| `get_events(command_id, domain)` | `getEvents(commandId, domain)` | EXISTS |

### 1.3 Model Extensions

**BatchMetadata** (`src/main/java/com/commandbus/model/BatchMetadata.java`):
```java
public record BatchMetadata(
    // existing fields...
    String batchType  // NEW: "COMMAND" or "PROCESS"
) {}
```

**ProcessMetadata** (`src/main/java/com/commandbus/model/ProcessMetadata.java`):
```java
public record ProcessMetadata<TState, TStep>(
    // existing fields...
    UUID batchId  // NEW: Optional link to batch
) {}
```

### 1.4 New Repository Methods Summary

**CommandRepository**:
```java
List<String> getDistinctDomains();
List<String> getDistinctCommandTypes(String domain);
```

**BatchRepository**:
```java
void refreshStats(String domain, UUID batchId);  // Calls sp_refresh_batch_stats
List<BatchMetadata> listByType(String domain, String batchType, BatchStatus status, int limit, int offset);
```

**ProcessRepository**:
```java
List<ProcessMetadata<?, ?>> listByBatch(String domain, UUID batchId, ProcessStatus status, int limit, int offset);
```

---

## Part 2: E2E Test Application Requirements

### 2.1 Fix: Command Status Filter (Issue #1)

**Problem**: Filter by status not working at `/commands?status=COMPLETED`

**Files to modify**:
- `src/test/java/com/commandbus/e2e/controller/CommandController.java`
- `src/test/resources/templates/e2e/pages/commands.html`

**Root cause investigation**:
- Check if `status` parameter is being parsed correctly (enum conversion)
- Verify query method passes status to repository
- Ensure template sends correct status value

**Fix**:
```java
@GetMapping
public String listCommands(
    @RequestParam(required = false) String commandType,
    @RequestParam(required = false) CommandStatus status,  // Ensure proper enum binding
    Model model) {
    // Pass status to repository query
}
```

### 2.2 Command Type and Domain Dropdowns from DB (Issue #2)

**Files to modify**:
- `src/test/java/com/commandbus/e2e/controller/CommandController.java`
- `src/test/resources/templates/e2e/pages/commands.html`

**Note**: Since command types are per-domain, we also need a domain dropdown to filter command types.

**New Repository Methods Required**:
```java
// In CommandRepository or via separate query
List<String> getDistinctDomains();
List<String> getDistinctCommandTypes(String domain);
```

**SQL for new methods**:
```sql
-- Get distinct domains
SELECT DISTINCT domain FROM commandbus.command ORDER BY domain;

-- Get distinct command types for a domain
SELECT DISTINCT command_type FROM commandbus.command
WHERE domain = ? ORDER BY command_type;
```

**Controller Implementation**:
```java
@GetMapping
public String listCommands(
    @RequestParam(required = false) String domain,
    @RequestParam(required = false) String commandType,
    @RequestParam(required = false) CommandStatus status,
    Model model) {

    // Get distinct domains for dropdown
    List<String> domains = commandRepository.getDistinctDomains();
    model.addAttribute("domains", domains);

    // Use selected domain or default
    String selectedDomain = domain != null ? domain : this.defaultDomain;
    model.addAttribute("selectedDomain", selectedDomain);

    // Get distinct command types for selected domain
    List<String> commandTypes = commandRepository.getDistinctCommandTypes(selectedDomain);
    model.addAttribute("commandTypes", commandTypes);

    // Query commands with filters
    List<CommandMetadata> commands = commandRepository.query(
        status, selectedDomain, commandType, null, null, 100, 0);
    model.addAttribute("commands", toViewList(commands));
    model.addAttribute("statuses", CommandStatus.values());

    return "pages/commands";
}
```

**Template change**:
```html
<form class="row g-3 mb-4" th:action="@{/commands}" method="get">
    <!-- Domain dropdown -->
    <div class="col-auto">
        <select name="domain" class="form-select" onchange="this.form.submit()">
            <option th:each="d : ${domains}"
                    th:value="${d}"
                    th:text="${d}"
                    th:selected="${d == selectedDomain}"></option>
        </select>
    </div>

    <!-- Command Type dropdown (populated based on domain) -->
    <div class="col-auto">
        <select name="commandType" class="form-select">
            <option value="">All Types</option>
            <option th:each="type : ${commandTypes}"
                    th:value="${type}"
                    th:text="${type}"
                    th:selected="${type == commandType}"></option>
        </select>
    </div>

    <!-- Status dropdown -->
    <div class="col-auto">
        <select name="status" class="form-select">
            <option value="">All Statuses</option>
            <option th:each="s : ${statuses}" th:value="${s}" th:text="${s}"
                    th:selected="${s == status}"></option>
        </select>
    </div>

    <div class="col-auto">
        <button type="submit" class="btn btn-primary">Filter</button>
        <a th:href="@{/commands}" class="btn btn-secondary">Clear</a>
    </div>
</form>
```

### 2.3 TSQ Action Icons (Issue #3)

**File**: `src/test/resources/templates/e2e/pages/tsq.html`

**Current**: Button groups with text labels
**Target**: Icon buttons with tooltips

**Implementation**:
```html
<td class="text-end">
    <div class="btn-group btn-group-sm">
        <button type="button" class="btn btn-outline-primary"
                title="Retry" data-bs-toggle="tooltip"
                th:data-command-id="${cmd.commandId}"
                onclick="retryCommand(this)">
            <i class="bi bi-arrow-clockwise"></i>
        </button>
        <button type="button" class="btn btn-outline-success"
                title="Complete" data-bs-toggle="tooltip"
                data-bs-toggle="modal" data-bs-target="#completeModal">
            <i class="bi bi-check-circle"></i>
        </button>
        <button type="button" class="btn btn-outline-danger"
                title="Cancel" data-bs-toggle="tooltip"
                data-bs-toggle="modal" data-bs-target="#cancelModal">
            <i class="bi bi-x-circle"></i>
        </button>
    </div>
</td>
```

### 2.4 TSQ Row Click to Command Detail (Issue #4)

**Files to modify**:
- `src/test/resources/templates/e2e/pages/tsq.html`

**Implementation**:
```html
<tr th:each="cmd : ${commands}"
    class="clickable-row"
    style="cursor: pointer;"
    th:data-href="@{/commands/{id}(id=${cmd.commandId})}">
    <!-- Row cells... -->
    <td class="text-end" onclick="event.stopPropagation()">
        <!-- Action buttons - stop propagation to prevent row click -->
    </td>
</tr>

<script>
document.querySelectorAll('.clickable-row').forEach(row => {
    row.addEventListener('click', function() {
        window.location.href = this.dataset.href;
    });
});
</script>
```

### 2.5 Batch Creation Page (Issue #5)

**New files**:
- `src/test/java/com/commandbus/e2e/controller/BatchController.java` (extend)
- `src/test/resources/templates/e2e/pages/batch_new.html`
- `src/test/java/com/commandbus/e2e/dto/BatchCreateRequest.java`

**BatchCreateRequest DTO**:
```java
public record BatchCreateRequest(
    String name,
    int commandCount,
    BatchBehavior behavior,
    int maxAttempts,
    String replyTo
) {
    public record BatchBehavior(
        double failPermanentPct,
        double failTransientPct,
        double timeoutPct,
        int minDurationMs,
        int maxDurationMs
    ) {}
}
```

**Controller endpoints**:
```java
@GetMapping("/new")
public String newBatchForm(Model model) {
    model.addAttribute("commandTypes", getAvailableCommandTypes());
    return "pages/batch_new";
}

@PostMapping
public String createBatch(@ModelAttribute BatchCreateRequest request,
                         RedirectAttributes redirectAttributes) {
    UUID batchId = batchService.createBatch(request);
    redirectAttributes.addFlashAttribute("success", "Batch created: " + batchId);
    return "redirect:/batches/" + batchId;
}
```

**Template features** (from Python reference `/Users/igormusic/code/rcmd/tests/e2e/app/templates/pages/batch_new.html`):
- Batch name input
- Command count (1-1,000,000)
- Failure probabilities (permanent %, transient %, timeout %)
- Duration range (min/max ms)
- Max attempts
- Reply queue option
- Real-time expected outcome preview (JavaScript)
- Sequential probability calculation (not independent)

**Sequential Probability Calculation**:
```javascript
function calculateExpectedOutcomes(count, failPermanent, failTransient, timeout) {
    const permanentCount = count * (failPermanent / 100);
    const remainingAfterPermanent = count - permanentCount;

    const transientCount = remainingAfterPermanent * (failTransient / 100);
    const remainingAfterTransient = remainingAfterPermanent - transientCount;

    const timeoutCount = remainingAfterTransient * (timeout / 100);
    const successCount = remainingAfterTransient - timeoutCount;

    return { permanent: permanentCount, transient: transientCount,
             timeout: timeoutCount, success: successCount };
}
```

### 2.6 Process Batch Creation (Issue #6)

**New files**:
- `src/test/java/com/commandbus/e2e/controller/ProcessBatchController.java`
- `src/test/resources/templates/e2e/pages/process_batch_new.html`
- `src/test/resources/templates/e2e/pages/process_batch_list.html`
- `src/test/resources/templates/e2e/pages/process_batch_detail.html`
- `src/test/java/com/commandbus/e2e/dto/ProcessBatchCreateRequest.java`
- `src/test/java/com/commandbus/e2e/dto/ProcessBatchView.java`

**ProcessBatchCreateRequest DTO**:
```java
public record ProcessBatchCreateRequest(
    int count,
    LocalDate fromDate,
    LocalDate toDate,
    String outputType,  // PDF, HTML, CSV
    Map<String, StepBehavior> behavior
) {
    public record StepBehavior(
        double failPermanentPct,
        double failTransientPct,
        double timeoutPct,
        int minDurationMs,
        int maxDurationMs
    ) {}
}
```

**Controller endpoints**:
```java
@Controller
@RequestMapping("/process-batches")
public class ProcessBatchController {

    @GetMapping
    public String listProcessBatches(
        @RequestParam(required = false) BatchStatus status,
        Model model) {
        List<BatchMetadata> batches = batchRepository.listByType(domain, "PROCESS", status, 50, 0);
        model.addAttribute("batches", batches);
        return "pages/process_batch_list";
    }

    @GetMapping("/new")
    public String newProcessBatchForm(Model model) {
        model.addAttribute("processTypes", getAvailableProcessTypes());
        return "pages/process_batch_new";
    }

    @PostMapping
    public String createProcessBatch(@ModelAttribute ProcessBatchCreateRequest request,
                                    RedirectAttributes redirectAttributes) {
        UUID batchId = processBatchService.createBatch(request);
        return "redirect:/process-batches/" + batchId;
    }

    @GetMapping("/{batchId}")
    public String processBatchDetail(@PathVariable UUID batchId, Model model) {
        BatchMetadata batch = batchRepository.get(domain, batchId).orElseThrow();
        List<ProcessMetadata<?,?>> processes = processRepository.listByBatch(domain, batchId, null, 50, 0);
        model.addAttribute("batch", batch);
        model.addAttribute("processes", processes);
        return "pages/process_batch_detail";
    }
}
```

**UI Features**:
- Process count input
- Date range selection
- Output type selector
- Per-step behavior configuration
- Progress bar with real-time updates
- Process list with status filtering
- Auto-refresh (3-5 seconds)

**Process Batch Stats Mapping**:
| Batch Column | PROCESS Batch Meaning |
|---|---|
| completed_count | Processes in COMPLETED or COMPENSATED status |
| canceled_count | Processes in FAILED or CANCELED status |
| in_troubleshooting_count | Processes blocked by commands in TSQ |

### 2.7 Queue Dropdown Selection (Issue #7)

**Files to modify**:
- `src/test/java/com/commandbus/e2e/controller/QueueController.java`
- `src/test/resources/templates/e2e/pages/queues.html`

**Implementation**:
```java
@GetMapping
public String queues(
    @RequestParam(required = false) String selectedQueue,
    Model model) {
    List<String> queueNames = pgmqClient.listQueues();  // or from repository
    model.addAttribute("queueNames", queueNames);
    model.addAttribute("selectedQueue", selectedQueue != null ? selectedQueue : defaultQueue);

    // Get stats for selected queue
    QueueStats stats = getQueueStats(selectedQueue);
    model.addAttribute("stats", stats);
    return "pages/queues";
}
```

**Template**:
```html
<div class="mb-4">
    <label class="form-label">Select Queue</label>
    <select name="selectedQueue" class="form-select" onchange="this.form.submit()">
        <option th:each="q : ${queueNames}"
                th:value="${q}"
                th:text="${q}"
                th:selected="${q == selectedQueue}"></option>
    </select>
</div>
```

### 2.8 Send with Probabilities (Issue #8)

**Files to modify**:
- `src/test/java/com/commandbus/e2e/controller/SendCommandController.java`
- `src/test/resources/templates/e2e/pages/send_command.html`

**New section in send form**:
```html
<div class="card mb-4">
    <div class="card-header">
        <h5>Send Batch with Probabilities</h5>
    </div>
    <div class="card-body">
        <form th:action="@{/send/probability-batch}" method="post">
            <div class="row g-3">
                <div class="col-md-4">
                    <label class="form-label">Command Count</label>
                    <input type="number" name="count" class="form-control"
                           min="1" max="1000000" value="100">
                </div>
                <div class="col-md-4">
                    <label class="form-label">Permanent Fail %</label>
                    <input type="number" name="failPermanentPct" class="form-control"
                           min="0" max="100" step="0.1" value="0">
                </div>
                <div class="col-md-4">
                    <label class="form-label">Transient Fail %</label>
                    <input type="number" name="failTransientPct" class="form-control"
                           min="0" max="100" step="0.1" value="0">
                </div>
                <div class="col-md-4">
                    <label class="form-label">Timeout %</label>
                    <input type="number" name="timeoutPct" class="form-control"
                           min="0" max="100" step="0.1" value="0">
                </div>
                <div class="col-md-4">
                    <label class="form-label">Min Duration (ms)</label>
                    <input type="number" name="minDurationMs" class="form-control"
                           min="0" max="120000" value="50">
                </div>
                <div class="col-md-4">
                    <label class="form-label">Max Duration (ms)</label>
                    <input type="number" name="maxDurationMs" class="form-control"
                           min="0" max="120000" value="100">
                </div>
            </div>

            <!-- Expected outcome preview -->
            <div id="expected-outcomes" class="mt-3">
                <h6>Expected Outcomes</h6>
                <div class="progress" style="height: 25px;">
                    <div class="progress-bar bg-success" id="success-bar">Success</div>
                    <div class="progress-bar bg-danger" id="permanent-bar">Permanent</div>
                    <div class="progress-bar bg-warning" id="transient-bar">Transient</div>
                    <div class="progress-bar bg-secondary" id="timeout-bar">Timeout</div>
                </div>
            </div>

            <button type="submit" class="btn btn-primary mt-3">Create Batch</button>
        </form>
    </div>
</div>
```

**Controller endpoint**:
```java
@PostMapping("/probability-batch")
public String sendProbabilityBatch(
    @RequestParam int count,
    @RequestParam double failPermanentPct,
    @RequestParam double failTransientPct,
    @RequestParam double timeoutPct,
    @RequestParam int minDurationMs,
    @RequestParam int maxDurationMs,
    RedirectAttributes redirectAttributes) {

    UUID batchId = UUID.randomUUID();

    // Calculate command distribution using sequential probability
    int remaining = count;
    int permanentCount = (int)(remaining * failPermanentPct / 100);
    remaining -= permanentCount;
    int transientCount = (int)(remaining * failTransientPct / 100);
    remaining -= transientCount;
    int timeoutCount = (int)(remaining * timeoutPct / 100);
    int successCount = remaining - timeoutCount;

    // Send commands with appropriate types
    for (int i = 0; i < permanentCount; i++) {
        commandBus.send(domain, "PermanentFailCommand", UUID.randomUUID(),
            Map.of("index", i), batchId, null, null);
    }
    // ... similar for transient, timeout, success

    redirectAttributes.addFlashAttribute("success",
        String.format("Batch created: %d success, %d permanent, %d transient, %d timeout",
            successCount, permanentCount, transientCount, timeoutCount));
    return "redirect:/batches/" + batchId;
}
```

---

## Part 3: Implementation Priority

### Phase 1: Quick Fixes
1. **Issue #1**: Fix command status filter
2. **Issue #2**: Command type dropdown from DB
3. **Issue #3**: TSQ action icons
4. **Issue #4**: TSQ row click to detail

### Phase 2: Enhanced Send
5. **Issue #8**: Send with probabilities
6. **Issue #7**: Queue dropdown selection

### Phase 3: Batch Creation
7. **Issue #5**: Batch creation page with full Python parity

### Phase 4: Process Batch
8. **Issue #6**: Process batch feature
   - Database migration
   - Core library extensions
   - E2E UI implementation

---

## Part 4: Verification Checklist

### Quick Fixes Verification
- [ ] `/commands?status=COMPLETED` filters correctly
- [ ] Command type dropdown shows distinct types from DB
- [ ] TSQ page shows icon buttons with tooltips
- [ ] Clicking TSQ row navigates to command detail

### Enhanced Features Verification
- [ ] Queue dropdown shows all available queues
- [ ] Selecting queue updates stats display
- [ ] Probability batch creates correct command distribution
- [ ] Expected outcome preview updates in real-time

### Batch Creation Verification
- [ ] `/batches/new` form renders correctly
- [ ] Batch creation with probabilities works
- [ ] Batch detail shows progress and commands
- [ ] Workers process batch commands correctly

### Process Batch Verification
- [ ] `/process-batches` lists process batches only
- [ ] `/process-batches/new` creates process batch
- [ ] Process batch detail shows process list
- [ ] Auto-refresh updates progress
- [ ] TSQ blocking is detected and displayed

---

## Part 5: Files Summary

### Core Library (src/main/)
| File | Action |
|------|--------|
| `resources/db/migration/V004__batch_type_and_process_batch.sql` | NEW |
| `java/com/commandbus/model/BatchMetadata.java` | MODIFY |
| `java/com/commandbus/model/ProcessMetadata.java` | MODIFY |
| `java/com/commandbus/repository/CommandRepository.java` | MODIFY |
| `java/com/commandbus/repository/BatchRepository.java` | MODIFY |
| `java/com/commandbus/repository/ProcessRepository.java` | MODIFY |
| `java/com/commandbus/repository/impl/JdbcCommandRepository.java` | MODIFY |
| `java/com/commandbus/repository/impl/JdbcBatchRepository.java` | MODIFY |
| `java/com/commandbus/repository/impl/JdbcProcessRepository.java` | MODIFY |

### E2E Test Application (src/test/)
| File | Action |
|------|--------|
| `java/com/commandbus/e2e/controller/CommandController.java` | MODIFY |
| `java/com/commandbus/e2e/controller/BatchController.java` | MODIFY |
| `java/com/commandbus/e2e/controller/ProcessBatchController.java` | NEW |
| `java/com/commandbus/e2e/controller/TsqController.java` | MODIFY |
| `java/com/commandbus/e2e/controller/QueueController.java` | MODIFY |
| `java/com/commandbus/e2e/controller/SendCommandController.java` | MODIFY |
| `java/com/commandbus/e2e/dto/BatchCreateRequest.java` | NEW |
| `java/com/commandbus/e2e/dto/ProcessBatchCreateRequest.java` | NEW |
| `java/com/commandbus/e2e/dto/ProcessBatchView.java` | NEW |
| `resources/templates/e2e/pages/commands.html` | MODIFY |
| `resources/templates/e2e/pages/tsq.html` | MODIFY |
| `resources/templates/e2e/pages/queues.html` | MODIFY |
| `resources/templates/e2e/pages/send_command.html` | MODIFY |
| `resources/templates/e2e/pages/batch_new.html` | NEW |
| `resources/templates/e2e/pages/process_batch_list.html` | NEW |
| `resources/templates/e2e/pages/process_batch_new.html` | NEW |
| `resources/templates/e2e/pages/process_batch_detail.html` | NEW |

---

## References

- Python batch_new.html: `/Users/igormusic/code/rcmd/tests/e2e/app/templates/pages/batch_new.html`
- Python process batch migrations: `/Users/igormusic/code/rcmd/migrations`
- Java spec 10-process.md: `/Users/igormusic/code/jrcmd/docs/java-spec/10-process.md`
