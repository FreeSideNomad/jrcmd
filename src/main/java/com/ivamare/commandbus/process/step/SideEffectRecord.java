package com.ivamare.commandbus.process.step;

import java.time.Instant;

/**
 * Record of a side effect within a ProcessStepManager workflow.
 *
 * <p>Side effects are operations that should only execute once and
 * return the same value on replay. Examples:
 * <ul>
 *   <li>Generating UUIDs or timestamps</li>
 *   <li>Reading external configuration</li>
 *   <li>Random number generation</li>
 * </ul>
 *
 * @param name Side effect identifier (unique within a process)
 * @param valueJson Serialized result value for replay
 * @param recordedAt When this side effect was recorded
 */
public record SideEffectRecord(
    String name,
    String valueJson,
    Instant recordedAt
) {

    /**
     * Create a new side effect record.
     */
    public static SideEffectRecord create(String name, String valueJson) {
        return new SideEffectRecord(name, valueJson, Instant.now());
    }
}
