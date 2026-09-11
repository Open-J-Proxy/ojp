package org.openjproxy.grpc.server;

import com.openjproxy.grpc.SessionInfo;

import javax.sql.DataSource;
import javax.sql.XAConnection;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;

/**
 * Manages sessions established with clients
 */
public interface SessionManager {
    void registerClientUUID(String connectionHash, String clientUUID);
    void deregisterClientUUID(String connectionHash, String clientUUID);
    int getClientCount(String connectionHash);
    SessionInfo createSession(String clientUUID, Connection connection);
    SessionInfo createSession(String clientUUID, String connHash, Connection connection);
    /**
     * Creates a lazy dual-datasource session.  No JDBC connections are acquired
     * at creation time; they are obtained on demand when
     * {@link Session#getConnection()} or {@link Session#getOrCreateReplicaConnection()}
     * is first called.
     *
     * @param clientUUID        the client identifier
     * @param primaryDataSource datasource for the primary database
     * @param replicaDataSource datasource for the read replica; {@code null} when no replica is configured
     * @return the new session info
     */
    SessionInfo createSession(String clientUUID, DataSource primaryDataSource, DataSource replicaDataSource);
    /**
     * Creates a lazy dual-datasource session with an explicitly supplied connection hash.
     * Use this overload when the caller already holds the primary's {@code connHash}
     * (e.g. from the driver's request) to avoid relying on the potentially stale
     * {@code connectionHashMap} lookup.
     *
     * @param clientUUID        the client identifier
     * @param connHash          the primary's connection hash (from the driver request)
     * @param primaryDataSource datasource for the primary database
     * @param replicaDataSource datasource for the read replica; {@code null} when no replica is configured
     * @return the new session info
     */
    SessionInfo createSession(String clientUUID, String connHash, DataSource primaryDataSource, DataSource replicaDataSource);
    SessionInfo createXASession(String clientUUID, Connection connection, XAConnection xaConnection);
    SessionInfo createDeferredXASession(String clientUUID, String connectionHash);
    Session getSession(SessionInfo sessionInfo);
    Connection getConnection(SessionInfo sessionInfo);
    String registerResultSet(SessionInfo sessionInfo, ResultSet rs);
    ResultSet getResultSet(SessionInfo sessionInfo, String uuid);
    String registerStatement(SessionInfo sessionInfo, Statement stmt);
    Statement getStatement(SessionInfo sessionInfo, String uuid);
    String registerPreparedStatement(SessionInfo sessionInfo, PreparedStatement ps);
    PreparedStatement getPreparedStatement(SessionInfo sessionInfo, String uuid);
    String registerCallableStatement(SessionInfo sessionInfo, CallableStatement cs);
    CallableStatement getCallableStatement(SessionInfo sessionInfo, String uuid);
    void registerLob(SessionInfo sessionInfo, Object o, String lobUuid);
    <T> T getLob(SessionInfo sessionInfo, String uuid);
    Collection<Object> getLobs(SessionInfo sessionInfo);
    void terminateSession(SessionInfo sessionInfo) throws SQLException;
    void waitLobStreamsConsumption(SessionInfo sessionInfo);
    void registerAttr(SessionInfo sessionInfo, String key, Object value);
    Object getAttr(SessionInfo sessionInfo, String key);
    void updateSessionActivity(SessionInfo sessionInfo);
    Collection<Session> getAllSessions();

}
