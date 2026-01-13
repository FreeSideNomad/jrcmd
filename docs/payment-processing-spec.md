# Payment Processing Specification

This document specifies the payment processing workflow implemented as a process manager in the commandbus-spring library. The implementation serves as a realistic e2e testing sample demonstrating multi-step workflows with conditional branching, timeouts, and saga-style compensation.

## Overview

The payment processing workflow handles international and domestic payments with:
- Transaction risk assessment (with manual approval for high-value payments)
- Foreign exchange booking (for cross-currency payments)
- Multi-level payment submission with progressive confirmation
- Saga-style compensation for failure recovery
- Timeout handling with TSQ escalation

## Domain Model

### Payment Entity

```java
public record Payment(
    UUID paymentId,
    LocalDate actionDate,           // Date payment was initiated
    LocalDate valueDate,            // Settlement date
    Currency debitCurrency,         // ISO 4217 currency code
    DebitAccount debitAccount,      // Source account
    CreditAccount creditAccount,    // Destination account (IBAN/BIC)
    BigDecimal debitAmount,         // Amount debited (18,2 precision)
    BigDecimal creditAmount,        // Amount credited (18,2 precision)
    Long fxContractId,              // FX contract reference (nullable)
    BigDecimal fxRate,              // Exchange rate (12,6 precision)
    PaymentStatus status,           // Current payment status
    Instant cutoffTimestamp,        // Payment must complete by this time
    Instant createdAt,
    Instant updatedAt
)
```

### Value Objects

#### DebitAccount
```java
public record DebitAccount(
    String transit,         // 5 digits with leading zeros (e.g., "00123")
    String accountNumber    // Account number
) {
    public DebitAccount {
        if (transit == null || !transit.matches("\\d{5}")) {
            throw new IllegalArgumentException("Transit must be 5 digits");
        }
    }
}
```

#### CreditAccount
```java
public record CreditAccount(
    String bic,     // SWIFT/BIC code (8 or 11 characters)
    String iban     // IBAN (up to 34 alphanumeric characters)
) {
    public CreditAccount {
        if (bic == null || !bic.matches("[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?")) {
            throw new IllegalArgumentException("Invalid BIC format");
        }
        if (iban == null || !iban.matches("[A-Z]{2}\\d{2}[A-Z0-9]{1,30}")) {
            throw new IllegalArgumentException("Invalid IBAN format");
        }
    }
}
```

### Currency Enum

```java
public enum Currency {
    USD, EUR, GBP, JPY, CHF, CAD, AUD, NZD,
    SEK, NOK, DKK, PLN, CZK, HUF, MXN, KRW,
    SGD, HKD, INR, BRL, ZAR, TRY, ILS, THB
}
```

### PaymentStatus Enum

```java
public enum PaymentStatus {
    DRAFT,          // Payment created but not submitted
    APPROVED,       // Risk approved, ready for processing
    PROCESSING,     // Currently being processed
    COMPLETE,       // Successfully completed
    FAILED,         // Failed and cannot be retried
    CANCELLED       // Cancelled (cutoff exceeded or manual)
}
```

## Process Flow

