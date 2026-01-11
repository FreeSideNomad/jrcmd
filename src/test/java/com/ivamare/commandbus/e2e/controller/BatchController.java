package com.ivamare.commandbus.e2e.controller;

import com.ivamare.commandbus.e2e.service.E2EService;
import com.ivamare.commandbus.model.BatchStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

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

        var batches = e2eService.getBatches(domain, status, size, page * size);

        model.addAttribute("batches", batches);
        model.addAttribute("status", status);
        model.addAttribute("page", page);
        model.addAttribute("size", size);
        model.addAttribute("statuses", BatchStatus.values());
        model.addAttribute("domain", domain);

        return "pages/batches";
    }

    @GetMapping("/{batchId}")
    public String batchDetail(
            @PathVariable UUID batchId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Model model) {

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
}
