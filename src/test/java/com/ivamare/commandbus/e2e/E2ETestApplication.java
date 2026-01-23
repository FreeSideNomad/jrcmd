package com.ivamare.commandbus.e2e;

import com.ivamare.commandbus.CommandBusProperties;
import com.ivamare.commandbus.api.CommandBus;
import com.ivamare.commandbus.e2e.payment.PaymentProcessManager;
import com.ivamare.commandbus.e2e.payment.PaymentRepository;
import com.ivamare.commandbus.e2e.payment.PendingApprovalRepository;
import com.ivamare.commandbus.e2e.payment.PendingNetworkResponseRepository;
import com.ivamare.commandbus.e2e.payment.step.PaymentCommandStepProcess;
import com.ivamare.commandbus.e2e.payment.step.PaymentStepProcess;
import com.ivamare.commandbus.e2e.payment.step.StepPaymentNetworkSimulator;
import com.ivamare.commandbus.pgmq.PgmqClient;
import com.ivamare.commandbus.process.BaseProcessManager;
import com.ivamare.commandbus.process.ProcessReplyRouter;
import com.ivamare.commandbus.process.ProcessRepository;
import com.ivamare.commandbus.process.step.CommandStepResponseHandler;
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

import com.fasterxml.jackson.databind.ObjectMapper;

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
     * Available in both UI and worker modes - UI needs it to start processes,
     * workers need it to execute them.
     */
    @Bean
    public PaymentStepProcess paymentStepProcess(
            ProcessRepository processRepository,
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            PaymentRepository paymentRepository,
            PendingApprovalRepository pendingApprovalRepository) {
        log.info("Creating PaymentStepProcess for step-based payments workflow");
        return new PaymentStepProcess(processRepository, jdbcTemplate, transactionTemplate,
            paymentRepository, pendingApprovalRepository);
    }

    /**
     * Create PaymentCommandStepProcess for command-step-based workflow execution.
     * This uses commandStep() for bookFx and submitPayment, sending commands to
     * external domains (fx, network) via PGMQ queues.
     * Available in both UI and worker modes.
     */
    @Bean
    public PaymentCommandStepProcess paymentCommandStepProcess(
            ProcessRepository processRepository,
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            CommandBus commandBus,
            CommandBusProperties commandBusProperties,
            PaymentRepository paymentRepository,
            PendingApprovalRepository pendingApprovalRepository) {
        log.info("Creating PaymentCommandStepProcess for command-step-based payments workflow");
        return new PaymentCommandStepProcess(processRepository, jdbcTemplate, transactionTemplate,
            commandBus, commandBusProperties, paymentRepository, pendingApprovalRepository);
    }

    /**
     * Create StepPaymentNetworkSimulator to simulate L1-L4 network responses.
     * Wires itself to both PaymentStepProcess and PaymentCommandStepProcess.
     */
    @Bean
    public StepPaymentNetworkSimulator stepPaymentNetworkSimulator(
            PaymentStepProcess paymentStepProcess,
            PaymentCommandStepProcess paymentCommandStepProcess,
            PendingNetworkResponseRepository pendingNetworkResponseRepository) {
        log.info("Creating StepPaymentNetworkSimulator for step-based payment confirmations");
        StepPaymentNetworkSimulator simulator = new StepPaymentNetworkSimulator(
            paymentStepProcess, pendingNetworkResponseRepository);
        paymentStepProcess.setNetworkSimulator(simulator);
        paymentCommandStepProcess.setNetworkSimulator(simulator);
        return simulator;
    }

    /**
     * Create ProcessStepWorker to poll for PENDING, RETRY, timeout, and deadline processes.
     * Handles both PaymentStepProcess and PaymentCommandStepProcess.
     * Only runs in worker mode (not UI).
     */
    @Bean
    @Profile("!ui")
    public ProcessStepWorker processStepWorker(
            PaymentStepProcess paymentStepProcess,
            PaymentCommandStepProcess paymentCommandStepProcess,
            ProcessRepository processRepository) {
        log.info("Creating ProcessStepWorker for step-based process managers");
        return new ProcessStepWorker(List.of(paymentStepProcess, paymentCommandStepProcess), processRepository);
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

    // ========== CommandStep Reply Handling ==========

    /**
     * Create CommandStepResponseHandler to poll payments__replies and route
     * responses back to PaymentCommandStepProcess.
     * Only runs in worker mode (not UI).
     */
    @Bean
    @Profile("!ui")
    public CommandStepResponseHandler commandStepResponseHandler(
            PgmqClient pgmqClient,
            ObjectMapper objectMapper,
            PaymentCommandStepProcess paymentCommandStepProcess) {
        log.info("Creating CommandStepResponseHandler for payments__replies");
        return new CommandStepResponseHandler(
            pgmqClient,
            objectMapper,
            List.of(paymentCommandStepProcess)
        );
    }

    /**
     * Auto-start the CommandStepResponseHandler when the application is ready.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void startCommandStepResponseHandler(ApplicationReadyEvent event) {
        try {
            CommandStepResponseHandler handler = event.getApplicationContext().getBean(CommandStepResponseHandler.class);
            log.info("Auto-starting CommandStepResponseHandler");
            handler.start();
        } catch (Exception e) {
            log.debug("CommandStepResponseHandler not available (UI-only mode): {}", e.getMessage());
        }
    }
}
