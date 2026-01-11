package com.ivamare.commandbus.e2e.process;

/**
 * Steps in the statement report generation workflow.
 *
 * <p>Flow: QUERY → AGGREGATION → RENDER
 */
public enum StatementReportStep {
    QUERY("StatementQuery"),
    AGGREGATION("StatementDataAggregation"),
    RENDER("StatementRender");

    private final String commandType;

    StatementReportStep(String commandType) {
        this.commandType = commandType;
    }

    /**
     * Get the command type for this step.
     */
    public String getCommandType() {
        return commandType;
    }
}
