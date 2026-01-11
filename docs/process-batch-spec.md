# Process Batch E2E Implementation Specification

## Overview

Replicate the process batch functionality from Python E2E app (`/Users/igormusic/code/rcmd/tests/e2e/app`) to the Java E2E app. This enables creating batches of processes (multi-step workflows) for testing the process manager functionality.

**Reference**: Python E2E app at `/Users/igormusic/code/rcmd/tests/e2e/app/process/statement_report.py`

---

## Part 1: StatementReportProcess Manager

### 1.1 Process Definition

Create a 3-step workflow process manager that generates statement reports:

```
StatementQuery → StatementDataAggregation → StatementRender
```

**File**: `src/test/java/com/ivamare/commandbus/e2e/process/StatementReportProcess.java`

### 1.2 State Definition

```java
public record StatementReportState(
    LocalDate fromDate,
    LocalDate toDate,
    List<String> accountList,
    OutputType outputType,        // PDF, HTML, CSV
    String queryResultPath,       // Set after Query step
    String aggregatedDataPath,    // Set after Aggregation step
    String renderedFilePath,      // Set after Render step
    StepBehavior behavior         // Per-step failure configuration
) implements ProcessState {

    @Override
    public Map<String, Object> toMap() { ... }

    public static StatementReportState fromMap(Map<String, Object> map) { ... }
}
```

### 1.3 Step Definition

```java
public enum StatementReportStep implements ProcessStep {
    QUERY,
    AGGREGATION,
    RENDER;

    @Override
    public String getCommandType() {
        return switch (this) {
            case QUERY -> "StatementQuery";
            case AGGREGATION -> "StatementDataAggregation";
            case RENDER -> "StatementRender";
        };
    }
}
```

### 1.4 Process Manager Implementation

```java
@Component
public class StatementReportProcessManager
        extends BaseProcessManager<StatementReportState, StatementReportStep> {

    public StatementReportProcessManager(...) {
        super("reporting", "StatementReport", StatementReportStep.class, ...);
    }

    @Override
    protected StatementReportState createInitialState(Map<String, Object> initialData) { ... }

    @Override
    protected Command buildCommand(ProcessMetadata<...> process) { ... }

    @Override
    protected StatementReportState updateState(ProcessMetadata<...> process, Reply reply) { ... }

    @Override
    protected StatementReportStep getNextStep(ProcessMetadata<...> process) { ... }
}
```

---

## Part 2: Reporting Domain Handlers

### 2.1 Handler Registration

**File**: `src/test/java/com/ivamare/commandbus/e2e/handlers/ReportingHandlers.java`

```java
@Component
public class ReportingHandlers {

    @PostConstruct
    public void registerHandlers() {
        handlerRegistry.register("reporting", "StatementQuery", this::handleQuery);
        handlerRegistry.register("reporting", "StatementDataAggregation", this::handleAggregation);
        handlerRegistry.register("reporting", "StatementRender", this::handleRender);
    }

    public Map<String, Object> handleQuery(Command cmd, HandlerContext ctx) {
        // Evaluate behavior from payload
        // Simulate query operation
        // Return: { query_result_path: "..." }
    }

    public Map<String, Object> handleAggregation(Command cmd, HandlerContext ctx) {
        // Evaluate behavior from payload
        // Simulate aggregation operation
        // Return: { aggregated_data_path: "..." }
    }

    public Map<String, Object> handleRender(Command cmd, HandlerContext ctx) {
        // Evaluate behavior from payload
        // Simulate render operation
        // Return: { rendered_file_path: "..." }
    }
}
```

### 2.2 Behavior Configuration

Each step can have independent failure/delay configuration:

```java
public record StepBehavior(
    ProbabilisticBehavior query,
    ProbabilisticBehavior aggregation,
    ProbabilisticBehavior render
) {}

public record ProbabilisticBehavior(
    double failPermanentPct,
    double failTransientPct,
    double failBusinessRulePct,
    double timeoutPct,
    int minDurationMs,
    int maxDurationMs
) {}
```

---

## Part 3: Worker Configuration

### 3.1 Multi-Domain Worker Support

