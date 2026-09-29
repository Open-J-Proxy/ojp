package org.openjproxy.xa.baseline.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.XAConnection;
import javax.sql.XADataSource;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Base class for XA transaction tests providing common setup, teardown, and utility methods.
 * 
 * This abstract class provides:
 * - Lifecycle management for XA connections
 * - Helper methods for common XA operations
 * - Cleanup utilities
 * - Test table creation and deletion
 * - Logging infrastructure
 * 
 * Subclasses should implement {@link #createXADataSource()} to provide
 * database-specific XA DataSource creation logic.
 */
public abstract class XATestBase {
    
    protected final Logger logger = LoggerFactory.getLogger(getClass());
    
    protected XADataSource xaDataSource;
    protected XAConnection xaConnection;
    protected XAResource xaResource;
    protected Connection connection;
    
    // Track resources for cleanup
    private final List<XAConnection> xaConnections = new ArrayList<>();
    private final List<Connection> connections = new ArrayList<>();
    private final List<String> testTables = new ArrayList<>();
    
    /**
     * Creates and configures the XA DataSource for testing.
     * Subclasses must implement this to provide database-specific configuration.
     * 
     * @return configured XA DataSource
     * @throws SQLException if DataSource creation fails
     */
    protected abstract XADataSource createXADataSource() throws SQLException;
    
    /**
     * Gets the database type name for logging and identification.
     * 
     * @return database type (e.g., "Oracle", "SQL Server", "DB2")
     */
    protected abstract String getDatabaseType();
    
    /**
     * Sets up test fixture before each test.
     * Creates XA DataSource and establishes initial connection.
     */
    @BeforeEach
    public void setUp() throws Exception {
        logger.info("Setting up {} XA test", getDatabaseType());
        
        // Create XA DataSource
        xaDataSource = createXADataSource();
        
        // Get initial XA connection
        xaConnection = xaDataSource.getXAConnection();
        xaConnections.add(xaConnection);
        
        xaResource = xaConnection.getXAResource();
        connection = xaConnection.getConnection();
        connections.add(connection);
        
        // Verify auto-commit is disabled (required for XA)
        if (connection.getAutoCommit()) {
            logger.warn("Auto-commit is enabled on XA connection, disabling it");
            connection.setAutoCommit(false);
        }
        
        logger.info("Setup complete for {}", getDatabaseType());
    }
    
    /**
     * Tears down test fixture after each test.
     * Closes all connections and cleans up test data.
     */
    @AfterEach
    public void tearDown() {
        logger.info("Tearing down {} XA test", getDatabaseType());
        
        // Clean up test tables
        cleanupTestTables();
        
        // Close all connections
        closeAllConnections();
        
        // Close all XA connections
        closeAllXAConnections();
        
        logger.info("Teardown complete for {}", getDatabaseType());
    }
    
    /**
     * Creates a test table with a unique name.
     * 
     * @param conn the connection to use
     * @return the table name
     * @throws SQLException if table creation fails
     */
    protected String createTestTable(Connection conn) throws SQLException {
        String tableName = "xa_test_" + System.currentTimeMillis() + "_" + 
                          (int)(Math.random() * 1000);
        String createSql = getCreateTableSQL(tableName);
        
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(createSql);
            testTables.add(tableName);
            logger.debug("Created test table: {}", tableName);
        }
        
        return tableName;
    }
    
    /**
     * Gets the SQL for creating a test table.
     * Subclasses can override for database-specific syntax.
     * 
     * @param tableName the table name
     * @return CREATE TABLE SQL statement
     */
    protected String getCreateTableSQL(String tableName) {
        return String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, name VARCHAR(100), value INT)",
            tableName
        );
    }
    
    /**
     * Drops a test table.
     * 
     * @param conn the connection to use
     * @param tableName the table to drop
     */
    protected void dropTestTable(Connection conn, String tableName) {
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("DROP TABLE " + tableName);
            testTables.remove(tableName);
            logger.debug("Dropped test table: {}", tableName);
        } catch (SQLException e) {
            logger.warn("Failed to drop test table {}: {}", tableName, e.getMessage());
        }
    }
    
    /**
     * Cleans up all test tables created during the test.
     */
    private void cleanupTestTables() {
        if (testTables.isEmpty()) {
            return;
        }
        
        // Try to use existing connection, or create a new one
        Connection cleanupConn = connection;
        boolean shouldCloseConn = false;
        
        if (cleanupConn == null || isConnectionClosed(cleanupConn)) {
            try {
                XAConnection tmpXaConn = xaDataSource.getXAConnection();
                cleanupConn = tmpXaConn.getConnection();
                shouldCloseConn = true;
            } catch (SQLException e) {
                logger.error("Cannot create connection for cleanup", e);
                return;
            }
        }
        
        for (String tableName : new ArrayList<>(testTables)) {
            dropTestTable(cleanupConn, tableName);
        }
        
        if (shouldCloseConn) {
            closeQuietly(cleanupConn);
        }
    }
    
    /**
     * Closes all regular connections.
     */
    private void closeAllConnections() {
        for (Connection conn : connections) {
            closeQuietly(conn);
        }
        connections.clear();
    }
    
    /**
     * Closes all XA connections.
     */
    private void closeAllXAConnections() {
        for (XAConnection xaConn : xaConnections) {
            try {
                if (xaConn != null) {
                    xaConn.close();
                }
            } catch (SQLException e) {
                logger.warn("Error closing XA connection: {}", e.getMessage());
            }
        }
        xaConnections.clear();
    }
    
    /**
     * Closes a connection quietly without throwing exceptions.
     * 
     * @param conn the connection to close
     */
    protected void closeQuietly(Connection conn) {
        if (conn != null) {
            try {
                conn.close();
            } catch (SQLException e) {
                logger.debug("Error closing connection: {}", e.getMessage());
            }
        }
    }
    
    /**
     * Checks if a connection is closed.
     * 
     * @param conn the connection to check
     * @return true if closed, false otherwise
     */
    private boolean isConnectionClosed(Connection conn) {
        try {
            return conn == null || conn.isClosed();
        } catch (SQLException e) {
            return true;
        }
    }
    
    /**
     * Creates a unique XID for testing.
     * 
     * @return a new Xid
     */
    protected Xid createXid() {
        return XidGenerator.createXid();
    }
    
    /**
     * Creates a unique XID with custom prefix for identification.
     * 
     * @param prefix the prefix for the XID
     * @return a new Xid
     */
    protected Xid createXid(String prefix) {
        return XidGenerator.createXid(1, prefix);
    }
    
    /**
     * Creates an additional XA connection for multi-resource testing.
     * The connection is tracked and will be cleaned up automatically.
     * 
     * @return a new XA connection
     * @throws SQLException if connection creation fails
     */
    protected XAConnection createAdditionalXAConnection() throws SQLException {
        XAConnection additionalXaConn = xaDataSource.getXAConnection();
        xaConnections.add(additionalXaConn);
        Connection additionalConn = additionalXaConn.getConnection();
        connections.add(additionalConn);
        return additionalXaConn;
    }
    
    /**
     * Waits for a short period (for timing-sensitive tests).
     * 
     * @param milliseconds the time to wait
     */
    protected void waitFor(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Wait interrupted", e);
        }
    }
    
    /**
     * Inserts test data into the xa_test_baseline table.
     * 
     * @param conn the connection to use
     * @param testName the test name
     * @param testValue the test value
     * @throws SQLException if insert fails
     */
    protected void insertTestData(Connection conn, String testName, int testValue) throws SQLException {
        String sql = "INSERT INTO xa_test_baseline (id, test_name, test_value, test_timestamp) " +
                    "VALUES (xa_test_seq.NEXTVAL, ?, ?, SYSTIMESTAMP)";
        try (var pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, testName);
            pstmt.setInt(2, testValue);
            pstmt.executeUpdate();
        }
    }
    
    /**
     * Verifies that data exists in the xa_test_baseline table.
     * 
     * @param conn the connection to use
     * @param testName the test name to look for
     * @return true if data exists
     * @throws SQLException if query fails
     */
    protected boolean verifyDataExists(Connection conn, String testName) throws SQLException {
        String sql = "SELECT COUNT(*) FROM xa_test_baseline WHERE test_name = ?";
        try (var pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, testName);
            try (var rs = pstmt.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }
    
    /**
     * Verifies that data does NOT exist in the xa_test_baseline table.
     * 
     * @param conn the connection to use
     * @param testName the test name to look for
     * @return true if data does not exist
     * @throws SQLException if query fails
     */
    protected boolean verifyDataNotExists(Connection conn, String testName) throws SQLException {
        return !verifyDataExists(conn, testName);
    }
}
