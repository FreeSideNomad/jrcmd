package com.ivamare.commandbus;

import com.ivamare.commandbus.handler.HandlerRegistry;
import com.ivamare.commandbus.health.WorkerHealthIndicator;
import com.ivamare.commandbus.policy.RetryPolicy;
import com.ivamare.commandbus.worker.Worker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;

import jakarta.annotation.PreDestroy;
import javax.sql.DataSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Auto-start configuration for workers.
 *
 * <p>Enable with:
 * <pre>
 * commandbus:
 *   worker:
 *     auto-start: true
 * </pre>
 *
 * <p>Workers are created for each domain with registered handlers.
 */
@Configuration
@ConditionalOnProperty(prefix = "commandbus.worker", name = "auto-start", havingValue = "true")
public class WorkerAutoStartConfiguration {

    private static final Logger log = LoggerFactory.getLogger(WorkerAutoStartConfiguration.class);

    private final List<Worker> workers = new ArrayList<>();
    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final HandlerRegistry handlerRegistry;
    private final RetryPolicy retryPolicy;
    private final CommandBusProperties properties;

    public WorkerAutoStartConfiguration(
            JdbcTemplate jdbcTemplate,
            DataSource dataSource,
            ObjectMapper objectMapper,
            HandlerRegistry handlerRegistry,
            RetryPolicy retryPolicy,
            CommandBusProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
        this.handlerRegistry = handlerRegistry;
        this.retryPolicy = retryPolicy;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startWorkers() {
        // Get domains from registered handlers
        List<String> domains = handlerRegistry.registeredHandlers().stream()
            .map(k -> k.domain())
            .distinct()
            .toList();

        if (domains.isEmpty()) {
            log.warn("No handlers registered, no workers to start");
            return;
        }

        CommandBusProperties.WorkerProperties wp = properties.getWorker();

        for (String domain : domains) {
            Worker worker = Worker.builder()
                .jdbcTemplate(jdbcTemplate)
                .dataSource(dataSource)
                .objectMapper(objectMapper)
                .domain(domain)
                .handlerRegistry(handlerRegistry)
                .visibilityTimeout(wp.getVisibilityTimeout())
                .pollIntervalMs(wp.getPollIntervalMs())
                .concurrency(wp.getConcurrency())
                .useNotify(wp.isUseNotify())
                .retryPolicy(retryPolicy)
                .build();

            worker.start();
            workers.add(worker);

            log.info("Started worker for domain={}", domain);
        }
    }

    @PreDestroy
    public void stopWorkers() {
        if (workers.isEmpty()) {
            return;
        }

        log.info("Stopping {} workers...", workers.size());

        workers.forEach(w -> w.stop(Duration.ofSeconds(30)));

        log.info("All workers stopped");
    }

    @Bean
    public List<Worker> commandBusWorkers() {
        return workers;
    }

    @Bean
    public HealthIndicator workerHealthIndicator() {
        return new WorkerHealthIndicator(workers);
    }
}
