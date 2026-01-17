package com.ivamare.commandbus.e2e.dto;

import com.ivamare.commandbus.e2e.payment.Currency;
import com.ivamare.commandbus.e2e.payment.PaymentStepBehavior;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Request DTO for creating a payment.
 *
 * @param executionModel Execution model: "COMMAND_BASED" (BaseProcessManager) or "STEP_BASED" (ProcessStepManager)
 */
public record PaymentCreateRequest(
    String debitTransit,
    String debitAccountNumber,
    String creditBic,
    String creditIban,
    BigDecimal debitAmount,
    Currency debitCurrency,
    Currency creditCurrency,
    LocalDate valueDate,
    Instant cutoffTimestamp,
    String executionModel,
    PaymentStepBehavior behavior
) {
    /**
     * Default request with sample values.
     */
    public static PaymentCreateRequest defaults() {
        return new PaymentCreateRequest(
            "00123",
            "12345678",
            "DEUTDEFF",
            "DE89370400440532013000",
            new BigDecimal("1000.00"),
            Currency.USD,
            Currency.EUR,
            LocalDate.now().plusDays(1),
            Instant.now().plus(24, ChronoUnit.HOURS),
            "COMMAND_BASED",
            PaymentStepBehavior.defaults()
        );
    }

    /**
     * Check if using STEP_BASED execution model (ProcessStepManager).
     */
    public boolean isStepBasedModel() {
        return "STEP_BASED".equals(executionModel);
    }
}