```
┌─────────────────┐
│     START       │
│  (paymentId)    │
└────────┬────────┘
         │
         ▼
┌─────────────────┐      DECLINED
│ BookTransaction │─────────────────────────────────────┐
│      Risk       │                                     │
└────────┬────────┘                                     │
         │ APPROVED                                     │
         │ (immediate or                                │
         │  manual approval)                            │
         ▼                                              │
    ┌────────────┐                                      │
    │  Same      │ YES                                  │
    │ Currency?  │────────────────────┐                 │
    └────┬───────┘                    │                 │
         │ NO                         │                 │
         ▼                            │                 │
┌─────────────────┐                   │                 │
│     BookFx      │                   │                 │
│ (get rate/amt)  │                   │                 │
└────────┬────────┘                   │                 │
         │                            │                 │
         ▼                            ▼                 │
┌─────────────────────────────────────────┐             │
│         SubmitPayment                   │             │
│    (sends command to payment network)   │             │
└────────────────┬────────────────────────┘             │
                 │                                      │
                 ▼                                      │
┌─────────────────────────────────────────┐             │
│           AwaitL1 [wait-only]           │             │
│      (5 min timeout → TSQ)              │             │
└────────────────┬────────────────────────┘             │
                 │ L1 reply received                    │
                 ▼                                      │
┌─────────────────────────────────────────┐             │
│           AwaitL2 [wait-only]           │             │
│      (5 min timeout → TSQ)              │             │
└────────────────┬────────────────────────┘             │
                 │ L2 reply received                    │
                 ▼                                      │
┌─────────────────────────────────────────┐             │
│           AwaitL3 [wait-only]           │             │
│      (3 hour timeout → TSQ)             │             │
└────────────────┬────────────────────────┘             │
                 │ L3 reply received                    │
                 ▼                                      │
┌─────────────────────────────────────────┐             │
│           AwaitL4 [wait-only]           │             │
│         (final confirmation)            │             │
└────────────────┬────────────────────────┘             │
                 │ L4 reply received                    │
                 ▼                                      ▼
         ┌───────────────┐                    ┌─────────────────┐
         │   COMPLETE    │                    │     FAILED      │
         └───────────────┘                    └─────────────────┘
```

## Multi-Reply Pattern

The payment submission uses a **multi-reply pattern** where:

1. **SubmitPayment** step sends ONE command to the payment network
2. External system sends progressive confirmations (L1, L2, L3, L4) as **replies**
3. Each level has a corresponding **wait-only step** that receives the reply
4. Wait-only steps do NOT send commands - they only transition on reply receipt

### Wait-Only Steps (BaseProcessManager Extension)

The `BaseProcessManager` is extended to support steps that don't send commands:

```java
/**
 * Override to indicate which steps are wait-only.
 * Wait-only steps don't send commands - they just wait for external replies.
 *
 * @return true if this step should wait without sending a command
 */
public boolean isWaitOnlyStep(TStep step) {
    return false;  // Default: all steps send commands
}
```

When `isWaitOnlyStep()` returns true:
- No command is sent
- Process transitions to WAITING_FOR_REPLY immediately
- Reply routing uses the same correlation_id (process_id)
- Timeout monitoring applies (scheduled job checks for stuck processes)

### External Reply Adapter

In production, an adapter receives messages from the payment network and sends replies:

```java
// Pseudo-code for production adapter
void onPaymentNetworkMessage(NetworkMessage msg) {
    Reply reply = Reply.success(
        msg.getPaymentId(),      // commandId (original submission)
        processId,               // correlationId
        Map.of(
            "level", msg.getLevel(),
            "status", msg.getStatus(),
            "reference", msg.getReference()
        )
    );

    // Send to reply queue
    pgmqClient.send("payments__process_replies", reply);
}
```

For e2e testing, handlers simulate this by sending replies with delays.

## Compensation Flow (Saga Pattern)

When a step fails after previous steps have completed, compensation runs in reverse order:

| Step | Compensation |
|------|--------------|
| BookTransactionRisk | UnwindTransactionRisk |
| BookFx | UnwindFx |
| SubmitPaymentL1-L4 | (none - not compensated) |

**Compensation triggers:**
- Business rule failure (auto-compensate)
- TSQ operator cancels command
- Technical failure after manual approval

## Process Steps

### Step 1: BookTransactionRisk

**Purpose:** Assess transaction risk and reserve credit limit.

**Command:** `BookTransactionRisk`

**Payload:**
```json
{
  "payment_id": "uuid",
  "debit_account": { "transit": "00123", "account_number": "12345678" },
  "debit_amount": 10000.00,
  "debit_currency": "USD"
}
```

**Responses:**

| Status | Method | Description |
|--------|--------|-------------|
| APPROVED | AVAILABLE_BALANCE | Immediate approval - sufficient balance |
| APPROVED | DAILY_LIMIT | Immediate approval - within daily limit |
| APPROVED | MANUAL | Manual approval by risk manager |
| DECLINED | AVAILABLE_BALANCE | Insufficient balance |
| DECLINED | DAILY_LIMIT | Exceeded daily limit (no manual review) |
| DECLINED | MANUAL | Risk manager declined |

