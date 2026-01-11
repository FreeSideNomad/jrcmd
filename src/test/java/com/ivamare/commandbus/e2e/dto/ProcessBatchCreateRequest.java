package com.ivamare.commandbus.e2e.dto;

import com.ivamare.commandbus.e2e.process.OutputType;
import com.ivamare.commandbus.e2e.process.ProbabilisticBehavior;
import com.ivamare.commandbus.e2e.process.StepBehavior;

import java.time.LocalDate;

/**
 * Request to create a batch of statement report processes.
 */
public record ProcessBatchCreateRequest(
    String name,
    int count,
    LocalDate fromDate,
    LocalDate toDate,
    int accountCount,
    OutputType outputType,
    StepBehavior behavior
) {
    /**
     * Default request for creating a small test batch.
     */
    public static ProcessBatchCreateRequest defaults() {
        return new ProcessBatchCreateRequest(
            "Test Process Batch",
            10,
            LocalDate.now().minusMonths(1),
            LocalDate.now(),
            5,
            OutputType.PDF,
            StepBehavior.defaults()
        );
    }

    /**
     * Helper to create behavior from form parameters.
     */
    public static StepBehavior createBehavior(
            double queryFailPerm, double queryFailTrans, double queryFailBiz, double queryTimeout, int queryMinMs, int queryMaxMs,
            double aggFailPerm, double aggFailTrans, double aggFailBiz, double aggTimeout, int aggMinMs, int aggMaxMs,
            double renderFailPerm, double renderFailTrans, double renderFailBiz, double renderTimeout, int renderMinMs, int renderMaxMs) {
        return new StepBehavior(
            new ProbabilisticBehavior(queryFailPerm, queryFailTrans, queryFailBiz, queryTimeout, queryMinMs, queryMaxMs),
            new ProbabilisticBehavior(aggFailPerm, aggFailTrans, aggFailBiz, aggTimeout, aggMinMs, aggMaxMs),
            new ProbabilisticBehavior(renderFailPerm, renderFailTrans, renderFailBiz, renderTimeout, renderMinMs, renderMaxMs)
        );
    }
}
