package com.commandbus.ops.impl;

import com.commandbus.exception.CommandNotFoundException;
import com.commandbus.exception.InvalidOperationException;
import com.commandbus.model.*;
import com.commandbus.ops.TroubleshootingQueue;
import com.commandbus.pgmq.PgmqClient;
import com.commandbus.repository.AuditRepository;
import com.commandbus.repository.BatchRepository;
import com.commandbus.repository.CommandRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DefaultTroubleshootingQueue")
class DefaultTroubleshootingQueueTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private PgmqClient pgmqClient;

    @Mock
    private CommandRepository commandRepository;

    @Mock
    private BatchRepository batchRepository;

    @Mock
    private AuditRepository auditRepository;

    private ObjectMapper objectMapper;

    private DefaultTroubleshootingQueue tsq;

    private static final String DOMAIN = "test";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        tsq = new DefaultTroubleshootingQueue(
            jdbcTemplate,
            pgmqClient,
            commandRepository,
            batchRepository,
            auditRepository,
            objectMapper
        );
    }

    @Nested
    @DisplayName("countTroubleshooting")
    class CountTroubleshootingTests {

        @Test
        @DisplayName("should count commands in TSQ")
        void shouldCountCommandsInTsq() {
            when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenReturn(5);

            int count = tsq.countTroubleshooting(DOMAIN, null);

            assertEquals(5, count);
        }

        @Test
        @DisplayName("should count with command type filter")
        void shouldCountWithCommandTypeFilter() {
            when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenReturn(3);

            int count = tsq.countTroubleshooting(DOMAIN, "TestCommand");

            assertEquals(3, count);
        }

        @Test
        @DisplayName("should return zero when null count")
        void shouldReturnZeroWhenNullCount() {
            when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenReturn(null);

            int count = tsq.countTroubleshooting(DOMAIN, null);

            assertEquals(0, count);
        }
    }

    @Nested
    @DisplayName("listDomains")
    class ListDomainsTests {

        @Test
        @DisplayName("should list domains with TSQ items")
        void shouldListDomainsWithTsqItems() {
            when(jdbcTemplate.queryForList(anyString(), eq(String.class), any()))
                .thenReturn(List.of("payments", "orders"));

            List<String> domains = tsq.listDomains();

            assertEquals(2, domains.size());
            assertTrue(domains.contains("payments"));
            assertTrue(domains.contains("orders"));
        }

        @Test
        @DisplayName("should return empty list when no TSQ items")
        void shouldReturnEmptyListWhenNoTsqItems() {
            when(jdbcTemplate.queryForList(anyString(), eq(String.class), any()))
                .thenReturn(List.of());

            List<String> domains = tsq.listDomains();

            assertTrue(domains.isEmpty());
        }
    }

    @Nested
    @DisplayName("getCommandDomain")
    class GetCommandDomainTests {

        @Test
        @DisplayName("should return domain for command")
        @SuppressWarnings("unchecked")
        void shouldReturnDomainForCommand() {
            UUID commandId = UUID.randomUUID();
            when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object.class)))
                .thenReturn(List.of(DOMAIN));

            String domain = tsq.getCommandDomain(commandId);

            assertEquals(DOMAIN, domain);
        }

        @Test
        @DisplayName("should throw when command not found")
        @SuppressWarnings("unchecked")
        void shouldThrowWhenCommandNotFound() {
            UUID commandId = UUID.randomUUID();
            when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object.class)))
                .thenReturn(List.of());

            assertThrows(CommandNotFoundException.class, () ->
                tsq.getCommandDomain(commandId)
            );
        }
    }

    @Nested
    @DisplayName("operatorRetry")
    class OperatorRetryTests {

        @Test
        @DisplayName("should retry command from TSQ")
        void shouldRetryCommandFromTsq() {
            UUID commandId = UUID.randomUUID();
            CommandMetadata metadata = createMetadata(commandId, CommandStatus.IN_TROUBLESHOOTING_QUEUE, null);
            Map<String, Object> payload = Map.of("command_id", commandId.toString());
            PgmqMessage archived = new PgmqMessage(100L, 0, Instant.now(), Instant.now(), payload);

            when(commandRepository.get(DOMAIN, commandId)).thenReturn(Optional.of(metadata));
            when(pgmqClient.getFromArchive(eq(DOMAIN + "__commands"), eq(commandId.toString())))
                .thenReturn(Optional.of(archived));
            when(pgmqClient.send(eq(DOMAIN + "__commands"), eq(payload))).thenReturn(200L);

            long newMsgId = tsq.operatorRetry(DOMAIN, commandId, "admin");

            assertEquals(200L, newMsgId);
            verify(jdbcTemplate).update(anyString(),
                eq(CommandStatus.PENDING.getValue()), eq(200L), eq(DOMAIN), eq(commandId));
            verify(auditRepository).log(eq(DOMAIN), eq(commandId), eq(AuditEventType.OPERATOR_RETRY), anyMap());
        }

        @Test
        @DisplayName("should throw when command not found")
        void shouldThrowWhenCommandNotFound() {
            UUID commandId = UUID.randomUUID();
            when(commandRepository.get(DOMAIN, commandId)).thenReturn(Optional.empty());

            assertThrows(CommandNotFoundException.class, () ->
                tsq.operatorRetry(DOMAIN, commandId, "admin")
            );
        }

        @Test
        @DisplayName("should throw when not in TSQ")
        void shouldThrowWhenNotInTsq() {
            UUID commandId = UUID.randomUUID();
            CommandMetadata metadata = createMetadata(commandId, CommandStatus.COMPLETED, null);

            when(commandRepository.get(DOMAIN, commandId)).thenReturn(Optional.of(metadata));

            assertThrows(InvalidOperationException.class, () ->
                tsq.operatorRetry(DOMAIN, commandId, "admin")
            );
        }

        @Test
        @DisplayName("should throw when payload not in archive")
        void shouldThrowWhenPayloadNotInArchive() {
            UUID commandId = UUID.randomUUID();
            CommandMetadata metadata = createMetadata(commandId, CommandStatus.IN_TROUBLESHOOTING_QUEUE, null);

            when(commandRepository.get(DOMAIN, commandId)).thenReturn(Optional.of(metadata));
            when(pgmqClient.getFromArchive(anyString(), anyString())).thenReturn(Optional.empty());

            assertThrows(InvalidOperationException.class, () ->
                tsq.operatorRetry(DOMAIN, commandId, "admin")
            );
        }

        @Test
        @DisplayName("should update batch counters on retry")
        void shouldUpdateBatchCountersOnRetry() {
            UUID commandId = UUID.randomUUID();
            UUID batchId = UUID.randomUUID();
            CommandMetadata metadata = createMetadata(commandId, CommandStatus.IN_TROUBLESHOOTING_QUEUE, batchId);
            Map<String, Object> payload = Map.of("command_id", commandId.toString());
            PgmqMessage archived = new PgmqMessage(100L, 0, Instant.now(), Instant.now(), payload);

            when(commandRepository.get(DOMAIN, commandId)).thenReturn(Optional.of(metadata));
            when(pgmqClient.getFromArchive(anyString(), anyString())).thenReturn(Optional.of(archived));
            when(pgmqClient.send(anyString(), anyMap())).thenReturn(200L);

            tsq.operatorRetry(DOMAIN, commandId, "admin");

            verify(batchRepository).tsqRetry(DOMAIN, batchId);
        }
    }

    @Nested
    @DisplayName("operatorCancel")
    class OperatorCancelTests {

        @Test
        @DisplayName("should cancel command in TSQ")
        void shouldCancelCommandInTsq() {
            UUID commandId = UUID.randomUUID();
            CommandMetadata metadata = createMetadata(commandId, CommandStatus.IN_TROUBLESHOOTING_QUEUE, null);

            when(commandRepository.get(DOMAIN, commandId)).thenReturn(Optional.of(metadata));

            tsq.operatorCancel(DOMAIN, commandId, "Invalid data", "admin");

            verify(jdbcTemplate).update(anyString(),
                eq(CommandStatus.CANCELED.getValue()), eq(DOMAIN), eq(commandId));
            verify(auditRepository).log(eq(DOMAIN), eq(commandId), eq(AuditEventType.OPERATOR_CANCEL), anyMap());
        }

        @Test
        @DisplayName("should throw when command not found")
        void shouldThrowWhenCommandNotFound() {
            UUID commandId = UUID.randomUUID();
            when(commandRepository.get(DOMAIN, commandId)).thenReturn(Optional.empty());

            assertThrows(CommandNotFoundException.class, () ->
                tsq.operatorCancel(DOMAIN, commandId, "reason", "admin")
            );
        }

        @Test
        @DisplayName("should throw when not in TSQ")
        void shouldThrowWhenNotInTsq() {
            UUID commandId = UUID.randomUUID();
            CommandMetadata metadata = createMetadata(commandId, CommandStatus.PENDING, null);

            when(commandRepository.get(DOMAIN, commandId)).thenReturn(Optional.of(metadata));

            assertThrows(InvalidOperationException.class, () ->
                tsq.operatorCancel(DOMAIN, commandId, "reason", "admin")
            );
        }

        @Test
        @DisplayName("should send canceled reply when reply_to configured")
        void shouldSendCanceledReplyWhenReplyToConfigured() {
            UUID commandId = UUID.randomUUID();
            CommandMetadata metadata = createMetadataWithReplyTo(commandId, "reply_queue");

            when(commandRepository.get(DOMAIN, commandId)).thenReturn(Optional.of(metadata));
            when(pgmqClient.send(eq("reply_queue"), anyMap())).thenReturn(1L);

            tsq.operatorCancel(DOMAIN, commandId, "Invalid", "admin");

            verify(pgmqClient).send(eq("reply_queue"), argThat(reply ->
                reply.get("outcome").equals("CANCELED") &&
                reply.get("reason").equals("Invalid")
            ));
        }

        @Test
        @DisplayName("should update batch counters on cancel")
        void shouldUpdateBatchCountersOnCancel() {
            UUID commandId = UUID.randomUUID();
            UUID batchId = UUID.randomUUID();
            CommandMetadata metadata = createMetadata(commandId, CommandStatus.IN_TROUBLESHOOTING_QUEUE, batchId);

            when(commandRepository.get(DOMAIN, commandId)).thenReturn(Optional.of(metadata));
            when(batchRepository.tsqCancel(DOMAIN, batchId)).thenReturn(false);

            tsq.operatorCancel(DOMAIN, commandId, "reason", "admin");

            verify(batchRepository).tsqCancel(DOMAIN, batchId);
        }
    }

    @Nested
    @DisplayName("operatorComplete")
    class OperatorCompleteTests {

        @Test
        @DisplayName("should complete command in TSQ")
        void shouldCompleteCommandInTsq() {
            UUID commandId = UUID.randomUUID();
            CommandMetadata metadata = createMetadata(commandId, CommandStatus.IN_TROUBLESHOOTING_QUEUE, null);

            when(commandRepository.get(DOMAIN, commandId)).thenReturn(Optional.of(metadata));

            tsq.operatorComplete(DOMAIN, commandId, Map.of("manual", true), "admin");

            verify(jdbcTemplate).update(anyString(),
                eq(CommandStatus.COMPLETED.getValue()), eq(DOMAIN), eq(commandId));
            verify(auditRepository).log(eq(DOMAIN), eq(commandId), eq(AuditEventType.OPERATOR_COMPLETE), anyMap());
        }

        @Test
        @DisplayName("should throw when command not found")
        void shouldThrowWhenCommandNotFound() {
            UUID commandId = UUID.randomUUID();
            when(commandRepository.get(DOMAIN, commandId)).thenReturn(Optional.empty());

            assertThrows(CommandNotFoundException.class, () ->
                tsq.operatorComplete(DOMAIN, commandId, null, "admin")
            );
        }

        @Test
        @DisplayName("should throw when not in TSQ")
        void shouldThrowWhenNotInTsq() {
            UUID commandId = UUID.randomUUID();
            CommandMetadata metadata = createMetadata(commandId, CommandStatus.CANCELED, null);

            when(commandRepository.get(DOMAIN, commandId)).thenReturn(Optional.of(metadata));

            assertThrows(InvalidOperationException.class, () ->
                tsq.operatorComplete(DOMAIN, commandId, null, "admin")
            );
        }

        @Test
        @DisplayName("should send success reply when reply_to configured")
        void shouldSendSuccessReplyWhenReplyToConfigured() {
            UUID commandId = UUID.randomUUID();
            UUID correlationId = UUID.randomUUID();
            CommandMetadata metadata = createMetadataWithCorrelation(commandId, "reply_queue", correlationId);

            when(commandRepository.get(DOMAIN, commandId)).thenReturn(Optional.of(metadata));
            when(pgmqClient.send(eq("reply_queue"), anyMap())).thenReturn(1L);

            tsq.operatorComplete(DOMAIN, commandId, Map.of("data", "value"), "admin");

            verify(pgmqClient).send(eq("reply_queue"), argThat(reply ->
                reply.get("outcome").equals("SUCCESS") &&
                reply.get("correlation_id").equals(correlationId.toString()) &&
                reply.containsKey("result")
            ));
        }

        @Test
        @DisplayName("should update batch counters on complete")
        void shouldUpdateBatchCountersOnComplete() {
            UUID commandId = UUID.randomUUID();
            UUID batchId = UUID.randomUUID();
            CommandMetadata metadata = createMetadata(commandId, CommandStatus.IN_TROUBLESHOOTING_QUEUE, batchId);

            when(commandRepository.get(DOMAIN, commandId)).thenReturn(Optional.of(metadata));
            when(batchRepository.tsqComplete(DOMAIN, batchId)).thenReturn(true);

            tsq.operatorComplete(DOMAIN, commandId, null, "admin");

            verify(batchRepository).tsqComplete(DOMAIN, batchId);
        }

        @Test
        @DisplayName("should complete without result data")
        void shouldCompleteWithoutResultData() {
            UUID commandId = UUID.randomUUID();
            CommandMetadata metadata = createMetadata(commandId, CommandStatus.IN_TROUBLESHOOTING_QUEUE, null);

            when(commandRepository.get(DOMAIN, commandId)).thenReturn(Optional.of(metadata));

            tsq.operatorComplete(DOMAIN, commandId, null, "admin");

            verify(jdbcTemplate).update(anyString(),
                eq(CommandStatus.COMPLETED.getValue()), eq(DOMAIN), eq(commandId));
        }
    }

    @Nested
    @DisplayName("listAllTroubleshooting")
    class ListAllTroubleshootingTests {

        @Test
        @DisplayName("should return empty result when no domains")
        void shouldReturnEmptyResultWhenNoDomains() {
            when(jdbcTemplate.queryForList(anyString(), eq(String.class), any()))
                .thenReturn(List.of());

            var result = tsq.listAllTroubleshooting(10, 0, null);

            assertTrue(result.items().isEmpty());
            assertEquals(0, result.totalCount());
            assertTrue(result.commandIds().isEmpty());
        }

        @Test
        @DisplayName("should filter by domain")
        @SuppressWarnings("unchecked")
        void shouldFilterByDomain() {
            when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenReturn(0);
            when(jdbcTemplate.query(contains("SELECT command_id"), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

            var result = tsq.listAllTroubleshooting(10, 0, DOMAIN);

            assertNotNull(result);
        }

        @Test
        @DisplayName("should skip domains with offset")
        @SuppressWarnings("unchecked")
        void shouldSkipDomainsWithOffset() {
            when(jdbcTemplate.queryForList(anyString(), eq(String.class), any()))
                .thenReturn(List.of("domain1", "domain2"));
            // domain1 has 5 items, domain2 has 3 items
            when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenReturn(5, 3);
            when(jdbcTemplate.query(contains("SELECT command_id"), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
            when(jdbcTemplate.query(contains("DISTINCT ON"), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

            // Offset 5 should skip all of domain1
            var result = tsq.listAllTroubleshooting(10, 5, null);

            assertNotNull(result);
        }

        @Test
        @DisplayName("should handle remaining limit zero")
        @SuppressWarnings("unchecked")
        void shouldHandleRemainingLimitZero() {
            when(jdbcTemplate.queryForList(anyString(), eq(String.class), any()))
                .thenReturn(List.of("domain1", "domain2"));
            when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenReturn(5, 5);
            when(jdbcTemplate.query(contains("SELECT command_id"), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
            when(jdbcTemplate.query(contains("DISTINCT ON"), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

            // Limit 3 should only fetch 3 items total
            var result = tsq.listAllTroubleshooting(3, 0, null);

            assertNotNull(result);
        }
    }

    @Nested
    @DisplayName("listTroubleshooting")
    class ListTroubleshootingTests {

        @Test
        @DisplayName("should list troubleshooting items without command type filter")
        @SuppressWarnings("unchecked")
        void shouldListTroubleshootingItemsWithoutFilter() {
            when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

            List<TroubleshootingItem> items = tsq.listTroubleshooting(DOMAIN, null, 10, 0);

            assertNotNull(items);
        }

        @Test
        @DisplayName("should list troubleshooting items with command type filter")
        @SuppressWarnings("unchecked")
        void shouldListTroubleshootingItemsWithFilter() {
            when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

            List<TroubleshootingItem> items = tsq.listTroubleshooting(DOMAIN, "TestCommand", 10, 0);

            assertNotNull(items);
        }
    }

    @Nested
    @DisplayName("operatorRetry edge cases")
    class OperatorRetryEdgeCaseTests {

        @Test
        @DisplayName("should handle null operator")
        void shouldHandleNullOperator() {
            UUID commandId = UUID.randomUUID();
            CommandMetadata metadata = createMetadata(commandId, CommandStatus.IN_TROUBLESHOOTING_QUEUE, null);
            Map<String, Object> payload = Map.of("command_id", commandId.toString());
            PgmqMessage archived = new PgmqMessage(100L, 0, Instant.now(), Instant.now(), payload);

            when(commandRepository.get(DOMAIN, commandId)).thenReturn(Optional.of(metadata));
            when(pgmqClient.getFromArchive(anyString(), anyString())).thenReturn(Optional.of(archived));
            when(pgmqClient.send(anyString(), anyMap())).thenReturn(200L);

            long newMsgId = tsq.operatorRetry(DOMAIN, commandId, null);

            assertEquals(200L, newMsgId);
            verify(auditRepository).log(eq(DOMAIN), eq(commandId), eq(AuditEventType.OPERATOR_RETRY),
                argThat(map -> "unknown".equals(map.get("operator"))));
        }
    }

    @Nested
    @DisplayName("operatorCancel edge cases")
    class OperatorCancelEdgeCaseTests {

        @Test
        @DisplayName("should handle null operator")
        void shouldHandleNullOperator() {
            UUID commandId = UUID.randomUUID();
            CommandMetadata metadata = createMetadata(commandId, CommandStatus.IN_TROUBLESHOOTING_QUEUE, null);

            when(commandRepository.get(DOMAIN, commandId)).thenReturn(Optional.of(metadata));

            tsq.operatorCancel(DOMAIN, commandId, "reason", null);

            verify(auditRepository).log(eq(DOMAIN), eq(commandId), eq(AuditEventType.OPERATOR_CANCEL),
                argThat(map -> "unknown".equals(map.get("operator"))));
        }

        @Test
        @DisplayName("should not send reply when reply_to null")
        void shouldNotSendReplyWhenReplyToNull() {
            UUID commandId = UUID.randomUUID();
            CommandMetadata metadata = createMetadata(commandId, CommandStatus.IN_TROUBLESHOOTING_QUEUE, null);

            when(commandRepository.get(DOMAIN, commandId)).thenReturn(Optional.of(metadata));

            tsq.operatorCancel(DOMAIN, commandId, "reason", "admin");

            verify(pgmqClient, never()).send(anyString(), anyMap());
        }

        @Test
        @DisplayName("should invoke batch callback when batch completes")
        void shouldInvokeBatchCallbackWhenBatchCompletes() {
            UUID commandId = UUID.randomUUID();
            UUID batchId = UUID.randomUUID();
            CommandMetadata metadata = createMetadata(commandId, CommandStatus.IN_TROUBLESHOOTING_QUEUE, batchId);

            when(commandRepository.get(DOMAIN, commandId)).thenReturn(Optional.of(metadata));
            when(batchRepository.tsqCancel(DOMAIN, batchId)).thenReturn(true);

            tsq.operatorCancel(DOMAIN, commandId, "reason", "admin");

            // Batch callback should be invoked (logs message)
            verify(batchRepository).tsqCancel(DOMAIN, batchId);
        }
    }

    @Nested
    @DisplayName("operatorComplete edge cases")
    class OperatorCompleteEdgeCaseTests {

        @Test
        @DisplayName("should handle null operator")
        void shouldHandleNullOperator() {
            UUID commandId = UUID.randomUUID();
            CommandMetadata metadata = createMetadata(commandId, CommandStatus.IN_TROUBLESHOOTING_QUEUE, null);

            when(commandRepository.get(DOMAIN, commandId)).thenReturn(Optional.of(metadata));

            tsq.operatorComplete(DOMAIN, commandId, null, null);

            verify(auditRepository).log(eq(DOMAIN), eq(commandId), eq(AuditEventType.OPERATOR_COMPLETE),
                argThat(map -> "unknown".equals(map.get("operator"))));
        }

        @Test
        @DisplayName("should not send reply when reply_to null")
        void shouldNotSendReplyWhenReplyToNull() {
            UUID commandId = UUID.randomUUID();
            CommandMetadata metadata = createMetadata(commandId, CommandStatus.IN_TROUBLESHOOTING_QUEUE, null);

            when(commandRepository.get(DOMAIN, commandId)).thenReturn(Optional.of(metadata));

            tsq.operatorComplete(DOMAIN, commandId, Map.of("data", "value"), "admin");

            verify(pgmqClient, never()).send(anyString(), anyMap());
        }

        @Test
        @DisplayName("should invoke batch callback when batch completes")
        void shouldInvokeBatchCallbackWhenBatchCompletes() {
            UUID commandId = UUID.randomUUID();
            UUID batchId = UUID.randomUUID();
            CommandMetadata metadata = createMetadata(commandId, CommandStatus.IN_TROUBLESHOOTING_QUEUE, batchId);

            when(commandRepository.get(DOMAIN, commandId)).thenReturn(Optional.of(metadata));
            when(batchRepository.tsqComplete(DOMAIN, batchId)).thenReturn(true);

            tsq.operatorComplete(DOMAIN, commandId, null, "admin");

            // Batch callback should be invoked
            verify(batchRepository).tsqComplete(DOMAIN, batchId);
        }

        @Test
        @DisplayName("should send reply without correlation id")
        void shouldSendReplyWithoutCorrelationId() {
            UUID commandId = UUID.randomUUID();
            CommandMetadata metadata = createMetadataWithReplyTo(commandId, "reply_queue");

            when(commandRepository.get(DOMAIN, commandId)).thenReturn(Optional.of(metadata));
            when(pgmqClient.send(eq("reply_queue"), anyMap())).thenReturn(1L);

            tsq.operatorComplete(DOMAIN, commandId, null, "admin");

            verify(pgmqClient).send(eq("reply_queue"), argThat(reply ->
                !reply.containsKey("correlation_id")
            ));
        }
    }

    private CommandMetadata createMetadata(UUID commandId, CommandStatus status, UUID batchId) {
        return new CommandMetadata(
            DOMAIN, commandId, "TestCommand", status,
            1, 3, 100L, null, null, null, null, null,
            Instant.now(), Instant.now(), batchId
        );
    }

    private CommandMetadata createMetadataWithReplyTo(UUID commandId, String replyTo) {
        return new CommandMetadata(
            DOMAIN, commandId, "TestCommand", CommandStatus.IN_TROUBLESHOOTING_QUEUE,
            1, 3, 100L, null, replyTo, null, null, null,
            Instant.now(), Instant.now(), null
        );
    }

    private CommandMetadata createMetadataWithCorrelation(UUID commandId, String replyTo, UUID correlationId) {
        return new CommandMetadata(
            DOMAIN, commandId, "TestCommand", CommandStatus.IN_TROUBLESHOOTING_QUEUE,
            1, 3, 100L, correlationId, replyTo, null, null, null,
            Instant.now(), Instant.now(), null
        );
    }
}
