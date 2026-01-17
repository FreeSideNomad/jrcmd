package com.ivamare.commandbus.process.step;

import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Configuration options for a step execution within ProcessStepManager.
 *
 * <p>Provides builder pattern for configuring:
 * <ul>
 *   <li>Step action to execute</li>
 *   <li>Retry behavior (attempts, delay)</li>
 *   <li>Timeout configuration</li>
 *   <li>Compensation action for saga pattern</li>
 *   <li>Rate limiting</li>
 * </ul>
 *
 * @param <TState> The process state type
 * @param <R> The step result type
 */
public class StepOptions<TState extends ProcessStepState, R> {

    private final Function<TState, R> action;
    private final int maxRetries;
    private final Duration retryDelay;
    private final Duration timeout;
    private final Consumer<TState> compensation;
    private final String rateLimitKey;
    private final Duration rateLimitTimeout;

    private StepOptions(Builder<TState, R> builder) {
        this.action = builder.action;
        this.maxRetries = builder.maxRetries;
        this.retryDelay = builder.retryDelay;
        this.timeout = builder.timeout;
        this.compensation = builder.compensation;
        this.rateLimitKey = builder.rateLimitKey;
        this.rateLimitTimeout = builder.rateLimitTimeout;
    }

    public static <TState extends ProcessStepState, R> Builder<TState, R> builder() {
        return new Builder<>();
    }

    public Function<TState, R> action() {
        return action;
    }

    public int maxRetries() {
        return maxRetries;
    }

    public Duration retryDelay() {
        return retryDelay;
    }

    public Duration timeout() {
        return timeout;
    }

    public Consumer<TState> compensation() {
        return compensation;
    }

    public String rateLimitKey() {
        return rateLimitKey;
    }

    public Duration rateLimitTimeout() {
        return rateLimitTimeout;
    }

    /**
     * Check if this step has retry configured (more than 1 attempt).
     */
    public boolean hasRetry() {
        return maxRetries > 1;
    }

    /**
     * Check if this step has rate limiting configured.
     */
    public boolean hasRateLimiting() {
        return rateLimitKey != null && !rateLimitKey.isBlank();
    }

    /**
     * Check if this step has compensation configured.
     */
    public boolean hasCompensation() {
        return compensation != null;
    }

    /**
     * Builder for StepOptions.
     */
    public static class Builder<TState extends ProcessStepState, R> {
        private Function<TState, R> action;
        private int maxRetries = 1;                      // Default: no retry
        private Duration retryDelay = Duration.ofSeconds(1);
        private Duration timeout = Duration.ofSeconds(30);
        private Consumer<TState> compensation;
        private String rateLimitKey;
        private Duration rateLimitTimeout = Duration.ofSeconds(5);

        /**
         * Set the action to execute for this step.
         */
        public Builder<TState, R> action(Function<TState, R> action) {
            this.action = action;
            return this;
        }

        /**
         * Set maximum retry attempts (1 = no retry).
         */
        public Builder<TState, R> maxRetries(int maxRetries) {
            if (maxRetries < 1) {
                throw new IllegalArgumentException("maxRetries must be at least 1");
            }
            this.maxRetries = maxRetries;
            return this;
        }

        /**
         * Set base delay between retries (will be exponentially increased).
         */
        public Builder<TState, R> retryDelay(Duration retryDelay) {
            this.retryDelay = retryDelay;
            return this;
        }

        /**
         * Set execution timeout for this step.
         */
        public Builder<TState, R> timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /**
         * Set compensation action for saga rollback.
         */
        public Builder<TState, R> compensation(Consumer<TState> compensation) {
            this.compensation = compensation;
            return this;
        }

        /**
         * Set rate limit key for distributed rate limiting.
         */
        public Builder<TState, R> rateLimitKey(String rateLimitKey) {
            this.rateLimitKey = rateLimitKey;
            return this;
        }

        /**
         * Set maximum wait time for rate limit ticket.
         */
        public Builder<TState, R> rateLimitTimeout(Duration rateLimitTimeout) {
            this.rateLimitTimeout = rateLimitTimeout;
            return this;
        }

        /**
         * Build the StepOptions instance.
         */
        public StepOptions<TState, R> build() {
            if (action == null) {
                throw new IllegalStateException("action must be set");
            }
            return new StepOptions<>(this);
        }
    }
}