**Timeout:** 2 hours (for manual approval cases)

**Reply:**
```json
{
  "status": "APPROVED",
  "method": "DAILY_LIMIT",
  "risk_reference": "RISK-2024-001234"
}
```

**Compensation:** `UnwindTransactionRisk` with same payload

### Step 2: BookFx (Conditional)

**Condition:** `debitCurrency != creditCurrency`

**Purpose:** Book FX contract and calculate credit amount.

**Command:** `BookFx`

**Payload:**
```json
{
  "payment_id": "uuid",
  "debit_currency": "USD",
  "credit_currency": "EUR",
  "debit_amount": 10000.00,
  "value_date": "2024-01-15"
}
```

**Reply:**
```json
{
  "fx_contract_id": 12345678,
  "fx_rate": 0.923456,
  "credit_amount": 9234.56
}
```

**Compensation:** `UnwindFx`
```json
{
  "payment_id": "uuid",
  "fx_contract_id": 12345678
}
```

### Step 3: SubmitPayment

**Purpose:** Submit payment to the external payment network.

**Command:** `SubmitPayment`

**Payload:**
```json
{
  "payment_id": "uuid",
  "debit_account": { "transit": "00123", "account_number": "12345678" },
  "credit_account": { "bic": "DEUTDEFF", "iban": "DE89370400440532013000" },
  "debit_amount": 10000.00,
  "debit_currency": "USD",
  "credit_amount": 9234.56,
  "credit_currency": "EUR",
  "value_date": "2024-01-15",
  "fx_contract_id": 12345678,
  "fx_rate": 0.923456
}
```

**Reply (immediate acknowledgment):**
```json
{
  "status": "SUBMITTED",
  "submission_reference": "SUB-2024-001234",
  "timestamp": "2024-01-15T10:30:00Z"
}
```

### Steps 4-7: AwaitL1, AwaitL2, AwaitL3, AwaitL4 (Wait-Only)

**Purpose:** Wait for progressive confirmations from the payment network.

These are **wait-only steps** - they don't send commands, they just wait for external replies.

| Step | Wait-Only | Timeout | Description |
|------|-----------|---------|-------------|
| AWAIT_L1 | Yes | 5 min | Wait for initial confirmation |
| AWAIT_L2 | Yes | 5 min | Wait for second confirmation |
| AWAIT_L3 | Yes | 3 hours | Wait for network confirmation |
| AWAIT_L4 | Yes | None | Wait for final confirmation |

**External Reply Format (L1-L4):**

The payment network sends replies with the level indicated:

```json
{
  "level": 1,
  "status": "SUCCESS",
  "reference": "L1-REF-001234",
  "timestamp": "2024-01-15T10:30:05Z"
}
```

or on failure:

```json
{
  "level": 2,
  "status": "FAILED",
  "error_code": "NETWORK_TIMEOUT",
  "error_message": "Payment network did not respond"
}
```

**How replies are routed:**
1. External system sends message to payment network adapter
2. Adapter creates `Reply` with:
   - `commandId` = original SubmitPayment command ID
   - `correlationId` = process ID
   - `data` = level, status, reference, etc.
3. Reply is sent to `payments__process_replies` queue
4. ProcessReplyRouter delivers to PaymentProcessManager
5. Process manager checks level and transitions to next await step or completes

## Process State

```java
public record PaymentProcessState(
    UUID paymentId,

    // Risk step results
    String riskStatus,           // APPROVED or DECLINED
    String riskMethod,           // AVAILABLE_BALANCE, DAILY_LIMIT, MANUAL
    String riskReference,        // Risk booking reference

    // FX step results
    Long fxContractId,           // FX contract ID
    BigDecimal fxRate,           // Exchange rate
    BigDecimal creditAmount,     // Calculated credit amount
    boolean fxRequired,          // Whether FX was needed

    // Submission results
    String submissionReference,  // Payment network reference
    int completedLevel,          // Last completed level (0-4)

    // Behavior for testing
    PaymentStepBehavior behavior
) implements ProcessState
```

## Process Steps Enum

