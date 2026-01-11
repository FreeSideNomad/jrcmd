package com.ivamare.commandbus.e2e.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ivamare.commandbus.e2e.service.E2EService;
import com.ivamare.commandbus.process.ProcessStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Process monitoring controller.
 */
@Controller
@RequestMapping("/processes")
public class ProcessController {

    private final E2EService e2eService;
    private final ObjectMapper objectMapper;
    private final String defaultDomain;

    public ProcessController(
            E2EService e2eService,
            ObjectMapper objectMapper,
            @Value("${commandbus.process.domain:reporting}") String defaultDomain) {
        this.e2eService = e2eService;
        this.objectMapper = objectMapper;
        this.defaultDomain = defaultDomain;
    }

    @GetMapping
    public String listProcesses(
            @RequestParam(required = false) String domain,
            @RequestParam(required = false) String processType,
            @RequestParam(required = false) ProcessStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Model model) {

        // Get distinct domains for dropdown
        List<String> domains = e2eService.getDistinctProcessDomains();
        model.addAttribute("domains", domains);

        // Use selected domain or default
        String selectedDomain = (domain != null && !domain.isBlank()) ? domain : defaultDomain;
        model.addAttribute("selectedDomain", selectedDomain);

        var processes = e2eService.getProcesses(selectedDomain, processType, status, size, page * size);

        model.addAttribute("processes", processes);
        model.addAttribute("processType", processType);
        model.addAttribute("status", status);
        model.addAttribute("page", page);
        model.addAttribute("size", size);
        model.addAttribute("statuses", ProcessStatus.values());
        model.addAttribute("domain", selectedDomain);

        return "pages/processes";
    }

    @GetMapping("/{processId}")
    public String processDetail(
            @PathVariable UUID processId,
            @RequestParam(required = false) String domain,
            Model model) {
        String selectedDomain = (domain != null && !domain.isBlank()) ? domain : defaultDomain;

        var process = e2eService.getProcessById(selectedDomain, processId);
        if (process.isEmpty()) {
            return "redirect:/processes?error=notfound";
        }

        var proc = process.get();
        model.addAttribute("process", proc);
        model.addAttribute("stateJson", formatJson(proc.state()));
        model.addAttribute("auditTrail", e2eService.getProcessAuditTrail(selectedDomain, processId));
        model.addAttribute("domain", selectedDomain);

        return "pages/process_detail";
    }

    private String formatJson(Object obj) {
        if (obj == null) return "{}";
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return obj.toString();
        }
    }
}
