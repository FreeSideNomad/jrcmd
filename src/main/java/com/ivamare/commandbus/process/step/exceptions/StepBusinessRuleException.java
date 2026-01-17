package com.ivamare.commandbus.process.step.exceptions;

/**
 * Exception thrown when a business rule is violated.
 *
 * <p>Business rule exceptions trigger automatic compensation (saga rollback)
 * rather than moving to the troubleshooting queue. This is used for
 * expected business failures like:
 * <ul>
 *   <li>Insufficient funds</li>
 *   <li>Risk declined</li>
 *   <li>Validation failures</li>
 *   <li>Business constraint violations</li>
 * </ul>
 */
public class StepBusinessRuleException extends RuntimeException {

    private final String errorCode;

    /**
     * Create a business rule exception.
     *
     * @param message Description of the business rule violation
     */
    public StepBusinessRuleException(String message) {
        super(message);
        this.errorCode = "BUSINESS_RULE_VIOLATION";
    }

    /**
     * Create a business rule exception with error code.
     *
     * @param errorCode Specific error code for the violation
     * @param message Description of the business rule violation
     */
    public StepBusinessRuleException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * Create a business rule exception with cause.
     *
     * @param message Description of the business rule violation
     * @param cause The underlying exception
     */
    public StepBusinessRuleException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = "BUSINESS_RULE_VIOLATION";
    }

    /**
     * Create a business rule exception with error code and cause.
     *
     * @param errorCode Specific error code for the violation
     * @param message Description of the business rule violation
     * @param cause The underlying exception
     */
    public StepBusinessRuleException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /**
     * Get the error code.
     */
    public String getErrorCode() {
        return errorCode;
    }
}
