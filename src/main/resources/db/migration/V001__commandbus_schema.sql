-- V001: Command Bus Core Schema (Consolidated)
-- Creates the 'commandbus' schema with all core tables and stored procedures
--
-- This migration consolidates all command bus database objects into a dedicated schema
-- for better organization and separation of concerns.
--
-- Includes:
-- - Command and batch tables
-- - Process manager tables (with ProcessStepManager extensions)
-- - All stored procedures (receive, finish, fail, batch stats, TSQ, process state, claiming)
-- - Rate limiting tables
-- - Process claiming for distributed workers

-- Enable PGMQ extension (required for message queuing)
CREATE EXTENSION IF NOT EXISTS pgmq;

-- Create commandbus schema
CREATE SCHEMA IF NOT EXISTS commandbus;

-- Set search path for this migration
SET search_path TO commandbus, pgmq, public;

-- ============================================================================
-- Tables
-- ============================================================================

-- Batch table (must be created before command table for FK reference)
-- Supports both command batches and process batches via batch_type
CREATE TABLE IF NOT EXISTS commandbus.batch (
    domain                    TEXT NOT NULL,
    batch_id                  UUID NOT NULL,
    name                      TEXT NULL,
    custom_data               JSONB NULL,
    status                    TEXT NOT NULL DEFAULT 'PENDING',
    total_count               INT NOT NULL DEFAULT 0,
    completed_count           INT NOT NULL DEFAULT 0,
    failed_count              INT NOT NULL DEFAULT 0,
    canceled_count            INT NOT NULL DEFAULT 0,
    in_troubleshooting_count  INT NOT NULL DEFAULT 0,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    started_at                TIMESTAMPTZ NULL,
    completed_at              TIMESTAMPTZ NULL,
    batch_type                TEXT NOT NULL DEFAULT 'COMMAND',
    PRIMARY KEY (domain, batch_id)
);

COMMENT ON COLUMN commandbus.batch.batch_type IS
'Type of batch: COMMAND for command batches, PROCESS for process batches.
Default is COMMAND for backward compatibility with existing batches.';

CREATE INDEX IF NOT EXISTS ix_batch_status
    ON commandbus.batch(domain, status);

CREATE INDEX IF NOT EXISTS ix_batch_created
    ON commandbus.batch(domain, created_at DESC);

