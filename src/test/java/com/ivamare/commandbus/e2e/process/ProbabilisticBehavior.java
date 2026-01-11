package com.ivamare.commandbus.e2e.process;

import java.util.HashMap;
import java.util.Map;

/**
 * Probabilistic behavior configuration for command handlers.
 *
 * <p>Handlers evaluate probabilities in order:
 * <ol>
 *   <li>failPermanentPct - chance of permanent failure</li>
 *   <li>failTransientPct - chance of transient failure</li>
 *   <li>failBusinessRulePct - chance of business rule violation</li>
 *   <li>timeoutPct - chance of timeout</li>
 *   <li>Otherwise - success with duration in range</li>
 * </ol>
 */
public record ProbabilisticBehavior(
    double failPermanentPct,
    double failTransientPct,
    double failBusinessRulePct,
    double timeoutPct,
    int minDurationMs,
    int maxDurationMs
) {

    /**
     * Default behavior - always succeeds with 10-100ms duration.
     */
    public static ProbabilisticBehavior defaults() {
        return new ProbabilisticBehavior(0, 0, 0, 0, 10, 100);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("fail_permanent_pct", failPermanentPct);
        map.put("fail_transient_pct", failTransientPct);
        map.put("fail_business_rule_pct", failBusinessRulePct);
        map.put("timeout_pct", timeoutPct);
        map.put("min_duration_ms", minDurationMs);
        map.put("max_duration_ms", maxDurationMs);
        return map;
    }

    public static ProbabilisticBehavior fromMap(Map<String, Object> map) {
        if (map == null) return defaults();
        return new ProbabilisticBehavior(
            toDouble(map.get("fail_permanent_pct")),
            toDouble(map.get("fail_transient_pct")),
            toDouble(map.get("fail_business_rule_pct")),
            toDouble(map.get("timeout_pct")),
            toInt(map.get("min_duration_ms"), 10),
            toInt(map.get("max_duration_ms"), 100)
        );
    }

    private static double toDouble(Object value) {
        if (value == null) return 0;
        if (value instanceof Number n) return n.doubleValue();
        return Double.parseDouble(value.toString());
    }

    private static int toInt(Object value, int defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof Number n) return n.intValue();
        return Integer.parseInt(value.toString());
    }
}
