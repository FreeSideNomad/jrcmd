package com.ivamare.commandbus.process.step;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CommandStepResponse")
class CommandStepResponseTest {

    @Nested
    @DisplayName("Factory Methods")
    class FactoryMethodTests {

        @Test
        @DisplayName("success should create successful response")
        void successShouldCreateSuccessfulResponse() {
            UUID processId = UUID.randomUUID();
            String stepName = "bookFx";
            String result = "FX-12345";

            CommandStepResponse<String> response = CommandStepResponse.success(processId, stepName, result);

            assertTrue(response.success());
            assertEquals(processId, response.processId());
            assertEquals(stepName, response.stepName());
            assertEquals(result, response.result());
            assertNull(response.errorCode());
            assertNull(response.errorMessage());
            assertNull(response.errorType());
        }

        @Test
        @DisplayName("failure should create failure response")
        void failureShouldCreateFailureResponse() {
            UUID processId = UUID.randomUUID();
            String stepName = "bookFx";

            CommandStepResponse<String> response = CommandStepResponse.failure(
                processId, stepName,
                CommandStepResponse.ErrorType.PERMANENT,
                "INVALID_DATA", "Invalid currency pair"
            );

            assertFalse(response.success());
            assertEquals(processId, response.processId());
            assertEquals(stepName, response.stepName());
            assertNull(response.result());
            assertEquals("INVALID_DATA", response.errorCode());
            assertEquals("Invalid currency pair", response.errorMessage());
            assertEquals(CommandStepResponse.ErrorType.PERMANENT, response.errorType());
        }

        @Test
        @DisplayName("transientFailure should create transient error response")
        void transientFailureShouldCreateTransientResponse() {
            UUID processId = UUID.randomUUID();

            CommandStepResponse<String> response = CommandStepResponse.transientFailure(
                processId, "step1", "TIMEOUT", "Request timed out"
            );

            assertFalse(response.success());
            assertEquals(CommandStepResponse.ErrorType.TRANSIENT, response.errorType());
            assertTrue(response.isRetryable());
            assertFalse(response.shouldMoveToTsq());
        }

        @Test
        @DisplayName("permanentFailure should create permanent error response")
        void permanentFailureShouldCreatePermanentResponse() {
            UUID processId = UUID.randomUUID();

            CommandStepResponse<String> response = CommandStepResponse.permanentFailure(
                processId, "step1", "INVALID", "Invalid data"
            );

            assertFalse(response.success());
            assertEquals(CommandStepResponse.ErrorType.PERMANENT, response.errorType());
            assertFalse(response.isRetryable());
            assertTrue(response.shouldMoveToTsq());
        }

        @Test
        @DisplayName("businessError should create business error response")
        void businessErrorShouldCreateBusinessErrorResponse() {
            UUID processId = UUID.randomUUID();

            CommandStepResponse<String> response = CommandStepResponse.businessError(
                processId, "step1", "INSUFFICIENT_FUNDS", "Not enough balance"
            );

            assertFalse(response.success());
            assertEquals(CommandStepResponse.ErrorType.BUSINESS, response.errorType());
            assertFalse(response.isRetryable());
            assertFalse(response.shouldMoveToTsq());
            assertTrue(response.isBusinessError());
        }

        @Test
        @DisplayName("timeout should create timeout error response")
        void timeoutShouldCreateTimeoutResponse() {
            UUID processId = UUID.randomUUID();

            CommandStepResponse<String> response = CommandStepResponse.timeout(
                processId, "step1", "Request timed out waiting for FX service"
            );

            assertFalse(response.success());
            assertEquals(CommandStepResponse.ErrorType.TIMEOUT, response.errorType());
            assertEquals("TIMEOUT", response.errorCode());
            assertTrue(response.isRetryable());
            assertFalse(response.shouldMoveToTsq());
        }
    }

    @Nested
    @DisplayName("Error Type Classification")
    class ErrorTypeClassificationTests {

        @Test
        @DisplayName("isRetryable should return true for transient errors")
        void isRetryableShouldReturnTrueForTransient() {
            UUID processId = UUID.randomUUID();
            CommandStepResponse<String> response = CommandStepResponse.transientFailure(
                processId, "step1", "ERROR", "Transient error"
            );

            assertTrue(response.isRetryable());
        }

        @Test
        @DisplayName("isRetryable should return true for timeout errors")
        void isRetryableShouldReturnTrueForTimeout() {
            UUID processId = UUID.randomUUID();
            CommandStepResponse<String> response = CommandStepResponse.timeout(
                processId, "step1", "Timed out"
            );

            assertTrue(response.isRetryable());
        }

        @Test
        @DisplayName("isRetryable should return false for permanent errors")
        void isRetryableShouldReturnFalseForPermanent() {
            UUID processId = UUID.randomUUID();
            CommandStepResponse<String> response = CommandStepResponse.permanentFailure(
                processId, "step1", "ERROR", "Permanent error"
            );

            assertFalse(response.isRetryable());
        }

        @Test
        @DisplayName("isRetryable should return false for business errors")
        void isRetryableShouldReturnFalseForBusiness() {
            UUID processId = UUID.randomUUID();
            CommandStepResponse<String> response = CommandStepResponse.businessError(
                processId, "step1", "ERROR", "Business error"
            );

            assertFalse(response.isRetryable());
        }

        @Test
        @DisplayName("shouldMoveToTsq should return true only for permanent errors")
        void shouldMoveToTsqShouldReturnTrueOnlyForPermanent() {
            UUID processId = UUID.randomUUID();

            assertTrue(CommandStepResponse.permanentFailure(processId, "s", "E", "M").shouldMoveToTsq());
            assertFalse(CommandStepResponse.transientFailure(processId, "s", "E", "M").shouldMoveToTsq());
            assertFalse(CommandStepResponse.businessError(processId, "s", "E", "M").shouldMoveToTsq());
            assertFalse(CommandStepResponse.timeout(processId, "s", "M").shouldMoveToTsq());
        }

        @Test
        @DisplayName("isBusinessError should return true only for business errors")
        void isBusinessErrorShouldReturnTrueOnlyForBusiness() {
            UUID processId = UUID.randomUUID();

            assertTrue(CommandStepResponse.businessError(processId, "s", "E", "M").isBusinessError());
            assertFalse(CommandStepResponse.permanentFailure(processId, "s", "E", "M").isBusinessError());
            assertFalse(CommandStepResponse.transientFailure(processId, "s", "E", "M").isBusinessError());
            assertFalse(CommandStepResponse.timeout(processId, "s", "M").isBusinessError());
        }

        @Test
        @DisplayName("success response should not be retryable")
        void successResponseShouldNotBeRetryable() {
            UUID processId = UUID.randomUUID();
            CommandStepResponse<String> response = CommandStepResponse.success(processId, "step1", "result");

            assertFalse(response.isRetryable());
        }

        @Test
        @DisplayName("success response should not move to TSQ")
        void successResponseShouldNotMoveToTsq() {
            UUID processId = UUID.randomUUID();
            CommandStepResponse<String> response = CommandStepResponse.success(processId, "step1", "result");

            assertFalse(response.shouldMoveToTsq());
        }

        @Test
        @DisplayName("success response should not be business error")
        void successResponseShouldNotBeBusinessError() {
            UUID processId = UUID.randomUUID();
            CommandStepResponse<String> response = CommandStepResponse.success(processId, "step1", "result");

            assertFalse(response.isBusinessError());
        }
    }
}
