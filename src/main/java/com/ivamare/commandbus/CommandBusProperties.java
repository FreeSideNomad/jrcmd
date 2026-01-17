package com.ivamare.commandbus;

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

    /**
     * Process-specific configuration (for BaseProcessManager with PGMQ).
     */
    private ProcessProperties process = new ProcessProperties();

    /**
     * Process step configuration (for ProcessStepManager with deterministic replay).
     */
    private ProcessStepProperties processStep = new ProcessStepProperties();

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

    public ProcessProperties getProcess() {
        return process;
    }

    public void setProcess(ProcessProperties process) {
        this.process = process;
    }

    public ProcessStepProperties getProcessStep() {
        return processStep;
    }

    public void setProcessStep(ProcessStepProperties processStep) {
        this.processStep = processStep;
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

    /**
     * Process configuration properties.
     */
    public static class ProcessProperties {

        /**
         * Enable/disable process manager.
         */
        private boolean enabled = false;

        /**
         * Domain for process management.
         */
        private String domain;

        /**
         * Reply queue name for process replies.
         */
        private String replyQueue;

        /**
         * Visibility timeout in seconds for reply messages.
         */
        private int visibilityTimeout = 30;

        /**
         * Number of concurrent reply processors.
         */
        private int concurrency = 10;

        /**
         * Poll interval in milliseconds.
         */
        private long pollIntervalMs = 1000;

        /**
         * Use PostgreSQL NOTIFY for instant wake-up.
         */
        private boolean useNotify = true;

        /**
         * Auto-start reply router on application ready.
         */
        private boolean autoStart = false;

        /**
         * Archive messages instead of deleting them after processing.
         * Useful for debugging to trace which messages were processed.
         */
        private boolean archiveMessages = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getDomain() {
            return domain;
        }

        public void setDomain(String domain) {
            this.domain = domain;
        }

        public String getReplyQueue() {
            return replyQueue;
        }

        public void setReplyQueue(String replyQueue) {
            this.replyQueue = replyQueue;
        }

        public int getVisibilityTimeout() {
            return visibilityTimeout;
        }

        public void setVisibilityTimeout(int visibilityTimeout) {
            this.visibilityTimeout = visibilityTimeout;
        }

        public int getConcurrency() {
            return concurrency;
        }

        public void setConcurrency(int concurrency) {
            this.concurrency = concurrency;
        }

        public long getPollIntervalMs() {
            return pollIntervalMs;
        }

        public void setPollIntervalMs(long pollIntervalMs) {
            this.pollIntervalMs = pollIntervalMs;
        }

        public boolean isUseNotify() {
            return useNotify;
        }

        public void setUseNotify(boolean useNotify) {
            this.useNotify = useNotify;
        }

        public boolean isAutoStart() {
            return autoStart;
        }

        public void setAutoStart(boolean autoStart) {
            this.autoStart = autoStart;
        }

        public boolean isArchiveMessages() {
            return archiveMessages;
        }

        public void setArchiveMessages(boolean archiveMessages) {
            this.archiveMessages = archiveMessages;
        }
    }

    /**
     * ProcessStepManager configuration properties.
     *
     * <p>Example configuration:
     * <pre>
     * commandbus:
     *   process-step:
     *     enabled: true
     *     batch-size: 100
     *     processing-timeout-seconds: 60
     *     poll-interval-ms: 1000
     *     retry-poll-interval-ms: 5000
     *     timeout-check-interval-ms: 60000
     *     deadline-check-interval-ms: 60000
     *     auto-start: true
     * </pre>
     */
    public static class ProcessStepProperties {

        /**
         * Enable/disable ProcessStepWorker.
         */
        private boolean enabled = false;

        /**
         * Number of processes to claim per poll cycle.
         * Each worker claims this many processes atomically using FOR UPDATE SKIP LOCKED.
         * Should be proportional to expected throughput and virtual thread capacity.
         */
        private int batchSize = 100;

        /**
         * Processing timeout in seconds.
         * If a worker crashes, processes claimed longer than this timeout
         * are automatically released for other workers to pick up.
         */
        private int processingTimeoutSeconds = 60;

        /**
         * Poll interval in milliseconds for pending processes.
         */
        private long pollIntervalMs = 1000;

        /**
         * Poll interval in milliseconds for retry-due processes.
         */
        private long retryPollIntervalMs = 5000;

        /**
         * Interval in milliseconds for checking wait timeouts.
         */
        private long timeoutCheckIntervalMs = 60000;

        /**
         * Interval in milliseconds for checking process deadlines.
         */
        private long deadlineCheckIntervalMs = 60000;

        /**
         * Auto-start ProcessStepWorker on application ready.
         */
        private boolean autoStart = false;

        // Getters and setters

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public int getProcessingTimeoutSeconds() {
            return processingTimeoutSeconds;
        }

        public void setProcessingTimeoutSeconds(int processingTimeoutSeconds) {
            this.processingTimeoutSeconds = processingTimeoutSeconds;
        }

        public long getPollIntervalMs() {
            return pollIntervalMs;
        }

        public void setPollIntervalMs(long pollIntervalMs) {
            this.pollIntervalMs = pollIntervalMs;
        }

        public long getRetryPollIntervalMs() {
            return retryPollIntervalMs;
        }

        public void setRetryPollIntervalMs(long retryPollIntervalMs) {
            this.retryPollIntervalMs = retryPollIntervalMs;
        }

        public long getTimeoutCheckIntervalMs() {
            return timeoutCheckIntervalMs;
        }

        public void setTimeoutCheckIntervalMs(long timeoutCheckIntervalMs) {
            this.timeoutCheckIntervalMs = timeoutCheckIntervalMs;
        }

        public long getDeadlineCheckIntervalMs() {
            return deadlineCheckIntervalMs;
        }

        public void setDeadlineCheckIntervalMs(long deadlineCheckIntervalMs) {
            this.deadlineCheckIntervalMs = deadlineCheckIntervalMs;
        }

        public boolean isAutoStart() {
            return autoStart;
        }

        public void setAutoStart(boolean autoStart) {
            this.autoStart = autoStart;
        }
    }
}
