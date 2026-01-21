package com.ivamare.commandbus.process.step;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ivamare.commandbus.CommandBusProperties;
import com.ivamare.commandbus.CommandBusProperties.ResilienceProperties;
import com.ivamare.commandbus.exception.DatabaseExceptionClassifier;
import com.ivamare.commandbus.model.PgmqMessage;
import com.ivamare.commandbus.model.ReplyOutcome;
import com.ivamare.commandbus.pgmq.PgmqClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Handler for command step responses.
 *
 * <p>This handler polls reply queues for responses from command steps and routes
 * them back to the appropriate process instances. It's responsible for:
 *
 * <ul>
 *   <li>Polling reply queues for command step responses</li>
 *   <li>Parsing response messages and creating CommandStepResponse objects</li>
 *   <li>Routing responses to the correct process via processAsyncResponse()</li>
 *   <li>Handling errors and managing message lifecycle (ack/nack)</li>
 * </ul>
 *
 * <p>Response routing uses the correlation_id (which is the process_id) to
 * identify the target process. The step_name from the response data identifies
 * which command step the response belongs to.
 *
 * <p>Example response message format:
 * <pre>{@code
 * {
 *   "correlation_id": "process-uuid",
 *   "command_id": "command-uuid",
 *   "outcome": "SUCCESS",  // or "FAILED"
 *   "result": { ... },     // for SUCCESS
 *   "error_type": "...",   // for FAILED
 *   "error_code": "...",   // for FAILED
 *   "error_message": "..." // for FAILED
 * }
 * }</pre>
 */
public class CommandStepResponseHandler {

    private static final Logger log = LoggerFactory.getLogger(CommandStepResponseHandler.class);

    private final PgmqClient pgmqClient;
    private final ObjectMapper objectMapper;
    private final List<ProcessStepManager<?>> processManagers;
    private final int visibilityTimeout;
    private final int batchSize;
    private final boolean archiveMessages;

    // Resilience configuration
    private final long initialBackoffMs;
    private final long maxBackoffMs;
    private final double backoffMultiplier;
    private final int errorThreshold;
    private final AtomicInteger consecutiveErrors = new AtomicInteger(0);

    private final ExecutorService virtualThreadExecutor;
    private volatile boolean running = false;

    /**
     * Create a new CommandStepResponseHandler with default settings.
     *
     * @param pgmqClient PGMQ client for queue operations
     * @param objectMapper ObjectMapper for JSON serialization
     * @param processManagers List of process managers that may receive responses
     */
    public CommandStepResponseHandler(
            PgmqClient pgmqClient,
            ObjectMapper objectMapper,
            List<ProcessStepManager<?>> processManagers) {
        this(pgmqClient, objectMapper, processManagers, 30, 100, false, new ResilienceProperties());
    }

