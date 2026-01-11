package com.ivamare.commandbus.api.impl;

import com.ivamare.commandbus.api.CommandBus;
import com.ivamare.commandbus.exception.BatchNotFoundException;
import com.ivamare.commandbus.exception.DuplicateCommandException;
import com.ivamare.commandbus.model.*;
import com.ivamare.commandbus.pgmq.PgmqClient;
import com.ivamare.commandbus.repository.AuditRepository;
import com.ivamare.commandbus.repository.BatchRepository;
import com.ivamare.commandbus.repository.CommandRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Default implementation of CommandBus.
 */
@Service
public class DefaultCommandBus implements CommandBus {

    private static final Logger log = LoggerFactory.getLogger(DefaultCommandBus.class);
    private static final int DEFAULT_CHUNK_SIZE = 1000;
    private static final int DEFAULT_MAX_ATTEMPTS = 3;

    private final PgmqClient pgmqClient;
    private final CommandRepository commandRepository;
    private final BatchRepository batchRepository;
    private final AuditRepository auditRepository;

    private final Map<UUID, Consumer<BatchMetadata>> batchCallbacks = new ConcurrentHashMap<>();

    /**
     * Creates a new DefaultCommandBus.
     *
     * @param pgmqClient The PGMQ client
     * @param commandRepository The command repository
     * @param batchRepository The batch repository
     * @param auditRepository The audit repository
     */
    public DefaultCommandBus(
            PgmqClient pgmqClient,
            CommandRepository commandRepository,
            BatchRepository batchRepository,
            AuditRepository auditRepository) {
        this.pgmqClient = pgmqClient;
        this.commandRepository = commandRepository;
        this.batchRepository = batchRepository;
        this.auditRepository = auditRepository;
    }

    // --- Single Command Operations ---

    @Override
    public SendResult send(String domain, String commandType, UUID commandId, Map<String, Object> data) {
        return send(domain, commandType, commandId, data, null, null, null);
    }

    @Override
    @Transactional
    public SendResult send(
            String domain,
            String commandType,
            UUID commandId,
            Map<String, Object> data,
            UUID correlationId,
            String replyTo,
            Integer maxAttempts) {

        return sendInternal(domain, commandType, commandId, data,
            correlationId, replyTo, maxAttempts, null);
    }

    @Override
    @Transactional
    public SendResult sendToBatch(
            String domain,
            String commandType,
            UUID commandId,
            Map<String, Object> data,
            UUID batchId) {

        if (!batchRepository.exists(domain, batchId)) {
            throw new BatchNotFoundException(domain, batchId.toString());
        }

        return sendInternal(domain, commandType, commandId, data,
            null, null, null, batchId);
    }

    private SendResult sendInternal(
            String domain,
            String commandType,
            UUID commandId,
            Map<String, Object> data,
            UUID correlationId,
            String replyTo,
            Integer maxAttempts,
            UUID batchId) {

        String queueName = domain + "__commands";
        int effectiveMaxAttempts = maxAttempts != null ? maxAttempts : DEFAULT_MAX_ATTEMPTS;
        UUID effectiveCorrelationId = correlationId != null ? correlationId : UUID.randomUUID();

        if (commandRepository.exists(domain, commandId)) {
            throw new DuplicateCommandException(domain, commandId.toString());
        }

        Map<String, Object> message = buildMessage(
            domain, commandType, commandId, data, effectiveCorrelationId, replyTo
        );

        long msgId = pgmqClient.send(queueName, message);

        Instant now = Instant.now();
        CommandMetadata metadata = new CommandMetadata(
            domain, commandId, commandType,
            CommandStatus.PENDING,
            0, effectiveMaxAttempts,
            msgId, effectiveCorrelationId, replyTo,
            null, null, null,
            now, now, batchId
        );

        commandRepository.save(metadata, queueName);

        auditRepository.log(domain, commandId, AuditEventType.SENT, Map.of(
            "command_type", commandType,
            "correlation_id", effectiveCorrelationId.toString(),
            "msg_id", msgId
        ));

        log.info("Sent command {}.{} (commandId={}, msgId={})",
            domain, commandType, commandId, msgId);

        return new SendResult(commandId, msgId);
    }

    // --- Batch Send Operations ---

    @Override
    public BatchSendResult sendBatch(List<SendRequest> requests) {
        return sendBatch(requests, DEFAULT_CHUNK_SIZE);
    }

