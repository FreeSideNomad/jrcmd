package com.ivamare.commandbus.e2e.service;

import com.ivamare.commandbus.api.CommandBus;
import com.ivamare.commandbus.e2e.dto.*;
import com.ivamare.commandbus.e2e.payment.*;
import com.ivamare.commandbus.e2e.payment.step.PaymentStepProcess;
import com.ivamare.commandbus.e2e.payment.step.PaymentStepState;
import com.ivamare.commandbus.e2e.process.OutputType;
import com.ivamare.commandbus.e2e.process.StatementReportProcessManager;
import com.ivamare.commandbus.e2e.process.StepBehavior;
import com.ivamare.commandbus.model.*;
import com.ivamare.commandbus.ops.TroubleshootingQueue;
import com.ivamare.commandbus.process.ProcessAuditEntry;
import com.ivamare.commandbus.process.ProcessRepository;
import com.ivamare.commandbus.process.ProcessStatus;
import com.ivamare.commandbus.repository.AuditRepository;
import com.ivamare.commandbus.repository.BatchRepository;
import com.ivamare.commandbus.repository.CommandRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Service for E2E test application data access.
 */
@Service
public class E2EService {

    private final JdbcTemplate jdbcTemplate;
    private final CommandBus commandBus;
    private final CommandRepository commandRepository;
    private final BatchRepository batchRepository;
    private final ProcessRepository processRepository;
    private final AuditRepository auditRepository;
    private final TroubleshootingQueue tsq;
    private final StatementReportProcessManager statementReportProcessManager;
    private final PaymentRepository paymentRepository;
    private final PaymentProcessManager paymentProcessManager;
    private final PaymentStepProcess paymentStepProcess;

    public E2EService(
            JdbcTemplate jdbcTemplate,
            CommandBus commandBus,
            CommandRepository commandRepository,
            BatchRepository batchRepository,
            ProcessRepository processRepository,
            AuditRepository auditRepository,
            TroubleshootingQueue tsq,
            @Nullable StatementReportProcessManager statementReportProcessManager,
            @Nullable PaymentRepository paymentRepository,
            @Nullable PaymentProcessManager paymentProcessManager,
            @Nullable PaymentStepProcess paymentStepProcess) {
        this.jdbcTemplate = jdbcTemplate;
        this.commandBus = commandBus;
        this.commandRepository = commandRepository;
        this.batchRepository = batchRepository;
        this.processRepository = processRepository;
        this.auditRepository = auditRepository;
        this.tsq = tsq;
        this.statementReportProcessManager = statementReportProcessManager;
        this.paymentRepository = paymentRepository;
        this.paymentProcessManager = paymentProcessManager;
        this.paymentStepProcess = paymentStepProcess;
    }

    // ========== Dashboard ==========

    @Transactional(readOnly = true)
    public DashboardStats getDashboardStats(String domain) {
        long pending = countCommandsByStatus(domain, CommandStatus.PENDING);
        long inProgress = countCommandsByStatus(domain, CommandStatus.IN_PROGRESS);
        long tsqCount = tsq.countTroubleshooting(domain, null);
        long activeBatches = countBatchesByStatuses(domain, List.of(BatchStatus.PENDING, BatchStatus.IN_PROGRESS));
        long completedBatches = countBatchesByStatuses(domain, List.of(BatchStatus.COMPLETED));
        long runningProcesses = countProcessesByStatuses(domain,
            List.of(ProcessStatus.IN_PROGRESS, ProcessStatus.WAITING_FOR_REPLY));
        long waitingProcesses = countProcessesByStatuses(domain, List.of(ProcessStatus.WAITING_FOR_TSQ));

        List<QueueStats> queues = getQueueStats(domain);
        long totalQueues = queues.size();
        long totalMessages = queues.stream().mapToLong(QueueStats::queueDepth).sum();

        return new DashboardStats(
            pending, inProgress, tsqCount, activeBatches, completedBatches,
            runningProcesses, waitingProcesses, totalQueues, totalMessages
        );
    }

    @Transactional(readOnly = true)
    public CommandStatusStats getCommandStats(String domain) {
        long pending = countCommandsByStatus(domain, CommandStatus.PENDING);
        long inProgress = countCommandsByStatus(domain, CommandStatus.IN_PROGRESS);
        long completed = countCommandsByStatus(domain, CommandStatus.COMPLETED);
        long failed = countCommandsByStatus(domain, CommandStatus.FAILED);
        long tsqCount = tsq.countTroubleshooting(domain, null);

        return new CommandStatusStats(pending, inProgress, completed, failed, tsqCount);
    }

    // ========== Commands ==========

    @Transactional(readOnly = true)
    public List<CommandView> getCommands(
            String domain,
            String commandType,
            CommandStatus status,
            Instant fromDate,
            Instant toDate,
            int limit,
            int offset) {
        return commandRepository.query(status, domain, commandType, fromDate, toDate, limit, offset)
            .stream()
            .map(this::toCommandView)
            .toList();
    }

    @Transactional(readOnly = true)
    public Optional<CommandView> getCommandById(String domain, UUID commandId) {
        return commandRepository.get(domain, commandId)
            .map(this::toCommandView);
    }

    @Transactional(readOnly = true)
    public List<AuditEvent> getCommandAuditTrail(String domain, UUID commandId) {
        return auditRepository.getEvents(commandId, domain);
    }

