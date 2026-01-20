package com.ivamare.commandbus.health;

import com.ivamare.commandbus.worker.Worker;
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
                w -> new WorkerStatus(w.isRunning(), w.inFlightCount(), w.getConsecutiveErrorCount()),
                (existing, replacement) -> existing
            ));

        boolean allRunning = workers.stream().allMatch(Worker::isRunning);
        int totalInFlight = workers.stream().mapToInt(Worker::inFlightCount).sum();
        int maxConsecutiveErrors = workers.stream()
            .mapToInt(Worker::getConsecutiveErrorCount)
            .max()
            .orElse(0);

        // Consider unhealthy if any worker has high consecutive errors
        boolean healthy = allRunning && maxConsecutiveErrors < 5;
        Health.Builder builder = healthy ? Health.up() : Health.down();

        return builder
            .withDetail("workers", workerStatuses)
            .withDetail("totalInFlight", totalInFlight)
            .withDetail("maxConsecutiveErrors", maxConsecutiveErrors)
            .build();
    }

    record WorkerStatus(boolean running, int inFlight, int consecutiveErrors) {}
}
