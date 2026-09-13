package org.openjproxy.grpc.server;

import com.openjproxy.grpc.SessionInfo;
import com.openjproxy.grpc.TransactionStatus;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.grpc.server.cache.CacheConfiguration;

import javax.sql.DataSource;
import javax.sql.XAConnection;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class SessionManagerImpl implements SessionManager {

    private Map<String, String> connectionHashMap = new ConcurrentHashMap<>();
    private Map<String, Session> sessionMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, AtomicInteger>> clientRefCounts = new ConcurrentHashMap<>();
    private final Map<String, CacheConfiguration> cacheConfigurationMap;

    /**
     * Default constructor - no cache configuration support.
     * Used for backward compatibility with existing code.
     */
    public SessionManagerImpl() {
        this(null);
    }

    /**
     * Constructor with cache configuration map.
     *
     * @param cacheConfigurationMap Map of connection hash to cache configuration (can be null)
     */
    public SessionManagerImpl(Map<String, CacheConfiguration> cacheConfigurationMap) {
        this.cacheConfigurationMap = cacheConfigurationMap;
    }

    @Override
    public void registerClientUUID(String connectionHash, String clientUUID) {
        log.debug("Registering client uuid {}", clientUUID);
        this.connectionHashMap.put(clientUUID, connectionHash);
        clientRefCounts.computeIfAbsent(connectionHash, k -> new ConcurrentHashMap<>())
            .compute(clientUUID, (k, v) -> {
                if (v == null) {
                    return new AtomicInteger(1);
                }
                v.incrementAndGet();
                return v;
            });
    }

    @Override
    public void deregisterClientUUID(String connectionHash, String clientUUID) {
        ConcurrentHashMap<String, AtomicInteger> perConnHash = clientRefCounts.get(connectionHash);
        if (perConnHash == null) {
            return;
        }
        perConnHash.compute(clientUUID, (k, v) -> {
            if (v == null) {
                return null;
            }
            return v.decrementAndGet() <= 0 ? null : v;
        });
        if (perConnHash.isEmpty()) {
            clientRefCounts.remove(connectionHash, perConnHash);
        }
    }

    @Override
    public int getClientCount(String connectionHash) {
        ConcurrentHashMap<String, AtomicInteger> perConnHash = clientRefCounts.get(connectionHash);
        // Return 1 as default: assume at least one client for fair-share calculation
        return (perConnHash == null || perConnHash.isEmpty()) ? 1 : perConnHash.size();
    }

    @Override
    public SessionInfo createSession(String clientUUID, Connection connection) {
        log.debug("Create session for client uuid {}", clientUUID);
        String connectionHash = connectionHashMap.get(clientUUID);
        CacheConfiguration cacheConfig = getCacheConfiguration(connectionHash);
        Session session = new Session(connection, connectionHash, clientUUID, false, null, cacheConfig);
        log.debug("Session {} created for client uuid {}", session.getSessionUUID(), clientUUID);
        this.sessionMap.put(session.getSessionUUID(), session);
        return session.getSessionInfo();
    }

    @Override
    public SessionInfo createSession(String clientUUID, String connHash, Connection connection) {
        log.info("Create session for client uuid {} with connHash {}", clientUUID, connHash);
        CacheConfiguration cacheConfig = getCacheConfiguration(connHash);
        Session session = new Session(connection, connHash, clientUUID, false, null, cacheConfig);
        log.info("Session {} created for client uuid {}", session.getSessionUUID(), clientUUID);
        this.sessionMap.put(session.getSessionUUID(), session);
        return session.getSessionInfo();
    }

    @Override
    public SessionInfo createSession(String clientUUID, DataSource primaryDataSource, DataSource replicaDataSource) {
        log.info("Create lazy dual-datasource session for client uuid {}", clientUUID);
        String connectionHash = connectionHashMap.get(clientUUID);
        CacheConfiguration cacheConfig = getCacheConfiguration(connectionHash);
        Session session = new Session(primaryDataSource, replicaDataSource, connectionHash, clientUUID, cacheConfig);
        log.info("Lazy session {} created for client uuid {}", session.getSessionUUID(), clientUUID);
        this.sessionMap.put(session.getSessionUUID(), session);
        return session.getSessionInfo();
    }

    @Override
    public SessionInfo createSession(String clientUUID, String connHash, DataSource primaryDataSource, DataSource replicaDataSource) {
        log.info("Create lazy dual-datasource session for client uuid {} with connHash {}", clientUUID, connHash);
        CacheConfiguration cacheConfig = getCacheConfiguration(connHash);
        Session session = new Session(primaryDataSource, replicaDataSource, connHash, clientUUID, cacheConfig);
        log.info("Lazy session {} created for client uuid {}", session.getSessionUUID(), clientUUID);
        this.sessionMap.put(session.getSessionUUID(), session);
        return session.getSessionInfo();
    }

    @Override
    public SessionInfo createXASession(String clientUUID, Connection connection, XAConnection xaConnection) {
        log.debug("Create XA session for client uuid {}", clientUUID);
        String connectionHash = connectionHashMap.get(clientUUID);
        CacheConfiguration cacheConfig = getCacheConfiguration(connectionHash);
        Session session = new Session(connection, connectionHash, clientUUID, true, xaConnection, cacheConfig);
        log.debug("XA Session {} created for client uuid {}", session.getSessionUUID(), clientUUID);
        this.sessionMap.put(session.getSessionUUID(), session);
        return session.getSessionInfo();
    }

    @Override
    public SessionInfo createDeferredXASession(String clientUUID, String connectionHash) {
        log.debug("Create deferred XA session for client uuid {}", clientUUID);
        CacheConfiguration cacheConfig = getCacheConfiguration(connectionHash);
        // Create session without XAConnection - will be bound later via bindXAConnection()
        Session session = new Session(null, connectionHash, clientUUID, true, null, cacheConfig);
        log.debug("Deferred XA Session {} created for client uuid {}", session.getSessionUUID(), clientUUID);
        this.sessionMap.put(session.getSessionUUID(), session);
        return session.getSessionInfo();
    }

    /**
     * Retrieves cache configuration for the given connection hash.
     * Returns null if no configuration is found or if cache configuration map is not set.
     *
     * @param connectionHash the connection hash
     * @return the cache configuration, or null if not found
     */
    private CacheConfiguration getCacheConfiguration(String connectionHash) {
        if (cacheConfigurationMap == null || connectionHash == null) {
            return null;
        }
        return cacheConfigurationMap.get(connectionHash);
    }

    @Override
    public Session getSession(SessionInfo sessionInfo) {
        return this.sessionMap.get(sessionInfo.getSessionUUID());
    }

    @Override
    public Connection getConnection(SessionInfo sessionInfo) {
        log.debug("Getting a connection for session {}", sessionInfo.getSessionUUID());
        Session session = this.sessionMap.get(sessionInfo.getSessionUUID());
        return session != null ? session.getConnection() : null;
    }

    @Override
    public String registerResultSet(SessionInfo sessionInfo, ResultSet rs) {
        String uuid = UUID.randomUUID().toString();
        this.sessionMap.get(sessionInfo.getSessionUUID()).addResultSet(uuid, rs);
        return uuid;
    }

    @Override
    public ResultSet getResultSet(SessionInfo sessionInfo, String uuid) {
        Session session = this.sessionMap.get(sessionInfo.getSessionUUID());
        return session != null ? session.getResultSet(uuid) : null;
    }

    @Override
    public String registerStatement(SessionInfo sessionInfo, Statement stmt) {
        String uuid = UUID.randomUUID().toString();
        this.sessionMap.get(sessionInfo.getSessionUUID()).addStatement(uuid, stmt);
        return uuid;
    }

    @Override
    public Statement getStatement(SessionInfo sessionInfo, String uuid) {
        Session session = this.sessionMap.get(sessionInfo.getSessionUUID());
        return session != null ? session.getStatement(uuid) : null;
    }

    @Override
    public String registerPreparedStatement(SessionInfo sessionInfo, PreparedStatement ps) {
        String uuid = UUID.randomUUID().toString();
        this.sessionMap.get(sessionInfo.getSessionUUID()).addPreparedStatement(uuid, ps);
        return uuid;
    }

    @Override
    public PreparedStatement getPreparedStatement(SessionInfo sessionInfo, String uuid) {
        Session session = this.sessionMap.get(sessionInfo.getSessionUUID());
        return session != null ? session.getPreparedStatement(uuid) : null;
    }

    @Override
    public String registerCallableStatement(SessionInfo sessionInfo, CallableStatement cs) {
        String uuid = UUID.randomUUID().toString();
        this.sessionMap.get(sessionInfo.getSessionUUID()).addCallableStatement(uuid, cs);
        return uuid;
    }

    @Override
    public CallableStatement getCallableStatement(SessionInfo sessionInfo, String uuid) {
        Session session = this.sessionMap.get(sessionInfo.getSessionUUID());
        return session != null ? session.getCallableStatement(uuid) : null;
    }

    @Override
    public void registerLob(SessionInfo sessionInfo, Object lob, String lobUuid) {
        log.debug("Registering LOB with UUID {} for session {}", lobUuid, sessionInfo.getSessionUUID());
        Session session = this.sessionMap.get(sessionInfo.getSessionUUID());
        if (session == null) {
            log.error("Attempting to register LOB {} on null session {}", lobUuid, sessionInfo.getSessionUUID());
            throw new RuntimeException("Session not found: " + sessionInfo.getSessionUUID());
        }
        session.addLob(lobUuid, lob);
    }

    @Override
    public <T> T getLob(SessionInfo sessionInfo, String uuid) {
        Session session = this.sessionMap.get(sessionInfo.getSessionUUID());
        if (session == null) {
            log.error("Attempting to get LOB {} from null session {}", uuid, sessionInfo.getSessionUUID());
            return null;
        }
        T lob = (T) session.getLob(uuid);
        if (lob == null) {
            log.warn("LOB with UUID {} not found in session {}", uuid, sessionInfo.getSessionUUID());
        }
        return lob;
    }

    @Override
    public Collection<Object> getLobs(SessionInfo sessionInfo) {
        return (Collection<Object>) this.sessionMap.get(sessionInfo.getSessionUUID()).getAllLobs();
    }

    @Override
    public void terminateSession(SessionInfo sessionInfo) throws SQLException {
        log.debug("Terminating session -> {}", sessionInfo.getSessionUUID());
        Session targetSession = this.sessionMap.remove(sessionInfo.getSessionUUID());

        // Handle case where session doesn't exist on this server (multinode scenario)
        if (targetSession == null) {
            log.warn("Session {} not found on this server - may have been created on another server or already terminated",
                    sessionInfo.getSessionUUID());
            return;
        }

        if (!sessionInfo.getClientUUID().isEmpty()) {
            deregisterClientUUID(sessionInfo.getConnHash(), sessionInfo.getClientUUID());
        }

        if (TransactionStatus.TRX_ACTIVE.equals(sessionInfo.getTransactionInfo().getTransactionStatus())) {
            if (!targetSession.getConnection().getAutoCommit()) {
                log.debug("Rolling back active transaction");
                targetSession.getConnection().rollback();
            }
        }
        targetSession.terminate();
    }

    @SneakyThrows
    @Override
    public void waitLobStreamsConsumption(SessionInfo sessionInfo) {
        log.debug("Check if there are any binary stream lobs in session");
        Session session = this.sessionMap.get(sessionInfo.getSessionUUID());
        List<LobDataBlocksInputStream> binaryStreamsLobs = session.getAllLobs().stream()
                .filter((o) -> o instanceof LobDataBlocksInputStream)
                .map(LobDataBlocksInputStream.class::cast).toList();
        log.debug("{} binary stream lobs found ", binaryStreamsLobs.size());
        for (LobDataBlocksInputStream lob : binaryStreamsLobs) {
            log.debug("Verifying that lob {} is fully consumed.", lob.getUuid());
            while (!lob.getFullyConsumed().get()) {
                Thread.sleep(10);
            }
            log.debug("Lob {} fully consumed.", lob.getUuid());
            //During postgres tests it was found out that if the update is executed immediately after the lob injection
            //the lob is not yet set in the prepared statement, this thread sleep currently is required as per there is
            // no way to be sure that the prepared statement is ready, as this only affects Binary streams (not blobs or
            // clobs), the MVP will use this solution.
            // TODO attempt reengineering.
            Thread.sleep(100);
            log.debug("Binary stream lob finished");
        }
    }

    @Override
    public void registerAttr(SessionInfo sessionInfo, String key, Object value) {
        Session session = this.sessionMap.get(sessionInfo.getSessionUUID());
        if (session != null) {
            session.addAttr(key, value);
        }
    }

    @Override
    public Object getAttr(SessionInfo sessionInfo, String key) {
        Session session = this.sessionMap.get(sessionInfo.getSessionUUID());
        return session != null ? session.getAttr(key) : null;
    }

    @Override
    public void updateSessionActivity(SessionInfo sessionInfo) {
        if (sessionInfo != null && sessionInfo.getSessionUUID() != null && !sessionInfo.getSessionUUID().isEmpty()) {
            Session session = this.sessionMap.get(sessionInfo.getSessionUUID());
            if (session != null) {
                session.updateActivity();
            }
        }
    }

    @Override
    public Collection<Session> getAllSessions() {
        return this.sessionMap.values();
    }

}