-- Command table (command metadata)
CREATE TABLE IF NOT EXISTS commandbus.command (
    domain            TEXT NOT NULL,
    queue_name        TEXT NOT NULL,
    msg_id            BIGINT NULL,
    command_id        UUID NOT NULL,
    command_type      TEXT NOT NULL,
    status            TEXT NOT NULL,
    attempts          INT NOT NULL DEFAULT 0,
    max_attempts      INT NOT NULL,
    lease_expires_at  TIMESTAMPTZ NULL,
    last_error_type   TEXT NULL,
    last_error_code   TEXT NULL,
    last_error_msg    TEXT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    reply_queue       TEXT NOT NULL DEFAULT '',
    correlation_id    UUID NULL,
    batch_id          UUID NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_command_domain_cmdid
    ON commandbus.command(domain, command_id);

CREATE INDEX IF NOT EXISTS ix_command_status_type
    ON commandbus.command(status, command_type);

CREATE INDEX IF NOT EXISTS ix_command_status_created
    ON commandbus.command(status, created_at DESC);

CREATE INDEX IF NOT EXISTS ix_command_updated
    ON commandbus.command(updated_at);

CREATE INDEX IF NOT EXISTS ix_command_batch
    ON commandbus.command(domain, batch_id) WHERE batch_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS ix_command_batch_status
    ON commandbus.command(batch_id, status) WHERE batch_id IS NOT NULL;

-- Audit table (append-only)
CREATE TABLE IF NOT EXISTS commandbus.audit (
    audit_id      BIGSERIAL PRIMARY KEY,
    domain        TEXT NOT NULL,
    command_id    UUID NOT NULL,
    event_type    TEXT NOT NULL,
    ts            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    details_json  JSONB NULL
);

CREATE INDEX IF NOT EXISTS ix_audit_cmdid_ts
    ON commandbus.audit(command_id, ts);

-- Optional payload archive
CREATE TABLE IF NOT EXISTS commandbus.payload_archive (
    domain        TEXT NOT NULL,
    command_id    UUID NOT NULL,
    payload_json  JSONB NOT NULL,
    archived_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY(domain, command_id)
);

-- ============================================================================
-- Process Manager Tables
-- ============================================================================

CREATE TABLE IF NOT EXISTS commandbus.process (
    domain VARCHAR(255) NOT NULL,
    process_id UUID NOT NULL,
    process_type VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    current_step VARCHAR(255),
    state JSONB NOT NULL DEFAULT '{}',
    error_code VARCHAR(255),
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMPTZ,
    batch_id UUID,
    -- ProcessStepManager extensions
    execution_model VARCHAR(20) DEFAULT 'STEP_BASED',
    next_retry_at TIMESTAMPTZ,
    next_wait_timeout_at TIMESTAMPTZ,
    deadline_at TIMESTAMPTZ,
    current_wait VARCHAR(100),
    claimed_at TIMESTAMPTZ,

    PRIMARY KEY (domain, process_id)
);

COMMENT ON COLUMN commandbus.process.execution_model IS
'Execution model for this process:
- STEP_BASED: Uses BaseProcessManager with step enum and PGMQ commands
- PROCESS_STEP: Uses ProcessStepManager with deterministic replay';

COMMENT ON COLUMN commandbus.process.next_retry_at IS
'When this process is due for retry (for WAITING_FOR_RETRY status)';

COMMENT ON COLUMN commandbus.process.next_wait_timeout_at IS
'When the current wait condition times out (for WAITING_FOR_ASYNC status)';

COMMENT ON COLUMN commandbus.process.deadline_at IS
'Process-level deadline - action taken when exceeded depends on DeadlineAction config';

COMMENT ON COLUMN commandbus.process.current_wait IS
'Name of the wait condition we are currently blocked on (for TSQ UI display)';

COMMENT ON COLUMN commandbus.process.claimed_at IS
'Timestamp when this process was claimed by a worker for processing.
Used with processing_timeout to detect stale claims from crashed workers.';

CREATE INDEX IF NOT EXISTS idx_process_type ON commandbus.process(domain, process_type);
CREATE INDEX IF NOT EXISTS idx_process_status ON commandbus.process(domain, status);
CREATE INDEX IF NOT EXISTS idx_process_created ON commandbus.process(created_at);

-- Partial index for looking up processes by batch
CREATE INDEX IF NOT EXISTS ix_process_batch_id
    ON commandbus.process(batch_id) WHERE batch_id IS NOT NULL;

-- Index for efficient stats calculation by batch and status
CREATE INDEX IF NOT EXISTS ix_process_batch_status
    ON commandbus.process(batch_id, status) WHERE batch_id IS NOT NULL;

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

-- Index for finding stale claims (IN_PROGRESS but claimed too long ago)
CREATE INDEX IF NOT EXISTS idx_process_stale_claims
ON commandbus.process(domain, claimed_at)
WHERE status = 'IN_PROGRESS' AND claimed_at IS NOT NULL;

-- Process Audit Table
CREATE TABLE IF NOT EXISTS commandbus.process_audit (
    id BIGSERIAL PRIMARY KEY,
    domain VARCHAR(255) NOT NULL,
    process_id UUID NOT NULL,
    step_name VARCHAR(255) NOT NULL,
    command_id UUID,
    command_type VARCHAR(255),
    command_data JSONB,
    sent_at TIMESTAMPTZ,
    reply_outcome VARCHAR(50),
    reply_data JSONB,
    received_at TIMESTAMPTZ,
    entry_type VARCHAR(30) DEFAULT 'COMMAND',
    step_status VARCHAR(20),
    attempt_number INTEGER,

    FOREIGN KEY (domain, process_id) REFERENCES commandbus.process(domain, process_id)
);

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

CREATE INDEX IF NOT EXISTS idx_process_audit_process ON commandbus.process_audit(domain, process_id);
CREATE INDEX IF NOT EXISTS idx_process_audit_command ON commandbus.process_audit(command_id);

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

-- Default rate limit configuration
INSERT INTO commandbus.rate_limit_config (resource_key, tokens_per_second, description)
VALUES
    ('default', 100, 'Default rate limit for unconfigured resources')
ON CONFLICT (resource_key) DO NOTHING;

-- ============================================================================
-- Stored Procedures: Command Operations
-- ============================================================================

-- sp_receive_command: Atomically receive a command
CREATE OR REPLACE FUNCTION commandbus.sp_receive_command(
    p_domain TEXT,
    p_command_id UUID,
    p_new_status TEXT DEFAULT 'IN_PROGRESS',
    p_msg_id BIGINT DEFAULT NULL,
    p_max_attempts INT DEFAULT NULL
) RETURNS TABLE (
    domain TEXT,
    command_id UUID,
    command_type TEXT,
    status TEXT,
    attempts INT,
    max_attempts INT,
    msg_id BIGINT,
    correlation_id UUID,
    reply_queue TEXT,
    last_error_type TEXT,
    last_error_code TEXT,
    last_error_msg TEXT,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ,
    batch_id UUID
) AS $$
DECLARE
    v_attempts INT;
    v_max_attempts INT;
    v_command_type TEXT;
    v_status TEXT;
    v_msg_id BIGINT;
    v_correlation_id UUID;
    v_reply_queue TEXT;
    v_last_error_type TEXT;
    v_last_error_code TEXT;
    v_last_error_msg TEXT;
    v_created_at TIMESTAMPTZ;
    v_updated_at TIMESTAMPTZ;
    v_batch_id UUID;
BEGIN
    UPDATE commandbus.command c
    SET attempts = c.attempts + 1,
        status = p_new_status,
        updated_at = NOW()
    WHERE c.domain = p_domain
      AND c.command_id = p_command_id
      AND c.status NOT IN ('COMPLETED', 'CANCELED')
    RETURNING
        c.command_type, c.status, c.attempts, c.max_attempts, c.msg_id,
        c.correlation_id, c.reply_queue, c.last_error_type, c.last_error_code,
        c.last_error_msg, c.created_at, c.updated_at, c.batch_id
    INTO
        v_command_type, v_status, v_attempts, v_max_attempts, v_msg_id,
        v_correlation_id, v_reply_queue, v_last_error_type, v_last_error_code,
        v_last_error_msg, v_created_at, v_updated_at, v_batch_id;

    IF NOT FOUND THEN
        RETURN;
    END IF;

    INSERT INTO commandbus.audit (domain, command_id, event_type, details_json)
    VALUES (
        p_domain, p_command_id, 'RECEIVED',
        jsonb_build_object(
            'msg_id', COALESCE(p_msg_id, v_msg_id),
            'attempt', v_attempts,
            'max_attempts', COALESCE(p_max_attempts, v_max_attempts)
        )
    );

    IF v_batch_id IS NOT NULL THEN
        PERFORM commandbus.sp_start_batch(p_domain, v_batch_id);
    END IF;

    RETURN QUERY SELECT
        p_domain, p_command_id, v_command_type, v_status, v_attempts,
        COALESCE(p_max_attempts, v_max_attempts), COALESCE(p_msg_id, v_msg_id),
        v_correlation_id, v_reply_queue, v_last_error_type, v_last_error_code,
        v_last_error_msg, v_created_at, v_updated_at, v_batch_id;
END;
$$ LANGUAGE plpgsql;


-- sp_finish_command: Atomically finish a command (success or failure)
CREATE OR REPLACE FUNCTION commandbus.sp_finish_command(
    p_domain TEXT,
    p_command_id UUID,
    p_status TEXT,
    p_event_type TEXT,
    p_error_type TEXT DEFAULT NULL,
    p_error_code TEXT DEFAULT NULL,
    p_error_msg TEXT DEFAULT NULL,
    p_details JSONB DEFAULT NULL,
    p_batch_id UUID DEFAULT NULL
) RETURNS BOOLEAN AS $$
DECLARE
    v_current_status TEXT;
BEGIN
    SELECT status INTO v_current_status
    FROM commandbus.command
    WHERE domain = p_domain AND command_id = p_command_id
    FOR UPDATE;

    IF v_current_status IS NULL THEN
        INSERT INTO commandbus.audit (domain, command_id, event_type, details_json)
        VALUES (p_domain, p_command_id, p_event_type, p_details);
        RETURN FALSE;
    END IF;

    IF v_current_status = p_status THEN
        INSERT INTO commandbus.audit (domain, command_id, event_type, details_json)
        VALUES (p_domain, p_command_id, p_event_type, p_details);
        RETURN FALSE;
    END IF;

    UPDATE commandbus.command
    SET status = p_status,
        last_error_type = COALESCE(p_error_type, last_error_type),
        last_error_code = COALESCE(p_error_code, last_error_code),
        last_error_msg = COALESCE(p_error_msg, last_error_msg),
        updated_at = NOW()
    WHERE domain = p_domain AND command_id = p_command_id;

    INSERT INTO commandbus.audit (domain, command_id, event_type, details_json)
    VALUES (p_domain, p_command_id, p_event_type, p_details);

    RETURN FALSE;
END;
$$ LANGUAGE plpgsql;


-- sp_fail_command: Handle transient failure with error update + audit
CREATE OR REPLACE FUNCTION commandbus.sp_fail_command(
    p_domain TEXT,
    p_command_id UUID,
    p_error_type TEXT,
    p_error_code TEXT,
    p_error_msg TEXT,
    p_attempt INT,
    p_max_attempts INT,
    p_msg_id BIGINT
) RETURNS BOOLEAN AS $$
BEGIN
    UPDATE commandbus.command
    SET last_error_type = p_error_type,
        last_error_code = p_error_code,
        last_error_msg = p_error_msg,
        updated_at = NOW()
    WHERE domain = p_domain AND command_id = p_command_id;

    IF NOT FOUND THEN
        RETURN FALSE;
    END IF;

    INSERT INTO commandbus.audit (domain, command_id, event_type, details_json)
    VALUES (
        p_domain, p_command_id, 'FAILED',
        jsonb_build_object(
            'msg_id', p_msg_id, 'attempt', p_attempt, 'max_attempts', p_max_attempts,
            'error_type', p_error_type, 'error_code', p_error_code, 'error_msg', p_error_msg
        )
    );

    RETURN TRUE;
END;
$$ LANGUAGE plpgsql;


-- ============================================================================
-- Stored Procedures: Batch Operations
-- ============================================================================

-- sp_update_batch_counters: Central helper for updating batch counters
CREATE OR REPLACE FUNCTION commandbus.sp_update_batch_counters(
    p_domain TEXT,
    p_batch_id UUID,
    p_update_type TEXT
) RETURNS BOOLEAN AS $$
DECLARE
    v_batch RECORD;
    v_is_complete BOOLEAN := FALSE;
BEGIN
    IF p_batch_id IS NULL THEN
        RETURN FALSE;
    END IF;

    CASE p_update_type
        WHEN 'complete' THEN
            UPDATE commandbus.batch SET completed_count = completed_count + 1
            WHERE domain = p_domain AND batch_id = p_batch_id RETURNING * INTO v_batch;
        WHEN 'tsq_move' THEN
            UPDATE commandbus.batch SET in_troubleshooting_count = in_troubleshooting_count + 1
            WHERE domain = p_domain AND batch_id = p_batch_id RETURNING * INTO v_batch;
        WHEN 'tsq_complete' THEN
            UPDATE commandbus.batch SET in_troubleshooting_count = in_troubleshooting_count - 1,
                completed_count = completed_count + 1
            WHERE domain = p_domain AND batch_id = p_batch_id RETURNING * INTO v_batch;
        WHEN 'tsq_cancel' THEN
            UPDATE commandbus.batch SET in_troubleshooting_count = in_troubleshooting_count - 1,
                canceled_count = canceled_count + 1
            WHERE domain = p_domain AND batch_id = p_batch_id RETURNING * INTO v_batch;
        WHEN 'tsq_retry' THEN
            UPDATE commandbus.batch SET in_troubleshooting_count = in_troubleshooting_count - 1
            WHERE domain = p_domain AND batch_id = p_batch_id RETURNING * INTO v_batch;
        ELSE
            RAISE EXCEPTION 'Unknown update_type: %', p_update_type;
    END CASE;

    IF v_batch IS NULL THEN RETURN FALSE; END IF;

    IF v_batch.completed_count + v_batch.canceled_count = v_batch.total_count
       AND v_batch.in_troubleshooting_count = 0 THEN
        v_is_complete := TRUE;
        IF v_batch.canceled_count > 0 THEN
            UPDATE commandbus.batch SET status = 'COMPLETED_WITH_FAILURES', completed_at = NOW()
            WHERE domain = p_domain AND batch_id = p_batch_id;
        ELSE
            UPDATE commandbus.batch SET status = 'COMPLETED', completed_at = NOW()
            WHERE domain = p_domain AND batch_id = p_batch_id;
        END IF;

        INSERT INTO commandbus.audit (domain, command_id, event_type, details_json)
        VALUES (p_domain, p_batch_id, 'BATCH_COMPLETED',
            jsonb_build_object('batch_id', p_batch_id, 'total_count', v_batch.total_count,
                'completed_count', v_batch.completed_count, 'canceled_count', v_batch.canceled_count));
    END IF;

    RETURN v_is_complete;
END;
$$ LANGUAGE plpgsql;


-- sp_start_batch: Transition batch from PENDING to IN_PROGRESS
CREATE OR REPLACE FUNCTION commandbus.sp_start_batch(
    p_domain TEXT,
    p_batch_id UUID
) RETURNS BOOLEAN AS $$
DECLARE
    v_batch RECORD;
BEGIN
    IF p_batch_id IS NULL THEN RETURN FALSE; END IF;

    SELECT * INTO v_batch FROM commandbus.batch
    WHERE domain = p_domain AND batch_id = p_batch_id FOR UPDATE;

    IF v_batch IS NULL THEN RETURN FALSE; END IF;

    IF v_batch.status = 'PENDING' THEN
        UPDATE commandbus.batch SET status = 'IN_PROGRESS', started_at = NOW()
        WHERE domain = p_domain AND batch_id = p_batch_id;

        INSERT INTO commandbus.audit (domain, command_id, event_type, details_json)
        VALUES (p_domain, p_batch_id, 'BATCH_STARTED', jsonb_build_object('batch_id', p_batch_id));

        RETURN TRUE;
    END IF;

    RETURN FALSE;
END;
$$ LANGUAGE plpgsql;


-- sp_refresh_batch_stats: Calculate batch stats on demand (with failed_count)
-- Supports both COMMAND batches and PROCESS batches (STEP_BASED and PROCESS_STEP)
CREATE OR REPLACE FUNCTION commandbus.sp_refresh_batch_stats(
    p_domain TEXT,
    p_batch_id UUID
) RETURNS TABLE (
    completed_count BIGINT,
    failed_count BIGINT,
    canceled_count BIGINT,
    in_troubleshooting_count BIGINT,
    is_complete BOOLEAN
) AS $$
DECLARE
    v_total_count INT;
    v_batch_type TEXT;
    v_completed BIGINT;
    v_failed BIGINT;
    v_canceled BIGINT;
    v_in_tsq BIGINT;
    v_in_progress BIGINT;
    v_is_complete BOOLEAN;
BEGIN
    SELECT b.total_count, b.batch_type INTO v_total_count, v_batch_type
    FROM commandbus.batch b WHERE b.domain = p_domain AND b.batch_id = p_batch_id;

    IF v_total_count IS NULL THEN RETURN; END IF;

    IF v_batch_type = 'PROCESS' THEN
        -- PROCESS batches: count from process table
        -- Handles both STEP_BASED (commands in TSQ) and PROCESS_STEP (WAITING_FOR_TSQ status)
        SELECT
            -- Completed: COMPLETED or COMPENSATED
            COALESCE(SUM(CASE WHEN p.status IN ('COMPLETED', 'COMPENSATED') THEN 1 ELSE 0 END), 0),
            -- Failed: FAILED status (terminal failure without compensation)
            COALESCE(SUM(CASE WHEN p.status = 'FAILED' THEN 1 ELSE 0 END), 0),
            -- Canceled: CANCELED status
            COALESCE(SUM(CASE WHEN p.status = 'CANCELED' THEN 1 ELSE 0 END), 0),
            -- In TSQ: Either WAITING_FOR_TSQ status (PROCESS_STEP) OR has command in TSQ (STEP_BASED)
            COALESCE(SUM(CASE
                WHEN p.status = 'WAITING_FOR_TSQ' THEN 1
                WHEN p.status NOT IN ('COMPLETED', 'COMPENSATED', 'FAILED', 'CANCELED', 'WAITING_FOR_TSQ')
                     AND EXISTS (SELECT 1 FROM commandbus.command c
                         WHERE c.correlation_id = p.process_id AND c.status = 'IN_TROUBLESHOOTING_QUEUE')
                THEN 1
                ELSE 0 END), 0),
            -- In progress: everything else (PENDING, IN_PROGRESS, EXECUTING, WAITING_FOR_ASYNC, WAITING_FOR_RETRY)
            COALESCE(SUM(CASE
                WHEN p.status NOT IN ('COMPLETED', 'COMPENSATED', 'FAILED', 'CANCELED', 'WAITING_FOR_TSQ')
                     AND NOT EXISTS (SELECT 1 FROM commandbus.command c
                         WHERE c.correlation_id = p.process_id AND c.status = 'IN_TROUBLESHOOTING_QUEUE')
                THEN 1
                ELSE 0 END), 0)
        INTO v_completed, v_failed, v_canceled, v_in_tsq, v_in_progress
        FROM commandbus.process p WHERE p.batch_id = p_batch_id;
    ELSE
        -- COMMAND batches: count from command table
        SELECT
            COALESCE(SUM(CASE WHEN status = 'COMPLETED' THEN 1 ELSE 0 END), 0),
            COALESCE(SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END), 0),
            COALESCE(SUM(CASE WHEN status = 'CANCELED' THEN 1 ELSE 0 END), 0),
            COALESCE(SUM(CASE WHEN status = 'IN_TROUBLESHOOTING_QUEUE' THEN 1 ELSE 0 END), 0),
            COALESCE(SUM(CASE WHEN status NOT IN ('COMPLETED', 'FAILED', 'CANCELED', 'IN_TROUBLESHOOTING_QUEUE') THEN 1 ELSE 0 END), 0)
        INTO v_completed, v_failed, v_canceled, v_in_tsq, v_in_progress
        FROM commandbus.command WHERE batch_id = p_batch_id;
    END IF;

    -- Batch is complete when all items are in terminal state (not in progress)
    v_is_complete := v_in_progress = 0 AND (v_completed + v_failed + v_canceled + v_in_tsq) > 0;

    UPDATE commandbus.batch
    SET completed_count = v_completed, failed_count = v_failed,
        canceled_count = v_canceled, in_troubleshooting_count = v_in_tsq,
        status = CASE
            WHEN v_is_complete AND (v_failed > 0 OR v_canceled > 0 OR v_in_tsq > 0) THEN 'COMPLETED_WITH_FAILURES'
            WHEN v_is_complete THEN 'COMPLETED'
            WHEN status = 'PENDING' AND (v_completed + v_failed + v_canceled + v_in_tsq + v_in_progress) > 0 THEN 'IN_PROGRESS'
            ELSE status END,
        completed_at = CASE WHEN v_is_complete AND completed_at IS NULL THEN NOW() ELSE completed_at END,
        started_at = CASE WHEN started_at IS NULL AND (v_completed + v_failed + v_canceled + v_in_tsq + v_in_progress) > 0 THEN NOW() ELSE started_at END
    WHERE domain = p_domain AND batch_id = p_batch_id;

    RETURN QUERY SELECT v_completed, v_failed, v_canceled, v_in_tsq, v_is_complete;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION commandbus.sp_refresh_batch_stats IS
