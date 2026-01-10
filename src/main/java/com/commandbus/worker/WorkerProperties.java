package com.commandbus.worker;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for workers.
 */
@ConfigurationProperties(prefix = "commandbus.worker")
public class WorkerProperties {

    /**
     * Visibility timeout in seconds (default: 30).
     */
    private int visibilityTimeout = 30;

    /**
     * Poll interval in milliseconds (default: 1000).
     */
    private int pollIntervalMs = 1000;

    /**
     * Concurrent handlers per worker (default: 4).
     */
    private int concurrency = 4;

    /**
     * Use PostgreSQL NOTIFY for instant wake-up (default: true).
     */
    private boolean useNotify = true;

    /**
     * Get the visibility timeout in seconds.
     *
     * @return visibility timeout
     */
    public int getVisibilityTimeout() {
        return visibilityTimeout;
    }

    /**
     * Set the visibility timeout in seconds.
     *
     * @param visibilityTimeout visibility timeout
     */
    public void setVisibilityTimeout(int visibilityTimeout) {
        this.visibilityTimeout = visibilityTimeout;
    }

    /**
     * Get the poll interval in milliseconds.
     *
     * @return poll interval
     */
    public int getPollIntervalMs() {
        return pollIntervalMs;
    }

    /**
     * Set the poll interval in milliseconds.
     *
     * @param pollIntervalMs poll interval
     */
    public void setPollIntervalMs(int pollIntervalMs) {
        this.pollIntervalMs = pollIntervalMs;
    }

    /**
     * Get the concurrency level.
     *
     * @return concurrency
     */
    public int getConcurrency() {
        return concurrency;
    }

    /**
     * Set the concurrency level.
     *
     * @param concurrency concurrency
     */
    public void setConcurrency(int concurrency) {
        this.concurrency = concurrency;
    }

    /**
     * Check if NOTIFY is enabled.
     *
     * @return true if NOTIFY is enabled
     */
    public boolean isUseNotify() {
        return useNotify;
    }

    /**
     * Set whether to use NOTIFY.
     *
     * @param useNotify whether to use NOTIFY
     */
    public void setUseNotify(boolean useNotify) {
        this.useNotify = useNotify;
    }
}
