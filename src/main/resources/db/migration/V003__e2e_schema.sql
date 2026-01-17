-- V003: E2E Test Schema (Consolidated)
-- Creates the 'e2e' schema with all test-specific tables
--
-- This migration is optional for production deployments.
-- It creates tables used by the E2E testing framework.
--
-- Includes:
-- - Test command table for behavior specification
-- - Configuration tables
-- - Batch summary for reply queue testing
-- - Payment processing tables
-- - Pending approval and network response queues

-- Create e2e schema
CREATE SCHEMA IF NOT EXISTS e2e;

-- Set search path for this migration
SET search_path TO e2e, public;

-- ============================================================================
-- Test Command Table
-- ============================================================================

-- Test command table for E2E testing
-- Stores behavior specification for test commands
CREATE TABLE IF NOT EXISTS e2e.test_command (
    id SERIAL PRIMARY KEY,
    command_id UUID NOT NULL UNIQUE,
    payload JSONB NOT NULL DEFAULT '{}',
    behavior JSONB NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    processed_at TIMESTAMPTZ,
    attempts INTEGER DEFAULT 0,
    result JSONB
);

CREATE INDEX IF NOT EXISTS ix_test_command_command_id
    ON e2e.test_command(command_id);

CREATE INDEX IF NOT EXISTS ix_test_command_created_at
    ON e2e.test_command(created_at);

-- ============================================================================
-- Configuration Table
-- ============================================================================

