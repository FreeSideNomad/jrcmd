package com.ivamare.commandbus.process;

import java.util.Map;

/**
 * Simple ProcessState wrapper for deserialized map data.
 *
 * <p>Used when loading processes from the database before the caller
 * converts to their typed state class.
 */
public record MapProcessState(Map<String, Object> data) implements ProcessState {

    @Override
    public Map<String, Object> toMap() {
        return data != null ? data : Map.of();
    }
}
