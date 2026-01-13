# Release Notes

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
