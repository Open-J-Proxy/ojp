package org.openjproxy.grpc.server;

import com.openjproxy.grpc.SessionInfo;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.grpc.server.cache.CacheConfiguration;

import javax.sql.DataSource;
import javax.sql.XAConnection;
import javax.transaction.xa.XAResource;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds information about a session of a given client.
 * <p>
 * Supports two construction modes:
 * <ul>
 *   <li><b>Eager (XA / legacy)</b>: constructed with a pre-acquired {@code Connection}.
 *       {@link #getConnection()} returns that connection immediately.</li>
 *   <li><b>Lazy (dual-datasource)</b>: constructed with {@code DataSource} references.
 *       {@link #getConnection()} acquires from the primary datasource on first call;
 *       {@link #getOrCreateReplicaConnection()} acquires from the replica datasource on
 *       first call. This allows replica-only sessions to avoid allocating a primary
 *       connection entirely.</li>
 * </ul>
 */
@Slf4j
public class Session {
    @Getter
    private final String sessionUUID;
    @Getter
    private final String connectionHash;
    @Getter
    private final String clientUUID;
    /** Primary connection — may be null until lazily acquired from {@link #primaryDataSource}. */
    private volatile Connection primaryConnection;
    /** Replica connection — null until lazily acquired via {@link #getOrCreateReplicaConnection()}. */
    private volatile Connection replicaConnection;
    /** DataSource for lazy primary acquisition; null for eagerly-constructed (XA) sessions. */
    private final DataSource primaryDataSource;
    /** DataSource for lazy replica acquisition; null when no replica is configured. */
    private final DataSource replicaDataSource;
    @Getter
    private final boolean isXA;
    @Getter
    private XAConnection xaConnection;
    @Getter
    private XAResource xaResource;
    @Getter
    private Object backendSession; // Holds XABackendSession for XA pooling (avoids hard dependency)
    @Getter
    private final CacheConfiguration cacheConfiguration;  // Can be null if caching not configured
    private Map<String, ResultSet> resultSetMap;
    private Map<String, Statement> statementMap;
    private Map<String, PreparedStatement> preparedStatementMap;
    private Map<String, CallableStatement> callableStatementMap;
    private Map<String, Object> lobMap;
    private Map<String, Object> attrMap;
    private Runnable connectionPermitReleaseHook;
    private boolean closed;
    private int transactionTimeout = 0;
    @Getter
    private volatile long lastActivityTime;
    @Getter
    private final long creationTime;

    /**
     * Lazy dual-datasource constructor.  No connections are acquired at
     * construction time; they are obtained on demand when
     * {@link #getConnection()} or {@link #getOrCreateReplicaConnection()} is
     * first called.
     *
     * @param primaryDataSource  datasource for the primary database (never null)
     * @param replicaDataSource  datasource for a read replica; {@code null} when
     *                           no replica is configured
     * @param connectionHash     connection hash identifying this datasource pair
     * @param clientUUID         client identifier
     * @param cacheConfiguration optional query-cache configuration (may be null)
     */
    public Session(DataSource primaryDataSource, DataSource replicaDataSource,
                   String connectionHash, String clientUUID,
                   CacheConfiguration cacheConfiguration) {
        this.primaryDataSource = primaryDataSource;
        this.replicaDataSource = replicaDataSource;
        this.primaryConnection = null;
        this.replicaConnection = null;
        this.connectionHash = connectionHash;
        this.clientUUID = clientUUID;
        this.isXA = false;
        this.xaConnection = null;
        this.cacheConfiguration = cacheConfiguration;
        this.sessionUUID = UUID.randomUUID().toString();
        this.closed = false;
        this.creationTime = System.nanoTime();
        this.lastActivityTime = this.creationTime;
        this.resultSetMap = new ConcurrentHashMap<>();
        this.statementMap = new ConcurrentHashMap<>();
        this.preparedStatementMap = new ConcurrentHashMap<>();
        this.callableStatementMap = new ConcurrentHashMap<>();
        this.lobMap = new ConcurrentHashMap<>();
        this.attrMap = new ConcurrentHashMap<>();
    }

    // ---- Eager constructors (kept for XA and legacy non-XA callers) ----

    public Session(Connection connection, String connectionHash, String clientUUID) {
        this(connection, connectionHash, clientUUID, false, null, null);
    }

    public Session(Connection connection, String connectionHash, String clientUUID, boolean isXA, XAConnection xaConnection) {
        this(connection, connectionHash, clientUUID, isXA, xaConnection, null);
    }

    public Session(Connection connection, String connectionHash, String clientUUID, boolean isXA, XAConnection xaConnection, CacheConfiguration cacheConfiguration) {
        this.primaryConnection = connection;
        this.replicaConnection = null;
        this.primaryDataSource = null;
        this.replicaDataSource = null;
        this.connectionHash = connectionHash;
        this.clientUUID = clientUUID;
        this.isXA = isXA;
        this.xaConnection = xaConnection;
        this.cacheConfiguration = cacheConfiguration;  // Can be null
        this.sessionUUID = UUID.randomUUID().toString();
        this.closed = false;
        this.creationTime = System.nanoTime();
        this.lastActivityTime = this.creationTime;
        this.resultSetMap = new ConcurrentHashMap<>();
        this.statementMap = new ConcurrentHashMap<>();
        this.preparedStatementMap = new ConcurrentHashMap<>();
        this.callableStatementMap = new ConcurrentHashMap<>();
        this.lobMap = new ConcurrentHashMap<>();
        this.attrMap = new ConcurrentHashMap<>();

        if (isXA && xaConnection != null) {
            try {
                this.xaResource = xaConnection.getXAResource();
            } catch (SQLException e) {
                log.error("Failed to get XAResource from XAConnection", e);
                throw new RuntimeException("Failed to initialize XA session", e);
            }
        }
    }

    /**
     * Binds an XAConnection to this session (for lazy XA allocation with pooling).
     * This method is thread-safe and can only be called once.
     *
     * @param xaConn The XAConnection to bind
     * @param backendSession The XABackendSession wrapper (from XA pool)
     * @throws IllegalStateException if XAConnection is already bound (unless both parameters are null for unbinding)
     */
    public synchronized void bindXAConnection(XAConnection xaConn, Object backendSession) {
        // Allow unbinding by passing null for both parameters
        if (xaConn == null && backendSession == null) {
            this.xaConnection = null;
            this.backendSession = null;
            this.primaryConnection = null;
            this.xaResource = null;
            log.debug("Unbound XAConnection from session {}", sessionUUID);
            return;
        }

        if (this.xaConnection != null) {
            throw new IllegalStateException("XAConnection already bound to session");
        }
        if (!this.isXA) {
            throw new IllegalStateException("Cannot bind XAConnection to non-XA session");
        }

        try {
            this.xaConnection = xaConn;
            this.backendSession = backendSession;
            this.primaryConnection = xaConn.getConnection();
            this.xaResource = xaConn.getXAResource();
            log.debug("Bound XAConnection to session {}", sessionUUID);
        } catch (SQLException e) {
            log.error("Failed to bind XAConnection", e);
            throw new RuntimeException("Failed to bind XAConnection", e);
        }
    }

    /**
     * Sets the backend session reference for XA pooling.
     *
     * @param backendSession The XABackendSession from the XA pool
     */
    public void setBackendSession(Object backendSession) {
        this.backendSession = backendSession;
    }

    /**
     * Refreshes the connection reference from the backend session.
     * This is called after XA transaction sanitization to update the connection
     * reference to the new logical connection obtained from the XAConnection.
     *
     * @throws SQLException if unable to get connection from backend session
     */
    public void refreshConnection() throws SQLException {
        if (backendSession != null && backendSession instanceof org.openjproxy.xa.pool.XABackendSession) {
            org.openjproxy.xa.pool.XABackendSession xaBackendSession =
                (org.openjproxy.xa.pool.XABackendSession) backendSession;
            this.primaryConnection = xaBackendSession.getConnection();
            log.debug("Refreshed connection reference in session {}", sessionUUID);
        }
    }

    /**
     * Gets the primary JDBC connection for this session.
     * <p>
     * For lazy sessions (created with {@link #Session(DataSource, DataSource, String, String, CacheConfiguration)}),
     * this acquires a connection from the primary datasource on first call and caches it
     * for subsequent calls.  For XA sessions with a pooled backend, the fresh connection
     * is returned from the backend session.
     *
     * @return the primary JDBC connection, or {@code null} if the session has no primary
     *         datasource and no eagerly-supplied connection
     */
    public synchronized Connection getConnection() {
        // For XA sessions with backend session, always get fresh connection reference
        // This ensures we get the updated connection after sanitization
        if (isXA && backendSession != null && backendSession instanceof org.openjproxy.xa.pool.XABackendSession) {
            org.openjproxy.xa.pool.XABackendSession xaBackendSession =
                (org.openjproxy.xa.pool.XABackendSession) backendSession;
            return xaBackendSession.getConnection();
        }
        // Lazy acquisition for dual-datasource sessions
        if (primaryConnection == null && primaryDataSource != null) {
            try {
                primaryConnection = primaryDataSource.getConnection();
                log.debug("Lazily acquired primary connection for session {}", sessionUUID);
            } catch (SQLException e) {
                throw new RuntimeException("Failed to acquire primary connection for session " + sessionUUID, e);
            }
        }
        return primaryConnection;
    }

    /**
     * Gets (or lazily creates) the replica JDBC connection for this session.
     * <p>
     * The connection is acquired from the replica datasource supplied at construction
     * time and cached for subsequent calls.  Returns {@code null} when no replica
     * datasource was configured.
     *
     * @return the replica JDBC connection, or {@code null} if no replica is configured
     * @throws SQLException if acquiring the connection from the pool fails
     */
    public synchronized Connection getOrCreateReplicaConnection() throws SQLException {
        return getOrCreateReplicaConnection(null);
    }

    /**
     * Gets (or lazily creates) the replica JDBC connection for this session.
     * <p>
     * Uses the replica datasource supplied at construction time when available;
     * falls back to {@code fallbackReplicaDs} when the session was created without
     * a replica datasource (e.g. originally created for a write / INSERT).  The
     * acquired connection is cached and reused on subsequent calls.
     *
     * @param fallbackReplicaDs datasource to use when no replica datasource was set
     *                          at construction time; may be {@code null}
     * @return the replica JDBC connection, or {@code null} if no replica datasource
     *         is available
     * @throws SQLException if acquiring the connection from the pool fails
     */
    public synchronized Connection getOrCreateReplicaConnection(DataSource fallbackReplicaDs) throws SQLException {
        if (replicaConnection == null) {
            DataSource ds = (replicaDataSource != null) ? replicaDataSource : fallbackReplicaDs;
            if (ds != null) {
                replicaConnection = ds.getConnection();
                log.debug("[RW-SPLIT] getOrCreateReplicaConnection: CREATED new replica connection from {} datasource for session={}",
                        (replicaDataSource != null) ? "instance" : "fallback", sessionUUID);
            } else {
                log.debug("[RW-SPLIT] getOrCreateReplicaConnection: no datasource available (replicaDataSource=null, fallback=null) for session={}",
                        sessionUUID);
            }
        } else {
            log.debug("[RW-SPLIT] getOrCreateReplicaConnection: REUSING existing replica connection for session={}", sessionUUID);
        }
        return replicaConnection;
    }

    /**
     * Returns {@code true} when the primary connection exists and has an open
     * (non-autoCommit) transaction.  Does <em>not</em> trigger lazy primary
     * connection acquisition; returns {@code false} when no primary connection
     * has been acquired yet (i.e. the session is replica-only so far).
     *
     * <p>This method is {@code synchronized} to ensure it sees the latest value
     * of {@code primaryConnection} (e.g. after a concurrent {@link #getConnection()}
     * call).
     *
     * @return {@code true} if there is an active transaction on the primary connection
     */
    public synchronized boolean hasActiveTransaction() {
        if (primaryConnection == null) {
            log.debug("[RW-SPLIT] hasActiveTransaction: primaryConnection is null, returning false for session={}", sessionUUID);
            return false;
        }
        try {
            boolean active = !primaryConnection.getAutoCommit();
            log.debug("[RW-SPLIT] hasActiveTransaction: session={}, autoCommit={}, hasActiveTransaction={}",
                    sessionUUID, !active, active);
            return active;
        } catch (SQLException e) {
            // Safety: assume transaction present if we cannot determine
            log.warn("Could not determine autoCommit state for session {}; assuming active transaction", sessionUUID);
            return true;
        }
    }

    public SessionInfo getSessionInfo() {
        log.debug("get session info -> " + this.connectionHash);
        return SessionInfo.newBuilder()
                .setConnHash(this.connectionHash)
                .setClientUUID(this.clientUUID)
                .setSessionUUID(this.sessionUUID)
                .setIsXA(this.isXA)
                .build();
    }

    public void addAttr(String key, Object value) {
        this.notClosed();
        this.attrMap.put(key, value);
    }

    public Object getAttr(String key) {
        this.notClosed();
        return this.attrMap.get(key);
    }

    public void addResultSet(String uuid, ResultSet rs) {
        this.notClosed();
        this.resultSetMap.put(uuid, rs);
    }

    public ResultSet getResultSet(String uuid) {
        this.notClosed();
        return this.resultSetMap.get(uuid);
    }

    public void addStatement(String uuid, Statement stmt) {
        this.notClosed();
        this.statementMap.put(uuid, stmt);
    }

    public Statement getStatement(String uuid) {
        this.notClosed();
        return this.statementMap.get(uuid);
    }

    public void addPreparedStatement(String uuid, PreparedStatement ps) {
        this.notClosed();
        this.preparedStatementMap.put(uuid, ps);
    }

    public PreparedStatement getPreparedStatement(String uuid) {
        this.notClosed();
        return this.preparedStatementMap.get(uuid);
    }

    public void addCallableStatement(String uuid, CallableStatement cs) {
        this.notClosed();
        this.callableStatementMap.put(uuid, cs);
    }

    public CallableStatement getCallableStatement(String uuid) {
        this.notClosed();
        return this.callableStatementMap.get(uuid);
    }

    public void addLob(String uuid, Object o) {
        this.notClosed();
        if (o != null) {
            this.lobMap.put(uuid, o);
        }
    }

    public <T> T getLob(String uuid) {
        this.notClosed();
        return (T) this.lobMap.get(uuid);
    }

    private void notClosed() {
        if (this.closed) {
            throw new RuntimeException("Session is closed.");
        }
    }

    public void terminate() throws SQLException {

        if (this.closed) {
            return;
        }

        // For XA connections with pooled XABackendSession, DO NOT close anything here
        // The XATransactionRegistry handles returning sessions to the pool via returnCompletedSessions()
        // which is called when the OJP XAConnection is closed (dual-condition lifecycle)
        if (isXA && backendSession != null) {
            // Pooled XA backend session - managed by XATransactionRegistry
            // Do nothing here - registry will return session to pool when appropriate
            log.debug("Skipping close for pooled XABackendSession {} - managed by XATransactionRegistry", sessionUUID);
        } else if (isXA && xaConnection != null) {
            // For XA connections WITHOUT pooling (pass-through mode), close the XA connection
            // Do NOT close the regular connection as it would trigger auto-commit changes
            try {
                xaConnection.close();
            } catch (SQLException e) {
                log.error("Error closing XA connection", e);
            }
        } else {
            // Non-XA: close replica connection first (if acquired), then primary (if acquired)
            if (replicaConnection != null) {
                try {
                    replicaConnection.close();
                } catch (SQLException e) {
                    log.error("Error closing replica connection for session {}", sessionUUID, e);
                }
            }
            if (primaryConnection != null) {
                try {
                    primaryConnection.close();
                } catch (SQLException e) {
                    log.error("Error closing primary connection for session {}", sessionUUID, e);
                }
            }
        }

        //Clear session internal objects to free memory
        this.closed = true;
        this.lobMap = null;
        this.resultSetMap = null;
        this.statementMap = null;
        this.preparedStatementMap = null;
        this.primaryConnection = null;
        this.replicaConnection = null;
        this.xaConnection = null;
        this.xaResource = null;
        this.backendSession = null;
        this.attrMap = null;
        releaseConnectionPermitIfPresent();
    }

    public synchronized void setConnectionPermitReleaseHook(Runnable connectionPermitReleaseHook) {
        this.connectionPermitReleaseHook = connectionPermitReleaseHook;
    }

    /**
     * Returns true if this session currently owns an admission-control permit
     * (acquired at session creation and released on session termination).
     * Used by request handlers to avoid acquiring a second per-statement slot
     * for a session that already holds one.
     */
    public synchronized boolean hasConnectionPermit() {
        return this.connectionPermitReleaseHook != null;
    }

    private synchronized void releaseConnectionPermitIfPresent() {
        if (this.connectionPermitReleaseHook != null) {
            try {
                this.connectionPermitReleaseHook.run();
            } catch (Exception e) {
                log.warn("Failed to release connection admission permit for session {}", sessionUUID, e);
            } finally {
                this.connectionPermitReleaseHook = null;
            }
        }
    }

    public void setTransactionTimeout(int seconds) {
        this.transactionTimeout = seconds;
    }

    public int getTransactionTimeout() {
        return this.transactionTimeout;
    }

    public Collection<Object> getAllLobs() {
        return this.lobMap.values();
    }

    /**
     * Updates the last activity time for this session to the current time.
     * This method should be called on every operation that uses this session
     * to prevent premature cleanup of active sessions.
     */
    public void updateActivity() {
        this.lastActivityTime = System.nanoTime();
    }

    /**
     * Checks if the session has been inactive for longer than the specified timeout.
     *
     * @param timeoutMillis the inactivity timeout in milliseconds
     * @return true if the session has been inactive for longer than the timeout, false otherwise
     */
    public boolean isInactive(long timeoutMillis) {
        long inactiveDuration = (System.nanoTime() - this.lastActivityTime) / 1_000_000L;
        return inactiveDuration > timeoutMillis;
    }

    /**
     * Gets the duration in milliseconds since the last activity.
     *
     * @return milliseconds since last activity
     */
    public long getInactiveDuration() {
        return (System.nanoTime() - this.lastActivityTime) / 1_000_000L;
    }
}
