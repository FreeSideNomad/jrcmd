package com.ivamare.commandbus.e2e.controller;

import com.ivamare.commandbus.e2e.service.E2EService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Map;
import java.util.UUID;

/**
 * Troubleshooting Queue management controller.
 */
@Controller
@RequestMapping("/tsq")
public class TsqController {

    private final E2EService e2eService;
    private final ObjectMapper objectMapper;
    private final String domain;

    public TsqController(
            E2EService e2eService,
            ObjectMapper objectMapper,
            @Value("${commandbus.domain:test}") String domain) {
        this.e2eService = e2eService;
        this.objectMapper = objectMapper;
        this.domain = domain;
    }

    @GetMapping
    public String listTsq(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            Model model) {
        model.addAttribute("commands", e2eService.getTsqCommands(domain, size, page * size));
        model.addAttribute("totalCount", e2eService.getTsqCount(domain));
        model.addAttribute("page", page);
        model.addAttribute("size", size);
        model.addAttribute("domain", domain);
        return "pages/tsq";
    }

    @PostMapping("/{commandId}/retry")
    public String retry(
            @PathVariable UUID commandId,
            RedirectAttributes redirectAttributes) {
        try {
            e2eService.retryTsqCommand(domain, commandId, "e2e-ui");
            redirectAttributes.addFlashAttribute("success", "Command queued for retry");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Failed to retry: " + e.getMessage());
        }
        return "redirect:/tsq";
    }

    @PostMapping("/{commandId}/cancel")
    public String cancel(
            @PathVariable UUID commandId,
            @RequestParam String reason,
            RedirectAttributes redirectAttributes) {
        try {
            e2eService.cancelTsqCommand(domain, commandId, reason, "e2e-ui");
            redirectAttributes.addFlashAttribute("success", "Command canceled");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Failed to cancel: " + e.getMessage());
        }
        return "redirect:/tsq";
    }

    @PostMapping("/{commandId}/complete")
    public String complete(
            @PathVariable UUID commandId,
            @RequestParam String resultJson,
            RedirectAttributes redirectAttributes) {
        try {
            Map<String, Object> result = objectMapper.readValue(resultJson, new TypeReference<>() {});
            e2eService.completeTsqCommand(domain, commandId, result, "e2e-ui");
            redirectAttributes.addFlashAttribute("success", "Command completed manually");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Failed to complete: " + e.getMessage());
        }
        return "redirect:/tsq";
    }

    @PostMapping("/retry-all")
    public String retryAll(RedirectAttributes redirectAttributes) {
        try {
            e2eService.retryAllTsq(domain, "e2e-ui");
            redirectAttributes.addFlashAttribute("success", "All commands queued for retry");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Failed to retry all: " + e.getMessage());
        }
        return "redirect:/tsq";
    }
}
