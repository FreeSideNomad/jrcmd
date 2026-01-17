-- V009: Add execution_model to pending tables
-- Allows distinguishing between STEP_BASED (BaseProcessManager) and PROCESS_STEP (ProcessStepManager)
-- processes in approval and network response queues.

SET search_path TO e2e, public;

-- ============================================================================
-- Pending Approval Table Extension
-- ============================================================================

ALTER TABLE e2e.pending_approval
ADD COLUMN IF NOT EXISTS execution_model VARCHAR(20) DEFAULT 'STEP_BASED';

COMMENT ON COLUMN e2e.pending_approval.execution_model IS
'Execution model for this approval request:
- STEP_BASED: Uses BaseProcessManager with PGMQ commands
- PROCESS_STEP: Uses ProcessStepManager with deterministic replay';

-- Index for filtering by execution model
CREATE INDEX IF NOT EXISTS idx_pending_approval_execution_model
ON e2e.pending_approval(execution_model, status)
WHERE status = 'PENDING';

-- ============================================================================
-- Pending Network Response Table Extension
-- ============================================================================

ALTER TABLE e2e.pending_network_response
ADD COLUMN IF NOT EXISTS execution_model VARCHAR(20) DEFAULT 'STEP_BASED';

COMMENT ON COLUMN e2e.pending_network_response.execution_model IS
'Execution model for this network response:
- STEP_BASED: Uses BaseProcessManager with PGMQ commands
- PROCESS_STEP: Uses ProcessStepManager with deterministic replay';

-- Index for filtering by execution model
CREATE INDEX IF NOT EXISTS idx_pending_network_response_execution_model
ON e2e.pending_network_response(execution_model, status)
WHERE status = 'PENDING';
