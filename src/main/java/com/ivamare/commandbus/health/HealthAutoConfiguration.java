package com.ivamare.commandbus.health;

import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Auto-configuration for Command Bus health indicators.
 */
@AutoConfiguration
@ConditionalOnClass({HealthIndicator.class, JdbcTemplate.class})
@ConditionalOnProperty(prefix = "commandbus", name = "enabled", havingValue = "true", matchIfMissing = true)
public class HealthAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(CommandBusHealthIndicator.class)
    public CommandBusHealthIndicator commandBusHealthIndicator(JdbcTemplate jdbcTemplate) {
        return new CommandBusHealthIndicator(jdbcTemplate);
    }
}
