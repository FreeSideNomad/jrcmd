package com.ivamare.commandbus;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 *     resilience:
 *       initial-backoff-ms: 1000
 *       max-backoff-ms: 30000
 *       backoff-multiplier: 2.0
 *       error-threshold: 5
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

    /**
     * Feature toggles for worker capabilities.
     */
    private FeaturesProperties features = new FeaturesProperties();

    /**
     * Per-domain configuration for concurrency and enabling/disabling.
     */
    private Map<String, DomainConfig> domains = new HashMap<>();

    /**
     * Command step configuration for ProcessStepManager.commandStep().
     */
    private Map<String, CommandStepConfig> commandSteps = new HashMap<>();

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

    public FeaturesProperties getFeatures() {
        return features;
    }

    public void setFeatures(FeaturesProperties features) {
        this.features = features;
    }

    public Map<String, DomainConfig> getDomains() {
        return domains;
    }

    public void setDomains(Map<String, DomainConfig> domains) {
        this.domains = domains;
    }

    public Map<String, CommandStepConfig> getCommandSteps() {
        return commandSteps;
    }

    public void setCommandSteps(Map<String, CommandStepConfig> commandSteps) {
        this.commandSteps = commandSteps;
    }

    /**
     * Get domain configuration, returning defaults if not explicitly configured.
     *
     * @param domain domain name
     * @return domain configuration (never null)
     */
    public DomainConfig getDomainConfig(String domain) {
        return domains.getOrDefault(domain, new DomainConfig());
    }

    /**
     * Get command step configuration.
     *
     * @param stepName step name
     * @return command step configuration, or null if not configured
     */
    public CommandStepConfig getCommandStepConfig(String stepName) {
        return commandSteps.get(stepName);
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

        /**
         * Resilience configuration for database error recovery.
         */
        private ResilienceProperties resilience = new ResilienceProperties();

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

        public ResilienceProperties getResilience() {
            return resilience;
        }

        public void setResilience(ResilienceProperties resilience) {
            this.resilience = resilience;
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

        /**
         * Resilience configuration for database error recovery.
         */
        private ResilienceProperties resilience = new ResilienceProperties();

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

        public ResilienceProperties getResilience() {
            return resilience;
        }

        public void setResilience(ResilienceProperties resilience) {
            this.resilience = resilience;
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

    /**
     * Resilience configuration for database error recovery.
     *
     * <p>These settings control exponential backoff behavior when database
     * errors occur in workers and process managers. The backoff formula is:
     * <pre>
     * delay = min(initialBackoffMs * (backoffMultiplier ^ errorCount), maxBackoffMs)
     * </pre>
     *
     * <p>With default settings (1s initial, 2x multiplier, 30s max):
     * <ul>
     *   <li>1st error: 1s delay</li>
     *   <li>2nd error: 2s delay</li>
     *   <li>3rd error: 4s delay</li>
     *   <li>4th error: 8s delay</li>
     *   <li>5th+ error: 16s, 30s (capped)</li>
     * </ul>
     */
    public static class ResilienceProperties {

        /**
         * Initial backoff duration in milliseconds after first database error.
         * Default: 1000ms (1 second)
         */
        private long initialBackoffMs = 1000;

        /**
         * Maximum backoff duration in milliseconds.
         * The exponential backoff is capped at this value.
         * Default: 30000ms (30 seconds)
         */
        private long maxBackoffMs = 30000;

        /**
         * Multiplier for exponential backoff.
         * Each consecutive error multiplies the delay by this factor.
         * Default: 2.0 (doubling)
         */
        private double backoffMultiplier = 2.0;

        /**
         * Number of consecutive errors before logging at ERROR level.
         * Below this threshold, errors are logged at WARN level.
         * Default: 5
         */
        private int errorThreshold = 5;

        // Getters and setters

        public long getInitialBackoffMs() {
            return initialBackoffMs;
        }

        public void setInitialBackoffMs(long initialBackoffMs) {
            this.initialBackoffMs = initialBackoffMs;
        }

        public long getMaxBackoffMs() {
            return maxBackoffMs;
        }

        public void setMaxBackoffMs(long maxBackoffMs) {
            this.maxBackoffMs = maxBackoffMs;
        }

        public double getBackoffMultiplier() {
            return backoffMultiplier;
        }

        public void setBackoffMultiplier(double backoffMultiplier) {
            this.backoffMultiplier = backoffMultiplier;
        }

        public int getErrorThreshold() {
            return errorThreshold;
        }

        public void setErrorThreshold(int errorThreshold) {
            this.errorThreshold = errorThreshold;
        }

        /**
         * Calculate the backoff delay for a given error count.
         *
         * @param errorCount the number of consecutive errors (1-based)
         * @return the delay in milliseconds
         */
        public long calculateBackoff(int errorCount) {
            if (errorCount <= 0) {
                return initialBackoffMs;
            }
            double delay = initialBackoffMs * Math.pow(backoffMultiplier, errorCount - 1);
            // Add jitter: +/- 10%
            double jitter = delay * 0.1 * (Math.random() * 2 - 1);
            return Math.min((long) (delay + jitter), maxBackoffMs);
        }
    }

    /**
     * Feature toggles for worker capabilities.
     *
     * <p>Controls what features are enabled for this worker instance.
     * Use to create dedicated workers (e.g., rate-limited command handlers only)
     * or multi-purpose workers (all features enabled).
     *
     * <p>Example configuration:
     * <pre>
     * commandbus:
     *   features:
     *     command-handlers: true
     *     process-execution: false
     *     reply-processing: false
     * </pre>
     */
    public static class FeaturesProperties {

        /**
         * Enable/disable command handler processing.
         */
        private boolean commandHandlers = true;

        /**
         * Enable/disable ProcessStepManager execution.
         */
        private boolean processExecution = true;

        /**
         * Enable/disable reply processing for command steps.
         */
        private boolean replyProcessing = true;

        public boolean isCommandHandlers() {
            return commandHandlers;
        }

        public void setCommandHandlers(boolean commandHandlers) {
            this.commandHandlers = commandHandlers;
        }

        public boolean isProcessExecution() {
            return processExecution;
        }

        public void setProcessExecution(boolean processExecution) {
            this.processExecution = processExecution;
        }

        public boolean isReplyProcessing() {
            return replyProcessing;
        }

        public void setReplyProcessing(boolean replyProcessing) {
            this.replyProcessing = replyProcessing;
        }
    }

    /**
     * Per-domain configuration.
     *
     * <p>Controls whether a domain is enabled and its concurrency limit.
     * The concurrency limit controls how many messages are processed
     * concurrently by this worker instance for this domain.
     *
     * <p>Total concurrent API calls = replicas × concurrent-messages
     *
     * <p>Example configuration:
     * <pre>
     * commandbus:
     *   domains:
     *     fx:
     *       enabled: true
     *       concurrent-messages: 2
     *     payments:
     *       enabled: true
     *       concurrent-messages: 5
     * </pre>
     */
    public static class DomainConfig {

        /**
         * Enable/disable processing for this domain.
         */
        private boolean enabled = true;

        /**
         * Maximum concurrent messages to process for this domain.
         * Each virtual thread blocks on I/O, so this directly controls
         * how many concurrent API calls can be in-flight.
         */
        private int concurrentMessages = 5;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getConcurrentMessages() {
            return concurrentMessages;
        }

        public void setConcurrentMessages(int concurrentMessages) {
            this.concurrentMessages = concurrentMessages;
        }
    }

    /**
     * Command step configuration for ProcessStepManager.commandStep().
     *
     * <p>Maps step names to target domains and command types.
     *
     * <p>Example configuration:
     * <pre>
     * commandbus:
     *   command-steps:
     *     bookFx:
     *       domain: fx
     *       command-type: BookFx
     *       timeout: 30s
     *     submitPayment:
     *       domain: payments
     *       command-type: SubmitPayment
     *       timeout: 30s
     * </pre>
     */
    public static class CommandStepConfig {

        /**
         * Target domain for this command step.
         */
        private String domain;

        /**
         * Command type for handler routing.
         */
        private String commandType;

        /**
         * Timeout for command step execution.
         */
        private Duration timeout = Duration.ofSeconds(30);

        /**
         * Reply queue name. If not specified, defaults to {process-domain}__replies.
         */
        private String replyQueue;

        public String getDomain() {
            return domain;
        }

        public void setDomain(String domain) {
            this.domain = domain;
        }

        public String getCommandType() {
            return commandType;
        }

        public void setCommandType(String commandType) {
            this.commandType = commandType;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public String getReplyQueue() {
            return replyQueue;
        }

        public void setReplyQueue(String replyQueue) {
            this.replyQueue = replyQueue;
        }
    }
}
