-- V010: Atomic State + Audit Update Stored Procedure
-- Provides atomic updates for ProcessStepManager state and audit trail
--
-- This stored procedure combines:
-- - Process state update (state JSON, status, scheduler columns)
-- - Audit trail entry insertion
-- All in a single atomic transaction to ensure consistency

SET search_path TO commandbus, pgmq, public;

-- ============================================================================
-- Stored Procedure: Atomic Process State + Audit Update
-- ============================================================================

CREATE OR REPLACE FUNCTION commandbus.sp_update_process_with_audit(
    p_domain TEXT,
    p_process_id UUID,
    p_state_json TEXT DEFAULT NULL,           -- Full state JSON (replaces entire state)
    p_state_patch JSONB DEFAULT NULL,         -- State patch (merged with existing state)
    p_new_step TEXT DEFAULT NULL,
    p_new_status TEXT DEFAULT NULL,
    p_error_code TEXT DEFAULT NULL,
    p_error_message TEXT DEFAULT NULL,
    p_next_retry_at TIMESTAMPTZ DEFAULT NULL,
    p_next_wait_timeout_at TIMESTAMPTZ DEFAULT NULL,
    p_current_wait TEXT DEFAULT NULL,
    -- Audit entry parameters
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
    -- Get current status to check for terminal state
    SELECT status INTO v_current_status
    FROM commandbus.process
    WHERE domain = p_domain AND process_id = p_process_id;

    -- Don't overwrite terminal status (race condition protection)
    IF v_current_status IN ('COMPLETED', 'FAILED', 'COMPENSATED', 'CANCELED') THEN
        -- For terminal processes, only update state if provided
        IF p_state_json IS NOT NULL THEN
            UPDATE commandbus.process
            SET state = p_state_json::jsonb,
                updated_at = NOW()
            WHERE domain = p_domain AND process_id = p_process_id;
        ELSIF p_state_patch IS NOT NULL THEN
            UPDATE commandbus.process
            SET state = state || p_state_patch,
                updated_at = NOW()
            WHERE domain = p_domain AND process_id = p_process_id;
        END IF;

        -- Still insert audit entry for terminal processes if provided
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

    -- Full update for non-terminal processes
    UPDATE commandbus.process
    SET state = CASE
            WHEN p_state_json IS NOT NULL THEN p_state_json::jsonb
            WHEN p_state_patch IS NOT NULL THEN state || p_state_patch
            ELSE state
        END,
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

    -- Insert audit entry if step name provided
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
'Atomically update process state and insert audit entry in a single transaction.
Combines the functionality of updateState(), updateStateAtomicStep(), and logStep()
to ensure consistency between process state and audit trail.

Parameters:
- p_state_json: Full state JSON (replaces entire state, mutually exclusive with p_state_patch)
- p_state_patch: State JSONB patch (merged with existing state)
- p_new_step, p_new_status, etc.: Same as sp_update_process_state_step
- p_audit_*: Audit entry fields (only inserted if p_audit_step_name is provided)';
