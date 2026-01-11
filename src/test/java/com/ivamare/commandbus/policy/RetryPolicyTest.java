package com.ivamare.commandbus.policy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RetryPolicy")
class RetryPolicyTest {

    @Test
    @DisplayName("should create default policy with 3 attempts")
    void shouldCreateDefaultPolicyWithThreeAttempts() {
        RetryPolicy policy = RetryPolicy.defaultPolicy();

        assertEquals(3, policy.maxAttempts());
        assertEquals(List.of(10, 60, 300), policy.backoffSchedule());
    }

    @Test
    @DisplayName("should create no retry policy")
    void shouldCreateNoRetryPolicy() {
        RetryPolicy policy = RetryPolicy.noRetry();

        assertEquals(1, policy.maxAttempts());
        assertTrue(policy.backoffSchedule().isEmpty());
    }

    @Test
    @DisplayName("should make backoff schedule immutable")
    void shouldMakeBackoffScheduleImmutable() {
        RetryPolicy policy = RetryPolicy.defaultPolicy();

        assertThrows(UnsupportedOperationException.class, () ->
            policy.backoffSchedule().add(500)
        );
    }

    @Test
    @DisplayName("should return correct backoff for attempt 1")
    void shouldReturnCorrectBackoffForAttemptOne() {
        RetryPolicy policy = RetryPolicy.defaultPolicy();

        assertEquals(10, policy.getBackoff(1));
    }

    @Test
    @DisplayName("should return correct backoff for attempt 2")
    void shouldReturnCorrectBackoffForAttemptTwo() {
        RetryPolicy policy = RetryPolicy.defaultPolicy();

        assertEquals(60, policy.getBackoff(2));
    }

    @Test
    @DisplayName("should return last backoff for attempt beyond schedule")
    void shouldReturnLastBackoffForAttemptBeyondSchedule() {
        RetryPolicy policy = new RetryPolicy(5, List.of(10, 60));

        // Attempt 3 and beyond should return the last value (60)
        assertEquals(60, policy.getBackoff(3));
        assertEquals(60, policy.getBackoff(4));
    }

    @Test
    @DisplayName("should return 0 for exhausted attempts")
    void shouldReturnZeroForExhaustedAttempts() {
        RetryPolicy policy = RetryPolicy.defaultPolicy();

        assertEquals(0, policy.getBackoff(3)); // maxAttempts is 3
        assertEquals(0, policy.getBackoff(4));
    }

    @Test
    @DisplayName("should return default backoff when schedule is empty")
    void shouldReturnDefaultBackoffWhenScheduleEmpty() {
        RetryPolicy policy = new RetryPolicy(3, List.of());

        assertEquals(30, policy.getBackoff(1));
    }

    @Test
    @DisplayName("should return default backoff for negative attempt")
    void shouldReturnDefaultBackoffForNegativeAttempt() {
        RetryPolicy policy = new RetryPolicy(3, List.of(10, 60));

        // Negative attempt index returns first value
        assertEquals(10, policy.getBackoff(0));
    }

    @Test
    @DisplayName("should allow retry when attempt less than max")
    void shouldAllowRetryWhenAttemptLessThanMax() {
        RetryPolicy policy = RetryPolicy.defaultPolicy();

        assertTrue(policy.shouldRetry(1));
        assertTrue(policy.shouldRetry(2));
    }

    @Test
    @DisplayName("should not allow retry when attempt equals max")
    void shouldNotAllowRetryWhenAttemptEqualsMax() {
        RetryPolicy policy = RetryPolicy.defaultPolicy();

        assertFalse(policy.shouldRetry(3));
    }

    @Test
    @DisplayName("should not allow retry when attempt exceeds max")
    void shouldNotAllowRetryWhenAttemptExceedsMax() {
        RetryPolicy policy = RetryPolicy.defaultPolicy();

        assertFalse(policy.shouldRetry(5));
    }

    @Test
    @DisplayName("should create custom policy")
    void shouldCreateCustomPolicy() {
        RetryPolicy policy = new RetryPolicy(5, List.of(5, 15, 30, 60));

        assertEquals(5, policy.maxAttempts());
        assertEquals(4, policy.backoffSchedule().size());
        assertEquals(5, policy.getBackoff(1));
        assertEquals(15, policy.getBackoff(2));
        assertEquals(30, policy.getBackoff(3));
        assertEquals(60, policy.getBackoff(4));
    }
}
