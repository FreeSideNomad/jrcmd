# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository Overview

This repository contains the implementation specification for `commandbus-spring`, a Java/Spring Boot command bus library using PostgreSQL with PGMQ extension for message queuing. The library provides command processing with retry policies, troubleshooting queues, and batch operations.

The Java implementation is designed to be wire-compatible with an existing Python `commandbus` library, sharing the same database schema, PGMQ message format, and queue naming conventions.

## Development Workflow

**All code changes must follow this workflow:**

1. **Create or link to a GitHub Issue** - Every change must be tied to a GitHub issue (feature, bug, user-story, or chore)
2. **Create a feature branch** - Branch from main with descriptive naming (e.g., `feature/123-add-retry-policy`, `fix/456-handler-timeout`)
3. **Run pre-commit validations** before committing:
   - `make lint` - Code style checks
   - `make typecheck` - Type hint validation
   - `make test` - Run test suite
4. **Create a Pull Request** - Use the PR template, link to the issue with "Closes #" or "Related to #"

## Build Commands

```bash
# Once the Maven project is set up:
mvn clean install          # Build and install
mvn test                   # Run tests
mvn verify                 # Full verification including integration tests

# Code quality (when configured):
make lint                  # Run linting
make typecheck             # Type checking
make test                  # Run tests
```

## Architecture

### Component Layers

```
Application Layer (Shipped Library)
├── CommandBus      - Send commands
├── Worker          - Process messages
├── TroubleshootingQ - Operator operations
└── ProcessManager  - Long-running workflows

Domain Layer
├── HandlerRegistry - Handler dispatch
├── RetryPolicy     - Configurable retry behavior
└── Domain Models   - Command, Batch, Reply records

Infrastructure Layer
├── PgmqClient      - PGMQ SQL wrapper
├── Repositories    - JDBC data access
└── ProcessRepository

Database: PostgreSQL + PGMQ extension
```

### Key Design Principles

- **Interface-First**: All public APIs are interfaces with hidden implementations
- **Clean Domain Model**: Records with no persistence annotations
- **Immutability**: Java records for immutable data structures
- **Virtual Threads**: Java 21 virtual threads for concurrency

### Message Flow

**Send**: `CommandBus.send()` → Transaction (PGMQ message + CommandMetadata + audit) → NOTIFY

**Process**: `Worker.run()` → LISTEN/poll → `sp_receive_command()` → Handler dispatch → complete/fail/retry

### Queue Naming Convention

- Command queues: `{domain}__commands`
- Reply queues: `{domain}__replies`
- Archive tables: `pgmq.a_{domain}__commands`

## Specification Documents

Implementation specs are in `docs/java-spec/`:

| Spec | Description |
|------|-------------|
| 01-domain-models | Records, enums, value objects |
| 02-pgmq-client | PGMQ SQL wrapper |
| 03-repositories | JDBC data access layer |
| 04-handler-registry | Handler registration and dispatch |
| 05-worker | Message processing worker |
| 06-command-bus | Public API |
| 07-troubleshooting | TSQ operations |
| 08-spring-integration | Auto-configuration |
| 09-testing | Test utilities and fakes |
| 10-process | Long-running workflow orchestration |
| 11-admin-ui | E2E test application (test scope only) |

## GitHub Issue Templates

Use the appropriate issue template:
- `01-feature.yml` - New features
- `02-user-story.yml` - User stories with acceptance criteria
- `03-bug.yml` - Bug reports
- `04-chore.yml` - Maintenance tasks

## PR Checklist

Before requesting review, verify:
- Code passes `make lint` and `make typecheck`
- All tests pass (`make test`)
- Type hints added for all new functions
- Coverage not decreased
- Related issue linked in PR description
