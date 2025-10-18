package org.openjproxy.grpc.server;

import com.openjproxy.grpc.SessionInfo;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

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
 */
@Slf4j
public class Session {
    @Getter
    private final String sessionUUID;
    @Getter
    private final String connectionHash;
    @Getter
    private final String clientUUID;
    @Getter
    private Connection connection;
    @Getter
    private final boolean isXA;
    @Getter
    private XAConnection xaConnection;
    @Getter
    private XAResource xaResource;
    private Map<String, ResultSet> resultSetMap;
    private Map<String, Statement> statementMap;
    private Map<String, PreparedStatement> preparedStatementMap;
    private Map<String, CallableStatement> callableStatementMap;
    private Map<String, Object> lobMap;
    private Map<String, Object> attrMap;
    private boolean closed;
    private int transactionTimeout = 0;

    public Session(Connection connection, String connectionHash, String clientUUID) {
        this(connection, connectionHash, clientUUID, false, null);
    }

    public Session(Connection connection, String connectionHash, String clientUUID, boolean isXA, XAConnection xaConnection) {
        this.connection = connection;
        this.connectionHash = connectionHash;
        this.clientUUID = clientUUID;
        this.isXA = isXA;
        this.xaConnection = xaConnection;
        this.sessionUUID = UUID.randomUUID().toString();
        this.closed = false;
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

        // For XA connections, close the XA connection (which also closes the logical connection)
        // Do NOT close the regular connection as it would trigger auto-commit changes
        if (isXA && xaConnection != null) {
            try {
                xaConnection.close();
            } catch (SQLException e) {
                log.error("Error closing XA connection", e);
            }
        } else if (connection != null) {
            // For regular connections, close normally
            this.connection.close();
        }

        //Clear session internal objects to free memory
        this.closed = true;
        this.lobMap = null;
        this.resultSetMap = null;
        this.statementMap = null;
        this.preparedStatementMap = null;
        this.connection = null;
        this.xaConnection = null;
        this.xaResource = null;
        this.attrMap = null;
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
}
