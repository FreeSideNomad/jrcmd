package com.ivamare.commandbus.e2e.payment;

import com.ivamare.commandbus.e2e.process.ProbabilisticBehavior;

import java.util.HashMap;
import java.util.Map;

/**
 * Special behavior configuration for BookTransactionRisk step.
 *
 * <p>Risk assessment can result in:
 * <ul>
 *   <li>Immediate approval via AVAILABLE_BALANCE check</li>
 *   <li>Immediate approval via DAILY_LIMIT check</li>
 *   <li>Manual approval (delayed response, simulates human review)</li>
 *   <li>Decline (triggers compensation)</li>
 * </ul>
 */
public record RiskBehavior(
    double approvedBalancePct,     // % approved via AVAILABLE_BALANCE
    double approvedLimitPct,       // % approved via DAILY_LIMIT
    double manualApprovalPct,      // % requiring manual review (operator must approve in UI)
    double declinedPct,            // % declined outright
    int minDurationMs,             // Min processing time for risk check
    int maxDurationMs              // Max processing time for risk check
) {
    /**
     * Default behavior - mostly approved via balance check.
     */
    public static RiskBehavior defaults() {
        return new RiskBehavior(70, 20, 5, 5, 50, 200);
    }

    /**
     * Convert to ProbabilisticBehavior for standard processing.
     * The declinedPct maps to failBusinessRulePct (triggers compensation).
     */
    public ProbabilisticBehavior toProbabilistic() {
        return new ProbabilisticBehavior(
            0,              // No permanent failures
            0,              // No transient failures
            declinedPct,    // Declined = business rule failure
            0,              // No timeouts
            minDurationMs,
            maxDurationMs
        );
    }

    /**
     * Determine the approval method based on random roll.
     *
     * @param roll Random value between 0 and 100
     * @return Approval method or null if declined
     */
    public String determineApprovalMethod(double roll) {
        double cumulative = 0;

        cumulative += approvedBalancePct;
        if (roll < cumulative) {
            return "AVAILABLE_BALANCE";
        }

        cumulative += approvedLimitPct;
        if (roll < cumulative) {
            return "DAILY_LIMIT";
        }

        cumulative += manualApprovalPct;
        if (roll < cumulative) {
            return "MANUAL";
        }

        // Only decline if declinedPct is actually set
        // If percentages don't add to 100% and declinedPct is 0, default to balance approval
        cumulative += declinedPct;
        if (roll < cumulative) {
            return null;  // Declined
        }

        // If we get here, percentages didn't add to 100% - default to approval
        // This handles cases where user sets declinedPct=0 but other values don't sum to 100
        return "AVAILABLE_BALANCE";
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("approved_balance_pct", approvedBalancePct);
        map.put("approved_limit_pct", approvedLimitPct);
        map.put("manual_approval_pct", manualApprovalPct);
        map.put("declined_pct", declinedPct);
        map.put("min_duration_ms", minDurationMs);
        map.put("max_duration_ms", maxDurationMs);
        return map;
    }

    public static RiskBehavior fromMap(Map<String, Object> map) {
        if (map == null) return defaults();
        return new RiskBehavior(
            toDouble(map.get("approved_balance_pct"), 70),
            toDouble(map.get("approved_limit_pct"), 20),
            toDouble(map.get("manual_approval_pct"), 5),
            toDouble(map.get("declined_pct"), 5),
            toInt(map.get("min_duration_ms"), 50),
            toInt(map.get("max_duration_ms"), 200)
        );
    }

    private static double toDouble(Object value, double defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof Number n) return n.doubleValue();
        return Double.parseDouble(value.toString());
    }

    private static int toInt(Object value, int defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof Number n) return n.intValue();
        return Integer.parseInt(value.toString());
    }
}
