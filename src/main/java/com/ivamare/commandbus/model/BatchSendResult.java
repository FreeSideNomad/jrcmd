package com.ivamare.commandbus.model;

import java.util.List;

/**
 * Result of a batch send operation.
 *
 * @param results Individual results for each command sent
 * @param chunksProcessed Number of transaction chunks processed
 * @param totalCommands Total number of commands sent
 */
public record BatchSendResult(
    List<SendResult> results,
    int chunksProcessed,
    int totalCommands
) {
    /**
     * Creates a BatchSendResult with immutable results list.
     */
    public BatchSendResult {
        results = List.copyOf(results);
    }
}
