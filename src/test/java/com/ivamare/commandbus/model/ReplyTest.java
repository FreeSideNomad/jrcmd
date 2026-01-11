package com.ivamare.commandbus.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Reply")
class ReplyTest {

    @Test
    @DisplayName("should create success reply with data")
    void shouldCreateSuccessReplyWithData() {
        UUID commandId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        Map<String, Object> data = Map.of("result", "value");

        Reply reply = Reply.success(commandId, correlationId, data);

        assertEquals(commandId, reply.commandId());
        assertEquals(correlationId, reply.correlationId());
        assertEquals(ReplyOutcome.SUCCESS, reply.outcome());
        assertEquals(data, reply.data());
        assertNull(reply.errorCode());
        assertNull(reply.errorMessage());
        assertTrue(reply.isSuccess());
        assertFalse(reply.isFailed());
        assertFalse(reply.isCanceled());
    }

    @Test
    @DisplayName("should create failed reply with error info")
    void shouldCreateFailedReplyWithErrorInfo() {
        UUID commandId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();

        Reply reply = Reply.failed(commandId, correlationId, "ERR001", "Something went wrong");

        assertEquals(commandId, reply.commandId());
        assertEquals(correlationId, reply.correlationId());
        assertEquals(ReplyOutcome.FAILED, reply.outcome());
        assertNull(reply.data());
        assertEquals("ERR001", reply.errorCode());
        assertEquals("Something went wrong", reply.errorMessage());
        assertFalse(reply.isSuccess());
        assertTrue(reply.isFailed());
        assertFalse(reply.isCanceled());
    }

    @Test
    @DisplayName("should create canceled reply")
    void shouldCreateCanceledReply() {
        UUID commandId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();

        Reply reply = Reply.canceled(commandId, correlationId);

        assertEquals(commandId, reply.commandId());
        assertEquals(correlationId, reply.correlationId());
        assertEquals(ReplyOutcome.CANCELED, reply.outcome());
        assertNull(reply.data());
        assertNull(reply.errorCode());
        assertNull(reply.errorMessage());
        assertFalse(reply.isSuccess());
        assertFalse(reply.isFailed());
        assertTrue(reply.isCanceled());
    }

    @Test
    @DisplayName("should allow null correlation id")
    void shouldAllowNullCorrelationId() {
        UUID commandId = UUID.randomUUID();

        Reply reply = Reply.success(commandId, null, Map.of());

        assertNull(reply.correlationId());
    }

    @Test
    @DisplayName("should create business rule failed reply")
    void shouldCreateBusinessRuleFailedReply() {
        UUID commandId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();

        Reply reply = Reply.businessRuleFailed(commandId, correlationId, "ACCOUNT_CLOSED", "Account is closed");

        assertEquals(commandId, reply.commandId());
        assertEquals(correlationId, reply.correlationId());
        assertEquals(ReplyOutcome.FAILED, reply.outcome());
        assertNull(reply.data());
        assertEquals("ACCOUNT_CLOSED", reply.errorCode());
        assertEquals("Account is closed", reply.errorMessage());
        assertEquals("BUSINESS_RULE", reply.errorType());
        assertTrue(reply.isBusinessRuleFailure());
        assertTrue(reply.isFailed());
        assertFalse(reply.isSuccess());
        assertFalse(reply.isCanceled());
    }

    @Test
    @DisplayName("should not be business rule failure for regular failed reply")
    void shouldNotBeBusinessRuleFailureForRegularFailed() {
        UUID commandId = UUID.randomUUID();

        Reply reply = Reply.failed(commandId, null, "ERR001", "Error");

        assertFalse(reply.isBusinessRuleFailure());
        assertEquals("PERMANENT", reply.errorType());  // Regular failed defaults to PERMANENT
    }

    @Test
    @DisplayName("should not be business rule failure for success reply")
    void shouldNotBeBusinessRuleFailureForSuccess() {
        UUID commandId = UUID.randomUUID();

        Reply reply = Reply.success(commandId, null, Map.of());

        assertFalse(reply.isBusinessRuleFailure());
    }
}
