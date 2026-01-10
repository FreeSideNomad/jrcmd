package com.commandbus.pgmq.impl;

import com.commandbus.model.PgmqMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JdbcPgmqClientTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private ObjectMapper objectMapper;
    private JdbcPgmqClient client;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        client = new JdbcPgmqClient(jdbcTemplate, objectMapper);
    }

    @Nested
    class CreateQueueTests {

        @Test
        void shouldExecuteCreateQueueSql() {
            client.createQueue("test_queue");
            verify(jdbcTemplate).execute("SELECT pgmq.create('test_queue')");
        }

        @Test
        void shouldEscapeSpecialCharacters() {
            client.createQueue("test-queue.name");
            verify(jdbcTemplate).execute("SELECT pgmq.create('test_queue_name')");
        }
    }

    @Nested
    class SendTests {

        @Test
        void shouldSendMessageWithoutDelay() {
            when(jdbcTemplate.queryForObject(
                eq("SELECT pgmq.send(?, ?::jsonb, ?)"),
                eq(Long.class),
                any(), any(), any()
            )).thenReturn(123L);

            long msgId = client.send("test_queue", Map.of("key", "value"));

            assertEquals(123L, msgId);
            verify(jdbcTemplate).queryForObject(
                eq("SELECT pgmq.send(?, ?::jsonb, ?)"),
                eq(Long.class),
                eq("test_queue"),
                contains("\"key\""),
                eq(0)
            );
            verify(jdbcTemplate).execute("NOTIFY pgmq_notify_test_queue");
        }

        @Test
        void shouldSendMessageWithDelay() {
            when(jdbcTemplate.queryForObject(
                eq("SELECT pgmq.send(?, ?::jsonb, ?)"),
                eq(Long.class),
                any(), any(), any()
            )).thenReturn(456L);

            long msgId = client.send("test_queue", Map.of("key", "value"), 30);

            assertEquals(456L, msgId);
            verify(jdbcTemplate).queryForObject(
                eq("SELECT pgmq.send(?, ?::jsonb, ?)"),
                eq(Long.class),
                eq("test_queue"),
                anyString(),
                eq(30)
            );
        }

        @Test
        void shouldThrowWhenSendReturnsNull() {
            when(jdbcTemplate.queryForObject(
                anyString(),
                eq(Long.class),
                any(), any(), any()
            )).thenReturn(null);

            assertThrows(RuntimeException.class,
                () -> client.send("test_queue", Map.of()));
        }
    }

    @Nested
    class SendBatchTests {

        @Test
        void shouldReturnEmptyListForEmptyInput() {
            List<Long> result = client.sendBatch("test_queue", List.of());
            assertTrue(result.isEmpty());
            verifyNoInteractions(jdbcTemplate);
        }

        @Test
        @SuppressWarnings("unchecked")
        void shouldSendBatchMessages() {
            List<Map<String, Object>> messages = List.of(
                Map.of("id", 1),
                Map.of("id", 2)
            );

            when(jdbcTemplate.query(
                anyString(),
                any(RowMapper.class),
                any(Object[].class)
            )).thenReturn(List.of(100L, 101L));

            List<Long> result = client.sendBatch("test_queue", messages);

            assertEquals(List.of(100L, 101L), result);

            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowMapper.class), any(Object[].class));

            String sql = sqlCaptor.getValue();
            assertTrue(sql.contains("pgmq.send_batch"));
            assertTrue(sql.contains("ARRAY["));
        }

        @Test
        @SuppressWarnings("unchecked")
        void shouldSendBatchWithDelay() {
            List<Map<String, Object>> messages = List.of(Map.of("id", 1));

            when(jdbcTemplate.query(
                anyString(),
                any(RowMapper.class),
                any(Object[].class)
            )).thenReturn(List.of(200L));

            List<Long> result = client.sendBatch("test_queue", messages, 60);

            assertEquals(List.of(200L), result);

            ArgumentCaptor<Object[]> paramsCaptor = ArgumentCaptor.forClass(Object[].class);
            verify(jdbcTemplate).query(anyString(), any(RowMapper.class), paramsCaptor.capture());

            Object[] params = paramsCaptor.getValue();
            assertEquals(60, params[params.length - 1]);
        }
    }

    @Nested
    class NotifyTests {

        @Test
        void shouldSendNotify() {
            client.notify("test_queue");
            verify(jdbcTemplate).execute("NOTIFY pgmq_notify_test_queue");
        }
    }

    @Nested
    class ReadTests {

        @Test
        @SuppressWarnings("unchecked")
        void shouldReadMessages() {
            PgmqMessage msg = new PgmqMessage(1L, 0, Instant.now(), Instant.now(), Map.of());

            when(jdbcTemplate.query(
                eq("SELECT * FROM pgmq.read(?, ?, ?)"),
                any(RowMapper.class),
                eq("test_queue"), eq(30), eq(10)
            )).thenReturn(List.of(msg));

            List<PgmqMessage> result = client.read("test_queue", 30, 10);

            assertEquals(1, result.size());
            assertEquals(msg, result.get(0));
        }

        @Test
        @SuppressWarnings("unchecked")
        void shouldReturnEmptyListWhenNoMessages() {
            when(jdbcTemplate.query(
                eq("SELECT * FROM pgmq.read(?, ?, ?)"),
                any(RowMapper.class),
                any(), any(), any()
            )).thenReturn(List.of());

            List<PgmqMessage> result = client.read("test_queue", 30, 10);

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    class DeleteTests {

        @Test
        void shouldReturnTrueWhenDeleted() {
            when(jdbcTemplate.queryForObject(
                eq("SELECT pgmq.delete(?, ?)"),
                eq(Boolean.class),
                eq("test_queue"), eq(123L)
            )).thenReturn(true);

            assertTrue(client.delete("test_queue", 123L));
        }

        @Test
        void shouldReturnFalseWhenNotFound() {
            when(jdbcTemplate.queryForObject(
                eq("SELECT pgmq.delete(?, ?)"),
                eq(Boolean.class),
                any(), any()
            )).thenReturn(false);

            assertFalse(client.delete("test_queue", 999L));
        }

        @Test
        void shouldReturnFalseWhenNull() {
            when(jdbcTemplate.queryForObject(
                eq("SELECT pgmq.delete(?, ?)"),
                eq(Boolean.class),
                any(), any()
            )).thenReturn(null);

            assertFalse(client.delete("test_queue", 123L));
        }
    }

    @Nested
    class ArchiveTests {

        @Test
        void shouldReturnTrueWhenArchived() {
            when(jdbcTemplate.queryForObject(
                eq("SELECT pgmq.archive(?, ?)"),
                eq(Boolean.class),
                eq("test_queue"), eq(123L)
            )).thenReturn(true);

            assertTrue(client.archive("test_queue", 123L));
        }

        @Test
        void shouldReturnFalseWhenArchiveFails() {
            when(jdbcTemplate.queryForObject(
                eq("SELECT pgmq.archive(?, ?)"),
                eq(Boolean.class),
                any(), any()
            )).thenReturn(false);

            assertFalse(client.archive("test_queue", 999L));
        }
    }

    @Nested
    class SetVisibilityTimeoutTests {

        @Test
        @SuppressWarnings("unchecked")
        void shouldReturnTrueWhenSet() {
            PgmqMessage msg = new PgmqMessage(123L, 1, Instant.now(), Instant.now().plusSeconds(60), Map.of());

            when(jdbcTemplate.query(
                eq("SELECT * FROM pgmq.set_vt(?, ?, ?)"),
                any(RowMapper.class),
                eq("test_queue"), eq(123L), eq(60)
            )).thenReturn(List.of(msg));

            assertTrue(client.setVisibilityTimeout("test_queue", 123L, 60));
        }

        @Test
        @SuppressWarnings("unchecked")
        void shouldReturnFalseWhenMessageNotFound() {
            when(jdbcTemplate.query(
                eq("SELECT * FROM pgmq.set_vt(?, ?, ?)"),
                any(RowMapper.class),
                any(), any(), any()
            )).thenReturn(List.of());

            assertFalse(client.setVisibilityTimeout("test_queue", 999L, 60));
        }
    }

    @Nested
    class GetFromArchiveTests {

        @Test
        @SuppressWarnings("unchecked")
        void shouldReturnMessageWhenFound() {
            PgmqMessage msg = new PgmqMessage(123L, 1, Instant.now(), null, Map.of("command_id", "cmd-123"));

            when(jdbcTemplate.query(
                contains("pgmq.a_test_queue"),
                any(RowMapper.class),
                eq("cmd-123")
            )).thenReturn(List.of(msg));

            Optional<PgmqMessage> result = client.getFromArchive("test_queue", "cmd-123");

            assertTrue(result.isPresent());
            assertEquals(msg, result.get());
        }

        @Test
        @SuppressWarnings("unchecked")
        void shouldReturnEmptyWhenNotFound() {
            when(jdbcTemplate.query(
                anyString(),
                any(RowMapper.class),
                anyString()
            )).thenReturn(List.of());

            Optional<PgmqMessage> result = client.getFromArchive("test_queue", "cmd-999");

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    class EscapeIdentifierTests {

        @Test
        void shouldPreserveValidCharacters() {
            assertEquals("valid_name123", client.escapeIdentifier("valid_name123"));
        }

        @Test
        void shouldReplaceInvalidCharacters() {
            assertEquals("test_queue_name", client.escapeIdentifier("test-queue.name"));
            assertEquals("queue_with_spaces", client.escapeIdentifier("queue with spaces"));
            assertEquals("queue__special", client.escapeIdentifier("queue!@special"));
        }
    }

    @Nested
    class ReadOneTests {

        @Test
        @SuppressWarnings("unchecked")
        void shouldReturnOptionalWhenMessageExists() {
            PgmqMessage msg = new PgmqMessage(1L, 0, Instant.now(), Instant.now(), Map.of());

            when(jdbcTemplate.query(
                eq("SELECT * FROM pgmq.read(?, ?, ?)"),
                any(RowMapper.class),
                eq("test_queue"), eq(30), eq(1)
            )).thenReturn(List.of(msg));

            Optional<PgmqMessage> result = client.readOne("test_queue", 30);

            assertTrue(result.isPresent());
            assertEquals(msg, result.get());
        }

        @Test
        @SuppressWarnings("unchecked")
        void shouldReturnEmptyWhenNoMessage() {
            when(jdbcTemplate.query(
                eq("SELECT * FROM pgmq.read(?, ?, ?)"),
                any(RowMapper.class),
                any(), any(), any()
            )).thenReturn(List.of());

            Optional<PgmqMessage> result = client.readOne("test_queue", 30);

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    class JsonSerializationTests {

        @Test
        void shouldSerializeMapToJson() {
            when(jdbcTemplate.queryForObject(
                anyString(),
                eq(Long.class),
                any(), any(), any()
            )).thenReturn(1L);

            client.send("queue", Map.of("key", "value", "number", 42));

            ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate).queryForObject(
                anyString(),
                eq(Long.class),
                any(),
                jsonCaptor.capture(),
                any()
            );

            String json = jsonCaptor.getValue();
            assertTrue(json.contains("\"key\""));
            assertTrue(json.contains("\"value\""));
            assertTrue(json.contains("42"));
        }
    }
}
