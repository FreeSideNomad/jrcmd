package com.ivamare.commandbus;

import com.ivamare.commandbus.api.CommandBus;
import com.ivamare.commandbus.api.impl.DefaultCommandBus;
import com.ivamare.commandbus.handler.HandlerRegistry;
import com.ivamare.commandbus.handler.impl.DefaultHandlerRegistry;
import com.ivamare.commandbus.ops.TroubleshootingQueue;
import com.ivamare.commandbus.ops.impl.DefaultTroubleshootingQueue;
import com.ivamare.commandbus.pgmq.PgmqClient;
import com.ivamare.commandbus.pgmq.impl.JdbcPgmqClient;
import com.ivamare.commandbus.policy.RetryPolicy;
import com.ivamare.commandbus.repository.AuditRepository;
import com.ivamare.commandbus.repository.BatchRepository;
import com.ivamare.commandbus.repository.CommandRepository;
import com.ivamare.commandbus.repository.impl.JdbcAuditRepository;
import com.ivamare.commandbus.repository.impl.JdbcBatchRepository;
import com.ivamare.commandbus.repository.impl.JdbcCommandRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Auto-configuration for Command Bus.
 *
 * <p>Automatically configures:
 * <ul>
 *   <li>PGMQ Client</li>
 *   <li>Repositories (Command, Batch, Audit)</li>
 *   <li>Handler Registry</li>
 *   <li>Command Bus</li>
 *   <li>Troubleshooting Queue</li>
 *   <li>Retry Policy</li>
 * </ul>
 *
 * <p>To disable auto-configuration:
 * <pre>
 * commandbus.enabled=false
 * </pre>
 */
@AutoConfiguration(after = DataSourceAutoConfiguration.class)
@ConditionalOnClass(JdbcTemplate.class)
@ConditionalOnProperty(prefix = "commandbus", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(CommandBusProperties.class)
public class CommandBusAutoConfiguration {

    // --- Object Mapper ---

    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper commandBusObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.findAndRegisterModules(); // Register JSR310 module
        return mapper;
    }

    // --- PGMQ Client ---

    @Bean
    @ConditionalOnMissingBean
    public PgmqClient pgmqClient(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        return new JdbcPgmqClient(jdbcTemplate, objectMapper);
    }

    // --- Repositories ---

    @Bean
    @ConditionalOnMissingBean
    public CommandRepository commandRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcCommandRepository(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public BatchRepository batchRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        return new JdbcBatchRepository(jdbcTemplate, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public AuditRepository auditRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        return new JdbcAuditRepository(jdbcTemplate, objectMapper);
    }

    // --- Handler Registry ---

    @Bean
    @ConditionalOnMissingBean
    public HandlerRegistry handlerRegistry() {
        return new DefaultHandlerRegistry();
    }

    // --- Retry Policy ---

    @Bean
    @ConditionalOnMissingBean
    public RetryPolicy retryPolicy(CommandBusProperties properties) {
        return new RetryPolicy(
            properties.getDefaultMaxAttempts(),
            properties.getBackoffSchedule()
        );
    }

    // --- Command Bus ---

    @Bean
    @ConditionalOnMissingBean
    public CommandBus commandBus(
            PgmqClient pgmqClient,
            CommandRepository commandRepository,
            BatchRepository batchRepository,
            AuditRepository auditRepository) {
        return new DefaultCommandBus(
            pgmqClient,
            commandRepository,
            batchRepository,
            auditRepository
        );
    }

    // --- Troubleshooting Queue ---

    @Bean
    @ConditionalOnMissingBean
    public TroubleshootingQueue troubleshootingQueue(
            JdbcTemplate jdbcTemplate,
            PgmqClient pgmqClient,
            CommandRepository commandRepository,
            BatchRepository batchRepository,
            AuditRepository auditRepository,
            ObjectMapper objectMapper) {
        return new DefaultTroubleshootingQueue(
            jdbcTemplate,
            pgmqClient,
            commandRepository,
            batchRepository,
            auditRepository,
            objectMapper
        );
    }
}
