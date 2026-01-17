package com.ivamare.commandbus.e2e;

import com.ivamare.commandbus.e2e.payment.PaymentProcessManager;
import com.ivamare.commandbus.e2e.payment.step.PaymentStepProcess;
import com.ivamare.commandbus.pgmq.PgmqClient;
import com.ivamare.commandbus.process.BaseProcessManager;
import com.ivamare.commandbus.process.ProcessReplyRouter;
import com.ivamare.commandbus.process.ProcessRepository;
import com.ivamare.commandbus.process.step.ProcessStepWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

/**
 * E2E Test Application for CommandBus library.
 *
 * <p>This application is used for:
 * <ul>
 *   <li>Integration testing during development</li>
 *   <li>Manual testing and debugging</li>
 *   <li>Demonstrating library features</li>
 * </ul>
 *
 * <p><strong>NOT shipped with the library - test scope only.</strong>
 *
 * <p>Run with: {@code mvn spring-boot:test-run -Dspring-boot.run.profiles=e2e}
 */
@SpringBootApplication(scanBasePackages = {"com.ivamare.commandbus"})
public class E2ETestApplication {

    private static final Logger log = LoggerFactory.getLogger(E2ETestApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(E2ETestApplication.class, args);
    }

    /**
     * Create a ProcessReplyRouter for the payments domain.
     * This is separate from the auto-configured router which handles the reporting domain.
     * Only runs in worker mode (not UI).
     */
    @Bean
    @Profile("!ui")
    @ConditionalOnBean(PaymentProcessManager.class)
    public ProcessReplyRouter paymentsReplyRouter(
            DataSource dataSource,
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            ProcessRepository processRepository,
            PgmqClient pgmqClient,
            PaymentProcessManager paymentProcessManager) {

        Map<String, BaseProcessManager<?, ?>> managers = Map.of(
            paymentProcessManager.getProcessType(), paymentProcessManager
        );

        log.info("Creating ProcessReplyRouter for payments domain");

        return new ProcessReplyRouter(
            dataSource,
            jdbcTemplate,
            transactionTemplate,
            processRepository,
            managers,
            pgmqClient,
            "payments__process_replies",
            "payments",
            30,   // visibilityTimeout
            20,   // concurrency - increased from 2 for better throughput
            500,  // pollIntervalMs
            true, // useNotify
            true  // archiveMessages - archive for debugging
        );
    }

    /**
     * Auto-start the payments reply router when the application is ready.
     * The router bean is only created when not in 'ui' profile (see @Profile on the bean).
     */
    @EventListener(ApplicationReadyEvent.class)
    public void startPaymentsReplyRouter(ApplicationReadyEvent event) {
        try {
            ProcessReplyRouter router = event.getApplicationContext().getBean("paymentsReplyRouter", ProcessReplyRouter.class);
            log.info("Auto-starting payments ProcessReplyRouter");
            router.start();
        } catch (Exception e) {
            log.debug("Payments reply router not available (UI-only mode): {}", e.getMessage());
        }
    }

    // ========== ProcessStepManager Configuration ==========

    /**
     * Create PaymentStepProcess for step-based workflow execution.
     * This is the ProcessStepManager implementation for payments.
     * Only runs in worker mode (not UI).
     */
    @Bean
    @Profile("!ui")
    public PaymentStepProcess paymentStepProcess(
            ProcessRepository processRepository,
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate) {
        log.info("Creating PaymentStepProcess for step-based payments workflow");
        return new PaymentStepProcess(processRepository, jdbcTemplate, transactionTemplate);
    }

    /**
     * Create ProcessStepWorker to poll for PENDING, RETRY, timeout, and deadline processes.
     * Only runs in worker mode (not UI).
     */
    @Bean
    @Profile("!ui")
    public ProcessStepWorker processStepWorker(
            PaymentStepProcess paymentStepProcess,
            ProcessRepository processRepository) {
        log.info("Creating ProcessStepWorker for step-based process managers");
        return new ProcessStepWorker(List.of(paymentStepProcess), processRepository);
    }

    /**
     * Auto-start the ProcessStepWorker when the application is ready.
     * The worker bean is only created when not in 'ui' profile (see @Profile on the bean).
     */
    @EventListener(ApplicationReadyEvent.class)
    public void startProcessStepWorker(ApplicationReadyEvent event) {
        try {
            ProcessStepWorker worker = event.getApplicationContext().getBean(ProcessStepWorker.class);
            log.info("Auto-starting ProcessStepWorker for step-based processes");
            worker.start();
        } catch (Exception e) {
            log.debug("ProcessStepWorker not available (UI-only mode): {}", e.getMessage());
        }
    }
}
