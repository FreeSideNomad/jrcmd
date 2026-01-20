package com.ivamare.commandbus.health;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Health indicator for Command Bus database connectivity.
 *
 * <p>Checks:
 * <ul>
 *   <li>PGMQ extension is installed</li>
 *   <li>commandbus schema exists</li>
 *   <li>Reports pending and troubleshooting command counts</li>
 * </ul>
 */
public class CommandBusHealthIndicator implements HealthIndicator {

    private static final int CONNECTION_VALIDITY_TIMEOUT_SECONDS = 3;

    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;

    public CommandBusHealthIndicator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = null;
    }

    public CommandBusHealthIndicator(JdbcTemplate jdbcTemplate, DataSource dataSource) {
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
    }

    @Override
    public Health health() {
        try {
            // Quick connectivity check first
            if (dataSource != null) {
                if (!isConnectionValid()) {
                    return Health.down()
                        .withDetail("error", "Database connection invalid")
                        .build();
                }
            }

            // Check PGMQ extension is available
            Boolean pgmqAvailable = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pgmq')",
                Boolean.class
            );

            if (!Boolean.TRUE.equals(pgmqAvailable)) {
                return Health.down()
                    .withDetail("error", "PGMQ extension not installed")
                    .build();
            }

            // Check commandbus schema exists
            Boolean schemaExists = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM information_schema.schemata WHERE schema_name = 'commandbus')",
                Boolean.class
            );

            if (!Boolean.TRUE.equals(schemaExists)) {
                return Health.down()
                    .withDetail("error", "commandbus schema not found")
                    .build();
            }

            // Get some stats
            Integer pendingCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM commandbus.command WHERE status = 'PENDING'",
                Integer.class
            );

            Integer tsqCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM commandbus.command WHERE status = 'IN_TROUBLESHOOTING_QUEUE'",
                Integer.class
            );

            Health.Builder builder = Health.up()
                .withDetail("pgmq", "available")
                .withDetail("schema", "commandbus")
                .withDetail("pendingCommands", pendingCount != null ? pendingCount : 0)
                .withDetail("troubleshootingCommands", tsqCount != null ? tsqCount : 0);

            // Add HikariCP pool stats if available
            addPoolStats(builder);

            return builder.build();

        } catch (Exception e) {
            return Health.down()
                .withDetail("error", e.getMessage())
                .build();
        }
    }

    /**
     * Quick connection validity check without heavy queries.
     */
    private boolean isConnectionValid() {
        try (Connection conn = dataSource.getConnection()) {
            return conn.isValid(CONNECTION_VALIDITY_TIMEOUT_SECONDS);
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * Add HikariCP pool statistics if using HikariDataSource.
     */
    private void addPoolStats(Health.Builder builder) {
        if (dataSource instanceof HikariDataSource hikari) {
            HikariPoolMXBean pool = hikari.getHikariPoolMXBean();
            if (pool != null) {
                builder.withDetail("pool.active", pool.getActiveConnections());
                builder.withDetail("pool.idle", pool.getIdleConnections());
                builder.withDetail("pool.total", pool.getTotalConnections());
                builder.withDetail("pool.pending", pool.getThreadsAwaitingConnection());
            }
        }
    }
}
