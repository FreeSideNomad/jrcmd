package com.ivamare.commandbus.e2e.commands.fx;

import com.ivamare.commandbus.process.step.ExpectedOutcome;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Command to book an FX contract.
 *
 * <p>Sent from the payments domain to the FX domain for currency conversion.
 * The ExpectedOutcome field enables testing of various failure scenarios:
 * <ul>
 *   <li>SUCCESS - FX booking succeeds, returns rate and contract ID</li>
 *   <li>TIMEOUT - Simulates network/service timeout (transient, will retry)</li>
 *   <li>TRANSIENT_FAILURE - Service temporarily unavailable (will retry)</li>
 *   <li>PERMANENT_FAILURE - Invalid currency pair, rejected (goes to TSQ)</li>
 *   <li>BUSINESS_ERROR - Insufficient margin, rate expired (triggers compensation)</li>
 * </ul>
 *
 * @param processId The originating process ID (for correlation)
 * @param stepName The step name in the process workflow
 * @param paymentId The payment being processed
 * @param sourceCurrency The currency to convert from
 * @param targetCurrency The currency to convert to
 * @param amount The amount to convert
 * @param expectedOutcome The expected outcome (for testing)
 */
public record BookFxCommand(
    UUID processId,
    String stepName,
    UUID paymentId,
    String sourceCurrency,
    String targetCurrency,
    BigDecimal amount,
    ExpectedOutcome expectedOutcome
) {
    /**
     * Create a command expecting success (default for production).
     */
    public static BookFxCommand of(
            UUID processId,
            String stepName,
            UUID paymentId,
            String sourceCurrency,
            String targetCurrency,
            BigDecimal amount) {
        return new BookFxCommand(processId, stepName, paymentId,
            sourceCurrency, targetCurrency, amount, ExpectedOutcome.SUCCESS);
    }

    /**
     * Create a command with specific expected outcome (for testing).
     */
    public static BookFxCommand withOutcome(
            UUID processId,
            String stepName,
            UUID paymentId,
            String sourceCurrency,
            String targetCurrency,
            BigDecimal amount,
            ExpectedOutcome outcome) {
        return new BookFxCommand(processId, stepName, paymentId,
            sourceCurrency, targetCurrency, amount, outcome);
    }
}
