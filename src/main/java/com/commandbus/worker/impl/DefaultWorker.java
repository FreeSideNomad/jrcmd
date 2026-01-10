package com.commandbus.worker.impl;

import com.commandbus.exception.PermanentCommandException;
import com.commandbus.exception.TransientCommandException;
import com.commandbus.handler.HandlerRegistry;
import com.commandbus.model.*;
import com.commandbus.pgmq.PgmqClient;
import com.commandbus.pgmq.impl.JdbcPgmqClient;
import com.commandbus.policy.RetryPolicy;
import com.commandbus.repository.CommandRepository;
import com.commandbus.repository.impl.JdbcCommandRepository;
import com.commandbus.worker.Worker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Default worker implementation using virtual threads.
 */
public class DefaultWorker implements Worker {

    private static final Logger log = LoggerFactory.getLogger(DefaultWorker.class);

    private final JdbcTemplate jdbcTemplate;
    private final String domain;
    private final String queueName;
    private final HandlerRegistry handlerRegistry;
    private final int visibilityTimeout;
    private final int pollIntervalMs;
    private final int concurrency;
    private final boolean useNotify;
    private final RetryPolicy retryPolicy;

    private final PgmqClient pgmqClient;
    private final CommandRepository commandRepository;
    private final ObjectMapper objectMapper;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private final AtomicInteger inFlightCount = new AtomicInteger(0);
    private final Semaphore semaphore;

    private ExecutorService executor;

    /**
     * Creates a new DefaultWorker.
     *
     * @param jdbcTemplate JDBC template for database operations
     * @param objectMapper Object mapper for JSON serialization
     * @param domain Domain to process commands for
     * @param handlerRegistry Registry of command handlers
     * @param visibilityTimeout Visibility timeout in seconds
     * @param pollIntervalMs Poll interval in milliseconds
     * @param concurrency Number of concurrent handlers
     * @param useNotify Whether to use PostgreSQL NOTIFY
     * @param retryPolicy Retry policy for failed commands
     */
    public DefaultWorker(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            String domain,
            HandlerRegistry handlerRegistry,
            int visibilityTimeout,
            int pollIntervalMs,
            int concurrency,
            boolean useNotify,
            RetryPolicy retryPolicy) {
        this(
            jdbcTemplate,
            objectMapper,
            domain,
            handlerRegistry,
            visibilityTimeout,
            pollIntervalMs,
            concurrency,
            useNotify,
            retryPolicy,
            new JdbcPgmqClient(jdbcTemplate, objectMapper),
            new JdbcCommandRepository(jdbcTemplate)
        );
    }

    /**
     * Creates a new DefaultWorker with injectable dependencies (for testing).
     *
     * @param jdbcTemplate JDBC template for database operations
     * @param objectMapper Object mapper for JSON serialization
     * @param domain Domain to process commands for
     * @param handlerRegistry Registry of command handlers
     * @param visibilityTimeout Visibility timeout in seconds
     * @param pollIntervalMs Poll interval in milliseconds
     * @param concurrency Number of concurrent handlers
     * @param useNotify Whether to use PostgreSQL NOTIFY
     * @param retryPolicy Retry policy for failed commands
     * @param pgmqClient PGMQ client
     * @param commandRepository Command repository
     */
    public DefaultWorker(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            String domain,
            HandlerRegistry handlerRegistry,
            int visibilityTimeout,
            int pollIntervalMs,
            int concurrency,
            boolean useNotify,
            RetryPolicy retryPolicy,
            PgmqClient pgmqClient,
            CommandRepository commandRepository) {

        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.domain = domain;
        this.queueName = domain + "__commands";
        this.handlerRegistry = handlerRegistry;
        this.visibilityTimeout = visibilityTimeout;
        this.pollIntervalMs = pollIntervalMs;
        this.concurrency = concurrency;
        this.useNotify = useNotify;
        this.retryPolicy = retryPolicy;

        this.pgmqClient = pgmqClient;
        this.commandRepository = commandRepository;

        this.semaphore = new Semaphore(concurrency);
    }

    @Override
    public void start() {
        if (running.getAndSet(true)) {
            log.warn("Worker for {} already running", domain);
            return;
        }

        stopping.set(false);
        executor = Executors.newVirtualThreadPerTaskExecutor();

        log.info("Starting worker for domain={}, concurrency={}, useNotify={}",
            domain, concurrency, useNotify);

        executor.submit(this::runLoop);
    }