-- Configuration table for worker/retry/runtime settings
CREATE TABLE IF NOT EXISTS e2e.config (
    key TEXT PRIMARY KEY,
    value JSONB NOT NULL,
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- Insert default configuration
INSERT INTO e2e.config (key, value) VALUES
    ('worker', '{
        "visibility_timeout": 30,
        "concurrency": 20,
        "poll_interval": 1.0,
        "batch_size": 10
    }'::jsonb),
    ('retry', '{
        "max_attempts": 3,
        "backoff_schedule": [10, 60, 300]
    }'::jsonb),
    ('runtime', '{
        "mode": "sync"
    }'::jsonb)
ON CONFLICT (key) DO NOTHING;

-- ============================================================================
-- Batch Summary Table for Reply Queue Aggregation
-- ============================================================================

-- Stores aggregated reply counts for e2e testing of reply queue functionality
CREATE TABLE IF NOT EXISTS e2e.batch_summary (
    id SERIAL PRIMARY KEY,
    batch_id UUID NOT NULL UNIQUE,
    domain TEXT NOT NULL DEFAULT 'e2e',
    total_expected INTEGER NOT NULL DEFAULT 0,
    success_count INTEGER NOT NULL DEFAULT 0,
    failed_count INTEGER NOT NULL DEFAULT 0,
    canceled_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    completed_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS ix_batch_summary_batch_id
    ON e2e.batch_summary(batch_id);

CREATE INDEX IF NOT EXISTS ix_batch_summary_created_at
    ON e2e.batch_summary(created_at);

-- ============================================================================
-- Payment Table
-- ============================================================================

CREATE TABLE IF NOT EXISTS e2e.payment (
    payment_id UUID PRIMARY KEY,
    action_date DATE NOT NULL,
    value_date DATE NOT NULL,
    debit_currency VARCHAR(3) NOT NULL,
    credit_currency VARCHAR(3) NOT NULL,
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

-- Index for querying by status (most common query pattern)
CREATE INDEX IF NOT EXISTS idx_payment_status
    ON e2e.payment(status);

-- Index for cutoff monitoring (find payments approaching/past cutoff)
CREATE INDEX IF NOT EXISTS idx_payment_cutoff
    ON e2e.payment(cutoff_timestamp)
    WHERE status NOT IN ('COMPLETE', 'FAILED', 'CANCELLED');

-- Index for date range queries
CREATE INDEX IF NOT EXISTS idx_payment_action_date
    ON e2e.payment(action_date);

-- Index for value date queries (settlement date lookups)
CREATE INDEX IF NOT EXISTS idx_payment_value_date
    ON e2e.payment(value_date);

-- ============================================================================
-- Payment Batch Tables
-- ============================================================================

CREATE TABLE IF NOT EXISTS e2e.payment_batch (
    batch_id UUID PRIMARY KEY,
    name TEXT NOT NULL,
    total_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Link payments to batches
CREATE TABLE IF NOT EXISTS e2e.payment_batch_item (
    batch_id UUID NOT NULL REFERENCES e2e.payment_batch(batch_id) ON DELETE CASCADE,
    payment_id UUID NOT NULL REFERENCES e2e.payment(payment_id) ON DELETE CASCADE,
    PRIMARY KEY (batch_id, payment_id)
);

CREATE INDEX IF NOT EXISTS idx_payment_batch_item_payment
    ON e2e.payment_batch_item(payment_id);

-- ============================================================================
-- Pending Approval Queue
-- ============================================================================

-- Stores payments that require manual approval during risk assessment.
-- When a payment is randomly selected for manual review, it's placed here
-- instead of blocking the worker thread. An operator must approve/reject
-- to send a reply and continue the process.

CREATE TABLE IF NOT EXISTS e2e.pending_approval (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id UUID NOT NULL,
    process_id UUID NOT NULL,
    correlation_id UUID NOT NULL,  -- For routing reply back to process
    command_id UUID NOT NULL,       -- Original command ID for reply

    -- Payment details for display
    amount DECIMAL(18, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    debit_account VARCHAR(50) NOT NULL,
    credit_account VARCHAR(50),

    -- Queue metadata
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',  -- PENDING, APPROVED, REJECTED
    execution_model VARCHAR(20) DEFAULT 'STEP_BASED',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    resolved_at TIMESTAMPTZ,
    resolved_by VARCHAR(100),
    resolution_notes TEXT
);

COMMENT ON COLUMN e2e.pending_approval.execution_model IS
'Execution model for this approval request:
- STEP_BASED: Uses BaseProcessManager with PGMQ commands
- PROCESS_STEP: Uses ProcessStepManager with deterministic replay';

-- Index for listing pending items
CREATE INDEX IF NOT EXISTS idx_pending_approval_status
    ON e2e.pending_approval(status)
    WHERE status = 'PENDING';

-- Index for finding by process
CREATE INDEX IF NOT EXISTS idx_pending_approval_process
    ON e2e.pending_approval(process_id);

-- Index for finding by payment
CREATE INDEX IF NOT EXISTS idx_pending_approval_payment
    ON e2e.pending_approval(payment_id);

-- Index for filtering by execution model
CREATE INDEX IF NOT EXISTS idx_pending_approval_execution_model
ON e2e.pending_approval(execution_model, status)
WHERE status = 'PENDING';

-- ============================================================================
-- Pending Network Response Queue
-- ============================================================================

-- Stores L3/L4 network confirmations that require operator intervention.
-- A percentage of payments randomly get their L3 or L4 response delayed
-- and placed in this queue. The operator must approve/reject to send
-- the response and continue the process.

CREATE TABLE IF NOT EXISTS e2e.pending_network_response (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id UUID,                -- Optional, for display
    process_id UUID NOT NULL,
    correlation_id UUID NOT NULL,   -- For routing reply back to process
    command_id UUID NOT NULL,       -- Original SubmitPayment command ID

    -- Network response details
    level INT NOT NULL,             -- 3 or 4 (which confirmation level)

    -- Queue metadata
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',  -- PENDING, APPROVED, REJECTED
    execution_model VARCHAR(20) DEFAULT 'STEP_BASED',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    resolved_at TIMESTAMPTZ,
    resolved_by VARCHAR(100),
    resolution_notes TEXT
);

COMMENT ON COLUMN e2e.pending_network_response.execution_model IS
'Execution model for this network response:
- STEP_BASED: Uses BaseProcessManager with PGMQ commands
- PROCESS_STEP: Uses ProcessStepManager with deterministic replay';

-- Index for listing pending items by level
CREATE INDEX IF NOT EXISTS idx_pending_network_response_status_level
    ON e2e.pending_network_response(status, level)
    WHERE status = 'PENDING';

-- Index for finding by process
CREATE INDEX IF NOT EXISTS idx_pending_network_response_process
    ON e2e.pending_network_response(process_id);

-- Composite index for common queries
CREATE INDEX IF NOT EXISTS idx_pending_network_response_pending
    ON e2e.pending_network_response(created_at)
    WHERE status = 'PENDING';

-- Index for filtering by execution model
CREATE INDEX IF NOT EXISTS idx_pending_network_response_execution_model
ON e2e.pending_network_response(execution_model, status)
WHERE status = 'PENDING';
