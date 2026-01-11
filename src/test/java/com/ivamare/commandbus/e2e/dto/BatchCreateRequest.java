package com.ivamare.commandbus.e2e.dto;

/**
 * Request for creating a new command batch.
 */
public record BatchCreateRequest(
    String name,
    int commandCount,
    String commandType,
    int maxAttempts,
    BatchBehavior behavior
) {
    /**
     * Behavior configuration for commands in the batch.
     * Probabilities are evaluated sequentially (not independently).
     */
    public record BatchBehavior(
        double failPermanentPct,
        double failTransientPct,
        double failBusinessRulePct,
        double timeoutPct,
        int minDurationMs,
        int maxDurationMs
    ) {
        public static BatchBehavior defaultBehavior() {
            return new BatchBehavior(0, 0, 0, 0, 0, 100);
        }
    }

    public static BatchCreateRequest defaults() {
        return new BatchCreateRequest(
            "Test Batch",
            10,
            "TestCommand",
            3,
            BatchBehavior.defaultBehavior()
        );
    }
}
