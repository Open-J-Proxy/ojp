package org.openjproxy.jdbc.xa;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.grpc.client.StatementService;
import org.openjproxy.grpc.client.StatementServiceGrpcClient;

import javax.sql.XAConnection;
import javax.sql.XADataSource;
import java.io.PrintWriter;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Implementation of XADataSource for OJP.
 * This is the entry point for JTA transaction managers to obtain XA connections.
 * Uses the integrated StatementService for all XA operations.
 */
@Slf4j
public class OjpXADataSource implements XADataSource {

    @Getter
    @Setter
    private String url;

    @Getter
    @Setter
    private String user;

    @Getter
    @Setter
    private String password;

    @Getter
    @Setter
    private int loginTimeout = 0;

    private PrintWriter logWriter;
    private final Properties properties = new Properties();
    private static StatementService statementService;

    public OjpXADataSource() {
        log.debug("Creating OjpXADataSource");
        initializeStatementService();
    }

    public OjpXADataSource(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
        log.debug("Creating OjpXADataSource with URL: {}", url);
        initializeStatementService();
    }

    private void initializeStatementService() {
        if (statementService == null) {
            synchronized (OjpXADataSource.class) {
                if (statementService == null) {
                    log.debug("Initializing StatementServiceGrpcClient for XA");
                    statementService = new StatementServiceGrpcClient();
                }
            }
        }
    }

    @Override
    public XAConnection getXAConnection() throws SQLException {
        log.debug("getXAConnection called");
        return getXAConnection(user, password);
    }

    @Override
    public XAConnection getXAConnection(String username, String password) throws SQLException {
        log.debug("getXAConnection called with username: {}", username);
        
        if (url == null || url.isEmpty()) {
            throw new SQLException("URL is not set");
        }

        // Create XA connection using the integrated StatementService
        return new OjpXAConnection(statementService, url, username, password, properties);
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return logWriter;
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        this.logWriter = out;
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        this.loginTimeout = seconds;
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return loginTimeout;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException("getParentLogger not supported");
    }

    /**
     * Set a connection property.
     */
    public void setProperty(String name, String value) {
        properties.setProperty(name, value);
    }

    /**
     * Get a connection property.
     */
    public String getProperty(String name) {
        return properties.getProperty(name);
    }

    /**
     * Get all properties.
     */
    public Properties getProperties() {
        return new Properties(properties);
    }
}
