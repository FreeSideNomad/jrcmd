package com.ivamare.commandbus.worker;

import com.ivamare.commandbus.handler.HandlerRegistry;
import com.ivamare.commandbus.policy.RetryPolicy;
import com.ivamare.commandbus.worker.impl.DefaultWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Builder for creating Worker instances.
 */
public class WorkerBuilder {

    private JdbcTemplate jdbcTemplate;
    private DataSource dataSource;
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
     * Set the DataSource for LISTEN connection.
     * Required when useNotify is true.
     *
     * @param dataSource The data source
     * @return this builder
     */
    public WorkerBuilder dataSource(DataSource dataSource) {
        this.dataSource = dataSource;
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
        if (useNotify && dataSource == null) {
            throw new IllegalStateException("dataSource is required when useNotify is enabled");
        }

        if (objectMapper == null) {
            objectMapper = new ObjectMapper();
        }

        if (retryPolicy == null) {
            retryPolicy = RetryPolicy.defaultPolicy();
        }

        return new DefaultWorker(
            jdbcTemplate,
            dataSource,
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
