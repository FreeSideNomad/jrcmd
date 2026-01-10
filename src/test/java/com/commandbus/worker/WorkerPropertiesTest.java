package com.commandbus.worker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("WorkerProperties")
class WorkerPropertiesTest {

    private WorkerProperties properties;

    @BeforeEach
    void setUp() {
        properties = new WorkerProperties();
    }

    @Test
    @DisplayName("should have default visibility timeout of 30")
    void shouldHaveDefaultVisibilityTimeout() {
        assertEquals(30, properties.getVisibilityTimeout());
    }

    @Test
    @DisplayName("should have default poll interval of 1000ms")
    void shouldHaveDefaultPollInterval() {
        assertEquals(1000, properties.getPollIntervalMs());
    }

    @Test
    @DisplayName("should have default concurrency of 4")
    void shouldHaveDefaultConcurrency() {
        assertEquals(4, properties.getConcurrency());
    }

    @Test
    @DisplayName("should have useNotify enabled by default")
    void shouldHaveUseNotifyEnabledByDefault() {
        assertTrue(properties.isUseNotify());
    }

    @Test
    @DisplayName("should set visibility timeout")
    void shouldSetVisibilityTimeout() {
        properties.setVisibilityTimeout(60);

        assertEquals(60, properties.getVisibilityTimeout());
    }

    @Test
    @DisplayName("should set poll interval")
    void shouldSetPollInterval() {
        properties.setPollIntervalMs(2000);

        assertEquals(2000, properties.getPollIntervalMs());
    }

    @Test
    @DisplayName("should set concurrency")
    void shouldSetConcurrency() {
        properties.setConcurrency(8);

        assertEquals(8, properties.getConcurrency());
    }

    @Test
    @DisplayName("should set useNotify")
    void shouldSetUseNotify() {
        properties.setUseNotify(false);

        assertFalse(properties.isUseNotify());
    }
}
