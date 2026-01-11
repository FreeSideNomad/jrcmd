package com.ivamare.commandbus.e2e.controller;

import com.ivamare.commandbus.e2e.service.E2EService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Queue statistics controller.
 */
@Controller
@RequestMapping("/queues")
public class QueueController {

    private final E2EService e2eService;
    private final String domain;

    public QueueController(
            E2EService e2eService,
            @Value("${commandbus.domain:test}") String domain) {
        this.e2eService = e2eService;
        this.domain = domain;
    }

    @GetMapping
    public String queueStats(Model model) {
        var queues = e2eService.getQueueStats(domain);

        // Find command and reply queues
        var commandQueue = queues.stream()
            .filter(q -> q.queueName().endsWith("__commands"))
            .findFirst()
            .orElse(null);
        var replyQueue = queues.stream()
            .filter(q -> q.queueName().endsWith("__replies"))
            .findFirst()
            .orElse(null);

        model.addAttribute("commandQueue", commandQueue);
        model.addAttribute("replyQueue", replyQueue);
        model.addAttribute("stats", e2eService.getCommandStats(domain));
        model.addAttribute("domain", domain);
        return "pages/queues";
    }
}
