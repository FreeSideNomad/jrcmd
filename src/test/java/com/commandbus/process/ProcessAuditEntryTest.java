package com.commandbus.process;

import com.commandbus.model.ReplyOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ProcessAuditEntry")
class ProcessAuditEntryTest {

    @Test
    @DisplayName("should create entry for command")
    void shouldCreateEntryForCommand() {
        UUID commandId = UUID.randomUUID();
        Map<String, Object> data = Map.of("orderId", "123");

        ProcessAuditEntry entry = ProcessAuditEntry.forCommand(
            "RESERVE_INVENTORY",
            commandId,
            "ReserveInventory",
            data
        );

        assertEquals("RESERVE_INVENTORY", entry.stepName());
        assertEquals(commandId, entry.commandId());
        assertEquals("ReserveInventory", entry.commandType());
        assertEquals(data, entry.commandData());
        assertNotNull(entry.sentAt());
        assertNull(entry.replyOutcome());
        assertNull(entry.replyData());
        assertNull(entry.receivedAt());
    }

    @Test
    @DisplayName("should update entry with success reply")
    void shouldUpdateEntryWithSuccessReply() {
        UUID commandId = UUID.randomUUID();
        ProcessAuditEntry entry = ProcessAuditEntry.forCommand(
            "PROCESS_PAYMENT",
            commandId,
            "ProcessPayment",
            Map.of("amount", 100)
        );

        Map<String, Object> replyData = Map.of("paymentId", "pay-123");
        ProcessAuditEntry updated = entry.withReply(ReplyOutcome.SUCCESS, replyData);

        assertEquals("PROCESS_PAYMENT", updated.stepName());
        assertEquals(commandId, updated.commandId());
        assertEquals(entry.commandData(), updated.commandData());
        assertEquals(entry.sentAt(), updated.sentAt());
        assertEquals(ReplyOutcome.SUCCESS, updated.replyOutcome());
        assertEquals(replyData, updated.replyData());
        assertNotNull(updated.receivedAt());
    }

    @Test
    @DisplayName("should update entry with failed reply")
    void shouldUpdateEntryWithFailedReply() {
        UUID commandId = UUID.randomUUID();
        ProcessAuditEntry entry = ProcessAuditEntry.forCommand(
            "SHIP_ORDER",
            commandId,
            "ShipOrder",
            Map.of("orderId", "ord-456")
        );

        ProcessAuditEntry updated = entry.withReply(ReplyOutcome.FAILED, null);

        assertEquals(ReplyOutcome.FAILED, updated.replyOutcome());
        assertNull(updated.replyData());
        assertNotNull(updated.receivedAt());
    }

    @Test
    @DisplayName("should be immutable")
    void shouldBeImmutable() {
        UUID commandId = UUID.randomUUID();
        ProcessAuditEntry original = ProcessAuditEntry.forCommand(
            "STEP_ONE",
            commandId,
            "DoSomething",
            Map.of()
        );

        ProcessAuditEntry updated = original.withReply(ReplyOutcome.SUCCESS, Map.of("result", "ok"));

        assertNull(original.replyOutcome());
        assertEquals(ReplyOutcome.SUCCESS, updated.replyOutcome());
        assertNotSame(original, updated);
    }
}
