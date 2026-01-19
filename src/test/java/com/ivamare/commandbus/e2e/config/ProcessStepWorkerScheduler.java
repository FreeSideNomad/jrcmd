package com.ivamare.commandbus.e2e.config;

import com.ivamare.commandbus.process.step.ProcessStepWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Scheduler for ProcessStepWorker polling operations.
 *
 * <p>This configuration enables scheduled execution of the ProcessStepWorker
 * polling methods. The worker polls for:
 * <ul>
 *   <li>PENDING processes - configurable via commandbus.step-worker.poll-pending-ms (default: 1000ms)</li>
 *   <li>Processes due for retry - configurable via commandbus.step-worker.poll-retries-ms (default: 5000ms)</li>
 *   <li>Expired wait timeouts - configurable via commandbus.step-worker.check-timeouts-ms (default: 60000ms)</li>
 *   <li>Exceeded process deadlines - configurable via commandbus.step-worker.check-deadlines-ms (default: 60000ms)</li>
 * </ul>
 *
 * <p>Only active when not in 'ui' profile (worker mode only).
 */
@Configuration
@Profile("!ui")
@EnableScheduling
public class ProcessStepWorkerScheduler {

    private static final Logger log = LoggerFactory.getLogger(ProcessStepWorkerScheduler.class);

    private final ProcessStepWorker worker;

    public ProcessStepWorkerScheduler(ProcessStepWorker worker) {
        this.worker = worker;
        log.info("ProcessStepWorkerScheduler initialized");
    }

    /**
     * Poll for PENDING processes.
     */
    @Scheduled(fixedRateString = "${commandbus.step-worker.poll-pending-ms:1000}")
    public void pollPendingProcesses() {
        if (worker.isRunning()) {
            worker.pollPendingProcesses();
        }
    }

    /**
     * Poll for processes due for retry.
     */
    @Scheduled(fixedRateString = "${commandbus.step-worker.poll-retries-ms:5000}")
    public void pollRetries() {
        if (worker.isRunning()) {
            worker.pollRetries();
        }
    }

    /**
     * Check for expired wait timeouts.
     */
    @Scheduled(fixedRateString = "${commandbus.step-worker.check-timeouts-ms:60000}")
    public void checkWaitTimeouts() {
        if (worker.isRunning()) {
            log.trace("Checking for expired wait timeouts...");
            worker.checkWaitTimeouts();
        }
    }

    /**
     * Check for exceeded process deadlines.
     */
    @Scheduled(fixedRateString = "${commandbus.step-worker.check-deadlines-ms:60000}")
    public void checkDeadlines() {
        if (worker.isRunning()) {
            worker.checkDeadlines();
        }
    }
}
