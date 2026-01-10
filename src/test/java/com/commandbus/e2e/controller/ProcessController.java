package com.commandbus.e2e.controller;

import com.commandbus.e2e.service.E2EService;
import com.commandbus.process.ProcessStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Process monitoring controller.
 */
@Controller
@RequestMapping("/processes")
public class ProcessController {

    private final E2EService e2eService;
    private final String domain;

    public ProcessController(
            E2EService e2eService,
            @Value("${commandbus.domain:test}") String domain) {
        this.e2eService = e2eService;
        this.domain = domain;
    }

    @GetMapping
    public String listProcesses(
            @RequestParam(required = false) String processType,
            @RequestParam(required = false) ProcessStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Model model) {

        var processes = e2eService.getProcesses(domain, processType, status, size, page * size);

        model.addAttribute("processes", processes);
        model.addAttribute("processType", processType);
        model.addAttribute("status", status);
        model.addAttribute("page", page);
        model.addAttribute("size", size);
        model.addAttribute("statuses", ProcessStatus.values());
        model.addAttribute("domain", domain);

        return "pages/processes";
    }

    @GetMapping("/{processId}")
    public String processDetail(@PathVariable UUID processId, Model model) {
        var process = e2eService.getProcessById(domain, processId);
        if (process.isEmpty()) {
            return "redirect:/processes?error=notfound";
        }

        model.addAttribute("process", process.get());
        model.addAttribute("auditTrail", e2eService.getProcessAuditTrail(domain, processId));
        model.addAttribute("domain", domain);

        return "pages/process_detail";
    }
}
