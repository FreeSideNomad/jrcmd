package com.ivamare.commandbus.e2e.process;

import com.ivamare.commandbus.api.CommandBus;
import com.ivamare.commandbus.model.Reply;
import com.ivamare.commandbus.process.BaseProcessManager;
import com.ivamare.commandbus.process.ProcessCommand;
import com.ivamare.commandbus.process.ProcessRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.*;

/**
 * Process manager for statement report generation.
 *
 * <p>Workflow: QUERY → AGGREGATION → RENDER
 *
 * <p>Each step can be configured with probabilistic behavior for testing.
 * Available in both UI and worker modes - UI uses it to start processes,
 * workers use it to handle replies.
 */
@Component
public class StatementReportProcessManager
        extends BaseProcessManager<StatementReportState, StatementReportStep> {

    private static final Logger log = LoggerFactory.getLogger(StatementReportProcessManager.class);

    private static final String PROCESS_TYPE = "StatementReport";
    private static final String DOMAIN = "reporting";

    public StatementReportProcessManager(
            CommandBus commandBus,
            ProcessRepository processRepo,
            @Value("${commandbus.process.reply-queue:reporting__process_replies}") String replyQueue,
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate) {
        super(commandBus, processRepo, replyQueue, jdbcTemplate, transactionTemplate);
    }

    @Override
    public String getProcessType() {
        return PROCESS_TYPE;
    }

    @Override
    public String getDomain() {
        return DOMAIN;
    }

    @Override
    public Class<StatementReportState> getStateClass() {
        return StatementReportState.class;
    }

    @Override
    public Class<StatementReportStep> getStepClass() {
        return StatementReportStep.class;
    }

    @Override
    @SuppressWarnings("unchecked")
    public StatementReportState createInitialState(Map<String, Object> initialData) {
        LocalDate fromDate = initialData.get("from_date") != null
            ? LocalDate.parse((String) initialData.get("from_date"))
            : LocalDate.now().minusMonths(1);

        LocalDate toDate = initialData.get("to_date") != null
            ? LocalDate.parse((String) initialData.get("to_date"))
            : LocalDate.now();

        List<String> accountList = initialData.get("account_list") != null
            ? (List<String>) initialData.get("account_list")
            : List.of("ACC001");

        OutputType outputType = initialData.get("output_type") != null
            ? OutputType.valueOf((String) initialData.get("output_type"))
            : OutputType.PDF;

        StepBehavior behavior = initialData.get("behavior") != null
            ? StepBehavior.fromMap((Map<String, Object>) initialData.get("behavior"))
            : null;

        return new StatementReportState(
            fromDate, toDate, accountList, outputType,
            null, null, null, behavior
        );
    }

    @Override
    public StatementReportStep getFirstStep(StatementReportState state) {
        return StatementReportStep.QUERY;
    }

    @Override
    public ProcessCommand<?> buildCommand(StatementReportStep step, StatementReportState state) {
        Map<String, Object> payload = new HashMap<>();

        // Common fields
        payload.put("from_date", state.fromDate().toString());
        payload.put("to_date", state.toDate().toString());
        payload.put("account_list", state.accountList());
        payload.put("output_type", state.outputType().name());

        // Step-specific fields
        switch (step) {
            case QUERY -> {
                // Query step needs date range and accounts
            }
            case AGGREGATION -> {
                // Aggregation needs query result path
                payload.put("query_result_path", state.queryResultPath());
            }
            case RENDER -> {
                // Render needs aggregated data path
                payload.put("aggregated_data_path", state.aggregatedDataPath());
            }
        }

        // Add behavior for this step if configured
        if (state.behavior() != null) {
            ProbabilisticBehavior stepBehavior = state.behavior().forStep(step);
            payload.put("behavior", stepBehavior.toMap());
        }

        log.debug("Building command for step {} with payload: {}", step, payload);
        return new ProcessCommand<>(step.getCommandType(), payload);
    }

    @Override
    public StatementReportState updateState(StatementReportState state, StatementReportStep step, Reply reply) {
        Map<String, Object> data = reply.data();
        if (data == null) {
            return state;
        }

        return switch (step) {
            case QUERY -> {
                String path = (String) data.get("query_result_path");
                yield state.withQueryResultPath(path != null ? path : "/tmp/query_" + UUID.randomUUID());
            }
            case AGGREGATION -> {
                String path = (String) data.get("aggregated_data_path");
                yield state.withAggregatedDataPath(path != null ? path : "/tmp/agg_" + UUID.randomUUID());
            }
            case RENDER -> {
                String path = (String) data.get("rendered_file_path");
                yield state.withRenderedFilePath(path != null ? path : "/tmp/report_" + UUID.randomUUID() + "." + state.outputType().name().toLowerCase());
            }
        };
    }

    @Override
    public StatementReportStep getNextStep(StatementReportStep currentStep, Reply reply, StatementReportState state) {
        return switch (currentStep) {
            case QUERY -> StatementReportStep.AGGREGATION;
            case AGGREGATION -> StatementReportStep.RENDER;
            case RENDER -> null;  // Process complete
        };
    }
}
