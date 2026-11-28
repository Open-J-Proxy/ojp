package org.openjproxy.xa.pool.commons;

import org.openjproxy.xa.pool.XABackendSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.XAConnection;
import javax.transaction.xa.XAResource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Default implementation of {@link XABackendSession} that wraps an {@link XAConnection}.
 * <p>
 * This class provides the poolable session abstraction over a JDBC XA connection.
 * It handles lifecycle operations (open, close, reset, health checks) and provides
 * access to the underlying XAResource for XA transaction operations.
 * </p>
 *
 * <h3>Reset Semantics:</h3>
 * <p>
 * The {@link #reset()} method cleans session state to prepare for reuse:
 * </p>
 * <ul>
 *   <li>Clears warnings</li>
 *   <li>Resets transaction isolation when configured</li>
 *   <li>Does NOT close the physical connection</li>
 * </ul>
 *
 * <p><strong>Critical Invariant:</strong> reset() is ONLY called after transaction
 * completion (COMMITTED or ROLLEDBACK state), never while in PREPARED state.</p>
 */
public class BackendSessionImpl implements XABackendSession {
    private static final Logger log = LoggerFactory.getLogger(BackendSessionImpl.class);

    private final XAConnection xaConnection;
    private final String sessionId;
    private final Integer defaultTransactionIsolation;
    private XAResource xaResource;
    private Connection connection;
    private volatile boolean closed = false;

    // Housekeeping state tracking
    private volatile long creationTime;
    private volatile long lastBorrowTime;
    private volatile long lastReturnTime;
    private volatile Thread borrowingThread;
    private volatile StackTraceElement[] borrowStackTrace;

    /**
     * Creates a new backend session wrapping an XAConnection.
     *
     * @param xaConnection the XA connection to wrap
     */
    public BackendSessionImpl(XAConnection xaConnection) {
        this(xaConnection, null);
    }

    /**
     * Creates a new backend session wrapping an XAConnection with transaction isolation reset support.
     *
     * @param xaConnection the XA connection to wrap
     * @param defaultTransactionIsolation the default transaction isolation level to reset to, or null to not reset
     */
    public BackendSessionImpl(XAConnection xaConnection, Integer defaultTransactionIsolation) {
        if (xaConnection == null) {
            throw new IllegalArgumentException("xaConnection cannot be null");
        }
        this.xaConnection = xaConnection;
        this.defaultTransactionIsolation = defaultTransactionIsolation;
        this.sessionId = "session-" + System.currentTimeMillis() + "-" +
                         Integer.toHexString(System.identityHashCode(this));
        this.creationTime = System.nanoTime();
        this.lastBorrowTime = 0;
        this.lastReturnTime = 0;
    }

    @Override
    public void open() throws SQLException {
        if (closed) {
            throw new IllegalStateException("Session is already closed");
        }

        // Obtain Connection and XAResource from XAConnection
        this.connection = xaConnection.getConnection();
        this.xaResource = xaConnection.getXAResource();

        log.debug("[{}] open() called - Connection object hashCode: {}, xaConnection hashCode: {}",
                sessionId, System.identityHashCode(connection), System.identityHashCode(xaConnection));

        // Set default transaction isolation
        // This is critical because xaConnection.getConnection() may return a Connection object
        // whose isolation level needs to be explicitly set to the configured default
        if (defaultTransactionIsolation != null) {
            try {
                int currentIsolation = connection.getTransactionIsolation();
                log.debug("[{}] open() - Current isolation: {}, Default isolation: {}",
                        sessionId, currentIsolation, defaultTransactionIsolation);
                if (currentIsolation != defaultTransactionIsolation) {
                    log.debug("[{}] open() - Setting transaction isolation from {} to default {}",
                            sessionId, currentIsolation, defaultTransactionIsolation);
                    connection.setTransactionIsolation(defaultTransactionIsolation);
                    int afterSet = connection.getTransactionIsolation();
                    log.debug("[{}] open() - After setTransactionIsolation, isolation is now: {}",
                            sessionId, afterSet);
                } else {
                    log.debug("[{}] open() - Transaction isolation already at default {}", sessionId, defaultTransactionIsolation);
                }
            } catch (SQLException e) {
                log.error("[{}] open() - Error setting default transaction isolation: {}", sessionId, e.getMessage());
                throw e;
            }
        }

        log.debug("Backend session opened");
    }

    @Override
    public void close() throws SQLException {
        if (closed) {
            return; // Idempotent
        }

        closed = true;

        // Close the logical connection first
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                log.warn("Error closing connection", e);
            }
            connection = null;
        }

        // Close the XAConnection (returns physical connection to pool or closes it)
        try {
            xaConnection.close();
        } catch (SQLException e) {
            log.error("Error closing XAConnection", e);
            throw e;
        }

        xaResource = null;

        log.debug("Backend session closed");
    }

    @Override
    public boolean isHealthy() {
        if (closed) {
            return false;
        }

        if (connection == null) {
            return false;
        }

        try {
            // Check if connection is valid (with 5 second timeout)
            return connection.isValid(5);
        } catch (SQLException e) {
            log.warn("Health check failed", e);
            return false;
        }
    }

    @Override
    public void reset() throws SQLException {
        if (closed) {
            throw new IllegalStateException("Cannot reset closed session");
        }

        if (connection == null) {
            throw new IllegalStateException("Session not opened");
        }

        log.debug("[{}] reset() called - Connection object hashCode: {}",
                sessionId, System.identityHashCode(connection));

        try {
            connection.clearWarnings();
        } catch (SQLException e) {
            log.warn("Error clearing warnings during reset", e);
        }

        // Reset transaction isolation level
        // This handles cases where the client changed isolation but didn't commit an XA transaction
        // (so sanitizeAfterTransaction() wasn't called)
        if (defaultTransactionIsolation != null) {
            try {
                int currentIsolation = connection.getTransactionIsolation();
                log.debug("[{}] reset() - Current isolation: {}, Default isolation: {}",
                        sessionId, currentIsolation, defaultTransactionIsolation);
                if (currentIsolation != defaultTransactionIsolation) {
                    log.debug("[{}] reset() - Resetting transaction isolation from {} to default {}",
                            sessionId, currentIsolation, defaultTransactionIsolation);
                    connection.setTransactionIsolation(defaultTransactionIsolation);
                    int afterSet = connection.getTransactionIsolation();
                    log.debug("[{}] reset() - After setTransactionIsolation, isolation is now: {}",
                            sessionId, afterSet);
                } else {
                    log.debug("[{}] reset() - Transaction isolation already at default {}", sessionId, defaultTransactionIsolation);
                }
            } catch (SQLException e) {
                log.warn("[{}] reset() - Error resetting transaction isolation: {}", sessionId, e.getMessage());
                // Don't throw - continue with reset even if isolation reset fails
            }
        }

        log.debug("Backend session reset completed");
    }

    @Override
    public void sanitizeAfterTransaction() throws SQLException {
        if (closed) {
            throw new IllegalStateException("Cannot sanitize closed session");
        }

        log.debug("[{}] sanitizeAfterTransaction() called - Connection object hashCode: {}",
                sessionId, System.identityHashCode(connection));

        // Reset transaction isolation on the current connection
        // IMPORTANT: We do NOT call xaConnection.getConnection() here because:
        // 1. The client (OJP Session) already has a reference to the Connection from open()
        // 2. Calling getConnection() would create a NEW Connection object, but the client still has the old reference
        // 3. If the client changed isolation on their Connection, we need to reset it on THE SAME Connection object
        //    that the client has, otherwise we're resetting a different Connection object and the physical
        //    connection still has the wrong isolation level
        // 4. Both Connection objects point to the same physical connection, so we just need to ensure
        //    isolation is reset before the session is reused
        if (defaultTransactionIsolation != null) {
            try {
                int currentIsolation = connection.getTransactionIsolation();
                log.debug("[{}] sanitizeAfterTransaction() - Current isolation: {}, Default isolation: {}",
                        sessionId, currentIsolation, defaultTransactionIsolation);
                if (currentIsolation != defaultTransactionIsolation) {
                    log.debug("[{}] sanitizeAfterTransaction() - Resetting transaction isolation from {} to default {}",
                            sessionId, currentIsolation, defaultTransactionIsolation);
                    connection.setTransactionIsolation(defaultTransactionIsolation);
                    int afterSet = connection.getTransactionIsolation();
                    log.debug("[{}] sanitizeAfterTransaction() - After setTransactionIsolation, isolation is now: {}",
                            sessionId, afterSet);
                } else {
                    log.debug("[{}] sanitizeAfterTransaction() - Transaction isolation already at default {}",
                            sessionId, defaultTransactionIsolation);
                }
            } catch (SQLException e) {
                log.warn("[{}] sanitizeAfterTransaction() - Error resetting transaction isolation: {}",
                        sessionId, e.getMessage());
                // Don't throw - session can still be used
            }
        }

        // Clear warnings on the connection
        try {
            connection.clearWarnings();
        } catch (SQLException e) {
            log.warn("Error clearing warnings after sanitization: {}", e.getMessage());
        }

        log.debug("Backend session sanitized successfully");
    }

    @Override
    public XAResource getXAResource() {
        if (xaResource == null) {
            throw new IllegalStateException("Session not opened");
        }
        return xaResource;
    }

    @Override
    public Connection getConnection() {
        if (connection == null) {
            throw new IllegalStateException("Session not opened");
        }
        return connection;
    }

    @Override
    public XAConnection getXAConnection() {
        return xaConnection;
    }

    @Override
    public String getSessionId() {
        return sessionId;
    }

    /**
     * Checks if this session is closed.
     *
     * @return true if closed, false otherwise
     */
    public boolean isClosed() {
        return closed;
    }

    // ========== Housekeeping Methods ==========

    /**
     * Called when the session is borrowed from the pool.
     * Updates tracking information for leak detection.
     *
     * @param captureStackTrace whether to capture the current stack trace for enhanced leak reporting
     */
    public void onBorrow(boolean captureStackTrace) {
        this.lastBorrowTime = System.nanoTime();
        this.borrowingThread = Thread.currentThread();
        if (captureStackTrace) {
            this.borrowStackTrace = Thread.currentThread().getStackTrace();
        } else {
            this.borrowStackTrace = null;
        }
    }

    /**
     * Called when the session is returned to the pool.
     * Clears tracking information and updates timestamps.
     */
    public void onReturn() {
        this.lastReturnTime = System.nanoTime();
        this.borrowingThread = null;
        this.borrowStackTrace = null;
    }

    /**
     * Checks if the session has been idle for longer than the specified threshold.
     *
     * @param thresholdMs the idle threshold in milliseconds
     * @return true if the session has been idle longer than the threshold
     */
    public boolean isIdle(long thresholdMs) {
        if (lastReturnTime == 0) {
            return false; // Never returned, so not idle
        }
        long idleNanos = System.nanoTime() - lastReturnTime;
        return idleNanos > (thresholdMs * 1_000_000L);
    }

    /**
     * Checks if the session has exceeded its maximum lifetime.
     * Also checks if the session has been idle for the required minimum time.
     *
     * @param maxLifetimeMs the maximum lifetime in milliseconds (0 = disabled)
     * @param idleBeforeRecycleMs the minimum idle time required before recycling
     * @return true if the session should be recycled
     */
    public boolean isExpired(long maxLifetimeMs, long idleBeforeRecycleMs) {
        if (maxLifetimeMs <= 0) {
            return false; // Max lifetime disabled
        }

        long ageNanos = System.nanoTime() - creationTime;
        long ageMs = ageNanos / 1_000_000L;

        if (ageMs <= maxLifetimeMs) {
            return false; // Not old enough yet
        }

        // Connection is old enough, check if it's been idle long enough
        return isIdle(idleBeforeRecycleMs);
    }

    /**
     * Gets the age of the session in nanoseconds.
     *
     * @return the age in nanoseconds since creation
     */
    public long getAge() {
        return System.nanoTime() - creationTime;
    }

    /**
     * Gets the idle time of the session in nanoseconds.
     *
     * @return the idle time in nanoseconds since last return (0 if never returned or currently borrowed)
     */
    public long getIdleTime() {
        if (lastReturnTime == 0) {
            return 0;
        }
        return System.nanoTime() - lastReturnTime;
    }

    /**
     * Gets the thread that currently has this session borrowed.
     *
     * @return the borrowing thread, or null if not borrowed
     */
    public Thread getBorrowingThread() {
        return borrowingThread;
    }

    /**
     * Gets the stack trace from when the session was borrowed.
     *
     * @return the stack trace, or null if not captured or not borrowed
     */
    public StackTraceElement[] getBorrowStackTrace() {
        return borrowStackTrace;
    }

    /**
     * Gets the timestamp when the session was last borrowed.
     *
     * @return the borrow timestamp in nanoseconds, or 0 if never borrowed
     */
    public long getLastBorrowTime() {
        return lastBorrowTime;
    }
}
