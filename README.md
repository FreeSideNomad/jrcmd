# Command Bus Spring

A reliable command bus implementation for Spring Boot applications using PostgreSQL and [PGMQ](https://github.com/tembo-io/pgmq) for durable message queuing.

**Latest Version: [v0.4.0](RELEASE_NOTES.md)** | [Release Notes](RELEASE_NOTES.md)

## Features

- **Reliable command processing** with exactly-once delivery semantics via PGMQ
- **Automatic retry** with configurable backoff schedules
- **Troubleshooting Queue (TSQ)** for operator intervention on failed commands
- **Batch operations** for efficient bulk command processing
- **Process Manager** for multi-step workflow orchestration with saga pattern
- **Spring Boot auto-configuration** for zero-boilerplate setup
- **Virtual threads** (Java 21+) for high concurrency
- **Wire-compatible** with Python `commandbus` library

## Requirements

- Java 21+
- Spring Boot 3.x
- PostgreSQL 15+ with [PGMQ extension](https://github.com/tembo-io/pgmq)

## Installation

Add the dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>com.ivamare</groupId>
    <artifactId>commandbus</artifactId>
    <version>0.4.0</version>
</dependency>
```

Or for Gradle:

```groovy
implementation 'com.ivamare:commandbus:0.4.0'
```

### Database Setup

Ensure PGMQ is installed in your PostgreSQL database:

```sql
CREATE EXTENSION IF NOT EXISTS pgmq;
```

The library includes SQL migration scripts in `db/migration/` directory. You can use these with Flyway or any other migration tool. If using Flyway, add it to your dependencies and configure:

```yaml
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration
```

## Quick Start

### 1. Configure Application

```yaml
# application.yml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/mydb
    username: postgres
    password: postgres

commandbus:
  enabled: true
  domain: myapp
  worker:
    auto-start: true
    concurrency: 4
    visibility-timeout: 30
```

### 2. Define a Command Handler

```java
@Component
public class PaymentHandlers {

    private final HandlerRegistry handlerRegistry;

    public PaymentHandlers(HandlerRegistry handlerRegistry) {
        this.handlerRegistry = handlerRegistry;
    }

    @PostConstruct
    public void registerHandlers() {
        handlerRegistry.register("payments", "DebitAccount", this::handleDebit);
        handlerRegistry.register("payments", "CreditAccount", this::handleCredit);
    }

    public Map<String, Object> handleDebit(Command command, HandlerContext context) {
        String accountId = (String) command.data().get("accountId");
        int amount = (Integer) command.data().get("amount");

        // Process the debit...

        return Map.of(
            "status", "debited",
            "accountId", accountId,
            "newBalance", 900
        );
    }

    public Map<String, Object> handleCredit(Command command, HandlerContext context) {
        // Process the credit...
        return Map.of("status", "credited");
    }
}
```

Alternatively, use the `@Handler` annotation:

```java
@Component
public class PaymentHandlers {

    @Handler(domain = "payments", commandType = "DebitAccount")
    public Map<String, Object> handleDebit(Command command, HandlerContext context) {
        // Process the debit...
        return Map.of("status", "debited");
    }
}
```

### 3. Send Commands

```java
@Service
public class PaymentService {

    private final CommandBus commandBus;

    public PaymentService(CommandBus commandBus) {
        this.commandBus = commandBus;
    }

    public void processPayment(String accountId, int amount) {
        UUID commandId = UUID.randomUUID();

        commandBus.send(
            "payments",           // domain
            "DebitAccount",       // command type
            commandId,            // unique command ID
            Map.of(
                "accountId", accountId,
                "amount", amount
            )
        );
    }
}
```

## Configuration Reference

```yaml
commandbus:
  enabled: true                    # Enable/disable the command bus (default: true)
  domain: myapp                    # Default domain for commands
  default-max-attempts: 3          # Max retry attempts (default: 3)
  backoff-schedule: [10, 60, 300]  # Retry delays in seconds

  worker:
    auto-start: true               # Auto-start workers (default: false)
    visibility-timeout: 30         # Message lock timeout in seconds
    poll-interval-ms: 1000         # Polling interval when no messages
    concurrency: 4                 # Number of concurrent handler threads
    use-notify: true               # Use PostgreSQL NOTIFY for instant wake-up

  batch:
    default-chunk-size: 100        # Commands per transaction in batch operations

  process:
    enabled: false                 # Enable process manager (default: false)
    domain: myapp                  # Domain for process commands
    reply-queue: myapp__replies    # Reply queue for process coordination
    auto-start: false              # Auto-start process reply handler
```

## Core Concepts

### Commands

A command represents a unit of work to be processed:

```java
record Command(
    String domain,           // e.g., "payments"
    String commandType,      // e.g., "DebitAccount"
    UUID commandId,          // Unique identifier
    Map<String, Object> data,// Payload
    UUID correlationId,      // For reply routing
    String replyTo,          // Reply queue name
    Instant createdAt
)
```

### Handlers

Handlers process commands and return results:

```java
public Map<String, Object> handleCommand(Command command, HandlerContext context) {
    // context.attempt() - current attempt number (1-based)
    // context.maxAttempts() - maximum retry attempts
    // context.isLastAttempt() - true if this is the final attempt
    // context.extendVisibility(Duration) - extend processing time

    return Map.of("result", "success");
}
```

### Exception Handling

The library uses exceptions to control retry behavior:

| Exception | Behavior |
|-----------|----------|
| `TransientCommandException` | Retry with backoff |
| `PermanentCommandException` | Move to TSQ immediately |
| `BusinessRuleException` | Move to TSQ (or auto-compensate in processes) |
| Other exceptions | Retry with backoff |

```java
// Retryable failure (network timeout, temporary unavailability)
throw new TransientCommandException("TIMEOUT", "Service temporarily unavailable");

// Non-retryable failure (validation error)
throw new PermanentCommandException("INVALID_DATA", "Account ID is required");

// Business rule violation (in process managers, triggers compensation)
throw new BusinessRuleException("INSUFFICIENT_FUNDS", "Account balance too low");
```

### Troubleshooting Queue (TSQ)

Commands that exhaust retries or fail permanently go to the TSQ for operator intervention:

```java
@Service
public class OperatorService {

    private final TroubleshootingQueue tsq;

    // List failed commands
    public List<TroubleshootingItem> listFailed(String domain) {
        return tsq.listTroubleshooting(domain, null, 100, 0);
    }

    // Retry a failed command
    public void retry(String domain, UUID commandId, String operator) {
        tsq.operatorRetry(domain, commandId, operator);
    }

    // Cancel a command (mark as failed, don't retry)
    public void cancel(String domain, UUID commandId, String reason, String operator) {
        tsq.operatorCancel(domain, commandId, reason, operator);
    }

    // Manually complete a command with result data
    public void complete(String domain, UUID commandId, Map<String, Object> result, String operator) {
        tsq.operatorComplete(domain, commandId, result, operator);
    }
}
```

## Batch Operations

For bulk operations, use batch APIs for better performance:

```java
// Create a batch atomically
List<BatchCommand> commands = new ArrayList<>();
for (Order order : orders) {
    commands.add(new BatchCommand(
        "ProcessOrder",
        UUID.randomUUID(),
        Map.of("orderId", order.getId()),
        null,  // correlationId
        null,  // replyTo
        null   // maxAttempts (use default)
    ));
}

CreateBatchResult result = commandBus.createBatch("orders", commands);
UUID batchId = result.batchId();

// Monitor batch progress
BatchMetadata batch = commandBus.getBatch("orders", batchId);
System.out.println("Completed: " + batch.completedCount() + "/" + batch.totalCount());
```

## Process Manager (Multi-Step Workflows)

For complex workflows requiring multiple steps with saga-style compensation:

### 1. Define Process State

```java
public record StatementReportState(
    String fromDate,
    String toDate,
    List<String> accountList,
    String outputType,
    // Step results
    Map<String, Object> queryResult,
    Map<String, Object> aggregationResult,
    String renderedOutput
) implements ProcessState {

    @Override
    public Map<String, Object> toMap() {
        return Map.of(
            "from_date", fromDate,
            "to_date", toDate,
            "account_list", accountList,
            "output_type", outputType,
            "query_result", queryResult != null ? queryResult : Map.of(),
            "aggregation_result", aggregationResult != null ? aggregationResult : Map.of(),
            "rendered_output", renderedOutput != null ? renderedOutput : ""
        );
    }

    public static StatementReportState fromMap(Map<String, Object> data) {
        return new StatementReportState(
            (String) data.get("from_date"),
            (String) data.get("to_date"),
            (List<String>) data.get("account_list"),
            (String) data.get("output_type"),
            (Map<String, Object>) data.get("query_result"),
            (Map<String, Object>) data.get("aggregation_result"),
            (String) data.get("rendered_output")
        );
    }

    // Immutable update methods
    public StatementReportState withQueryResult(Map<String, Object> result) {
        return new StatementReportState(fromDate, toDate, accountList, outputType,
            result, aggregationResult, renderedOutput);
    }
}
```

### 2. Define Steps

```java
public enum ReportStep {
    QUERY,
    AGGREGATION,
    RENDER
}
```

### 3. Implement Process Manager

```java
@Component
public class StatementReportProcessManager
        extends BaseProcessManager<StatementReportState, ReportStep> {

    @Override
    public String getProcessType() { return "StatementReport"; }

    @Override
    public String getDomain() { return "reporting"; }

    @Override
    public StatementReportState createInitialState(Map<String, Object> initialData) {
        return new StatementReportState(
            (String) initialData.get("from_date"),
            (String) initialData.get("to_date"),
            (List<String>) initialData.get("account_list"),
            (String) initialData.get("output_type"),
            null, null, null
        );
    }

    @Override
    public ReportStep getFirstStep(StatementReportState state) {
        return ReportStep.QUERY;
    }

    @Override
    public ProcessCommand<?> buildCommand(ReportStep step, StatementReportState state) {
        return switch (step) {
            case QUERY -> new ProcessCommand<>(
                "StatementQuery",
                Map.of(
                    "from_date", state.fromDate(),
                    "to_date", state.toDate(),
                    "accounts", state.accountList()
                )
            );
            case AGGREGATION -> new ProcessCommand<>(
                "StatementDataAggregation",
                Map.of("data", state.queryResult())
            );
            case RENDER -> new ProcessCommand<>(
                "StatementRender",
                Map.of(
                    "data", state.aggregationResult(),
                    "format", state.outputType()
                )
            );
        };
    }

    @Override
    public StatementReportState updateState(StatementReportState state, ReportStep step, Reply reply) {
        return switch (step) {
            case QUERY -> state.withQueryResult(reply.data());
            case AGGREGATION -> state.withAggregationResult(reply.data());
            case RENDER -> state.withRenderedOutput((String) reply.data().get("output"));
        };
    }

    @Override
    public ReportStep getNextStep(ReportStep currentStep, Reply reply, StatementReportState state) {
        return switch (currentStep) {
            case QUERY -> ReportStep.AGGREGATION;
            case AGGREGATION -> ReportStep.RENDER;
            case RENDER -> null;  // Process complete
        };
    }
}
```

### 4. Start a Process

```java
@Service
public class ReportService {

    private final StatementReportProcessManager processManager;

    public UUID generateReport(String fromDate, String toDate, List<String> accounts) {
        return processManager.start(Map.of(
            "from_date", fromDate,
            "to_date", toDate,
            "account_list", accounts,
            "output_type", "PDF"
        ));
    }
}
```

## Running the E2E Demo Application

The library includes a comprehensive test application demonstrating all features:

```bash
# Start PostgreSQL with PGMQ
docker-compose -f docker/docker-compose.yml up -d

# Run the demo UI
mvn spring-boot:test-run -Dspring-boot.run.profiles=e2e,ui

# In another terminal, start workers
mvn spring-boot:test-run -Dspring-boot.run.profiles=e2e,worker

# Access the UI at http://localhost:8080
```

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                     Application Layer                           │
├─────────────────────────────────────────────────────────────────┤
│  CommandBus    │  Worker      │  TSQ           │  ProcessManager│
│  - send()      │  - run()     │  - retry()     │  - start()     │
│  - batch()     │  - stop()    │  - cancel()    │  - handleReply │
└────────┬───────┴──────┬───────┴────────┬───────┴────────┬───────┘
         │              │                │                │
┌────────┴──────────────┴────────────────┴────────────────┴───────┐
│                      Domain Layer                               │
├─────────────────────────────────────────────────────────────────┤
│  HandlerRegistry  │  RetryPolicy  │  Command/Reply Models       │
└────────┬──────────┴───────┬───────┴─────────────────────────────┘
         │                  │
┌────────┴──────────────────┴─────────────────────────────────────┐
│                   Infrastructure Layer                          │
├─────────────────────────────────────────────────────────────────┤
│  PgmqClient    │  CommandRepository  │  BatchRepository         │
│  - send()      │  - save()           │  - get()                 │
│  - read()      │  - update()         │  - updateStats()         │
│  - archive()   │  - query()          │                          │
└────────────────┴────────────────────────────────────────────────┘
                              │
                    ┌─────────┴─────────┐
                    │   PostgreSQL      │
                    │   + PGMQ ext      │
                    └───────────────────┘
```

## Queue Naming Convention

- Command queues: `{domain}__commands` (e.g., `payments__commands`)
- Reply queues: `{domain}__replies` (e.g., `payments__replies`)
- Archive tables: `pgmq.a_{domain}__commands`

## License

MIT License - see [LICENSE](LICENSE) for details.

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes with tests
4. Ensure `mvn verify` passes (80% coverage required)
5. Submit a pull request

## Support

- [GitHub Issues](https://github.com/FreeSideNomad/jrcmd/issues)
- [Documentation](https://github.com/FreeSideNomad/jrcmd/tree/main/docs)
