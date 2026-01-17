package com.ivamare.commandbus.e2e.controller;

import com.ivamare.commandbus.e2e.dto.PaymentBatchCreateRequest;
import com.ivamare.commandbus.e2e.dto.PaymentCreateRequest;
import com.ivamare.commandbus.e2e.payment.*;
import com.ivamare.commandbus.e2e.process.ProbabilisticBehavior;
import com.ivamare.commandbus.e2e.service.E2EService;
import com.ivamare.commandbus.process.ProcessStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Controller for payment management UI.
 */
@Controller
@RequestMapping("/payments")
public class PaymentController {

    private final E2EService e2eService;

    public PaymentController(E2EService e2eService) {
        this.e2eService = e2eService;
    }

    @GetMapping
    public String listPayments(
            @RequestParam(required = false) PaymentStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            Model model) {

        var payments = e2eService.getPayments(status, size, page * size);

        model.addAttribute("payments", payments);
        model.addAttribute("status", status);
        model.addAttribute("statuses", PaymentStatus.values());
        model.addAttribute("page", page);
        model.addAttribute("size", size);

        return "pages/payments";
    }

    @GetMapping("/new")
    public String newPaymentForm(Model model) {
        model.addAttribute("request", PaymentCreateRequest.defaults());
        model.addAttribute("currencies", Currency.values());
        return "pages/payment_new";
    }

    @PostMapping
    public String createPayment(
            @RequestParam String debitTransit,
            @RequestParam String debitAccountNumber,
            @RequestParam String creditBic,
            @RequestParam String creditIban,
            @RequestParam BigDecimal debitAmount,
            @RequestParam String debitCurrency,
            @RequestParam String creditCurrency,
            @RequestParam String valueDate,
            @RequestParam(defaultValue = "24") int cutoffHours,
            @RequestParam(defaultValue = "COMMAND_BASED") String executionModel,
            RedirectAttributes redirectAttributes) {

        try {
            Payment payment = Payment.builder()
                .debitAccount(DebitAccount.of(debitTransit, debitAccountNumber))
                .creditAccount(CreditAccount.of(creditBic, creditIban))
                .debitAmount(debitAmount)
                .debitCurrency(Currency.valueOf(debitCurrency))
                .creditCurrency(Currency.valueOf(creditCurrency))
                .valueDate(LocalDate.parse(valueDate))
                .cutoffTimestamp(Instant.now().plus(cutoffHours, ChronoUnit.HOURS))
                .build();

            UUID processId = e2eService.createPayment(payment, PaymentStepBehavior.defaults(), executionModel);

            redirectAttributes.addFlashAttribute("success", "Payment created with " + executionModel + " execution model");
            return "redirect:/payments/" + payment.paymentId();

        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", "Invalid input: " + e.getMessage());
            return "redirect:/payments/new";
        }
    }

    @GetMapping("/{paymentId}")
    public String paymentDetail(
            @PathVariable UUID paymentId,
            Model model) {

        var paymentView = e2eService.getPaymentById(paymentId);
        if (paymentView.isEmpty()) {
            return "redirect:/payments?error=notfound";
        }

        var auditTrail = e2eService.getPaymentAuditTrail(paymentId);

        model.addAttribute("payment", paymentView.get());
        model.addAttribute("auditTrail", auditTrail);
        model.addAttribute("steps", PaymentStep.values());

        return "pages/payment_detail";
    }

    @GetMapping("/batches")
    public String listPaymentBatches(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            Model model) {

        var batches = e2eService.getPaymentBatches(size, page * size);
        boolean hasInProgress = batches.stream().anyMatch(b -> b.inProgressCount() > 0);

        model.addAttribute("batches", batches);
        model.addAttribute("hasInProgress", hasInProgress);
        model.addAttribute("page", page);
        model.addAttribute("size", size);

        return "pages/payment_batches";
    }

    @GetMapping("/batch/new")
    public String newPaymentBatchForm(Model model) {
        model.addAttribute("request", PaymentBatchCreateRequest.defaults());
        model.addAttribute("currencies", Currency.values());
        return "pages/payment_batch_new";
    }

