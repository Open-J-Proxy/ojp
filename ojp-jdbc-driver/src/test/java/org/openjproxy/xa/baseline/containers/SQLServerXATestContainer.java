package org.openjproxy.xa.baseline.containers;

import org.testcontainers.containers.MSSQLServerContainer;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Singleton SQL Server XA test container for all SQL Server XA integration tests.
 * This ensures that all tests share the same SQL Server instance to improve test performance
 * and reduce resource usage.
 */
public class SQLServerXATestContainer {
    
    // SQL Server Docker image version
    private static final String MSSQL_IMAGE = "mcr.microsoft.com/mssql/server:2022-latest";
    private static final String TEST_USERNAME = "testuser";
    private static final String TEST_PASSWORD = "TestPassword123!";
    private static final String TEST_DATABASE = "xatestdb";
    
    private static MSSQLServerContainer<?> container;
    private static boolean isStarted = false;
    private static boolean shutdownHookRegistered = false;
    private static ReentrantLock initLock = new ReentrantLock();
    
    /**
     * Gets or creates the shared SQL Server XA test container instance.
     * The container is automatically started on first access.
     * Thread-safe: ensures only one container start operation even with parallel test execution.
     * 
     * @return the shared MSSQLServerContainer instance
     */
    public static MSSQLServerContainer<?> getInstance() {
        // Fast-path: if container already started, return it without locking
        if (isStarted && container != null) {
            return container;
        }
        
        // Slow-path: need to create/start container (with lock to ensure single initialization)
        initLock.lock();
        try {
            // Double-check after acquiring lock
            if (isStarted && container != null) {
                return container;
            }
            
            if (container == null) {
                container = new MSSQLServerContainer<>(MSSQL_IMAGE)
                    .acceptLicense();
                    // Note: Do NOT use .withInitScript() - GO statements don't work via JDBC
                    // All initialization is done programmatically via sqlcmd after container starts
            }
            
            if (!isStarted) {
                System.out.println("[SQLServerXATestContainer] Starting SQL Server container...");
                container.start();
                System.out.println("[SQLServerXATestContainer] Container started, beginning XA initialization...");
                
                // Post-start initialization for XA features
                try {
                    System.out.println("[SQLServerXATestContainer] Installing XA stored procedures...");
                    installXaStoredProcedures();
                    System.out.println("[SQLServerXATestContainer] Creating test database...");
                    createTestDatabase();
                    System.out.println("[SQLServerXATestContainer] Creating test table and sequence...");
                    createTestTableAndSequence();
                    System.out.println("[SQLServerXATestContainer] Creating test user...");
                    createTestUser();
                    System.out.println("[SQLServerXATestContainer] Granting XA permissions...");
                    grantXaPermissions();
                    System.out.println("[SQLServerXATestContainer] XA initialization complete!");
                } catch (Exception e) {
                    System.err.println("[SQLServerXATestContainer] ERROR: Failed to initialize XA: " + e.getMessage());
                    e.printStackTrace();
                    throw new RuntimeException("SQL Server XA initialization failed", e);
                }
                
                isStarted = true; // Set AFTER start() and initialization complete to prevent race
                System.out.println("[SQLServerXATestContainer] Container fully initialized and ready for tests");
                
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
     * Installs Microsoft SQL Server XA stored procedures.
     */
    private static void installXaStoredProcedures() throws Exception {
        final String sqlcmd = "/opt/mssql-tools18/bin/sqlcmd";
        final String saUser = container.getUsername();
        final String saPassword = container.getPassword();
        
        String[] cmd = new String[] {
            sqlcmd, "-S", "localhost", "-U", saUser, "-P", saPassword,
            "-d", "master", "-C", "-Q", "EXEC sp_sqljdbc_xa_install;"
        };
        
        org.testcontainers.containers.Container.ExecResult res = container.execInContainer(cmd);
        if (res.getExitCode() != 0) {
            throw new IllegalStateException("sp_sqljdbc_xa_install failed: " + res.getStderr());
        }
    }
    
    /**
     * Creates the test database.
     */
    private static void createTestDatabase() throws Exception {
        final String sqlcmd = "/opt/mssql-tools18/bin/sqlcmd";
        final String saUser = container.getUsername();
        final String saPassword = container.getPassword();
        
        String[] cmd = new String[] {
            sqlcmd, "-S", "localhost", "-U", saUser, "-P", saPassword, "-C", "-Q",
            "IF DB_ID('" + TEST_DATABASE + "') IS NULL CREATE DATABASE " + TEST_DATABASE + ";"
        };
        container.execInContainer(cmd);
    }
    
    /**
     * Creates the test table and sequence in the test database.
     */
    private static void createTestTableAndSequence() throws Exception {
        final String sqlcmd = "/opt/mssql-tools18/bin/sqlcmd";
        final String saUser = container.getUsername();
        final String saPassword = container.getPassword();
        
        // Create test table
        String createTableScript = String.join(" ",
            "IF NOT EXISTS (SELECT * FROM sys.tables WHERE name = 'xa_test_baseline')",
            "BEGIN",
            "CREATE TABLE xa_test_baseline (",
            "id INT PRIMARY KEY IDENTITY(1,1),",
            "test_name NVARCHAR(100) NOT NULL,",
            "test_value NVARCHAR(255),",
            "test_timestamp DATETIME2 DEFAULT GETDATE()",
            ");",
            "CREATE INDEX idx_xa_test_name ON xa_test_baseline(test_name);",
            "END"
        );
        
        String[] createTableCmd = new String[] {
            sqlcmd, "-S", "localhost", "-U", saUser, "-P", saPassword,
            "-d", TEST_DATABASE, "-C", "-Q", createTableScript
        };
        container.execInContainer(createTableCmd);
        
        // Create sequence for ID generation
        String createSeqScript = String.join(" ",
            "IF NOT EXISTS (SELECT * FROM sys.sequences WHERE name = 'xa_test_seq')",
            "BEGIN",
            "CREATE SEQUENCE xa_test_seq START WITH 1 INCREMENT BY 1 MINVALUE 1 MAXVALUE 9999999999 NO CYCLE CACHE 10;",
            "END"
        );
        
        String[] createSeqCmd = new String[] {
            sqlcmd, "-S", "localhost", "-U", saUser, "-P", saPassword,
            "-d", TEST_DATABASE, "-C", "-Q", createSeqScript
        };
        container.execInContainer(createSeqCmd);
    }
    
    /**
     * Creates the test user.
     */
    private static void createTestUser() throws Exception {
        final String sqlcmd = "/opt/mssql-tools18/bin/sqlcmd";
        final String saUser = container.getUsername();
        final String saPassword = container.getPassword();
        
        // Create login
        String[] createLogin = new String[] {
            sqlcmd, "-S", "localhost", "-U", saUser, "-P", saPassword, "-C", "-Q",
            "IF NOT EXISTS (SELECT * FROM sys.sql_logins WHERE name = '" + TEST_USERNAME + "') " +
            "CREATE LOGIN " + TEST_USERNAME + " WITH PASSWORD = '" + TEST_PASSWORD + "';"
        };
        container.execInContainer(createLogin);
        
        // Create user in test database
        String[] createUser = new String[] {
            sqlcmd, "-S", "localhost", "-U", saUser, "-P", saPassword,
            "-d", TEST_DATABASE, "-C", "-Q",
            "IF NOT EXISTS (SELECT * FROM sys.database_principals WHERE name = '" + TEST_USERNAME + "') " +
            "BEGIN CREATE USER " + TEST_USERNAME + " FOR LOGIN " + TEST_USERNAME + "; " +
            "ALTER ROLE db_owner ADD MEMBER " + TEST_USERNAME + "; END"
        };
        container.execInContainer(createUser);
    }
    
    /**
     * Grants XA permissions to the test user.
     */
    private static void grantXaPermissions() throws Exception {
        final String sqlcmd = "/opt/mssql-tools18/bin/sqlcmd";
        final String saUser = container.getUsername();
        final String saPassword = container.getPassword();
        
        String grantScript = String.join("\n",
            "IF NOT EXISTS (SELECT * FROM sys.database_principals WHERE name = '" + TEST_USERNAME + "') BEGIN",
            "  CREATE USER " + TEST_USERNAME + " FOR LOGIN " + TEST_USERNAME + ";",
            "END",
            "GRANT EXECUTE ON xp_sqljdbc_xa_init TO " + TEST_USERNAME + ";",
            "GRANT EXECUTE ON xp_sqljdbc_xa_start TO " + TEST_USERNAME + ";",
            "GRANT EXECUTE ON xp_sqljdbc_xa_end TO " + TEST_USERNAME + ";",
            "GRANT EXECUTE ON xp_sqljdbc_xa_prepare TO " + TEST_USERNAME + ";",
            "GRANT EXECUTE ON xp_sqljdbc_xa_commit TO " + TEST_USERNAME + ";",
            "GRANT EXECUTE ON xp_sqljdbc_xa_rollback TO " + TEST_USERNAME + ";",
            "GRANT EXECUTE ON xp_sqljdbc_xa_recover TO " + TEST_USERNAME + ";",
            "GRANT EXECUTE ON xp_sqljdbc_xa_forget TO " + TEST_USERNAME + ";",
            "IF NOT EXISTS (SELECT * FROM sys.database_principals WHERE name = 'SqlJDBCXAUser' AND type = 'R') BEGIN",
            "  CREATE ROLE [SqlJDBCXAUser];",
            "END",
            "ALTER ROLE [SqlJDBCXAUser] ADD MEMBER " + TEST_USERNAME + ";"
        );
        
        String[] cmd = new String[] {
            sqlcmd, "-S", "localhost", "-U", saUser, "-P", saPassword,
            "-d", "master", "-C", "-Q", grantScript
        };
        container.execInContainer(cmd);
    }
    
    /**
     * Gets the JDBC URL for connecting to the test container and test database.
     * 
     * @return JDBC URL string
     */
    public static String getJdbcUrl() {
        return getInstance().getJdbcUrl() + ";databaseName=" + TEST_DATABASE;
    }
    
    /**
     * Gets the test database name.
     * 
     * @return database name string
     */
    public static String getTestDatabase() {
        return TEST_DATABASE;
    }
    
    /**
     * Gets the test username.
     * 
     * @return username string
     */
    public static String getTestUsername() {
        return TEST_USERNAME;
    }
    
    /**
     * Gets the test password.
     * 
     * @return password string
     */
    public static String getTestPassword() {
        return TEST_PASSWORD;
    }
    
    /**
     * Gets the SA username from the container.
     * 
     * @return SA username
     */
    public static String getUsername() {
        return getInstance().getUsername();
    }
    
    /**
     * Gets the SA password from the container.
     * 
     * @return SA password
     */
    public static String getPassword() {
        return getInstance().getPassword();
    }
    
    /**
     * Checks if SQL Server XA tests are enabled via system property.
     * 
     * @return true if SQL Server XA tests should run
     */
    public static boolean isEnabled() {
        // Reuse existing enableSqlServerTests property for consistency
        return Boolean.parseBoolean(System.getProperty("enableSqlServerTests", "false"));
    }
}
