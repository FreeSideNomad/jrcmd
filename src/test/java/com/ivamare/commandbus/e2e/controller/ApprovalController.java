package com.ivamare.commandbus.e2e.controller;

import com.ivamare.commandbus.e2e.payment.*;
import com.ivamare.commandbus.e2e.payment.PendingApproval.ApprovalStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.UUID;

/**
 * Controller for managing pending approval queue.
 *
 * <p>Displays payments requiring manual approval and allows operators
 * to approve or reject them.
 */
@Controller
@RequestMapping("/approvals")
public class ApprovalController {

    private final PendingApprovalRepository pendingApprovalRepository;
    private final PaymentNetworkSimulator networkSimulator;
    private final String domain;

    public ApprovalController(
            PendingApprovalRepository pendingApprovalRepository,
            PaymentNetworkSimulator networkSimulator,
            @Value("${commandbus.domain:payments}") String domain) {
        this.pendingApprovalRepository = pendingApprovalRepository;
        this.networkSimulator = networkSimulator;
        this.domain = domain;
    }

    @GetMapping
    public String listApprovals(Model model) {
        List<PendingApproval> approvals = pendingApprovalRepository.findAllPending();
        int totalCount = pendingApprovalRepository.countPending();

        model.addAttribute("approvals", approvals);
        model.addAttribute("totalCount", totalCount);
        model.addAttribute("domain", domain);
        return "pages/approvals";
    }

    @PostMapping("/{id}/approve")
    public String approve(
            @PathVariable UUID id,
            @RequestParam(required = false) String notes,
            RedirectAttributes redirectAttributes) {
        try {
            PendingApproval approval = pendingApprovalRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Approval not found: " + id));

            if (!approval.isPending()) {
                redirectAttributes.addFlashAttribute("error", "Approval already processed");
                return "redirect:/approvals";
            }

            // Update status
            PendingApproval resolved = approval.withResolution(ApprovalStatus.APPROVED, "operator", notes);
            pendingApprovalRepository.update(resolved);

            // Send approval reply to process
            networkSimulator.sendApprovalApprovedReply(approval.commandId(), approval.correlationId());

            redirectAttributes.addFlashAttribute("success",
                String.format("Payment %s approved", abbreviate(approval.paymentId())));
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Failed to approve: " + e.getMessage());
        }
        return "redirect:/approvals";
    }

    @PostMapping("/{id}/reject")
    public String reject(
            @PathVariable UUID id,
            @RequestParam String reason,
            RedirectAttributes redirectAttributes) {
        try {
            PendingApproval approval = pendingApprovalRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Approval not found: " + id));

            if (!approval.isPending()) {
                redirectAttributes.addFlashAttribute("error", "Approval already processed");
                return "redirect:/approvals";
            }

            // Update status
            PendingApproval resolved = approval.withResolution(ApprovalStatus.REJECTED, "operator", reason);
            pendingApprovalRepository.update(resolved);

            // Send rejection reply to process (will trigger compensation)
            networkSimulator.sendApprovalRejectedReply(approval.commandId(), approval.correlationId(), reason);

            redirectAttributes.addFlashAttribute("success",
                String.format("Payment %s rejected", abbreviate(approval.paymentId())));
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Failed to reject: " + e.getMessage());
        }
        return "redirect:/approvals";
    }

    @PostMapping("/approve-all")
    public String approveAll(RedirectAttributes redirectAttributes) {
        try {
            List<PendingApproval> approvals = pendingApprovalRepository.findAllPending();
            int count = 0;

            for (PendingApproval approval : approvals) {
                PendingApproval resolved = approval.withResolution(ApprovalStatus.APPROVED, "operator", "Bulk approved");
                pendingApprovalRepository.update(resolved);
                networkSimulator.sendApprovalApprovedReply(approval.commandId(), approval.correlationId());
                count++;
            }

            redirectAttributes.addFlashAttribute("success",
                String.format("Approved %d payment(s)", count));
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Failed to approve all: " + e.getMessage());
        }
        return "redirect:/approvals";
    }

    private String abbreviate(UUID uuid) {
        String s = uuid.toString();
        return s.substring(0, Math.min(8, s.length())) + "...";
    }
}
