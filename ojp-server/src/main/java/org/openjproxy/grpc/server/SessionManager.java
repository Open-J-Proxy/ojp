package org.openjproxy.grpc.server;

import com.openjproxy.grpc.SessionInfo;

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
    SessionInfo createSession(String clientUUID, Connection connection);
    SessionInfo createXASession(String clientUUID, Connection connection, XAConnection xaConnection);
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

}
