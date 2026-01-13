package com.ivamare.commandbus.e2e.payment;

/**
 * Process steps for payment processing workflow.
 *
 * <p>The workflow supports:
 * <ul>
 *   <li>Normal flow: BOOK_RISK → [AWAIT_RISK_APPROVAL] → [BOOK_FX] → SUBMIT_PAYMENT → AWAIT_CONFIRMATIONS</li>
 *   <li>Manual approval: AWAIT_RISK_APPROVAL is inserted when payment requires operator approval</li>
 *   <li>Compensation flow: UNWIND_FX → UNWIND_RISK</li>
 * </ul>
 *
 * <p>AWAIT_CONFIRMATIONS accumulates L1-L4 network replies in any order.
 * Process completes when L4 success is received, or cancels on any error.
 */
public enum PaymentStep {
    // Normal flow steps
    BOOK_RISK("BookTransactionRisk", false),
    BOOK_FX("BookFx", false),
    SUBMIT_PAYMENT("SubmitPayment", false),

    // Wait-only steps (no command sent, waiting for external reply)
    AWAIT_RISK_APPROVAL(null, true),  // Waiting for operator to approve risk
    AWAIT_CONFIRMATIONS(null, true),  // Waiting for L1-L4 network confirmations (any order)

    // Compensation steps
    UNWIND_RISK("UnwindTransactionRisk", false),
    UNWIND_FX("UnwindFx", false);

    private final String commandType;
    private final boolean waitOnly;

    PaymentStep(String commandType, boolean waitOnly) {
        this.commandType = commandType;
        this.waitOnly = waitOnly;
    }

    /**
     * Get the command type to send for this step.
     *
     * @return Command type, or null for wait-only steps
     */
    public String getCommandType() {
        return commandType;
    }

    /**
     * Check if this is a wait-only step.
     * Wait-only steps don't send commands - they wait for external replies.
     *
     * @return true if this step is wait-only
     */
    public boolean isWaitOnly() {
        return waitOnly;
    }
}
