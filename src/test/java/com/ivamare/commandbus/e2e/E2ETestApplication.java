package com.ivamare.commandbus.e2e;

import com.ivamare.commandbus.e2e.payment.PaymentProcessManager;
import com.ivamare.commandbus.pgmq.PgmqClient;
import com.ivamare.commandbus.process.BaseProcessManager;
import com.ivamare.commandbus.process.ProcessReplyRouter;
import com.ivamare.commandbus.process.ProcessRepository;
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
            2,    // concurrency
            500,  // pollIntervalMs
            true  // useNotify
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
}
