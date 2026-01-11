package com.ivamare.commandbus.e2e.controller;

import com.ivamare.commandbus.e2e.service.E2EService;
import com.ivamare.commandbus.model.CommandStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Command browser controller.
 */
@Controller
@RequestMapping("/commands")
public class CommandController {

    private final E2EService e2eService;
    private final String defaultDomain;

    public CommandController(
            E2EService e2eService,
            @Value("${commandbus.domain:test}") String domain) {
        this.e2eService = e2eService;
        this.defaultDomain = domain;
    }

    @GetMapping
    public String listCommands(
            @RequestParam(required = false) String domain,
            @RequestParam(required = false) String commandType,
            @RequestParam(required = false) CommandStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            Model model) {

        // Get distinct domains for dropdown
        List<String> domains = e2eService.getDistinctDomains();
        model.addAttribute("domains", domains);

        // Use selected domain or default
        String selectedDomain = (domain != null && !domain.isBlank()) ? domain : defaultDomain;
        model.addAttribute("selectedDomain", selectedDomain);

        // Get distinct command types for selected domain
        List<String> commandTypes = e2eService.getDistinctCommandTypes(selectedDomain);
        model.addAttribute("commandTypes", commandTypes);

        // Query commands with filters
        var commands = e2eService.getCommands(selectedDomain, commandType, status, from, to, size, page * size);

        model.addAttribute("commands", commands);
        model.addAttribute("commandType", commandType);
        model.addAttribute("status", status);
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        model.addAttribute("page", page);
        model.addAttribute("size", size);
        model.addAttribute("statuses", CommandStatus.values());

        return "pages/commands";
    }

    @GetMapping("/{commandId}")
    public String commandDetail(@PathVariable UUID commandId,
                               @RequestParam(required = false) String domain,
                               Model model) {
        String selectedDomain = (domain != null && !domain.isBlank()) ? domain : defaultDomain;

        var command = e2eService.getCommandById(selectedDomain, commandId);
        if (command.isEmpty()) {
            return "redirect:/commands?error=notfound";
        }

        model.addAttribute("command", command.get());
        model.addAttribute("auditTrail", e2eService.getCommandAuditTrail(selectedDomain, commandId));
        model.addAttribute("domain", selectedDomain);

        return "pages/command_detail";
    }
}
