package com.ivamare.commandbus.worker;

import com.ivamare.commandbus.handler.HandlerRegistry;
import com.ivamare.commandbus.policy.RetryPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("WorkerBuilder")
class WorkerBuilderTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private DataSource dataSource;

    @Mock
    private HandlerRegistry handlerRegistry;

    @Mock
    private ObjectMapper objectMapper;

    private WorkerBuilder builder;

    @BeforeEach
    void setUp() {
        builder = Worker.builder();
    }

    @Test
    @DisplayName("should build worker with required parameters")
    void shouldBuildWorkerWithRequiredParameters() {
        Worker worker = builder
            .jdbcTemplate(jdbcTemplate)
            .dataSource(dataSource)
            .domain("payments")
            .handlerRegistry(handlerRegistry)
            .build();

        assertNotNull(worker);
        assertEquals("payments", worker.domain());
    }

    @Test
    @DisplayName("should throw when jdbcTemplate is missing")
    void shouldThrowWhenJdbcTemplateIsMissing() {
        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
            builder
                .domain("payments")
                .handlerRegistry(handlerRegistry)
                .build()
        );

        assertEquals("jdbcTemplate is required", exception.getMessage());
    }

    @Test
    @DisplayName("should throw when domain is missing")
    void shouldThrowWhenDomainIsMissing() {
        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
            builder
                .jdbcTemplate(jdbcTemplate)
                .handlerRegistry(handlerRegistry)
                .build()
        );

        assertEquals("domain is required", exception.getMessage());
    }

    @Test
    @DisplayName("should throw when handlerRegistry is missing")
    void shouldThrowWhenHandlerRegistryIsMissing() {
        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
            builder
                .jdbcTemplate(jdbcTemplate)
                .domain("payments")
                .build()
        );

        assertEquals("handlerRegistry is required", exception.getMessage());
    }

    @Test
    @DisplayName("should set visibility timeout")
    void shouldSetVisibilityTimeout() {
        Worker worker = builder
            .jdbcTemplate(jdbcTemplate)
            .dataSource(dataSource)
            .domain("payments")
            .handlerRegistry(handlerRegistry)
            .visibilityTimeout(60)
            .build();

        assertNotNull(worker);
    }

    @Test
    @DisplayName("should set poll interval")
    void shouldSetPollInterval() {
        Worker worker = builder
            .jdbcTemplate(jdbcTemplate)
            .dataSource(dataSource)
            .domain("payments")
            .handlerRegistry(handlerRegistry)
            .pollIntervalMs(2000)
            .build();

        assertNotNull(worker);
    }

    @Test
    @DisplayName("should set concurrency")
    void shouldSetConcurrency() {
        Worker worker = builder
            .jdbcTemplate(jdbcTemplate)
            .dataSource(dataSource)
            .domain("payments")
            .handlerRegistry(handlerRegistry)
            .concurrency(8)
            .build();

        assertNotNull(worker);
    }

    @Test
    @DisplayName("should set useNotify")
    void shouldSetUseNotify() {
        Worker worker = builder
            .jdbcTemplate(jdbcTemplate)
            .domain("payments")
            .handlerRegistry(handlerRegistry)
            .useNotify(false)
            .build();

        assertNotNull(worker);
    }

    @Test
    @DisplayName("should set custom retry policy")
    void shouldSetCustomRetryPolicy() {
        RetryPolicy customPolicy = RetryPolicy.noRetry();

        Worker worker = builder
            .jdbcTemplate(jdbcTemplate)
            .dataSource(dataSource)
            .domain("payments")
            .handlerRegistry(handlerRegistry)
            .retryPolicy(customPolicy)
            .build();

        assertNotNull(worker);
    }

    @Test
    @DisplayName("should set object mapper")
    void shouldSetObjectMapper() {
        Worker worker = builder
            .jdbcTemplate(jdbcTemplate)
            .dataSource(dataSource)
            .domain("payments")
            .handlerRegistry(handlerRegistry)
            .objectMapper(objectMapper)
            .build();

        assertNotNull(worker);
    }

    @Test
    @DisplayName("should use default retry policy when not set")
    void shouldUseDefaultRetryPolicyWhenNotSet() {
        Worker worker = builder
            .jdbcTemplate(jdbcTemplate)
            .dataSource(dataSource)
            .domain("payments")
            .handlerRegistry(handlerRegistry)
            .build();

        assertNotNull(worker);
    }

    @Test
    @DisplayName("should allow fluent chaining")
    void shouldAllowFluentChaining() {
        Worker worker = builder
            .jdbcTemplate(jdbcTemplate)
            .dataSource(dataSource)
            .objectMapper(objectMapper)
            .domain("payments")
            .handlerRegistry(handlerRegistry)
            .visibilityTimeout(45)
            .pollIntervalMs(500)
            .concurrency(4)
            .useNotify(true)
            .retryPolicy(RetryPolicy.defaultPolicy())
            .build();

        assertNotNull(worker);
        assertEquals("payments", worker.domain());
    }

    @Test
    @DisplayName("should throw when dataSource is missing and useNotify is true")
    void shouldThrowWhenDataSourceMissingAndUseNotifyTrue() {
        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
            builder
                .jdbcTemplate(jdbcTemplate)
                .domain("payments")
                .handlerRegistry(handlerRegistry)
                .useNotify(true)
                .build()
        );

        assertEquals("dataSource is required when useNotify is enabled", exception.getMessage());
    }

    @Test
    @DisplayName("should allow building without dataSource when useNotify is false")
    void shouldAllowBuildingWithoutDataSourceWhenUseNotifyFalse() {
        Worker worker = builder
            .jdbcTemplate(jdbcTemplate)
            .domain("payments")
            .handlerRegistry(handlerRegistry)
            .useNotify(false)
            .build();

        assertNotNull(worker);
    }
}
