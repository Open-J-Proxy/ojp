package org.openjproxy.jdbc.testutil;

import org.testcontainers.containers.MSSQLServerContainer;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Singleton SQL Server test container for all SQL Server integration tests.
 * This ensures that all tests share the same SQL Server instance to improve test performance
 * and reduce resource usage.
 */
public class SQLServerTestContainer {
    
    // SQL Server Docker image version
    private static final String MSSQL_IMAGE = "mcr.microsoft.com/mssql/server:2022-latest";
    
    private static MSSQLServerContainer<?> container;
    private static boolean isStarted = false;
    private static boolean shutdownHookRegistered = false;

    private static final ReentrantLock initLock = new ReentrantLock();
    /**
     * Gets or creates the shared SQL Server test container instance.
     * The container is automatically started on first access.
     * 
     * @return the shared MSSQLServerContainer instance
     */
    public static  MSSQLServerContainer<?> getInstance() {
        // Fast-path: if container already created and running, return it without locking
        MSSQLServerContainer<?> local = container;
        if (local != null && local.isRunning()) {
            return local;
        }

        initLock.lock();
        try {
            if (container == null) {
                container = new MSSQLServerContainer<>(MSSQL_IMAGE).acceptLicense();
            }

            if (!isStarted) {
                container.start();
                isStarted = true;

            // Post-start initialization for features needed by tests
            try {
                // Create default database and test user matching the CSV entries
                createDefaultDbAndUser();
                installXaStoredProcedures();
                grantXaPermissionsToTestUser();
            } catch (Exception e) {
                // Do not fail tests on init best-effort; XA tests will fail with clearer message if needed
                System.err.println("[SQLServerTestContainer] Warning: Failed to install XA stored procedures: " + e.getMessage());
            }

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
        }finally {
            initLock.unlock();
        }
    }

    /**
     * Installs Microsoft SQL Server XA stored procedures required for XA transactions.
     * Uses the SA credentials provided by the TestContainers image.
     */
    private static void installXaStoredProcedures() throws Exception {
        // Path to sqlcmd inside the container (tools18 has TLS defaults compatible with -C switch)
        final String sqlcmd = "/opt/mssql-tools18/bin/sqlcmd";
        final String saUser = getInstance().getUsername(); // typically "sa"
        final String saPassword = getInstance().getPassword();

        // Wait a little for SQL service readiness just in case
        // (container.start() should already wait, but this is inexpensive)
        try { Thread.sleep(1000); } catch (InterruptedException ignored) {}

        // Install XA stored procedures in master
        String[] cmd = new String[] {
                sqlcmd,
                "-S", "localhost",
                "-U", saUser,
                "-P", saPassword,
                "-d", "master",
                "-C",
                "-Q", "EXEC sp_sqljdbc_xa_install;"
        };

        org.testcontainers.containers.Container.ExecResult res = getInstance().execInContainer(cmd);
        if (res.getExitCode() != 0) {
            throw new IllegalStateException("sp_sqljdbc_xa_install failed: " + res.getStderr());
        }
    }

    /**
     * Creates defaultdb database and testuser with db_owner in defaultdb to align with existing tests.
     */
    private static void createDefaultDbAndUser() throws Exception {
        final String sqlcmd = "/opt/mssql-tools18/bin/sqlcmd";
        final String saUser = getInstance().getUsername();
        final String saPassword = getInstance().getPassword();

        // Create defaultdb
        String[] createDb = new String[] { sqlcmd, "-S", "localhost", "-U", saUser, "-P", saPassword, "-C", "-Q",
                "IF DB_ID('defaultdb') IS NULL CREATE DATABASE defaultdb;" };
        getInstance().execInContainer(createDb);

        // Create login testuser
        String[] createLogin = new String[] { sqlcmd, "-S", "localhost", "-U", saUser, "-P", saPassword, "-C", "-Q",
                "IF NOT EXISTS (SELECT * FROM sys.sql_logins WHERE name = 'testuser') CREATE LOGIN testuser WITH PASSWORD = 'TestPassword123!';" };
        getInstance().execInContainer(createLogin);

        // Create user in defaultdb and grant db_owner
        String[] createUser = new String[] { sqlcmd, "-S", "localhost", "-U", saUser, "-P", saPassword, "-d", "defaultdb", "-C", "-Q",
                "IF NOT EXISTS (SELECT * FROM sys.database_principals WHERE name = 'testuser') BEGIN CREATE USER testuser FOR LOGIN testuser; ALTER ROLE db_owner ADD MEMBER testuser; END" };
        getInstance().execInContainer(createUser);
    }

    /**
     * Grants execute permissions on XA extended stored procedures to testuser in master DB and adds to SqlJDBCXAUser role.
     */
    private static void grantXaPermissionsToTestUser() throws Exception {
        final String sqlcmd = "/opt/mssql-tools18/bin/sqlcmd";
        final String saUser = getInstance().getUsername();
        final String saPassword = getInstance().getPassword();

        String grantScript = String.join("\n",
                "IF NOT EXISTS (SELECT * FROM sys.database_principals WHERE name = 'testuser') BEGIN",
                "  CREATE USER testuser FOR LOGIN testuser;",
                "END",
                "GRANT EXECUTE ON xp_sqljdbc_xa_init TO testuser;",
                "GRANT EXECUTE ON xp_sqljdbc_xa_start TO testuser;",
                "GRANT EXECUTE ON xp_sqljdbc_xa_end TO testuser;",
                "GRANT EXECUTE ON xp_sqljdbc_xa_prepare TO testuser;",
                "GRANT EXECUTE ON xp_sqljdbc_xa_commit TO testuser;",
                "GRANT EXECUTE ON xp_sqljdbc_xa_rollback TO testuser;",
                "GRANT EXECUTE ON xp_sqljdbc_xa_recover TO testuser;",
                "GRANT EXECUTE ON xp_sqljdbc_xa_forget TO testuser;",
                "IF NOT EXISTS (SELECT * FROM sys.database_principals WHERE name = 'SqlJDBCXAUser' AND type = 'R') BEGIN",
                "  CREATE ROLE [SqlJDBCXAUser];",
                "END",
                "ALTER ROLE [SqlJDBCXAUser] ADD MEMBER testuser;"
        );

        String[] grantCmd = new String[] { sqlcmd, "-S", "localhost", "-U", saUser, "-P", saPassword, "-d", "master", "-C", "-Q", grantScript };
        getInstance().execInContainer(grantCmd);
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
        return getInstance().getUsername();
    }
    
    /**
     * Gets the password for connecting to the test container.
     * 
     * @return password string
     */
    public static String getPassword() {
        return getInstance().getPassword();
    }
    
    /**
     * Checks if SQL Server tests are enabled via system property.
     * 
     * @return true if SQL Server tests should run
     */
    public static boolean isEnabled() {
        return Boolean.parseBoolean(System.getProperty("enableSqlServerTests", "false"));
    }
}
