package com.commandbus.model;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BatchMetadataTest {

    @Nested
    class CreateTests {

        @Test
        void shouldCreateWithDefaults() {
            UUID batchId = UUID.randomUUID();
            BatchMetadata metadata = BatchMetadata.create(
                "payments", batchId, "Test Batch", Map.of("key", "value"), 10
            );

            assertEquals("payments", metadata.domain());
            assertEquals(batchId, metadata.batchId());
            assertEquals("Test Batch", metadata.name());
            assertEquals(Map.of("key", "value"), metadata.customData());
            assertEquals(BatchStatus.PENDING, metadata.status());
            assertEquals(10, metadata.totalCount());
            assertEquals(0, metadata.completedCount());
            assertEquals(0, metadata.canceledCount());
            assertEquals(0, metadata.inTroubleshootingCount());
            assertNotNull(metadata.createdAt());
            assertNull(metadata.startedAt());
            assertNull(metadata.completedAt());
        }

        @Test
        void shouldCreateWithNullCustomData() {
            BatchMetadata metadata = BatchMetadata.create(
                "payments", UUID.randomUUID(), "Test", null, 5
            );

            assertNull(metadata.customData());
        }
    }

    @Nested
    class IsCompleteTests {

        @Test
        void shouldReturnTrueForCompleted() {
            BatchMetadata metadata = new BatchMetadata(
                "payments", UUID.randomUUID(), "Test", null,
                BatchStatus.COMPLETED, 10, 10, 0, 0,
                Instant.now(), Instant.now(), Instant.now()
            );

            assertTrue(metadata.isComplete());
        }

        @Test
        void shouldReturnTrueForCompletedWithFailures() {
            BatchMetadata metadata = new BatchMetadata(
                "payments", UUID.randomUUID(), "Test", null,
                BatchStatus.COMPLETED_WITH_FAILURES, 10, 8, 2, 0,
                Instant.now(), Instant.now(), Instant.now()
            );

            assertTrue(metadata.isComplete());
        }

        @Test
        void shouldReturnFalseForPending() {
            BatchMetadata metadata = new BatchMetadata(
                "payments", UUID.randomUUID(), "Test", null,
                BatchStatus.PENDING, 10, 0, 0, 0,
                Instant.now(), null, null
            );

            assertFalse(metadata.isComplete());
        }

        @Test
        void shouldReturnFalseForInProgress() {
            BatchMetadata metadata = new BatchMetadata(
                "payments", UUID.randomUUID(), "Test", null,
                BatchStatus.IN_PROGRESS, 10, 5, 0, 0,
                Instant.now(), Instant.now(), null
            );

            assertFalse(metadata.isComplete());
        }
    }

    @Nested
    class IsFullySuccessfulTests {

        @Test
        void shouldReturnTrueWhenAllSuccessful() {
            BatchMetadata metadata = new BatchMetadata(
                "payments", UUID.randomUUID(), "Test", null,
                BatchStatus.COMPLETED, 10, 10, 0, 0,
                Instant.now(), Instant.now(), Instant.now()
            );

            assertTrue(metadata.isFullySuccessful());
        }

        @Test
        void shouldReturnFalseWhenHasCanceled() {
            BatchMetadata metadata = new BatchMetadata(
                "payments", UUID.randomUUID(), "Test", null,
                BatchStatus.COMPLETED, 10, 8, 2, 0,
                Instant.now(), Instant.now(), Instant.now()
            );

            assertFalse(metadata.isFullySuccessful());
        }

        @Test
        void shouldReturnFalseWhenHasTroubleshooting() {
            BatchMetadata metadata = new BatchMetadata(
                "payments", UUID.randomUUID(), "Test", null,
                BatchStatus.COMPLETED, 10, 8, 0, 2,
                Instant.now(), Instant.now(), Instant.now()
            );

            assertFalse(metadata.isFullySuccessful());
        }

        @Test
        void shouldReturnFalseWhenNotCompleted() {
            BatchMetadata metadata = new BatchMetadata(
                "payments", UUID.randomUUID(), "Test", null,
                BatchStatus.IN_PROGRESS, 10, 5, 0, 0,
                Instant.now(), Instant.now(), null
            );

            assertFalse(metadata.isFullySuccessful());
        }

        @Test
        void shouldReturnFalseForCompletedWithFailures() {
            BatchMetadata metadata = new BatchMetadata(
                "payments", UUID.randomUUID(), "Test", null,
                BatchStatus.COMPLETED_WITH_FAILURES, 10, 8, 2, 0,
                Instant.now(), Instant.now(), Instant.now()
            );

            assertFalse(metadata.isFullySuccessful());
        }
    }

    @Nested
    class RecordEqualityTests {

        @Test
        void shouldBeEqualWithSameValues() {
            UUID batchId = UUID.randomUUID();
            Instant now = Instant.now();

            BatchMetadata m1 = new BatchMetadata(
                "payments", batchId, "Test", Map.of("a", 1),
                BatchStatus.PENDING, 10, 0, 0, 0,
                now, null, null
            );

            BatchMetadata m2 = new BatchMetadata(
                "payments", batchId, "Test", Map.of("a", 1),
                BatchStatus.PENDING, 10, 0, 0, 0,
                now, null, null
            );

            assertEquals(m1, m2);
            assertEquals(m1.hashCode(), m2.hashCode());
        }

        @Test
        void shouldNotBeEqualWithDifferentValues() {
            BatchMetadata m1 = BatchMetadata.create(
                "payments", UUID.randomUUID(), "Test", null, 10
            );

            BatchMetadata m2 = BatchMetadata.create(
                "payments", UUID.randomUUID(), "Test", null, 10
            );

            assertNotEquals(m1, m2);
        }
    }
}
