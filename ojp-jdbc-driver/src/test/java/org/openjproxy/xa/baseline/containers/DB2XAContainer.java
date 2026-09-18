package org.openjproxy.xa.baseline.containers;

import com.ibm.db2.jcc.DB2XADataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Db2Container;

import javax.sql.XADataSource;
import java.sql.SQLException;

/**
 * DB2 XA DataSource factory that uses the singleton DB2XATestContainer.
 * 
 * This class provides a simple way to create XADataSources for DB2 XA tests
 * without manually managing container lifecycle. The singleton pattern ensures
 * all tests share the same DB2 container instance.
 * 
 * Usage:
 * <pre>
 * DB2XAContainer db2 = new DB2XAContainer();
 * XADataSource xaDataSource = db2.createXADataSource();
 * </pre>
 */
public class DB2XAContainer {
    
    private static final Logger logger = LoggerFactory.getLogger(DB2XAContainer.class);
    
    /**
     * Creates an XADataSource configured to connect to the singleton DB2 container.
     * The container is automatically started if not already running.
     * 
     * @return configured XADataSource
     * @throws SQLException if DataSource creation fails
     */
    public XADataSource createXADataSource() throws SQLException {
        // Get the singleton container (starts it if needed)
        Db2Container container = DB2XATestContainer.getInstance();
        
        DB2XADataSource xaDataSource = new DB2XADataSource();
        
        // Configure connection properties using the singleton container
        String jdbcUrl = DB2XATestContainer.getJdbcUrl();
        xaDataSource.setServerName(container.getHost());
        xaDataSource.setPortNumber(container.getMappedPort(Db2Container.DB2_PORT));
        xaDataSource.setDatabaseName(DB2XATestContainer.getDatabaseName());
        xaDataSource.setUser(DB2XATestContainer.getUsername());
        xaDataSource.setPassword(DB2XATestContainer.getPassword());
        
        // Enable XA support
        xaDataSource.setDriverType(4);  // Type 4 driver (pure Java)
        
        logger.info("Created DB2 XADataSource for URL: {}", jdbcUrl);
        
        return xaDataSource;
    }
    
    /**
     * Gets the JDBC URL from the singleton container.
     * 
     * @return JDBC URL
     */
    public String getJdbcUrl() {
        return DB2XATestContainer.getJdbcUrl();
    }
    
    /**
     * Gets the username from the singleton container.
     * 
     * @return username
     */
    public String getUsername() {
        return DB2XATestContainer.getUsername();
    }
    
    /**
     * Gets the password from the singleton container.
     * 
     * @return password
     */
    public String getPassword() {
        return DB2XATestContainer.getPassword();
    }
    
    /**
     * Gets the database name from the singleton container.
     * 
     * @return database name
     */
    public String getDatabaseName() {
        return DB2XATestContainer.getDatabaseName();
    }
    
    /**
     * Checks if the singleton container is running.
     * 
     * @return true if container is running
     */
    public boolean isRunning() {
        Db2Container container = DB2XATestContainer.getInstance();
        return container != null && container.isRunning();
    }
}
