package org.openjproxy.xa.baseline.containers;

import org.testcontainers.containers.OracleContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Singleton Oracle XA test container for all Oracle XA integration tests.
 * This ensures that all tests share the same Oracle instance to improve test performance
 * and reduce resource usage.
 */
public class OracleXATestContainer {
    
    // Oracle XE Docker image version
    private static final String ORACLE_IMAGE = "gvenzl/oracle-xe:21-slim";
    private static final String DEFAULT_USERNAME = "testuser";
    private static final String DEFAULT_PASSWORD = "testpass";
    private static final String DEFAULT_DATABASE_NAME = "XEPDB1";
    
    private static OracleContainer container;
    private static boolean isStarted = false;
    private static boolean shutdownHookRegistered = false;
    private static ReentrantLock initLock = new ReentrantLock();
    
    /**
     * Gets or creates the shared Oracle XA test container instance.
     * The container is automatically started on first access.
     * Thread-safe: ensures only one container start operation even with parallel test execution.
     * 
     * @return the shared OracleContainer instance
     */
    public static OracleContainer getInstance() {
        // Fast-path: if container already started, return it without locking
        if (isStarted && container != null) {
            return container;
        }
        
        // Slow-path: need to create/start container (with lock to ensure single initialization)
        initLock.lock();
        try {
            // Double-check: another thread may have initialized while we waited for lock
            if (isStarted && container != null) {
                return container;
            }
            
            if (container == null) {
                container = new OracleContainer(
                    DockerImageName.parse(ORACLE_IMAGE)
                        .asCompatibleSubstituteFor("gvenzl/oracle-xe")
                )
                .withUsername(DEFAULT_USERNAME)
                .withPassword(DEFAULT_PASSWORD)
                .withInitScript("xa-baseline/sql/oracle-xa-setup.sql")
                .withStartupTimeoutSeconds(120);
            }
            
            if (!isStarted) {
                container.start();
                isStarted = true; // Set AFTER start() completes to prevent race
                
                // Add shutdown hook to stop container when JVM exits
                if (!shutdownHookRegistered) {
                    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                        if (container != null && container.isRunning()) {
                            container.stop();
                        }
                    }));
                    shutdownHookRegistered = true;
                }
            }
            
            return container;
        } finally {
            initLock.unlock();
        }
    }
    
    /**
     * Gets the JDBC URL for connecting to the test container.
     * 
     * @return JDBC URL string
     */
    public static String getJdbcUrl() {
        OracleContainer instance = getInstance();
        return "jdbc:oracle:thin:@//" + instance.getHost() + ":" + 
               instance.getOraclePort() + "/" + DEFAULT_DATABASE_NAME;
    }
    
    /**
     * Gets the username for connecting to the test container.
     * 
     * @return username string
     */
    public static String getUsername() {
        return DEFAULT_USERNAME;
    }
    
    /**
     * Gets the password for connecting to the test container.
     * 
     * @return password string
     */
    public static String getPassword() {
        return DEFAULT_PASSWORD;
    }
    
    /**
     * Gets the database name (service name).
     * 
     * @return database name
     */
    public static String getDatabaseName() {
        return DEFAULT_DATABASE_NAME;
    }
    
    /**
     * Checks if Oracle XA tests are enabled via system property.
     * 
     * @return true if Oracle XA tests should run
     */
    public static boolean isEnabled() {
        // Reuse existing enableOracleTests property for consistency
        return Boolean.parseBoolean(System.getProperty("enableOracleTests", "false"));
    }
}
