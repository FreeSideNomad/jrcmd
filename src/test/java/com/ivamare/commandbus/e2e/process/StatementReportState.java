package com.ivamare.commandbus.e2e.process;

import com.ivamare.commandbus.process.ProcessState;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * State for statement report generation process.
 *
 * <p>Tracks the progress through the 3-step workflow and stores
 * intermediate results from each step.
 */
public record StatementReportState(
    LocalDate fromDate,
    LocalDate toDate,
    List<String> accountList,
    OutputType outputType,
    String queryResultPath,       // Set after QUERY step
    String aggregatedDataPath,    // Set after AGGREGATION step
    String renderedFilePath,      // Set after RENDER step
    StepBehavior behavior         // Per-step behavior configuration
) implements ProcessState {

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("from_date", fromDate != null ? fromDate.toString() : null);
        map.put("to_date", toDate != null ? toDate.toString() : null);
        map.put("account_list", accountList);
        map.put("output_type", outputType != null ? outputType.name() : null);
        map.put("query_result_path", queryResultPath);
        map.put("aggregated_data_path", aggregatedDataPath);
        map.put("rendered_file_path", renderedFilePath);
        if (behavior != null) {
            map.put("behavior", behavior.toMap());
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    public static StatementReportState fromMap(Map<String, Object> map) {
        LocalDate fromDate = map.get("from_date") != null
            ? LocalDate.parse((String) map.get("from_date")) : null;
        LocalDate toDate = map.get("to_date") != null
            ? LocalDate.parse((String) map.get("to_date")) : null;
        List<String> accountList = (List<String>) map.get("account_list");
        OutputType outputType = map.get("output_type") != null
            ? OutputType.valueOf((String) map.get("output_type")) : null;
        String queryResultPath = (String) map.get("query_result_path");
        String aggregatedDataPath = (String) map.get("aggregated_data_path");
        String renderedFilePath = (String) map.get("rendered_file_path");
        StepBehavior behavior = map.get("behavior") != null
            ? StepBehavior.fromMap((Map<String, Object>) map.get("behavior")) : null;

        return new StatementReportState(
            fromDate, toDate, accountList, outputType,
            queryResultPath, aggregatedDataPath, renderedFilePath,
            behavior
        );
    }

    /**
     * Create a new state with updated query result path.
     */
    public StatementReportState withQueryResultPath(String path) {
        return new StatementReportState(
            fromDate, toDate, accountList, outputType,
            path, aggregatedDataPath, renderedFilePath, behavior
        );
    }

    /**
     * Create a new state with updated aggregated data path.
     */
    public StatementReportState withAggregatedDataPath(String path) {
        return new StatementReportState(
            fromDate, toDate, accountList, outputType,
            queryResultPath, path, renderedFilePath, behavior
        );
    }

    /**
     * Create a new state with updated rendered file path.
     */
    public StatementReportState withRenderedFilePath(String path) {
        return new StatementReportState(
            fromDate, toDate, accountList, outputType,
            queryResultPath, aggregatedDataPath, path, behavior
        );
    }
}