**File**: `src/test/resources/application-worker.yml`

Update to support both "test" and "reporting" domains:

```yaml
commandbus:
  domain: test  # Primary domain
  worker:
    auto-start: true
    concurrency: 4
  process:
    enabled: true
    domain: reporting
    reply-queue: reporting__process_replies
    auto-start: true
```

### 3.2 Process Reply Router

Ensure ProcessReplyRouter is configured to route replies to StatementReportProcessManager.

**File**: `src/test/java/com/ivamare/commandbus/e2e/E2ETestApplication.java`

```java
@Bean
public ProcessReplyRouter processReplyRouter(...) {
    return ProcessReplyRouter.builder()
        .jdbcTemplate(jdbcTemplate)
        .domain("reporting")
        .replyQueue("reporting__process_replies")
        .managers(List.of(statementReportProcessManager))
        .build();
}
```

---

## Part 4: Process Batch DTOs

### 4.1 Request/Response DTOs

**File**: `src/test/java/com/ivamare/commandbus/e2e/dto/ProcessBatchCreateRequest.java`

```java
public record ProcessBatchCreateRequest(
    int count,
    LocalDate fromDate,
    LocalDate toDate,
    int accountCount,           // Number of random accounts per process
    OutputType outputType,
    StepBehavior behavior       // Optional per-step behavior
) {
    public static ProcessBatchCreateRequest defaults() {
        return new ProcessBatchCreateRequest(10, LocalDate.now().minusMonths(1),
            LocalDate.now(), 5, OutputType.PDF, null);
    }
}
```

**File**: `src/test/java/com/ivamare/commandbus/e2e/dto/ProcessBatchView.java`

```java
public record ProcessBatchView(
    UUID batchId,
    String domain,
    String name,
    BatchStatus status,
    int totalCount,
    int completedCount,
    int failedCount,
    int inProgressCount,
    Instant createdAt,
    Instant completedAt
) {
    public int progressPercent() { ... }
}
```

---

## Part 5: E2E Service Updates

### 5.1 Process Batch Methods

**File**: `src/test/java/com/ivamare/commandbus/e2e/service/E2EService.java`

Add methods for process batch management:

```java
// Create process batch
@Transactional
public UUID createProcessBatch(String domain, ProcessBatchCreateRequest request) {
    UUID batchId = UUID.randomUUID();

    // Create batch metadata (batch_type = 'PROCESS')
    batchRepository.save(new BatchMetadata(
        domain, batchId, request.name(), null,
        BatchStatus.PENDING, request.count(), 0, 0, 0,
        Instant.now(), null, null, "PROCESS"
    ));

    // Create processes in chunks
    for (int i = 0; i < request.count(); i++) {
        List<String> accounts = generateRandomAccounts(request.accountCount());
        Map<String, Object> initialData = buildInitialData(request, accounts);

        statementReportProcessManager.startProcess(batchId, initialData);
    }

    return batchId;
}

// List process batches
@Transactional(readOnly = true)
public List<ProcessBatchView> getProcessBatches(String domain, int limit, int offset) {
    return batchRepository.listBatches(domain, null, limit, offset)
        .stream()
        .filter(b -> "PROCESS".equals(b.batchType()))
        .map(this::toProcessBatchView)
        .toList();
}

// Get process batch with stats
@Transactional(readOnly = true)
public Optional<ProcessBatchView> getProcessBatchById(String domain, UUID batchId) { ... }

// Get processes in batch
@Transactional(readOnly = true)
public List<ProcessView> getProcessBatchProcesses(String domain, UUID batchId,
        ProcessStatus status, int limit, int offset) { ... }

// Refresh process batch stats
@Transactional
public void refreshProcessBatchStats(String domain, UUID batchId) {
    // Count processes by status and update batch metadata
}
```

---

## Part 6: Process Batch Controller

### 6.1 Web Controller

**File**: `src/test/java/com/ivamare/commandbus/e2e/controller/ProcessBatchController.java`

