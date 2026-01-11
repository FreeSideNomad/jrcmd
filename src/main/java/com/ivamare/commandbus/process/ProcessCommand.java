package com.ivamare.commandbus.process;

import java.util.Map;

/**
 * Typed wrapper for process command data.
 *
 * <p>Used by process managers to build commands for each step.
 *
 * @param <TData> The command data type
 * @param commandType The type of command to send
 * @param data The command payload data
 */
public record ProcessCommand<TData>(
    String commandType,
    TData data
) {
    /**
     * Convert to map for serialization.
     *
     * <p>If data implements ProcessState, uses its toMap() method.
     * If data is already a Map, returns it directly.
     * Otherwise throws IllegalArgumentException.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> toMap() {
        if (data instanceof ProcessState ps) {
            return ps.toMap();
        }
        if (data instanceof Map) {
            return (Map<String, Object>) data;
        }
        throw new IllegalArgumentException(
            "Command data must be a ProcessState or Map, got: " +
            (data != null ? data.getClass().getName() : "null")
        );
    }
}
