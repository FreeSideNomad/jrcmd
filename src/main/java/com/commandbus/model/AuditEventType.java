package com.commandbus.model;

/**
 * Standard audit event types.
 */
public final class AuditEventType {
    private AuditEventType() {}

    /** Command sent to queue */
    public static final String SENT = "SENT";

    /** Command received by worker */
    public static final String RECEIVED = "RECEIVED";

    /** Command completed successfully */
    public static final String COMPLETED = "COMPLETED";

    /** Command failed (transient or permanent) */
    public static final String FAILED = "FAILED";

    /** Command failed due to business rule violation (bypasses TSQ) */
    public static final String BUSINESS_RULE_FAILED = "BUSINESS_RULE_FAILED";

    /** Command scheduled for retry */
    public static final String RETRY_SCHEDULED = "RETRY_SCHEDULED";

    /** Command moved to troubleshooting queue */
    public static final String MOVED_TO_TSQ = "MOVED_TO_TSQ";

    /** Operator initiated retry from TSQ */
    public static final String OPERATOR_RETRY = "OPERATOR_RETRY";

    /** Operator canceled command from TSQ */
    public static final String OPERATOR_CANCEL = "OPERATOR_CANCEL";

    /** Operator marked command complete from TSQ */
    public static final String OPERATOR_COMPLETE = "OPERATOR_COMPLETE";

    /** Batch processing started */
    public static final String BATCH_STARTED = "BATCH_STARTED";

    /** Batch processing completed */
    public static final String BATCH_COMPLETED = "BATCH_COMPLETED";
}
