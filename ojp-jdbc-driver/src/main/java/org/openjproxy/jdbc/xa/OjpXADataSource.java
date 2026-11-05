package org.openjproxy.jdbc.xa;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.constants.CommonConstants;
import org.openjproxy.grpc.client.MultinodeConnectionManager;
import org.openjproxy.grpc.client.MultinodeStatementService;
import org.openjproxy.grpc.client.MultinodeUrlParser;
import org.openjproxy.grpc.client.StatementService;
import org.openjproxy.jdbc.DatasourcePropertiesLoader;
import org.openjproxy.jdbc.UrlParser;

import javax.sql.XAConnection;
import javax.sql.XADataSource;
import java.io.PrintWriter;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

/**
 * Implementation of XADataSource for OJP.
 * This is the entry point for JTA transaction managers to obtain XA connections.
 * Uses the integrated StatementService for all XA operations.
 *
 * <p>The GRPC connection is initialized once per datasource and reused by all XA connections
 * to avoid the overhead of creating multiple GRPC channels.
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

    // Shared StatementService per datasource - initialized lazily
    private StatementService statementService;

    // Parsed URL information
    private String cleanUrl;
    private String dataSourceName;
    private List<String> serverEndpoints;

    private static final ReentrantLock INIT_LOCK = new ReentrantLock();

    public OjpXADataSource() {
        log.debug("Creating OjpXADataSource");
    }

    public OjpXADataSource(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
        log.debug("Creating OjpXADataSource with URL: {}", url);
    }

    /**
     * Initialize the StatementService and parse URL.
     * This is done lazily when the first XA connection is requested.
     * The GRPC channel is opened once and reused by all XA connections from this datasource.
     */
    private void initializeIfNeeded() throws SQLException {
        // Fast path - no lock needed if already initialized
        if (statementService != null) {
            return; // Already initialized
        }

        INIT_LOCK.lock();
        try {
            // Double-check inside lock
            if (statementService != null) {
                return;
            }

            if (url == null || url.isEmpty()) {
                throw new SQLException("URL is not set");
            }
            // Parse URL to extract datasource name and clean URL
            UrlParser.UrlParseResult urlParseResult = UrlParser.parseUrlWithDataSource(url);
            this.cleanUrl = urlParseResult.cleanUrl;
            this.dataSourceName = urlParseResult.dataSourceName;

            log.debug("Parsed URL - clean: {}, dataSource: {}", cleanUrl, dataSourceName);

            // Load ojp.properties file and extract datasource-specific configuration
            Properties ojpProperties = DatasourcePropertiesLoader.loadOjpPropertiesForDataSource(dataSourceName);
            if (ojpProperties != null && !ojpProperties.isEmpty()) {
                // Merge ojp.properties with any manually set properties, then always enforce
                // the name inferred from the URL (ojp.datasource.name is never user-configurable)
                for (String key : ojpProperties.stringPropertyNames()) {
                    if (!properties.containsKey(key)) {
                        properties.setProperty(key, ojpProperties.getProperty(key));
                    }
                }
                // Always use the name inferred from the URL, regardless of what may have been set
                properties.setProperty(CommonConstants.DATASOURCE_NAME_PROPERTY, dataSourceName);
                log.debug("Loaded ojp.properties with {} properties for dataSource: {}", ojpProperties.size(), dataSourceName);
            }

            // Initialize StatementService - this will open the GRPC channel on first use
            log.debug("Initializing StatementService for XA datasource: {}", dataSourceName);

            // Get or create the StatementService for the configured endpoint(s)
            MultinodeUrlParser.ServiceAndUrl serviceAndUrl = MultinodeUrlParser.getOrCreateStatementService(
                    cleanUrl, urlParseResult.dataSourceNames);
            statementService = serviceAndUrl.getService();
            this.serverEndpoints = serviceAndUrl.getServerEndpoints();

            // The GRPC channel will be opened lazily on the first connect() call
            // Since this StatementService instance is shared by all XA connections from this datasource,
            // the channel is opened once and reused
            log.info("StatementService initialized for datasource: {}. GRPC channel will open on first use.", dataSourceName);
        } finally {
            INIT_LOCK.unlock();
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

        // Initialize on first use (lazily)
        initializeIfNeeded();

        // Create XA connection using the shared StatementService
        // The GRPC channel is already open and will be reused
        // The session will be created lazily when first needed
        OjpXAConnection xaConnection = new OjpXAConnection(statementService, cleanUrl, username, password, properties, serverEndpoints);

        // Phase 2: Register connection as health listener if using multinode
        if (statementService instanceof MultinodeStatementService) {
            MultinodeStatementService multinodeService = (MultinodeStatementService) statementService;
            MultinodeConnectionManager connectionManager = multinodeService.getConnectionManager();
            if (connectionManager != null) {
                connectionManager.addHealthListener(xaConnection);
                log.debug("Registered XA connection as health listener");
            }
        }

        return xaConnection;
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
