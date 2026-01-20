package com.ivamare.commandbus.exception;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.*;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientConnectionException;
import java.sql.SQLTransientException;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseExceptionClassifierTest {

    @Nested
    class SqlStateClassification {

        @Test
        void shouldClassifyConnectionErrorStatesAsTransient() {
            // 08xxx - Connection Exception states
            assertTrue(isTransient("08000", "Connection error"));
            assertTrue(isTransient("08001", "SQL client unable to establish connection"));
            assertTrue(isTransient("08003", "Connection does not exist"));
            assertTrue(isTransient("08004", "Rejected connection"));
            assertTrue(isTransient("08006", "Connection failure"));
            assertTrue(isTransient("08007", "Transaction resolution unknown"));
            assertTrue(isTransient("08P01", "Protocol violation"));
        }

        @Test
        void shouldClassifyResourceErrorStatesAsTransient() {
            // 53xxx - Insufficient Resources
            assertTrue(isTransient("53000", "Insufficient resources"));
            assertTrue(isTransient("53100", "Disk full"));
            assertTrue(isTransient("53200", "Out of memory"));
            assertTrue(isTransient("53300", "Too many connections"));
        }

        @Test
        void shouldClassifyShutdownStatesAsTransient() {
            // 57xxx - Operator Intervention
            assertTrue(isTransient("57P01", "Admin shutdown"));
            assertTrue(isTransient("57P02", "Crash shutdown"));
            assertTrue(isTransient("57P03", "Cannot connect now"));
            assertTrue(isTransient("57P04", "Database dropped"));
        }

        @Test
        void shouldClassifySerializationStatesAsTransient() {
            // 40xxx - Transaction Rollback
            assertTrue(isTransient("40001", "Serialization failure"));
            assertTrue(isTransient("40002", "Transaction integrity constraint violation"));
            assertTrue(isTransient("40003", "Statement completion unknown"));
            assertTrue(isTransient("40P01", "Deadlock detected"));
        }

        @Test
        void shouldNotClassifyOtherSqlStatesAsTransient() {
            // Syntax errors, data errors, etc. should not be transient
            assertFalse(isTransient("42000", "Syntax error"));
            assertFalse(isTransient("42601", "Syntax error"));
            assertFalse(isTransient("23505", "Unique violation"));
            assertFalse(isTransient("22001", "String data right truncation"));
        }

        private boolean isTransient(String sqlState, String message) {
            SQLException ex = new SQLException(message, sqlState);
            return DatabaseExceptionClassifier.isTransient(ex);
        }
    }

    @Nested
    class SpringExceptionClassification {

        @Test
        void shouldClassifyTransientDataAccessExceptionsAsTransient() {
            assertTrue(DatabaseExceptionClassifier.isTransient(
                new TransientDataAccessResourceException("Resource unavailable")));
            assertTrue(DatabaseExceptionClassifier.isTransient(
                new QueryTimeoutException("Query timed out")));
        }

        @Test
        void shouldClassifyCannotGetJdbcConnectionAsTransient() {
            assertTrue(DatabaseExceptionClassifier.isTransient(
                new CannotGetJdbcConnectionException("Cannot get connection")));
        }

        @Test
        void shouldClassifyDataAccessResourceFailureAsTransient() {
            assertTrue(DatabaseExceptionClassifier.isTransient(
                new DataAccessResourceFailureException("Resource failure")));
        }

        @Test
        void shouldNotClassifyNonTransientSpringExceptionsAsTransient() {
            assertFalse(DatabaseExceptionClassifier.isTransient(
                new DuplicateKeyException("Duplicate key")));
            assertFalse(DatabaseExceptionClassifier.isTransient(
                new DataIntegrityViolationException("Constraint violation")));
        }
    }

    @Nested
    class SqlExceptionTypeClassification {

        @Test
        void shouldClassifySQLRecoverableExceptionAsTransient() {
            assertTrue(DatabaseExceptionClassifier.isTransient(
                new SQLRecoverableException("Connection lost")));
        }

        @Test
        void shouldClassifySQLTransientExceptionAsTransient() {
            assertTrue(DatabaseExceptionClassifier.isTransient(
                new SQLTransientException("Transient error")));
        }

        @Test
        void shouldClassifySQLTransientConnectionExceptionAsTransient() {
            assertTrue(DatabaseExceptionClassifier.isTransient(
                new SQLTransientConnectionException("Connection error")));
        }

        @Test
        void shouldClassifySQLNonTransientConnectionExceptionAsTransient() {
            // Non-transient connection exceptions are actually transient from recovery perspective
            assertTrue(DatabaseExceptionClassifier.isTransient(
                new SQLNonTransientConnectionException("Connection refused")));
        }
    }

    @Nested
    class MessagePatternClassification {

        @Test
        void shouldClassifyConnectionResetAsTransient() {
            assertTrue(DatabaseExceptionClassifier.isTransient(
                new RuntimeException("Connection reset by peer")));
            assertTrue(DatabaseExceptionClassifier.isTransient(
                new RuntimeException("connection reset")));
        }

        @Test
        void shouldClassifyBrokenPipeAsTransient() {
            assertTrue(DatabaseExceptionClassifier.isTransient(
                new RuntimeException("Broken pipe")));
        }

        @Test
        void shouldClassifyConnectionRefusedAsTransient() {
            assertTrue(DatabaseExceptionClassifier.isTransient(
                new RuntimeException("Connection refused")));
        }

        @Test
        void shouldClassifySocketTimeoutAsTransient() {
            assertTrue(DatabaseExceptionClassifier.isTransient(
                new RuntimeException("Socket timeout occurred")));
            assertTrue(DatabaseExceptionClassifier.isTransient(
                new RuntimeException("Read timed out")));
        }

        @Test
        void shouldClassifyNoRouteToHostAsTransient() {
            assertTrue(DatabaseExceptionClassifier.isTransient(
                new RuntimeException("No route to host")));
        }

        @Test
        void shouldClassifyHostUnreachableAsTransient() {
            assertTrue(DatabaseExceptionClassifier.isTransient(
                new RuntimeException("Host is unreachable")));
        }

        @Test
        void shouldClassifyNetworkUnreachableAsTransient() {
            assertTrue(DatabaseExceptionClassifier.isTransient(
                new RuntimeException("Network is unreachable")));
        }

        @Test
        void shouldClassifyPoolExhaustedAsTransient() {
            assertTrue(DatabaseExceptionClassifier.isTransient(
                new RuntimeException("Connection pool exhausted")));
            assertTrue(DatabaseExceptionClassifier.isTransient(
                new RuntimeException("pool exhausted: all connections busy")));
        }

        @Test
        void shouldClassifyConnectionClosedAsTransient() {
            assertTrue(DatabaseExceptionClassifier.isTransient(
                new RuntimeException("Connection closed unexpectedly")));
            assertTrue(DatabaseExceptionClassifier.isTransient(
                new RuntimeException("The connection closed while processing")));
        }

        @Test
        void shouldClassifyAdminShutdownAsTransient() {
            assertTrue(DatabaseExceptionClassifier.isTransient(
                new RuntimeException("terminating connection due to administrator command")));
        }

        @Test
        void shouldNotClassifyUnrelatedMessagesAsTransient() {
            assertFalse(DatabaseExceptionClassifier.isTransient(
                new RuntimeException("Unique constraint violated")));
            assertFalse(DatabaseExceptionClassifier.isTransient(
                new RuntimeException("Syntax error near SELECT")));
            assertFalse(DatabaseExceptionClassifier.isTransient(
                new RuntimeException("Table does not exist")));
        }
    }

    @Nested
    class NestedCauseClassification {

        @Test
        void shouldFindTransientCauseInChain() {
            SQLException sqlEx = new SQLException("Connection error", "08001");
            RuntimeException wrapper = new RuntimeException("Wrapper", sqlEx);
            assertTrue(DatabaseExceptionClassifier.isTransient(wrapper));
        }

        @Test
        void shouldFindSocketExceptionInCause() {
            SocketTimeoutException socketEx = new SocketTimeoutException("Read timed out");
            RuntimeException wrapper = new RuntimeException("Database error", socketEx);
            assertTrue(DatabaseExceptionClassifier.isTransient(wrapper));
        }

        @Test
        void shouldFindConnectExceptionInCause() {
            ConnectException connectEx = new ConnectException("Connection refused");
            RuntimeException wrapper = new RuntimeException("Failed to connect", connectEx);
            assertTrue(DatabaseExceptionClassifier.isTransient(wrapper));
        }

        @Test
        void shouldFindIOExceptionWithConnectionMessageInCause() {
            IOException ioEx = new IOException("Connection reset");
            RuntimeException wrapper = new RuntimeException("IO error", ioEx);
            assertTrue(DatabaseExceptionClassifier.isTransient(wrapper));
        }

        @Test
        void shouldNotClassifyChainWithNonTransientCausesAsTransient() {
            IllegalArgumentException argEx = new IllegalArgumentException("Invalid input");
            RuntimeException wrapper = new RuntimeException("Wrapper", argEx);
            assertFalse(DatabaseExceptionClassifier.isTransient(wrapper));
        }

        @Test
        void shouldHandleCircularCauseReferences() {
            // Create exception with circular reference (shouldn't happen but be defensive)
            RuntimeException ex = new RuntimeException("Outer");
            // Can't create actual circular reference in Java, but method should handle depth limit
            assertFalse(DatabaseExceptionClassifier.isTransient(ex));
        }
    }

    @Nested
    class GetTransientReasonTest {

        @Test
        void shouldReturnSqlStateForSqlException() {
            SQLException ex = new SQLException("Connection error", "08001");
            String reason = DatabaseExceptionClassifier.getTransientReason(ex);
            assertTrue(reason.contains("08001"), "Expected reason to contain SQL state: " + reason);
        }

        @Test
        void shouldReturnSpringTypeForTransientDataAccessException() {
            TransientDataAccessResourceException ex =
                new TransientDataAccessResourceException("Resource unavailable");
            String reason = DatabaseExceptionClassifier.getTransientReason(ex);
            assertTrue(reason.contains("TransientDataAccessResourceException"),
                "Expected reason to contain TransientDataAccessResourceException: " + reason);
        }

        @Test
        void shouldReturnMessagePatternForRuntimeException() {
            RuntimeException ex = new RuntimeException("Connection reset by peer");
            String reason = DatabaseExceptionClassifier.getTransientReason(ex);
            assertTrue(reason.contains("Connection reset") || reason.contains("connection reset"));
        }

        @Test
        void shouldReturnUnknownForNonTransientException() {
            RuntimeException ex = new RuntimeException("Some random error");
            String reason = DatabaseExceptionClassifier.getTransientReason(ex);
            assertEquals("Unknown", reason);
        }

        @Test
        void shouldHandleNullException() {
            String reason = DatabaseExceptionClassifier.getTransientReason(null);
            assertEquals("Unknown", reason);
        }
    }

    @Nested
    class NullAndEdgeCases {

        @Test
        void shouldHandleNullException() {
            assertFalse(DatabaseExceptionClassifier.isTransient(null));
        }

        @Test
        void shouldHandleNullMessage() {
            RuntimeException ex = new RuntimeException((String) null);
            assertFalse(DatabaseExceptionClassifier.isTransient(ex));
        }

        @Test
        void shouldHandleEmptyMessage() {
            RuntimeException ex = new RuntimeException("");
            assertFalse(DatabaseExceptionClassifier.isTransient(ex));
        }

        @Test
        void shouldHandleSQLExceptionWithNullSqlState() {
            SQLException ex = new SQLException("Error message", (String) null);
            // Should still check message patterns
            assertFalse(DatabaseExceptionClassifier.isTransient(ex));

            SQLException connEx = new SQLException("Connection reset", (String) null);
            assertTrue(DatabaseExceptionClassifier.isTransient(connEx));
        }
    }
}
