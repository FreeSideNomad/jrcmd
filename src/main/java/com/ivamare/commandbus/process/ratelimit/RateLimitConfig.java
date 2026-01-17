package com.ivamare.commandbus.process.ratelimit;

import java.time.Instant;

/**
 * Configuration for a rate-limited resource.
 *
 * @param resourceKey Unique identifier for the resource (e.g., "fx_booking_api")
 * @param ticketsPerSecond Maximum operations allowed per second
 * @param description Human-readable description of the resource
 * @param createdAt When this config was created
 * @param updatedAt When this config was last updated
 */
public record RateLimitConfig(
    String resourceKey,
    int ticketsPerSecond,
    String description,
    Instant createdAt,
    Instant updatedAt
) {

    /**
     * Create a new configuration with minimal fields.
     */
    public static RateLimitConfig create(String resourceKey, int ticketsPerSecond) {
        return create(resourceKey, ticketsPerSecond, null);
    }

    /**
     * Create a new configuration with description.
     */
    public static RateLimitConfig create(String resourceKey, int ticketsPerSecond, String description) {
        Instant now = Instant.now();
        return new RateLimitConfig(resourceKey, ticketsPerSecond, description, now, now);
    }

    /**
     * Create an updated configuration with new tickets per second.
     */
    public RateLimitConfig withTicketsPerSecond(int newTicketsPerSecond) {
        return new RateLimitConfig(resourceKey, newTicketsPerSecond, description, createdAt, Instant.now());
    }
}
