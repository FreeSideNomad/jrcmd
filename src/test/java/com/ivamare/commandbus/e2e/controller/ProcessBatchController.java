package com.ivamare.commandbus.e2e.controller;

import com.ivamare.commandbus.e2e.dto.ProcessBatchCreateRequest;
import com.ivamare.commandbus.e2e.process.OutputType;
import com.ivamare.commandbus.e2e.process.ProbabilisticBehavior;
import com.ivamare.commandbus.e2e.process.StepBehavior;
import com.ivamare.commandbus.e2e.service.E2EService;
import com.ivamare.commandbus.process.ProcessStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Controller for process batch management.
 */
@Controller
@RequestMapping("/process-batches")
public class ProcessBatchController {

    private final E2EService e2eService;
    private final String domain;

    public ProcessBatchController(
            E2EService e2eService,
            @Value("${commandbus.process.domain:reporting}") String domain) {
        this.e2eService = e2eService;
        this.domain = domain;
    }

    @GetMapping
    public String listProcessBatches(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Model model) {

        var batches = e2eService.getProcessBatches(domain, size, page * size);

        model.addAttribute("batches", batches);
        model.addAttribute("page", page);
        model.addAttribute("size", size);
        model.addAttribute("domain", domain);

        return "pages/process_batches";
    }

    @GetMapping("/new")
    public String newProcessBatchForm(Model model) {
        model.addAttribute("domain", domain);
        model.addAttribute("request", ProcessBatchCreateRequest.defaults());
        model.addAttribute("outputTypes", OutputType.values());
        return "pages/process_batch_new";
    }

    @PostMapping
    public String createProcessBatch(
            @RequestParam String name,
            @RequestParam(defaultValue = "10") int count,
            @RequestParam String fromDate,
            @RequestParam String toDate,
            @RequestParam(defaultValue = "5") int accountCount,
            @RequestParam(defaultValue = "PDF") String outputType,
            // Query step behavior
            @RequestParam(defaultValue = "0") double queryFailPerm,
            @RequestParam(defaultValue = "0") double queryFailTrans,
            @RequestParam(defaultValue = "0") double queryFailBiz,
            @RequestParam(defaultValue = "0") double queryTimeout,
            @RequestParam(defaultValue = "10") int queryMinMs,
            @RequestParam(defaultValue = "100") int queryMaxMs,
            // Aggregation step behavior
            @RequestParam(defaultValue = "0") double aggFailPerm,
            @RequestParam(defaultValue = "0") double aggFailTrans,
            @RequestParam(defaultValue = "0") double aggFailBiz,
            @RequestParam(defaultValue = "0") double aggTimeout,
            @RequestParam(defaultValue = "10") int aggMinMs,
            @RequestParam(defaultValue = "100") int aggMaxMs,
            // Render step behavior
            @RequestParam(defaultValue = "0") double renderFailPerm,
            @RequestParam(defaultValue = "0") double renderFailTrans,
            @RequestParam(defaultValue = "0") double renderFailBiz,
            @RequestParam(defaultValue = "0") double renderTimeout,
            @RequestParam(defaultValue = "10") int renderMinMs,
            @RequestParam(defaultValue = "100") int renderMaxMs,
            RedirectAttributes redirectAttributes) {

        StepBehavior behavior = new StepBehavior(
            new ProbabilisticBehavior(queryFailPerm, queryFailTrans, queryFailBiz, queryTimeout, queryMinMs, queryMaxMs),
            new ProbabilisticBehavior(aggFailPerm, aggFailTrans, aggFailBiz, aggTimeout, aggMinMs, aggMaxMs),
            new ProbabilisticBehavior(renderFailPerm, renderFailTrans, renderFailBiz, renderTimeout, renderMinMs, renderMaxMs)
        );

        ProcessBatchCreateRequest request = new ProcessBatchCreateRequest(
            name,
            count,
            LocalDate.parse(fromDate),
            LocalDate.parse(toDate),
            accountCount,
            OutputType.valueOf(outputType),
            behavior
        );

        UUID batchId = e2eService.createProcessBatch(domain, request);
        redirectAttributes.addFlashAttribute("success", "Process batch created with " + count + " processes");
        return "redirect:/process-batches/" + batchId;
    }

    @GetMapping("/{batchId}")
    public String processBatchDetail(
            @PathVariable UUID batchId,
            @RequestParam(required = false) ProcessStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            Model model) {

        // Refresh batch stats before displaying
        e2eService.refreshProcessBatchStats(domain, batchId);

        var batch = e2eService.getProcessBatchById(domain, batchId);
        if (batch.isEmpty()) {
            return "redirect:/process-batches?error=notfound";
        }

        var processes = e2eService.getProcessBatchProcesses(domain, batchId, status, size, page * size);

        model.addAttribute("batch", batch.get());
        model.addAttribute("processes", processes);
        model.addAttribute("status", status);
        model.addAttribute("statuses", ProcessStatus.values());
        model.addAttribute("page", page);
        model.addAttribute("size", size);
        model.addAttribute("domain", domain);

        return "pages/process_batch_detail";
    }
}
