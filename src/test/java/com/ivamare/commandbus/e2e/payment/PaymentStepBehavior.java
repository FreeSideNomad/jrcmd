package com.ivamare.commandbus.e2e.payment;

import com.ivamare.commandbus.e2e.process.ProbabilisticBehavior;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-step behavior configuration for payment processing.
 *
 * <p>Allows configuring failure rates, durations, and special behaviors
 * for each step in the payment workflow.
 */
public record PaymentStepBehavior(
    RiskBehavior bookRisk,
    ProbabilisticBehavior bookFx,
    ProbabilisticBehavior submitPayment,
    ProbabilisticBehavior awaitL1,
    ProbabilisticBehavior awaitL2,
    ProbabilisticBehavior awaitL3,
    ProbabilisticBehavior awaitL4
) {
    /**
     * Default behavior - all steps succeed with minimal delays.
     */
    public static PaymentStepBehavior defaults() {
        return new PaymentStepBehavior(
            RiskBehavior.defaults(),
            ProbabilisticBehavior.defaults(),
            ProbabilisticBehavior.defaults(),
            ProbabilisticBehavior.defaults(),
            ProbabilisticBehavior.defaults(),
            ProbabilisticBehavior.defaults(),
            ProbabilisticBehavior.defaults()
        );
    }

    /**
     * Get the probabilistic behavior for a given step.
     */
    public ProbabilisticBehavior forStep(PaymentStep step) {
        return switch (step) {
            case BOOK_RISK -> bookRisk.toProbabilistic();
            case BOOK_FX -> bookFx;
            case SUBMIT_PAYMENT -> submitPayment;
            case AWAIT_RISK_APPROVAL -> ProbabilisticBehavior.defaults();  // Wait-only, no behavior needed
            case AWAIT_CONFIRMATIONS -> ProbabilisticBehavior.defaults();  // L1-L4 behaviors used by simulator
            case UNWIND_RISK, UNWIND_FX -> ProbabilisticBehavior.defaults();
        };
    }

    /**
     * Get the probabilistic behavior for a specific network confirmation level.
     * Used by PaymentNetworkSimulator to determine per-level delays and failure rates.
     */
    public ProbabilisticBehavior forLevel(int level) {
        return switch (level) {
            case 1 -> awaitL1;
            case 2 -> awaitL2;
            case 3 -> awaitL3;
            case 4 -> awaitL4;
            default -> ProbabilisticBehavior.defaults();
        };
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("book_risk", bookRisk != null ? bookRisk.toMap() : null);
        map.put("book_fx", bookFx != null ? bookFx.toMap() : null);
        map.put("submit_payment", submitPayment != null ? submitPayment.toMap() : null);
        map.put("await_l1", awaitL1 != null ? awaitL1.toMap() : null);
        map.put("await_l2", awaitL2 != null ? awaitL2.toMap() : null);
        map.put("await_l3", awaitL3 != null ? awaitL3.toMap() : null);
        map.put("await_l4", awaitL4 != null ? awaitL4.toMap() : null);
        return map;
    }

    @SuppressWarnings("unchecked")
    public static PaymentStepBehavior fromMap(Map<String, Object> map) {
        if (map == null) return defaults();
        return new PaymentStepBehavior(
            RiskBehavior.fromMap((Map<String, Object>) map.get("book_risk")),
            ProbabilisticBehavior.fromMap((Map<String, Object>) map.get("book_fx")),
            ProbabilisticBehavior.fromMap((Map<String, Object>) map.get("submit_payment")),
            ProbabilisticBehavior.fromMap((Map<String, Object>) map.get("await_l1")),
            ProbabilisticBehavior.fromMap((Map<String, Object>) map.get("await_l2")),
            ProbabilisticBehavior.fromMap((Map<String, Object>) map.get("await_l3")),
            ProbabilisticBehavior.fromMap((Map<String, Object>) map.get("await_l4"))
        );
    }

    /**
     * Builder for creating custom PaymentStepBehavior instances.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private RiskBehavior bookRisk = RiskBehavior.defaults();
        private ProbabilisticBehavior bookFx = ProbabilisticBehavior.defaults();
        private ProbabilisticBehavior submitPayment = ProbabilisticBehavior.defaults();
        private ProbabilisticBehavior awaitL1 = ProbabilisticBehavior.defaults();
        private ProbabilisticBehavior awaitL2 = ProbabilisticBehavior.defaults();
        private ProbabilisticBehavior awaitL3 = ProbabilisticBehavior.defaults();
        private ProbabilisticBehavior awaitL4 = ProbabilisticBehavior.defaults();

        public Builder bookRisk(RiskBehavior behavior) {
            this.bookRisk = behavior;
            return this;
        }

        public Builder bookFx(ProbabilisticBehavior behavior) {
            this.bookFx = behavior;
            return this;
        }

        public Builder submitPayment(ProbabilisticBehavior behavior) {
            this.submitPayment = behavior;
            return this;
        }

        public Builder awaitL1(ProbabilisticBehavior behavior) {
            this.awaitL1 = behavior;
            return this;
        }

        public Builder awaitL2(ProbabilisticBehavior behavior) {
            this.awaitL2 = behavior;
            return this;
        }

        public Builder awaitL3(ProbabilisticBehavior behavior) {
            this.awaitL3 = behavior;
            return this;
        }

        public Builder awaitL4(ProbabilisticBehavior behavior) {
            this.awaitL4 = behavior;
            return this;
        }

        public PaymentStepBehavior build() {
            return new PaymentStepBehavior(
                bookRisk, bookFx, submitPayment,
                awaitL1, awaitL2, awaitL3, awaitL4
            );
        }
    }
}
