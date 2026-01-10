package com.commandbus.e2e.service;

import com.commandbus.e2e.dto.*;
import com.commandbus.model.*;
import com.commandbus.ops.TroubleshootingQueue;
import com.commandbus.process.ProcessRepository;
import com.commandbus.process.ProcessStatus;
import com.commandbus.repository.AuditRepository;
import com.commandbus.repository.BatchRepository;
import com.commandbus.repository.CommandRepository;
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
    private final CommandRepository commandRepository;
    private final BatchRepository batchRepository;
    private final ProcessRepository processRepository;
    private final AuditRepository auditRepository;
    private final TroubleshootingQueue tsq;

    public E2EService(
            JdbcTemplate jdbcTemplate,
            CommandRepository commandRepository,
            BatchRepository batchRepository,
            ProcessRepository processRepository,
            AuditRepository auditRepository,
            TroubleshootingQueue tsq) {
        this.jdbcTemplate = jdbcTemplate;
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
        List<com.commandbus.process.ProcessMetadata<?, ?>> processes;
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
    public List<com.commandbus.process.ProcessAuditEntry> getProcessAuditTrail(String domain, UUID processId) {
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
    private ProcessView toProcessView(com.commandbus.process.ProcessMetadata<?, ?> process) {
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
