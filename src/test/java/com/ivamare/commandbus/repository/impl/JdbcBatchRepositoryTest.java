package com.ivamare.commandbus.repository.impl;

import com.ivamare.commandbus.model.BatchMetadata;
import com.ivamare.commandbus.model.BatchStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JdbcBatchRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private ObjectMapper objectMapper;
    private JdbcBatchRepository repository;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        repository = new JdbcBatchRepository(jdbcTemplate, objectMapper);
    }

    private BatchMetadata createTestMetadata() {
        return BatchMetadata.create(
            "payments",
            UUID.randomUUID(),
            "Test Batch",
            Map.of("key", "value"),
            10
        );
    }

    @Nested
    class SaveTests {

        @Test
        void shouldSaveBatchMetadata() {
            BatchMetadata metadata = createTestMetadata();

            repository.save(metadata);

            verify(jdbcTemplate).update(
                contains("INSERT INTO commandbus.batch"),
                eq(metadata.domain()),
                eq(metadata.batchId()),
                eq(metadata.name()),
                contains("key"),
                eq(metadata.status().getValue()),
                eq(metadata.totalCount()),
                eq(metadata.completedCount()),
                eq(metadata.canceledCount()),
                eq(metadata.inTroubleshootingCount()),
                any(), any(), any(), any()  // createdAt, startedAt, completedAt, batchType
            );
        }

        @Test
        void shouldSaveWithNullCustomData() {
            BatchMetadata metadata = new BatchMetadata(
                "payments", UUID.randomUUID(), "Test", null,
                BatchStatus.PENDING, 10, 0, 0, 0,
                Instant.now(), null, null, "COMMAND"
            );

            repository.save(metadata);

            verify(jdbcTemplate).update(
                contains("INSERT INTO commandbus.batch"),
                eq(metadata.domain()),
                eq(metadata.batchId()),
                eq(metadata.name()),
                isNull(),
                any(), any(), any(), any(), any(), any(), any(), any(), any()  // Added batchType
            );
        }
    }

    @Nested
    class GetTests {

        @Test
        @SuppressWarnings("unchecked")
        void shouldReturnMetadataWhenFound() {
            BatchMetadata expected = createTestMetadata();
            UUID batchId = expected.batchId();

            when(jdbcTemplate.query(
                contains("SELECT * FROM commandbus.batch WHERE domain"),
                any(RowMapper.class),
                eq("payments"), eq(batchId)
            )).thenReturn(List.of(expected));

            Optional<BatchMetadata> result = repository.get("payments", batchId);

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

            Optional<BatchMetadata> result = repository.get("payments", UUID.randomUUID());

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
    class ListBatchesTests {

        @Test
        @SuppressWarnings("unchecked")
        void shouldListBatchesWithStatus() {
            when(jdbcTemplate.query(
                contains("status = ?"),
                any(RowMapper.class),
                eq("payments"), eq("PENDING"), eq(10), eq(0)
            )).thenReturn(List.of());

            repository.listBatches("payments", BatchStatus.PENDING, 10, 0);

            verify(jdbcTemplate).query(
                contains("status = ?"),
                any(RowMapper.class),
                eq("payments"), eq("PENDING"), eq(10), eq(0)
            );
        }

        @Test
        @SuppressWarnings("unchecked")
        void shouldListBatchesWithoutStatus() {
            when(jdbcTemplate.query(
                argThat(sql -> sql != null && !sql.contains("status = ?")),
                any(RowMapper.class),
                eq("payments"), eq(10), eq(0)
            )).thenReturn(List.of());

            repository.listBatches("payments", null, 10, 0);

            verify(jdbcTemplate).query(
                argThat(sql -> sql != null && !sql.contains("status = ?")),
                any(RowMapper.class),
                eq("payments"), eq(10), eq(0)
            );
        }
    }

    @Nested
    class TsqOperationTests {

        @Test
        void tsqRetryShouldCallStoredProcedure() {
            UUID batchId = UUID.randomUUID();
            when(jdbcTemplate.queryForObject(
                contains("sp_tsq_retry"),
                eq(Boolean.class),
                eq("payments"), eq(batchId)
            )).thenReturn(false);

            boolean result = repository.tsqRetry("payments", batchId);

            assertFalse(result);
        }

        @Test
        void tsqCancelShouldCallStoredProcedure() {
            UUID batchId = UUID.randomUUID();
            when(jdbcTemplate.queryForObject(
                contains("sp_tsq_cancel"),
                eq(Boolean.class),
                eq("payments"), eq(batchId)
            )).thenReturn(true);

            boolean result = repository.tsqCancel("payments", batchId);

            assertTrue(result);
        }

        @Test
        void tsqCompleteShouldCallStoredProcedure() {
            UUID batchId = UUID.randomUUID();
            when(jdbcTemplate.queryForObject(
                contains("sp_tsq_complete"),
                eq(Boolean.class),
                eq("payments"), eq(batchId)
            )).thenReturn(true);

            boolean result = repository.tsqComplete("payments", batchId);

            assertTrue(result);
        }

        @Test
        void tsqOperationsShouldReturnFalseOnNull() {
            UUID batchId = UUID.randomUUID();
            when(jdbcTemplate.queryForObject(
                anyString(),
                eq(Boolean.class),
                any(), any()
            )).thenReturn(null);

            assertFalse(repository.tsqRetry("payments", batchId));
            assertFalse(repository.tsqCancel("payments", batchId));
            assertFalse(repository.tsqComplete("payments", batchId));
        }
    }

    @Nested
    class RefreshStatsTests {

        @Test
        void shouldCallRefreshStatsStoredProcedure() {
            UUID batchId = UUID.randomUUID();
            when(jdbcTemplate.queryForObject(
                contains("sp_refresh_batch_stats"),
                eq(Boolean.class),
                eq("payments"), eq(batchId)
            )).thenReturn(true);

            repository.refreshStats("payments", batchId);

            verify(jdbcTemplate).queryForObject(
                contains("sp_refresh_batch_stats"),
                eq(Boolean.class),
                eq("payments"), eq(batchId)
            );
        }
    }

    @Nested
    class ListByTypeTests {

        @Test
        @SuppressWarnings("unchecked")
        void shouldListByTypeWithStatus() {
            when(jdbcTemplate.query(
                contains("batch_type = ?"),
                any(RowMapper.class),
                eq("payments"), eq("COMMAND"), eq("PENDING"), eq(10), eq(0)
            )).thenReturn(List.of());

            repository.listByType("payments", "COMMAND", BatchStatus.PENDING, 10, 0);

            verify(jdbcTemplate).query(
                contains("batch_type = ?"),
                any(RowMapper.class),
                eq("payments"), eq("COMMAND"), eq("PENDING"), eq(10), eq(0)
            );
        }

        @Test
        @SuppressWarnings("unchecked")
        void shouldListByTypeWithoutStatus() {
            when(jdbcTemplate.query(
                argThat(sql -> sql != null && sql.contains("batch_type = ?") && !sql.contains("status = ?")),
                any(RowMapper.class),
                eq("payments"), eq("PROCESS"), eq(10), eq(0)
            )).thenReturn(List.of());

            repository.listByType("payments", "PROCESS", null, 10, 0);

            verify(jdbcTemplate).query(
                argThat(sql -> sql != null && sql.contains("batch_type = ?") && !sql.contains("status = ?")),
                any(RowMapper.class),
                eq("payments"), eq("PROCESS"), eq(10), eq(0)
            );
        }
    }
}