'Calculate and update batch statistics on demand.
For PROCESS batches: Handles both STEP_BASED (checks command table for TSQ) and PROCESS_STEP (checks WAITING_FOR_TSQ status).
For COMMAND batches: Counts from command table.';


-- ============================================================================
-- Stored Procedures: TSQ Operations
-- ============================================================================

CREATE OR REPLACE FUNCTION commandbus.sp_tsq_complete(p_domain TEXT, p_batch_id UUID) RETURNS BOOLEAN AS $$
BEGIN
    IF p_batch_id IS NULL THEN RETURN FALSE; END IF;
    RETURN commandbus.sp_update_batch_counters(p_domain, p_batch_id, 'tsq_complete');
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION commandbus.sp_tsq_cancel(p_domain TEXT, p_batch_id UUID) RETURNS BOOLEAN AS $$
BEGIN
    IF p_batch_id IS NULL THEN RETURN FALSE; END IF;
    RETURN commandbus.sp_update_batch_counters(p_domain, p_batch_id, 'tsq_cancel');
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION commandbus.sp_tsq_retry(p_domain TEXT, p_batch_id UUID) RETURNS BOOLEAN AS $$
BEGIN
    IF p_batch_id IS NULL THEN RETURN FALSE; END IF;
    RETURN commandbus.sp_update_batch_counters(p_domain, p_batch_id, 'tsq_retry');
