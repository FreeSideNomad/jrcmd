# Connection Pool Configuration for High Availability

This guide covers HikariCP configuration for rapid recovery from database outages, with specific recommendations for commandbus-spring applications including workers, API servers, and process managers.

## Overview

When a database goes down unexpectedly and comes back up, applications using connection pools can experience prolonged downtime if not properly configured. The goal is to achieve **recovery within 60 seconds** of the database becoming available again.

### The Problem

When a database fails abruptly (not graceful shutdown):
1. TCP connections become "stuck" waiting for acknowledgments that never arrive
2. HikariCP can only recover connections **currently in the pool** - borrowed connections are outside its control
3. Without proper timeouts, the OS-level TCP timeout can be **several hours**
4. The connection pool may appear exhausted, causing application failures

### The Solution

Configure multiple layers of timeouts and keepalives:
1. **Driver-level socket timeout** - Detect dead connections quickly
2. **TCP keepalive** - Probe idle connections for liveness
3. **HikariCP keepalive** - Actively test pooled connections
4. **Connection lifecycle** - Force connection recycling

---

## Aggressive Recovery Configuration (< 60 seconds)

### Application Properties

```yaml
spring:
  datasource:
    # JDBC URL with aggressive timeout settings
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:commandbus}?socketTimeout=15&connectTimeout=5&tcpKeepAlive=true&cancelSignalTimeout=5

    hikari:
      # Pool sizing
      maximum-pool-size: 20
      minimum-idle: 5

      # Connection acquisition (how long to wait for a connection from pool)
      connection-timeout: 10000        # 10 seconds

      # Validation (how long to test if connection is alive)
      validation-timeout: 3000         # 3 seconds

      # Idle connection management
      idle-timeout: 60000              # 1 minute - evict idle connections quickly

      # Force connection recycling (prevents stale connections)
      max-lifetime: 300000             # 5 minutes

      # Active keepalive probing of idle connections
      keepalive-time: 30000            # 30 seconds - ping idle connections frequently

      # Connection test query (for drivers that don't support JDBC4 isValid())
      connection-test-query: SELECT 1
```

### JDBC URL Parameters Explained

| Parameter | Value | Purpose |
|-----------|-------|---------|
| `socketTimeout` | 15 | Seconds to wait for socket read operations. Detects dead connections during queries. |
| `connectTimeout` | 5 | Seconds to wait for initial connection establishment. |
| `tcpKeepAlive` | true | Enable TCP keepalive probes at OS level. |
| `cancelSignalTimeout` | 5 | Seconds to wait when canceling a query. |

### HikariCP Parameters Explained

| Parameter | Value | Purpose |
|-----------|-------|---------|
| `connection-timeout` | 10s | Max time to wait for a connection from the pool. Fails fast if pool exhausted. |
| `validation-timeout` | 3s | Max time for `isValid()` check. Short timeout detects dead connections quickly. |
| `idle-timeout` | 1min | How long a connection can sit idle before eviction. Short value cleans up stale connections. |
| `max-lifetime` | 5min | Force close connections older than this. Ensures fresh connections. |
| `keepalive-time` | 30s | How often to ping idle connections. Must be < `max-lifetime`. |

### Recovery Timeline

With this configuration:

| Event | Time | Action |
|-------|------|--------|
| Database fails | T+0 | - |
| Idle connection probed | T+0 to T+30s | `keepalive-time` detects dead idle connections |
| In-use connection fails | T+15s max | `socketTimeout` triggers SQLException |
| Connection evicted | Immediate | HikariCP removes failed connection |
| New connection attempted | Immediate | On next borrow request |
| Database restored | T+X | - |
| First successful connection | T+X+5s | `connectTimeout` for new connection |
| Pool recovered | T+X+10s | New connections created as needed |

**Total recovery time after DB available: ~10-15 seconds**

---

## Application Design Guidelines

### DO: Implement Retry Logic in Workers

The `DefaultWorker` and `ProcessStepWorker` in this codebase use polling loops with error handling. Database errors should trigger a backoff, not crash the worker.

```java
// GOOD: Worker with connection error resilience
private void runWithPolling() {
    while (running.get() && !stopping.get()) {
        try {
            drainQueue();
            sleep(pollIntervalMs);
        } catch (DataAccessException e) {
            // Database connection error - back off and retry
            log.warn("Database error in worker loop, backing off: {}", e.getMessage());
            sleep(backoffMs);  // Wait before retrying
        }
    }
}
```

