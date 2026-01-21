package com.ivamare.commandbus.process.step;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PendingCommandStep")
class PendingCommandStepTest {

    @Nested
    @DisplayName("Factory Methods")
    class FactoryMethodTests {

        @Test
        @DisplayName("create with default timeout should use 30 seconds")
        void createWithDefaultTimeoutShouldUse30Seconds() {
            String stepName = "bookFx";
            UUID commandId = UUID.randomUUID();

            PendingCommandStep pending = PendingCommandStep.create(stepName, commandId);

            assertEquals(stepName, pending.stepName());
            assertEquals(commandId, pending.commandId());
            assertNotNull(pending.sentAt());
            assertNotNull(pending.timeoutAt());

            // Timeout should be about 30 seconds from now
            long expectedTimeout = pending.sentAt().plusSeconds(30).toEpochMilli();
            long actualTimeout = pending.timeoutAt().toEpochMilli();
            assertTrue(Math.abs(expectedTimeout - actualTimeout) < 1000,
                "Timeout should be 30 seconds from sentAt");
        }

        @Test
        @DisplayName("create with custom timeout should use specified value")
        void createWithCustomTimeoutShouldUseSpecifiedValue() {
            String stepName = "submitPayment";
            UUID commandId = UUID.randomUUID();
            long timeoutSeconds = 60;

            PendingCommandStep pending = PendingCommandStep.create(stepName, commandId, timeoutSeconds);

            assertEquals(stepName, pending.stepName());
            assertEquals(commandId, pending.commandId());

            // Timeout should be about 60 seconds from now
            long expectedTimeout = pending.sentAt().plusSeconds(60).toEpochMilli();
            long actualTimeout = pending.timeoutAt().toEpochMilli();
            assertTrue(Math.abs(expectedTimeout - actualTimeout) < 1000,
                "Timeout should be 60 seconds from sentAt");
        }
    }

    @Nested
    @DisplayName("Timeout Check")
    class TimeoutCheckTests {

        @Test
        @DisplayName("isTimedOut should return false when not timed out")
        void isTimedOutShouldReturnFalseWhenNotTimedOut() {
            PendingCommandStep pending = PendingCommandStep.create("step1", UUID.randomUUID(), 60);

            assertFalse(pending.isTimedOut());
        }

        @Test
        @DisplayName("isTimedOut should return true when timed out")
        void isTimedOutShouldReturnTrueWhenTimedOut() {
            Instant now = Instant.now();
            Instant sentAt = now.minusSeconds(120);  // Sent 2 minutes ago
            Instant timeoutAt = now.minusSeconds(60);  // Should have timed out 1 minute ago

            PendingCommandStep pending = new PendingCommandStep("step1", UUID.randomUUID(), sentAt, timeoutAt);

            assertTrue(pending.isTimedOut());
        }

        @Test
        @DisplayName("isTimedOut should return false just before timeout")
        void isTimedOutShouldReturnFalseJustBeforeTimeout() {
            // Create with 10 second timeout (should not be timed out yet)
            PendingCommandStep pending = PendingCommandStep.create("step1", UUID.randomUUID(), 10);

            assertFalse(pending.isTimedOut());
        }
    }

    @Nested
    @DisplayName("Record Properties")
    class RecordPropertiesTests {

        @Test
        @DisplayName("should have correct record components")
        void shouldHaveCorrectRecordComponents() {
            String stepName = "myStep";
            UUID commandId = UUID.randomUUID();
            Instant sentAt = Instant.now();
            Instant timeoutAt = sentAt.plusSeconds(30);

            PendingCommandStep pending = new PendingCommandStep(stepName, commandId, sentAt, timeoutAt);

            assertEquals(stepName, pending.stepName());
            assertEquals(commandId, pending.commandId());
            assertEquals(sentAt, pending.sentAt());
            assertEquals(timeoutAt, pending.timeoutAt());
        }

        @Test
        @DisplayName("equals and hashCode should work correctly")
        void equalsAndHashCodeShouldWorkCorrectly() {
            UUID commandId = UUID.randomUUID();
            Instant sentAt = Instant.now();
            Instant timeoutAt = sentAt.plusSeconds(30);

            PendingCommandStep p1 = new PendingCommandStep("step", commandId, sentAt, timeoutAt);
            PendingCommandStep p2 = new PendingCommandStep("step", commandId, sentAt, timeoutAt);

            assertEquals(p1, p2);
            assertEquals(p1.hashCode(), p2.hashCode());
        }

        @Test
        @DisplayName("toString should include all fields")
        void toStringShouldIncludeAllFields() {
            String stepName = "testStep";
            UUID commandId = UUID.randomUUID();

            PendingCommandStep pending = PendingCommandStep.create(stepName, commandId);

            String toString = pending.toString();
            assertTrue(toString.contains(stepName));
            assertTrue(toString.contains(commandId.toString()));
        }
    }
}
