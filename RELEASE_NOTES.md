# Release Notes

## v0.3.0

### Highlights

- **Connection Pool Resilience** - Automatic recovery from database outages with exponential backoff
- **ProcessStepManager** - Temporal.io-inspired workflow framework for long-running processes
- **Security Fix** - CVE-2025-68161 (log4j TLS vulnerability)

### New Features

#### Connection Pool Resilience (#115)
- Exponential backoff on transient database errors (connection lost, timeouts, pool exhaustion)
- `DatabaseExceptionClassifier` utility for classifying retryable exceptions
- Enhanced health indicators with HikariCP pool statistics
- Configurable backoff parameters via `commandbus.worker.resilience.*` properties
- Workers automatically recover within 60 seconds of database becoming available

#### ProcessStepManager - Workflow Framework (#102, #103, #104)
- Step-based workflow orchestration inspired by Temporal.io
- Support for approval workflows with `PENDING_APPROVAL` state
- Risk assessment with configurable risk types and decisions
- Execution model dispatch for approval and network controllers
- E2E testing support with TSQ integration
- UI integration for process creation and monitoring

#### Payment Processing Enhancements (#105, #106, #107)
- Improved audit logging with full coverage
- TSQ (Troubleshooting Queue) behavior injection fixes
- Payment network simulator integration

### Security Fixes

#### CVE-2025-68161 - Log4j TLS Vulnerability (#116)
- Upgraded log4j from 2.24.3 to 2.25.3
- Fixes TLS hostname verification vulnerability

### Dependency Updates
- Spring Boot 3.4.1 → 3.5.9
- PostgreSQL JDBC 42.7.8 → 42.7.9
- bucket4j 8.14.0 → 8.16.0
- Bootstrap 5.3.2 → 5.3.8
- htmx.org 1.9.10 → 2.0.8

### Documentation
- Comprehensive `DEVELOPER_GUIDE.md` for ProcessStepManager
- `connection-pool-config.md` with recommended HikariCP settings

### Breaking Changes

#### Flyway Migration Consolidation
Database migrations have been consolidated:
- V002-V007 merged into V001__commandbus_schema.sql
- V002__pgmq_queues.sql is now a separate file
- V003__e2e_schema.sql contains E2E test schema

**For existing deployments:**
1. If upgrading from v0.2.0, you must manually reconcile the flyway_schema_history table
2. Option A: Drop and recreate the schema (development environments)
3. Option B: Mark old migrations as deleted and add new ones (production)

```sql
-- Option B: Update flyway history for existing deployments
DELETE FROM flyway_schema_history WHERE version IN ('2', '3', '4', '5', '6', '7');
-- Then run flyway migrate
```

### Configuration

#### New Resilience Configuration Options

```yaml
commandbus:
  worker:
    resilience:
      initial-backoff-ms: 1000      # Starting backoff delay
      max-backoff-ms: 30000         # Maximum backoff cap
      backoff-multiplier: 2.0       # Exponential multiplier
      error-threshold: 5            # Log ERROR after N consecutive errors
```

---

## v0.2.0

### New Features

#### Batch Process Startup
- New `BaseProcessManager.startBatch(List<Map<String, Object>>)` method for starting multiple process instances in a single transaction
- Batch inserts for processes, commands, and audit entries
- Dramatically improved performance for high-volume scenarios (e.g., bulk payment processing)

#### Wait-Only Steps (Multi-Reply Patterns)
- New `BaseProcessManager.isWaitOnlyStep(TStep)` method for steps that wait for external replies without sending commands
- Enables payment status workflows with progressive confirmations (L1-L4 patterns)
- Steps can receive multiple replies from external systems

#### Atomic State Updates
- New `ProcessRepository.updateStateAtomic()` method using JSONB patch-based updates via stored procedure
- Eliminates read-modify-write race conditions for concurrent process updates
- Reduces lock contention in high-concurrency environments

#### Terminal State Handling
- New `BaseProcessManager.updateStateOnly()` method for late-arriving replies to completed/cancelled processes
- Replies after terminal status update state for audit purposes without changing process status

#### Archive Messages Configuration
- New `commandbus.process.archive-messages` property
- Option to archive processed messages instead of deleting them
- Useful for debugging and message tracing

### Improvements

- `ProcessStatus.isTerminal()` - Check if a process status is final (COMPLETED, COMPENSATED, FAILED, CANCELED)
- `ProcessReplyRouter` - Support for both "result" and "data" fields in reply messages for simulator compatibility
- `JdbcProcessRepository.updateStepReply()` - Insert audit entries for external replies (e.g., L1-L4 confirmations)
- `JdbcProcessRepository.saveBatch()` / `logBatchSteps()` - Batch operations for bulk process creation

### Migration Notes

No migration required. All changes are backward compatible with v0.1.0.

---

## v0.1.0

Initial release of commandbus-spring library.
