package com.ivamare.commandbus.process.step;

import com.ivamare.commandbus.process.ProcessRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Background worker for ProcessStepManager.
 *
 * <p>Handles scheduled tasks using virtual threads:
 * <ul>
 *   <li>Poll for PENDING processes and start execution</li>
 *   <li>Poll for processes due for retry</li>
 *   <li>Check for expired wait timeouts</li>
 *   <li>Check for exceeded process deadlines</li>
 * </ul>
 *
 * <p>Uses atomic claiming with FOR UPDATE SKIP LOCKED to ensure balanced
 * load distribution across multiple worker instances. Each worker claims
 * a batch of processes atomically, preventing duplicate execution.
 *
 * <p>This worker is not a Spring component by default - applications should
 * wire it up with scheduled execution as needed.
 */
public class ProcessStepWorker {

    private static final Logger log = LoggerFactory.getLogger(ProcessStepWorker.class);

    /** Default batch size for claiming processes */
    public static final int DEFAULT_BATCH_SIZE = 100;

    /** Default processing timeout in seconds */
    public static final int DEFAULT_PROCESSING_TIMEOUT_SECONDS = 60;

    private final List<ProcessStepManager<?>> processManagers;
    private final ProcessRepository processRepo;
    private final ExecutorService virtualThreadExecutor;
    private final int batchSize;
    private final int processingTimeoutSeconds;

    private volatile boolean running = false;

    /**
     * Create a new ProcessStepWorker with default settings.
     *
     * @param processManagers List of process managers to poll
     * @param processRepo Process repository for queries
     */
    public ProcessStepWorker(List<ProcessStepManager<?>> processManagers, ProcessRepository processRepo) {
        this(processManagers, processRepo, DEFAULT_BATCH_SIZE, DEFAULT_PROCESSING_TIMEOUT_SECONDS);
    }

    /**
     * Create a new ProcessStepWorker with custom batch size and timeout.
     *
     * @param processManagers List of process managers to poll
     * @param processRepo Process repository for queries
     * @param batchSize Number of processes to claim per poll (should be proportional to virtual thread capacity)
     * @param processingTimeoutSeconds Timeout before a claimed process is considered stale
     */
    public ProcessStepWorker(List<ProcessStepManager<?>> processManagers, ProcessRepository processRepo,
                             int batchSize, int processingTimeoutSeconds) {
        this.processManagers = processManagers;
        this.processRepo = processRepo;
        this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.batchSize = batchSize;
        this.processingTimeoutSeconds = processingTimeoutSeconds;
    }

    /**
     * Start the worker.
     */
    public void start() {
        this.running = true;
        log.info("ProcessStepWorker started with {} managers", processManagers.size());
    }

