package com.ivamare.commandbus.repository.impl;

import com.ivamare.commandbus.model.AuditEvent;
import com.ivamare.commandbus.repository.AuditRepository.AuditEventRecord;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JdbcAuditRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private ObjectMapper objectMapper;
    private JdbcAuditRepository repository;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        repository = new JdbcAuditRepository(jdbcTemplate, objectMapper);
    }

    @Nested
    class LogTests {

        @Test
        void shouldLogAuditEvent() {
            UUID commandId = UUID.randomUUID();
            Map<String, Object> details = Map.of("key", "value");

            repository.log("payments", commandId, "SENT", details);

            verify(jdbcTemplate).update(
                contains("INSERT INTO commandbus.audit"),
                eq("payments"),
                eq(commandId),
                eq("SENT"),
                contains("key")
            );
        }

        @Test
        void shouldLogWithNullDetails() {
            UUID commandId = UUID.randomUUID();

            repository.log("payments", commandId, "COMPLETED", null);

            verify(jdbcTemplate).update(
                contains("INSERT INTO commandbus.audit"),
                eq("payments"),
                eq(commandId),
                eq("COMPLETED"),
                isNull()
            );
        }

        @Test
        void shouldLogWithEmptyDetails() {
            UUID commandId = UUID.randomUUID();

            repository.log("payments", commandId, "RECEIVED", Map.of());

            verify(jdbcTemplate).update(
                contains("INSERT INTO commandbus.audit"),
                eq("payments"),
                eq(commandId),
                eq("RECEIVED"),
                isNull()
            );
        }
    }

    @Nested
    class LogBatchTests {

        @Test
        void shouldNotExecuteForEmptyList() {
            repository.logBatch(List.of());
            verifyNoInteractions(jdbcTemplate);
        }

        @Test
        void shouldBatchInsertEvents() {
            List<AuditEventRecord> events = List.of(
                new AuditEventRecord("payments", UUID.randomUUID(), "SENT", Map.of("a", 1)),
                new AuditEventRecord("payments", UUID.randomUUID(), "COMPLETED", null)
            );

            repository.logBatch(events);

            verify(jdbcTemplate).batchUpdate(
                contains("INSERT INTO commandbus.audit"),
                anyList()
            );
        }
    }

    @Nested
    class GetEventsTests {

        @Test
        @SuppressWarnings("unchecked")
        void shouldGetEventsWithDomain() {
            UUID commandId = UUID.randomUUID();
            AuditEvent expected = new AuditEvent(
                1L, "payments", commandId, "SENT", Instant.now(), null
            );

            when(jdbcTemplate.query(
                contains("domain = ?"),
                any(RowMapper.class),
                eq(commandId), eq("payments")
            )).thenReturn(List.of(expected));

            List<AuditEvent> result = repository.getEvents(commandId, "payments");

            assertEquals(1, result.size());
            assertEquals(expected, result.get(0));
        }

        @Test
        @SuppressWarnings("unchecked")
        void shouldGetEventsWithoutDomain() {
            UUID commandId = UUID.randomUUID();

            when(jdbcTemplate.query(
                argThat(sql -> sql != null && !sql.contains("domain = ?")),
                any(RowMapper.class),
                eq(commandId)
            )).thenReturn(List.of());

            List<AuditEvent> result = repository.getEvents(commandId, null);

            assertTrue(result.isEmpty());
            verify(jdbcTemplate).query(
                argThat(sql -> sql != null && !sql.contains("domain = ?")),
                any(RowMapper.class),
                eq(commandId)
            );
        }

        @Test
        @SuppressWarnings("unchecked")
        void shouldReturnEmptyListWhenNoEvents() {
            when(jdbcTemplate.query(
                anyString(),
                any(RowMapper.class),
                any(), any()
            )).thenReturn(List.of());

            List<AuditEvent> result = repository.getEvents(UUID.randomUUID(), "payments");

            assertTrue(result.isEmpty());
        }
    }
}
