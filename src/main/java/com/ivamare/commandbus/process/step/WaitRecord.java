package com.ivamare.commandbus.process.step;

import java.time.Duration;
import java.time.Instant;

/**
 * Record of a wait condition within a ProcessStepManager workflow.
 *
 * <p>Wait records track:
 * <ul>
 *   <li>Whether the condition has been satisfied</li>
 *   <li>Timeout configuration and expiration time</li>
 * </ul>
 *
 * @param name Wait condition identifier (unique within a process)
 * @param satisfied Whether the condition has been met
 * @param recordedAt When this wait was first recorded
 * @param timeout Configured timeout duration
 * @param timeoutAt Absolute time when this wait expires
 */
public record WaitRecord(
    String name,
    boolean satisfied,
    Instant recordedAt,
    Duration timeout,
    Instant timeoutAt
) {

    /**
     * Create a pending wait record (condition not yet met).
     */
    public static WaitRecord pending(String name, Duration timeout, Instant timeoutAt) {
        return new WaitRecord(name, false, Instant.now(), timeout, timeoutAt);
    }

    /**
     * Create a satisfied wait record.
     */
    public static WaitRecord satisfied(String name) {
        return new WaitRecord(name, true, Instant.now(), null, null);
    }

    /**
     * Create an updated record marking the wait as satisfied.
     */
    public WaitRecord markSatisfied() {
        return new WaitRecord(name, true, recordedAt, timeout, timeoutAt);
    }

    /**
     * Check if the wait has timed out.
     */
    public boolean isTimedOut() {
        return !satisfied && timeoutAt != null && Instant.now().isAfter(timeoutAt);
    }
}