    @Transactional(readOnly = true)
    public long countCommands(String domain, CommandStatus status) {
        return countCommandsByStatus(domain, status);
    }

    @Transactional(readOnly = true)
    public List<String> getDistinctDomains() {
        return commandRepository.getDistinctDomains();
    }

    @Transactional(readOnly = true)
    public List<String> getDistinctCommandTypes(String domain) {
        return commandRepository.getDistinctCommandTypes(domain);
    }

    @Transactional(readOnly = true)
    public List<String> getDistinctProcessDomains() {
        return jdbcTemplate.queryForList(
            "SELECT DISTINCT domain FROM commandbus.process ORDER BY domain",
            String.class
        );
    }

    // ========== TSQ ==========

    @Transactional(readOnly = true)
    public List<TsqCommandView> getTsqCommands(String domain, int limit, int offset) {
        return tsq.listTroubleshooting(domain, null, limit, offset)
            .stream()
            .map(this::toTsqView)
            .toList();
    }

    @Transactional(readOnly = true)
    public int getTsqCount(String domain) {
        return tsq.countTroubleshooting(domain, null);
    }

    @Transactional
    public void retryTsqCommand(String domain, UUID commandId, String operator) {
        tsq.operatorRetry(domain, commandId, operator);
    }

    @Transactional
    public void cancelTsqCommand(String domain, UUID commandId, String reason, String operator) {
        tsq.operatorCancel(domain, commandId, reason, operator);
    }

    @Transactional
    public void completeTsqCommand(String domain, UUID commandId, Map<String, Object> resultData, String operator) {
        tsq.operatorComplete(domain, commandId, resultData, operator);
    }

    @Transactional
    public void retryAllTsq(String domain, String operator) {
        List<TroubleshootingItem> items = tsq.listTroubleshooting(domain, null, 1000, 0);
        for (TroubleshootingItem item : items) {
            tsq.operatorRetry(domain, item.commandId(), operator);
        }
    }

    /**
     * Get all domains that have items in the troubleshooting queue.
     */
    @Transactional(readOnly = true)
    public List<String> getDomainsWithTsqItems() {
        return jdbcTemplate.queryForList(
            "SELECT DISTINCT domain FROM commandbus.command WHERE status = 'IN_TROUBLESHOOTING_QUEUE' ORDER BY domain",
            String.class
        );
    }

    // ========== TSQ Process Operations (for PROCESS_STEP execution model) ==========

