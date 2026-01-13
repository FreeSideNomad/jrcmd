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
            @RequestParam(required = false) String domain,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            Model model) {
        String effectiveDomain = domain != null ? domain : this.domain;
        model.addAttribute("commands", e2eService.getTsqCommands(effectiveDomain, size, page * size));
        model.addAttribute("totalCount", e2eService.getTsqCount(effectiveDomain));
        model.addAttribute("page", page);
        model.addAttribute("size", size);
        model.addAttribute("domain", effectiveDomain);
        model.addAttribute("availableDomains", e2eService.getDomainsWithTsqItems());
        return "pages/tsq";
    }

    @PostMapping("/{commandId}/retry")
    public String retry(
            @PathVariable UUID commandId,
            @RequestParam(required = false) String domain,
            RedirectAttributes redirectAttributes) {
        String effectiveDomain = domain != null ? domain : this.domain;
        try {
            e2eService.retryTsqCommand(effectiveDomain, commandId, "e2e-ui");
            redirectAttributes.addFlashAttribute("success", "Command queued for retry");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Failed to retry: " + e.getMessage());
        }
        return "redirect:/tsq?domain=" + effectiveDomain;
    }

    @PostMapping("/{commandId}/cancel")
    public String cancel(
            @PathVariable UUID commandId,
            @RequestParam String reason,
            @RequestParam(required = false) String domain,
            RedirectAttributes redirectAttributes) {
        String effectiveDomain = domain != null ? domain : this.domain;
        try {
            e2eService.cancelTsqCommand(effectiveDomain, commandId, reason, "e2e-ui");
            redirectAttributes.addFlashAttribute("success", "Command canceled");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Failed to cancel: " + e.getMessage());
        }
        return "redirect:/tsq?domain=" + effectiveDomain;
    }

    @PostMapping("/{commandId}/complete")
    public String complete(
            @PathVariable UUID commandId,
            @RequestParam String resultJson,
            @RequestParam(required = false) String domain,
            RedirectAttributes redirectAttributes) {
        String effectiveDomain = domain != null ? domain : this.domain;
        try {
            Map<String, Object> result = objectMapper.readValue(resultJson, new TypeReference<>() {});
            e2eService.completeTsqCommand(effectiveDomain, commandId, result, "e2e-ui");
            redirectAttributes.addFlashAttribute("success", "Command completed manually");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Failed to complete: " + e.getMessage());
        }
        return "redirect:/tsq?domain=" + effectiveDomain;
    }

    @PostMapping("/retry-all")
    public String retryAll(
            @RequestParam(required = false) String domain,
            RedirectAttributes redirectAttributes) {
        String effectiveDomain = domain != null ? domain : this.domain;
        try {
            e2eService.retryAllTsq(effectiveDomain, "e2e-ui");
            redirectAttributes.addFlashAttribute("success", "All commands queued for retry");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Failed to retry all: " + e.getMessage());
        }
        return "redirect:/tsq?domain=" + effectiveDomain;
    }
}
