package com.ivamare.commandbus.process.ratelimit;

import java.util.List;
import java.util.Optional;

/**
 * Repository for rate limit configurations.
 */
public interface RateLimitConfigRepository {

    /**
     * Save or update a rate limit configuration.
     *
     * @param config The configuration to save
     */
    void save(RateLimitConfig config);

    /**
     * Get configuration by resource key.
     *
     * @param resourceKey The resource key to look up
     * @return Optional containing the config if found
     */
    Optional<RateLimitConfig> findByResourceKey(String resourceKey);

    /**
     * Delete configuration by resource key.
     *
     * @param resourceKey The resource key to delete
     * @return true if deleted, false if not found
     */
    boolean delete(String resourceKey);

    /**
     * Get all configurations.
     *
     * @return List of all configurations
     */
    List<RateLimitConfig> findAll();
}
