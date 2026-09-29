package org.openjproxy.xa.baseline.containers;

import oracle.jdbc.xa.client.OracleXADataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.OracleContainer;

import javax.sql.XADataSource;
import java.sql.SQLException;

/**
 * Oracle XA DataSource factory that uses the singleton OracleXATestContainer.
 * 
 * This class provides a simple way to create XADataSources for Oracle XA tests
 * without manually managing container lifecycle. The singleton pattern ensures
 * all tests share the same Oracle container instance.
 * 
 * Usage:
 * <pre>
 * OracleXAContainer oracle = new OracleXAContainer();
 * XADataSource xaDataSource = oracle.createXADataSource();
 * </pre>
 */
public class OracleXAContainer {
    
    private static final Logger logger = LoggerFactory.getLogger(OracleXAContainer.class);
    
    /**
     * Creates an XADataSource configured to connect to the singleton Oracle container.
     * The container is automatically started if not already running.
     * 
     * @return configured XADataSource
     * @throws SQLException if DataSource creation fails
     */
    public XADataSource createXADataSource() throws SQLException {
        // Get the singleton container (starts it if needed)
        OracleContainer container = OracleXATestContainer.getInstance();
        
        OracleXADataSource xaDataSource = new OracleXADataSource();
        
        // Configure connection properties using the singleton container
        String jdbcUrl = OracleXATestContainer.getJdbcUrl();
        xaDataSource.setURL(jdbcUrl);
        xaDataSource.setUser(OracleXATestContainer.getUsername());
        xaDataSource.setPassword(OracleXATestContainer.getPassword());
        
        logger.info("Created Oracle XADataSource for URL: {}", jdbcUrl);
        
        return xaDataSource;
    }
    
    /**
     * Gets the JDBC URL from the singleton container.
     * 
     * @return JDBC URL
     */
    public String getJdbcUrl() {
        return OracleXATestContainer.getJdbcUrl();
    }
    
    /**
     * Gets the username from the singleton container.
     * 
     * @return username
     */
    public String getUsername() {
        return OracleXATestContainer.getUsername();
    }
    
    /**
     * Gets the password from the singleton container.
     * 
     * @return password
     */
    public String getPassword() {
        return OracleXATestContainer.getPassword();
    }
    
    /**
     * Gets the database name from the singleton container.
     * 
     * @return database name
     */
    public String getDatabaseName() {
        return OracleXATestContainer.getDatabaseName();
    }
    
    /**
     * Checks if the singleton container is running.
     * 
     * @return true if container is running
     */
    public boolean isRunning() {
        OracleContainer container = OracleXATestContainer.getInstance();
        return container != null && container.isRunning();
    }
}
