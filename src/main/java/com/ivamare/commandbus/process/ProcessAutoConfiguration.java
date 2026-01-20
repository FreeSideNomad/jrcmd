package com.ivamare.commandbus.process;

import com.ivamare.commandbus.CommandBusAutoConfiguration;
import com.ivamare.commandbus.CommandBusProperties;
import com.ivamare.commandbus.pgmq.PgmqClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Auto-configuration for Process Manager.
 *
 * <p>Automatically configures:
 * <ul>
 *   <li>ProcessRepository for process persistence</li>
 *   <li>ProcessReplyRouter for routing replies to process managers</li>
 * </ul>
 *
 * <p>To enable process management:
 * <pre>
 * commandbus:
 *   process:
 *     enabled: true
 *     domain: my_domain
 *     reply-queue: my_domain__process_replies
 *     auto-start: true
 * </pre>
 */
@AutoConfiguration(after = CommandBusAutoConfiguration.class)
@ConditionalOnProperty(prefix = "commandbus.process", name = "enabled", havingValue = "true")
@ConditionalOnBean(PgmqClient.class)
public class ProcessAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ProcessAutoConfiguration.class);

    private final CommandBusProperties properties;

    public ProcessAutoConfiguration(CommandBusProperties properties) {
        this.properties = properties;
    }

    @Bean
    @ConditionalOnMissingBean
    public ProcessRepository processRepository(JdbcTemplate jdbcTemplate,
                                                com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        return new JdbcProcessRepository(jdbcTemplate, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public ProcessReplyRouter processReplyRouter(
            DataSource dataSource,
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            ProcessRepository processRepository,
            PgmqClient pgmqClient,
            ObjectProvider<BaseProcessManager<?, ?>> managersProvider) {

        CommandBusProperties.ProcessProperties processProps = properties.getProcess();

        // Collect all registered process managers
        Map<String, BaseProcessManager<?, ?>> managers = new HashMap<>();
        List<BaseProcessManager<?, ?>> managerList = managersProvider.orderedStream().toList();

        for (BaseProcessManager<?, ?> manager : managerList) {
            managers.put(manager.getProcessType(), manager);
            log.info("Registered process manager: {} for type {}",
                manager.getClass().getSimpleName(), manager.getProcessType());
        }

        String domain = processProps.getDomain();
        String replyQueue = processProps.getReplyQueue();

        if (domain == null || domain.isBlank()) {
            throw new IllegalStateException("commandbus.process.domain must be configured");
        }
        if (replyQueue == null || replyQueue.isBlank()) {
            throw new IllegalStateException("commandbus.process.reply-queue must be configured");
        }

        log.info("Creating ProcessReplyRouter for domain={}, replyQueue={}, managers={}",
            domain, replyQueue, managers.keySet());

        return new ProcessReplyRouter(
            dataSource,
            jdbcTemplate,
            transactionTemplate,
            processRepository,
            managers,
            pgmqClient,
            replyQueue,
            domain,
            processProps.getVisibilityTimeout(),
            processProps.getConcurrency(),
            processProps.getPollIntervalMs(),
            processProps.isUseNotify(),
            processProps.isArchiveMessages(),
            processProps.getResilience()
        );
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady(ApplicationReadyEvent event) {
        if (properties.getProcess().isAutoStart()) {
            ProcessReplyRouter router = event.getApplicationContext().getBean(ProcessReplyRouter.class);
            log.info("Auto-starting ProcessReplyRouter");
            router.start();
        }
    }
}
