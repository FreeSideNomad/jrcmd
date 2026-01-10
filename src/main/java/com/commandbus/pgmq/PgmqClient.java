package com.commandbus.pgmq;

import com.commandbus.model.PgmqMessage;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Client for interacting with PGMQ queues.
 *
 * <p>Wraps PGMQ SQL functions for queue operations. All methods support
 * both standalone execution and participation in existing transactions.
 */
public interface PgmqClient {

    /**
     * Create a queue if it doesn't exist.
     *
     * @param queueName Name of the queue to create
     */
    void createQueue(String queueName);

    /**
     * Send a message to a queue.
     *
     * @param queueName Name of the queue
     * @param message Message payload (will be JSON serialized)
     * @return Message ID assigned by PGMQ
     */
    long send(String queueName, Map<String, Object> message);

    /**
     * Send a message to a queue with delay.
     *
     * @param queueName Name of the queue
     * @param message Message payload (will be JSON serialized)
     * @param delaySeconds Delay in seconds before message becomes visible
     * @return Message ID assigned by PGMQ
     */
    long send(String queueName, Map<String, Object> message, int delaySeconds);

    /**
     * Send multiple messages to a queue in a single operation.
     *
     * <p>Uses PGMQ's native send_batch() for optimal performance.
     * Does NOT send NOTIFY - caller is responsible for notification.
     *
     * @param queueName Name of the queue
     * @param messages List of message payloads
     * @return List of message IDs assigned by PGMQ
     */
    List<Long> sendBatch(String queueName, List<Map<String, Object>> messages);

    /**
     * Send multiple messages with delay.
     *
     * @param queueName Name of the queue
     * @param messages List of message payloads
     * @param delaySeconds Delay in seconds
     * @return List of message IDs
     */
    List<Long> sendBatch(String queueName, List<Map<String, Object>> messages, int delaySeconds);

    /**
     * Send a NOTIFY signal for a queue.
     *
     * <p>Used after batch operations to wake up workers.
     *
     * @param queueName Name of the queue
     */
    void notify(String queueName);

    /**
     * Read messages from a queue.
     *
     * @param queueName Name of the queue
     * @param visibilityTimeoutSeconds Seconds before message becomes visible again
     * @param batchSize Maximum number of messages to read
     * @return List of messages (may be empty)
     */
    List<PgmqMessage> read(String queueName, int visibilityTimeoutSeconds, int batchSize);

    /**
     * Read a single message from a queue.
     *
     * @param queueName Name of the queue
     * @param visibilityTimeoutSeconds Visibility timeout in seconds
     * @return Optional message (empty if queue is empty)
     */
    default Optional<PgmqMessage> readOne(String queueName, int visibilityTimeoutSeconds) {
        var messages = read(queueName, visibilityTimeoutSeconds, 1);
        return messages.isEmpty() ? Optional.empty() : Optional.of(messages.get(0));
    }

    /**
     * Delete a message from a queue.
     *
     * @param queueName Name of the queue
     * @param msgId Message ID to delete
     * @return true if message was deleted, false if not found
     */
    boolean delete(String queueName, long msgId);

    /**
     * Archive a message (move to archive table).
     *
     * @param queueName Name of the queue
     * @param msgId Message ID to archive
     * @return true if message was archived
     */
    boolean archive(String queueName, long msgId);

    /**
     * Set visibility timeout for a message.
     *
     * <p>Used for extending visibility timeout for long-running handlers
     * or implementing backoff delays.
     *
     * @param queueName Name of the queue
     * @param msgId Message ID
     * @param visibilityTimeoutSeconds New visibility timeout in seconds from now
     * @return true if timeout was set
     */
    boolean setVisibilityTimeout(String queueName, long msgId, int visibilityTimeoutSeconds);

    /**
     * Get message from archive by command ID.
     *
     * @param queueName Name of the queue
     * @param commandId Command ID to search for
     * @return Optional archived message
     */
    Optional<PgmqMessage> getFromArchive(String queueName, String commandId);
}
