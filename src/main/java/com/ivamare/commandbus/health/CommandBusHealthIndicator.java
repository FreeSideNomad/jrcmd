package com.ivamare.commandbus.health;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;

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

    private final JdbcTemplate jdbcTemplate;

    public CommandBusHealthIndicator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Health health() {
        try {
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

            return Health.up()
                .withDetail("pgmq", "available")
                .withDetail("schema", "commandbus")
                .withDetail("pendingCommands", pendingCount != null ? pendingCount : 0)
                .withDetail("troubleshootingCommands", tsqCount != null ? tsqCount : 0)
                .build();

        } catch (Exception e) {
            return Health.down()
                .withDetail("error", e.getMessage())
                .build();
        }
    }
}
