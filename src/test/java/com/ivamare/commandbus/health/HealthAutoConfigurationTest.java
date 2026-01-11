package com.ivamare.commandbus.health;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("HealthAutoConfiguration")
class HealthAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(HealthAutoConfiguration.class))
        .withUserConfiguration(MockJdbcConfig.class);

    @Test
    @DisplayName("should create CommandBusHealthIndicator when enabled")
    void shouldCreateCommandBusHealthIndicatorWhenEnabled() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(CommandBusHealthIndicator.class);
        });
    }

    @Test
    @DisplayName("should not create CommandBusHealthIndicator when disabled")
    void shouldNotCreateCommandBusHealthIndicatorWhenDisabled() {
        contextRunner
            .withPropertyValues("commandbus.enabled=false")
            .run(context -> {
                assertThat(context).doesNotHaveBean(CommandBusHealthIndicator.class);
            });
    }

    @Test
    @DisplayName("should not create duplicate health indicator if one exists")
    void shouldNotCreateDuplicateHealthIndicator() {
        contextRunner
            .withUserConfiguration(CustomHealthIndicatorConfig.class)
            .run(context -> {
                assertThat(context).hasSingleBean(CommandBusHealthIndicator.class);
                assertThat(context.getBean(CommandBusHealthIndicator.class))
                    .isSameAs(CustomHealthIndicatorConfig.CUSTOM_INDICATOR);
            });
    }

    @Configuration
    static class MockJdbcConfig {
        @Bean
        public JdbcTemplate jdbcTemplate() {
            return mock(JdbcTemplate.class);
        }
    }

    @Configuration
    static class CustomHealthIndicatorConfig {
        static final CommandBusHealthIndicator CUSTOM_INDICATOR =
            new CommandBusHealthIndicator(mock(JdbcTemplate.class));

        @Bean
        public CommandBusHealthIndicator commandBusHealthIndicator() {
            return CUSTOM_INDICATOR;
        }
    }
}
