package com.ivamare.commandbus.exception;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.sql.SQLTransientException;
import java.util.Set;

/**
 * Classifies database exceptions to determine if they are transient (retryable).
 *
 * <p>Transient exceptions typically indicate temporary conditions that may resolve
 * themselves, such as:
 * <ul>
 *   <li>Connection timeouts and socket timeouts</li>
 *   <li>Connection refused or reset</li>
 *   <li>Too many connections</li>
 *   <li>Temporary database unavailability (shutdown, restart)</li>
 *   <li>Resource exhaustion (disk full, out of memory)</li>
 *   <li>Serialization failures and deadlocks</li>
 * </ul>
 *
 * <p>This classifier is used by workers and process managers to determine
 * whether to retry operations with exponential backoff or fail immediately.
 *
 * @see <a href="https://www.postgresql.org/docs/current/errcodes-appendix.html">PostgreSQL Error Codes</a>
 */
public final class DatabaseExceptionClassifier {

    private DatabaseExceptionClassifier() {
        // Utility class - no instantiation
    }

    /**
     * PostgreSQL SQL state codes that indicate transient/retryable conditions.
     *
     * <p>Organized by category:
     * <ul>
     *   <li>08xxx - Connection exceptions</li>
     *   <li>53xxx - Insufficient resources</li>
     *   <li>57xxx - Operator intervention (shutdown)</li>
     *   <li>40xxx - Transaction rollback (serialization, deadlock)</li>
     * </ul>
     */
    private static final Set<String> TRANSIENT_SQL_STATES = Set.of(
        // Class 08 - Connection Exception
        "08000",  // connection_exception
        "08001",  // sqlclient_unable_to_establish_sqlconnection
        "08003",  // connection_does_not_exist
        "08004",  // sqlserver_rejected_establishment_of_sqlconnection
        "08006",  // connection_failure
        "08007",  // transaction_resolution_unknown
        "08P01",  // protocol_violation

        // Class 53 - Insufficient Resources
        "53000",  // insufficient_resources
        "53100",  // disk_full
        "53200",  // out_of_memory
        "53300",  // too_many_connections

        // Class 57 - Operator Intervention
        "57P01",  // admin_shutdown
        "57P02",  // crash_shutdown
        "57P03",  // cannot_connect_now
        "57P04",  // database_dropped

        // Class 40 - Transaction Rollback
        "40001",  // serialization_failure
        "40002",  // transaction_integrity_constraint_violation
        "40003",  // statement_completion_unknown
        "40P01"   // deadlock_detected
    );

    /**
     * Message patterns that indicate transient database conditions.
     * These are checked case-insensitively against exception messages.
     */
    private static final String[] TRANSIENT_MESSAGE_PATTERNS = {
        "connection refused",
        "connection reset",
        "connection timed out",
        "socket timeout",
        "read timed out",
        "connect timed out",
        "no available connection",
        "connection is not available",
        "connection pool exhausted",
        "pool exhausted",
        "cannot acquire connection",
        "connection closed",
        "connection was aborted",
        "broken pipe",
        "network is unreachable",
        "host is unreachable",
        "no route to host",
        "connection was killed",
        "terminating connection",
        "server closed the connection",
        "could not connect to server",
        "the database system is starting up",
        "the database system is shutting down"
    };

