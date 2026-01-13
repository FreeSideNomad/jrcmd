package com.ivamare.commandbus.e2e.controller;

import com.ivamare.commandbus.e2e.dto.QueueStats;
import com.ivamare.commandbus.e2e.service.E2EService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
        var allQueues = e2eService.getAllQueueStats();

        // Group queues by domain (prefix before __)
        Map<String, List<QueueStats>> queuesByDomain = allQueues.stream()
            .collect(Collectors.groupingBy(
                q -> {
                    String name = q.queueName();
                    int idx = name.indexOf("__");
                    return idx > 0 ? name.substring(0, idx) : "other";
                },
                LinkedHashMap::new,
                Collectors.toList()
            ));

        // Calculate totals
        long totalDepth = allQueues.stream().mapToLong(QueueStats::queueDepth).sum();
        long totalArchive = allQueues.stream().mapToLong(QueueStats::archiveSize).sum();

        model.addAttribute("allQueues", allQueues);
        model.addAttribute("queuesByDomain", queuesByDomain);
        model.addAttribute("totalDepth", totalDepth);
        model.addAttribute("totalArchive", totalArchive);
        model.addAttribute("domain", domain);
        return "pages/queues";
    }
}
