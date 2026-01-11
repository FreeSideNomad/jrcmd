package com.ivamare.commandbus;

import com.ivamare.commandbus.api.CommandBus;
import com.ivamare.commandbus.handler.HandlerRegistry;
import com.ivamare.commandbus.ops.TroubleshootingQueue;
import com.ivamare.commandbus.pgmq.PgmqClient;
import com.ivamare.commandbus.policy.RetryPolicy;
import com.ivamare.commandbus.repository.AuditRepository;
import com.ivamare.commandbus.repository.BatchRepository;
import com.ivamare.commandbus.repository.CommandRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("CommandBusAutoConfiguration")
class CommandBusAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(CommandBusAutoConfiguration.class))
        .withUserConfiguration(MockDataSourceConfig.class);

    @Test
    @DisplayName("should create all beans when enabled")
    void shouldCreateAllBeansWhenEnabled() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ObjectMapper.class);
            assertThat(context).hasSingleBean(PgmqClient.class);
            assertThat(context).hasSingleBean(CommandRepository.class);
            assertThat(context).hasSingleBean(BatchRepository.class);
            assertThat(context).hasSingleBean(AuditRepository.class);
            assertThat(context).hasSingleBean(HandlerRegistry.class);
            assertThat(context).hasSingleBean(RetryPolicy.class);
            assertThat(context).hasSingleBean(CommandBus.class);
            assertThat(context).hasSingleBean(TroubleshootingQueue.class);
        });
    }

    @Test
    @DisplayName("should not create beans when disabled")
    void shouldNotCreateBeansWhenDisabled() {
        contextRunner
            .withPropertyValues("commandbus.enabled=false")
            .run(context -> {
                assertThat(context).doesNotHaveBean(CommandBus.class);
                assertThat(context).doesNotHaveBean(TroubleshootingQueue.class);
            });
    }

    @Test
    @DisplayName("should use custom ObjectMapper if provided")
    void shouldUseCustomObjectMapperIfProvided() {
        contextRunner
            .withUserConfiguration(CustomObjectMapperConfig.class)
            .run(context -> {
                assertThat(context).hasSingleBean(ObjectMapper.class);
                ObjectMapper mapper = context.getBean(ObjectMapper.class);
                assertThat(mapper).isSameAs(CustomObjectMapperConfig.CUSTOM_MAPPER);
            });
    }

    @Test
    @DisplayName("should use custom PgmqClient if provided")
    void shouldUseCustomPgmqClientIfProvided() {
        contextRunner
            .withUserConfiguration(CustomPgmqClientConfig.class)
            .run(context -> {
                assertThat(context).hasSingleBean(PgmqClient.class);
                PgmqClient client = context.getBean(PgmqClient.class);
                assertThat(client).isSameAs(CustomPgmqClientConfig.CUSTOM_CLIENT);
            });
    }

    @Test
    @DisplayName("should use custom HandlerRegistry if provided")
    void shouldUseCustomHandlerRegistryIfProvided() {
        contextRunner
            .withUserConfiguration(CustomHandlerRegistryConfig.class)
            .run(context -> {
                assertThat(context).hasSingleBean(HandlerRegistry.class);
                HandlerRegistry registry = context.getBean(HandlerRegistry.class);
                assertThat(registry).isSameAs(CustomHandlerRegistryConfig.CUSTOM_REGISTRY);
            });
    }

    @Test
    @DisplayName("should use custom RetryPolicy if provided")
    void shouldUseCustomRetryPolicyIfProvided() {
        contextRunner
            .withUserConfiguration(CustomRetryPolicyConfig.class)
            .run(context -> {
                assertThat(context).hasSingleBean(RetryPolicy.class);
                RetryPolicy policy = context.getBean(RetryPolicy.class);
                assertThat(policy).isSameAs(CustomRetryPolicyConfig.CUSTOM_POLICY);
            });
    }

    @Test
    @DisplayName("should configure RetryPolicy from properties")
    void shouldConfigureRetryPolicyFromProperties() {
        contextRunner
            .withPropertyValues(
                "commandbus.default-max-attempts=5",
                "commandbus.backoff-schedule=5,30,120"
            )
            .run(context -> {
                RetryPolicy policy = context.getBean(RetryPolicy.class);
                assertThat(policy.maxAttempts()).isEqualTo(5);
                assertThat(policy.backoffSchedule()).containsExactly(5, 30, 120);
            });
    }

    @Test
    @DisplayName("should bind CommandBusProperties")
    void shouldBindCommandBusProperties() {
        contextRunner
            .withPropertyValues(
                "commandbus.default-max-attempts=7",
                "commandbus.worker.visibility-timeout=45",
                "commandbus.worker.poll-interval-ms=2000",
                "commandbus.worker.concurrency=8",
                "commandbus.worker.use-notify=false",
                "commandbus.batch.default-chunk-size=500"
            )
            .run(context -> {
                CommandBusProperties props = context.getBean(CommandBusProperties.class);
                assertThat(props.getDefaultMaxAttempts()).isEqualTo(7);
                assertThat(props.getWorker().getVisibilityTimeout()).isEqualTo(45);
                assertThat(props.getWorker().getPollIntervalMs()).isEqualTo(2000);
                assertThat(props.getWorker().getConcurrency()).isEqualTo(8);
                assertThat(props.getWorker().isUseNotify()).isFalse();
                assertThat(props.getBatch().getDefaultChunkSize()).isEqualTo(500);
            });
    }

    @Configuration
    static class MockDataSourceConfig {
        @Bean
        public DataSource dataSource() {
            return mock(DataSource.class);
        }

        @Bean
        public JdbcTemplate jdbcTemplate() {
            return mock(JdbcTemplate.class);
        }
    }

    @Configuration
    static class CustomObjectMapperConfig {
        static final ObjectMapper CUSTOM_MAPPER = new ObjectMapper();

        @Bean
        public ObjectMapper objectMapper() {
            return CUSTOM_MAPPER;
        }
    }

    @Configuration
    static class CustomPgmqClientConfig {
        static final PgmqClient CUSTOM_CLIENT = mock(PgmqClient.class);

        @Bean
        public PgmqClient pgmqClient() {
            return CUSTOM_CLIENT;
        }
    }

    @Configuration
    static class CustomHandlerRegistryConfig {
        static final HandlerRegistry CUSTOM_REGISTRY = mock(HandlerRegistry.class);

        @Bean
        public HandlerRegistry handlerRegistry() {
            return CUSTOM_REGISTRY;
        }
    }

    @Configuration
    static class CustomRetryPolicyConfig {
        static final RetryPolicy CUSTOM_POLICY = new RetryPolicy(10, java.util.List.of(1, 2, 3));

        @Bean
        public RetryPolicy retryPolicy() {
            return CUSTOM_POLICY;
        }
    }
}
