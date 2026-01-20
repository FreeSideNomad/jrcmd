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
        // Commands (STEP_BASED execution model)
        model.addAttribute("commands", e2eService.getTsqCommands(effectiveDomain, size, page * size));
        model.addAttribute("totalCount", e2eService.getTsqCount(effectiveDomain));
        // Processes (PROCESS_STEP execution model - WAITING_FOR_TSQ status)
        model.addAttribute("processes", e2eService.getTsqProcesses(effectiveDomain, size, page * size));
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

    // ========== Bulk Command Operations ==========

    @PostMapping("/bulk/retry")
    public String bulkRetryCommands(
            @RequestParam String ids,
            @RequestParam(required = false) String domain,
            RedirectAttributes redirectAttributes) {
        String effectiveDomain = domain != null ? domain : this.domain;
        try {
            String[] idArray = ids.split(",");
            int count = 0;
            for (String id : idArray) {
                e2eService.retryTsqCommand(effectiveDomain, UUID.fromString(id.trim()), "e2e-ui-bulk");
                count++;
            }
            redirectAttributes.addFlashAttribute("success", count + " command(s) queued for retry");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Failed to retry commands: " + e.getMessage());
        }
        return "redirect:/tsq?domain=" + effectiveDomain;
    }

    @PostMapping("/bulk/cancel")
    public String bulkCancelCommands(
            @RequestParam String ids,
            @RequestParam String reason,
            @RequestParam(required = false) String domain,
            RedirectAttributes redirectAttributes) {
        String effectiveDomain = domain != null ? domain : this.domain;
        try {
            String[] idArray = ids.split(",");
            int count = 0;
            for (String id : idArray) {
                e2eService.cancelTsqCommand(effectiveDomain, UUID.fromString(id.trim()), reason, "e2e-ui-bulk");
                count++;
            }
            redirectAttributes.addFlashAttribute("success", count + " command(s) canceled");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Failed to cancel commands: " + e.getMessage());
        }
        return "redirect:/tsq?domain=" + effectiveDomain;
    }

    @PostMapping("/bulk/complete")
    public String bulkCompleteCommands(
            @RequestParam String ids,
            @RequestParam(required = false) String domain,
            RedirectAttributes redirectAttributes) {
        String effectiveDomain = domain != null ? domain : this.domain;
        try {
            String[] idArray = ids.split(",");
            int count = 0;
            for (String id : idArray) {
                e2eService.completeTsqCommand(effectiveDomain, UUID.fromString(id.trim()), Map.of(), "e2e-ui-bulk");
                count++;
            }
            redirectAttributes.addFlashAttribute("success", count + " command(s) completed");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Failed to complete commands: " + e.getMessage());
        }
        return "redirect:/tsq?domain=" + effectiveDomain;
    }

    // ========== Process TSQ Operations (for PROCESS_STEP execution model) ==========

    @PostMapping("/processes/{processId}/retry")
    public String retryProcess(
            @PathVariable UUID processId,
            @RequestParam(required = false) String domain,
            RedirectAttributes redirectAttributes) {
        String effectiveDomain = domain != null ? domain : this.domain;
        try {
            e2eService.retryTsqProcess(effectiveDomain, processId);
            redirectAttributes.addFlashAttribute("success", "Process queued for retry");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Failed to retry process: " + e.getMessage());
        }
        return "redirect:/tsq?domain=" + effectiveDomain;
    }

    @PostMapping("/processes/{processId}/cancel")
    public String cancelProcess(
            @PathVariable UUID processId,
            @RequestParam(required = false, defaultValue = "true") boolean runCompensations,
            @RequestParam(required = false) String domain,
            RedirectAttributes redirectAttributes) {
        String effectiveDomain = domain != null ? domain : this.domain;
        try {
            e2eService.cancelTsqProcess(effectiveDomain, processId, runCompensations);
            redirectAttributes.addFlashAttribute("success", "Process canceled" +
                (runCompensations ? " with compensations" : ""));
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Failed to cancel process: " + e.getMessage());
        }
        return "redirect:/tsq?domain=" + effectiveDomain;
    }

    @PostMapping("/processes/{processId}/complete")
    public String completeProcess(
            @PathVariable UUID processId,
            @RequestParam(required = false) String stateOverrides,
            @RequestParam(required = false) String domain,
            RedirectAttributes redirectAttributes) {
        String effectiveDomain = domain != null ? domain : this.domain;
        try {
            Map<String, Object> overrides = null;
            if (stateOverrides != null && !stateOverrides.isBlank() && !stateOverrides.equals("{}")) {
                overrides = objectMapper.readValue(stateOverrides, new TypeReference<>() {});
            }
            e2eService.completeTsqProcess(effectiveDomain, processId, overrides);
            redirectAttributes.addFlashAttribute("success", "Process completed manually");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Failed to complete process: " + e.getMessage());
        }
        return "redirect:/tsq?domain=" + effectiveDomain;
    }

    @PostMapping("/processes/retry-all")
    public String retryAllProcesses(
            @RequestParam(required = false) String domain,
            RedirectAttributes redirectAttributes) {
        String effectiveDomain = domain != null ? domain : this.domain;
        try {
            e2eService.retryAllTsqProcesses(effectiveDomain);
            redirectAttributes.addFlashAttribute("success", "All processes queued for retry");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Failed to retry all processes: " + e.getMessage());
        }
        return "redirect:/tsq?domain=" + effectiveDomain;
    }

    // ========== Bulk Process Operations ==========

    @PostMapping("/processes/bulk/retry")
    public String bulkRetryProcesses(
            @RequestParam String ids,
            @RequestParam(required = false) String domain,
            RedirectAttributes redirectAttributes) {
        String effectiveDomain = domain != null ? domain : this.domain;
        try {
            String[] idArray = ids.split(",");
            int count = 0;
            for (String id : idArray) {
                e2eService.retryTsqProcess(effectiveDomain, UUID.fromString(id.trim()));
                count++;
            }
            redirectAttributes.addFlashAttribute("success", count + " process(es) queued for retry");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Failed to retry processes: " + e.getMessage());
        }
        return "redirect:/tsq?domain=" + effectiveDomain;
    }

    @PostMapping("/processes/bulk/cancel")
    public String bulkCancelProcesses(
            @RequestParam String ids,
            @RequestParam(required = false, defaultValue = "true") boolean runCompensations,
            @RequestParam(required = false) String domain,
            RedirectAttributes redirectAttributes) {
        String effectiveDomain = domain != null ? domain : this.domain;
        try {
            String[] idArray = ids.split(",");
            int count = 0;
            for (String id : idArray) {
                e2eService.cancelTsqProcess(effectiveDomain, UUID.fromString(id.trim()), runCompensations);
                count++;
            }
            redirectAttributes.addFlashAttribute("success", count + " process(es) canceled" +
                (runCompensations ? " with compensations" : ""));
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Failed to cancel processes: " + e.getMessage());
        }
        return "redirect:/tsq?domain=" + effectiveDomain;
    }

    @PostMapping("/processes/bulk/complete")
    public String bulkCompleteProcesses(
            @RequestParam String ids,
            @RequestParam(required = false) String domain,
            RedirectAttributes redirectAttributes) {
        String effectiveDomain = domain != null ? domain : this.domain;
        try {
            String[] idArray = ids.split(",");
            int count = 0;
            for (String id : idArray) {
                e2eService.completeTsqProcess(effectiveDomain, UUID.fromString(id.trim()), null);
                count++;
            }
            redirectAttributes.addFlashAttribute("success", count + " process(es) completed");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Failed to complete processes: " + e.getMessage());
        }
        return "redirect:/tsq?domain=" + effectiveDomain;
    }
}