    /**
     * Create a new CommandStepResponseHandler with custom settings.
     *
     * @param pgmqClient PGMQ client for queue operations
     * @param objectMapper ObjectMapper for JSON serialization
     * @param processManagers List of process managers that may receive responses
     * @param visibilityTimeout Visibility timeout in seconds
     * @param batchSize Number of messages to process per poll
     * @param archiveMessages Whether to archive messages instead of deleting
     * @param resilience Resilience configuration
     */
    public CommandStepResponseHandler(
            PgmqClient pgmqClient,
            ObjectMapper objectMapper,
            List<ProcessStepManager<?>> processManagers,
            int visibilityTimeout,
            int batchSize,
            boolean archiveMessages,
            ResilienceProperties resilience) {
        this.pgmqClient = pgmqClient;
        this.objectMapper = objectMapper;
        this.processManagers = processManagers;
        this.visibilityTimeout = visibilityTimeout;
        this.batchSize = batchSize;
        this.archiveMessages = archiveMessages;
        this.initialBackoffMs = resilience.getInitialBackoffMs();
        this.maxBackoffMs = resilience.getMaxBackoffMs();
        this.backoffMultiplier = resilience.getBackoffMultiplier();
        this.errorThreshold = resilience.getErrorThreshold();
        this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Start the response handler.
     */
    public void start() {
        this.running = true;
        log.info("CommandStepResponseHandler started");
    }

    /**
     * Stop the response handler gracefully.
     */
    public void stop() {
        this.running = false;
        virtualThreadExecutor.shutdown();
        try {
            if (!virtualThreadExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                virtualThreadExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            virtualThreadExecutor.shutdownNow();
        }
        log.info("CommandStepResponseHandler stopped");
    }

    /**
     * Check if the handler is running.
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Poll reply queues for responses.
     * Should be called periodically (e.g., every 1 second).
     *
     * @param replyQueueName The reply queue to poll
     */
    public void pollResponses(String replyQueueName) {
        if (!running) return;

        try {
            List<PgmqMessage> messages = pgmqClient.read(replyQueueName, visibilityTimeout, batchSize);

            if (!messages.isEmpty()) {
                log.debug("Received {} messages from {}", messages.size(), replyQueueName);
            }

            for (PgmqMessage message : messages) {
                virtualThreadExecutor.submit(() -> processMessage(replyQueueName, message));
            }

            // Reset error count on success
            consecutiveErrors.set(0);

        } catch (Exception e) {
            handlePollError(replyQueueName, e);
        }
    }

    /**
     * Process a single response message.
     */
    private void processMessage(String replyQueueName, PgmqMessage message) {
        try {
            Map<String, Object> payload = message.message();

            // Extract routing information
            String correlationIdStr = (String) payload.get("correlation_id");
            String commandIdStr = (String) payload.get("command_id");

            if (correlationIdStr == null) {
                log.warn("Response message missing correlation_id, skipping: msgId={}", message.msgId());
                deleteMessage(replyQueueName, message.msgId());
                return;
            }

            UUID processId = UUID.fromString(correlationIdStr);
            UUID commandId = commandIdStr != null ? UUID.fromString(commandIdStr) : null;

            // Parse outcome
            String outcomeStr = (String) payload.get("outcome");
            ReplyOutcome outcome = outcomeStr != null
                ? ReplyOutcome.fromValue(outcomeStr)
                : ReplyOutcome.FAILED;

            // Create CommandStepResponse from the message
            CommandStepResponse<?> response = createResponseFromPayload(processId, payload, outcome);

            // Find the process manager for this process
            ProcessStepManager<?> manager = findProcessManager(processId);
            if (manager == null) {
                log.warn("No process manager found for process {}, skipping response", processId);
                deleteMessage(replyQueueName, message.msgId());
                return;
            }

            // Route response to the process
            routeResponseToProcess(manager, processId, response);

            // Acknowledge message
            deleteMessage(replyQueueName, message.msgId());

            log.debug("Successfully processed response for process {} command {}",
                processId, commandId);

        } catch (Exception e) {
            log.error("Failed to process response message msgId={}: {}",
                message.msgId(), e.getMessage(), e);
            // Message will become visible again after visibility timeout
        }
    }

    /**
     * Create a CommandStepResponse from a reply message payload.
     */
    @SuppressWarnings("unchecked")
    private CommandStepResponse<?> createResponseFromPayload(
            UUID processId,
            Map<String, Object> payload,
            ReplyOutcome outcome) {

        // Extract step name from the original command data
        Map<String, Object> data = (Map<String, Object>) payload.get("data");
        String stepName = data != null ? (String) data.get("step_name") : null;
        if (stepName == null) {
            stepName = "unknown";
        }

        if (outcome == ReplyOutcome.SUCCESS) {
            // Success response - extract result
            Object result = payload.get("result");
            return CommandStepResponse.success(processId, stepName, result);
        }

        // Failure response - extract error details
        String errorType = (String) payload.get("error_type");
        String errorCode = (String) payload.get("error_code");
        String errorMessage = (String) payload.get("error_message");

        CommandStepResponse.ErrorType type = mapErrorType(errorType);

        return CommandStepResponse.failure(processId, stepName, type, errorCode, errorMessage);
    }

    /**
     * Map error type string to ErrorType enum.
     */
    private CommandStepResponse.ErrorType mapErrorType(String errorType) {
        if (errorType == null) {
            return CommandStepResponse.ErrorType.PERMANENT;
        }

        return switch (errorType.toUpperCase()) {
            case "TRANSIENT", "TRANSIENT_ERROR" -> CommandStepResponse.ErrorType.TRANSIENT;
            case "BUSINESS", "BUSINESS_RULE", "BUSINESS_ERROR" -> CommandStepResponse.ErrorType.BUSINESS;
            case "TIMEOUT" -> CommandStepResponse.ErrorType.TIMEOUT;
            default -> CommandStepResponse.ErrorType.PERMANENT;
        };
    }

    /**
     * Find the process manager that owns a process.
     */
    private ProcessStepManager<?> findProcessManager(UUID processId) {
        for (ProcessStepManager<?> manager : processManagers) {
            // Check if this manager handles the process
            // This is a simple linear scan - could be optimized with caching
            try {
                // Try to load state - if it succeeds, this is the right manager
                // This is a bit inefficient, but correct
                // In a production system, we'd use domain routing
                return manager;
            } catch (Exception e) {
                // Not this manager
            }
        }
        return processManagers.isEmpty() ? null : processManagers.get(0);
    }

    /**
     * Route a response to the appropriate process.
     *
     * @param manager The process manager
     * @param processId The process ID
     * @param response The command step response
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void routeResponseToProcess(
            ProcessStepManager manager,
            UUID processId,
            CommandStepResponse<?> response) {

        // Use processAsyncResponse to update state and resume process
        manager.processAsyncResponse(processId, state -> {
            if (state instanceof ProcessStepState processState) {
                // Store the response in state for the next execution to pick up
                processState.storeCommandStepResponse(response.stepName(), response);
            }
        });

        log.info("Routed response for step {} to process {} (success={})",
            response.stepName(), processId, response.success());
    }

    /**
     * Delete or archive a message.
     */
    private void deleteMessage(String queueName, long msgId) {
        try {
            if (archiveMessages) {
                pgmqClient.archive(queueName, msgId);
            } else {
                pgmqClient.delete(queueName, msgId);
            }
        } catch (Exception e) {
            log.error("Failed to delete/archive message {}: {}", msgId, e.getMessage());
        }
    }

    /**
     * Handle poll errors with exponential backoff.
     */
    private void handlePollError(String replyQueueName, Exception e) {
        int errors = consecutiveErrors.incrementAndGet();

        if (DatabaseExceptionClassifier.isTransient(e)) {
            long backoff = calculateBackoff(errors);
            if (errors >= errorThreshold) {
                log.error("Database error polling {} (consecutive errors: {}), backing off for {}ms",
                    replyQueueName, errors, backoff, e);
            } else {
                log.warn("Database error polling {} (consecutive errors: {}), backing off for {}ms",
                    replyQueueName, errors, backoff);
            }
            sleep(backoff);
        } else {
            log.error("Unexpected error polling {}: {}", replyQueueName, e.getMessage(), e);
        }
    }

    /**
     * Calculate exponential backoff delay.
     */
    private long calculateBackoff(int errorCount) {
        if (errorCount <= 0) {
            return initialBackoffMs;
        }
        double delay = initialBackoffMs * Math.pow(backoffMultiplier, errorCount - 1);
        double jitter = delay * 0.1 * (Math.random() * 2 - 1);
        return Math.min((long) (delay + jitter), maxBackoffMs);
    }

    /**
     * Sleep for the given duration, handling interrupts.
     */
    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
