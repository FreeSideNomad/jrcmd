-- V008: ProcessStepManager Schema Extensions
-- Adds support for the ProcessStepManager framework with deterministic replay
--
-- Includes:
-- - execution_model column for distinguishing process types
-- - Scheduler support columns (next_retry_at, next_wait_timeout_at, deadline_at)
-- - Process audit extensions for step-based entries
-- - Rate limiting tables
-- - Indexes for efficient scheduler queries

SET search_path TO commandbus, pgmq, public;

-- ============================================================================
-- Process Table Extensions
-- ============================================================================

-- Add execution_model column to distinguish BaseProcessManager vs ProcessStepManager
ALTER TABLE commandbus.process
ADD COLUMN IF NOT EXISTS execution_model VARCHAR(20) DEFAULT 'STEP_BASED';
-- Values: 'STEP_BASED' (BaseProcessManager), 'PROCESS_STEP' (ProcessStepManager)

COMMENT ON COLUMN commandbus.process.execution_model IS
'Execution model for this process:
- STEP_BASED: Uses BaseProcessManager with step enum and PGMQ commands
- PROCESS_STEP: Uses ProcessStepManager with deterministic replay';

-- Add scheduler support columns
ALTER TABLE commandbus.process
ADD COLUMN IF NOT EXISTS next_retry_at TIMESTAMPTZ;

ALTER TABLE commandbus.process
ADD COLUMN IF NOT EXISTS next_wait_timeout_at TIMESTAMPTZ;

ALTER TABLE commandbus.process
ADD COLUMN IF NOT EXISTS deadline_at TIMESTAMPTZ;

ALTER TABLE commandbus.process
ADD COLUMN IF NOT EXISTS current_wait VARCHAR(100);
-- Name of wait we're blocked on (for TSQ UI display)

COMMENT ON COLUMN commandbus.process.next_retry_at IS
'When this process is due for retry (for WAITING_FOR_RETRY status)';

COMMENT ON COLUMN commandbus.process.next_wait_timeout_at IS
'When the current wait condition times out (for WAITING_FOR_ASYNC status)';

COMMENT ON COLUMN commandbus.process.deadline_at IS
'Process-level deadline - action taken when exceeded depends on DeadlineAction config';

COMMENT ON COLUMN commandbus.process.current_wait IS
'Name of the wait condition we are currently blocked on (for TSQ UI display)';

-- ============================================================================
-- Process Status Extensions
-- Add new statuses for ProcessStepManager
-- ============================================================================

-- Note: ProcessStatus enum now includes:
-- - WAITING_FOR_ASYNC (waiting for external event)
-- - WAITING_FOR_RETRY (scheduled for retry)
-- These are handled at application level, no DB enum change needed

-- ============================================================================
-- Indexes for ProcessStepWorker Polling
-- ============================================================================

-- Index for finding PENDING PROCESS_STEP processes
CREATE INDEX IF NOT EXISTS idx_process_pending_step
ON commandbus.process(domain, execution_model)
WHERE status = 'PENDING' AND execution_model = 'PROCESS_STEP';

-- Index for finding processes due for retry
CREATE INDEX IF NOT EXISTS idx_process_retry
ON commandbus.process(domain, next_retry_at)
WHERE status = 'WAITING_FOR_RETRY' AND next_retry_at IS NOT NULL;

-- Index for finding processes with expired wait timeouts
CREATE INDEX IF NOT EXISTS idx_process_wait_timeout
ON commandbus.process(domain, next_wait_timeout_at)
WHERE status = 'WAITING_FOR_ASYNC' AND next_wait_timeout_at IS NOT NULL;

-- Index for finding processes with exceeded deadlines
CREATE INDEX IF NOT EXISTS idx_process_deadline
ON commandbus.process(domain, deadline_at)
WHERE status NOT IN ('COMPLETED', 'FAILED', 'COMPENSATED', 'CANCELED')
AND deadline_at IS NOT NULL;

-- Index for filtering by execution model
CREATE INDEX IF NOT EXISTS idx_process_execution_model
ON commandbus.process(domain, execution_model);

-- ============================================================================
-- Process Audit Table Extensions
-- ============================================================================

-- Add entry_type to distinguish audit entry types
ALTER TABLE commandbus.process_audit
ADD COLUMN IF NOT EXISTS entry_type VARCHAR(30) DEFAULT 'COMMAND';
-- Values: COMMAND (existing), STEP, WAIT, SIDE_EFFECT, ASYNC_RESPONSE, COMPENSATION

ALTER TABLE commandbus.process_audit
ADD COLUMN IF NOT EXISTS step_status VARCHAR(20);
-- Values: STARTED, COMPLETED, FAILED, WAITING_RETRY

ALTER TABLE commandbus.process_audit
ADD COLUMN IF NOT EXISTS attempt_number INTEGER;

-- Make command_id nullable (not all entry types have commands)
ALTER TABLE commandbus.process_audit
ALTER COLUMN command_id DROP NOT NULL;

-- Make command_type nullable
ALTER TABLE commandbus.process_audit
ALTER COLUMN command_type DROP NOT NULL;

-- Make sent_at nullable
ALTER TABLE commandbus.process_audit
ALTER COLUMN sent_at DROP NOT NULL;

