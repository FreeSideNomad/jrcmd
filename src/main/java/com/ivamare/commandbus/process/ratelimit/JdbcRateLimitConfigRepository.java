package com.ivamare.commandbus.process.ratelimit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * JDBC implementation of RateLimitConfigRepository.
 */
public class JdbcRateLimitConfigRepository implements RateLimitConfigRepository {

    private final JdbcTemplate jdbcTemplate;
    private final RateLimitConfigRowMapper rowMapper = new RateLimitConfigRowMapper();

    public JdbcRateLimitConfigRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void save(RateLimitConfig config) {
        String sql = """
            INSERT INTO commandbus.rate_limit_config
                (resource_key, tickets_per_second, description, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (resource_key) DO UPDATE SET
                tickets_per_second = EXCLUDED.tickets_per_second,
                description = EXCLUDED.description,
                updated_at = EXCLUDED.updated_at
            """;

        jdbcTemplate.update(sql,
            config.resourceKey(),
            config.ticketsPerSecond(),
            config.description(),
            java.sql.Timestamp.from(config.createdAt()),
            java.sql.Timestamp.from(config.updatedAt())
        );
    }

    @Override
    public Optional<RateLimitConfig> findByResourceKey(String resourceKey) {
        String sql = """
            SELECT resource_key, tickets_per_second, description, created_at, updated_at
            FROM commandbus.rate_limit_config
            WHERE resource_key = ?
            """;

        List<RateLimitConfig> results = jdbcTemplate.query(sql, rowMapper, resourceKey);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public boolean delete(String resourceKey) {
        String sql = "DELETE FROM commandbus.rate_limit_config WHERE resource_key = ?";
        int rowsAffected = jdbcTemplate.update(sql, resourceKey);
        return rowsAffected > 0;
    }

    @Override
    public List<RateLimitConfig> findAll() {
        String sql = """
            SELECT resource_key, tickets_per_second, description, created_at, updated_at
            FROM commandbus.rate_limit_config
            ORDER BY resource_key
            """;

        return jdbcTemplate.query(sql, rowMapper);
    }

    private static class RateLimitConfigRowMapper implements RowMapper<RateLimitConfig> {
        @Override
        public RateLimitConfig mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new RateLimitConfig(
                rs.getString("resource_key"),
                rs.getInt("tickets_per_second"),
                rs.getString("description"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
            );
        }
    }
}
