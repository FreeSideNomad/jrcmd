package com.commandbus.process;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ProcessCommand")
class ProcessCommandTest {

    record TestState(String orderId, int amount) implements ProcessState {
        @Override
        public Map<String, Object> toMap() {
            return Map.of("orderId", orderId, "amount", amount);
        }

        public static TestState fromMap(Map<String, Object> data) {
            return new TestState(
                (String) data.get("orderId"),
                (Integer) data.get("amount")
            );
        }
    }

    @Test
    @DisplayName("should convert ProcessState data to map")
    void shouldConvertProcessStateDataToMap() {
        TestState state = new TestState("ord-123", 500);
        ProcessCommand<TestState> command = new ProcessCommand<>("CreateOrder", state);

        Map<String, Object> map = command.toMap();

        assertEquals("ord-123", map.get("orderId"));
        assertEquals(500, map.get("amount"));
    }

    @Test
    @DisplayName("should return map data directly")
    void shouldReturnMapDataDirectly() {
        Map<String, Object> data = Map.of("key", "value", "count", 42);
        ProcessCommand<Map<String, Object>> command = new ProcessCommand<>("DoAction", data);

        Map<String, Object> result = command.toMap();

        assertEquals(data, result);
    }

    @Test
    @DisplayName("should throw for unsupported data type")
    void shouldThrowForUnsupportedDataType() {
        ProcessCommand<String> command = new ProcessCommand<>("Invalid", "not a map");

        assertThrows(IllegalArgumentException.class, command::toMap);
    }

    @Test
    @DisplayName("should throw for null data")
    void shouldThrowForNullData() {
        ProcessCommand<Object> command = new ProcessCommand<>("WithNull", null);

        assertThrows(IllegalArgumentException.class, command::toMap);
    }

    @Test
    @DisplayName("should store command type")
    void shouldStoreCommandType() {
        ProcessCommand<Map<String, Object>> command = new ProcessCommand<>("ReserveInventory", Map.of());

        assertEquals("ReserveInventory", command.commandType());
    }
}