END;
$$ LANGUAGE plpgsql;


-- ============================================================================
-- Stored Procedures: Process State Operations
-- ============================================================================

-- sp_update_process_state: Atomic process state updates (BaseProcessManager)
CREATE OR REPLACE FUNCTION commandbus.sp_update_process_state(
    p_domain TEXT,
    p_process_id UUID,
    p_state_patch JSONB,
    p_new_step TEXT DEFAULT NULL,
    p_new_status TEXT DEFAULT NULL,
    p_error_code TEXT DEFAULT NULL,
    p_error_message TEXT DEFAULT NULL
) RETURNS VOID AS $$
BEGIN
    UPDATE commandbus.process
    SET
        state = state || jsonb_strip_nulls(p_state_patch),
        current_step = COALESCE(p_new_step, current_step),
        status = CASE
            WHEN status IN ('COMPLETED', 'FAILED', 'CANCELED', 'COMPENSATED')
                 AND (p_new_status IS NULL OR p_new_status NOT IN ('COMPLETED', 'FAILED', 'CANCELED', 'COMPENSATED'))
            THEN status
            ELSE COALESCE(p_new_status, status)
        END,
        error_code = COALESCE(p_error_code, error_code),
        error_message = COALESCE(p_error_message, error_message),
        updated_at = NOW(),
        completed_at = CASE WHEN p_new_status IN ('COMPLETED', 'FAILED', 'CANCELED') THEN NOW() ELSE completed_at END
    WHERE domain = p_domain AND process_id = p_process_id;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION commandbus.sp_update_process_state IS
