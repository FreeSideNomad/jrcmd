package com.commandbus.exception;

/**
 * Thrown when a batch cannot be found.
 */
public class BatchNotFoundException extends CommandBusException {

    private final String domain;
    private final String batchId;

    public BatchNotFoundException(String domain, String batchId) {
        super("Batch " + batchId + " not found in domain " + domain);
        this.domain = domain;
        this.batchId = batchId;
    }

    public String getDomain() {
        return domain;
    }

    public String getBatchId() {
        return batchId;
    }
}