    /**
     * Determine if the exception is transient and should trigger a retry.
     *
     * <p>Checks the exception hierarchy for:
     * <ol>
     *   <li>Spring's transient/recoverable exception types</li>
     *   <li>JDBC transient exception types</li>
     *   <li>PostgreSQL-specific SQL state codes</li>
     *   <li>Known transient message patterns</li>
     *   <li>Wrapped cause exceptions (recursive)</li>
     * </ol>
     *
     * @param ex the exception to classify
     * @return true if the exception is transient and should be retried
     */
    public static boolean isTransient(Throwable ex) {
        if (ex == null) {
            return false;
        }

        // Check Spring's own transient classifications
        // Note: Check subclasses before parent classes to ensure all branches are reachable
        if (ex instanceof CannotGetJdbcConnectionException) {
            return true;
        }
        if (ex instanceof TransientDataAccessException) {
            return true;
        }
        if (ex instanceof RecoverableDataAccessException) {
            return true;
        }
        if (ex instanceof DataAccessResourceFailureException) {
            return true;
        }

        // Check JDBC transient exception types
        // Note: Check subclasses before parent classes to ensure all branches are reachable
        if (ex instanceof SQLTimeoutException) {
            return true;
        }
        if (ex instanceof SQLTransientConnectionException) {
            return true;
        }
        if (ex instanceof SQLTransientException) {
            return true;
        }
        if (ex instanceof SQLRecoverableException) {
            return true;
        }
        if (ex instanceof SQLNonTransientConnectionException) {
            // Non-transient connection exceptions are actually transient from recovery perspective
            // They indicate connection is closed/broken but can be retried with a new connection
            return true;
        }

        // Check SQL state for SQLException
        if (ex instanceof SQLException sqlEx) {
            String sqlState = sqlEx.getSQLState();
            if (sqlState != null && TRANSIENT_SQL_STATES.contains(sqlState)) {
                return true;
            }
        }

        // Check exception message for known transient patterns
        String message = ex.getMessage();
        if (message != null) {
            String lowerMessage = message.toLowerCase();
            for (String pattern : TRANSIENT_MESSAGE_PATTERNS) {
                if (lowerMessage.contains(pattern)) {
                    return true;
                }
            }
        }

        // Check wrapped cause (but avoid infinite loops)
        Throwable cause = ex.getCause();
        if (cause != null && cause != ex) {
            return isTransient(cause);
        }

        return false;
    }

    /**
     * Get the SQL state from an exception if available.
     *
     * @param ex the exception to inspect
     * @return the SQL state code, or null if not available
     */
    public static String getSqlState(Throwable ex) {
        if (ex instanceof SQLException sqlEx) {
            return sqlEx.getSQLState();
        }
        if (ex.getCause() != null && ex.getCause() != ex) {
            return getSqlState(ex.getCause());
        }
        return null;
    }

    /**
     * Get a brief description of why the exception was classified as transient.
     * Useful for logging.
     *
     * @param ex the exception to describe
     * @return a brief description of the transient condition, or null if not transient
     */
    public static String getTransientReason(Throwable ex) {
        if (ex == null) {
            return "Unknown";
        }

        // Check subclasses before parent classes to ensure all branches are reachable
        if (ex instanceof CannotGetJdbcConnectionException) {
            return "Spring CannotGetJdbcConnectionException";
        }
        if (ex instanceof TransientDataAccessException) {
            return "Spring " + ex.getClass().getSimpleName();
        }
        if (ex instanceof RecoverableDataAccessException) {
            return "Spring " + ex.getClass().getSimpleName();
        }
        if (ex instanceof DataAccessResourceFailureException) {
            return "Spring DataAccessResourceFailureException";
        }
        if (ex instanceof SQLTimeoutException) {
            return "JDBC SQLTimeoutException";
        }
        if (ex instanceof SQLTransientConnectionException) {
            return "JDBC SQLTransientConnectionException";
        }
        if (ex instanceof SQLTransientException) {
            return "JDBC SQLTransientException";
        }
        if (ex instanceof SQLRecoverableException) {
            return "JDBC SQLRecoverableException";
        }
        if (ex instanceof SQLNonTransientConnectionException) {
            return "JDBC SQLNonTransientConnectionException";
        }

        if (ex instanceof SQLException sqlEx) {
            String sqlState = sqlEx.getSQLState();
            if (sqlState != null && TRANSIENT_SQL_STATES.contains(sqlState)) {
                return "SQL state " + sqlState;
            }
        }

        String message = ex.getMessage();
        if (message != null) {
            String lowerMessage = message.toLowerCase();
            for (String pattern : TRANSIENT_MESSAGE_PATTERNS) {
                if (lowerMessage.contains(pattern)) {
                    return "Message pattern: " + pattern;
                }
            }
        }

        Throwable cause = ex.getCause();
        if (cause != null && cause != ex) {
            String causeReason = getTransientReason(cause);
            if (!"Unknown".equals(causeReason)) {
                return causeReason;
            }
        }

        return "Unknown";
    }
}