    @PostMapping("/batch")
    public String createPaymentBatch(
            @RequestParam String name,
            @RequestParam(defaultValue = "10") int count,
            @RequestParam(defaultValue = "100") BigDecimal minAmount,
            @RequestParam(defaultValue = "10000") BigDecimal maxAmount,
            @RequestParam String debitCurrency,
            @RequestParam String creditCurrency,
            @RequestParam String valueDate,
            @RequestParam(defaultValue = "24") int cutoffHours,
            @RequestParam(defaultValue = "STEP_BASED") String executionModel,
            // Risk behavior
            @RequestParam(defaultValue = "70") double riskApprovedBalancePct,
            @RequestParam(defaultValue = "20") double riskApprovedLimitPct,
            @RequestParam(defaultValue = "5") double riskManualApprovalPct,
            @RequestParam(defaultValue = "5") double riskDeclinedPct,
            @RequestParam(defaultValue = "50") int riskMinMs,
            @RequestParam(defaultValue = "200") int riskMaxMs,
            // FX behavior
            @RequestParam(defaultValue = "0") double fxFailPerm,
            @RequestParam(defaultValue = "0") double fxFailTrans,
            @RequestParam(defaultValue = "0") double fxFailBiz,
            @RequestParam(defaultValue = "0") double fxTimeout,
            @RequestParam(defaultValue = "10") int fxMinMs,
            @RequestParam(defaultValue = "100") int fxMaxMs,
            // Submit behavior
            @RequestParam(defaultValue = "0") double submitFailPerm,
            @RequestParam(defaultValue = "0") double submitFailTrans,
            @RequestParam(defaultValue = "0") double submitFailBiz,
            @RequestParam(defaultValue = "0") double submitTimeout,
            @RequestParam(defaultValue = "10") int submitMinMs,
            @RequestParam(defaultValue = "100") int submitMaxMs,
            // L1 behavior
            @RequestParam(defaultValue = "0") double l1FailPerm,
            @RequestParam(defaultValue = "0") double l1FailTrans,
            @RequestParam(defaultValue = "0") double l1FailBiz,
            @RequestParam(defaultValue = "0") double l1Timeout,
            @RequestParam(defaultValue = "10") int l1MinMs,
            @RequestParam(defaultValue = "100") int l1MaxMs,
            // L2 behavior
            @RequestParam(defaultValue = "0") double l2FailPerm,
            @RequestParam(defaultValue = "0") double l2FailTrans,
            @RequestParam(defaultValue = "0") double l2FailBiz,
            @RequestParam(defaultValue = "0") double l2Timeout,
            @RequestParam(defaultValue = "10") int l2MinMs,
            @RequestParam(defaultValue = "100") int l2MaxMs,
            // L3 behavior
            @RequestParam(defaultValue = "0") double l3FailPerm,
            @RequestParam(defaultValue = "0") double l3FailTrans,
            @RequestParam(defaultValue = "0") double l3FailBiz,
            @RequestParam(defaultValue = "0") double l3Timeout,
            @RequestParam(defaultValue = "0") double l3Pending,
            @RequestParam(defaultValue = "10") int l3MinMs,
            @RequestParam(defaultValue = "100") int l3MaxMs,
            // L4 behavior
            @RequestParam(defaultValue = "0") double l4FailPerm,
            @RequestParam(defaultValue = "0") double l4FailTrans,
            @RequestParam(defaultValue = "0") double l4FailBiz,
            @RequestParam(defaultValue = "0") double l4Timeout,
            @RequestParam(defaultValue = "0") double l4Pending,
            @RequestParam(defaultValue = "10") int l4MinMs,
            @RequestParam(defaultValue = "100") int l4MaxMs,
            RedirectAttributes redirectAttributes) {

        PaymentStepBehavior behavior = PaymentBatchCreateRequest.createBehavior(
            riskApprovedBalancePct, riskApprovedLimitPct, riskManualApprovalPct, riskDeclinedPct, riskMinMs, riskMaxMs,
            fxFailPerm, fxFailTrans, fxFailBiz, fxTimeout, fxMinMs, fxMaxMs,
            submitFailPerm, submitFailTrans, submitFailBiz, submitTimeout, submitMinMs, submitMaxMs,
            l1FailPerm, l1FailTrans, l1FailBiz, l1Timeout, l1MinMs, l1MaxMs,
            l2FailPerm, l2FailTrans, l2FailBiz, l2Timeout, l2MinMs, l2MaxMs,
            l3FailPerm, l3FailTrans, l3FailBiz, l3Timeout, l3Pending, l3MinMs, l3MaxMs,
            l4FailPerm, l4FailTrans, l4FailBiz, l4Timeout, l4Pending, l4MinMs, l4MaxMs
        );

        PaymentBatchCreateRequest request = new PaymentBatchCreateRequest(
            name,
            count,
            minAmount,
            maxAmount,
            Currency.valueOf(debitCurrency),
            Currency.valueOf(creditCurrency),
            LocalDate.parse(valueDate),
            cutoffHours,
            executionModel,
            behavior
        );

        UUID batchId = e2eService.createPaymentBatch(request);
        redirectAttributes.addFlashAttribute("success", "Payment batch created with " + count + " payments");
        return "redirect:/payments/batch/" + batchId;
    }

    @GetMapping("/batch/{batchId}")
    public String paymentBatchDetail(
            @PathVariable UUID batchId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            Model model) {

        var batch = e2eService.getPaymentBatchById(batchId);
        if (batch.isEmpty()) {
            return "redirect:/payments?error=batchnotfound";
        }

        var payments = e2eService.getPaymentsByBatchId(batchId, status, size, page * size);
        var stats = e2eService.getPaymentBatchStats(batchId);

        model.addAttribute("batch", batch.get());
        model.addAttribute("payments", payments);
        model.addAttribute("stats", stats);
        model.addAttribute("status", status);
        // Display statuses for filtering: PROCESSING, COMPLETE, FAILED, CANCELLED, NEEDS_ATTENTION
        model.addAttribute("statuses", java.util.List.of("PROCESSING", "COMPLETE", "FAILED", "CANCELLED", "NEEDS_ATTENTION"));
        model.addAttribute("page", page);
        model.addAttribute("size", size);

        return "pages/payment_batch_detail";
    }
}
