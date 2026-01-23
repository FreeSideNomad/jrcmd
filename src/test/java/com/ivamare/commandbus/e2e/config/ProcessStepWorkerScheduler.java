package com.ivamare.commandbus.e2e.config;

import com.ivamare.commandbus.process.step.CommandStepResponseHandler;
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
 *   <li>Command step responses - configurable via commandbus.step-worker.poll-replies-ms (default: 1000ms)</li>
 * </ul>
 *
 * <p>Only active when not in 'ui' profile (worker mode only).
 */
@Configuration
@Profile("!ui")
@EnableScheduling
public class ProcessStepWorkerScheduler {

    private static final Logger log = LoggerFactory.getLogger(ProcessStepWorkerScheduler.class);
    private static final String REPLY_QUEUE = "payments__replies";

    private final ProcessStepWorker worker;
    private final CommandStepResponseHandler commandStepResponseHandler;

    public ProcessStepWorkerScheduler(
            ProcessStepWorker worker,
            CommandStepResponseHandler commandStepResponseHandler) {
        this.worker = worker;
        this.commandStepResponseHandler = commandStepResponseHandler;
        log.info("ProcessStepWorkerScheduler initialized with CommandStepResponseHandler");
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

    /**
     * Poll for command step responses from the reply queue.
     * Routes responses back to the originating process via CommandStepResponseHandler.
     */
    @Scheduled(fixedRateString = "${commandbus.step-worker.poll-replies-ms:1000}")
    public void pollCommandStepResponses() {
        if (commandStepResponseHandler.isRunning()) {
            commandStepResponseHandler.pollResponses(REPLY_QUEUE);
        }
    }
}
