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
) {}
