package com.ivamare.commandbus.model;

import java.util.List;
import java.util.UUID;

/**
 * Result of creating a batch with commands.
 *
 * @param batchId The unique ID of the created batch
 * @param commandResults Individual results for each command sent
 * @param totalCommands Total number of commands in the batch
 */
public record CreateBatchResult(
    UUID batchId,
    List<SendResult> commandResults,
    int totalCommands
) {
    /**
     * Creates a CreateBatchResult with immutable results list.
     */
    public CreateBatchResult {
        commandResults = List.copyOf(commandResults);
    }
}