COMMENT ON COLUMN commandbus.process_audit.entry_type IS
'Type of audit entry:
- COMMAND: Traditional step with PGMQ command (BaseProcessManager)
- STEP: ProcessStepManager step execution
- WAIT: Wait condition evaluation
- SIDE_EFFECT: Side effect recording
- ASYNC_RESPONSE: External async response received
- COMPENSATION: Compensation action executed';

COMMENT ON COLUMN commandbus.process_audit.step_status IS
'Status of step execution: STARTED, COMPLETED, FAILED, WAITING_RETRY';

COMMENT ON COLUMN commandbus.process_audit.attempt_number IS
'Attempt number for this step (1-based)';

-- ============================================================================
-- Rate Limiting Tables
-- ============================================================================

-- Rate limit configuration (application-managed)
CREATE TABLE IF NOT EXISTS commandbus.rate_limit_config (
    resource_key VARCHAR(100) PRIMARY KEY,
    tokens_per_second INTEGER NOT NULL,
    description VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE commandbus.rate_limit_config IS
'Configuration for distributed rate limiting. Each resource_key represents
a rate-limited resource (e.g., external API). tokens_per_second defines
the rate limit using token bucket algorithm.';

-- Bucket4j state storage (managed by Bucket4j PostgreSQL proxy)
CREATE TABLE IF NOT EXISTS commandbus.rate_limit_bucket (
    id VARCHAR(255) PRIMARY KEY,
    state BYTEA NOT NULL,
    expires_at BIGINT
);

COMMENT ON TABLE commandbus.rate_limit_bucket IS
'Bucket4j rate limiter state storage. Managed by Bucket4j PostgreSQL
advisory lock-based proxy manager. State contains serialized bucket data.';

-- Index for cleanup of expired buckets
CREATE INDEX IF NOT EXISTS idx_rate_limit_bucket_expires
ON commandbus.rate_limit_bucket(expires_at)
WHERE expires_at IS NOT NULL;

-- ============================================================================
-- Stored Procedure: Update Process State Atomic (ProcessStepManager)
-- ============================================================================

-- Extended sp_update_process_state to handle ProcessStepManager fields
CREATE OR REPLACE FUNCTION commandbus.sp_update_process_state_step(
    p_domain TEXT,
    p_process_id UUID,
    p_state_patch JSONB,
    p_new_step TEXT DEFAULT NULL,
    p_new_status TEXT DEFAULT NULL,
    p_error_code TEXT DEFAULT NULL,
    p_error_message TEXT DEFAULT NULL,
    p_next_retry_at TIMESTAMPTZ DEFAULT NULL,
    p_next_wait_timeout_at TIMESTAMPTZ DEFAULT NULL,
    p_current_wait TEXT DEFAULT NULL
)
RETURNS VOID AS $$
DECLARE
    v_current_status TEXT;
BEGIN
    -- Get current status to check for terminal state
    SELECT status INTO v_current_status
    FROM commandbus.process
    WHERE domain = p_domain AND process_id = p_process_id;

    -- Don't overwrite terminal status (race condition protection)
    IF v_current_status IN ('COMPLETED', 'FAILED', 'COMPENSATED', 'CANCELED') THEN
        -- For terminal processes, only update state if provided
        IF p_state_patch IS NOT NULL THEN
            UPDATE commandbus.process
            SET state = state || p_state_patch,
                updated_at = NOW()
            WHERE domain = p_domain AND process_id = p_process_id;
        END IF;
        RETURN;
    END IF;

    -- Full update for non-terminal processes
    UPDATE commandbus.process
    SET state = CASE WHEN p_state_patch IS NOT NULL THEN state || p_state_patch ELSE state END,
        current_step = COALESCE(p_new_step, current_step),
        status = COALESCE(p_new_status, status),
        error_code = COALESCE(p_error_code, error_code),
        error_message = COALESCE(p_error_message, error_message),
        next_retry_at = CASE
            WHEN p_new_status = 'WAITING_FOR_RETRY' THEN p_next_retry_at
            ELSE NULL
        END,
        next_wait_timeout_at = CASE
            WHEN p_new_status = 'WAITING_FOR_ASYNC' THEN p_next_wait_timeout_at
            ELSE NULL
        END,
        current_wait = CASE
            WHEN p_new_status = 'WAITING_FOR_ASYNC' THEN p_current_wait
            ELSE NULL
        END,
        completed_at = CASE
            WHEN p_new_status IN ('COMPLETED', 'FAILED', 'COMPENSATED', 'CANCELED') THEN NOW()
            ELSE completed_at
        END,
        updated_at = NOW()
    WHERE domain = p_domain AND process_id = p_process_id;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION commandbus.sp_update_process_state_step IS
'Atomically update process state for ProcessStepManager.
Handles scheduler fields (next_retry_at, next_wait_timeout_at, current_wait)
and protects against overwriting terminal status.';

-- ============================================================================
-- Default Rate Limit Configuration
-- ============================================================================

-- Insert default rate limit configs (can be modified by application)
INSERT INTO commandbus.rate_limit_config (resource_key, tokens_per_second, description)
VALUES
    ('default', 100, 'Default rate limit for unconfigured resources')
ON CONFLICT (resource_key) DO NOTHING;
