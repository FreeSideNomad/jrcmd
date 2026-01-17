package com.ivamare.commandbus.e2e.dto;

import com.ivamare.commandbus.e2e.payment.Currency;
import com.ivamare.commandbus.e2e.payment.PaymentStepBehavior;
import com.ivamare.commandbus.e2e.payment.RiskBehavior;
import com.ivamare.commandbus.e2e.process.ProbabilisticBehavior;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Request DTO for creating a batch of payments.
 *
 * @param executionModel Execution model: "STEP_BASED" (BaseProcessManager) or "PROCESS_STEP" (ProcessStepManager)
 */
public record PaymentBatchCreateRequest(
    String name,
    int count,
    BigDecimal minAmount,
    BigDecimal maxAmount,
    Currency debitCurrency,
    Currency creditCurrency,
    LocalDate valueDate,
    int cutoffHours,
    String executionModel,
    PaymentStepBehavior behavior
) {
    /**
     * Default request with sample values.
     */
    public static PaymentBatchCreateRequest defaults() {
        return new PaymentBatchCreateRequest(
            "Payment Batch",
            10,
            new BigDecimal("100.00"),
            new BigDecimal("10000.00"),
            Currency.USD,
            Currency.EUR,
            LocalDate.now().plusDays(1),
            24,
            "STEP_BASED",
            PaymentStepBehavior.defaults()
        );
    }

    /**
     * Check if using PROCESS_STEP execution model.
     */
    public boolean isProcessStepModel() {
        return "PROCESS_STEP".equals(executionModel);
    }

    /**
     * Create behavior from form parameters.
     */
    public static PaymentStepBehavior createBehavior(
            // Risk behavior
            double riskApprovedBalancePct,
            double riskApprovedLimitPct,
            double riskManualApprovalPct,
            double riskDeclinedPct,
            int riskMinMs,
            int riskMaxMs,
            // FX behavior
            double fxFailPerm,
            double fxFailTrans,
            double fxFailBiz,
            double fxTimeout,
            int fxMinMs,
            int fxMaxMs,
            // Submit behavior
            double submitFailPerm,
            double submitFailTrans,
            double submitFailBiz,
            double submitTimeout,
            int submitMinMs,
            int submitMaxMs,
            // L1-L4 behaviors (L3/L4 include pendingPct)
            double l1FailPerm, double l1FailTrans, double l1FailBiz, double l1Timeout, int l1MinMs, int l1MaxMs,
            double l2FailPerm, double l2FailTrans, double l2FailBiz, double l2Timeout, int l2MinMs, int l2MaxMs,
            double l3FailPerm, double l3FailTrans, double l3FailBiz, double l3Timeout, double l3Pending, int l3MinMs, int l3MaxMs,
            double l4FailPerm, double l4FailTrans, double l4FailBiz, double l4Timeout, double l4Pending, int l4MinMs, int l4MaxMs
    ) {
        return new PaymentStepBehavior(
            new RiskBehavior(riskApprovedBalancePct, riskApprovedLimitPct, riskManualApprovalPct, riskDeclinedPct, riskMinMs, riskMaxMs),
            new ProbabilisticBehavior(fxFailPerm, fxFailTrans, fxFailBiz, fxTimeout, fxMinMs, fxMaxMs),
            new ProbabilisticBehavior(submitFailPerm, submitFailTrans, submitFailBiz, submitTimeout, submitMinMs, submitMaxMs),
            new ProbabilisticBehavior(l1FailPerm, l1FailTrans, l1FailBiz, l1Timeout, l1MinMs, l1MaxMs),
            new ProbabilisticBehavior(l2FailPerm, l2FailTrans, l2FailBiz, l2Timeout, l2MinMs, l2MaxMs),
            new ProbabilisticBehavior(l3FailPerm, l3FailTrans, l3FailBiz, l3Timeout, l3Pending, l3MinMs, l3MaxMs),
            new ProbabilisticBehavior(l4FailPerm, l4FailTrans, l4FailBiz, l4Timeout, l4Pending, l4MinMs, l4MaxMs)
        );
    }
}
