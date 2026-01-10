package com.commandbus.pgmq;

/**
 * Queue naming utilities.
 *
 * <p>Provides standardized naming conventions for PGMQ queues used by the
 * command bus. Queue names follow the pattern: {domain}__{suffix}
 */
public final class QueueNames {

    private QueueNames() {
        // Utility class - prevent instantiation
    }

    /** Suffix for command queues */
    public static final String COMMANDS_SUFFIX = "commands";

    /** Suffix for reply queues */
    public static final String REPLIES_SUFFIX = "replies";

    /** Prefix for NOTIFY channel names */
    public static final String NOTIFY_PREFIX = "pgmq_notify";

    /** Separator between domain and suffix */
    public static final String SEPARATOR = "__";

    /**
     * Create command queue name from domain.
     *
     * @param domain The domain name
     * @return Queue name in format {domain}__commands
     */
    public static String commandQueue(String domain) {
        return domain + SEPARATOR + COMMANDS_SUFFIX;
    }

    /**
     * Create reply queue name from domain.
     *
     * @param domain The domain name
     * @return Queue name in format {domain}__replies
     */
    public static String replyQueue(String domain) {
        return domain + SEPARATOR + REPLIES_SUFFIX;
    }

    /**
     * Create notify channel name from queue name.
     *
     * @param queueName The queue name
     * @return Channel name in format pgmq_notify_{queueName}
     */
    public static String notifyChannel(String queueName) {
        return NOTIFY_PREFIX + "_" + queueName;
    }

    /**
     * Extract domain from queue name.
     *
     * @param queueName The queue name
     * @return The domain part, or the original queue name if no separator found
     */
    public static String extractDomain(String queueName) {
        int separatorIndex = queueName.indexOf(SEPARATOR);
        return separatorIndex > 0 ? queueName.substring(0, separatorIndex) : queueName;
    }

    /**
     * Get the archive table name for a queue.
     *
     * @param queueName The queue name
     * @return Archive table name in format pgmq.a_{queueName}
     */
    public static String archiveTable(String queueName) {
        return "pgmq.a_" + queueName;
    }
}
