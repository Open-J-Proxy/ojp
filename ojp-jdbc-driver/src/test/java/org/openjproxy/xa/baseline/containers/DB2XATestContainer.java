package org.openjproxy.xa.baseline.containers;

import org.testcontainers.containers.Db2Container;
import org.testcontainers.utility.DockerImageName;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Singleton DB2 XA test container for all DB2 XA integration tests.
 * This ensures that all tests share the same DB2 instance to improve test performance
 * and reduce resource usage.
 */
public class DB2XATestContainer {
    
    // DB2 Docker image version - using latest stable LTS version
    // Note: IBM DB2 Developer-C Edition (free for non-production use)
    private static final String DB2_IMAGE = "icr.io/db2_community/db2:11.5.9.0";
    private static final String DEFAULT_USERNAME = "db2inst1";
    private static final String DEFAULT_PASSWORD = "testpass123";
    private static final String DEFAULT_DATABASE = "testdb";
    
    private static Db2Container container;
    private static boolean isStarted = false;
    private static boolean shutdownHookRegistered = false;
    private static ReentrantLock initLock = new ReentrantLock();
    
    /**
     * Gets or creates the shared DB2 XA test container instance.
     * The container is automatically started on first access.
     * Thread-safe: ensures only one container start operation even with parallel test execution.
     * 
     * @return the shared Db2Container instance
     */
    public static Db2Container getInstance() {
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
                container = new Db2Container(
                    DockerImageName.parse(DB2_IMAGE)
                        .asCompatibleSubstituteFor("ibmcom/db2")
                )
                .withUsername(DEFAULT_USERNAME)
                .withPassword(DEFAULT_PASSWORD)
                .withDatabaseName(DEFAULT_DATABASE)
                .acceptLicense()
                // DB2 requires additional environment variables for proper startup
                .withEnv("DB2INSTANCE", DEFAULT_USERNAME)
                .withEnv("DBNAME", DEFAULT_DATABASE)
                .withEnv("BLU", "false")
                .withEnv("ENABLE_ORACLE_COMPATIBILITY", "false")
                .withEnv("UPDATEAVAIL", "NO")
                .withEnv("TO_CREATE_SAMPLEDB", "false")
                .withEnv("REPODB", "false")
                // DB2 requires shared memory for proper operation
                .withSharedMemorySize(256 * 1024 * 1024L) // 256MB
                // Note: No init script - DB2 permissions don't allow GRANT commands via init script
                // XA configuration is done via configureTmDatabase() below
                .withStartupTimeoutSeconds(300); // DB2 can be very slow to start (5 minutes)
            }
            
            if (!isStarted) {
                container.start();
                
                // Post-start initialization for XA features
                try {
                    configureTmDatabase();
                    createTestTable();
                } catch (Exception e) {
                    System.err.println("[DB2XATestContainer] Warning: Failed to initialize DB2 XA: " + e.getMessage());
                }
                
                isStarted = true; // Set AFTER start() and initialization complete to prevent race
                
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
     * Configures DB2 for XA transactions by setting TM_DATABASE.
     */
    private static void configureTmDatabase() throws Exception {
        // Update DB2 configuration for transaction manager database
        String[] cmd = new String[] {
            "su", "-", DEFAULT_USERNAME, "-c",
            "db2 UPDATE DBM CFG USING TM_DATABASE " + DEFAULT_DATABASE + " IMMEDIATE"
        };
        
        org.testcontainers.containers.Container.ExecResult res = container.execInContainer(cmd);
        if (res.getExitCode() != 0) {
            System.err.println("TM_DATABASE configuration warning: " + res.getStderr());
        }
    }
    
    /**
     * Creates the XA test table.
     */
    private static void createTestTable() throws Exception {
        // Construct direct DB2 JDBC URL (not OJP-wrapped)
        String db2JdbcUrl = String.format("jdbc:db2://%s:%d/%s",
            container.getHost(),
            container.getMappedPort(50000),
            DEFAULT_DATABASE
        );
        
        // Load DB2 driver explicitly
        Class.forName("com.ibm.db2.jcc.DB2Driver");
        
        // Use JDBC to create the test table
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection(
                db2JdbcUrl,
                DEFAULT_USERNAME,
                DEFAULT_PASSWORD);
             java.sql.Statement stmt = conn.createStatement()) {
            
            // Create test table
            stmt.execute(
                "CREATE TABLE xa_test_baseline (" +
                "    id INTEGER NOT NULL GENERATED ALWAYS AS IDENTITY (START WITH 1, INCREMENT BY 1)," +
                "    test_name VARCHAR(100) NOT NULL," +
                "    test_value VARCHAR(255)," +
                "    test_timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "    PRIMARY KEY (id)" +
                ")"
            );
            
            // Create index
            stmt.execute("CREATE INDEX idx_xa_test_name ON xa_test_baseline(test_name)");
        }
    }
    
    /**
     * Gets the JDBC URL for connecting to the test container.
     * 
     * @return JDBC URL string
     */
    public static String getJdbcUrl() {
        return getInstance().getJdbcUrl();
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
     * Gets the database name.
     * 
     * @return database name
     */
    public static String getDatabaseName() {
        return DEFAULT_DATABASE;
    }
    
    /**
     * Checks if DB2 XA tests are enabled via system property.
     * 
     * @return true if DB2 XA tests should run
     */
    public static boolean isEnabled() {
        // Use a dedicated property for DB2 tests
        return Boolean.parseBoolean(System.getProperty("enableDb2Tests", "false"));
    }
}
