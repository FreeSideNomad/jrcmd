-- V011: Process Claiming Stored Procedures
-- Adds support for atomic process claiming with FOR UPDATE SKIP LOCKED
-- Enables multiple workers to safely compete for pending processes
--
-- Includes:
-- - claimed_at column for tracking when process was claimed
-- - sp_claim_pending_processes: Claim N pending processes atomically
-- - sp_claim_retry_processes: Claim N retry-due processes atomically
-- - sp_release_stale_claims: Release processes claimed longer than timeout

SET search_path TO commandbus, pgmq, public;

-- ============================================================================
-- Process Table Extensions for Claiming
-- ============================================================================

-- Add claimed_at column to track when process was claimed for processing
ALTER TABLE commandbus.process
ADD COLUMN IF NOT EXISTS claimed_at TIMESTAMPTZ;

COMMENT ON COLUMN commandbus.process.claimed_at IS
'Timestamp when this process was claimed by a worker for processing.
Used with processing_timeout to detect stale claims from crashed workers.';

-- Index for finding stale claims (IN_PROGRESS but claimed too long ago)
CREATE INDEX IF NOT EXISTS idx_process_stale_claims
ON commandbus.process(domain, claimed_at)
WHERE status = 'IN_PROGRESS' AND claimed_at IS NOT NULL;

-- ============================================================================
-- Stored Procedure: Claim Pending Processes
-- ============================================================================

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
    SET status = 'PENDING',
        claimed_at = NULL,
        updated_at = NOW()
    WHERE p.domain = p_domain
      AND p.process_type = p_process_type
      AND p.execution_model = 'PROCESS_STEP'
      AND p.status = 'IN_PROGRESS'
      AND p.claimed_at IS NOT NULL
      AND p.claimed_at < NOW() - (p_processing_timeout_seconds || ' seconds')::INTERVAL;

    -- Claim pending processes atomically using FOR UPDATE SKIP LOCKED
    RETURN QUERY
    WITH claimed AS (
        SELECT p.process_id
        FROM commandbus.process p
        WHERE p.domain = p_domain
          AND p.process_type = p_process_type
          AND p.execution_model = 'PROCESS_STEP'
          AND p.status = 'PENDING'
        ORDER BY p.created_at ASC
        LIMIT p_batch_size
        FOR UPDATE SKIP LOCKED
    )
    UPDATE commandbus.process p
    SET status = 'IN_PROGRESS',
        claimed_at = NOW(),
        updated_at = NOW()
    FROM claimed c
    WHERE p.process_id = c.process_id
    RETURNING p.process_id;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION commandbus.sp_claim_pending_processes IS
'Atomically claim up to p_batch_size PENDING processes for execution.
Uses FOR UPDATE SKIP LOCKED for safe concurrent access by multiple workers.
Also releases stale claims from crashed workers based on processing_timeout_seconds.
Returns the claimed process IDs (status already updated to IN_PROGRESS).';

-- ============================================================================
-- Stored Procedure: Claim Retry Processes
-- ============================================================================

CREATE OR REPLACE FUNCTION commandbus.sp_claim_retry_processes(
    p_domain TEXT,
    p_process_type TEXT,
    p_batch_size INTEGER,
    p_processing_timeout_seconds INTEGER DEFAULT 60
)
RETURNS TABLE(process_id UUID) AS $$
BEGIN
    -- First, release any stale claims (same as pending)
    UPDATE commandbus.process p
    SET status = 'WAITING_FOR_RETRY',
        claimed_at = NULL,
        updated_at = NOW()
    WHERE p.domain = p_domain
      AND p.process_type = p_process_type
      AND p.execution_model = 'PROCESS_STEP'
      AND p.status = 'IN_PROGRESS'
      AND p.claimed_at IS NOT NULL
      AND p.claimed_at < NOW() - (p_processing_timeout_seconds || ' seconds')::INTERVAL
      AND p.next_retry_at IS NOT NULL;  -- Was a retry, not a pending

    -- Claim retry-due processes atomically
    RETURN QUERY
    WITH claimed AS (
        SELECT p.process_id
        FROM commandbus.process p
        WHERE p.domain = p_domain
          AND p.process_type = p_process_type
          AND p.execution_model = 'PROCESS_STEP'
          AND p.status = 'WAITING_FOR_RETRY'
          AND p.next_retry_at IS NOT NULL
          AND p.next_retry_at <= NOW()
        ORDER BY p.next_retry_at ASC
        LIMIT p_batch_size
        FOR UPDATE SKIP LOCKED
    )
    UPDATE commandbus.process p
    SET status = 'IN_PROGRESS',
        claimed_at = NOW(),
        next_retry_at = NULL,
        updated_at = NOW()
    FROM claimed c
    WHERE p.process_id = c.process_id
    RETURNING p.process_id;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION commandbus.sp_claim_retry_processes IS
'Atomically claim up to p_batch_size processes due for retry.
Uses FOR UPDATE SKIP LOCKED for safe concurrent access by multiple workers.
Also releases stale claims from crashed workers.
Returns the claimed process IDs (status already updated to IN_PROGRESS).';

-- ============================================================================
-- Stored Procedure: Release Stale Claims (for manual cleanup if needed)
-- ============================================================================

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
        SET status = CASE
                WHEN p.next_retry_at IS NOT NULL THEN 'WAITING_FOR_RETRY'
                ELSE 'PENDING'
            END,
            claimed_at = NULL,
            updated_at = NOW()
        WHERE p.domain = p_domain
          AND p.execution_model = 'PROCESS_STEP'
          AND p.status = 'IN_PROGRESS'
          AND p.claimed_at IS NOT NULL
          AND p.claimed_at < NOW() - (p_processing_timeout_seconds || ' seconds')::INTERVAL
        RETURNING p.process_id
    )
    SELECT COUNT(*) INTO v_count FROM released;

    RETURN v_count;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION commandbus.sp_release_stale_claims IS
'Release processes that have been claimed for longer than the timeout.
Useful for manual cleanup or scheduled maintenance.
Returns the number of released processes.';
