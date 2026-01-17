package com.ivamare.commandbus.process.step;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SideEffectRecord")
class SideEffectRecordTest {

    @Test
    @DisplayName("should create side effect record with factory method")
    void shouldCreateSideEffectRecord() {
        SideEffectRecord record = SideEffectRecord.create("generateTransactionId",
            "\"TXN-123456\"");

        assertEquals("generateTransactionId", record.name());
        assertEquals("\"TXN-123456\"", record.valueJson());
        assertNotNull(record.recordedAt());
    }

    @Test
    @DisplayName("should create side effect record with null value")
    void shouldCreateSideEffectRecordWithNullValue() {
        SideEffectRecord record = SideEffectRecord.create("generateOptionalValue", null);

        assertEquals("generateOptionalValue", record.name());
        assertNull(record.valueJson());
        assertNotNull(record.recordedAt());
    }

    @Test
    @DisplayName("should create side effect record with complex JSON")
    void shouldCreateSideEffectRecordWithComplexJson() {
        String complexJson = "{\"id\": \"123\", \"timestamp\": \"2024-01-15T10:30:00Z\", " +
            "\"values\": [1, 2, 3]}";
        SideEffectRecord record = SideEffectRecord.create("generateConfig", complexJson);

        assertEquals("generateConfig", record.name());
        assertEquals(complexJson, record.valueJson());
    }

    @Test
    @DisplayName("record equality should work correctly")
    void recordEqualityShouldWorkCorrectly() {
        Instant now = Instant.now();
        SideEffectRecord record1 = new SideEffectRecord("test", "{}", now);
        SideEffectRecord record2 = new SideEffectRecord("test", "{}", now);

        assertEquals(record1, record2);
        assertEquals(record1.hashCode(), record2.hashCode());
    }

    @Test
    @DisplayName("records with different names should not be equal")
    void recordsWithDifferentNamesShouldNotBeEqual() {
        Instant now = Instant.now();
        SideEffectRecord record1 = new SideEffectRecord("name1", "{}", now);
        SideEffectRecord record2 = new SideEffectRecord("name2", "{}", now);

        assertNotEquals(record1, record2);
    }

    @Test
    @DisplayName("records with different values should not be equal")
    void recordsWithDifferentValuesShouldNotBeEqual() {
        Instant now = Instant.now();
        SideEffectRecord record1 = new SideEffectRecord("test", "{\"a\": 1}", now);
        SideEffectRecord record2 = new SideEffectRecord("test", "{\"a\": 2}", now);

        assertNotEquals(record1, record2);
    }
}
