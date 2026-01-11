package com.ivamare.commandbus.model;

import java.time.Instant;
import java.util.Map;

/**
 * A message from a PGMQ queue.
 *
 * @param msgId Unique message ID assigned by PGMQ
 * @param readCount Number of times this message has been read
 * @param enqueuedAt When the message was enqueued
 * @param visibilityTimeout When the message becomes visible again
 * @param message The message payload
 */
public record PgmqMessage(
    long msgId,
    int readCount,
    Instant enqueuedAt,
    Instant visibilityTimeout,
    Map<String, Object> message
) {
    /**
     * Creates a PgmqMessage with an immutable copy of the message data.
     */
    public PgmqMessage {
        if (message != null) {
            message = Map.copyOf(message);
        }
    }
}
