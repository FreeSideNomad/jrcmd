package com.ivamare.commandbus.process.step;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("StepOptions")
class StepOptionsTest {

    @Test
    @DisplayName("should build with required action")
    void shouldBuildWithRequiredAction() {
        StepOptions<TestState, String> options = StepOptions.<TestState, String>builder()
            .action(state -> "result")
            .build();

        assertNotNull(options.action());
        assertEquals(1, options.maxRetries());
        assertEquals(Duration.ofSeconds(1), options.retryDelay());
        assertEquals(Duration.ofSeconds(30), options.timeout());
        assertNull(options.compensation());
        assertNull(options.rateLimitKey());
        assertEquals(Duration.ofSeconds(5), options.rateLimitTimeout());
    }

    @Test
    @DisplayName("should build with all options")
    void shouldBuildWithAllOptions() {
        StepOptions<TestState, String> options = StepOptions.<TestState, String>builder()
            .action(state -> "result")
            .maxRetries(3)
            .retryDelay(Duration.ofSeconds(5))
            .timeout(Duration.ofMinutes(2))
            .compensation(state -> {})
            .rateLimitKey("fx_api")
            .rateLimitTimeout(Duration.ofSeconds(10))
            .build();

        assertEquals(3, options.maxRetries());
        assertEquals(Duration.ofSeconds(5), options.retryDelay());
        assertEquals(Duration.ofMinutes(2), options.timeout());
        assertNotNull(options.compensation());
        assertEquals("fx_api", options.rateLimitKey());
        assertEquals(Duration.ofSeconds(10), options.rateLimitTimeout());
    }

    @Test
    @DisplayName("should throw when action is not set")
    void shouldThrowWhenActionNotSet() {
        StepOptions.Builder<TestState, String> builder = StepOptions.builder();

        assertThrows(IllegalStateException.class, builder::build);
    }

    @Test
    @DisplayName("should throw when maxRetries is less than 1")
    void shouldThrowWhenMaxRetriesLessThanOne() {
        assertThrows(IllegalArgumentException.class, () -> {
            StepOptions.<TestState, String>builder()
                .action(state -> "result")
                .maxRetries(0)
                .build();
        });
    }

    @Test
    @DisplayName("hasRetry should return true when maxRetries > 1")
    void hasRetryShouldReturnTrueWhenMaxRetriesGreaterThanOne() {
        StepOptions<TestState, String> options = StepOptions.<TestState, String>builder()
            .action(state -> "result")
            .maxRetries(3)
            .build();

        assertTrue(options.hasRetry());
    }

    @Test
    @DisplayName("hasRetry should return false when maxRetries is 1")
    void hasRetryShouldReturnFalseWhenMaxRetriesIsOne() {
        StepOptions<TestState, String> options = StepOptions.<TestState, String>builder()
            .action(state -> "result")
            .build();

        assertFalse(options.hasRetry());
    }

    @Test
    @DisplayName("hasRateLimiting should return true when rateLimitKey is set")
    void hasRateLimitingShouldReturnTrueWhenRateLimitKeyIsSet() {
        StepOptions<TestState, String> options = StepOptions.<TestState, String>builder()
            .action(state -> "result")
            .rateLimitKey("api_key")
            .build();

        assertTrue(options.hasRateLimiting());
    }

    @Test
    @DisplayName("hasRateLimiting should return false when rateLimitKey is null")
    void hasRateLimitingShouldReturnFalseWhenRateLimitKeyIsNull() {
        StepOptions<TestState, String> options = StepOptions.<TestState, String>builder()
            .action(state -> "result")
            .build();

        assertFalse(options.hasRateLimiting());
    }

    @Test
    @DisplayName("hasRateLimiting should return false when rateLimitKey is blank")
    void hasRateLimitingShouldReturnFalseWhenRateLimitKeyIsBlank() {
        StepOptions<TestState, String> options = StepOptions.<TestState, String>builder()
            .action(state -> "result")
            .rateLimitKey("  ")
            .build();

        assertFalse(options.hasRateLimiting());
    }

    @Test
    @DisplayName("hasCompensation should return true when compensation is set")
    void hasCompensationShouldReturnTrueWhenCompensationIsSet() {
        StepOptions<TestState, String> options = StepOptions.<TestState, String>builder()
            .action(state -> "result")
            .compensation(state -> {})
            .build();

        assertTrue(options.hasCompensation());
    }

    @Test
    @DisplayName("hasCompensation should return false when compensation is null")
    void hasCompensationShouldReturnFalseWhenCompensationIsNull() {
        StepOptions<TestState, String> options = StepOptions.<TestState, String>builder()
            .action(state -> "result")
            .build();

        assertFalse(options.hasCompensation());
    }

    // Test implementation of abstract class
    private static class TestState extends ProcessStepState {
    }
}