```java
public enum PaymentStep {
    // Normal flow steps
    BOOK_RISK("BookTransactionRisk", false),
    BOOK_FX("BookFx", false),
    SUBMIT_PAYMENT("SubmitPayment", false),
    AWAIT_L1(null, true),    // Wait-only: no command
    AWAIT_L2(null, true),    // Wait-only: no command
    AWAIT_L3(null, true),    // Wait-only: no command
    AWAIT_L4(null, true),    // Wait-only: no command

    // Compensation steps
    UNWIND_RISK("UnwindTransactionRisk", false),
    UNWIND_FX("UnwindFx", false);

    private final String commandType;
    private final boolean waitOnly;

    public boolean isWaitOnly() {
        return waitOnly;
    }
}
```

## Timeout Handling

| Step | Timeout | Action on Timeout |
|------|---------|-------------------|
| BOOK_RISK | 2 hours | Move to TSQ |
| BOOK_FX | Default (30s) | Retry per policy, then TSQ |
| SUBMIT_PAYMENT | Default (30s) | Retry per policy, then TSQ |
| AWAIT_L1 | 5 minutes | Move to TSQ |
| AWAIT_L2 | 5 minutes | Move to TSQ |
| AWAIT_L3 | 3 hours | Move to TSQ |
| AWAIT_L4 | Default | No timeout (final step) |

**Implementation for wait-only steps:**

Since wait-only steps don't have commands, timeout monitoring requires a scheduled job:

```java
@Scheduled(fixedRate = 60000)  // Check every minute
public void checkWaitOnlyTimeouts() {
    Instant now = Instant.now();

    // Find processes in WAITING_FOR_REPLY with wait-only steps
    List<ProcessMetadata> waitingProcesses = processRepo
        .findByStatus(domain, List.of(ProcessStatus.WAITING_FOR_REPLY));

    for (ProcessMetadata process : waitingProcesses) {
        PaymentStep step = PaymentStep.valueOf(process.currentStep());
        if (!step.isWaitOnly()) continue;

        Duration waitTime = Duration.between(process.updatedAt(), now);
        Duration timeout = getStepTimeout(step);

        if (timeout != null && waitTime.compareTo(timeout) > 0) {
            // Move to TSQ
            processRepo.update(process.withStatus(ProcessStatus.WAITING_FOR_TSQ));
        }
    }
}
```

## Cutoff Handling

Each payment has a `cutoffTimestamp`. If the payment is not in COMPLETE status by cutoff:
- Process is cancelled
- Compensation runs (UnwindFx if booked, UnwindRisk if approved)
- Payment status set to CANCELLED

**Implementation:** Scheduled job checks for processes past cutoff.

## Database Schema

### Payment Table

```sql
CREATE TABLE commandbus.payment (
    payment_id UUID PRIMARY KEY,
    action_date DATE NOT NULL,
    value_date DATE NOT NULL,
    debit_currency VARCHAR(3) NOT NULL,
    debit_transit VARCHAR(5) NOT NULL,
    debit_account_number VARCHAR(20) NOT NULL,
    credit_bic VARCHAR(11) NOT NULL,
    credit_iban VARCHAR(34) NOT NULL,
    debit_amount DECIMAL(18, 2) NOT NULL,
    credit_amount DECIMAL(18, 2),
    fx_contract_id BIGINT,
    fx_rate DECIMAL(12, 6),
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    cutoff_timestamp TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_payment_status ON commandbus.payment(status);
CREATE INDEX idx_payment_cutoff ON commandbus.payment(cutoff_timestamp)
    WHERE status NOT IN ('COMPLETE', 'FAILED', 'CANCELLED');
```

## Handler Implementations

### PaymentHandlers

Registered in `payments` domain with probabilistic behavior for testing:

```java
@Component
@Profile("worker")
public class PaymentHandlers {

    @PostConstruct
    public void registerHandlers() {
        registry.register("payments", "BookTransactionRisk", this::handleBookRisk);
        registry.register("payments", "UnwindTransactionRisk", this::handleUnwindRisk);
        registry.register("payments", "BookFx", this::handleBookFx);
        registry.register("payments", "UnwindFx", this::handleUnwindFx);
        registry.register("payments", "SubmitPayment", this::handleSubmitPayment);
    }
}
```

