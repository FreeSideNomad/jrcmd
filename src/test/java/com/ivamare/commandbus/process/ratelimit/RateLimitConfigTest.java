package com.ivamare.commandbus.process.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RateLimitConfig")
class RateLimitConfigTest {

    @Test
    @DisplayName("should create with minimal fields")
    void shouldCreateWithMinimalFields() {
        Instant before = Instant.now();
        RateLimitConfig config = RateLimitConfig.create("test-key", 100);
        Instant after = Instant.now();

        assertEquals("test-key", config.resourceKey());
        assertEquals(100, config.ticketsPerSecond());
        assertNull(config.description());
        assertFalse(config.createdAt().isBefore(before));
        assertFalse(config.createdAt().isAfter(after));
        assertEquals(config.createdAt(), config.updatedAt());
    }

    @Test
    @DisplayName("should create with description")
    void shouldCreateWithDescription() {
        RateLimitConfig config = RateLimitConfig.create("fx-api", 50, "FX Booking API limit");

        assertEquals("fx-api", config.resourceKey());
        assertEquals(50, config.ticketsPerSecond());
        assertEquals("FX Booking API limit", config.description());
    }

    @Test
    @DisplayName("should create updated config with new tickets per second")
    void shouldCreateUpdatedConfigWithNewTicketsPerSecond() throws InterruptedException {
        RateLimitConfig original = RateLimitConfig.create("test-key", 100, "Test");

        // Small delay to ensure updatedAt is different
        Thread.sleep(1);

        RateLimitConfig updated = original.withTicketsPerSecond(200);

        assertEquals("test-key", updated.resourceKey());
        assertEquals(200, updated.ticketsPerSecond());
        assertEquals("Test", updated.description());
        assertEquals(original.createdAt(), updated.createdAt());
        assertTrue(updated.updatedAt().isAfter(original.updatedAt()) ||
            updated.updatedAt().equals(original.updatedAt()));
    }

    @Test
    @DisplayName("should be equal when all fields match")
    void shouldBeEqualWhenAllFieldsMatch() {
        Instant now = Instant.now();
        RateLimitConfig config1 = new RateLimitConfig("key", 100, "desc", now, now);
        RateLimitConfig config2 = new RateLimitConfig("key", 100, "desc", now, now);

        assertEquals(config1, config2);
        assertEquals(config1.hashCode(), config2.hashCode());
    }
}