### DO: Use Short Transactions

Keep database transactions short to minimize the window where a connection can become stuck.

```java
// GOOD: Short, focused transaction
@Transactional
public void processCommand(UUID commandId) {
    Command cmd = commandRepository.findById(commandId);
    cmd.markProcessed();
    commandRepository.save(cmd);
}

// BAD: Long transaction with external calls
@Transactional
public void processCommand(UUID commandId) {
    Command cmd = commandRepository.findById(commandId);
    externalService.call(cmd);  // Can take minutes!
    commandRepository.save(cmd);
}
```

### DO: Use Idempotent Operations

Design operations to be safely retryable. When a connection fails mid-operation, the retry should not cause data corruption.

```java
// GOOD: Idempotent update using optimistic locking
UPDATE command_metadata
SET status = 'COMPLETED', version = version + 1
WHERE command_id = ? AND version = ?

// GOOD: Use INSERT ON CONFLICT for safe retries
INSERT INTO audit_log (event_id, event_type, payload)
VALUES (?, ?, ?)
ON CONFLICT (event_id) DO NOTHING
```

### DO: Separate Read and Write Pools (Advanced)

For high-availability setups with read replicas:

```yaml
spring:
  datasource:
    primary:
      url: jdbc:postgresql://primary:5432/db?socketTimeout=15&tcpKeepAlive=true
      hikari:
        pool-name: primary-pool
        maximum-pool-size: 10
    replica:
      url: jdbc:postgresql://replica:5432/db?socketTimeout=15&tcpKeepAlive=true
      hikari:
        pool-name: replica-pool
        maximum-pool-size: 20
```

### DO: Handle Connection Errors Gracefully in APIs

Return appropriate HTTP status codes when database is unavailable:

```java
@RestController
public class PaymentController {

    @GetMapping("/payments/{id}")
    public ResponseEntity<Payment> getPayment(@PathVariable UUID id) {
        try {
            return ResponseEntity.ok(paymentService.findById(id));
        } catch (DataAccessResourceFailureException e) {
            // Database unavailable - return 503 Service Unavailable
            log.error("Database unavailable: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "30")
                .build();
        }
    }
}
```

### DON'T: Hold Connections During External Calls

Never keep a database connection while waiting for external services:

```java
// BAD: Holds connection while waiting for HTTP response
@Transactional
public void processPayment(Payment payment) {
    paymentRepository.save(payment);
    PaymentResult result = externalPaymentGateway.submit(payment);  // Blocks!
    payment.setResult(result);
    paymentRepository.save(payment);
}

// GOOD: Release connection between operations
public void processPayment(Payment payment) {
    paymentRepository.save(payment);  // Transaction 1 - completes quickly

    PaymentResult result = externalPaymentGateway.submit(payment);  // No DB connection held

    payment.setResult(result);
    paymentRepository.save(payment);  // Transaction 2 - separate connection
}
```

### DON'T: Use Long Visibility Timeouts Without Socket Timeouts

If using PGMQ with long visibility timeouts, ensure socket timeouts are configured:

```java
// Visibility timeout of 5 minutes means connection could be held that long
// Without socketTimeout, a dead connection blocks for OS TCP timeout (hours)
List<PgmqMessage> messages = pgmqClient.read(queueName, 300, batchSize);
```

### DON'T: Ignore Connection Test Failures

Log and alert on repeated connection failures:

```java
// Configure HikariCP exception handler (via MeterRegistry or custom)
hikariDataSource.setHealthCheckRegistry(healthCheckRegistry);
hikariDataSource.setMetricRegistry(metricRegistry);
```

### DON'T: Use Connection Pooling for LISTEN/NOTIFY Connections

The `ProcessReplyRouter` correctly uses a dedicated connection for LISTEN:

```java
// GOOD: Dedicated connection for LISTEN (not from pool)
try (Connection listenConn = dataSource.getConnection()) {
    listenConn.setAutoCommit(true);
    try (var stmt = listenConn.createStatement()) {
        stmt.execute("LISTEN " + channel);
    }
    // This connection is held for the lifetime of the listener
    // It should have its own timeout handling
}
```

---

## Worker-Specific Recommendations

### DefaultWorker Recovery

The `DefaultWorker` handles errors in its loop but should be enhanced for connection resilience:

