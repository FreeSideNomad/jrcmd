package com.ivamare.commandbus.e2e.dto;

import java.time.Instant;

/**
 * Queue statistics.
 */
public record QueueStats(
    String queueName,
    long queueDepth,
    long archiveSize,
    long messagesPerMinute,
    Instant oldestMessageAt
) {
    /**
     * Returns the queue type based on naming convention.
     */
    public String queueType() {
        if (queueName.endsWith("__commands")) {
            return "commands";
        } else if (queueName.endsWith("__process_replies")) {
            return "process_replies";
        } else if (queueName.endsWith("__replies")) {
            return "replies";
        } else {
            return "other";
        }
    }
}
