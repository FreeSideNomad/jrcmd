package com.commandbus.process;

import com.commandbus.model.PgmqMessage;
import com.commandbus.model.Reply;
import com.commandbus.model.ReplyOutcome;
import com.commandbus.pgmq.PgmqClient;
import org.postgresql.PGConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Routes replies from process queue to appropriate process managers.
 *
 * <p>Implements a high-concurrency worker pattern using virtual threads,
 * semaphores and pg_notify for efficient throughput.
 */
public class ProcessReplyRouter {

    private static final Logger log = LoggerFactory.getLogger(ProcessReplyRouter.class);
    private static final String PGMQ_NOTIFY_CHANNEL = "pgmq_new_message";

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ProcessRepository processRepo;
    private final Map<String, BaseProcessManager<?, ?>> managers;
    private final PgmqClient pgmqClient;
    private final String replyQueue;
    private final String domain;
    private final int visibilityTimeout;
    private final int concurrency;
    private final long pollIntervalMs;
    private final boolean useNotify;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private final AtomicInteger inFlightCount = new AtomicInteger(0);
    private final Semaphore semaphore;

    private ExecutorService executor;

    public ProcessReplyRouter(
            DataSource dataSource,
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            ProcessRepository processRepo,
            Map<String, BaseProcessManager<?, ?>> managers,
            PgmqClient pgmqClient,
            String replyQueue,
            String domain,
            int visibilityTimeout,
            int concurrency,
            long pollIntervalMs,
            boolean useNotify) {
        this.dataSource = dataSource;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.processRepo = processRepo;
        this.managers = managers;
        this.pgmqClient = pgmqClient;
        this.replyQueue = replyQueue;
        this.domain = domain;
        this.visibilityTimeout = visibilityTimeout;
        this.concurrency = concurrency;
        this.pollIntervalMs = pollIntervalMs;
        this.useNotify = useNotify;
        this.semaphore = new Semaphore(concurrency);
    }

    public boolean isRunning() {
        return running.get() && !stopping.get();
    }

    public String getReplyQueue() {
        return replyQueue;
    }

    public String getDomain() {
        return domain;
    }

    public int inFlightCount() {
        return inFlightCount.get();
    }

    /**
     * Start the router.
     */
    public void start() {
        if (running.getAndSet(true)) {
            log.warn("Reply router for {} already running", replyQueue);
            return;
        }

        stopping.set(false);
        executor = Executors.newVirtualThreadPerTaskExecutor();

        log.info("Starting process reply router on {} (concurrency={}, useNotify={})",
            replyQueue, concurrency, useNotify);

        executor.submit(this::runLoop);
    }

