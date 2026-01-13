package com.ivamare.commandbus.e2e.controller;

import com.ivamare.commandbus.e2e.payment.*;
import com.ivamare.commandbus.e2e.payment.PendingNetworkResponse.ResponseStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.UUID;

/**
 * Controller for managing pending network response queue.
 *
 * <p>Displays L3/L4 network confirmations requiring operator intervention
 * and allows operators to approve (send success) or reject (send failure).
 */
@Controller
@RequestMapping("/pending-network")
public class PendingNetworkController {

    private final PendingNetworkResponseRepository pendingNetworkResponseRepository;
    private final PaymentNetworkSimulator networkSimulator;
    private final String domain;

    public PendingNetworkController(
            PendingNetworkResponseRepository pendingNetworkResponseRepository,
            PaymentNetworkSimulator networkSimulator,
            @Value("${commandbus.domain:payments}") String domain) {
        this.pendingNetworkResponseRepository = pendingNetworkResponseRepository;
        this.networkSimulator = networkSimulator;
        this.domain = domain;
    }

    @GetMapping
    public String listPending(
            @RequestParam(required = false) Integer level,
            Model model) {
        List<PendingNetworkResponse> responses;
        int totalCount;

        if (level != null && (level == 3 || level == 4)) {
            responses = pendingNetworkResponseRepository.findPendingByLevel(level);
            totalCount = pendingNetworkResponseRepository.countPendingByLevel(level);
        } else {
            responses = pendingNetworkResponseRepository.findAllPending();
            totalCount = pendingNetworkResponseRepository.countPending();
        }

        int l3Count = pendingNetworkResponseRepository.countPendingByLevel(3);
        int l4Count = pendingNetworkResponseRepository.countPendingByLevel(4);

        model.addAttribute("responses", responses);
        model.addAttribute("totalCount", totalCount);
        model.addAttribute("l3Count", l3Count);
        model.addAttribute("l4Count", l4Count);
        model.addAttribute("selectedLevel", level);
        model.addAttribute("domain", domain);
        return "pages/pending_network";
    }

    @PostMapping("/{id}/approve")
    public String approve(
            @PathVariable UUID id,
            @RequestParam(required = false) String notes,
            RedirectAttributes redirectAttributes) {
        try {
            PendingNetworkResponse response = pendingNetworkResponseRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Response not found: " + id));

            if (!response.isPending()) {
                redirectAttributes.addFlashAttribute("error", "Response already processed");
                return "redirect:/pending-network";
            }

            // Update status
            PendingNetworkResponse resolved = response.withResolution(ResponseStatus.SUCCESS, "operator", notes);
            pendingNetworkResponseRepository.update(resolved);

            // Send success reply to process
            networkSimulator.sendApprovedReply(response.commandId(), response.correlationId(), response.level());

            redirectAttributes.addFlashAttribute("success",
                String.format("L%d response approved for process %s", response.level(), abbreviate(response.processId())));
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Failed to approve: " + e.getMessage());
        }
        return "redirect:/pending-network";
    }

    @PostMapping("/{id}/reject")
    public String reject(
            @PathVariable UUID id,
            @RequestParam String reason,
            RedirectAttributes redirectAttributes) {
        try {
            PendingNetworkResponse response = pendingNetworkResponseRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Response not found: " + id));

            if (!response.isPending()) {
                redirectAttributes.addFlashAttribute("error", "Response already processed");
                return "redirect:/pending-network";
            }

            // Update status
            PendingNetworkResponse resolved = response.withResolution(ResponseStatus.FAILED, "operator", reason);
            pendingNetworkResponseRepository.update(resolved);

            // Send failure reply to process (will trigger compensation)
            networkSimulator.sendRejectedReply(response.commandId(), response.correlationId(), response.level());

            redirectAttributes.addFlashAttribute("success",
                String.format("L%d response rejected for process %s", response.level(), abbreviate(response.processId())));
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Failed to reject: " + e.getMessage());
        }
        return "redirect:/pending-network";
    }

    @PostMapping("/approve-all")
    public String approveAll(
            @RequestParam(required = false) Integer level,
            RedirectAttributes redirectAttributes) {
        try {
            List<PendingNetworkResponse> responses;
            if (level != null && (level == 3 || level == 4)) {
                responses = pendingNetworkResponseRepository.findPendingByLevel(level);
            } else {
                responses = pendingNetworkResponseRepository.findAllPending();
            }

            int count = 0;
            for (PendingNetworkResponse response : responses) {
                PendingNetworkResponse resolved = response.withResolution(ResponseStatus.SUCCESS, "operator", "Bulk approved");
                pendingNetworkResponseRepository.update(resolved);
                networkSimulator.sendApprovedReply(response.commandId(), response.correlationId(), response.level());
                count++;
            }

            redirectAttributes.addFlashAttribute("success",
                String.format("Approved %d response(s)", count));
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Failed to approve all: " + e.getMessage());
        }
        return "redirect:/pending-network";
    }

    @PostMapping("/reject-all")
    public String rejectAll(
            @RequestParam(required = false) Integer level,
            @RequestParam String reason,
            RedirectAttributes redirectAttributes) {
        try {
            List<PendingNetworkResponse> responses;
            if (level != null && (level == 3 || level == 4)) {
                responses = pendingNetworkResponseRepository.findPendingByLevel(level);
            } else {
                responses = pendingNetworkResponseRepository.findAllPending();
            }

            int count = 0;
            for (PendingNetworkResponse response : responses) {
                PendingNetworkResponse resolved = response.withResolution(ResponseStatus.FAILED, "operator", reason);
                pendingNetworkResponseRepository.update(resolved);
                networkSimulator.sendRejectedReply(response.commandId(), response.correlationId(), response.level());
                count++;
            }

            redirectAttributes.addFlashAttribute("success",
                String.format("Rejected %d response(s)", count));
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Failed to reject all: " + e.getMessage());
        }
        return "redirect:/pending-network";
    }

    private String abbreviate(UUID uuid) {
        String s = uuid.toString();
        return s.substring(0, Math.min(8, s.length())) + "...";
    }
}