```java
// Recommended error handling pattern for workers
private void runWithPolling() {
    int consecutiveErrors = 0;

    while (running.get() && !stopping.get()) {
        try {
            drainQueue();
            consecutiveErrors = 0;  // Reset on success
            sleep(pollIntervalMs);
        } catch (Exception e) {
            consecutiveErrors++;
            if (!stopping.get()) {
                log.error("Error in worker loop (consecutive: {}): {}",
                    consecutiveErrors, e.getMessage());

                // Exponential backoff: 1s, 2s, 4s, 8s, max 30s
                long backoff = Math.min(1000L * (1L << Math.min(consecutiveErrors - 1, 4)), 30000);
                sleep(backoff);
            }
        }
    }
}
```

### ProcessStepWorker Recovery

The `ProcessStepWorker` polls multiple managers. Each poll should be independent:

```java
// Each manager poll is wrapped in try-catch (already in codebase)
for (var pm : processManagers) {
    try {
        List<UUID> claimedProcesses = processRepo.claimPendingProcesses(...);
        // Process...
    } catch (Exception e) {
        // Log and continue to next manager - don't fail entire poll cycle
        log.error("Error polling pending processes for domain {}: {}",
            pm.getDomain(), e.getMessage());
    }
}
```

### ProcessReplyRouter Recovery

For NOTIFY mode, the dedicated LISTEN connection needs special handling:

```java
private void runWithNotify() throws SQLException, InterruptedException {
    while (running.get() && !stopping.get()) {
        try (Connection listenConn = dataSource.getConnection()) {
            // Setup LISTEN...
            while (running.get() && !stopping.get()) {
                drainQueue();
                pgConn.getNotifications((int) pollIntervalMs);
            }
        } catch (SQLException e) {
            if (!stopping.get()) {
                log.warn("LISTEN connection lost, reconnecting: {}", e.getMessage());
                sleep(5000);  // Wait before reconnecting
            }
        }
    }
}
```

---

## OS-Level TCP Keepalive (Linux)

For defense in depth, configure OS-level TCP keepalives:

```bash
# Detect dead connections in ~70 seconds (60 + 5*2)
# Add to /etc/sysctl.conf or run with sysctl -w

# Start probing after 60 seconds of idle
net.ipv4.tcp_keepalive_time = 60

# Send probe every 5 seconds
net.ipv4.tcp_keepalive_intvl = 5

# Give up after 2 failed probes
net.ipv4.tcp_keepalive_probes = 2
```

Apply without reboot: `sudo sysctl -p`

---

## Health Checks and Monitoring

### Spring Boot Actuator

```yaml
management:
  endpoint:
    health:
      show-details: always
  health:
    db:
      enabled: true
```

### Custom Worker Health Indicator

The codebase includes `WorkerHealthIndicator` - ensure it reports database connectivity:

```java
@Component
public class WorkerHealthIndicator implements HealthIndicator {

    private final DataSource dataSource;
    private final Worker worker;

    @Override
    public Health health() {
        // Check if worker is running
        if (!worker.isRunning()) {
            return Health.down().withDetail("worker", "stopped").build();
        }

        // Quick database connectivity check
        try (Connection conn = dataSource.getConnection()) {
            if (!conn.isValid(3)) {
                return Health.down().withDetail("database", "invalid connection").build();
            }
        } catch (SQLException e) {
            return Health.down().withDetail("database", e.getMessage()).build();
        }

        return Health.up()
            .withDetail("inFlight", worker.inFlightCount())
            .build();
    }
}
```

### Metrics to Monitor

```java
// HikariCP exposes these metrics automatically with Micrometer
// Key metrics to alert on:
hikaricp_connections_active        // Currently borrowed connections
hikaricp_connections_idle          // Available connections
hikaricp_connections_pending       // Threads waiting for connection
hikaricp_connections_timeout_total // Connection acquisition timeouts
hikaricp_connections_creation_seconds // Time to create new connections
```

**Alert thresholds:**
- `hikaricp_connections_pending > 0` for > 30 seconds → Pool exhaustion warning
- `hikaricp_connections_timeout_total` increasing → Database connectivity issue
- `hikaricp_connections_creation_seconds` > 5 seconds → Network/database latency issue

---

## Summary: Quick Reference

### Minimum Configuration for 60-Second Recovery

