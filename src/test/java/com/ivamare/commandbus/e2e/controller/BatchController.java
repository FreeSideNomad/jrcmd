package com.ivamare.commandbus.e2e.controller;

import com.ivamare.commandbus.e2e.dto.BatchCreateRequest;
import com.ivamare.commandbus.e2e.service.E2EService;
import com.ivamare.commandbus.model.BatchStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.UUID;

/**
 * Batch management controller.
 */
@Controller
@RequestMapping("/batches")
public class BatchController {

    private final E2EService e2eService;
    private final String domain;

    public BatchController(
            E2EService e2eService,
            @Value("${commandbus.domain:test}") String domain) {
        this.e2eService = e2eService;
        this.domain = domain;
    }

    @GetMapping
    public String listBatches(
            @RequestParam(required = false) BatchStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Model model) {

        // Refresh stats for pending/in-progress batches
        e2eService.refreshAllPendingBatchStats(domain);

        var batches = e2eService.getBatches(domain, status, size, page * size);

        model.addAttribute("batches", batches);
        model.addAttribute("status", status);
        model.addAttribute("page", page);
        model.addAttribute("size", size);
        model.addAttribute("statuses", BatchStatus.values());
        model.addAttribute("domain", domain);

        return "pages/batches";
    }

    @GetMapping("/new")
    public String newBatchForm(Model model) {
        model.addAttribute("domain", domain);
        model.addAttribute("commandTypes", getAvailableCommandTypes());
        model.addAttribute("request", BatchCreateRequest.defaults());
        return "pages/batch_new";
    }

    @PostMapping
    public String createBatch(
            @RequestParam String name,
            @RequestParam(defaultValue = "10") int commandCount,
            @RequestParam(defaultValue = "TestCommand") String commandType,
            @RequestParam(defaultValue = "3") int maxAttempts,
            @RequestParam(defaultValue = "0") double failPermanentPct,
            @RequestParam(defaultValue = "0") double failTransientPct,
            @RequestParam(defaultValue = "0") double failBusinessRulePct,
            @RequestParam(defaultValue = "0") double timeoutPct,
            @RequestParam(defaultValue = "0") int minDurationMs,
            @RequestParam(defaultValue = "100") int maxDurationMs,
            RedirectAttributes redirectAttributes) {

        BatchCreateRequest request = new BatchCreateRequest(
            name,
            commandCount,
            commandType,
            maxAttempts,
            new BatchCreateRequest.BatchBehavior(
                failPermanentPct,
                failTransientPct,
                failBusinessRulePct,
                timeoutPct,
                minDurationMs,
                maxDurationMs
            )
        );

        UUID batchId = e2eService.createBatch(domain, request);
        redirectAttributes.addFlashAttribute("success", "Batch created with " + commandCount + " commands");
        return "redirect:/batches/" + batchId;
    }

    @GetMapping("/{batchId}")
    public String batchDetail(
            @PathVariable UUID batchId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            Model model) {

        // Refresh batch statistics before displaying
        e2eService.refreshBatchStats(domain, batchId);

        var batch = e2eService.getBatchById(domain, batchId);
        if (batch.isEmpty()) {
            return "redirect:/batches?error=notfound";
        }

        var commands = e2eService.getBatchCommands(domain, batchId, size, page * size);

        model.addAttribute("batch", batch.get());
        model.addAttribute("commands", commands);
        model.addAttribute("page", page);
        model.addAttribute("size", size);
        model.addAttribute("domain", domain);

        return "pages/batch_detail";
    }

    private List<String> getAvailableCommandTypes() {
        return List.of(
            "TestCommand",
            "SuccessCommand",
            "TransientFailCommand",
            "PermanentFailCommand",
            "BusinessRuleFailCommand",
            "SlowCommand"
        );
    }
}