### PaymentNetworkSimulator

For e2e testing, simulates the external payment network sending L1-L4 replies:

```java
@Component
@Profile("worker")
public class PaymentNetworkSimulator {

    private final PgmqClient pgmqClient;
    private final ScheduledExecutorService scheduler;

    /**
     * Called by SubmitPayment handler after initial submission.
     * Schedules L1-L4 replies with configurable delays and failure rates.
     */
    public void simulatePaymentConfirmations(
            UUID commandId,
            UUID processId,
            PaymentStepBehavior behavior) {

        // Schedule L1 reply after delay
        scheduler.schedule(() -> {
            sendLevelReply(commandId, processId, 1, behavior.awaitL1());
        }, randomDelay(100, 500), TimeUnit.MILLISECONDS);

        // L2, L3, L4 are scheduled after receiving previous level
        // (In real implementation, each level triggers the next)
    }

    private void sendLevelReply(UUID commandId, UUID processId, int level,
                                ProbabilisticBehavior behavior) {
        Reply reply;
        if (shouldFail(behavior)) {
            reply = Reply.failed(commandId, processId,
                "NETWORK_ERROR", "Simulated failure at L" + level);
        } else {
            reply = Reply.success(commandId, processId, Map.of(
                "level", level,
                "status", "SUCCESS",
                "reference", "L" + level + "-REF-" + UUID.randomUUID()
            ));
        }

        pgmqClient.send("payments__process_replies", serializeReply(reply));
    }
}
```

## Testing Behavior Configuration

### PaymentStepBehavior

```java
public record PaymentStepBehavior(
    RiskBehavior bookRisk,           // Special behavior for risk (includes manual approval)
    ProbabilisticBehavior bookFx,
    ProbabilisticBehavior submitPayment,
    ProbabilisticBehavior awaitL1,
    ProbabilisticBehavior awaitL2,
    ProbabilisticBehavior awaitL3,
    ProbabilisticBehavior awaitL4
) {
    public ProbabilisticBehavior forStep(PaymentStep step) {
        return switch (step) {
            case BOOK_RISK -> bookRisk.toProbabilistic();
            case BOOK_FX -> bookFx;
            case SUBMIT_PAYMENT -> submitPayment;
            case AWAIT_L1 -> awaitL1;
            case AWAIT_L2 -> awaitL2;
            case AWAIT_L3 -> awaitL3;
            case AWAIT_L4 -> awaitL4;
            default -> ProbabilisticBehavior.DEFAULT;
        };
    }
}
```

### RiskBehavior

Special behavior for `BookTransactionRisk` that includes manual approval simulation:

```java
public record RiskBehavior(
    double approvedBalancePct,     // % approved via AVAILABLE_BALANCE
    double approvedLimitPct,       // % approved via DAILY_LIMIT
    double manualApprovalPct,      // % requiring manual review
    double declinedPct,            // % declined outright
    int manualApprovalDelayMs,     // Delay for manual approval (simulates human review)
    int minDurationMs,
    int maxDurationMs
) {
    /**
     * Convert to ProbabilisticBehavior for standard processing.
     * declinedPct maps to failBusinessRulePct (triggers compensation).
     */
    public ProbabilisticBehavior toProbabilistic() {
        return new ProbabilisticBehavior(
            0,              // No permanent failures
            0,              // No transient failures
            declinedPct,    // Declined = business rule failure
            0,              // No timeouts
            minDurationMs,
            maxDurationMs
        );
    }
}
```

Each step can be configured with:
- `failPermanentPct` - Permanent failure rate
- `failTransientPct` - Transient failure rate (will retry)
- `failBusinessRulePct` - Business rule failure (triggers compensation)
- `timeoutPct` - Simulated timeout
- `minDurationMs` / `maxDurationMs` - Execution duration

## UI Integration

### Payment Creation Form

Fields:
- Debit Account (transit + account number)
- Credit Account (BIC + IBAN)
- Amount and Currency
- Value Date
- Cutoff Time

### Payment Batch Creation

