-- V006: Pending Approval and Network Response Queues
-- Creates tables for operator intervention queues in the payment processing demo
--
-- These tables support the asynchronous approval workflow:
-- 1. Manual approval queue - payments requiring human approval before risk booking
-- 2. Network response queue - L3/L4 responses awaiting operator decision
--
-- Both queues demonstrate how to handle long-running external interactions
-- without blocking worker threads.

SET search_path TO e2e, public;

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
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    resolved_at TIMESTAMPTZ,
    resolved_by VARCHAR(100),
    resolution_notes TEXT
);

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
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    resolved_at TIMESTAMPTZ,
    resolved_by VARCHAR(100),
    resolution_notes TEXT
);

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