'Atomic state update using JSONB merge with jsonb_strip_nulls. Reduces lock contention.';


-- sp_update_process_state_step: ProcessStepManager state updates
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
    SELECT status INTO v_current_status FROM commandbus.process
    WHERE domain = p_domain AND process_id = p_process_id;

    IF v_current_status IN ('COMPLETED', 'FAILED', 'COMPENSATED', 'CANCELED') THEN
        IF p_state_patch IS NOT NULL THEN
            UPDATE commandbus.process SET state = state || p_state_patch, updated_at = NOW()
            WHERE domain = p_domain AND process_id = p_process_id;
        END IF;
        RETURN;
    END IF;

    UPDATE commandbus.process
    SET state = CASE WHEN p_state_patch IS NOT NULL THEN state || p_state_patch ELSE state END,
        current_step = COALESCE(p_new_step, current_step),
        status = COALESCE(p_new_status, status),
        error_code = COALESCE(p_error_code, error_code),
        error_message = COALESCE(p_error_message, error_message),
        next_retry_at = CASE WHEN p_new_status = 'WAITING_FOR_RETRY' THEN p_next_retry_at ELSE NULL END,
        next_wait_timeout_at = CASE WHEN p_new_status = 'WAITING_FOR_ASYNC' THEN p_next_wait_timeout_at ELSE NULL END,
        current_wait = CASE WHEN p_new_status = 'WAITING_FOR_ASYNC' THEN p_current_wait ELSE NULL END,
        completed_at = CASE WHEN p_new_status IN ('COMPLETED', 'FAILED', 'COMPENSATED', 'CANCELED') THEN NOW() ELSE completed_at END,
        updated_at = NOW()
    WHERE domain = p_domain AND process_id = p_process_id;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION commandbus.sp_update_process_state_step IS
