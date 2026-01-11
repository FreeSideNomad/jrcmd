package com.ivamare.commandbus.e2e.process;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-step behavior configuration for statement report process.
 *
 * <p>Allows configuring different failure rates and durations for each
 * step in the workflow (Query, Aggregation, Render).
 */
public record StepBehavior(
    ProbabilisticBehavior query,
    ProbabilisticBehavior aggregation,
    ProbabilisticBehavior render
) {

    /**
     * Default behavior - all steps succeed with default durations.
     */
    public static StepBehavior defaults() {
        return new StepBehavior(
            ProbabilisticBehavior.defaults(),
            ProbabilisticBehavior.defaults(),
            ProbabilisticBehavior.defaults()
        );
    }

    /**
     * Get behavior for a specific step.
     */
    public ProbabilisticBehavior forStep(StatementReportStep step) {
        return switch (step) {
            case QUERY -> query != null ? query : ProbabilisticBehavior.defaults();
            case AGGREGATION -> aggregation != null ? aggregation : ProbabilisticBehavior.defaults();
            case RENDER -> render != null ? render : ProbabilisticBehavior.defaults();
        };
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        if (query != null) map.put("query", query.toMap());
        if (aggregation != null) map.put("aggregation", aggregation.toMap());
        if (render != null) map.put("render", render.toMap());
        return map;
    }

    @SuppressWarnings("unchecked")
    public static StepBehavior fromMap(Map<String, Object> map) {
        if (map == null) return defaults();
        return new StepBehavior(
            map.get("query") != null
                ? ProbabilisticBehavior.fromMap((Map<String, Object>) map.get("query")) : null,
            map.get("aggregation") != null
                ? ProbabilisticBehavior.fromMap((Map<String, Object>) map.get("aggregation")) : null,
            map.get("render") != null
                ? ProbabilisticBehavior.fromMap((Map<String, Object>) map.get("render")) : null
        );
    }
}
