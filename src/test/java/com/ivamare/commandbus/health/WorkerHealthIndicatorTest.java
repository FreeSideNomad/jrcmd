package com.ivamare.commandbus.health;

import com.ivamare.commandbus.worker.Worker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("WorkerHealthIndicator")
class WorkerHealthIndicatorTest {

    @Test
    @DisplayName("should return UNKNOWN when no workers registered")
    void shouldReturnUnknownWhenNoWorkersRegistered() {
        WorkerHealthIndicator healthIndicator = new WorkerHealthIndicator(List.of());

        Health health = healthIndicator.health();

        assertEquals(Status.UNKNOWN, health.getStatus());
        assertEquals("No workers registered", health.getDetails().get("message"));
    }

    @Test
    @DisplayName("should return UNKNOWN when null workers list")
    void shouldReturnUnknownWhenNullWorkersList() {
        WorkerHealthIndicator healthIndicator = new WorkerHealthIndicator(null);

        Health health = healthIndicator.health();

        assertEquals(Status.UNKNOWN, health.getStatus());
        assertEquals("No workers registered", health.getDetails().get("message"));
    }

    @Test
    @DisplayName("should return UP when all workers running")
    void shouldReturnUpWhenAllWorkersRunning() {
        Worker worker1 = mock(Worker.class);
        when(worker1.domain()).thenReturn("payments");
        when(worker1.isRunning()).thenReturn(true);
        when(worker1.inFlightCount()).thenReturn(3);

        Worker worker2 = mock(Worker.class);
        when(worker2.domain()).thenReturn("orders");
        when(worker2.isRunning()).thenReturn(true);
        when(worker2.inFlightCount()).thenReturn(2);

        WorkerHealthIndicator healthIndicator = new WorkerHealthIndicator(List.of(worker1, worker2));

        Health health = healthIndicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals(5, health.getDetails().get("totalInFlight"));
        assertNotNull(health.getDetails().get("workers"));
    }

    @Test
    @DisplayName("should return DOWN when any worker not running")
    void shouldReturnDownWhenAnyWorkerNotRunning() {
        Worker worker1 = mock(Worker.class);
        when(worker1.domain()).thenReturn("payments");
        when(worker1.isRunning()).thenReturn(true);
        when(worker1.inFlightCount()).thenReturn(3);

        Worker worker2 = mock(Worker.class);
        when(worker2.domain()).thenReturn("orders");
        when(worker2.isRunning()).thenReturn(false);
        when(worker2.inFlightCount()).thenReturn(0);

        WorkerHealthIndicator healthIndicator = new WorkerHealthIndicator(List.of(worker1, worker2));

        Health health = healthIndicator.health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals(3, health.getDetails().get("totalInFlight"));
    }

    @Test
    @DisplayName("should handle duplicate domains")
    void shouldHandleDuplicateDomains() {
        Worker worker1 = mock(Worker.class);
        when(worker1.domain()).thenReturn("payments");
        when(worker1.isRunning()).thenReturn(true);
        when(worker1.inFlightCount()).thenReturn(3);

        Worker worker2 = mock(Worker.class);
        when(worker2.domain()).thenReturn("payments");
        when(worker2.isRunning()).thenReturn(true);
        when(worker2.inFlightCount()).thenReturn(2);

        WorkerHealthIndicator healthIndicator = new WorkerHealthIndicator(List.of(worker1, worker2));

        Health health = healthIndicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals(5, health.getDetails().get("totalInFlight"));
    }

    @Test
    @DisplayName("should report individual worker status")
    void shouldReportIndividualWorkerStatus() {
        Worker worker = mock(Worker.class);
        when(worker.domain()).thenReturn("payments");
        when(worker.isRunning()).thenReturn(true);
        when(worker.inFlightCount()).thenReturn(5);

        WorkerHealthIndicator healthIndicator = new WorkerHealthIndicator(List.of(worker));

        Health health = healthIndicator.health();

        assertEquals(Status.UP, health.getStatus());
        @SuppressWarnings("unchecked")
        var workers = (java.util.Map<String, WorkerHealthIndicator.WorkerStatus>) health.getDetails().get("workers");
        assertNotNull(workers);
        assertTrue(workers.containsKey("payments"));
        WorkerHealthIndicator.WorkerStatus status = workers.get("payments");
        assertTrue(status.running());
        assertEquals(5, status.inFlight());
    }
}