'Atomically update process state for ProcessStepManager with scheduler fields.';


-- sp_update_process_with_audit: Atomic state + audit update
CREATE OR REPLACE FUNCTION commandbus.sp_update_process_with_audit(
    p_domain TEXT,
    p_process_id UUID,
    p_state_json TEXT DEFAULT NULL,
    p_state_patch JSONB DEFAULT NULL,
    p_new_step TEXT DEFAULT NULL,
    p_new_status TEXT DEFAULT NULL,
    p_error_code TEXT DEFAULT NULL,
    p_error_message TEXT DEFAULT NULL,
    p_next_retry_at TIMESTAMPTZ DEFAULT NULL,
    p_next_wait_timeout_at TIMESTAMPTZ DEFAULT NULL,
    p_current_wait TEXT DEFAULT NULL,
    p_audit_step_name TEXT DEFAULT NULL,
    p_audit_command_id UUID DEFAULT NULL,
    p_audit_command_type TEXT DEFAULT NULL,
    p_audit_command_data JSONB DEFAULT NULL,
    p_audit_sent_at TIMESTAMPTZ DEFAULT NULL,
    p_audit_reply_outcome TEXT DEFAULT NULL,
    p_audit_reply_data JSONB DEFAULT NULL,
    p_audit_received_at TIMESTAMPTZ DEFAULT NULL
)
RETURNS VOID AS $$
DECLARE
    v_current_status TEXT;
