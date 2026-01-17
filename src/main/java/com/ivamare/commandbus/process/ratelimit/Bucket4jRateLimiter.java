package com.ivamare.commandbus.process.ratelimit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.distributed.jdbc.PrimaryKeyMapper;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.postgresql.Bucket4jPostgreSQL;
import io.github.bucket4j.postgresql.PostgreSQLadvisoryLockBasedProxyManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Distributed rate limiter using Bucket4j with PostgreSQL backend.
 *
 * <p>Uses advisory locks for coordination across multiple instances.
 * Rate limits are defined per resource key in the rate_limit_config table.
 */
public class Bucket4jRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(Bucket4jRateLimiter.class);

    private final PostgreSQLadvisoryLockBasedProxyManager<String> proxyManager;
    private final RateLimitConfigRepository configRepository;
    private final ConcurrentHashMap<String, BucketConfiguration> configCache = new ConcurrentHashMap<>();

    // Default rate limit if no config found
    private static final int DEFAULT_TICKETS_PER_SECOND = 10;

    /**
     * Create a new Bucket4j rate limiter.
     *
     * @param dataSource DataSource for PostgreSQL connection
     * @param configRepository Repository for rate limit configurations
     */
    public Bucket4jRateLimiter(DataSource dataSource, RateLimitConfigRepository configRepository) {
        this.configRepository = configRepository;
        this.proxyManager = Bucket4jPostgreSQL
            .advisoryLockBasedBuilder(dataSource)
            .primaryKeyMapper(PrimaryKeyMapper.STRING)
            .build();
    }

    /**
     * Acquire a rate limit ticket, waiting up to maxWait if necessary.
     *
     * @param resourceKey The resource to rate limit (e.g., "fx_booking_api")
     * @param maxWait Maximum time to wait for a ticket
     * @return true if ticket acquired, false if timed out
     */
    public boolean acquire(String resourceKey, Duration maxWait) {
        BucketConfiguration config = getOrCreateConfig(resourceKey);
        BucketProxy bucket = proxyManager.getProxy(resourceKey, () -> config);

        try {
            return bucket.asBlocking().tryConsume(1, maxWait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Rate limit acquire interrupted for resource: {}", resourceKey);
            return false;
        }
    }

    /**
     * Try to acquire a ticket immediately (non-blocking).
     *
     * @param resourceKey The resource to rate limit
     * @return true if ticket acquired, false if not available
     */
    public boolean tryAcquire(String resourceKey) {
        BucketConfiguration config = getOrCreateConfig(resourceKey);
        BucketProxy bucket = proxyManager.getProxy(resourceKey, () -> config);

        return bucket.tryConsume(1);
    }

    /**
     * Get available tokens for a resource.
     *
     * @param resourceKey The resource key
     * @return Number of available tokens
     */
    public long getAvailableTokens(String resourceKey) {
        BucketConfiguration config = getOrCreateConfig(resourceKey);
        BucketProxy bucket = proxyManager.getProxy(resourceKey, () -> config);

        return bucket.getAvailableTokens();
    }

    /**
     * Refresh configuration for a resource from the database.
     * Call this when configuration changes.
     *
     * @param resourceKey The resource key to refresh
     */
    public void refreshConfig(String resourceKey) {
        configCache.remove(resourceKey);
        log.debug("Refreshed rate limit config for resource: {}", resourceKey);
    }

    /**
     * Clear all cached configurations.
     */
    public void clearCache() {
        configCache.clear();
        log.debug("Cleared all rate limit config cache");
    }

    /**
     * Get or create bucket configuration for a resource.
     */
    private BucketConfiguration getOrCreateConfig(String resourceKey) {
        return configCache.computeIfAbsent(resourceKey, this::loadConfig);
    }

    /**
     * Load configuration from database or create default.
     */
    private BucketConfiguration loadConfig(String resourceKey) {
        int ticketsPerSecond = configRepository.findByResourceKey(resourceKey)
            .map(RateLimitConfig::ticketsPerSecond)
            .orElseGet(() -> {
                log.debug("No rate limit config for {}, using default: {}/sec",
                    resourceKey, DEFAULT_TICKETS_PER_SECOND);
                return DEFAULT_TICKETS_PER_SECOND;
            });

        return BucketConfiguration.builder()
            .addLimit(Bandwidth.builder()
                .capacity(ticketsPerSecond)
                .refillGreedy(ticketsPerSecond, Duration.ofSeconds(1))
                .build())
            .build();
    }
}
