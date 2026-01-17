package com.ivamare.commandbus.e2e.payment.step;

import com.ivamare.commandbus.process.step.exceptions.TerminalFailureException;

/**
 * Exception thrown when risk assessment declines a payment.
 *
 * <p>This results in:
 * <ul>
 *   <li>Payment status = FAILED</li>
 *   <li>Process status = FAILED (not COMPENSATED)</li>
 *   <li>No compensation runs</li>
 * </ul>
 *
 * <p>Matches the behavior of PaymentProcessManager where
 * DECLINED results in UPDATE_STATUS_FAILED step.
 */
public class RiskDeclinedException extends TerminalFailureException {

    public RiskDeclinedException(String message) {
        super("RISK_DECLINED", message);
    }

    public RiskDeclinedException(String message, Throwable cause) {
        super("RISK_DECLINED", message, cause);
    }
}