```java
@Controller
@RequestMapping("/process-batches")
public class ProcessBatchController {

    @GetMapping
    public String listProcessBatches(Model model) {
        // List all process batches
        return "pages/process_batches";
    }

    @GetMapping("/new")
    public String newProcessBatchForm(Model model) {
        model.addAttribute("request", ProcessBatchCreateRequest.defaults());
        model.addAttribute("outputTypes", OutputType.values());
        return "pages/process_batch_new";
    }

    @PostMapping
    public String createProcessBatch(ProcessBatchCreateRequest request,
            RedirectAttributes redirectAttributes) {
        UUID batchId = e2eService.createProcessBatch(domain, request);
        redirectAttributes.addFlashAttribute("success", "Process batch created");
        return "redirect:/process-batches/" + batchId;
    }

    @GetMapping("/{batchId}")
    public String processBatchDetail(@PathVariable UUID batchId, Model model) {
        e2eService.refreshProcessBatchStats(domain, batchId);
        // Get batch details and processes
        return "pages/process_batch_detail";
    }
}
```

---

## Part 7: UI Templates

### 7.1 Process Batch List Page

**File**: `src/test/resources/templates/e2e/pages/process_batches.html`

```html
<!-- List of process batches with:
     - Batch ID, Name, Status badge
     - Progress bar (completed/total)
     - Created timestamp
     - Link to detail page
     - "New Batch" button -->
```

### 7.2 Process Batch Creation Form

**File**: `src/test/resources/templates/e2e/pages/process_batch_new.html`

```html
<!-- Form with fields:
     - Batch name
     - Process count
     - Date range (from/to)
     - Account count per process
     - Output type dropdown (PDF/HTML/CSV)
     - Per-step behavior configuration (collapsible)
       - Query: failPermanent%, failTransient%, failBusinessRule%, timeout%, duration range
       - Aggregation: same fields
       - Render: same fields
     - Submit button -->
```

### 7.3 Process Batch Detail Page

**File**: `src/test/resources/templates/e2e/pages/process_batch_detail.html`

```html
<!-- Batch details:
     - Batch ID, Status badge
     - Progress bar with counts
     - Created/Completed timestamps
     - Duration (first started to last completed)

     Process table:
     - Process ID, Status, Current Step
     - Created timestamp
     - Clickable rows to process detail

     Auto-refresh while in progress -->
```

### 7.4 Navigation Update

**File**: `src/test/resources/templates/e2e/layout/main.html`

Add "Process Batches" link to sidebar navigation.

---

## Part 8: Database Support

### 8.1 Batch Type Column

Ensure `commandbus.batch` table has `batch_type` column to distinguish command batches from process batches:

```sql
ALTER TABLE commandbus.batch ADD COLUMN IF NOT EXISTS batch_type VARCHAR(20) DEFAULT 'COMMAND';
```

**File**: Add migration `V00X__add_batch_type.sql` if not exists.

### 8.2 Process-Batch Relationship

Processes are linked to batches via `batch_id` column in `commandbus.process` table.

---

## Part 9: Files to Create/Modify

### New Files

| File | Description |
|------|-------------|
| `src/test/java/com/ivamare/commandbus/e2e/process/StatementReportState.java` | Process state record |
| `src/test/java/com/ivamare/commandbus/e2e/process/StatementReportStep.java` | Process step enum |
| `src/test/java/com/ivamare/commandbus/e2e/process/StatementReportProcessManager.java` | Process manager implementation |
| `src/test/java/com/ivamare/commandbus/e2e/process/OutputType.java` | Output type enum (PDF/HTML/CSV) |
| `src/test/java/com/ivamare/commandbus/e2e/process/StepBehavior.java` | Per-step behavior config |
| `src/test/java/com/ivamare/commandbus/e2e/handlers/ReportingHandlers.java` | Reporting domain handlers |
| `src/test/java/com/ivamare/commandbus/e2e/controller/ProcessBatchController.java` | Web controller |
| `src/test/java/com/ivamare/commandbus/e2e/dto/ProcessBatchCreateRequest.java` | Request DTO |
| `src/test/java/com/ivamare/commandbus/e2e/dto/ProcessBatchView.java` | View DTO |
| `src/test/resources/templates/e2e/pages/process_batches.html` | List page |
| `src/test/resources/templates/e2e/pages/process_batch_new.html` | Creation form |
| `src/test/resources/templates/e2e/pages/process_batch_detail.html` | Detail page |

