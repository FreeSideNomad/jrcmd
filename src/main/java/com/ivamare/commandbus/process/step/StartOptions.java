package com.ivamare.commandbus.process.step;

/**
 * Options for starting a single process with ProcessStepManager.
 *
 * <p>Controls whether the process executes immediately in the caller thread
 * or is deferred for worker pickup:
 *
 * <ul>
 *   <li><b>Immediate (default)</b>: Process executes in caller thread until
 *       completion, wait for async, or retry needed. Good for API calls.</li>
 *   <li><b>Deferred</b>: Process created as PENDING; worker picks up later.
 *       Good for background jobs and batch processing.</li>
 * </ul>
 */
public class StartOptions {

    private final boolean executeImmediately;

    private StartOptions(boolean executeImmediately) {
        this.executeImmediately = executeImmediately;
    }

    /**
     * Create default options (immediate execution).
     */
    public static StartOptions defaults() {
        return new StartOptions(true);
    }

    /**
     * Create options for deferred execution (worker picks up PENDING process).
     */
    public static StartOptions deferred() {
        return new StartOptions(false);
    }

    /**
     * Create a builder for custom options.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Check if process should execute immediately in caller thread.
     */
    public boolean isExecuteImmediately() {
        return executeImmediately;
    }

    /**
     * Check if process is deferred for worker pickup.
     */
    public boolean isDeferred() {
        return !executeImmediately;
    }

    /**
     * Builder for StartOptions.
     */
    public static class Builder {
        private boolean executeImmediately = true;  // Default: immediate execution

        /**
         * Set whether to execute immediately in caller thread.
         */
        public Builder executeImmediately(boolean executeImmediately) {
            this.executeImmediately = executeImmediately;
            return this;
        }

        /**
         * Configure for deferred execution (worker picks up).
         */
        public Builder deferred() {
            this.executeImmediately = false;
            return this;
        }

        /**
         * Build the StartOptions instance.
         */
        public StartOptions build() {
            return new StartOptions(executeImmediately);
        }
    }
}