    /**
     * Stop the worker gracefully.
     */
    public void stop() {
        this.running = false;
        virtualThreadExecutor.shutdown();
        try {
            if (!virtualThreadExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                virtualThreadExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            virtualThreadExecutor.shutdownNow();
        }
        log.info("ProcessStepWorker stopped");
    }

    /**
     * Check if the worker is running.
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Poll for PENDING processes and start execution.
     * Should be called periodically (e.g., every 1 second).
     *
     * <p>Uses atomic claiming with FOR UPDATE SKIP LOCKED to ensure balanced
     * load distribution. Each call claims up to batchSize processes atomically,
     * which are already updated to IN_PROGRESS status.
     */
    public void pollPendingProcesses() {
        if (!running) return;

        for (var pm : processManagers) {
            if (!"PROCESS_STEP".equals(pm.getExecutionModel())) continue;

            log.debug("Polling for pending processes: domain={}, type={}, batchSize={}",
                pm.getDomain(), pm.getProcessType(), batchSize);

            try {
                // Atomically claim a batch of pending processes
                // The stored procedure uses FOR UPDATE SKIP LOCKED for safe concurrency
                // and also releases stale claims from crashed workers
                List<UUID> claimedProcesses = processRepo.claimPendingProcesses(
                    pm.getDomain(), pm.getProcessType(), batchSize);

                if (claimedProcesses.isEmpty()) {
                    log.debug("No pending processes found for {} (type={})",
                        pm.getDomain(), pm.getProcessType());
                } else {
                    log.debug("Claimed {} pending processes for {} (type={})",
                        claimedProcesses.size(), pm.getDomain(), pm.getProcessType());
                }

                for (UUID processId : claimedProcesses) {
                    virtualThreadExecutor.submit(() -> executeProcess(pm, processId, "pending"));
                }
            } catch (Exception e) {
                log.error("Error polling pending processes for domain {}: {}", pm.getDomain(), e.getMessage());
            }
        }
    }

    /**
     * Poll for processes due for retry.
     * Should be called periodically (e.g., every 5 seconds).
     *
     * <p>Uses atomic claiming with FOR UPDATE SKIP LOCKED to ensure balanced
     * load distribution. Each call claims up to batchSize processes atomically.
     */
    public void pollRetries() {
        if (!running) return;

        for (var pm : processManagers) {
            if (!"PROCESS_STEP".equals(pm.getExecutionModel())) continue;

            log.debug("Polling for retry-due processes: domain={}, type={}, batchSize={}",
                pm.getDomain(), pm.getProcessType(), batchSize);

            try {
                // Atomically claim a batch of retry-due processes
                List<UUID> claimedProcesses = processRepo.claimDueForRetry(
                    pm.getDomain(), pm.getProcessType(), Instant.now(), batchSize);

                if (claimedProcesses.isEmpty()) {
                    log.debug("No retry-due processes found for {} (type={})",
                        pm.getDomain(), pm.getProcessType());
                } else {
                    log.debug("Claimed {} retry processes for {} (type={})",
                        claimedProcesses.size(), pm.getDomain(), pm.getProcessType());
                }

                for (UUID processId : claimedProcesses) {
                    virtualThreadExecutor.submit(() -> executeProcess(pm, processId, "retry"));
                }
            } catch (Exception e) {
                log.error("Error polling retries for domain {}: {}", pm.getDomain(), e.getMessage());
            }
        }
    }

    /**
     * Check for expired wait timeouts.
     * Should be called periodically (e.g., every 60 seconds).
     */
    public void checkWaitTimeouts() {
        if (!running) return;

        for (var pm : processManagers) {
            if (!"PROCESS_STEP".equals(pm.getExecutionModel())) continue;

            log.debug("Checking for expired wait timeouts: domain={}, type={}",
                pm.getDomain(), pm.getProcessType());

            try {
                Instant now = Instant.now();
                // Filter by process type to ensure correct manager deserializes the state
                List<UUID> expiredProcesses = processRepo.findExpiredWaits(
                    pm.getDomain(), pm.getProcessType(), now);

                if (expiredProcesses.isEmpty()) {
                    log.debug("No expired wait timeouts found for {} (type={})",
                        pm.getDomain(), pm.getProcessType());
                } else {
                    log.debug("Found {} expired waits for {} (type={}) at {}",
                        expiredProcesses.size(), pm.getDomain(), pm.getProcessType(), now);
                }

                for (UUID processId : expiredProcesses) {
                    virtualThreadExecutor.submit(() -> handleTimeout(pm, processId));
                }
            } catch (Exception e) {
                log.error("Error checking wait timeouts for domain {}: {}", pm.getDomain(), e.getMessage());
            }
        }
    }

    /**
     * Check for exceeded process deadlines.
     * Should be called periodically (e.g., every 60 seconds).
     */
    public void checkDeadlines() {
        if (!running) return;

        for (var pm : processManagers) {
            if (!"PROCESS_STEP".equals(pm.getExecutionModel())) continue;

            log.debug("Checking for expired process deadlines: domain={}, type={}",
                pm.getDomain(), pm.getProcessType());

            try {
                Instant now = Instant.now();
                // Filter by process type to ensure correct manager deserializes the state
                List<UUID> expiredProcesses = processRepo.findExpiredDeadlines(
                    pm.getDomain(), pm.getProcessType(), now);

                if (expiredProcesses.isEmpty()) {
                    log.debug("No expired deadlines found for {} (type={})",
                        pm.getDomain(), pm.getProcessType());
                } else {
                    log.debug("Found {} expired deadlines for {} (type={}) at {}",
                        expiredProcesses.size(), pm.getDomain(), pm.getProcessType(), now);
                }

                for (UUID processId : expiredProcesses) {
                    virtualThreadExecutor.submit(() -> handleDeadline(pm, processId));
                }
            } catch (Exception e) {
                log.error("Error checking deadlines for domain {}: {}", pm.getDomain(), e.getMessage());
            }
        }
    }

    // ========== Internal Methods ==========

    private void executeProcess(ProcessStepManager<?> pm, UUID processId, String reason) {
        try {
            log.debug("Resuming process {} (reason: {})", processId, reason);
            pm.resume(processId);
        } catch (Exception e) {
            log.error("Failed to resume process {} (reason: {}): {}", processId, reason, e.getMessage());
        }
    }

    private void handleTimeout(ProcessStepManager<?> pm, UUID processId) {
        try {
            log.debug("Handling wait timeout for process {}", processId);
            pm.handleWaitTimeout(processId);
        } catch (Exception e) {
            log.error("Failed to handle timeout for process {}: {}", processId, e.getMessage());
        }
    }

    private void handleDeadline(ProcessStepManager<?> pm, UUID processId) {
        try {
            log.debug("Handling deadline exceeded for process {}", processId);
            pm.handleDeadlineExceeded(processId);
        } catch (Exception e) {
            log.error("Failed to handle deadline for process {}: {}", processId, e.getMessage());
        }
    }
}
