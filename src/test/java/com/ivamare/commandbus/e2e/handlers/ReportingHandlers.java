package com.ivamare.commandbus.e2e.handlers;

import com.ivamare.commandbus.e2e.process.ProbabilisticBehavior;
import com.ivamare.commandbus.exception.BusinessRuleException;
import com.ivamare.commandbus.exception.PermanentCommandException;
import com.ivamare.commandbus.exception.TransientCommandException;
import com.ivamare.commandbus.handler.HandlerRegistry;
import com.ivamare.commandbus.model.Command;
import com.ivamare.commandbus.model.HandlerContext;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Command handlers for the reporting domain.
 *
 * <p>Handles the three steps of the StatementReport process:
 * <ul>
 *   <li>StatementQuery - Query statement data</li>
 *   <li>StatementDataAggregation - Aggregate query results</li>
 *   <li>StatementRender - Render to output format</li>
 * </ul>
 *
 * <p>Each handler supports probabilistic behavior for testing failure scenarios.
 * Only active when running with the 'worker' profile.
 */
@Component
@Profile("worker")
public class ReportingHandlers {

    private static final Logger log = LoggerFactory.getLogger(ReportingHandlers.class);
    private static final String DOMAIN = "reporting";

    private final HandlerRegistry handlerRegistry;
    private final Random random = new Random();

    public ReportingHandlers(HandlerRegistry handlerRegistry) {
        this.handlerRegistry = handlerRegistry;
    }

    @PostConstruct
    public void registerHandlers() {
        handlerRegistry.register(DOMAIN, "StatementQuery", this::handleStatementQuery);
        handlerRegistry.register(DOMAIN, "StatementDataAggregation", this::handleStatementDataAggregation);
        handlerRegistry.register(DOMAIN, "StatementRender", this::handleStatementRender);
        log.info("Registered reporting domain handlers: StatementQuery, StatementDataAggregation, StatementRender");
    }

    /**
     * Handle StatementQuery command - queries statement data for accounts.
     */
    public Map<String, Object> handleStatementQuery(Command command, HandlerContext context) {
        log.info("Processing StatementQuery: {}", command.commandId());

        evaluateBehavior(command);

        // Simulate query operation
        String queryResultPath = "/tmp/query_" + UUID.randomUUID() + ".json";

        Map<String, Object> result = new HashMap<>();
        result.put("query_result_path", queryResultPath);
        result.put("record_count", random.nextInt(1000) + 100);
        result.put("status", "success");

        log.info("StatementQuery completed: {} records at {}", result.get("record_count"), queryResultPath);
        return result;
    }

    /**
     * Handle StatementDataAggregation command - aggregates query results.
     */
    public Map<String, Object> handleStatementDataAggregation(Command command, HandlerContext context) {
        log.info("Processing StatementDataAggregation: {}", command.commandId());

        evaluateBehavior(command);

        // Simulate aggregation operation
        String aggregatedDataPath = "/tmp/agg_" + UUID.randomUUID() + ".json";

        Map<String, Object> result = new HashMap<>();
        result.put("aggregated_data_path", aggregatedDataPath);
        result.put("aggregation_count", random.nextInt(50) + 10);
        result.put("status", "success");

        log.info("StatementDataAggregation completed: {} aggregations at {}",
            result.get("aggregation_count"), aggregatedDataPath);
        return result;
    }

    /**
     * Handle StatementRender command - renders aggregated data to output format.
     */
    public Map<String, Object> handleStatementRender(Command command, HandlerContext context) {
        log.info("Processing StatementRender: {}", command.commandId());

        evaluateBehavior(command);

        // Get output type from payload
        String outputType = (String) command.data().getOrDefault("output_type", "PDF");
        String extension = outputType.toLowerCase();

        // Simulate render operation
        String renderedFilePath = "/tmp/report_" + UUID.randomUUID() + "." + extension;

        Map<String, Object> result = new HashMap<>();
        result.put("rendered_file_path", renderedFilePath);
        result.put("output_type", outputType);
        result.put("file_size", random.nextInt(100000) + 10000);
        result.put("status", "success");

        log.info("StatementRender completed: {} file at {} ({}KB)",
            outputType, renderedFilePath, (int) result.get("file_size") / 1024);
        return result;
    }

    /**
     * Evaluate probabilistic behavior from command payload.
     * Throws appropriate exception based on configured probabilities.
     */
    @SuppressWarnings("unchecked")
    private void evaluateBehavior(Command command) {
        Map<String, Object> behaviorMap = (Map<String, Object>) command.data().get("behavior");
        if (behaviorMap == null) {
            // No behavior configured, default short delay and success
            simulateDuration(10, 100);
            return;
        }

        ProbabilisticBehavior behavior = ProbabilisticBehavior.fromMap(behaviorMap);

        double roll = random.nextDouble() * 100;
        double cumulative = 0;

        // Evaluate in order: permanent → transient → business_rule → timeout → success
        cumulative += behavior.failPermanentPct();
        if (roll < cumulative) {
            log.warn("Simulating permanent failure (roll={}, threshold={})", roll, cumulative);
            throw new PermanentCommandException("PERM_ERROR", "Simulated permanent error");
        }

        cumulative += behavior.failTransientPct();
        if (roll < cumulative) {
            log.warn("Simulating transient failure (roll={}, threshold={})", roll, cumulative);
            throw new TransientCommandException("TRANS_ERROR", "Simulated transient error");
        }

        cumulative += behavior.failBusinessRulePct();
        if (roll < cumulative) {
            log.warn("Simulating business rule failure (roll={}, threshold={})", roll, cumulative);
            throw new BusinessRuleException("BIZ_RULE", "Simulated business rule violation");
        }

        cumulative += behavior.timeoutPct();
        if (roll < cumulative) {
            log.warn("Simulating timeout (roll={}, threshold={})", roll, cumulative);
            // Sleep longer than visibility timeout to simulate timeout
            try {
                Thread.sleep(60000); // 60 seconds
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return;
        }

        // Success with duration in configured range
        simulateDuration(behavior.minDurationMs(), behavior.maxDurationMs());
    }

    private void simulateDuration(int minMs, int maxMs) {
        int duration = minMs + random.nextInt(Math.max(1, maxMs - minMs));
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
