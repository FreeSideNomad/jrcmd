package com.ivamare.commandbus.e2e.commands.network;

import com.ivamare.commandbus.process.step.ExpectedOutcome;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Command to submit a payment to the payment network.
 *
 * <p>Sent from the payments domain to the network domain for payment processing.
 * The ExpectedOutcome field enables testing of various failure scenarios:
 * <ul>
 *   <li>SUCCESS - Payment submitted successfully, returns submission reference</li>
 *   <li>TIMEOUT - Simulates network timeout (transient, will retry)</li>
 *   <li>TRANSIENT_FAILURE - Network temporarily unavailable (will retry)</li>
 *   <li>PERMANENT_FAILURE - Invalid payment details, rejected (goes to TSQ)</li>
 *   <li>BUSINESS_ERROR - Account blocked, insufficient funds (triggers compensation)</li>
 * </ul>
 *
 * @param processId The originating process ID (for correlation)
 * @param stepName The step name in the process workflow
 * @param paymentId The payment being submitted
 * @param amount The payment amount
 * @param currency The payment currency
 * @param debitAccountBic The debit account BIC
 * @param debitAccountIban The debit account IBAN
 * @param creditAccountBic The credit account BIC
 * @param creditAccountIban The credit account IBAN
 * @param expectedOutcome The expected outcome (for testing)
 */
public record SubmitPaymentCommand(
    UUID processId,
    String stepName,
    UUID paymentId,
    BigDecimal amount,
    String currency,
    String debitAccountBic,
    String debitAccountIban,
    String creditAccountBic,
    String creditAccountIban,
    ExpectedOutcome expectedOutcome
) {
    /**
     * Create a command expecting success (default for production).
     */
    public static SubmitPaymentCommand of(
            UUID processId,
            String stepName,
            UUID paymentId,
            BigDecimal amount,
            String currency,
            String debitAccountBic,
            String debitAccountIban,
            String creditAccountBic,
            String creditAccountIban) {
        return new SubmitPaymentCommand(processId, stepName, paymentId, amount, currency,
            debitAccountBic, debitAccountIban, creditAccountBic, creditAccountIban,
            ExpectedOutcome.SUCCESS);
    }

    /**
     * Create a command with specific expected outcome (for testing).
     */
    public static SubmitPaymentCommand withOutcome(
            UUID processId,
            String stepName,
            UUID paymentId,
            BigDecimal amount,
            String currency,
            String debitAccountBic,
            String debitAccountIban,
            String creditAccountBic,
            String creditAccountIban,
            ExpectedOutcome outcome) {
        return new SubmitPaymentCommand(processId, stepName, paymentId, amount, currency,
            debitAccountBic, debitAccountIban, creditAccountBic, creditAccountIban, outcome);
    }
}
