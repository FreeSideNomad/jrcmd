package com.ivamare.commandbus.policy;

import java.util.List;

/**
 * Policy for handling command retries.
 *
 * @param maxAttempts Maximum number of attempts before giving up
 * @param backoffSchedule List of visibility timeouts in seconds for each retry
 */
public record RetryPolicy(
    int maxAttempts,
    List<Integer> backoffSchedule
) {
    /**
     * Creates a RetryPolicy with immutable backoff schedule.
     */
    public RetryPolicy {
        backoffSchedule = List.copyOf(backoffSchedule);
    }

    /**
     * Default retry policy: 3 attempts with backoff [10, 60, 300].
     *
     * @return Default retry policy
     */
    public static RetryPolicy defaultPolicy() {
        return new RetryPolicy(3, List.of(10, 60, 300));
    }

    /**
     * Create a policy with no retries.
     *
     * @return No retry policy
     */
    public static RetryPolicy noRetry() {
        return new RetryPolicy(1, List.of());
    }

    /**
     * Get the backoff delay for a given attempt number.
     *
     * @param attempt The current attempt number (1-based)
     * @return Visibility timeout in seconds for the next retry
     */
    public int getBackoff(int attempt) {
        if (attempt >= maxAttempts) {
            return 0; // No more retries
        }

        int index = attempt - 1;
        if (index < 0) {
            return backoffSchedule.isEmpty() ? 30 : backoffSchedule.getFirst();
        }

        if (index < backoffSchedule.size()) {
            return backoffSchedule.get(index);
        }

        // Use last value for attempts beyond schedule
        return backoffSchedule.isEmpty() ? 30 : backoffSchedule.getLast();
    }

    /**
     * Check if another retry should be attempted.
     *
     * @param attempt The current attempt number (1-based)
     * @return true if more attempts are allowed
     */
    public boolean shouldRetry(int attempt) {
        return attempt < maxAttempts;
    }
}
