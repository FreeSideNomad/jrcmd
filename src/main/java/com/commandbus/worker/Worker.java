package com.commandbus.worker;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Worker for processing commands from a domain queue.
 *
 * <p>The worker reads messages from PGMQ, dispatches them to registered handlers,
 * and manages the command lifecycle including retries and error handling.
 *
 * <p>Example:
 * <pre>
 * Worker worker = Worker.builder()
 *     .jdbcTemplate(jdbcTemplate)
 *     .domain("payments")
 *     .handlerRegistry(registry)
 *     .concurrency(4)
 *     .build();
 *
 * worker.start();
 * // ... later
 * worker.stop(Duration.ofSeconds(30));
 * </pre>
 */
public interface Worker {

    /**
     * Start the worker.
     *
     * <p>The worker begins reading messages from the queue and dispatching
     * them to handlers. Processing continues until stop() is called.
     */
    void start();

    /**
     * Stop the worker gracefully.
     *
     * <p>Stops accepting new messages and waits for in-flight commands
     * to complete within the specified timeout.
     *
     * @param timeout Maximum time to wait for in-flight commands
     * @return Future that completes when worker has stopped
     */
    CompletableFuture<Void> stop(Duration timeout);

    /**
     * Stop the worker immediately without waiting.
     */
    void stopNow();

    /**
     * Check if the worker is running.
     *
     * @return true if worker is accepting and processing commands
     */
    boolean isRunning();

    /**
     * Get the number of commands currently being processed.
     *
     * @return count of in-flight commands
     */
    int inFlightCount();

    /**
     * Get the domain this worker processes.
     *
     * @return domain name
     */
    String domain();

    /**
     * Create a new worker builder.
     *
     * @return new builder instance
     */
    static WorkerBuilder builder() {
        return new WorkerBuilder();
    }
}
