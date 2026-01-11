package com.ivamare.commandbus.e2e.controller;

import com.ivamare.commandbus.api.CommandBus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Controller for submitting test commands.
 */
@Controller
@RequestMapping("/send")
public class SendCommandController {

    private final CommandBus commandBus;
    private final ObjectMapper objectMapper;
    private final String domain;

    public SendCommandController(
            CommandBus commandBus,
            ObjectMapper objectMapper,
            @Value("${commandbus.domain:test}") String domain) {
        this.commandBus = commandBus;
        this.objectMapper = objectMapper;
        this.domain = domain;
    }

    @GetMapping
    public String sendForm(Model model) {
        model.addAttribute("domain", domain);
        model.addAttribute("commandTypes", new String[]{
            "SuccessCommand",
            "TransientFailCommand",
            "PermanentFailCommand",
            "SlowCommand"
        });
        return "pages/send_command";
    }

    @PostMapping("/single")
    public String sendSingleCommand(
            @RequestParam String commandType,
            @RequestParam(defaultValue = "{}") String data,
            RedirectAttributes redirectAttributes) {
        try {
            Map<String, Object> commandData = objectMapper.readValue(data, new TypeReference<>() {});
            UUID commandId = UUID.randomUUID();

            commandBus.send(domain, commandType, commandId, commandData, null, null, null);

            redirectAttributes.addFlashAttribute("success",
                "Command sent: " + commandType + " (ID: " + commandId + ")");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Failed to send: " + e.getMessage());
        }
        return "redirect:/send";
    }

    @PostMapping("/batch")
    public String sendBatchCommand(
            @RequestParam String commandType,
            @RequestParam(defaultValue = "10") int count,
            RedirectAttributes redirectAttributes) {
        try {
            UUID batchId = UUID.randomUUID();
            for (int i = 0; i < count; i++) {
                UUID commandId = UUID.randomUUID();
                Map<String, Object> data = new HashMap<>();
                data.put("index", i);
                commandBus.send(domain, commandType, commandId, data, batchId, null, null);
            }

            redirectAttributes.addFlashAttribute("success",
                "Batch sent: " + count + " " + commandType + " commands (Batch ID: " + batchId + ")");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Failed to send batch: " + e.getMessage());
        }
        return "redirect:/send";
    }

    @PostMapping("/quick")
    public String sendQuickAction(
            @RequestParam String action,
            RedirectAttributes redirectAttributes) {
        try {
            UUID batchId = UUID.randomUUID();
            switch (action) {
                case "success-batch" -> {
                    for (int i = 0; i < 10; i++) {
                        commandBus.send(domain, "SuccessCommand", UUID.randomUUID(),
                            Map.of("index", i), batchId, null, null);
                    }
                    redirectAttributes.addFlashAttribute("success",
                        "Sent 10 SuccessCommand batch (ID: " + batchId + ")");
                }
                case "mixed-batch" -> {
                    for (int i = 0; i < 5; i++) {
                        commandBus.send(domain, "SuccessCommand", UUID.randomUUID(),
                            Map.of("index", i), batchId, null, null);
                    }
                    for (int i = 0; i < 5; i++) {
                        commandBus.send(domain, "PermanentFailCommand", UUID.randomUUID(),
                            Map.of("index", i), batchId, null, null);
                    }
                    redirectAttributes.addFlashAttribute("success",
                        "Sent mixed batch: 5 success + 5 fail (ID: " + batchId + ")");
                }
                case "fail-batch" -> {
                    for (int i = 0; i < 5; i++) {
                        commandBus.send(domain, "PermanentFailCommand", UUID.randomUUID(),
                            Map.of("index", i), batchId, null, null);
                    }
                    redirectAttributes.addFlashAttribute("success",
                        "Sent 5 PermanentFailCommand batch (ID: " + batchId + ")");
                }
                default -> redirectAttributes.addFlashAttribute("error", "Unknown action: " + action);
            }
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Failed: " + e.getMessage());
        }
        return "redirect:/send";
    }
}
