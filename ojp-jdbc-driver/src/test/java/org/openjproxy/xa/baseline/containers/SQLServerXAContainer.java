package org.openjproxy.xa.baseline.containers;

import com.microsoft.sqlserver.jdbc.SQLServerXADataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.MSSQLServerContainer;

import javax.sql.XADataSource;
import java.sql.SQLException;

/**
 * SQL Server XA DataSource factory that uses the singleton SQLServerXATestContainer.
 * 
 * This class provides a simple way to create XADataSources for SQL Server XA tests
 * without manually managing container lifecycle. The singleton pattern ensures
 * all tests share the same SQL Server container instance.
 * 
 * Usage:
 * <pre>
 * SQLServerXAContainer sqlServer = new SQLServerXAContainer();
 * XADataSource xaDataSource = sqlServer.createXADataSource();
 * </pre>
 */
public class SQLServerXAContainer {
    
    private static final Logger logger = LoggerFactory.getLogger(SQLServerXAContainer.class);
    
    /**
     * Creates an XADataSource configured to connect to the singleton SQL Server container.
     * The container is automatically started if not already running.
     * 
     * @return configured XADataSource
     * @throws SQLException if DataSource creation fails
     */
    public XADataSource createXADataSource() throws SQLException {
        // Get the singleton container (starts it if needed)
        MSSQLServerContainer<?> container = SQLServerXATestContainer.getInstance();
        
        SQLServerXADataSource xaDataSource = new SQLServerXADataSource();
        
        // Configure connection properties using the singleton container
        String jdbcUrl = SQLServerXATestContainer.getJdbcUrl();
        xaDataSource.setServerName(container.getHost());
        xaDataSource.setPortNumber(container.getMappedPort(MSSQLServerContainer.MS_SQL_SERVER_PORT));
        xaDataSource.setDatabaseName(SQLServerXATestContainer.getTestDatabase());
        xaDataSource.setUser(SQLServerXATestContainer.getTestUsername());
        xaDataSource.setPassword(SQLServerXATestContainer.getTestPassword());
        
        // Trust server certificate (for testing)
        xaDataSource.setTrustServerCertificate(true);
        xaDataSource.setEncrypt(false);
        
        logger.info("Created SQL Server XADataSource for URL: {}", jdbcUrl);
        
        return xaDataSource;
    }
    
    /**
     * Gets the JDBC URL from the singleton container.
     * 
     * @return JDBC URL
     */
    public String getJdbcUrl() {
        return SQLServerXATestContainer.getJdbcUrl();
    }
    
    /**
     * Gets the username from the singleton container.
     * 
     * @return username
     */
    public String getUsername() {
        return SQLServerXATestContainer.getUsername();
    }
    
    /**
     * Gets the password from the singleton container.
     * 
     * @return password
     */
    public String getPassword() {
        return SQLServerXATestContainer.getPassword();
    }
    
    /**
     * Checks if the singleton container is running.
     * 
     * @return true if container is running
     */
    public boolean isRunning() {
        MSSQLServerContainer<?> container = SQLServerXATestContainer.getInstance();
        return container != null && container.isRunning();
    }
}
