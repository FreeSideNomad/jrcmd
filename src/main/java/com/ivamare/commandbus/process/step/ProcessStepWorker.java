package com.ivamare.commandbus.process.step;

import com.ivamare.commandbus.process.ProcessRepository;
import com.ivamare.commandbus.process.ProcessStatus;
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
 * <p>This worker is not a Spring component by default - applications should
 * wire it up with scheduled execution as needed.
 */
public class ProcessStepWorker {

    private static final Logger log = LoggerFactory.getLogger(ProcessStepWorker.class);

    private final List<ProcessStepManager<?>> processManagers;
    private final ProcessRepository processRepo;
    private final ExecutorService virtualThreadExecutor;

    private volatile boolean running = false;

    /**
     * Create a new ProcessStepWorker.
     *
     * @param processManagers List of process managers to poll
     * @param processRepo Process repository for queries
     */
    public ProcessStepWorker(List<ProcessStepManager<?>> processManagers, ProcessRepository processRepo) {
        this.processManagers = processManagers;
        this.processRepo = processRepo;
        this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
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
     */
    public void pollPendingProcesses() {
        if (!running) return;

        for (var pm : processManagers) {
            if (!"PROCESS_STEP".equals(pm.getExecutionModel())) continue;

            try {
                List<UUID> pendingProcesses = processRepo.findByExecutionModelAndStatus(
                    pm.getDomain(), "PROCESS_STEP", ProcessStatus.PENDING);

                for (UUID processId : pendingProcesses) {
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
     */
    public void pollRetries() {
        if (!running) return;

        for (var pm : processManagers) {
            if (!"PROCESS_STEP".equals(pm.getExecutionModel())) continue;

            try {
                List<UUID> dueProcesses = processRepo.findDueForRetry(pm.getDomain(), Instant.now());

                for (UUID processId : dueProcesses) {
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

            try {
                List<UUID> expiredProcesses = processRepo.findExpiredWaits(pm.getDomain(), Instant.now());

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

            try {
                List<UUID> expiredProcesses = processRepo.findExpiredDeadlines(pm.getDomain(), Instant.now());

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
