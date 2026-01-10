package com.commandbus.health;

import com.commandbus.worker.Worker;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Health indicator for Command Bus workers.
 *
 * <p>Reports:
 * <ul>
 *   <li>Status of each worker by domain</li>
 *   <li>In-flight message counts</li>
 *   <li>Overall health based on worker status</li>
 * </ul>
 */
public class WorkerHealthIndicator implements HealthIndicator {

    private final List<Worker> workers;

    public WorkerHealthIndicator(List<Worker> workers) {
        this.workers = workers != null ? workers : List.of();
    }

    @Override
    public Health health() {
        if (workers.isEmpty()) {
            return Health.unknown()
                .withDetail("message", "No workers registered")
                .build();
        }

        Map<String, WorkerStatus> workerStatuses = workers.stream()
            .collect(Collectors.toMap(
                Worker::domain,
                w -> new WorkerStatus(w.isRunning(), w.inFlightCount()),
                (existing, replacement) -> existing
            ));

        boolean allRunning = workers.stream().allMatch(Worker::isRunning);
        int totalInFlight = workers.stream().mapToInt(Worker::inFlightCount).sum();

        Health.Builder builder = allRunning ? Health.up() : Health.down();

        return builder
            .withDetail("workers", workerStatuses)
            .withDetail("totalInFlight", totalInFlight)
            .build();
    }

    record WorkerStatus(boolean running, int inFlight) {}
}