    @Override
    public BatchSendResult sendBatch(List<SendRequest> requests, int chunkSize) {
        if (requests.isEmpty()) {
            return new BatchSendResult(List.of(), 0, 0);
        }

        List<SendResult> allResults = new ArrayList<>();
        int chunksProcessed = 0;

        for (int i = 0; i < requests.size(); i += chunkSize) {
            List<SendRequest> chunk = requests.subList(i, Math.min(i + chunkSize, requests.size()));
            List<SendResult> chunkResults = sendBatchChunk(chunk);
            allResults.addAll(chunkResults);
            chunksProcessed++;
        }

        log.info("Sent {} commands in {} chunks", allResults.size(), chunksProcessed);

        return new BatchSendResult(allResults, chunksProcessed, allResults.size());
    }

    @Transactional
    protected List<SendResult> sendBatchChunk(List<SendRequest> requests) {
        List<SendResult> results = new ArrayList<>();

        Map<String, List<SendRequest>> byDomain = new HashMap<>();
        for (SendRequest req : requests) {
            byDomain.computeIfAbsent(req.domain(), k -> new ArrayList<>()).add(req);
        }

        Instant now = Instant.now();

        for (var entry : byDomain.entrySet()) {
            String domain = entry.getKey();
            List<SendRequest> domainRequests = entry.getValue();
            String queueName = domain + "__commands";

            List<UUID> commandIds = domainRequests.stream()
                .map(SendRequest::commandId)
                .toList();
            Set<UUID> existing = commandRepository.existsBatch(domain, commandIds);
            if (!existing.isEmpty()) {
                UUID firstDup = existing.iterator().next();
                throw new DuplicateCommandException(domain, firstDup.toString());
            }

            List<Map<String, Object>> messages = new ArrayList<>();
            for (SendRequest req : domainRequests) {
                UUID correlationId = req.correlationId() != null ? req.correlationId() : UUID.randomUUID();
                messages.add(buildMessage(domain, req.commandType(), req.commandId(),
                    req.data(), correlationId, req.replyTo()));
            }

            List<Long> msgIds = pgmqClient.sendBatch(queueName, messages);

            List<CommandMetadata> metadataList = new ArrayList<>();
            List<AuditRepository.AuditEventRecord> auditEvents = new ArrayList<>();

            for (int i = 0; i < domainRequests.size(); i++) {
                SendRequest req = domainRequests.get(i);
                long msgId = msgIds.get(i);
                int maxAttempts = req.maxAttempts() != null ? req.maxAttempts() : DEFAULT_MAX_ATTEMPTS;
                UUID correlationId = req.correlationId() != null ? req.correlationId() : UUID.randomUUID();

                metadataList.add(new CommandMetadata(
                    domain, req.commandId(), req.commandType(),
                    CommandStatus.PENDING,
                    0, maxAttempts,
                    msgId, correlationId, req.replyTo(),
                    null, null, null,
                    now, now, null
                ));

                auditEvents.add(new AuditRepository.AuditEventRecord(
                    domain, req.commandId(), AuditEventType.SENT,
                    Map.of("command_type", req.commandType(), "msg_id", msgId)
                ));

                results.add(new SendResult(req.commandId(), msgId));
            }

            commandRepository.saveBatch(metadataList, queueName);
            auditRepository.logBatch(auditEvents);

            pgmqClient.notify(queueName);
        }

        return results;
    }

    // --- Batch Management ---

    @Override
    public CreateBatchResult createBatch(String domain, List<BatchCommand> commands) {
        return createBatch(domain, commands, null, null, null, null);
    }

