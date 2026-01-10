package com.commandbus.e2e.controller;

import com.commandbus.api.CommandBus;
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

    @PostMapping
    public String sendCommand(
            @RequestParam String commandType,
            @RequestParam String dataJson,
            RedirectAttributes redirectAttributes) {
        try {
            Map<String, Object> data = objectMapper.readValue(dataJson, new TypeReference<>() {});
            UUID commandId = UUID.randomUUID();

            commandBus.send(domain, commandType, commandId, data, null, null, null);

            redirectAttributes.addFlashAttribute("success",
                "Command sent: " + commandType + " (ID: " + commandId + ")");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Failed to send: " + e.getMessage());
        }
        return "redirect:/send";
    }
}
