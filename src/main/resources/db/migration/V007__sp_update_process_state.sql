-- Stored procedure for atomic process state updates
-- Avoids read-modify-write cycle by merging state patch directly in DB

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
        -- Use jsonb_strip_nulls to prevent null values from overwriting existing data
        -- This is critical for concurrent updates (e.g., L1-L4 confirmations)
        state = state || jsonb_strip_nulls(p_state_patch),
        current_step = COALESCE(p_new_step, current_step),
        -- Don't allow non-terminal status to overwrite terminal status.
        -- This handles race conditions where L4 completes before earlier replies are processed.
        status = CASE
            WHEN status IN ('COMPLETED', 'FAILED', 'CANCELED', 'COMPENSATED')
                 AND (p_new_status IS NULL OR p_new_status NOT IN ('COMPLETED', 'FAILED', 'CANCELED', 'COMPENSATED'))
            THEN status  -- Keep terminal status
            ELSE COALESCE(p_new_status, status)
        END,
        error_code = COALESCE(p_error_code, error_code),
        error_message = COALESCE(p_error_message, error_message),
        updated_at = NOW(),
        completed_at = CASE
            WHEN p_new_status IN ('COMPLETED', 'FAILED', 'CANCELED')
            THEN NOW()
            ELSE completed_at
        END
    WHERE domain = p_domain AND process_id = p_process_id;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION commandbus.sp_update_process_state IS
'Atomic state update using JSONB merge with jsonb_strip_nulls. Reduces lock contention and prevents concurrent updates from overwriting each other with nulls.';