```yaml
spring:
  datasource:
    url: jdbc:postgresql://host:5432/db?socketTimeout=15&connectTimeout=5&tcpKeepAlive=true
    hikari:
      connection-timeout: 10000
      validation-timeout: 3000
      idle-timeout: 60000
      max-lifetime: 300000
      keepalive-time: 30000
```

### Application Design Checklist

- [ ] Workers have error handling with exponential backoff
- [ ] Transactions are short (< 1 second)
- [ ] No external HTTP calls inside transactions
- [ ] Operations are idempotent and retryable
- [ ] APIs return 503 with Retry-After on database errors
- [ ] LISTEN connections have reconnection logic
- [ ] Health checks verify database connectivity
- [ ] Metrics and alerting configured for pool exhaustion

### Testing Recovery

1. Start application with aggressive config
2. Run workload (API requests, worker processing)
3. Kill database process (`pg_ctl stop -m immediate`)
4. Wait 10 minutes
5. Start database (`pg_ctl start`)
6. Verify application recovers within 60 seconds
7. Verify no data loss or corruption

---

## Library Resilience Configuration

The commandbus-spring library includes built-in resilience for database errors with exponential backoff. Configure via application properties:

### Configuration Properties

```yaml
commandbus:
  worker:
    auto-start: true
    resilience:
      initial-backoff-ms: 1000      # First retry delay (default: 1 second)
      max-backoff-ms: 30000         # Maximum backoff cap (default: 30 seconds)
      backoff-multiplier: 2.0       # Exponential multiplier (default: 2x)
      error-threshold: 5            # Log ERROR level after this many consecutive failures

  process:
    enabled: true
    resilience:
      initial-backoff-ms: 1000
      max-backoff-ms: 30000
      backoff-multiplier: 2.0
      error-threshold: 5
```

### Backoff Behavior

With default settings (1s initial, 2x multiplier, 30s max):

| Consecutive Errors | Backoff Delay |
|-------------------|---------------|
| 1st error | 1s |
| 2nd error | 2s |
| 3rd error | 4s |
| 4th error | 8s |
| 5th error | 16s (+ logs at ERROR level) |
| 6th+ error | 30s (capped) |

A 10% jitter is applied to prevent thundering herd after mass recovery.

### Components with Resilience

| Component | Behavior |
|-----------|----------|
| `DefaultWorker` | Polling loop backs off on database errors. NOTIFY mode reconnects automatically. |
| `ProcessStepWorker` | All poll methods (pending, retry, timeout, deadline) have independent backoff. |
| `ProcessReplyRouter` | NOTIFY connection wrapped in reconnection loop. Polling has backoff. |

### Exception Classification

The library classifies exceptions as transient (retryable) based on:

- **SQL State Codes**: Connection errors (08xxx), resource errors (53xxx), shutdown (57xxx), serialization/deadlock (40xxx)
- **Spring Classifications**: `TransientDataAccessException` hierarchy
- **Message Patterns**: "connection reset", "broken pipe", "connection refused", "socket timeout"

Transient errors trigger exponential backoff. Non-transient errors are logged at ERROR level without backoff.

### Health Indicators

The library provides health indicators that expose resilience state:

```json
// GET /actuator/health
{
  "status": "UP",
  "components": {
    "commandBus": {
      "status": "UP",
      "details": {
        "pgmq": "available",
        "pendingCommands": 5,
        "pool.active": 3,
        "pool.idle": 7,
        "pool.pending": 0
      }
    },
    "workers": {
      "status": "UP",
      "details": {
        "workers": {
          "payments": { "running": true, "inFlight": 2, "consecutiveErrors": 0 }
        },
        "maxConsecutiveErrors": 0
      }
    }
  }
}
```

The worker health indicator reports DOWN if:
- Any worker is not running
- `maxConsecutiveErrors >= 5` across all workers

---

## References

- [HikariCP Rapid Recovery Guide](https://github.com/brettwooldridge/HikariCP/wiki/Rapid-Recovery)
- [HikariCP TCP Keepalive Configuration](https://github.com/brettwooldridge/HikariCP/wiki/Setting-Driver-or-OS-TCP-Keepalive)
- [PostgreSQL JDBC Driver Parameters](https://jdbc.postgresql.org/documentation/use/)
- [Spring Boot HikariCP Configuration](https://docs.spring.io/spring-boot/docs/current/reference/html/application-properties.html#application-properties.data.spring.datasource.hikari)
