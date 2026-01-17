package com.ivamare.commandbus.process.step;

/**
 * Options for starting a batch of processes with ProcessStepManager.
 *
 * <p>By default, batch processes are deferred (worker picks up PENDING processes),
 * which is different from single process start that defaults to immediate execution.
 *
 * <p>This is because batch operations are typically used for:
 * <ul>
 *   <li>Background job processing</li>
 *   <li>Scheduled batch runs</li>
 *   <li>High-volume scenarios where parallel worker processing is preferred</li>
 * </ul>
 */
public class BatchOptions {

    private final boolean executeImmediately;

    private BatchOptions(boolean executeImmediately) {
        this.executeImmediately = executeImmediately;
    }

    /**
     * Create default options (deferred execution for batches).
     */
    public static BatchOptions defaults() {
        return new BatchOptions(false);  // Note: Different from StartOptions.defaults()
    }

    /**
     * Create options for immediate execution of all batch items.
     */
    public static BatchOptions immediate() {
        return new BatchOptions(true);
    }

    /**
     * Create a builder for custom options.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Check if processes should execute immediately.
     */
    public boolean isExecuteImmediately() {
        return executeImmediately;
    }

    /**
     * Check if processes are deferred for worker pickup.
     */
    public boolean isDeferred() {
        return !executeImmediately;
    }

    /**
     * Builder for BatchOptions.
     */
    public static class Builder {
        private boolean executeImmediately = false;  // Default: deferred (different from StartOptions)

        /**
         * Set whether to execute immediately.
         */
        public Builder executeImmediately(boolean executeImmediately) {
            this.executeImmediately = executeImmediately;
            return this;
        }

        /**
         * Build the BatchOptions instance.
         */
        public BatchOptions build() {
            return new BatchOptions(executeImmediately);
        }
    }
}
