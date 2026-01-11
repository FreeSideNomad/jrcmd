package com.ivamare.commandbus.process;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MapProcessState")
class MapProcessStateTest {

    @Test
    @DisplayName("toMap should return data when not null")
    void toMapShouldReturnDataWhenNotNull() {
        Map<String, Object> data = Map.of("key", "value", "count", 42);
        MapProcessState state = new MapProcessState(data);

        assertEquals(data, state.toMap());
    }

    @Test
    @DisplayName("toMap should return empty map when data is null")
    void toMapShouldReturnEmptyMapWhenDataIsNull() {
        MapProcessState state = new MapProcessState(null);

        Map<String, Object> result = state.toMap();

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("data accessor should return the data")
    void dataShouldReturnData() {
        Map<String, Object> data = Map.of("test", "data");
        MapProcessState state = new MapProcessState(data);

        assertEquals(data, state.data());
    }
}
