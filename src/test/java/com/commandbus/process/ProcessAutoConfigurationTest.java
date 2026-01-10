package com.commandbus.process;

import com.commandbus.CommandBusAutoConfiguration;
import com.commandbus.CommandBusProperties;
import com.commandbus.api.CommandBus;
import com.commandbus.model.Reply;
import com.commandbus.pgmq.PgmqClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("ProcessAutoConfiguration")
class ProcessAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            CommandBusAutoConfiguration.class,
            ProcessAutoConfiguration.class
        ))
        .withUserConfiguration(MockDataSourceConfig.class);

    @Test
    @DisplayName("should not create beans when process.enabled is false")
    void shouldNotCreateBeansWhenDisabled() {
        contextRunner
            .withPropertyValues(
                "commandbus.process.enabled=false"
            )
            .run(context -> {
                assertThat(context).doesNotHaveBean(ProcessRepository.class);
                assertThat(context).doesNotHaveBean(ProcessReplyRouter.class);
            });
    }

    @Test
    @DisplayName("should not create beans when process.enabled is not set")
    void shouldNotCreateBeansWhenNotSet() {
        contextRunner
            .run(context -> {
                assertThat(context).doesNotHaveBean(ProcessReplyRouter.class);
            });
    }

    @Test
    @DisplayName("should create ProcessRepository when enabled")
    void shouldCreateProcessRepositoryWhenEnabled() {
        contextRunner
            .withPropertyValues(
                "commandbus.process.enabled=true",
                "commandbus.process.domain=test_domain",
                "commandbus.process.reply-queue=test_replies"
            )
            .run(context -> {
                assertThat(context).hasSingleBean(ProcessRepository.class);
                assertThat(context.getBean(ProcessRepository.class))
                    .isInstanceOf(JdbcProcessRepository.class);
            });
    }

    @Test
    @DisplayName("should create ProcessReplyRouter when enabled with required config")
    void shouldCreateProcessReplyRouterWhenEnabled() {
        contextRunner
            .withPropertyValues(
                "commandbus.process.enabled=true",
                "commandbus.process.domain=test_domain",
                "commandbus.process.reply-queue=test_replies"
            )
            .run(context -> {
                assertThat(context).hasSingleBean(ProcessReplyRouter.class);
                ProcessReplyRouter router = context.getBean(ProcessReplyRouter.class);
                assertThat(router.getDomain()).isEqualTo("test_domain");
                assertThat(router.getReplyQueue()).isEqualTo("test_replies");
            });
    }

    @Test
    @DisplayName("should fail when domain is not configured")
    void shouldFailWhenDomainNotConfigured() {
        contextRunner
            .withPropertyValues(
                "commandbus.process.enabled=true",
                "commandbus.process.reply-queue=test_replies"
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("domain");
            });
    }

    @Test
    @DisplayName("should fail when reply-queue is not configured")
    void shouldFailWhenReplyQueueNotConfigured() {
        contextRunner
            .withPropertyValues(
                "commandbus.process.enabled=true",
                "commandbus.process.domain=test_domain"
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("reply-queue");
            });
    }

    @Test
    @DisplayName("should fail when domain is blank")
    void shouldFailWhenDomainIsBlank() {
        contextRunner
            .withPropertyValues(
                "commandbus.process.enabled=true",
                "commandbus.process.domain=   ",
                "commandbus.process.reply-queue=test_replies"
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("domain");
            });
    }

    @Test
    @DisplayName("should fail when reply-queue is blank")
    void shouldFailWhenReplyQueueIsBlank() {
        contextRunner
            .withPropertyValues(
                "commandbus.process.enabled=true",
                "commandbus.process.domain=test_domain",
                "commandbus.process.reply-queue=   "
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("reply-queue");
            });
    }

    @Test
    @DisplayName("should bind ProcessProperties from configuration")
    void shouldBindProcessProperties() {
        contextRunner
            .withPropertyValues(
                "commandbus.process.enabled=true",
                "commandbus.process.domain=my_domain",
                "commandbus.process.reply-queue=my_replies",
                "commandbus.process.visibility-timeout=60",
                "commandbus.process.concurrency=20",
                "commandbus.process.poll-interval-ms=2000",
                "commandbus.process.use-notify=false",
                "commandbus.process.auto-start=true"
            )
            .run(context -> {
                CommandBusProperties props = context.getBean(CommandBusProperties.class);
                CommandBusProperties.ProcessProperties processProps = props.getProcess();

                assertThat(processProps.isEnabled()).isTrue();
                assertThat(processProps.getDomain()).isEqualTo("my_domain");
                assertThat(processProps.getReplyQueue()).isEqualTo("my_replies");
                assertThat(processProps.getVisibilityTimeout()).isEqualTo(60);
                assertThat(processProps.getConcurrency()).isEqualTo(20);
                assertThat(processProps.getPollIntervalMs()).isEqualTo(2000);
                assertThat(processProps.isUseNotify()).isFalse();
                assertThat(processProps.isAutoStart()).isTrue();
            });
    }

    @Test
    @DisplayName("should use custom ProcessRepository if provided")
    void shouldUseCustomProcessRepositoryIfProvided() {
        contextRunner
            .withUserConfiguration(CustomProcessRepositoryConfig.class)
            .withPropertyValues(
                "commandbus.process.enabled=true",
                "commandbus.process.domain=test_domain",
                "commandbus.process.reply-queue=test_replies"
            )
            .run(context -> {
                assertThat(context).hasSingleBean(ProcessRepository.class);
                assertThat(context.getBean(ProcessRepository.class))
                    .isSameAs(CustomProcessRepositoryConfig.CUSTOM_REPO);
            });
    }

    @Test
    @DisplayName("should discover and register process managers")
    void shouldDiscoverProcessManagers() {
        contextRunner
            .withUserConfiguration(TestProcessManagerConfig.class)
            .withPropertyValues(
                "commandbus.process.enabled=true",
                "commandbus.process.domain=test_domain",
                "commandbus.process.reply-queue=test_replies"
            )
            .run(context -> {
                assertThat(context).hasSingleBean(ProcessReplyRouter.class);
                // The router should have been created with the test manager
                assertThat(context).hasBean("testProcessManager");
            });
    }

    @Test
    @DisplayName("should not auto-start when auto-start is false")
    void shouldNotAutoStartWhenAutoStartIsFalse() {
        contextRunner
            .withPropertyValues(
                "commandbus.process.enabled=true",
                "commandbus.process.domain=test_domain",
                "commandbus.process.reply-queue=test_replies",
                "commandbus.process.auto-start=false"
            )
            .run(context -> {
                ProcessReplyRouter router = context.getBean(ProcessReplyRouter.class);
                // Router should not be running since auto-start is false
                assertThat(router.isRunning()).isFalse();
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

        @Bean
        public TransactionTemplate transactionTemplate() {
            return mock(TransactionTemplate.class);
        }
    }

    @Configuration
    static class CustomProcessRepositoryConfig {
        static final ProcessRepository CUSTOM_REPO = mock(ProcessRepository.class);

        @Bean
        public ProcessRepository processRepository() {
            return CUSTOM_REPO;
        }
    }

    @Configuration
    static class TestProcessManagerConfig {
        @Bean
        public TestProcessManager testProcessManager(
                CommandBus commandBus,
                ProcessRepository processRepository,
                JdbcTemplate jdbcTemplate,
                TransactionTemplate transactionTemplate) {
            return new TestProcessManager(commandBus, processRepository, jdbcTemplate, transactionTemplate);
        }
    }

    // Simple test process manager for discovery tests
    static class TestProcessManager extends BaseProcessManager<TestState, TestStep> {

        protected TestProcessManager(
                CommandBus commandBus,
                ProcessRepository processRepo,
                JdbcTemplate jdbcTemplate,
                TransactionTemplate transactionTemplate) {
            super(commandBus, processRepo, "test_replies", jdbcTemplate, transactionTemplate);
        }

        @Override
        public String getProcessType() {
            return "TEST_PROCESS";
        }

        @Override
        public String getDomain() {
            return "test_domain";
        }

        @Override
        public Class<TestState> getStateClass() {
            return TestState.class;
        }

        @Override
        public Class<TestStep> getStepClass() {
            return TestStep.class;
        }

        @Override
        public TestState createInitialState(Map<String, Object> initialData) {
            return new TestState();
        }

        @Override
        public TestStep getFirstStep(TestState state) {
            return TestStep.STEP_ONE;
        }

        @Override
        public ProcessCommand<?> buildCommand(TestStep step, TestState state) {
            return new ProcessCommand<>("TestCommand", Map.of());
        }

        @Override
        public TestState updateState(TestState state, TestStep step, Reply reply) {
            return state;
        }

        @Override
        public TestStep getNextStep(TestStep currentStep, Reply reply, TestState state) {
            return null;
        }
    }

    enum TestStep {
        STEP_ONE
    }

    static class TestState implements ProcessState {
        @Override
        public Map<String, Object> toMap() {
            return Map.of();
        }

        public static TestState fromMap(Map<String, Object> data) {
            return new TestState();
        }
    }
}
