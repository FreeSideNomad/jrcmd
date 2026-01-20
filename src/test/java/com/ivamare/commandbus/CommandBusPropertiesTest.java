package com.ivamare.commandbus;

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

    @Test
    @DisplayName("ResilienceProperties should have default values")
    void resiliencePropertiesShouldHaveDefaultValues() {
        CommandBusProperties.ResilienceProperties resilience = new CommandBusProperties.ResilienceProperties();

        assertEquals(1000, resilience.getInitialBackoffMs());
        assertEquals(30000, resilience.getMaxBackoffMs());
        assertEquals(2.0, resilience.getBackoffMultiplier());
        assertEquals(5, resilience.getErrorThreshold());
    }

    @Test
    @DisplayName("ResilienceProperties should set all properties")
    void resiliencePropertiesShouldSetAllProperties() {
        CommandBusProperties.ResilienceProperties resilience = new CommandBusProperties.ResilienceProperties();

        resilience.setInitialBackoffMs(2000);
        resilience.setMaxBackoffMs(60000);
        resilience.setBackoffMultiplier(3.0);
        resilience.setErrorThreshold(10);

        assertEquals(2000, resilience.getInitialBackoffMs());
        assertEquals(60000, resilience.getMaxBackoffMs());
        assertEquals(3.0, resilience.getBackoffMultiplier());
        assertEquals(10, resilience.getErrorThreshold());
    }

    @Test
    @DisplayName("ResilienceProperties calculateBackoff should use exponential formula")
    void calculateBackoffShouldUseExponentialFormula() {
        CommandBusProperties.ResilienceProperties resilience = new CommandBusProperties.ResilienceProperties();
        resilience.setInitialBackoffMs(1000);
        resilience.setBackoffMultiplier(2.0);
        resilience.setMaxBackoffMs(30000);

        // First error: initial backoff with jitter (+/- 10%)
        long backoff1 = resilience.calculateBackoff(1);
        assertTrue(backoff1 >= 900 && backoff1 <= 1100, "First backoff should be ~1000ms");

        // Second error: 2x with jitter
        long backoff2 = resilience.calculateBackoff(2);
        assertTrue(backoff2 >= 1800 && backoff2 <= 2200, "Second backoff should be ~2000ms");

        // Third error: 4x with jitter
        long backoff3 = resilience.calculateBackoff(3);
        assertTrue(backoff3 >= 3600 && backoff3 <= 4400, "Third backoff should be ~4000ms");
    }

    @Test
    @DisplayName("ResilienceProperties calculateBackoff should cap at maxBackoffMs")
    void calculateBackoffShouldCapAtMax() {
        CommandBusProperties.ResilienceProperties resilience = new CommandBusProperties.ResilienceProperties();
        resilience.setInitialBackoffMs(1000);
        resilience.setBackoffMultiplier(2.0);
        resilience.setMaxBackoffMs(10000);

        // Error count 5: 1000 * 2^4 = 16000, should be capped at 10000
        long backoff = resilience.calculateBackoff(5);
        assertTrue(backoff <= 10000, "Backoff should be capped at maxBackoffMs");
    }

    @Test
    @DisplayName("ResilienceProperties calculateBackoff should handle zero/negative errorCount")
    void calculateBackoffShouldHandleZeroOrNegative() {
        CommandBusProperties.ResilienceProperties resilience = new CommandBusProperties.ResilienceProperties();
        resilience.setInitialBackoffMs(1000);

        // Zero error count should return initial
        long backoff0 = resilience.calculateBackoff(0);
        assertEquals(1000, backoff0);

        // Negative should also return initial
        long backoffNeg = resilience.calculateBackoff(-1);
        assertEquals(1000, backoffNeg);
    }

    @Test
    @DisplayName("WorkerProperties should have resilience with default values")
    void workerPropertiesShouldHaveResilienceDefaults() {
        CommandBusProperties.WorkerProperties workerProps = new CommandBusProperties.WorkerProperties();

        assertNotNull(workerProps.getResilience());
        assertEquals(1000, workerProps.getResilience().getInitialBackoffMs());
        assertEquals(30000, workerProps.getResilience().getMaxBackoffMs());
    }

    @Test
    @DisplayName("WorkerProperties should allow custom resilience configuration")
    void workerPropertiesShouldAllowCustomResilience() {
        CommandBusProperties.WorkerProperties workerProps = new CommandBusProperties.WorkerProperties();
        CommandBusProperties.ResilienceProperties resilience = new CommandBusProperties.ResilienceProperties();
        resilience.setInitialBackoffMs(5000);
        workerProps.setResilience(resilience);

        assertEquals(5000, workerProps.getResilience().getInitialBackoffMs());
    }

    @Test
    @DisplayName("ProcessProperties should have resilience with default values")
    void processPropertiesShouldHaveResilienceDefaults() {
        CommandBusProperties.ProcessProperties processProps = new CommandBusProperties.ProcessProperties();

        assertNotNull(processProps.getResilience());
        assertEquals(1000, processProps.getResilience().getInitialBackoffMs());
    }

    @Test
    @DisplayName("ProcessProperties should allow custom resilience configuration")
    void processPropertiesShouldAllowCustomResilience() {
        CommandBusProperties.ProcessProperties processProps = new CommandBusProperties.ProcessProperties();
        CommandBusProperties.ResilienceProperties resilience = new CommandBusProperties.ResilienceProperties();
        resilience.setMaxBackoffMs(60000);
        processProps.setResilience(resilience);

        assertEquals(60000, processProps.getResilience().getMaxBackoffMs());
    }
}