BEGIN
    SELECT status INTO v_current_status FROM commandbus.process
    WHERE domain = p_domain AND process_id = p_process_id;

    IF v_current_status IN ('COMPLETED', 'FAILED', 'COMPENSATED', 'CANCELED') THEN
        IF p_state_json IS NOT NULL THEN
            UPDATE commandbus.process SET state = p_state_json::jsonb, updated_at = NOW()
            WHERE domain = p_domain AND process_id = p_process_id;
        ELSIF p_state_patch IS NOT NULL THEN
            UPDATE commandbus.process SET state = state || p_state_patch, updated_at = NOW()
            WHERE domain = p_domain AND process_id = p_process_id;
        END IF;

        IF p_audit_step_name IS NOT NULL THEN
            INSERT INTO commandbus.process_audit (
                domain, process_id, step_name, command_id, command_type,
                command_data, sent_at, reply_outcome, reply_data, received_at
            ) VALUES (
                p_domain, p_process_id, p_audit_step_name, p_audit_command_id, p_audit_command_type,
                p_audit_command_data, p_audit_sent_at, p_audit_reply_outcome, p_audit_reply_data, p_audit_received_at
            );
        END IF;
        RETURN;
    END IF;

    UPDATE commandbus.process
    SET state = CASE
            WHEN p_state_json IS NOT NULL THEN p_state_json::jsonb
            WHEN p_state_patch IS NOT NULL THEN state || p_state_patch
            ELSE state END,
        current_step = COALESCE(p_new_step, current_step),
        status = COALESCE(p_new_status, status),
        error_code = COALESCE(p_error_code, error_code),
        error_message = COALESCE(p_error_message, error_message),
        next_retry_at = CASE WHEN p_new_status = 'WAITING_FOR_RETRY' THEN p_next_retry_at ELSE NULL END,
        next_wait_timeout_at = CASE WHEN p_new_status = 'WAITING_FOR_ASYNC' THEN p_next_wait_timeout_at ELSE NULL END,
        current_wait = CASE WHEN p_new_status = 'WAITING_FOR_ASYNC' THEN p_current_wait ELSE NULL END,
        completed_at = CASE WHEN p_new_status IN ('COMPLETED', 'FAILED', 'COMPENSATED', 'CANCELED') THEN NOW() ELSE completed_at END,
        updated_at = NOW()
    WHERE domain = p_domain AND process_id = p_process_id;

    IF p_audit_step_name IS NOT NULL THEN
        INSERT INTO commandbus.process_audit (
            domain, process_id, step_name, command_id, command_type,
            command_data, sent_at, reply_outcome, reply_data, received_at
        ) VALUES (
            p_domain, p_process_id, p_audit_step_name, p_audit_command_id, p_audit_command_type,
            p_audit_command_data, p_audit_sent_at, p_audit_reply_outcome, p_audit_reply_data, p_audit_received_at
        );
    END IF;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION commandbus.sp_update_process_with_audit IS
'Atomically update process state and insert audit entry in a single transaction.';


-- ============================================================================
-- Stored Procedures: Process Claiming (Distributed Workers)
-- ============================================================================

