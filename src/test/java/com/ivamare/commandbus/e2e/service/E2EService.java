package com.ivamare.commandbus.e2e.service;

import com.ivamare.commandbus.api.CommandBus;
import com.ivamare.commandbus.e2e.dto.*;
import com.ivamare.commandbus.model.*;
import com.ivamare.commandbus.ops.TroubleshootingQueue;
import com.ivamare.commandbus.process.ProcessRepository;
import com.ivamare.commandbus.process.ProcessStatus;
import com.ivamare.commandbus.repository.AuditRepository;
import com.ivamare.commandbus.repository.BatchRepository;
import com.ivamare.commandbus.repository.CommandRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
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

    public E2EService(
            JdbcTemplate jdbcTemplate,
            CommandBus commandBus,
            CommandRepository commandRepository,
            BatchRepository batchRepository,
            ProcessRepository processRepository,
            AuditRepository auditRepository,
            TroubleshootingQueue tsq) {
        this.jdbcTemplate = jdbcTemplate;
        this.commandBus = commandBus;
        this.commandRepository = commandRepository;
        this.batchRepository = batchRepository;
        this.processRepository = processRepository;
        this.auditRepository = auditRepository;
        this.tsq = tsq;
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
                MIN(CASE WHEN event_type = 'RECEIVED' THEN timestamp END) as first_received,
                MAX(CASE WHEN event_type IN ('COMPLETED', 'FAILED', 'MOVED_TO_TSQ', 'BUSINESS_RULE_FAILED') THEN timestamp END) as last_completed
            FROM commandbus.command_audit a
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
}
