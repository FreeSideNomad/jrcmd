-- V005: Payment Processing Schema
-- Creates tables for the payment processing e2e sample application
--
-- This migration adds the payment table and related structures for
-- demonstrating multi-step workflows with conditional branching,
-- multi-reply patterns, and saga-style compensation.

-- Set search path
SET search_path TO e2e, public;

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
-- Payment Batch Table (for batch creation UI)
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
-- PGMQ Queue for Payment Process Manager
-- ============================================================================

-- Create process reply queue for payment workflow
-- (payments__commands and payments__replies already exist from V002)
SELECT pgmq.create('payments__process_replies');
