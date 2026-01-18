package com.ivamare.commandbus.e2e.payment;

import com.ivamare.commandbus.e2e.process.ProbabilisticBehavior;

import java.util.HashMap;
import java.util.Map;

/**
 * Special behavior configuration for BookTransactionRisk step.
 *
 * <p>Risk assessment returns a type (AVAILABLE_BALANCE or DAILY_LIMIT) and
 * a decision (APPROVED, DECLINED, or PENDING):
 * <ul>
 *   <li>APPROVED - immediate approval, payment can proceed</li>
 *   <li>DECLINED - immediate decline, payment fails</li>
 *   <li>PENDING - requires manual review, operator must approve/decline in UI</li>
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
     * Risk type - the method used for assessment.
     */
    public enum RiskType {
        AVAILABLE_BALANCE,
        DAILY_LIMIT
    }

    /**
     * Risk decision - the outcome of the assessment.
     */
    public enum RiskDecision {
        APPROVED,
        DECLINED,
        PENDING
    }

    /**
     * Result of risk assessment containing both type and decision.
     */
    public record RiskAssessmentResult(RiskType type, RiskDecision decision) {
        public boolean isApproved() {
            return decision == RiskDecision.APPROVED;
        }
        public boolean isDeclined() {
            return decision == RiskDecision.DECLINED;
        }
        public boolean isPending() {
            return decision == RiskDecision.PENDING;
        }
    }
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
     * Assess the risk and return both type and decision.
     *
     * @param roll Random value between 0 and 100
     * @return RiskAssessmentResult with type and decision
     */
    public RiskAssessmentResult assess(double roll) {
        double cumulative = 0;

        // Approved via AVAILABLE_BALANCE
        cumulative += approvedBalancePct;
        if (roll < cumulative) {
            return new RiskAssessmentResult(RiskType.AVAILABLE_BALANCE, RiskDecision.APPROVED);
        }

        // Approved via DAILY_LIMIT
        cumulative += approvedLimitPct;
        if (roll < cumulative) {
            return new RiskAssessmentResult(RiskType.DAILY_LIMIT, RiskDecision.APPROVED);
        }

        // Pending manual review (use DAILY_LIMIT as type since auto-approval failed)
        cumulative += manualApprovalPct;
        if (roll < cumulative) {
            return new RiskAssessmentResult(RiskType.DAILY_LIMIT, RiskDecision.PENDING);
        }

        // Declined outright
        cumulative += declinedPct;
        if (roll < cumulative) {
            return new RiskAssessmentResult(RiskType.DAILY_LIMIT, RiskDecision.DECLINED);
        }

        // Default to approval if percentages don't add to 100%
        return new RiskAssessmentResult(RiskType.AVAILABLE_BALANCE, RiskDecision.APPROVED);
    }

    /**
     * Determine the approval method based on random roll.
     *
     * @param roll Random value between 0 and 100
     * @return Approval method or null if declined
     * @deprecated Use {@link #assess(double)} instead for full type and decision info
     */
    @Deprecated
    public String determineApprovalMethod(double roll) {
        RiskAssessmentResult result = assess(roll);
        if (result.isDeclined()) {
            return null;
        }
        if (result.isPending()) {
            return "MANUAL";
        }
        return result.type().name();
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
