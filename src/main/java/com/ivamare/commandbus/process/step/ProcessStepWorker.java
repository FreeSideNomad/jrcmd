package com.ivamare.commandbus.process.step;

import com.ivamare.commandbus.CommandBusProperties.ResilienceProperties;
import com.ivamare.commandbus.exception.DatabaseExceptionClassifier;
import com.ivamare.commandbus.process.ProcessRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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

    // Resilience configuration
    private final long initialBackoffMs;
    private final long maxBackoffMs;
    private final double backoffMultiplier;
    private final int errorThreshold;
    private final AtomicInteger consecutiveErrors = new AtomicInteger(0);

    private volatile boolean running = false;

    /**
     * Create a new ProcessStepWorker with default settings.
     *
     * @param processManagers List of process managers to poll
     * @param processRepo Process repository for queries
     */
    public ProcessStepWorker(List<ProcessStepManager<?>> processManagers, ProcessRepository processRepo) {
        this(processManagers, processRepo, DEFAULT_BATCH_SIZE, DEFAULT_PROCESSING_TIMEOUT_SECONDS, new ResilienceProperties());
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
        this(processManagers, processRepo, batchSize, processingTimeoutSeconds, new ResilienceProperties());
    }

    /**
     * Create a new ProcessStepWorker with custom settings including resilience configuration.
     *
     * @param processManagers List of process managers to poll
     * @param processRepo Process repository for queries
     * @param batchSize Number of processes to claim per poll (should be proportional to virtual thread capacity)
     * @param processingTimeoutSeconds Timeout before a claimed process is considered stale
     * @param resilience Resilience configuration for database error recovery
     */
    public ProcessStepWorker(List<ProcessStepManager<?>> processManagers, ProcessRepository processRepo,
                             int batchSize, int processingTimeoutSeconds, ResilienceProperties resilience) {
        this.processManagers = processManagers;
        this.processRepo = processRepo;
        this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.batchSize = batchSize;
        this.processingTimeoutSeconds = processingTimeoutSeconds;
        this.initialBackoffMs = resilience.getInitialBackoffMs();
        this.maxBackoffMs = resilience.getMaxBackoffMs();
        this.backoffMultiplier = resilience.getBackoffMultiplier();
        this.errorThreshold = resilience.getErrorThreshold();
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

            log.trace("Polling for pending processes: domain={}, type={}, batchSize={}",
                pm.getDomain(), pm.getProcessType(), batchSize);

            try {
                // Atomically claim a batch of pending processes
                // The stored procedure uses FOR UPDATE SKIP LOCKED for safe concurrency
                // and also releases stale claims from crashed workers
                List<UUID> claimedProcesses = processRepo.claimPendingProcesses(
                    pm.getDomain(), pm.getProcessType(), batchSize);

                consecutiveErrors.set(0);  // Reset on success

                if (claimedProcesses.isEmpty()) {
                    log.trace("No pending processes found for {} (type={})",
                        pm.getDomain(), pm.getProcessType());
                } else {
                    log.debug("Claimed {} pending processes for {} (type={})",
                        claimedProcesses.size(), pm.getDomain(), pm.getProcessType());
                }

                for (UUID processId : claimedProcesses) {
                    virtualThreadExecutor.submit(() -> executeProcess(pm, processId, "pending"));
                }
            } catch (Exception e) {
                handlePollError("pollPendingProcesses", pm.getDomain(), e);
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

            log.trace("Polling for retry-due processes: domain={}, type={}, batchSize={}",
                pm.getDomain(), pm.getProcessType(), batchSize);

            try {
                // Atomically claim a batch of retry-due processes
                List<UUID> claimedProcesses = processRepo.claimDueForRetry(
                    pm.getDomain(), pm.getProcessType(), Instant.now(), batchSize);

                consecutiveErrors.set(0);  // Reset on success

                if (claimedProcesses.isEmpty()) {
                    log.trace("No retry-due processes found for {} (type={})",
                        pm.getDomain(), pm.getProcessType());
                } else {
                    log.debug("Claimed {} retry processes for {} (type={})",
                        claimedProcesses.size(), pm.getDomain(), pm.getProcessType());
                }

                for (UUID processId : claimedProcesses) {
                    virtualThreadExecutor.submit(() -> executeProcess(pm, processId, "retry"));
                }
            } catch (Exception e) {
                handlePollError("pollRetries", pm.getDomain(), e);
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

            log.trace("Checking for expired wait timeouts: domain={}, type={}",
                pm.getDomain(), pm.getProcessType());

            try {
                Instant now = Instant.now();
                // Filter by process type to ensure correct manager deserializes the state
                List<UUID> expiredProcesses = processRepo.findExpiredWaits(
                    pm.getDomain(), pm.getProcessType(), now);

                consecutiveErrors.set(0);  // Reset on success

                if (expiredProcesses.isEmpty()) {
                    log.trace("No expired wait timeouts found for {} (type={})",
                        pm.getDomain(), pm.getProcessType());
                } else {
                    log.debug("Found {} expired waits for {} (type={}) at {}",
                        expiredProcesses.size(), pm.getDomain(), pm.getProcessType(), now);
                }

                for (UUID processId : expiredProcesses) {
                    virtualThreadExecutor.submit(() -> handleTimeout(pm, processId));
                }
            } catch (Exception e) {
                handlePollError("checkWaitTimeouts", pm.getDomain(), e);
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

            log.trace("Checking for expired process deadlines: domain={}, type={}",
                pm.getDomain(), pm.getProcessType());

            try {
                Instant now = Instant.now();
                // Filter by process type to ensure correct manager deserializes the state
                List<UUID> expiredProcesses = processRepo.findExpiredDeadlines(
                    pm.getDomain(), pm.getProcessType(), now);

                consecutiveErrors.set(0);  // Reset on success

                if (expiredProcesses.isEmpty()) {
                    log.trace("No expired deadlines found for {} (type={})",
                        pm.getDomain(), pm.getProcessType());
                } else {
                    log.debug("Found {} expired deadlines for {} (type={}) at {}",
                        expiredProcesses.size(), pm.getDomain(), pm.getProcessType(), now);
                }

                for (UUID processId : expiredProcesses) {
                    virtualThreadExecutor.submit(() -> handleDeadline(pm, processId));
                }
            } catch (Exception e) {
                handlePollError("checkDeadlines", pm.getDomain(), e);
            }
        }
    }

    /**
     * Get the current consecutive error count.
     * Useful for monitoring and testing.
     *
     * @return current consecutive error count
     */
    public int getConsecutiveErrorCount() {
        return consecutiveErrors.get();
    }

    // ========== Internal Methods ==========

    /**
     * Handle poll errors with exponential backoff for transient database errors.
     */
    private void handlePollError(String operation, String domain, Exception e) {
        int errors = consecutiveErrors.incrementAndGet();

        if (DatabaseExceptionClassifier.isTransient(e)) {
            long backoff = calculateBackoff(errors);
            String reason = DatabaseExceptionClassifier.getTransientReason(e);

            if (errors >= errorThreshold) {
                log.error("ProcessStepWorker {} database error (domain={}, count={}, reason={}), backing off {}ms: {}",
                    operation, domain, errors, reason, backoff, e.getMessage());
            } else {
                log.warn("ProcessStepWorker {} database error (domain={}, count={}, reason={}), backing off {}ms: {}",
                    operation, domain, errors, reason, backoff, e.getMessage());
            }

            sleep(backoff);
        } else {
            log.error("ProcessStepWorker {} non-transient error for domain {}: {}",
                operation, domain, e.getMessage());
        }
    }

    /**
     * Calculate exponential backoff delay with jitter.
     */
    private long calculateBackoff(int errorCount) {
        if (errorCount <= 0) {
            return initialBackoffMs;
        }
        double delay = initialBackoffMs * Math.pow(backoffMultiplier, errorCount - 1);
        // Add jitter: +/- 10%
        double jitter = delay * 0.1 * (Math.random() * 2 - 1);
        return Math.min((long) (delay + jitter), maxBackoffMs);
    }

    /**
     * Sleep for the specified duration, ignoring interrupts.
     */
    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

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
