package com.commandbus.model;

/**
 * Context provided to command handlers during execution.
 *
 * <p>Provides access to command metadata and utilities like visibility
 * timeout extension for long-running handlers.
 *
 * @param command The command being processed
 * @param attempt Current attempt number (1-based)
 * @param maxAttempts Maximum attempts before exhaustion
 * @param msgId PGMQ message ID
 * @param visibilityExtender Function to extend visibility timeout (nullable)
 */
public record HandlerContext(
    Command command,
    int attempt,
    int maxAttempts,
    long msgId,
    VisibilityExtender visibilityExtender
) {
    /**
     * Extend the visibility timeout for long-running operations.
     *
     * @param seconds Additional seconds to extend visibility
     * @throws IllegalStateException if visibility extender is not available
     */
    public void extendVisibility(int seconds) {
        if (visibilityExtender == null) {
            throw new IllegalStateException("Visibility extender not available");
        }
        visibilityExtender.extend(seconds);
    }

    /**
     * Check if this is the last retry attempt.
     *
     * @return true if no more retries after this attempt
     */
    public boolean isLastAttempt() {
        return attempt >= maxAttempts;
    }

    /**
     * Functional interface for extending message visibility.
     */
    @FunctionalInterface
    public interface VisibilityExtender {
        void extend(int seconds);
    }
}
