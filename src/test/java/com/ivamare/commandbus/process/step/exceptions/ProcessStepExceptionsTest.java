package com.ivamare.commandbus.process.step.exceptions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Process Step Exceptions")
class ProcessStepExceptionsTest {

    @Nested
    @DisplayName("WaitConditionNotMetException")
    class WaitConditionNotMetExceptionTest {

        @Test
        @DisplayName("should create exception with wait name")
        void shouldCreateExceptionWithWaitName() {
            WaitConditionNotMetException ex = new WaitConditionNotMetException("awaitL1");

            assertEquals("awaitL1", ex.getWaitName());
            assertTrue(ex.getMessage().contains("awaitL1"));
            assertTrue(ex.getMessage().contains("not met"));
        }

        @Test
        @DisplayName("should extend RuntimeException")
        void shouldExtendRuntimeException() {
            WaitConditionNotMetException ex = new WaitConditionNotMetException("test");
            assertTrue(ex instanceof RuntimeException);
        }
    }

    @Nested
    @DisplayName("WaitingForRetryException")
    class WaitingForRetryExceptionTest {

        @Test
        @DisplayName("should create exception with step name and retry time")
        void shouldCreateExceptionWithStepNameAndRetryTime() {
            Instant retryAt = Instant.now().plusSeconds(30);
            WaitingForRetryException ex = new WaitingForRetryException("bookRisk", retryAt);

            assertEquals("bookRisk", ex.getStepName());
            assertEquals(retryAt, ex.getNextRetryAt());
            assertTrue(ex.getMessage().contains("bookRisk"));
            assertTrue(ex.getMessage().contains("retry"));
        }

        @Test
        @DisplayName("should extend RuntimeException")
        void shouldExtendRuntimeException() {
            WaitingForRetryException ex = new WaitingForRetryException("test", Instant.now());
            assertTrue(ex instanceof RuntimeException);
        }
    }

    @Nested
    @DisplayName("StepFailedException")
    class StepFailedExceptionTest {

        @Test
        @DisplayName("should create exception with step name, error code, and message")
        void shouldCreateExceptionWithStepNameAndErrorCode() {
            StepFailedException ex = new StepFailedException("bookRisk",
                "RETRIES_EXHAUSTED", "All retry attempts failed");

            assertEquals("bookRisk", ex.getStepName());
            assertEquals("RETRIES_EXHAUSTED", ex.getErrorCode());
            assertTrue(ex.getMessage().contains("bookRisk"));
            assertTrue(ex.getMessage().contains("failed"));
        }

        @Test
        @DisplayName("should create exception with cause")
        void shouldCreateExceptionWithCause() {
            RuntimeException cause = new RuntimeException("Original error");
            StepFailedException ex = new StepFailedException("bookRisk",
                "INTERNAL_ERROR", "Unexpected error", cause);

            assertEquals("bookRisk", ex.getStepName());
            assertEquals("INTERNAL_ERROR", ex.getErrorCode());
            assertSame(cause, ex.getCause());
        }

        @Test
        @DisplayName("should extend RuntimeException")
        void shouldExtendRuntimeException() {
            StepFailedException ex = new StepFailedException("test", "ERR", "msg");
            assertTrue(ex instanceof RuntimeException);
        }
    }

    @Nested
    @DisplayName("RateLimitExceededException")
    class RateLimitExceededExceptionTest {

        @Test
        @DisplayName("should create exception with message")
        void shouldCreateExceptionWithMessage() {
            RateLimitExceededException ex =
                new RateLimitExceededException("Rate limit exceeded for fx_api");

            assertTrue(ex.getMessage().contains("Rate limit exceeded"));
            assertNull(ex.getResourceKey());
        }

        @Test
        @DisplayName("should create exception with message and resource key")
        void shouldCreateExceptionWithMessageAndResourceKey() {
            RateLimitExceededException ex =
                new RateLimitExceededException("Rate limit exceeded", "fx_api");

            assertEquals("fx_api", ex.getResourceKey());
        }

        @Test
        @DisplayName("should extend RuntimeException")
        void shouldExtendRuntimeException() {
            RateLimitExceededException ex = new RateLimitExceededException("test");
            assertTrue(ex instanceof RuntimeException);
        }
    }

    @Nested
    @DisplayName("StepBusinessRuleException")
    class StepBusinessRuleExceptionTest {

        @Test
        @DisplayName("should create exception with message")
        void shouldCreateExceptionWithMessage() {
            StepBusinessRuleException ex = new StepBusinessRuleException("Insufficient funds");

            assertEquals("Insufficient funds", ex.getMessage());
            assertEquals("BUSINESS_RULE_VIOLATION", ex.getErrorCode());
        }

        @Test
        @DisplayName("should create exception with error code and message")
        void shouldCreateExceptionWithErrorCodeAndMessage() {
            StepBusinessRuleException ex = new StepBusinessRuleException(
                "INSUFFICIENT_FUNDS", "Account balance too low");

            assertEquals("INSUFFICIENT_FUNDS", ex.getErrorCode());
            assertEquals("Account balance too low", ex.getMessage());
        }

        @Test
        @DisplayName("should create exception with message and cause")
        void shouldCreateExceptionWithMessageAndCause() {
            RuntimeException cause = new RuntimeException("Original error");
            StepBusinessRuleException ex = new StepBusinessRuleException("Business error", cause);

            assertEquals("BUSINESS_RULE_VIOLATION", ex.getErrorCode());
            assertSame(cause, ex.getCause());
        }

        @Test
        @DisplayName("should create exception with error code, message, and cause")
        void shouldCreateExceptionWithErrorCodeMessageAndCause() {
            RuntimeException cause = new RuntimeException("Original error");
            StepBusinessRuleException ex = new StepBusinessRuleException(
                "RISK_DECLINED", "Risk assessment failed", cause);

            assertEquals("RISK_DECLINED", ex.getErrorCode());
            assertEquals("Risk assessment failed", ex.getMessage());
            assertSame(cause, ex.getCause());
        }

        @Test
        @DisplayName("should extend RuntimeException")
        void shouldExtendRuntimeException() {
            StepBusinessRuleException ex = new StepBusinessRuleException("test");
            assertTrue(ex instanceof RuntimeException);
        }
    }
}
