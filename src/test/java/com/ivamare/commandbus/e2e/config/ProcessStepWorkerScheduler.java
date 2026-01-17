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
 *   <li>PENDING processes - every 1 second</li>
 *   <li>Processes due for retry - every 5 seconds</li>
 *   <li>Expired wait timeouts - every 60 seconds</li>
 *   <li>Exceeded process deadlines - every 60 seconds</li>
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
     * Poll for PENDING processes every 1 second.
     */
    @Scheduled(fixedRate = 1000)
    public void pollPendingProcesses() {
        if (worker.isRunning()) {
            worker.pollPendingProcesses();
        }
    }

    /**
     * Poll for processes due for retry every 5 seconds.
     */
    @Scheduled(fixedRate = 5000)
    public void pollRetries() {
        if (worker.isRunning()) {
            worker.pollRetries();
        }
    }

    /**
     * Check for expired wait timeouts every 60 seconds.
     */
    @Scheduled(fixedRate = 60000)
    public void checkWaitTimeouts() {
        if (worker.isRunning()) {
            log.debug("Checking for expired wait timeouts...");
            worker.checkWaitTimeouts();
        }
    }

    /**
     * Check for exceeded process deadlines every 60 seconds.
     */
    @Scheduled(fixedRate = 60000)
    public void checkDeadlines() {
        if (worker.isRunning()) {
            worker.checkDeadlines();
        }
    }
}
