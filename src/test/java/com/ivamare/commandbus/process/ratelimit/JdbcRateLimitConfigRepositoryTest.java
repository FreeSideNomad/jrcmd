package com.ivamare.commandbus.process.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("JdbcRateLimitConfigRepository")
class JdbcRateLimitConfigRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private ResultSet resultSet;

    private JdbcRateLimitConfigRepository repository;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        repository = new JdbcRateLimitConfigRepository(jdbcTemplate);
    }

    @Test
    @DisplayName("save should insert or update config")
    void saveShouldInsertOrUpdateConfig() {
        RateLimitConfig config = RateLimitConfig.create("test-key", 100, "Test description");

        repository.save(config);

        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(contains("INSERT INTO commandbus.rate_limit_config"), argsCaptor.capture());

        Object[] args = argsCaptor.getValue();
        assertEquals("test-key", args[0]);
        assertEquals(100, args[1]);
        assertEquals("Test description", args[2]);
    }

    @Test
    @DisplayName("findByResourceKey should return config when found")
    void findByResourceKeyShouldReturnConfigWhenFound() throws SQLException {
        Instant now = Instant.now();
        when(resultSet.getString("resource_key")).thenReturn("test-key");
        when(resultSet.getInt("tickets_per_second")).thenReturn(100);
        when(resultSet.getString("description")).thenReturn("Test");
        when(resultSet.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
        when(resultSet.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));

        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq("test-key")))
            .thenAnswer(invocation -> {
                RowMapper<RateLimitConfig> mapper = invocation.getArgument(1);
                return List.of(mapper.mapRow(resultSet, 0));
            });

        Optional<RateLimitConfig> result = repository.findByResourceKey("test-key");

        assertTrue(result.isPresent());
        assertEquals("test-key", result.get().resourceKey());
        assertEquals(100, result.get().ticketsPerSecond());
    }

    @Test
    @DisplayName("findByResourceKey should return empty when not found")
    void findByResourceKeyShouldReturnEmptyWhenNotFound() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq("nonexistent")))
            .thenReturn(List.of());

        Optional<RateLimitConfig> result = repository.findByResourceKey("nonexistent");

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("delete should return true when row deleted")
    void deleteShouldReturnTrueWhenRowDeleted() {
        when(jdbcTemplate.update(contains("DELETE FROM"), eq("test-key"))).thenReturn(1);

        boolean result = repository.delete("test-key");

        assertTrue(result);
    }

    @Test
    @DisplayName("delete should return false when row not found")
    void deleteShouldReturnFalseWhenRowNotFound() {
        when(jdbcTemplate.update(contains("DELETE FROM"), eq("nonexistent"))).thenReturn(0);

        boolean result = repository.delete("nonexistent");

        assertFalse(result);
    }

    @Test
    @DisplayName("findAll should return all configs")
    void findAllShouldReturnAllConfigs() throws SQLException {
        Instant now = Instant.now();
        when(resultSet.getString("resource_key")).thenReturn("key1", "key2");
        when(resultSet.getInt("tickets_per_second")).thenReturn(100, 200);
        when(resultSet.getString("description")).thenReturn("Desc1", "Desc2");
        when(resultSet.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
        when(resultSet.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));

        when(jdbcTemplate.query(contains("SELECT"), any(RowMapper.class)))
            .thenAnswer(invocation -> {
                RowMapper<RateLimitConfig> mapper = invocation.getArgument(1);
                return List.of(
                    mapper.mapRow(resultSet, 0),
                    mapper.mapRow(resultSet, 1)
                );
            });

        List<RateLimitConfig> results = repository.findAll();

        assertEquals(2, results.size());
    }
}
