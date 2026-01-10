# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository Overview

This repository contains the implementation specification for `commandbus-spring`, a Java/Spring Boot command bus library using PostgreSQL with PGMQ extension for message queuing. The library provides command processing with retry policies, troubleshooting queues, and batch operations.

The Java implementation is designed to be wire-compatible with an existing Python `commandbus` library, sharing the same database schema, PGMQ message format, and queue naming conventions.

## Development Workflow

**All code changes must follow this workflow:**

1. **Create or link to a GitHub Issue** - Every change must be tied to a GitHub issue (feature, bug, user-story, or chore)
2. **Create a feature branch** - Branch from main with descriptive naming (e.g., `feature/123-add-retry-policy`, `fix/456-handler-timeout`)
3. **Implement and commit** - Pre-commit hook automatically runs tests with coverage validation (80% line/branch required)
4. **Push and verify CI** - After pushing, check GitHub Actions for any CI failures
5. **Create a Pull Request** - Use the PR template, link to the issue with "Closes #" or "Related to #"

## Pre-commit Hook Setup

Configure git to use the project hooks:

```bash
git config core.hooksPath .githooks
```

The pre-commit hook runs `mvn clean verify` which includes:
- Unit tests
- JaCoCo coverage check (80% line and branch coverage required)

## Build Commands

```bash
mvn clean install          # Build and install
mvn test                   # Run unit tests only
mvn verify                 # Full verification with coverage check
mvn jacoco:report          # Generate coverage report (target/site/jacoco/)
```

## CI/CD

GitHub Actions workflow (`.github/workflows/ci.yml`) runs on push to main and all PRs:
- Builds with JDK 21
- Runs tests against PostgreSQL with PGMQ extension
- Enforces 80% code coverage (line and branch)
- Posts coverage report to PRs

**After every push, verify CI passes before proceeding.**

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
- All tests pass (`mvn verify`)
- Coverage meets 80% threshold (line and branch)
- CI workflow passes (check GitHub Actions)
- Related issue linked in PR description