-- sp_claim_pending_processes: Claim N pending processes atomically
CREATE OR REPLACE FUNCTION commandbus.sp_claim_pending_processes(
    p_domain TEXT,
    p_process_type TEXT,
    p_batch_size INTEGER,
    p_processing_timeout_seconds INTEGER DEFAULT 60
)
RETURNS TABLE(process_id UUID) AS $$
BEGIN
    -- First, release any stale claims (processes stuck in IN_PROGRESS too long)
    UPDATE commandbus.process p
    SET status = 'PENDING', claimed_at = NULL, updated_at = NOW()
    WHERE p.domain = p_domain AND p.process_type = p_process_type
      AND p.execution_model = 'PROCESS_STEP' AND p.status = 'IN_PROGRESS'
      AND p.claimed_at IS NOT NULL
      AND p.claimed_at < NOW() - (p_processing_timeout_seconds || ' seconds')::INTERVAL;

    -- Claim pending processes atomically using FOR UPDATE SKIP LOCKED
    RETURN QUERY
    WITH claimed AS (
        SELECT p.process_id FROM commandbus.process p
        WHERE p.domain = p_domain AND p.process_type = p_process_type
          AND p.execution_model = 'PROCESS_STEP' AND p.status = 'PENDING'
        ORDER BY p.created_at ASC LIMIT p_batch_size
        FOR UPDATE SKIP LOCKED
    )
    UPDATE commandbus.process p
    SET status = 'IN_PROGRESS', claimed_at = NOW(), updated_at = NOW()
    FROM claimed c WHERE p.process_id = c.process_id
    RETURNING p.process_id;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION commandbus.sp_claim_pending_processes IS
'Atomically claim up to p_batch_size PENDING processes for execution.
Uses FOR UPDATE SKIP LOCKED for safe concurrent access by multiple workers.';


-- sp_claim_retry_processes: Claim N retry-due processes atomically
CREATE OR REPLACE FUNCTION commandbus.sp_claim_retry_processes(
    p_domain TEXT,
    p_process_type TEXT,
    p_batch_size INTEGER,
    p_processing_timeout_seconds INTEGER DEFAULT 60
)
RETURNS TABLE(process_id UUID) AS $$
BEGIN
    -- First, release any stale claims
    UPDATE commandbus.process p
    SET status = 'WAITING_FOR_RETRY', claimed_at = NULL, updated_at = NOW()
    WHERE p.domain = p_domain AND p.process_type = p_process_type
      AND p.execution_model = 'PROCESS_STEP' AND p.status = 'IN_PROGRESS'
      AND p.claimed_at IS NOT NULL
      AND p.claimed_at < NOW() - (p_processing_timeout_seconds || ' seconds')::INTERVAL
      AND p.next_retry_at IS NOT NULL;

    -- Claim retry-due processes atomically
    RETURN QUERY
    WITH claimed AS (
        SELECT p.process_id FROM commandbus.process p
        WHERE p.domain = p_domain AND p.process_type = p_process_type
          AND p.execution_model = 'PROCESS_STEP' AND p.status = 'WAITING_FOR_RETRY'
          AND p.next_retry_at IS NOT NULL AND p.next_retry_at <= NOW()
        ORDER BY p.next_retry_at ASC LIMIT p_batch_size
        FOR UPDATE SKIP LOCKED
    )
    UPDATE commandbus.process p
    SET status = 'IN_PROGRESS', claimed_at = NOW(), next_retry_at = NULL, updated_at = NOW()
    FROM claimed c WHERE p.process_id = c.process_id
    RETURNING p.process_id;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION commandbus.sp_claim_retry_processes IS
'Atomically claim up to p_batch_size processes due for retry.
Uses FOR UPDATE SKIP LOCKED for safe concurrent access by multiple workers.';


-- sp_release_stale_claims: Release processes claimed longer than timeout
CREATE OR REPLACE FUNCTION commandbus.sp_release_stale_claims(
    p_domain TEXT,
    p_processing_timeout_seconds INTEGER DEFAULT 60
)
RETURNS INTEGER AS $$
DECLARE
    v_count INTEGER;
BEGIN
    WITH released AS (
        UPDATE commandbus.process p
        SET status = CASE WHEN p.next_retry_at IS NOT NULL THEN 'WAITING_FOR_RETRY' ELSE 'PENDING' END,
            claimed_at = NULL, updated_at = NOW()
        WHERE p.domain = p_domain AND p.execution_model = 'PROCESS_STEP'
          AND p.status = 'IN_PROGRESS' AND p.claimed_at IS NOT NULL
          AND p.claimed_at < NOW() - (p_processing_timeout_seconds || ' seconds')::INTERVAL
        RETURNING p.process_id
    )
    SELECT COUNT(*) INTO v_count FROM released;

    RETURN v_count;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION commandbus.sp_release_stale_claims IS
'Release processes that have been claimed for longer than the timeout.
Useful for manual cleanup or scheduled maintenance.';