    @Override
    @Transactional
    public CreateBatchResult createBatch(
            String domain,
            List<BatchCommand> commands,
            UUID batchId,
            String name,
            Map<String, Object> customData,
            Consumer<BatchMetadata> onComplete) {

        if (commands.isEmpty()) {
            throw new IllegalArgumentException("Batch must contain at least one command");
        }

        Set<UUID> seen = new HashSet<>();
        for (BatchCommand cmd : commands) {
            if (!seen.add(cmd.commandId())) {
                throw new DuplicateCommandException(domain, cmd.commandId().toString());
            }
        }

        UUID effectiveBatchId = batchId != null ? batchId : UUID.randomUUID();
        String queueName = domain + "__commands";
        Instant now = Instant.now();

        List<UUID> commandIds = commands.stream().map(BatchCommand::commandId).toList();
        Set<UUID> existing = commandRepository.existsBatch(domain, commandIds);
        if (!existing.isEmpty()) {
            throw new DuplicateCommandException(domain, existing.iterator().next().toString());
        }

        BatchMetadata batchMetadata = new BatchMetadata(
            domain, effectiveBatchId, name, customData,
            BatchStatus.PENDING,
            commands.size(), 0, 0, 0,
            now, null, null,
            "COMMAND"
        );
        batchRepository.save(batchMetadata);

        List<Map<String, Object>> messages = new ArrayList<>();
        for (BatchCommand cmd : commands) {
            UUID correlationId = cmd.correlationId() != null ? cmd.correlationId() : UUID.randomUUID();
            messages.add(buildMessage(domain, cmd.commandType(), cmd.commandId(),
                cmd.data(), correlationId, cmd.replyTo()));
        }

        List<Long> msgIds = pgmqClient.sendBatch(queueName, messages);

        List<CommandMetadata> metadataList = new ArrayList<>();
        List<AuditRepository.AuditEventRecord> auditEvents = new ArrayList<>();
        List<SendResult> commandResults = new ArrayList<>();

        for (int i = 0; i < commands.size(); i++) {
            BatchCommand cmd = commands.get(i);
            long msgId = msgIds.get(i);
            int maxAttempts = cmd.maxAttempts() != null ? cmd.maxAttempts() : DEFAULT_MAX_ATTEMPTS;
            UUID correlationId = cmd.correlationId() != null ? cmd.correlationId() : UUID.randomUUID();

            metadataList.add(new CommandMetadata(
                domain, cmd.commandId(), cmd.commandType(),
                CommandStatus.PENDING,
                0, maxAttempts,
                msgId, correlationId, cmd.replyTo(),
                null, null, null,
                now, now, effectiveBatchId
            ));

            auditEvents.add(new AuditRepository.AuditEventRecord(
                domain, cmd.commandId(), AuditEventType.SENT,
                Map.of("command_type", cmd.commandType(), "msg_id", msgId, "batch_id", effectiveBatchId.toString())
            ));

            commandResults.add(new SendResult(cmd.commandId(), msgId));
        }

        commandRepository.saveBatch(metadataList, queueName);
        auditRepository.logBatch(auditEvents);

        pgmqClient.notify(queueName);

        if (onComplete != null) {
            batchCallbacks.put(effectiveBatchId, onComplete);
        }

        log.info("Created batch {} in domain {} with {} commands",
            effectiveBatchId, domain, commands.size());

        return new CreateBatchResult(effectiveBatchId, commandResults, commands.size());
    }

    @Override
    public BatchMetadata getBatch(String domain, UUID batchId) {
        return batchRepository.get(domain, batchId).orElse(null);
    }

    @Override
    public List<BatchMetadata> listBatches(String domain, BatchStatus status, int limit, int offset) {
        return batchRepository.listBatches(domain, status, limit, offset);
    }

    @Override
    public List<CommandMetadata> listBatchCommands(
            String domain, UUID batchId, CommandStatus status, int limit, int offset) {
        return commandRepository.listByBatch(domain, batchId, status, limit, offset);
    }

    // --- Query Operations ---

    @Override
    public CommandMetadata getCommand(String domain, UUID commandId) {
        return commandRepository.get(domain, commandId).orElse(null);
    }

    @Override
    public boolean commandExists(String domain, UUID commandId) {
        return commandRepository.exists(domain, commandId);
    }

    @Override
    public List<CommandMetadata> queryCommands(
            CommandStatus status,
            String domain,
            String commandType,
            Instant createdAfter,
            Instant createdBefore,
            int limit,
            int offset) {
        return commandRepository.query(status, domain, commandType,
            createdAfter, createdBefore, limit, offset);
    }

    @Override
    public List<AuditEvent> getAuditTrail(UUID commandId, String domain) {
        return auditRepository.getEvents(commandId, domain);
    }

    // --- Helper Methods ---

    private Map<String, Object> buildMessage(
            String domain,
            String commandType,
            UUID commandId,
            Map<String, Object> data,
            UUID correlationId,
            String replyTo) {

        Map<String, Object> message = new HashMap<>();
        message.put("domain", domain);
        message.put("command_type", commandType);
        message.put("command_id", commandId.toString());
        message.put("correlation_id", correlationId.toString());
        message.put("data", data != null ? data : Map.of());

        if (replyTo != null) {
            message.put("reply_to", replyTo);
        }

        return message;
    }

    /**
     * Invoke batch completion callback (called by Worker).
     *
     * @param batchId The batch ID
     * @param batch The batch metadata
     */
    public void invokeBatchCallback(UUID batchId, BatchMetadata batch) {
        Consumer<BatchMetadata> callback = batchCallbacks.remove(batchId);
        if (callback != null) {
            try {
                callback.accept(batch);
            } catch (Exception e) {
                log.error("Batch callback failed for batch {}", batchId, e);
            }
        }
    }
}
