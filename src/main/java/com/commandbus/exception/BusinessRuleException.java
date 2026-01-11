package com.commandbus.exception;

import java.util.Map;

/**
 * Raised for business rule violations that should fail immediately without TSQ.
 *
 * <p>When a handler throws this exception:
 * <ul>
 *   <li>Command status → FAILED (bypasses TSQ)</li>
 *   <li>Reply sent with outcome=FAILED, errorType=BUSINESS_RULE</li>
 *   <li>Process auto-compensates (no operator intervention)</li>
 * </ul>
 *
 * <p>Use cases:
 * <ul>
 *   <li>Account closed before statement generation</li>
 *   <li>Invalid date range in report request</li>
 *   <li>Missing required data that won't appear later</li>
 *   <li>Business validation rules that cannot be overridden</li>
 * </ul>
 */
public class BusinessRuleException extends CommandBusException {

    private final String code;
    private final String errorMessage;
    private final Map<String, Object> details;

    public BusinessRuleException(String code, String message) {
        this(code, message, Map.of());
    }

    public BusinessRuleException(String code, String message, Map<String, Object> details) {
        super("[" + code + "] " + message);
        this.code = code;
        this.errorMessage = message;
        this.details = details != null ? Map.copyOf(details) : Map.of();
    }

    public String getCode() {
        return code;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Map<String, Object> getDetails() {
        return details;
    }
}