Create multiple payments with:
- Random account generation
- Configurable failure rates per step
- Batch progress monitoring

### Payment Process Monitoring

- View payment process state
- See current step and status
- View audit trail of all commands/replies
- TSQ intervention for stuck payments

## Configuration

```yaml
commandbus:
  process:
    payment:
      domain: payments
      reply-queue: payments__process_replies
      risk-timeout-hours: 2
      l1-timeout-minutes: 5
      l2-timeout-minutes: 5
      l3-timeout-hours: 3
```

## File Structure

```
src/main/java/com/ivamare/commandbus/process/
└── BaseProcessManager.java          # Updated: add isWaitOnlyStep() support

src/test/java/com/ivamare/commandbus/e2e/
├── payment/
│   ├── Currency.java                # ISO 4217 currency codes
│   ├── DebitAccount.java            # Value object (transit + account)
│   ├── CreditAccount.java           # Value object (BIC + IBAN)
│   ├── Payment.java                 # Payment entity
│   ├── PaymentStatus.java           # DRAFT, APPROVED, PROCESSING, etc.
│   ├── PaymentRepository.java       # JDBC repository interface
│   ├── JdbcPaymentRepository.java   # JDBC implementation
│   ├── PaymentStep.java             # Process steps enum (with waitOnly flag)
│   ├── PaymentProcessState.java     # Process state record
│   ├── PaymentProcessManager.java   # Process manager implementation
│   ├── PaymentStepBehavior.java     # Per-step test behavior
│   ├── RiskBehavior.java            # Special risk behavior (manual approval)
│   └── PaymentNetworkSimulator.java # Simulates L1-L4 replies for testing
├── handlers/
│   └── PaymentHandlers.java         # Command handlers
├── controller/
│   └── PaymentController.java       # Web controller
├── dto/
│   ├── PaymentView.java
│   └── PaymentCreateRequest.java
└── service/
    └── E2EService.java              # Updated: add payment methods

src/test/resources/
├── db/migration/
│   └── V005__payment_schema.sql     # Payment table DDL
├── application-e2e.yml              # Updated: payment config
└── templates/e2e/pages/
    ├── payments.html
    ├── payment_detail.html
    └── payment_new.html
```

## Implementation Checklist

### Phase 1: BaseProcessManager Extension
- [ ] Add `isWaitOnlyStep(TStep step)` method to BaseProcessManager
- [ ] Update `executeStep()` to skip command sending for wait-only steps
- [ ] Add unit tests for wait-only step behavior

### Phase 2: Payment Domain Model
- [ ] Create Currency enum with OECD currencies
- [ ] Create DebitAccount value object with validation
- [ ] Create CreditAccount value object with BIC/IBAN validation
- [ ] Create Payment entity record
- [ ] Create PaymentStatus enum
- [ ] Create V005__payment_schema.sql migration

### Phase 3: Payment Repository
- [ ] Create PaymentRepository interface
- [ ] Implement JdbcPaymentRepository
- [ ] Add repository tests

### Phase 4: Payment Process Manager
- [ ] Create PaymentStep enum with waitOnly flag
- [ ] Create PaymentProcessState record with toMap/fromMap
- [ ] Create PaymentStepBehavior and RiskBehavior records
- [ ] Implement PaymentProcessManager extending BaseProcessManager
- [ ] Implement compensation mapping (UNWIND_RISK, UNWIND_FX)
- [ ] Add process manager tests

### Phase 5: Handlers and Simulator
- [ ] Implement PaymentHandlers (BookRisk, BookFx, SubmitPayment, etc.)
- [ ] Implement PaymentNetworkSimulator for L1-L4 reply generation
- [ ] Add handler tests

### Phase 6: UI Integration
- [ ] Create PaymentController
- [ ] Create payment Thymeleaf templates
- [ ] Update E2EService with payment methods
- [ ] Add payment section to navigation

### Phase 7: End-to-End Testing
- [ ] Test full payment flow (happy path)
- [ ] Test FX flow (cross-currency)
- [ ] Test compensation flow (failure after risk approval)
- [ ] Test timeout scenarios
- [ ] Test manual approval simulation
