package com.commandbus.worker;

import com.commandbus.handler.HandlerRegistry;
import com.commandbus.policy.RetryPolicy;
import com.commandbus.worker.impl.DefaultWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Builder for creating Worker instances.
 */
public class WorkerBuilder {

    private JdbcTemplate jdbcTemplate;
    private ObjectMapper objectMapper;
    private String domain;
    private HandlerRegistry handlerRegistry;
    private int visibilityTimeout = 30;
    private int pollIntervalMs = 1000;
    private int concurrency = 1;
    private boolean useNotify = true;
    private RetryPolicy retryPolicy;

    /**
     * Set the JdbcTemplate for database operations.
     *
     * @param jdbcTemplate The JDBC template
     * @return this builder
     */
    public WorkerBuilder jdbcTemplate(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        return this;
    }

    /**
     * Set the ObjectMapper for JSON serialization.
     *
     * @param objectMapper The object mapper
     * @return this builder
     */
    public WorkerBuilder objectMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        return this;
    }

    /**
     * Set the domain to process commands for.
     *
     * @param domain The domain name
     * @return this builder
     */
    public WorkerBuilder domain(String domain) {
        this.domain = domain;
        return this;
    }

    /**
     * Set the handler registry.
     *
     * @param handlerRegistry The handler registry
     * @return this builder
     */
    public WorkerBuilder handlerRegistry(HandlerRegistry handlerRegistry) {
        this.handlerRegistry = handlerRegistry;
        return this;
    }

    /**
     * Set the visibility timeout in seconds (default: 30).
     *
     * @param seconds Visibility timeout in seconds
     * @return this builder
     */
    public WorkerBuilder visibilityTimeout(int seconds) {
        this.visibilityTimeout = seconds;
        return this;
    }

    /**
     * Set the poll interval in milliseconds (default: 1000).
     *
     * @param ms Poll interval in milliseconds
     * @return this builder
     */
    public WorkerBuilder pollIntervalMs(int ms) {
        this.pollIntervalMs = ms;
        return this;
    }

    /**
     * Set the concurrency level (default: 1).
     *
     * @param concurrency Number of concurrent handlers
     * @return this builder
     */
    public WorkerBuilder concurrency(int concurrency) {
        this.concurrency = concurrency;
        return this;
    }

    /**
     * Enable/disable PostgreSQL NOTIFY for instant wake-up (default: true).
     *
     * @param useNotify Whether to use NOTIFY
     * @return this builder
     */
    public WorkerBuilder useNotify(boolean useNotify) {
        this.useNotify = useNotify;
        return this;
    }

    /**
     * Set the retry policy (default: 3 attempts with backoff [10, 60, 300]).
     *
     * @param retryPolicy The retry policy
     * @return this builder
     */
    public WorkerBuilder retryPolicy(RetryPolicy retryPolicy) {
        this.retryPolicy = retryPolicy;
        return this;
    }

    /**
     * Build the worker instance.
     *
     * @return configured Worker
     * @throws IllegalStateException if required properties not set
     */
    public Worker build() {
        if (jdbcTemplate == null) {
            throw new IllegalStateException("jdbcTemplate is required");
        }
        if (domain == null) {
            throw new IllegalStateException("domain is required");
        }
        if (handlerRegistry == null) {
            throw new IllegalStateException("handlerRegistry is required");
        }

        if (objectMapper == null) {
            objectMapper = new ObjectMapper();
        }

        if (retryPolicy == null) {
            retryPolicy = RetryPolicy.defaultPolicy();
        }

        return new DefaultWorker(
            jdbcTemplate,
            objectMapper,
            domain,
            handlerRegistry,
            visibilityTimeout,
            pollIntervalMs,
            concurrency,
            useNotify,
            retryPolicy
        );
    }
}
