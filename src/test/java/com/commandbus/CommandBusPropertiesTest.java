package com.commandbus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CommandBusProperties")
class CommandBusPropertiesTest {

    @Test
    @DisplayName("should have default values")
    void shouldHaveDefaultValues() {
        CommandBusProperties properties = new CommandBusProperties();

        assertTrue(properties.isEnabled());
        assertEquals(3, properties.getDefaultMaxAttempts());
        assertEquals(List.of(10, 60, 300), properties.getBackoffSchedule());
        assertNotNull(properties.getWorker());
        assertNotNull(properties.getBatch());
    }

    @Test
    @DisplayName("should set enabled property")
    void shouldSetEnabledProperty() {
        CommandBusProperties properties = new CommandBusProperties();
        properties.setEnabled(false);

        assertFalse(properties.isEnabled());
    }

    @Test
    @DisplayName("should set default max attempts")
    void shouldSetDefaultMaxAttempts() {
        CommandBusProperties properties = new CommandBusProperties();
        properties.setDefaultMaxAttempts(5);

        assertEquals(5, properties.getDefaultMaxAttempts());
    }

    @Test
    @DisplayName("should set backoff schedule")
    void shouldSetBackoffSchedule() {
        CommandBusProperties properties = new CommandBusProperties();
        properties.setBackoffSchedule(List.of(5, 30, 120));

        assertEquals(List.of(5, 30, 120), properties.getBackoffSchedule());
    }

    @Test
    @DisplayName("should set worker properties")
    void shouldSetWorkerProperties() {
        CommandBusProperties properties = new CommandBusProperties();
        CommandBusProperties.WorkerProperties workerProps = new CommandBusProperties.WorkerProperties();
        workerProps.setVisibilityTimeout(60);
        properties.setWorker(workerProps);

        assertEquals(60, properties.getWorker().getVisibilityTimeout());
    }

    @Test
    @DisplayName("should set batch properties")
    void shouldSetBatchProperties() {
        CommandBusProperties properties = new CommandBusProperties();
        CommandBusProperties.BatchProperties batchProps = new CommandBusProperties.BatchProperties();
        batchProps.setDefaultChunkSize(500);
        properties.setBatch(batchProps);

        assertEquals(500, properties.getBatch().getDefaultChunkSize());
    }

    @Test
    @DisplayName("WorkerProperties should have default values")
    void workerPropertiesShouldHaveDefaultValues() {
        CommandBusProperties.WorkerProperties workerProps = new CommandBusProperties.WorkerProperties();

        assertFalse(workerProps.isAutoStart());
        assertEquals(30, workerProps.getVisibilityTimeout());
        assertEquals(1000, workerProps.getPollIntervalMs());
        assertEquals(4, workerProps.getConcurrency());
        assertTrue(workerProps.isUseNotify());
    }

    @Test
    @DisplayName("WorkerProperties should set all properties")
    void workerPropertiesShouldSetAllProperties() {
        CommandBusProperties.WorkerProperties workerProps = new CommandBusProperties.WorkerProperties();

        workerProps.setAutoStart(true);
        workerProps.setVisibilityTimeout(60);
        workerProps.setPollIntervalMs(2000);
        workerProps.setConcurrency(8);
        workerProps.setUseNotify(false);

        assertTrue(workerProps.isAutoStart());
        assertEquals(60, workerProps.getVisibilityTimeout());
        assertEquals(2000, workerProps.getPollIntervalMs());
        assertEquals(8, workerProps.getConcurrency());
        assertFalse(workerProps.isUseNotify());
    }

    @Test
    @DisplayName("BatchProperties should have default values")
    void batchPropertiesShouldHaveDefaultValues() {
        CommandBusProperties.BatchProperties batchProps = new CommandBusProperties.BatchProperties();

        assertEquals(1000, batchProps.getDefaultChunkSize());
    }

    @Test
    @DisplayName("BatchProperties should set chunk size")
    void batchPropertiesShouldSetChunkSize() {
        CommandBusProperties.BatchProperties batchProps = new CommandBusProperties.BatchProperties();
        batchProps.setDefaultChunkSize(2000);

        assertEquals(2000, batchProps.getDefaultChunkSize());
    }
}
