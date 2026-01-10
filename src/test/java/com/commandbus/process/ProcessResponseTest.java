package com.commandbus.process;

import com.commandbus.model.Reply;
import com.commandbus.model.ReplyOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ProcessResponse")
class ProcessResponseTest {

    record PaymentResult(String paymentId, String status) {
        public static PaymentResult fromMap(Map<String, Object> data) {
            return new PaymentResult(
                (String) data.get("paymentId"),
                (String) data.get("status")
            );
        }
    }

    @Test
    @DisplayName("should create from success reply with type conversion")
    void shouldCreateFromSuccessReplyWithTypeConversion() {
        UUID commandId = UUID.randomUUID();
        Reply reply = Reply.success(commandId, null, Map.of(
            "paymentId", "pay-123",
            "status", "APPROVED"
        ));

        ProcessResponse<PaymentResult> response = ProcessResponse.fromReply(
            reply,
            PaymentResult::fromMap
        );

        assertTrue(response.isSuccess());
        assertFalse(response.isFailed());
        assertNotNull(response.result());
        assertEquals("pay-123", response.result().paymentId());
        assertEquals("APPROVED", response.result().status());
        assertNull(response.errorCode());
        assertNull(response.errorMessage());
    }

    @Test
    @DisplayName("should create from failed reply")
    void shouldCreateFromFailedReply() {
        UUID commandId = UUID.randomUUID();
        Reply reply = Reply.failed(commandId, null, "PAYMENT_DECLINED", "Insufficient funds");

        ProcessResponse<PaymentResult> response = ProcessResponse.fromReply(
            reply,
            PaymentResult::fromMap
        );

        assertFalse(response.isSuccess());
        assertTrue(response.isFailed());
        assertNull(response.result());
        assertEquals("PAYMENT_DECLINED", response.errorCode());
        assertEquals("Insufficient funds", response.errorMessage());
    }

    @Test
    @DisplayName("should handle null data in success reply")
    void shouldHandleNullDataInSuccessReply() {
        UUID commandId = UUID.randomUUID();
        Reply reply = new Reply(commandId, null, ReplyOutcome.SUCCESS, null, null, null);

        ProcessResponse<PaymentResult> response = ProcessResponse.fromReply(
            reply,
            PaymentResult::fromMap
        );

        assertTrue(response.isSuccess());
        assertNull(response.result());
    }

    @Test
    @DisplayName("should create success response directly")
    void shouldCreateSuccessResponseDirectly() {
        PaymentResult result = new PaymentResult("pay-456", "COMPLETED");

        ProcessResponse<PaymentResult> response = ProcessResponse.success(result);

        assertTrue(response.isSuccess());
        assertEquals(result, response.result());
        assertNull(response.errorCode());
    }

    @Test
    @DisplayName("should create failed response directly")
    void shouldCreateFailedResponseDirectly() {
        ProcessResponse<PaymentResult> response = ProcessResponse.failed("ERR001", "Failed");

        assertTrue(response.isFailed());
        assertNull(response.result());
        assertEquals("ERR001", response.errorCode());
        assertEquals("Failed", response.errorMessage());
    }

    @Test
    @DisplayName("should detect canceled response")
    void shouldDetectCanceledResponse() {
        UUID commandId = UUID.randomUUID();
        Reply reply = Reply.canceled(commandId, null);

        ProcessResponse<PaymentResult> response = ProcessResponse.fromReply(
            reply,
            PaymentResult::fromMap
        );

        assertFalse(response.isSuccess());
        assertFalse(response.isFailed());
        assertTrue(response.isCanceled());
    }
}