    @Override
    public CompletableFuture<Void> stop(Duration timeout) {
        if (!running.get()) {
            return CompletableFuture.completedFuture(null);
        }

        stopping.set(true);
        log.info("Stopping worker for {}, waiting for {} in-flight commands",
            domain, inFlightCount.get());

        return CompletableFuture.runAsync(() -> {
            try {
                // Wait for in-flight tasks
                long deadline = System.currentTimeMillis() + timeout.toMillis();
                while (inFlightCount.get() > 0 && System.currentTimeMillis() < deadline) {
                    Thread.sleep(100);
                }

                if (inFlightCount.get() > 0) {
                    log.warn("Timeout waiting for {} in-flight commands", inFlightCount.get());
                }

                running.set(false);
                executor.shutdown();

                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }

                log.info("Worker for {} stopped", domain);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    @Override
    public void stopNow() {
        stopping.set(true);
        running.set(false);
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get() && !stopping.get();
    }

    @Override
    public int inFlightCount() {
        return inFlightCount.get();
    }

    @Override
    public String domain() {
        return domain;
    }

    // --- Main Processing Loop ---

    private void runLoop() {
        log.debug("Worker loop started for {}", domain);

        while (running.get() && !stopping.get()) {
            try {
                drainQueue();

                // Wait for new messages or poll interval
                if (!stopping.get()) {
                    waitForMessages();
                }
            } catch (Exception e) {
                if (!stopping.get()) {
                    log.error("Error in worker loop for {}", domain, e);
                    sleep(1000); // Back off on error
                }
            }
        }

        log.debug("Worker loop ended for {}", domain);
    }

    private void drainQueue() {
        while (running.get() && !stopping.get()) {
            int availableSlots = semaphore.availablePermits();
            if (availableSlots == 0) {
                // Wait for a slot to become available
                waitForSlot();
                continue;
            }

            // Read up to available slots messages
            List<PgmqMessage> messages = pgmqClient.read(queueName, visibilityTimeout, availableSlots);

            if (messages.isEmpty()) {
                break; // Queue drained
            }

            for (PgmqMessage message : messages) {
                processMessage(message);
            }

            // Yield to allow fair scheduling
            Thread.yield();
        }
    }

    private void waitForSlot() {
        try {
            semaphore.acquire();
            semaphore.release();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void waitForMessages() {
        // TODO: Implement PostgreSQL LISTEN/NOTIFY for instant wake
        // For now, fall back to polling
        sleep(pollIntervalMs);
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // --- Message Processing ---

    private void processMessage(PgmqMessage pgmqMessage) {
        try {
            semaphore.acquire();
            inFlightCount.incrementAndGet();

            executor.submit(() -> {
                try {
                    processMessageInternal(pgmqMessage);
                } finally {
                    inFlightCount.decrementAndGet();
                    semaphore.release();
                }
            });

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @SuppressWarnings("unchecked")
    private void processMessageInternal(PgmqMessage pgmqMessage) {
        Map<String, Object> payload = pgmqMessage.message();

        String commandIdStr = (String) payload.get("command_id");
        UUID commandId = UUID.fromString(commandIdStr);

        log.debug("Processing message msgId={}, commandId={}", pgmqMessage.msgId(), commandId);

        try {
            // Atomically receive the command
            Optional<CommandMetadata> metadataOpt = commandRepository.spReceiveCommand(
                domain, commandId, pgmqMessage.msgId(), null
            );

            if (metadataOpt.isEmpty()) {
                log.debug("Command {} not receivable (terminal state or not found)", commandId);
                return;
            }

            CommandMetadata metadata = metadataOpt.get();

            // Build Command and Context
            Command command = buildCommand(payload, metadata);
            HandlerContext context = buildContext(command, metadata, pgmqMessage.msgId());

            // Dispatch to handler
            Object result = handlerRegistry.dispatch(command, context);

            // Complete successfully
            complete(metadata, pgmqMessage.msgId(), result);

        } catch (TransientCommandException e) {
            handleTransientError(commandId, pgmqMessage.msgId(), e);
        } catch (PermanentCommandException e) {
            handlePermanentError(commandId, pgmqMessage.msgId(), e);
        } catch (Exception e) {
            // Treat unknown exceptions as transient
            handleTransientError(commandId, pgmqMessage.msgId(),
                new TransientCommandException("INTERNAL_ERROR", e.getMessage()));
        }
    }

    @SuppressWarnings("unchecked")
    private Command buildCommand(Map<String, Object> payload, CommandMetadata metadata) {
        Object dataObj = payload.getOrDefault("data", Map.of());
        Map<String, Object> data;
        if (dataObj instanceof Map) {
            data = (Map<String, Object>) dataObj;
        } else {
            data = Map.of();
        }

        Object correlationIdObj = payload.get("correlation_id");
        UUID correlationId = correlationIdObj != null ?
            UUID.fromString(correlationIdObj.toString()) : null;

        return new Command(
            (String) payload.get("domain"),
            (String) payload.get("command_type"),
            UUID.fromString((String) payload.get("command_id")),
            data,
            correlationId,
            (String) payload.get("reply_to"),
            metadata.createdAt()
        );
    }

    private HandlerContext buildContext(Command command, CommandMetadata metadata, long msgId) {
        return new HandlerContext(
            command,
            metadata.attempts(),
            metadata.maxAttempts(),
            msgId,
            seconds -> pgmqClient.setVisibilityTimeout(queueName, msgId, seconds)
        );
    }

    // --- Completion Handling ---

    private void complete(CommandMetadata metadata, long msgId, Object result) {
        log.debug("Completing command {} with result", metadata.commandId());

        // Delete from queue
        pgmqClient.delete(queueName, msgId);

        // Update status via stored procedure
        String details = result != null ? serializeResult(result) : null;
        boolean isBatchComplete = commandRepository.spFinishCommand(
            domain,
            metadata.commandId(),
            CommandStatus.COMPLETED,
            AuditEventType.COMPLETED,
            null, null, null,
            details,
            metadata.batchId()
        );

        // Send reply if configured
        if (metadata.replyTo() != null && !metadata.replyTo().isBlank()) {
            sendReply(metadata, ReplyOutcome.SUCCESS, result, null, null);
        }

        // Invoke batch callback if complete
        if (isBatchComplete && metadata.batchId() != null) {
            invokeBatchCallback(metadata.batchId());
        }

        log.info("Completed command {}.{} (commandId={})",
            domain, metadata.commandType(), metadata.commandId());
    }

    private void handleTransientError(UUID commandId, long msgId, TransientCommandException e) {
        log.debug("Transient error for command {}: {}", commandId, e.getMessage());

        Optional<CommandMetadata> metadataOpt = commandRepository.get(domain, commandId);
        if (metadataOpt.isEmpty()) return;

        CommandMetadata metadata = metadataOpt.get();

        if (retryPolicy.shouldRetry(metadata.attempts())) {
            // Record failure and schedule retry
            commandRepository.spFailCommand(
                domain, commandId,
                "TRANSIENT", e.getCode(), e.getErrorMessage(),
                metadata.attempts(), metadata.maxAttempts(), msgId
            );

            int backoff = retryPolicy.getBackoff(metadata.attempts());
            pgmqClient.setVisibilityTimeout(queueName, msgId, backoff);

            log.info("Scheduled retry for command {} in {}s (attempt {}/{})",
                commandId, backoff, metadata.attempts(), metadata.maxAttempts());
        } else {
            // Retries exhausted - move to TSQ
            failExhausted(metadata, msgId, e);
        }
    }

    private void handlePermanentError(UUID commandId, long msgId, PermanentCommandException e) {
        log.debug("Permanent error for command {}: {}", commandId, e.getMessage());

        Optional<CommandMetadata> metadataOpt = commandRepository.get(domain, commandId);
        if (metadataOpt.isEmpty()) return;

        CommandMetadata metadata = metadataOpt.get();

        // Archive the message
        pgmqClient.archive(queueName, msgId);

        // Update status
        commandRepository.spFinishCommand(
            domain, commandId,
            CommandStatus.IN_TROUBLESHOOTING_QUEUE,
            AuditEventType.MOVED_TO_TSQ,
            "PERMANENT", e.getCode(), e.getErrorMessage(),
            null,
            metadata.batchId()
        );

        // Send failure reply
        if (metadata.replyTo() != null) {
            sendReply(metadata, ReplyOutcome.FAILED, null, e.getCode(), e.getErrorMessage());
        }

        log.warn("Command {} moved to TSQ (permanent error): {}",
            commandId, e.getMessage());
    }

    private void failExhausted(CommandMetadata metadata, long msgId, TransientCommandException e) {
        // Archive the message
        pgmqClient.archive(queueName, msgId);

        // Update status
        commandRepository.spFinishCommand(
            domain, metadata.commandId(),
            CommandStatus.IN_TROUBLESHOOTING_QUEUE,
            AuditEventType.MOVED_TO_TSQ,
            "TRANSIENT", e.getCode(), e.getErrorMessage(),
            null,
            metadata.batchId()
        );

        // Send failure reply
        if (metadata.replyTo() != null) {
            sendReply(metadata, ReplyOutcome.FAILED, null, e.getCode(), e.getErrorMessage());
        }

        log.warn("Command {} moved to TSQ (retries exhausted): {}",
            metadata.commandId(), e.getMessage());
    }

    private void sendReply(CommandMetadata metadata, ReplyOutcome outcome,
                          Object result, String errorCode, String errorMessage) {
        try {
            Map<String, Object> reply = new HashMap<>();
            reply.put("command_id", metadata.commandId().toString());
            if (metadata.correlationId() != null) {
                reply.put("correlation_id", metadata.correlationId().toString());
            }
            reply.put("outcome", outcome.getValue());

            if (result != null) {
                reply.put("result", result);
            }
            if (errorCode != null) {
                reply.put("error_code", errorCode);
                reply.put("error_message", errorMessage);
            }

            pgmqClient.send(metadata.replyTo(), reply);
        } catch (Exception e) {
            log.error("Failed to send reply for command {}", metadata.commandId(), e);
        }
    }

    private void invokeBatchCallback(UUID batchId) {
        // TODO: Implement batch callback invocation
        log.debug("Batch {} completed", batchId);
    }

    private String serializeResult(Object result) {
        try {
            return objectMapper.writeValueAsString(Map.of("result", result));
        } catch (Exception e) {
            return null;
        }
    }
}
