package com.ivamare.commandbus.process.step.exceptions;

/**
 * Exception thrown when a rate limit is exceeded.
 *
 * <p>This exception is thrown when a step cannot acquire a rate limit
 * ticket within the configured timeout. The framework handles this
 * by scheduling a retry.
 */
public class RateLimitExceededException extends RuntimeException {

    private final String resourceKey;

    /**
     * Create a rate limit exceeded exception.
     *
     * @param message Description of the rate limit exceeded
     */
    public RateLimitExceededException(String message) {
        super(message);
        this.resourceKey = null;
    }

    /**
     * Create a rate limit exceeded exception with resource key.
     *
     * @param message Description of the rate limit exceeded
     * @param resourceKey The rate limit resource key that was exceeded
     */
    public RateLimitExceededException(String message, String resourceKey) {
        super(message);
        this.resourceKey = resourceKey;
    }

    /**
     * Get the rate limit resource key, if available.
     */
    public String getResourceKey() {
        return resourceKey;
    }
}