### Modified Files

| File | Changes |
|------|---------|
| `src/test/java/com/ivamare/commandbus/e2e/service/E2EService.java` | Add process batch methods |
| `src/test/java/com/ivamare/commandbus/e2e/E2ETestApplication.java` | Configure process reply router |
| `src/test/resources/templates/e2e/layout/main.html` | Add navigation link |
| `src/test/resources/application-e2e.yml` | Multi-domain configuration |
| `src/test/resources/application-worker.yml` | Process configuration |
| `src/main/resources/db/migration/V00X__add_batch_type.sql` | Migration (if needed) |

---

## Part 10: Implementation Order

1. **Process definitions** - State, Step enum, OutputType enum
2. **Process manager** - StatementReportProcessManager extending BaseProcessManager
3. **Reporting handlers** - StatementQuery, StatementDataAggregation, StatementRender
4. **DTOs** - ProcessBatchCreateRequest, ProcessBatchView, StepBehavior
5. **Service methods** - createProcessBatch, getProcessBatches, etc.
6. **Controller** - ProcessBatchController
7. **Templates** - List, form, detail pages
8. **Configuration** - Worker and process reply router setup
9. **Navigation** - Update main layout

---

## Part 11: Verification

### Manual Testing

```bash
# Start UI application
./scripts/run-demo-ui.sh

# Start workers (handles both test and reporting domains)
./scripts/run-demo-workers.sh 2 8

# Navigate to http://localhost:8080/process-batches/new
# Create a batch with:
#   - 10 processes
#   - Date range: last month to today
#   - 5 accounts per process
#   - Output: PDF
#   - Query step: 10% transient failure
#   - Other steps: 0% failure

# Verify:
# - Batch appears in list with PENDING status
# - Progress bar updates as processes complete
# - Detail page shows individual process status
# - Clicking process shows 3-step workflow progress
# - Failed processes appear in appropriate state
```

### Integration Test

Create integration test that:
1. Creates a process batch via service
2. Starts worker to process commands
3. Verifies all processes complete or fail as expected
4. Verifies batch status updates correctly

---

## Part 12: Key Differences from Python

| Aspect | Python | Java |
|--------|--------|------|
| Async/Sync | Async handlers default | Sync handlers with virtual threads |
| Framework | FastAPI + Jinja2 | Spring Boot + Thymeleaf |
| Handler registration | Decorators | @PostConstruct with registry |
| Process state | Dataclass | Java record |
| Configuration | TOML/Database | YAML properties |
| Pool management | Dynamic pool sizing | HikariCP defaults |

---

## Appendix: Python Reference Code

### StatementReportProcess (Python)

```python
# /Users/igormusic/code/rcmd/tests/e2e/app/process/statement_report.py

class StatementReportStep(str, Enum):
    QUERY = "StatementQuery"
    AGGREGATION = "StatementDataAggregation"
    RENDER = "StatementRender"

@dataclass
class StatementReportState(ProcessState):
    from_date: date
    to_date: date
    account_list: list[str]
    output_type: OutputType
    query_result_path: str | None = None
    aggregated_data_path: str | None = None
    rendered_file_path: str | None = None
    behavior: dict[str, Any] | None = None

class StatementReportProcess(BaseProcessManager[StatementReportState, StatementReportStep]):
    def __init__(self, pool, command_bus, process_repo):
        super().__init__(
            domain="reporting",
            process_type="StatementReport",
            step_class=StatementReportStep,
            pool=pool,
            command_bus=command_bus,
            process_repo=process_repo
        )
```

### Process Batch API (Python)

```python
# /Users/igormusic/code/rcmd/tests/e2e/app/api/routes.py

@api_router.post("/processes/batch", status_code=201)
async def create_process_batch(request: ProcessBatchCreateRequest, ...):
    batch_id = uuid4()
    # Create batch metadata with batch_type='PROCESS'
    # Create processes in chunks of 500
    # Link processes to batch via batch_id
    return ProcessBatchCreateResponse(batch_id=batch_id, count=request.count)
```
