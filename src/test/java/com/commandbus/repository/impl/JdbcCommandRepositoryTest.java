package com.commandbus.repository.impl;

import com.commandbus.model.CommandMetadata;
import com.commandbus.model.CommandStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JdbcCommandRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private JdbcCommandRepository repository;

    @BeforeEach
    void setUp() {
        repository = new JdbcCommandRepository(jdbcTemplate);
    }

    private CommandMetadata createTestMetadata() {
        return CommandMetadata.create("payments", UUID.randomUUID(), "DebitAccount", 3);
    }

    @Nested
    class SaveTests {

        @Test
        void shouldSaveCommandMetadata() {
            CommandMetadata metadata = createTestMetadata();

            repository.save(metadata, "payments__commands");

            verify(jdbcTemplate).update(
                contains("INSERT INTO commandbus.command"),
                eq(metadata.domain()),
                eq("payments__commands"),
                eq(metadata.msgId()),
                eq(metadata.commandId()),
                eq(metadata.commandType()),
                eq(metadata.status().getValue()),
                eq(metadata.attempts()),
                eq(metadata.maxAttempts()),
                eq(metadata.correlationId()),
                eq(metadata.replyTo()),
                eq(metadata.batchId()),
                any(), any()
            );
        }
    }

    @Nested
    class SaveBatchTests {

        @Test
        void shouldNotExecuteForEmptyList() {
            repository.saveBatch(List.of(), "queue");
            verifyNoInteractions(jdbcTemplate);
        }

        @Test
        void shouldBatchInsertMetadata() {
            List<CommandMetadata> metadataList = List.of(
                createTestMetadata(),
                createTestMetadata()
            );

            repository.saveBatch(metadataList, "payments__commands");

            verify(jdbcTemplate).batchUpdate(
                contains("INSERT INTO commandbus.command"),
                anyList()
            );
        }
    }

    @Nested
    class GetTests {

        @Test
        @SuppressWarnings("unchecked")
        void shouldReturnMetadataWhenFound() {
            CommandMetadata expected = createTestMetadata();
            UUID commandId = expected.commandId();

            when(jdbcTemplate.query(
                contains("SELECT * FROM commandbus.command WHERE domain"),
                any(RowMapper.class),
                eq("payments"), eq(commandId)
            )).thenReturn(List.of(expected));

            Optional<CommandMetadata> result = repository.get("payments", commandId);

            assertTrue(result.isPresent());
            assertEquals(expected, result.get());
        }

        @Test
        @SuppressWarnings("unchecked")
        void shouldReturnEmptyWhenNotFound() {
            when(jdbcTemplate.query(
                anyString(),
                any(RowMapper.class),
                any(), any()
            )).thenReturn(List.of());

            Optional<CommandMetadata> result = repository.get("payments", UUID.randomUUID());

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    class ExistsTests {

        @Test
        void shouldReturnTrueWhenExists() {
            when(jdbcTemplate.queryForObject(
                contains("SELECT COUNT(*)"),
                eq(Integer.class),
                any(), any()
            )).thenReturn(1);

            assertTrue(repository.exists("payments", UUID.randomUUID()));
        }

        @Test
        void shouldReturnFalseWhenNotExists() {
            when(jdbcTemplate.queryForObject(
                anyString(),
                eq(Integer.class),
                any(), any()
            )).thenReturn(0);

            assertFalse(repository.exists("payments", UUID.randomUUID()));
        }

        @Test
        void shouldReturnFalseWhenNull() {
            when(jdbcTemplate.queryForObject(
                anyString(),
                eq(Integer.class),
                any(), any()
            )).thenReturn(null);

            assertFalse(repository.exists("payments", UUID.randomUUID()));
        }
    }

    @Nested
    class ExistsBatchTests {

        @Test
        void shouldReturnEmptySetForEmptyInput() {
            Set<UUID> result = repository.existsBatch("payments", List.of());
            assertTrue(result.isEmpty());
            verifyNoInteractions(jdbcTemplate);
        }

        @Test
        @SuppressWarnings("unchecked")
        void shouldReturnExistingIds() {
            UUID id1 = UUID.randomUUID();
            UUID id2 = UUID.randomUUID();
            UUID id3 = UUID.randomUUID();

            when(jdbcTemplate.query(
                contains("SELECT command_id"),
                any(RowMapper.class),
                any(Object[].class)
            )).thenReturn(List.of(id1, id3));

            Set<UUID> result = repository.existsBatch("payments", List.of(id1, id2, id3));

            assertEquals(Set.of(id1, id3), result);
        }
    }

    @Nested
    class UpdateStatusTests {

        @Test
        void shouldUpdateStatus() {
            UUID commandId = UUID.randomUUID();

            repository.updateStatus("payments", commandId, CommandStatus.COMPLETED);

            verify(jdbcTemplate).update(
                contains("UPDATE commandbus.command SET status"),
                eq(CommandStatus.COMPLETED.getValue()),
                eq("payments"),
                eq(commandId)
            );
        }
    }

    @Nested
    class QueryTests {

        @Test
        @SuppressWarnings("unchecked")
        void shouldQueryWithAllFilters() {
            Instant after = Instant.now().minusSeconds(3600);
            Instant before = Instant.now();

            when(jdbcTemplate.query(
                anyString(),
                any(RowMapper.class),
                any(Object[].class)
            )).thenReturn(List.of());

            repository.query(
                CommandStatus.PENDING,
                "payments",
                "DebitAccount",
                after,
                before,
                10,
                0
            );

            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowMapper.class), any(Object[].class));

            String sql = sqlCaptor.getValue();
            assertTrue(sql.contains("status = ?"));
            assertTrue(sql.contains("domain = ?"));
            assertTrue(sql.contains("command_type = ?"));
            assertTrue(sql.contains("created_at >= ?"));
            assertTrue(sql.contains("created_at <= ?"));
            assertTrue(sql.contains("LIMIT ? OFFSET ?"));
        }

        @Test
        @SuppressWarnings("unchecked")
        void shouldQueryWithNoFilters() {
            when(jdbcTemplate.query(
                anyString(),
                any(RowMapper.class),
                any(Object[].class)
            )).thenReturn(List.of());

            repository.query(null, null, null, null, null, 10, 0);

            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowMapper.class), any(Object[].class));

            String sql = sqlCaptor.getValue();
            assertFalse(sql.contains("status = ?"));
            assertFalse(sql.contains("AND domain = ?"));
        }
    }

    @Nested
    class ListByBatchTests {

        @Test
        @SuppressWarnings("unchecked")
        void shouldListByBatchWithStatus() {
            UUID batchId = UUID.randomUUID();

            when(jdbcTemplate.query(
                anyString(),
                any(RowMapper.class),
                any(Object[].class)
            )).thenReturn(List.of());

            repository.listByBatch("payments", batchId, CommandStatus.PENDING, 10, 0);

            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowMapper.class), any(Object[].class));

            String sql = sqlCaptor.getValue();
            assertTrue(sql.contains("batch_id = ?"));
            assertTrue(sql.contains("status = ?"));
        }

        @Test
        @SuppressWarnings("unchecked")
        void shouldListByBatchWithoutStatus() {
            UUID batchId = UUID.randomUUID();

            when(jdbcTemplate.query(
                anyString(),
                any(RowMapper.class),
                any(Object[].class)
            )).thenReturn(List.of());

            repository.listByBatch("payments", batchId, null, 10, 0);

            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowMapper.class), any(Object[].class));

            String sql = sqlCaptor.getValue();
            assertTrue(sql.contains("batch_id = ?"));
            assertFalse(sql.contains("status = ?"));
        }
    }

    @Nested
    class SpReceiveCommandTests {

        @Test
        @SuppressWarnings("unchecked")
        void shouldReturnMetadataWhenReceived() {
            CommandMetadata expected = createTestMetadata();
            UUID commandId = expected.commandId();

            when(jdbcTemplate.query(
                contains("sp_receive_command"),
                any(RowMapper.class),
                eq("payments"), eq(commandId), eq(123L), eq(5)
            )).thenReturn(List.of(expected));

            Optional<CommandMetadata> result = repository.spReceiveCommand(
                "payments", commandId, 123L, 5
            );

            assertTrue(result.isPresent());
            assertEquals(expected, result.get());
        }

        @Test
        @SuppressWarnings("unchecked")
        void shouldReturnEmptyWhenNotReceivable() {
            when(jdbcTemplate.query(
                anyString(),
                any(RowMapper.class),
                any(), any(), any(), any()
            )).thenReturn(List.of());

            Optional<CommandMetadata> result = repository.spReceiveCommand(
                "payments", UUID.randomUUID(), null, null
            );

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    class SpFinishCommandTests {

        @Test
        void shouldReturnTrueWhenBatchComplete() {
            when(jdbcTemplate.queryForObject(
                contains("sp_finish_command"),
                eq(Boolean.class),
                any(), any(), any(), any(), any(), any(), any(), any(), any()
            )).thenReturn(true);

            boolean result = repository.spFinishCommand(
                "payments",
                UUID.randomUUID(),
                CommandStatus.COMPLETED,
                "COMPLETED",
                null, null, null, null,
                UUID.randomUUID()
            );

            assertTrue(result);
        }

        @Test
        void shouldReturnFalseWhenNotComplete() {
            when(jdbcTemplate.queryForObject(
                anyString(),
                eq(Boolean.class),
                any(), any(), any(), any(), any(), any(), any(), any(), any()
            )).thenReturn(false);

            boolean result = repository.spFinishCommand(
                "payments",
                UUID.randomUUID(),
                CommandStatus.COMPLETED,
                "COMPLETED",
                null, null, null, null,
                null
            );

            assertFalse(result);
        }
    }

    @Nested
    class SpFailCommandTests {

        @Test
        void shouldReturnTrueWhenRecorded() {
            when(jdbcTemplate.queryForObject(
                contains("sp_fail_command"),
                eq(Boolean.class),
                any(), any(), any(), any(), any(), any(), any(), any()
            )).thenReturn(true);

            boolean result = repository.spFailCommand(
                "payments",
                UUID.randomUUID(),
                "TRANSIENT",
                "TIMEOUT",
                "Connection timeout",
                1, 3, 123L
            );

            assertTrue(result);
        }

        @Test
        void shouldReturnFalseOnFailure() {
            when(jdbcTemplate.queryForObject(
                anyString(),
                eq(Boolean.class),
                any(), any(), any(), any(), any(), any(), any(), any()
            )).thenReturn(false);

            boolean result = repository.spFailCommand(
                "payments",
                UUID.randomUUID(),
                "TRANSIENT",
                "TIMEOUT",
                "Error",
                1, 3, 123L
            );

            assertFalse(result);
        }
    }

    @Nested
    class GetDistinctDomainsTests {

        @Test
        void shouldReturnDistinctDomains() {
            when(jdbcTemplate.queryForList(
                contains("SELECT DISTINCT domain"),
                eq(String.class)
            )).thenReturn(List.of("payments", "orders", "users"));

            List<String> result = repository.getDistinctDomains();

            assertEquals(3, result.size());
            assertEquals(List.of("payments", "orders", "users"), result);
        }

        @Test
        void shouldReturnEmptyListWhenNoDomains() {
            when(jdbcTemplate.queryForList(
                contains("SELECT DISTINCT domain"),
                eq(String.class)
            )).thenReturn(List.of());

            List<String> result = repository.getDistinctDomains();

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    class GetDistinctCommandTypesTests {

        @Test
        void shouldReturnDistinctCommandTypes() {
            when(jdbcTemplate.queryForList(
                contains("SELECT DISTINCT command_type"),
                eq(String.class),
                eq("payments")
            )).thenReturn(List.of("DebitAccount", "CreditAccount", "TransferFunds"));

            List<String> result = repository.getDistinctCommandTypes("payments");

            assertEquals(3, result.size());
            assertEquals(List.of("DebitAccount", "CreditAccount", "TransferFunds"), result);
        }

        @Test
        void shouldReturnEmptyListWhenNoCommandTypes() {
            when(jdbcTemplate.queryForList(
                contains("SELECT DISTINCT command_type"),
                eq(String.class),
                eq("unknown")
            )).thenReturn(List.of());

            List<String> result = repository.getDistinctCommandTypes("unknown");

            assertTrue(result.isEmpty());
        }
    }
}
