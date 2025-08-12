package org.openjdbcproxy.grpc.server;

import com.openjdbcproxy.grpc.SessionInfo;
import com.openjdbcproxy.grpc.TransactionStatus;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

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

@Slf4j
public class SessionManagerImpl implements SessionManager {

    private Map<String, String> connectionHashMap = new ConcurrentHashMap<>();
    private Map<String, Session> sessionMap = new ConcurrentHashMap<>();

    @Override
    public void registerClientUUID(String connectionHash, String clientUUID) {
        log.info("Registering client uuid {}", clientUUID);
        this.connectionHashMap.put(clientUUID, connectionHash);
    }

    @Override
    public SessionInfo createSession(String clientUUID, Connection connection) {
        log.info("Create session for client uuid " + clientUUID);
        Session session = new Session(connection, connectionHashMap.get(clientUUID), clientUUID);
        log.info("Session " + session.getSessionUUID() + " created for client uuid " + clientUUID);
        this.sessionMap.put(session.getSessionUUID(), session);
        return session.getSessionInfo();
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
        return this.sessionMap.get(sessionInfo.getSessionUUID()).getResultSet(uuid);
    }

    @Override
    public String registerStatement(SessionInfo sessionInfo, Statement stmt) {
        String uuid = UUID.randomUUID().toString();
        this.sessionMap.get(sessionInfo.getSessionUUID()).addStatement(uuid, stmt);
        return uuid;
    }

    @Override
    public Statement getStatement(SessionInfo sessionInfo, String uuid) {
        return this.sessionMap.get(sessionInfo.getSessionUUID()).getStatement(uuid);
    }

    @Override
    public String registerPreparedStatement(SessionInfo sessionInfo, PreparedStatement ps) {
        String uuid = UUID.randomUUID().toString();
        this.sessionMap.get(sessionInfo.getSessionUUID()).addPreparedStatement(uuid, ps);
        return uuid;
    }

    @Override
    public PreparedStatement getPreparedStatement(SessionInfo sessionInfo, String uuid) {
        return this.sessionMap.get(sessionInfo.getSessionUUID()).getPreparedStatement(uuid);
    }

    @Override
    public String registerCallableStatement(SessionInfo sessionInfo, CallableStatement cs) {
        String uuid = UUID.randomUUID().toString();
        this.sessionMap.get(sessionInfo.getSessionUUID()).addCallableStatement(uuid, cs);
        return uuid;
    }

    @Override
    public CallableStatement getCallableStatement(SessionInfo sessionInfo, String uuid) {
        return this.sessionMap.get(sessionInfo.getSessionUUID()).getCallableStatement(uuid);
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
        log.info("Terminating session -> " + sessionInfo.getSessionUUID());
        Session targetSession = this.sessionMap.remove(sessionInfo.getSessionUUID());

        if (TransactionStatus.TRX_ACTIVE.equals(sessionInfo.getTransactionInfo().getTransactionStatus())) {
            if (!targetSession.getConnection().getAutoCommit()) {
                log.info("Rolling back active transaction");
                targetSession.getConnection().rollback();
            }
        }
        targetSession.terminate();
    }

    @SneakyThrows
    @Override
    public void waitLobStreamsConsumption(SessionInfo sessionInfo) {
        log.info("Check if there are any binary stream lobs in session");
        Session session = this.sessionMap.get(sessionInfo.getSessionUUID());
        List<LobDataBlocksInputStream> binaryStreamsLobs = session.getAllLobs().stream()
                .filter((o) -> o instanceof LobDataBlocksInputStream)
                .map(LobDataBlocksInputStream.class::cast).toList();
        log.info("{} binary stream lobs found ", binaryStreamsLobs.size());
        for (LobDataBlocksInputStream lob : binaryStreamsLobs) {
            log.info("Verifying that lob {} is fully consumed.", lob.getUuid());
            while (!lob.getFullyConsumed().get()) {
                Thread.sleep(10);
            }
            log.info("Lob {} fully consumed.", lob.getUuid());
            //During postgres tests it was found out that if the update is executed immediately after the lob injection
            //the lob is not yet set in the prepared statement, this thread sleep currently is required as per there is
            // no way to be sure that the prepared statement is ready, as this only affects Binary streams (not blobs or
            // clobs), the MVP will use this solution.
            // TODO attempt reengineering.
            Thread.sleep(100);
            log.info("Binary stream lob finished");
        }
    }

    @Override
    public void registerAttr(SessionInfo sessionInfo, String key, Object value) {
        Session session = this.sessionMap.get(sessionInfo.getSessionUUID());
        session.addAttr(key, value);
    }

    @Override
    public Object getAttr(SessionInfo sessionInfo, String key) {
        Session session = this.sessionMap.get(sessionInfo.getSessionUUID());
        return session.getAttr(key);
    }
}
