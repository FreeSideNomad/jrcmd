package com.ivamare.commandbus.api.impl;

import com.ivamare.commandbus.exception.BatchNotFoundException;
import com.ivamare.commandbus.exception.DuplicateCommandException;
import com.ivamare.commandbus.model.*;
import com.ivamare.commandbus.pgmq.PgmqClient;
import com.ivamare.commandbus.repository.AuditRepository;
import com.ivamare.commandbus.repository.BatchRepository;
import com.ivamare.commandbus.repository.CommandRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DefaultCommandBusTest {

    @Mock
    private PgmqClient pgmqClient;

    @Mock
    private CommandRepository commandRepository;

    @Mock
    private BatchRepository batchRepository;

    @Mock
    private AuditRepository auditRepository;

    private DefaultCommandBus commandBus;

    @BeforeEach
    void setUp() {
        commandBus = new DefaultCommandBus(
            pgmqClient, commandRepository, batchRepository, auditRepository
        );
    }

    @Nested
    class SendTests {

        @Test
        void shouldSendCommand() {
            UUID commandId = UUID.randomUUID();
            when(commandRepository.exists("payments", commandId)).thenReturn(false);
            when(pgmqClient.send(eq("payments__commands"), anyMap())).thenReturn(123L);

            SendResult result = commandBus.send(
                "payments", "DebitAccount", commandId, Map.of("amount", 100)
            );

            assertEquals(commandId, result.commandId());
            assertEquals(123L, result.msgId());

            verify(commandRepository).save(any(CommandMetadata.class), eq("payments__commands"));
            verify(auditRepository).log(eq("payments"), eq(commandId), eq(AuditEventType.SENT), anyMap());
        }

        @Test
        void shouldSendCommandWithAllOptions() {
            UUID commandId = UUID.randomUUID();
            UUID correlationId = UUID.randomUUID();
            when(commandRepository.exists("payments", commandId)).thenReturn(false);
            when(pgmqClient.send(anyString(), anyMap())).thenReturn(456L);

            SendResult result = commandBus.send(
                "payments", "DebitAccount", commandId,
                Map.of("amount", 100),
                correlationId,
                "reply-queue",
                5
            );

            assertEquals(commandId, result.commandId());
            assertEquals(456L, result.msgId());

            ArgumentCaptor<CommandMetadata> captor = ArgumentCaptor.forClass(CommandMetadata.class);
            verify(commandRepository).save(captor.capture(), anyString());

            CommandMetadata saved = captor.getValue();
            assertEquals(correlationId, saved.correlationId());
            assertEquals("reply-queue", saved.replyTo());
            assertEquals(5, saved.maxAttempts());
        }

        @Test
        void shouldThrowOnDuplicateCommand() {
            UUID commandId = UUID.randomUUID();
            when(commandRepository.exists("payments", commandId)).thenReturn(true);

            assertThrows(DuplicateCommandException.class, () ->
                commandBus.send("payments", "DebitAccount", commandId, Map.of()));
        }

        @Test
        void shouldAutoGenerateCorrelationId() {
            UUID commandId = UUID.randomUUID();
            when(commandRepository.exists("payments", commandId)).thenReturn(false);
            when(pgmqClient.send(anyString(), anyMap())).thenReturn(1L);

            commandBus.send("payments", "DebitAccount", commandId, Map.of());

            ArgumentCaptor<CommandMetadata> captor = ArgumentCaptor.forClass(CommandMetadata.class);
            verify(commandRepository).save(captor.capture(), anyString());

            assertNotNull(captor.getValue().correlationId());
        }
    }

    @Nested
    class SendToBatchTests {

        @Test
        void shouldSendCommandToBatch() {
            UUID commandId = UUID.randomUUID();
            UUID batchId = UUID.randomUUID();

            when(batchRepository.exists("payments", batchId)).thenReturn(true);
            when(commandRepository.exists("payments", commandId)).thenReturn(false);
            when(pgmqClient.send(anyString(), anyMap())).thenReturn(789L);

            SendResult result = commandBus.sendToBatch(
                "payments", "DebitAccount", commandId, Map.of(), batchId
            );

            assertEquals(commandId, result.commandId());

            ArgumentCaptor<CommandMetadata> captor = ArgumentCaptor.forClass(CommandMetadata.class);
            verify(commandRepository).save(captor.capture(), anyString());

            assertEquals(batchId, captor.getValue().batchId());
        }

        @Test
        void shouldThrowWhenBatchNotFound() {
            UUID batchId = UUID.randomUUID();
            when(batchRepository.exists("payments", batchId)).thenReturn(false);

            assertThrows(BatchNotFoundException.class, () ->
                commandBus.sendToBatch("payments", "DebitAccount", UUID.randomUUID(), Map.of(), batchId));
        }
    }

    @Nested
    class SendBatchTests {

        @Test
        void shouldReturnEmptyResultForEmptyList() {
            BatchSendResult result = commandBus.sendBatch(List.of());

            assertEquals(0, result.totalCommands());
            assertEquals(0, result.chunksProcessed());
            assertTrue(result.results().isEmpty());
        }

        @Test
        void shouldSendBatchOfCommands() {
            UUID cmd1 = UUID.randomUUID();
            UUID cmd2 = UUID.randomUUID();

            when(commandRepository.existsBatch(eq("payments"), anyList())).thenReturn(Set.of());
            when(pgmqClient.sendBatch(eq("payments__commands"), anyList())).thenReturn(List.of(1L, 2L));

            List<SendRequest> requests = List.of(
                SendRequest.of("payments", "Debit", cmd1, Map.of()),
                SendRequest.of("payments", "Credit", cmd2, Map.of())
            );

            BatchSendResult result = commandBus.sendBatch(requests);

            assertEquals(2, result.totalCommands());
            assertEquals(1, result.chunksProcessed());
            assertEquals(2, result.results().size());

            verify(commandRepository).saveBatch(anyList(), eq("payments__commands"));
            verify(auditRepository).logBatch(anyList());
            verify(pgmqClient).notify("payments__commands");
        }

        @Test
        void shouldProcessInChunks() {
            when(commandRepository.existsBatch(anyString(), anyList())).thenReturn(Set.of());
            when(pgmqClient.sendBatch(anyString(), argThat(list -> list != null && list.size() == 2)))
                .thenReturn(List.of(1L, 2L));
            when(pgmqClient.sendBatch(anyString(), argThat(list -> list != null && list.size() == 1)))
                .thenReturn(List.of(3L));

            List<SendRequest> requests = List.of(
                SendRequest.of("payments", "Cmd1", UUID.randomUUID(), Map.of()),
                SendRequest.of("payments", "Cmd2", UUID.randomUUID(), Map.of()),
                SendRequest.of("payments", "Cmd3", UUID.randomUUID(), Map.of())
            );

            BatchSendResult result = commandBus.sendBatch(requests, 2);

            assertEquals(3, result.totalCommands());
            assertEquals(2, result.chunksProcessed());
        }

        @Test
        void shouldThrowOnDuplicateInBatch() {
            UUID duplicateId = UUID.randomUUID();
            when(commandRepository.existsBatch(eq("payments"), anyList()))
                .thenReturn(Set.of(duplicateId));

            List<SendRequest> requests = List.of(
                SendRequest.of("payments", "Cmd", duplicateId, Map.of())
            );

            assertThrows(DuplicateCommandException.class, () ->
                commandBus.sendBatch(requests));
        }
    }

    @Nested
    class CreateBatchTests {

        @Test
        void shouldCreateBatch() {
            UUID cmd1 = UUID.randomUUID();
            UUID cmd2 = UUID.randomUUID();

            when(commandRepository.existsBatch(eq("orders"), anyList())).thenReturn(Set.of());
            when(pgmqClient.sendBatch(eq("orders__commands"), anyList())).thenReturn(List.of(1L, 2L));

            List<BatchCommand> commands = List.of(
                BatchCommand.of("CreateOrder", cmd1, Map.of()),
                BatchCommand.of("CreateOrder", cmd2, Map.of())
            );

            CreateBatchResult result = commandBus.createBatch("orders", commands);

            assertNotNull(result.batchId());
            assertEquals(2, result.totalCommands());
            assertEquals(2, result.commandResults().size());

            verify(batchRepository).save(any(BatchMetadata.class));
            verify(commandRepository).saveBatch(anyList(), eq("orders__commands"));
        }

        @Test
        void shouldThrowForEmptyCommands() {
            assertThrows(IllegalArgumentException.class, () ->
                commandBus.createBatch("orders", List.of()));
        }

        @Test
        void shouldThrowOnDuplicateCommandIdInBatch() {
            UUID sameId = UUID.randomUUID();

            List<BatchCommand> commands = List.of(
                BatchCommand.of("Cmd1", sameId, Map.of()),
                BatchCommand.of("Cmd2", sameId, Map.of())
            );

            assertThrows(DuplicateCommandException.class, () ->
                commandBus.createBatch("orders", commands));
        }

        @Test
        void shouldCreateBatchWithOptions() {
            UUID batchId = UUID.randomUUID();
            UUID cmdId = UUID.randomUUID();
            AtomicReference<BatchMetadata> callbackReceived = new AtomicReference<>();

            when(commandRepository.existsBatch(eq("orders"), anyList())).thenReturn(Set.of());
            when(pgmqClient.sendBatch(anyString(), anyList())).thenReturn(List.of(1L));

            CreateBatchResult result = commandBus.createBatch(
                "orders",
                List.of(BatchCommand.of("CreateOrder", cmdId, Map.of())),
                batchId,
                "Test Batch",
                Map.of("key", "value"),
                callbackReceived::set
            );

            assertEquals(batchId, result.batchId());

            ArgumentCaptor<BatchMetadata> captor = ArgumentCaptor.forClass(BatchMetadata.class);
            verify(batchRepository).save(captor.capture());

            BatchMetadata saved = captor.getValue();
            assertEquals("Test Batch", saved.name());
            assertEquals(Map.of("key", "value"), saved.customData());
        }
    }

    @Nested
    class BatchQueryTests {

        @Test
        void shouldGetBatch() {
            UUID batchId = UUID.randomUUID();
            BatchMetadata expected = BatchMetadata.create("orders", batchId, "Test", null, 5);
            when(batchRepository.get("orders", batchId)).thenReturn(Optional.of(expected));

            BatchMetadata result = commandBus.getBatch("orders", batchId);

            assertEquals(expected, result);
        }

        @Test
        void shouldReturnNullForMissingBatch() {
            when(batchRepository.get(anyString(), any())).thenReturn(Optional.empty());

            BatchMetadata result = commandBus.getBatch("orders", UUID.randomUUID());

            assertNull(result);
        }

        @Test
        void shouldListBatches() {
            commandBus.listBatches("orders", BatchStatus.PENDING, 10, 0);

            verify(batchRepository).listBatches("orders", BatchStatus.PENDING, 10, 0);
        }

        @Test
        void shouldListBatchCommands() {
            UUID batchId = UUID.randomUUID();

            commandBus.listBatchCommands("orders", batchId, CommandStatus.COMPLETED, 10, 0);

            verify(commandRepository).listByBatch("orders", batchId, CommandStatus.COMPLETED, 10, 0);
        }
    }

    @Nested
    class QueryTests {

        @Test
        void shouldGetCommand() {
            UUID commandId = UUID.randomUUID();
            CommandMetadata expected = CommandMetadata.create("payments", commandId, "Debit", 3);
            when(commandRepository.get("payments", commandId)).thenReturn(Optional.of(expected));

            CommandMetadata result = commandBus.getCommand("payments", commandId);

            assertEquals(expected, result);
        }

        @Test
        void shouldReturnNullForMissingCommand() {
            when(commandRepository.get(anyString(), any())).thenReturn(Optional.empty());

            CommandMetadata result = commandBus.getCommand("payments", UUID.randomUUID());

            assertNull(result);
        }

        @Test
        void shouldCheckCommandExists() {
            UUID commandId = UUID.randomUUID();
            when(commandRepository.exists("payments", commandId)).thenReturn(true);

            assertTrue(commandBus.commandExists("payments", commandId));
        }

        @Test
        void shouldQueryCommands() {
            Instant after = Instant.now().minusSeconds(3600);
            Instant before = Instant.now();

            commandBus.queryCommands(
                CommandStatus.PENDING, "payments", "Debit",
                after, before, 10, 0
            );

            verify(commandRepository).query(
                CommandStatus.PENDING, "payments", "Debit",
                after, before, 10, 0
            );
        }

        @Test
        void shouldGetAuditTrail() {
            UUID commandId = UUID.randomUUID();

            commandBus.getAuditTrail(commandId, "payments");

            verify(auditRepository).getEvents(commandId, "payments");
        }
    }

    @Nested
    class BatchCallbackTests {

        @Test
        void shouldInvokeBatchCallback() {
            UUID batchId = UUID.randomUUID();
            AtomicReference<BatchMetadata> received = new AtomicReference<>();

            when(commandRepository.existsBatch(anyString(), anyList())).thenReturn(Set.of());
            when(pgmqClient.sendBatch(anyString(), anyList())).thenReturn(List.of(1L));

            commandBus.createBatch(
                "orders",
                List.of(BatchCommand.of("Cmd", UUID.randomUUID(), Map.of())),
                batchId, null, null,
                received::set
            );

            BatchMetadata completed = BatchMetadata.create("orders", batchId, null, null, 1);
            commandBus.invokeBatchCallback(batchId, completed);

            assertNotNull(received.get());
            assertEquals(batchId, received.get().batchId());
        }

        @Test
        void shouldNotFailOnMissingCallback() {
            assertDoesNotThrow(() ->
                commandBus.invokeBatchCallback(UUID.randomUUID(), null));
        }
    }
}
