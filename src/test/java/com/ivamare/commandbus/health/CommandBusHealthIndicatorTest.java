package com.ivamare.commandbus.health;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("CommandBusHealthIndicator")
class CommandBusHealthIndicatorTest {

    private JdbcTemplate jdbcTemplate;
    private CommandBusHealthIndicator healthIndicator;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        healthIndicator = new CommandBusHealthIndicator(jdbcTemplate);
    }

    @Test
    @DisplayName("should return UP when PGMQ and schema exist")
    void shouldReturnUpWhenPgmqAndSchemaExist() {
        when(jdbcTemplate.queryForObject(contains("pg_extension"), eq(Boolean.class)))
            .thenReturn(true);
        when(jdbcTemplate.queryForObject(contains("information_schema"), eq(Boolean.class)))
            .thenReturn(true);
        when(jdbcTemplate.queryForObject(contains("status = 'PENDING'"), eq(Integer.class)))
            .thenReturn(5);
        when(jdbcTemplate.queryForObject(contains("IN_TROUBLESHOOTING_QUEUE"), eq(Integer.class)))
            .thenReturn(2);

        Health health = healthIndicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("available", health.getDetails().get("pgmq"));
        assertEquals("commandbus", health.getDetails().get("schema"));
        assertEquals(5, health.getDetails().get("pendingCommands"));
        assertEquals(2, health.getDetails().get("troubleshootingCommands"));
    }

    @Test
    @DisplayName("should return DOWN when PGMQ extension not installed")
    void shouldReturnDownWhenPgmqNotInstalled() {
        when(jdbcTemplate.queryForObject(contains("pg_extension"), eq(Boolean.class)))
            .thenReturn(false);

        Health health = healthIndicator.health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("PGMQ extension not installed", health.getDetails().get("error"));
    }

    @Test
    @DisplayName("should return DOWN when PGMQ check returns null")
    void shouldReturnDownWhenPgmqCheckReturnsNull() {
        when(jdbcTemplate.queryForObject(contains("pg_extension"), eq(Boolean.class)))
            .thenReturn(null);

        Health health = healthIndicator.health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("PGMQ extension not installed", health.getDetails().get("error"));
    }

    @Test
    @DisplayName("should return DOWN when commandbus schema not found")
    void shouldReturnDownWhenSchemaNotFound() {
        when(jdbcTemplate.queryForObject(contains("pg_extension"), eq(Boolean.class)))
            .thenReturn(true);
        when(jdbcTemplate.queryForObject(contains("information_schema"), eq(Boolean.class)))
            .thenReturn(false);

        Health health = healthIndicator.health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("commandbus schema not found", health.getDetails().get("error"));
    }

    @Test
    @DisplayName("should return DOWN when schema check returns null")
    void shouldReturnDownWhenSchemaCheckReturnsNull() {
        when(jdbcTemplate.queryForObject(contains("pg_extension"), eq(Boolean.class)))
            .thenReturn(true);
        when(jdbcTemplate.queryForObject(contains("information_schema"), eq(Boolean.class)))
            .thenReturn(null);

        Health health = healthIndicator.health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("commandbus schema not found", health.getDetails().get("error"));
    }

    @Test
    @DisplayName("should return DOWN when database query fails")
    void shouldReturnDownWhenDatabaseQueryFails() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class)))
            .thenThrow(new RuntimeException("Connection failed"));

        Health health = healthIndicator.health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("Connection failed", health.getDetails().get("error"));
    }

    @Test
    @DisplayName("should handle null count values")
    void shouldHandleNullCountValues() {
        when(jdbcTemplate.queryForObject(contains("pg_extension"), eq(Boolean.class)))
            .thenReturn(true);
        when(jdbcTemplate.queryForObject(contains("information_schema"), eq(Boolean.class)))
            .thenReturn(true);
        when(jdbcTemplate.queryForObject(contains("status = 'PENDING'"), eq(Integer.class)))
            .thenReturn(null);
        when(jdbcTemplate.queryForObject(contains("IN_TROUBLESHOOTING_QUEUE"), eq(Integer.class)))
            .thenReturn(null);

        Health health = healthIndicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals(0, health.getDetails().get("pendingCommands"));
        assertEquals(0, health.getDetails().get("troubleshootingCommands"));
    }
}
