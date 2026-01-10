package com.commandbus;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Configuration properties for Command Bus.
 *
 * <p>Example configuration:
 * <pre>
 * commandbus:
 *   enabled: true
 *   default-max-attempts: 3
 *   backoff-schedule: [10, 60, 300]
 *   worker:
 *     visibility-timeout: 30
 *     poll-interval-ms: 1000
 *     concurrency: 4
 *     use-notify: true
 *   batch:
 *     default-chunk-size: 1000
 * </pre>
 */
@ConfigurationProperties(prefix = "commandbus")
public class CommandBusProperties {

    /**
     * Enable/disable Command Bus auto-configuration.
     */
    private boolean enabled = true;

    /**
     * Default maximum retry attempts for commands.
     */
    private int defaultMaxAttempts = 3;

    /**
     * Backoff schedule in seconds for each retry.
     */
    private List<Integer> backoffSchedule = List.of(10, 60, 300);

    /**
     * Worker-specific configuration.
     */
    private WorkerProperties worker = new WorkerProperties();

    /**
     * Batch-specific configuration.
     */
    private BatchProperties batch = new BatchProperties();

    // Getters and setters

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getDefaultMaxAttempts() {
        return defaultMaxAttempts;
    }

    public void setDefaultMaxAttempts(int defaultMaxAttempts) {
        this.defaultMaxAttempts = defaultMaxAttempts;
    }

    public List<Integer> getBackoffSchedule() {
        return backoffSchedule;
    }

    public void setBackoffSchedule(List<Integer> backoffSchedule) {
        this.backoffSchedule = backoffSchedule;
    }

    public WorkerProperties getWorker() {
        return worker;
    }

    public void setWorker(WorkerProperties worker) {
        this.worker = worker;
    }

    public BatchProperties getBatch() {
        return batch;
    }

    public void setBatch(BatchProperties batch) {
        this.batch = batch;
    }

    /**
     * Worker configuration properties.
     */
    public static class WorkerProperties {

        /**
         * Auto-start workers on application ready.
         */
        private boolean autoStart = false;

        /**
         * Visibility timeout in seconds.
         */
        private int visibilityTimeout = 30;

        /**
         * Poll interval in milliseconds.
         */
        private int pollIntervalMs = 1000;

        /**
         * Number of concurrent handlers.
         */
        private int concurrency = 4;

        /**
         * Use PostgreSQL NOTIFY for instant wake-up.
         */
        private boolean useNotify = true;

        // Getters and setters

        public boolean isAutoStart() {
            return autoStart;
        }

        public void setAutoStart(boolean autoStart) {
            this.autoStart = autoStart;
        }

        public int getVisibilityTimeout() {
            return visibilityTimeout;
        }

        public void setVisibilityTimeout(int visibilityTimeout) {
            this.visibilityTimeout = visibilityTimeout;
        }

        public int getPollIntervalMs() {
            return pollIntervalMs;
        }

        public void setPollIntervalMs(int pollIntervalMs) {
            this.pollIntervalMs = pollIntervalMs;
        }

        public int getConcurrency() {
            return concurrency;
        }

        public void setConcurrency(int concurrency) {
            this.concurrency = concurrency;
        }

        public boolean isUseNotify() {
            return useNotify;
        }

        public void setUseNotify(boolean useNotify) {
            this.useNotify = useNotify;
        }
    }

    /**
     * Batch configuration properties.
     */
    public static class BatchProperties {

        /**
         * Default chunk size for batch operations.
         */
        private int defaultChunkSize = 1000;

        public int getDefaultChunkSize() {
            return defaultChunkSize;
        }

        public void setDefaultChunkSize(int defaultChunkSize) {
            this.defaultChunkSize = defaultChunkSize;
        }
    }
}