    /**
     * Stop the router gracefully.
     */
    public CompletableFuture<Void> stop(Duration timeout) {
        if (!running.get()) {
            return CompletableFuture.completedFuture(null);
        }

        stopping.set(true);
        log.info("Stopping reply router for {}, waiting for {} in-flight replies",
            replyQueue, inFlightCount.get());

        return CompletableFuture.runAsync(() -> {
            try {
                long deadline = System.currentTimeMillis() + timeout.toMillis();
                while (inFlightCount.get() > 0 && System.currentTimeMillis() < deadline) {
                    Thread.sleep(100);
                }

                if (inFlightCount.get() > 0) {
                    log.warn("Timeout waiting for {} in-flight replies", inFlightCount.get());
                }

                running.set(false);
                executor.shutdown();

                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }

                log.info("Reply router for {} stopped", replyQueue);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /**
     * Stop the router immediately.
     */
    public void stopNow() {
        stopping.set(true);
        running.set(false);
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    // --- Main Processing Loop ---

    private void runLoop() {
        log.debug("Reply router loop started for {}", replyQueue);

        try {
            if (useNotify) {
                runWithNotify();
            } else {
                runWithPolling();
            }
        } catch (Exception e) {
            if (!stopping.get()) {
                log.error("Reply router crashed for {}", replyQueue, e);
            }
        } finally {
            running.set(false);
            log.debug("Reply router loop ended for {}", replyQueue);
        }
    }

    private void runWithNotify() throws SQLException, InterruptedException {
        String channel = PGMQ_NOTIFY_CHANNEL + "_" + replyQueue;

        try (Connection listenConn = dataSource.getConnection()) {
            listenConn.setAutoCommit(true);

            try (var stmt = listenConn.createStatement()) {
                stmt.execute("LISTEN " + channel);
            }
            log.debug("Listening on channel {}", channel);

            PGConnection pgConn = listenConn.unwrap(PGConnection.class);

            while (running.get() && !stopping.get()) {
                drainQueue();
                if (stopping.get()) return;

                // Wait for notification or timeout
                pgConn.getNotifications((int) pollIntervalMs);
            }
        }
    }

    private void runWithPolling() throws InterruptedException {
        while (running.get() && !stopping.get()) {
            drainQueue();
            if (stopping.get()) return;

            Thread.sleep(pollIntervalMs);
        }
    }

    private void drainQueue() {
        while (running.get() && !stopping.get()) {
            int availableSlots = semaphore.availablePermits();
            if (availableSlots == 0) {
                waitForSlot();
                continue;
            }

            List<PgmqMessage> messages = pgmqClient.read(replyQueue, visibilityTimeout, availableSlots);

            if (messages.isEmpty()) {
                break;
            }

            for (PgmqMessage msg : messages) {
                executor.submit(() -> processMessage(msg));
            }
        }
    }

    private void waitForSlot() {
        try {
            semaphore.tryAcquire(100, TimeUnit.MILLISECONDS);
            if (semaphore.availablePermits() < concurrency) {
                semaphore.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void processMessage(PgmqMessage msg) {
        try {
            semaphore.acquire();
            inFlightCount.incrementAndGet();

            try {
                transactionTemplate.executeWithoutResult(status -> {
                    dispatchReply(msg);
                });
            } finally {
                inFlightCount.decrementAndGet();
                semaphore.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("Error processing reply message {}", msg.msgId(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private void dispatchReply(PgmqMessage msg) {
        long msgId = msg.msgId();
        Map<String, Object> message = msg.message();

        // Parse reply from message
        Reply reply = parseReply(message);

        if (reply.correlationId() == null) {
            log.warn("Reply {} has no correlation_id, discarding", msgId);
            pgmqClient.delete(replyQueue, msgId);
            return;
        }

        // Look up process by correlation_id (which is process_id)
        Optional<ProcessMetadata<?, ?>> processOpt = processRepo.getById(
            domain,
            reply.correlationId(),
            jdbcTemplate
        );

        if (processOpt.isEmpty()) {
            log.warn("Reply for unknown process {}, discarding", reply.correlationId());
            pgmqClient.delete(replyQueue, msgId);
            return;
        }

        ProcessMetadata<?, ?> process = processOpt.get();
        BaseProcessManager<?, ?> manager = managers.get(process.processType());

        if (manager == null) {
            log.error("No manager for process type {}, discarding", process.processType());
            pgmqClient.delete(replyQueue, msgId);
            return;
        }

        // Dispatch to manager
        manager.handleReply(reply, process, jdbcTemplate);

        // Delete message (atomically with process update since we're in a transaction)
        pgmqClient.delete(replyQueue, msgId);

        log.debug("Processed reply for process {} step {}",
            process.processId(), process.currentStep());
    }

    @SuppressWarnings("unchecked")
    private Reply parseReply(Map<String, Object> message) {
        UUID commandId = parseUUID(message.get("command_id"));
        UUID correlationId = parseUUID(message.get("correlation_id"));
        ReplyOutcome outcome = ReplyOutcome.valueOf((String) message.get("outcome"));
        Map<String, Object> resultData = (Map<String, Object>) message.get("result");
        String errorCode = (String) message.get("error_code");
        String errorMessage = (String) message.get("error_message");

        return new Reply(commandId, correlationId, outcome, resultData, errorCode, errorMessage);
    }

    private UUID parseUUID(Object value) {
        if (value == null) return null;
        if (value instanceof UUID) return (UUID) value;
        return UUID.fromString(value.toString());
    }
}
