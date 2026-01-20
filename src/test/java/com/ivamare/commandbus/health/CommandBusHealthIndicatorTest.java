package com.ivamare.commandbus.health;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

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

    @Nested
    @DisplayName("with HikariDataSource")
    class HikariDataSourceTests {

        @Test
        @DisplayName("should include pool stats when HikariDataSource is provided")
        void shouldIncludePoolStatsWithHikariDataSource() throws SQLException {
            HikariDataSource hikariDataSource = mock(HikariDataSource.class);
            HikariPoolMXBean poolBean = mock(HikariPoolMXBean.class);
            Connection connection = mock(Connection.class);

            when(hikariDataSource.getConnection()).thenReturn(connection);
            when(connection.isValid(3)).thenReturn(true);
            when(hikariDataSource.getHikariPoolMXBean()).thenReturn(poolBean);
            when(poolBean.getActiveConnections()).thenReturn(5);
            when(poolBean.getIdleConnections()).thenReturn(3);
            when(poolBean.getTotalConnections()).thenReturn(8);
            when(poolBean.getThreadsAwaitingConnection()).thenReturn(0);

            CommandBusHealthIndicator indicatorWithHikari =
                new CommandBusHealthIndicator(jdbcTemplate, hikariDataSource);

            when(jdbcTemplate.queryForObject(contains("pg_extension"), eq(Boolean.class)))
                .thenReturn(true);
            when(jdbcTemplate.queryForObject(contains("information_schema"), eq(Boolean.class)))
                .thenReturn(true);
            when(jdbcTemplate.queryForObject(contains("status = 'PENDING'"), eq(Integer.class)))
                .thenReturn(1);
            when(jdbcTemplate.queryForObject(contains("IN_TROUBLESHOOTING_QUEUE"), eq(Integer.class)))
                .thenReturn(0);

            Health health = indicatorWithHikari.health();

            assertEquals(Status.UP, health.getStatus());
            assertEquals(5, health.getDetails().get("pool.active"));
            assertEquals(3, health.getDetails().get("pool.idle"));
            assertEquals(8, health.getDetails().get("pool.total"));
            assertEquals(0, health.getDetails().get("pool.pending"));
        }

        @Test
        @DisplayName("should return DOWN when connection is invalid")
        void shouldReturnDownWhenConnectionInvalid() throws SQLException {
            HikariDataSource hikariDataSource = mock(HikariDataSource.class);
            Connection connection = mock(Connection.class);

            when(hikariDataSource.getConnection()).thenReturn(connection);
            when(connection.isValid(3)).thenReturn(false);

            CommandBusHealthIndicator indicatorWithHikari =
                new CommandBusHealthIndicator(jdbcTemplate, hikariDataSource);

            Health health = indicatorWithHikari.health();

            assertEquals(Status.DOWN, health.getStatus());
            assertEquals("Database connection invalid", health.getDetails().get("error"));
        }

        @Test
        @DisplayName("should return DOWN when connection throws SQLException")
        void shouldReturnDownWhenConnectionThrowsException() throws SQLException {
            HikariDataSource hikariDataSource = mock(HikariDataSource.class);

            when(hikariDataSource.getConnection()).thenThrow(new SQLException("Connection failed"));

            CommandBusHealthIndicator indicatorWithHikari =
                new CommandBusHealthIndicator(jdbcTemplate, hikariDataSource);

            Health health = indicatorWithHikari.health();

            // The SQLException from getConnection() is caught in isConnectionValid() which returns false
            // This triggers the "Database connection invalid" error message
            assertEquals(Status.DOWN, health.getStatus());
            assertEquals("Database connection invalid", health.getDetails().get("error"));
        }

        @Test
        @DisplayName("should handle null pool bean from HikariDataSource")
        void shouldHandleNullPoolBean() throws SQLException {
            HikariDataSource hikariDataSource = mock(HikariDataSource.class);
            Connection connection = mock(Connection.class);

            when(hikariDataSource.getConnection()).thenReturn(connection);
            when(connection.isValid(3)).thenReturn(true);
            when(hikariDataSource.getHikariPoolMXBean()).thenReturn(null);

            CommandBusHealthIndicator indicatorWithHikari =
                new CommandBusHealthIndicator(jdbcTemplate, hikariDataSource);

            when(jdbcTemplate.queryForObject(contains("pg_extension"), eq(Boolean.class)))
                .thenReturn(true);
            when(jdbcTemplate.queryForObject(contains("information_schema"), eq(Boolean.class)))
                .thenReturn(true);
            when(jdbcTemplate.queryForObject(contains("status = 'PENDING'"), eq(Integer.class)))
                .thenReturn(0);
            when(jdbcTemplate.queryForObject(contains("IN_TROUBLESHOOTING_QUEUE"), eq(Integer.class)))
                .thenReturn(0);

            Health health = indicatorWithHikari.health();

            assertEquals(Status.UP, health.getStatus());
            // Pool stats should not be present when pool bean is null
            assertFalse(health.getDetails().containsKey("pool.active"));
        }

        @Test
        @DisplayName("should work with non-Hikari DataSource")
        void shouldWorkWithNonHikariDataSource() throws SQLException {
            DataSource regularDataSource = mock(DataSource.class);
            Connection connection = mock(Connection.class);

            when(regularDataSource.getConnection()).thenReturn(connection);
            when(connection.isValid(3)).thenReturn(true);

            CommandBusHealthIndicator indicatorWithRegular =
                new CommandBusHealthIndicator(jdbcTemplate, regularDataSource);

            when(jdbcTemplate.queryForObject(contains("pg_extension"), eq(Boolean.class)))
                .thenReturn(true);
            when(jdbcTemplate.queryForObject(contains("information_schema"), eq(Boolean.class)))
                .thenReturn(true);
            when(jdbcTemplate.queryForObject(contains("status = 'PENDING'"), eq(Integer.class)))
                .thenReturn(0);
            when(jdbcTemplate.queryForObject(contains("IN_TROUBLESHOOTING_QUEUE"), eq(Integer.class)))
                .thenReturn(0);

            Health health = indicatorWithRegular.health();

            assertEquals(Status.UP, health.getStatus());
            // Pool stats should not be present for non-Hikari datasource
            assertFalse(health.getDetails().containsKey("pool.active"));
        }
    }
}