    /**
     * Get processes in WAITING_FOR_TSQ status (for TSQ UI tab).
     */
    @Transactional(readOnly = true)
    public List<TsqProcessView> getTsqProcesses(String domain, int limit, int offset) {
        String sql = """
            SELECT process_id, domain, process_type, current_step, current_wait,
                   error_code, error_message, created_at, updated_at
            FROM commandbus.process
            WHERE domain = ? AND status = 'WAITING_FOR_TSQ'
            ORDER BY updated_at DESC
            LIMIT ? OFFSET ?
            """;

        return jdbcTemplate.query(sql, (rs, rowNum) -> new TsqProcessView(
            UUID.fromString(rs.getString("process_id")),
            rs.getString("domain"),
            rs.getString("process_type"),
            rs.getString("current_step"),
            rs.getString("current_wait"),
            rs.getString("error_code"),
            rs.getString("error_message"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
        ), domain, limit, offset);
    }

    /**
     * Retry a TSQ process (mark as PENDING to resume execution).
     */
    @Transactional
    public void retryTsqProcess(String domain, UUID processId) {
        // Clear error and set status to PENDING for ProcessStepWorker to pick up
        jdbcTemplate.update("""
            UPDATE commandbus.process
            SET status = 'PENDING',
                error_code = NULL,
                error_message = NULL,
                updated_at = NOW()
            WHERE domain = ? AND process_id = ? AND status = 'WAITING_FOR_TSQ'
            """, domain, processId);
    }

    /**
     * Cancel a TSQ process with optional compensations.
     */
    @Transactional
    public void cancelTsqProcess(String domain, UUID processId, boolean runCompensations) {
        // For now, just mark as CANCELED. Full compensation support requires ProcessStepManager.
        String newStatus = runCompensations ? "COMPENSATED" : "CANCELED";
        jdbcTemplate.update("""
            UPDATE commandbus.process
            SET status = ?,
                completed_at = NOW(),
                updated_at = NOW()
            WHERE domain = ? AND process_id = ? AND status = 'WAITING_FOR_TSQ'
            """, newStatus, domain, processId);
    }

    /**
     * Complete a TSQ process manually with optional state overrides.
     */
    @Transactional
    public void completeTsqProcess(String domain, UUID processId, Map<String, Object> stateOverrides) {
        if (stateOverrides != null && !stateOverrides.isEmpty()) {
            try {
                String overrideJson = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(stateOverrides);
                jdbcTemplate.update("""
                    UPDATE commandbus.process
                    SET status = 'COMPLETED',
                        state = state || ?::jsonb,
                        completed_at = NOW(),
                        updated_at = NOW()
                    WHERE domain = ? AND process_id = ? AND status = 'WAITING_FOR_TSQ'
                    """, overrideJson, domain, processId);
            } catch (Exception e) {
                throw new RuntimeException("Failed to serialize state overrides", e);
            }
        } else {
            jdbcTemplate.update("""
                UPDATE commandbus.process
                SET status = 'COMPLETED',
                    completed_at = NOW(),
                    updated_at = NOW()
                WHERE domain = ? AND process_id = ? AND status = 'WAITING_FOR_TSQ'
                """, domain, processId);
        }
    }

    /**
     * Retry all TSQ processes for a domain.
     */
    @Transactional
    public void retryAllTsqProcesses(String domain) {
        jdbcTemplate.update("""
            UPDATE commandbus.process
            SET status = 'PENDING',
                error_code = NULL,
                error_message = NULL,
                updated_at = NOW()
            WHERE domain = ? AND status = 'WAITING_FOR_TSQ'
            """, domain);
    }

    // ========== Batches ==========

    @Transactional
    public UUID createBatch(String domain, BatchCreateRequest request) {
        // Build BatchCommand list with behavior embedded in payload
        List<BatchCommand> commands = new java.util.ArrayList<>();
        String commandType = request.commandType() != null ? request.commandType() : "TestCommand";
        Integer maxAttempts = request.maxAttempts() > 0 ? request.maxAttempts() : null;

        for (int i = 0; i < request.commandCount(); i++) {
            Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("index", i);
            payload.put("batchName", request.name());
            if (request.behavior() != null) {
                payload.put("failPermanentPct", request.behavior().failPermanentPct());
                payload.put("failTransientPct", request.behavior().failTransientPct());
                payload.put("failBusinessRulePct", request.behavior().failBusinessRulePct());
                payload.put("timeoutPct", request.behavior().timeoutPct());
                payload.put("minDurationMs", request.behavior().minDurationMs());
                payload.put("maxDurationMs", request.behavior().maxDurationMs());
            }

            commands.add(new BatchCommand(
                commandType,
                UUID.randomUUID(),
                payload,
                null,  // correlationId
                null,  // replyTo
                maxAttempts
            ));
        }

        // Create batch using CommandBus
        CreateBatchResult result = commandBus.createBatch(domain, commands);
        return result.batchId();
    }

    @Transactional(readOnly = true)
    public List<BatchView> getBatches(String domain, BatchStatus status, int limit, int offset) {
        return batchRepository.listBatches(domain, status, limit, offset)
            .stream()
            .map(this::toBatchView)
            .toList();
    }

    @Transactional(readOnly = true)
    public Optional<BatchView> getBatchById(String domain, UUID batchId) {
        return batchRepository.get(domain, batchId)
            .map(this::toBatchView);
    }

    /**
     * Refresh batch statistics by calling the stored procedure.
     */
    @Transactional
    public void refreshBatchStats(String domain, UUID batchId) {
        jdbcTemplate.queryForRowSet(
            "SELECT * FROM commandbus.sp_refresh_batch_stats(?, ?)",
            domain, batchId
        );
    }

    /**
     * Refresh stats for all non-completed batches in the domain.
     */
    @Transactional
    public void refreshAllPendingBatchStats(String domain) {
        List<BatchMetadata> pendingBatches = batchRepository.listBatches(domain, null, 100, 0)
            .stream()
            .filter(b -> b.status() == BatchStatus.PENDING || b.status() == BatchStatus.IN_PROGRESS)
            .toList();

        for (BatchMetadata batch : pendingBatches) {
            refreshBatchStats(domain, batch.batchId());
        }
    }

    /**
     * Calculate batch duration from audit events.
     * Returns duration between first RECEIVED and last terminal event (COMPLETED/FAILED/MOVED_TO_TSQ).
     */
    @Transactional(readOnly = true)
    public BatchDuration getBatchDuration(String domain, UUID batchId) {
        String sql = """
            SELECT
                MIN(CASE WHEN event_type = 'RECEIVED' THEN ts END) as first_received,
                MAX(CASE WHEN event_type IN ('COMPLETED', 'FAILED', 'MOVED_TO_TSQ', 'BUSINESS_RULE_FAILED') THEN ts END) as last_completed
            FROM commandbus.audit a
            JOIN commandbus.command c ON a.command_id = c.command_id AND a.domain = c.domain
            WHERE c.batch_id = ? AND a.domain = ?
            """;

        return jdbcTemplate.queryForObject(sql, (rs, rowNum) -> {
            java.sql.Timestamp firstReceived = rs.getTimestamp("first_received");
            java.sql.Timestamp lastCompleted = rs.getTimestamp("last_completed");
            return new BatchDuration(
                firstReceived != null ? firstReceived.toInstant() : null,
                lastCompleted != null ? lastCompleted.toInstant() : null
            );
        }, batchId, domain);
    }

    public record BatchDuration(Instant firstReceived, Instant lastCompleted) {
        public Long durationMs() {
            if (firstReceived == null || lastCompleted == null) return null;
            return java.time.Duration.between(firstReceived, lastCompleted).toMillis();
        }

        public String durationFormatted() {
            Long ms = durationMs();
            if (ms == null) return null;
            if (ms >= 1000) {
                return String.format("%.3fs", ms / 1000.0);
            }
            return ms + "ms";
        }
    }

    @Transactional(readOnly = true)
    public List<CommandView> getBatchCommands(String domain, UUID batchId, int limit, int offset) {
        return commandRepository.listByBatch(domain, batchId, null, limit, offset)
            .stream()
            .map(this::toCommandView)
            .toList();
    }

    // ========== Processes ==========

    @Transactional(readOnly = true)
    public List<ProcessView> getProcesses(
            String domain,
            String processType,
            ProcessStatus status,
            int limit,
            int offset) {
        List<com.ivamare.commandbus.process.ProcessMetadata<?, ?>> processes;
        if (status != null) {
            processes = processRepository.findByStatus(domain, List.of(status));
        } else if (processType != null && !processType.isBlank()) {
            processes = processRepository.findByType(domain, processType);
        } else {
            processes = processRepository.findByStatus(domain, Arrays.asList(ProcessStatus.values()));
        }

        return processes.stream()
            .skip(offset)
            .limit(limit)
            .map(this::toProcessView)
            .toList();
    }

    @Transactional(readOnly = true)
    public Optional<ProcessView> getProcessById(String domain, UUID processId) {
        return processRepository.getById(domain, processId)
            .map(this::toProcessView);
    }

    @Transactional(readOnly = true)
    public List<com.ivamare.commandbus.process.ProcessAuditEntry> getProcessAuditTrail(String domain, UUID processId) {
        return processRepository.getAuditTrail(domain, processId);
    }

    // ========== Queues ==========

    @Transactional(readOnly = true)
    public List<QueueStats> getQueueStats(String domain) {
        String sql = "SELECT queue_name FROM pgmq.meta WHERE queue_name LIKE ?";
        List<String> queueNames = jdbcTemplate.queryForList(sql, String.class, domain + "%");

        return queueNames.stream()
            .map(this::getQueueStatsForQueue)
            .toList();
    }

    @Transactional(readOnly = true)
    public List<QueueStats> getAllQueueStats() {
        String sql = "SELECT queue_name FROM pgmq.meta ORDER BY queue_name";
        List<String> queueNames = jdbcTemplate.queryForList(sql, String.class);

        return queueNames.stream()
            .map(this::getQueueStatsForQueue)
            .toList();
    }

    private QueueStats getQueueStatsForQueue(String queueName) {
        try {
            String depthSql = "SELECT count(*) FROM pgmq.q_" + queueName;
            String archiveSql = "SELECT count(*) FROM pgmq.a_" + queueName;
            String oldestSql = "SELECT MIN(enqueued_at) FROM pgmq.q_" + queueName;

            Long depth = jdbcTemplate.queryForObject(depthSql, Long.class);
            Long archive = jdbcTemplate.queryForObject(archiveSql, Long.class);
            java.sql.Timestamp oldest = jdbcTemplate.queryForObject(oldestSql, java.sql.Timestamp.class);

            return new QueueStats(
                queueName,
                depth != null ? depth : 0,
                archive != null ? archive : 0,
                0,
                oldest != null ? oldest.toInstant() : null
            );
        } catch (Exception e) {
            return new QueueStats(queueName, 0, 0, 0, null);
        }
    }

    // ========== Process Batches ==========

    /**
     * Create a batch of statement report processes.
     */
    @Transactional
    public UUID createProcessBatch(String domain, ProcessBatchCreateRequest request) {
        if (statementReportProcessManager == null) {
            throw new IllegalStateException("StatementReportProcessManager not configured");
        }

        UUID batchId = UUID.randomUUID();
        Instant now = Instant.now();

        // Create batch metadata with batch_type = 'PROCESS'
        BatchMetadata batchMetadata = new BatchMetadata(
            domain, batchId, request.name(), null,
            BatchStatus.PENDING, request.count(), 0, 0, 0,
            now, null, null,
            "PROCESS"
        );
        batchRepository.save(batchMetadata);

        // Create processes
        Random random = new Random();
        for (int i = 0; i < request.count(); i++) {
            // Generate random accounts for each process
            List<String> accounts = new ArrayList<>();
            for (int j = 0; j < request.accountCount(); j++) {
                accounts.add("ACC" + String.format("%06d", random.nextInt(999999)));
            }

            Map<String, Object> initialData = new HashMap<>();
            initialData.put("from_date", request.fromDate().toString());
            initialData.put("to_date", request.toDate().toString());
            initialData.put("account_list", accounts);
            initialData.put("output_type", request.outputType().name());
            if (request.behavior() != null) {
                initialData.put("behavior", request.behavior().toMap());
            }

            // Start the process - it will be linked to batch via batch_id column
            UUID processId = statementReportProcessManager.start(initialData);

            // Update process with batch_id
            jdbcTemplate.update(
                "UPDATE commandbus.process SET batch_id = ? WHERE domain = ? AND process_id = ?",
                batchId, "reporting", processId
            );
        }

        return batchId;
    }

    /**
     * Get process batches (batches with batch_type = 'PROCESS').
     */
    @Transactional(readOnly = true)
    public List<ProcessBatchView> getProcessBatches(String domain, int limit, int offset) {
        String sql = """
            SELECT b.*,
                   COALESCE(ps.completed_count, 0) as proc_completed,
                   COALESCE(ps.failed_count, 0) as proc_failed,
                   COALESCE(ps.in_progress_count, 0) as proc_in_progress,
                   COALESCE(ps.waiting_for_tsq_count, 0) as proc_waiting_tsq
            FROM commandbus.batch b
            LEFT JOIN (
                SELECT batch_id,
                       COUNT(*) FILTER (WHERE status = 'COMPLETED') as completed_count,
                       COUNT(*) FILTER (WHERE status IN ('FAILED', 'COMPENSATED')) as failed_count,
                       COUNT(*) FILTER (WHERE status IN ('IN_PROGRESS', 'WAITING_FOR_REPLY')) as in_progress_count,
                       COUNT(*) FILTER (WHERE status = 'WAITING_FOR_TSQ') as waiting_for_tsq_count
                FROM commandbus.process
                WHERE batch_id IS NOT NULL
                GROUP BY batch_id
            ) ps ON b.batch_id = ps.batch_id
            WHERE b.domain = ? AND b.batch_type = 'PROCESS'
            ORDER BY b.created_at DESC
            LIMIT ? OFFSET ?
            """;

        return jdbcTemplate.query(sql, (rs, rowNum) -> new ProcessBatchView(
            UUID.fromString(rs.getString("batch_id")),
            rs.getString("domain"),
            rs.getString("name"),
            BatchStatus.valueOf(rs.getString("status")),
            rs.getInt("total_count"),
            rs.getInt("proc_completed"),
            rs.getInt("proc_failed"),
            rs.getInt("proc_in_progress"),
            rs.getInt("proc_waiting_tsq"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("completed_at") != null ? rs.getTimestamp("completed_at").toInstant() : null
        ), domain, limit, offset);
    }

    /**
     * Get a single process batch by ID.
     */
    @Transactional(readOnly = true)
    public Optional<ProcessBatchView> getProcessBatchById(String domain, UUID batchId) {
        List<ProcessBatchView> batches = getProcessBatches(domain, 1000, 0);
        return batches.stream()
            .filter(b -> b.batchId().equals(batchId))
            .findFirst();
    }

    /**
     * Get processes belonging to a batch.
     */
    @Transactional(readOnly = true)
    public List<ProcessView> getProcessBatchProcesses(String domain, UUID batchId, ProcessStatus status, int limit, int offset) {
        StringBuilder sql = new StringBuilder("""
            SELECT * FROM commandbus.process
            WHERE batch_id = ?
            """);
        List<Object> params = new ArrayList<>();
        params.add(batchId);

        if (status != null) {
            sql.append(" AND status = ?");
            params.add(status.name());
        }

        sql.append(" ORDER BY created_at DESC LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);

        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> {
            String stateJson = rs.getString("state");
            Map<String, Object> stateMap = stateJson != null ? parseJson(stateJson) : Map.of();

            return new ProcessView(
                UUID.fromString(rs.getString("process_id")),
                rs.getString("domain"),
                rs.getString("process_type"),
                ProcessStatus.valueOf(rs.getString("status")),
                rs.getString("current_step"),
                stateMap,
                rs.getString("error_code"),
                rs.getString("error_message"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getTimestamp("completed_at") != null ? rs.getTimestamp("completed_at").toInstant() : null
            );
        }, params.toArray());
    }

    /**
     * Count processes in a batch.
     */
    @Transactional(readOnly = true)
    public int countProcessBatchProcesses(UUID batchId, ProcessStatus status) {
        String sql = status != null
            ? "SELECT COUNT(*) FROM commandbus.process WHERE batch_id = ? AND status = ?"
            : "SELECT COUNT(*) FROM commandbus.process WHERE batch_id = ?";

        Integer count = status != null
            ? jdbcTemplate.queryForObject(sql, Integer.class, batchId, status.name())
            : jdbcTemplate.queryForObject(sql, Integer.class, batchId);

        return count != null ? count : 0;
    }

    /**
     * Refresh process batch stats and update batch status.
     */
    @Transactional
    public void refreshProcessBatchStats(String domain, UUID batchId) {
        // Get current process counts
        String countSql = """
            SELECT
                COUNT(*) FILTER (WHERE status = 'COMPLETED') as completed,
                COUNT(*) FILTER (WHERE status IN ('FAILED', 'COMPENSATED')) as failed,
                COUNT(*) FILTER (WHERE status = 'WAITING_FOR_TSQ') as in_tsq
            FROM commandbus.process
            WHERE batch_id = ?
            """;

        jdbcTemplate.query(countSql, (rs, rowNum) -> {
            int completed = rs.getInt("completed");
            int failed = rs.getInt("failed");
            int inTsq = rs.getInt("in_tsq");

            // Get total count from batch
            Integer totalCount = jdbcTemplate.queryForObject(
                "SELECT total_count FROM commandbus.batch WHERE domain = ? AND batch_id = ?",
                Integer.class, domain, batchId
            );

            if (totalCount == null) return null;

            // Determine batch status
            int terminal = completed + failed + inTsq;
            BatchStatus newStatus;
            Instant completedAt = null;

            if (terminal == 0) {
                newStatus = BatchStatus.PENDING;
            } else if (terminal >= totalCount) {
                newStatus = BatchStatus.COMPLETED;
                completedAt = Instant.now();
            } else {
                newStatus = BatchStatus.IN_PROGRESS;
            }

            // Update batch
            jdbcTemplate.update("""
                UPDATE commandbus.batch
                SET status = ?, completed_count = ?, canceled_count = ?,
                    in_troubleshooting_count = ?, completed_at = ?,
                    started_at = COALESCE(started_at, NOW())
                WHERE domain = ? AND batch_id = ?
                """,
                newStatus.name(), completed, failed, inTsq,
                completedAt != null ? java.sql.Timestamp.from(completedAt) : null,
                domain, batchId
            );

            return null;
        }, batchId);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    // ========== Helper Methods ==========

    private long countCommandsByStatus(String domain, CommandStatus status) {
        String sql = "SELECT COUNT(*) FROM commandbus.command WHERE domain = ? AND status = ?";
        Long count = jdbcTemplate.queryForObject(sql, Long.class, domain, status.name());
        return count != null ? count : 0;
    }

    private long countBatchesByStatuses(String domain, List<BatchStatus> statuses) {
        if (statuses.isEmpty()) return 0;
        String placeholders = String.join(",", statuses.stream().map(s -> "?").toList());
        Object[] params = new Object[statuses.size() + 1];
        params[0] = domain;
        for (int i = 0; i < statuses.size(); i++) {
            params[i + 1] = statuses.get(i).name();
        }
        String sql = "SELECT COUNT(*) FROM commandbus.batch WHERE domain = ? AND status IN (" + placeholders + ")";
        Long count = jdbcTemplate.queryForObject(sql, Long.class, params);
        return count != null ? count : 0;
    }

    private long countProcessesByStatuses(String domain, List<ProcessStatus> statuses) {
        if (statuses.isEmpty()) return 0;
        String placeholders = String.join(",", statuses.stream().map(s -> "?").toList());
        Object[] params = new Object[statuses.size() + 1];
        params[0] = domain;
        for (int i = 0; i < statuses.size(); i++) {
            params[i + 1] = statuses.get(i).name();
        }
        String sql = "SELECT COUNT(*) FROM commandbus.process WHERE domain = ? AND status IN (" + placeholders + ")";
        Long count = jdbcTemplate.queryForObject(sql, Long.class, params);
        return count != null ? count : 0;
    }

    private CommandView toCommandView(CommandMetadata cmd) {
        return new CommandView(
            cmd.commandId(), cmd.domain(), cmd.commandType(), cmd.status(),
            cmd.correlationId(), cmd.replyTo(), cmd.batchId(),
            cmd.attempts(), cmd.maxAttempts(),
            cmd.createdAt(), cmd.updatedAt(),
            cmd.lastErrorCode(), cmd.lastErrorMessage()
        );
    }

    private TsqCommandView toTsqView(TroubleshootingItem item) {
        return new TsqCommandView(
            item.commandId(), item.domain(), item.commandType(),
            item.payload(), item.lastErrorCode(), item.lastErrorMessage(),
            item.attempts(), item.maxAttempts(),
            item.createdAt(), item.updatedAt()
        );
    }

    private BatchView toBatchView(BatchMetadata batch) {
        return new BatchView(
            batch.batchId(), batch.domain(), batch.name(), batch.status(),
            batch.totalCount(), batch.completedCount(), batch.canceledCount(),
            batch.inTroubleshootingCount(), batch.createdAt(), batch.startedAt(),
            batch.completedAt()
        );
    }

    @SuppressWarnings("unchecked")
    private ProcessView toProcessView(com.ivamare.commandbus.process.ProcessMetadata<?, ?> process) {
        Map<String, Object> stateMap;
        if (process.state() == null) {
            stateMap = Map.of();
        } else {
            stateMap = process.state().toMap();
        }

        return new ProcessView(
            process.processId(), process.domain(), process.processType(),
            process.status(),
            process.currentStep() != null ? process.currentStep().toString() : null,
            stateMap, process.errorCode(), process.errorMessage(),
            process.createdAt(), process.updatedAt(), process.completedAt()
        );
    }

    // ========== Payments ==========

    /**
     * Create a payment and start the payment process.
     *
     * @param executionModel "COMMAND_BASED" (BaseProcessManager) or "STEP_BASED" (ProcessStepManager)
     */
    @Transactional
    public UUID createPayment(Payment payment, PaymentStepBehavior behavior, String executionModel) {
        if (paymentRepository == null) {
            throw new IllegalStateException("Payment components not configured");
        }

        // Save payment
        paymentRepository.save(payment);

        // Start process based on execution model
        if ("STEP_BASED".equals(executionModel)) {
            if (paymentStepProcess == null) {
                throw new IllegalStateException("PaymentStepProcess not configured for STEP_BASED execution");
            }
            // Create PaymentStepState from Payment
            PaymentStepState state = PaymentStepState.fromPayment(payment, behavior);
            return paymentStepProcess.start(state);
        } else {
            // Default to COMMAND_BASED
            if (paymentProcessManager == null) {
                throw new IllegalStateException("PaymentProcessManager not configured for COMMAND_BASED execution");
            }
            return paymentProcessManager.startPayment(payment, behavior);
        }
    }

    /**
     * Create a payment and start the payment process using COMMAND_BASED model.
     * @deprecated Use {@link #createPayment(Payment, PaymentStepBehavior, String)} instead
     */
    @Deprecated
    @Transactional
    public UUID createPayment(Payment payment, PaymentStepBehavior behavior) {
        return createPayment(payment, behavior, "COMMAND_BASED");
    }

    /**
     * Get payments with optional status filter.
     * The filter uses derived status from process status to match what's displayed in UI.
     */
    @Transactional(readOnly = true)
    public List<PaymentView> getPayments(PaymentStatus status, int limit, int offset) {
        if (paymentRepository == null) {
            return List.of();
        }

        if (status == null) {
            // No filter - return all with pagination
            List<Payment> payments = paymentRepository.findAll(limit, offset, jdbcTemplate);
            return payments.stream()
                .map(this::toPaymentView)
                .toList();
        }

        // Filter based on derived status from process
        // Map PaymentStatus to ProcessStatus conditions
        String processStatusCondition = switch (status) {
            case COMPLETE -> "p.status = 'COMPLETED'";
            case FAILED -> "p.status = 'FAILED'";
            case CANCELLED -> "p.status IN ('COMPENSATING', 'COMPENSATED', 'CANCELED')";
            case PROCESSING -> "p.status IN ('IN_PROGRESS', 'WAITING_FOR_REPLY', 'WAITING_FOR_TSQ')";
            case APPROVED -> "p.status = 'PENDING'";
            case DRAFT -> "p.process_id IS NULL";
        };

        String sql = """
            SELECT pay.payment_id
            FROM e2e.payment pay
            LEFT JOIN commandbus.process p ON p.domain = 'payments'
                AND p.state->>'payment_id' = pay.payment_id::text
            WHERE %s
            ORDER BY pay.created_at DESC
            LIMIT ? OFFSET ?
            """.formatted(processStatusCondition);

        List<UUID> paymentIds = jdbcTemplate.queryForList(sql, UUID.class, limit, offset);

        return paymentIds.stream()
            .map(id -> paymentRepository.findById(id, jdbcTemplate))
            .filter(Optional::isPresent)
            .map(Optional::get)
            .map(this::toPaymentView)
            .toList();
    }

    /**
     * Get payment by ID with process info.
     */
    @Transactional(readOnly = true)
    public Optional<PaymentView> getPaymentById(UUID paymentId) {
        if (paymentRepository == null) {
            return Optional.empty();
        }

        return paymentRepository.findById(paymentId, jdbcTemplate)
            .map(this::toPaymentView);
    }

    /**
     * Get payment audit trail (process audit entries).
     */
    @Transactional(readOnly = true)
    public List<ProcessAuditEntry> getPaymentAuditTrail(UUID paymentId) {
        // Find process for this payment
        String sql = """
            SELECT process_id FROM commandbus.process
            WHERE domain = 'payments' AND state->>'payment_id' = ?
            """;
        List<String> processIds = jdbcTemplate.queryForList(sql, String.class, paymentId.toString());

        if (processIds.isEmpty()) {
            return List.of();
        }

        UUID processId = UUID.fromString(processIds.get(0));
        return processRepository.getAuditTrail("payments", processId);
    }

    /**
     * Create a batch of payments.
     */
    @Transactional
    public UUID createPaymentBatch(PaymentBatchCreateRequest request) {
        if (paymentRepository == null) {
            throw new IllegalStateException("Payment components not configured");
        }

        UUID batchId = UUID.randomUUID();
        Random random = new Random();

        List<Payment> payments = new ArrayList<>();
        for (int i = 0; i < request.count(); i++) {
            // Generate random payment
            BigDecimal amount = request.minAmount().add(
                new BigDecimal(random.nextDouble()).multiply(
                    request.maxAmount().subtract(request.minAmount())
                ).setScale(2, java.math.RoundingMode.HALF_UP)
            );

            Payment payment = Payment.builder()
                .debitAccount(DebitAccount.of(
                    String.format("%05d", random.nextInt(99999)),
                    String.format("%08d", random.nextInt(99999999))
                ))
                .creditAccount(CreditAccount.of(
                    generateRandomBic(),
                    generateRandomIban(request.creditCurrency().name())
                ))
                .debitAmount(amount)
                .debitCurrency(request.debitCurrency())
                .creditCurrency(request.creditCurrency())
                .valueDate(request.valueDate())
                .cutoffTimestamp(Instant.now().plus(request.cutoffHours(), ChronoUnit.HOURS))
                .build();

            payments.add(payment);
        }

        // Save batch and payments
        ((JdbcPaymentRepository) paymentRepository).saveBatch(batchId, request.name(), payments);

        // Start processes based on execution model
        if (request.isStepBasedModel()) {
            if (paymentStepProcess == null) {
                throw new IllegalStateException("PaymentStepProcess not configured for STEP_BASED execution");
            }
            // Create states from payments and start batch
            List<PaymentStepState> states = payments.stream()
                .map(p -> PaymentStepState.fromPayment(p, request.behavior()))
                .toList();
            paymentStepProcess.startBatch(states);
        } else {
            // Default to COMMAND_BASED
            if (paymentProcessManager == null) {
                throw new IllegalStateException("PaymentProcessManager not configured for COMMAND_BASED execution");
            }
            paymentProcessManager.startPaymentBatch(payments, request.behavior());
        }

        return batchId;
    }

    /**
     * Get all payment batches with statistics.
     */
    @Transactional(readOnly = true)
    public List<PaymentBatchListItem> getPaymentBatches(int limit, int offset) {
        String sql = """
            SELECT
                b.batch_id, b.name, b.total_count, b.created_at,
                COUNT(*) FILTER (WHERE ps.status = 'COMPLETED') as completed_count,
                COUNT(*) FILTER (WHERE ps.status IN ('FAILED', 'COMPENSATED', 'CANCELED')) as failed_count,
                COUNT(*) FILTER (WHERE ps.status IN ('IN_PROGRESS', 'WAITING_FOR_REPLY', 'WAITING_FOR_TSQ', 'PENDING')) as in_progress_count
            FROM e2e.payment_batch b
            LEFT JOIN e2e.payment_batch_item bi ON b.batch_id = bi.batch_id
            LEFT JOIN e2e.payment p ON bi.payment_id = p.payment_id
            LEFT JOIN commandbus.process ps ON ps.domain = 'payments'
                AND ps.state->>'payment_id' = p.payment_id::text
            GROUP BY b.batch_id, b.name, b.total_count, b.created_at
            ORDER BY b.created_at DESC
            LIMIT ? OFFSET ?
            """;

        return jdbcTemplate.query(sql, (rs, rowNum) -> new PaymentBatchListItem(
            UUID.fromString(rs.getString("batch_id")),
            rs.getString("name"),
            rs.getInt("total_count"),
            rs.getInt("completed_count"),
            rs.getInt("failed_count"),
            rs.getInt("in_progress_count"),
            rs.getTimestamp("created_at").toInstant()
        ), limit, offset);
    }

    /**
     * Payment batch list item with statistics.
     */
    public record PaymentBatchListItem(
        UUID batchId,
        String name,
        int totalCount,
        int completedCount,
        int failedCount,
        int inProgressCount,
        Instant createdAt
    ) {}

    /**
     * Get payment batch by ID.
     */
    @Transactional(readOnly = true)
    public Optional<PaymentBatchInfo> getPaymentBatchById(UUID batchId) {
        String sql = """
            SELECT batch_id, name, total_count, created_at
            FROM e2e.payment_batch WHERE batch_id = ?
            """;

        List<PaymentBatchInfo> batches = jdbcTemplate.query(sql, (rs, rowNum) -> new PaymentBatchInfo(
            UUID.fromString(rs.getString("batch_id")),
            rs.getString("name"),
            rs.getInt("total_count"),
            rs.getTimestamp("created_at").toInstant()
        ), batchId);

        return batches.isEmpty() ? Optional.empty() : Optional.of(batches.get(0));
    }

    /**
     * Get payments in a batch, filtered by display status.
     * Display status values: PROCESSING, COMPLETE, FAILED, CANCELLED, NEEDS_ATTENTION
     */
    @Transactional(readOnly = true)
    public List<PaymentView> getPaymentsByBatchId(UUID batchId, String displayStatus, int limit, int offset) {
        if (paymentRepository == null) {
            return List.of();
        }

        List<Payment> payments = paymentRepository.findByBatchId(batchId, jdbcTemplate);

        // Convert to views first, then filter by display status (which includes process status)
        return payments.stream()
            .map(this::toPaymentView)
            .filter(pv -> displayStatus == null || displayStatus.isBlank() || pv.displayStatus().equals(displayStatus))
            .skip(offset)
            .limit(limit)
            .toList();
    }

    /**
     * Get payment batch statistics.
     */
    @Transactional(readOnly = true)
    public PaymentBatchStats getPaymentBatchStats(UUID batchId) {
        String sql = """
            SELECT
                COUNT(*) as total,
                COUNT(*) FILTER (WHERE ps.status = 'COMPLETED') as completed,
                COUNT(*) FILTER (WHERE ps.status IN ('FAILED', 'COMPENSATED', 'CANCELED')) as failed,
                COUNT(*) FILTER (WHERE ps.status IN ('IN_PROGRESS', 'WAITING_FOR_REPLY', 'WAITING_FOR_TSQ', 'PENDING')) as in_progress
            FROM e2e.payment_batch_item bi
            JOIN e2e.payment p ON bi.payment_id = p.payment_id
            LEFT JOIN commandbus.process ps ON ps.domain = 'payments'
                AND ps.state->>'payment_id' = p.payment_id::text
            WHERE bi.batch_id = ?
            """;

        return jdbcTemplate.queryForObject(sql, (rs, rowNum) -> new PaymentBatchStats(
            rs.getInt("total"),
            rs.getInt("completed"),
            rs.getInt("failed"),
            rs.getInt("in_progress")
        ), batchId);
    }

    private PaymentView toPaymentView(Payment payment) {
        // Try to find associated process
        com.ivamare.commandbus.process.ProcessMetadata<?, ?> process = null;
        String sql = """
            SELECT process_id FROM commandbus.process
            WHERE domain = 'payments' AND state->>'payment_id' = ?
            """;
        List<String> processIds = jdbcTemplate.queryForList(sql, String.class, payment.paymentId().toString());

        if (!processIds.isEmpty()) {
            process = processRepository.getById("payments", UUID.fromString(processIds.get(0))).orElse(null);
        }

        return PaymentView.from(payment, process);
    }

    private String generateRandomBic() {
        String[] banks = {"DEUT", "BNPA", "HSBC", "BARC", "UBSW", "CITI", "JPMO"};
        String[] countries = {"DE", "FR", "GB", "US", "CH", "NL"};
        Random r = new Random();
        return banks[r.nextInt(banks.length)] + countries[r.nextInt(countries.length)] + "FF";
    }

    private String generateRandomIban(String currency) {
        Random r = new Random();
        String countryCode = switch (currency) {
            case "EUR" -> "DE";
            case "GBP" -> "GB";
            case "CHF" -> "CH";
            default -> "DE";
        };
        return countryCode + String.format("%02d", r.nextInt(99)) +
               String.format("%018d", Math.abs(r.nextLong()) % 1_000_000_000_000_000_000L);
    }

    /**
     * Payment batch info record.
     */
    public record PaymentBatchInfo(UUID batchId, String name, int totalCount, Instant createdAt) {}

    /**
     * Payment batch statistics record.
     */
    public record PaymentBatchStats(int totalCount, int completedCount, int failedCount, int inProgressCount) {}
}
