package openjproxy.jdbc;

import lombok.extern.slf4j.Slf4j;
import openjproxy.jdbc.testutil.TestDBUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.openjproxy.jdbc.xa.OjpXADataSource;

import javax.sql.XAConnection;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Integration tests for XA transaction support with PostgreSQL.
 * These tests require:
 * 1. A running OJP server (localhost:1059)
 * 2. A PostgreSQL database with XA support
 * 3. The client-side code updated to use integrated StatementService
 */
@Slf4j
public class PostgresXAIntegrationTest {

    private static boolean isTestDisabled;
    private XAConnection xaConnection;
    private Connection connection;

    @BeforeAll
    public static void checkTestConfiguration() {
        // Enable by default for testing
        isTestDisabled = Boolean.parseBoolean(System.getProperty("disablePostgresXATests", "false"));
    }

    public void setUp(String driverClass, String url, String user, String password) throws SQLException {
        assumeFalse(isTestDisabled, "Postgres XA tests are disabled. Enable with -DdisablePostgresXATests=false");
        
        // Create XA DataSource
        OjpXADataSource xaDataSource = new OjpXADataSource();
        xaDataSource.setUrl(url);
        xaDataSource.setUser(user);
        xaDataSource.setPassword(password);
        
        // Get XA Connection
        xaConnection = xaDataSource.getXAConnection(user, password);
        connection = xaConnection.getConnection();
    }

    @AfterEach
    public void tearDown() {
        TestDBUtils.closeQuietly(connection);
        if (xaConnection != null) {
            try {
                xaConnection.close();
            } catch (Exception e) {
                log.warn("Error closing XA connection: {}", e.getMessage());
            }
        }
    }

    /**
     * Test basic XA connection creation and closure.
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_xa_connection.csv")
    public void testXAConnectionBasics(String driverClass, String url, String user, String password) throws Exception {
        setUp(driverClass, url, user, password);
        
        assertNotNull(xaConnection, "XA connection should be created");
        assertNotNull(connection, "Logical connection should be created");
        assertFalse(connection.isClosed(), "Connection should not be closed");
        
        // Get XA Resource
        XAResource xaResource = xaConnection.getXAResource();
        assertNotNull(xaResource, "XA resource should not be null");
        
        // Verify connection is not auto-commit (XA connections should never be auto-commit)
        assertFalse(connection.getAutoCommit(), "XA connection should not be auto-commit");
    }

    /**
     * Test XA transaction with simple CRUD operations.
     * This tests: xaStart -> executeUpdate -> xaEnd -> xaPrepare -> xaCommit
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_xa_connection.csv")
    public void testXATransactionWithCRUD(String driverClass, String url, String user, String password) throws Exception {
        setUp(driverClass, url, user, password);
        
        XAResource xaResource = xaConnection.getXAResource();
        
        // Create test table
        String tableName = "xa_test_table_" + System.currentTimeMillis();
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("CREATE TABLE " + tableName + " (id INT PRIMARY KEY, name VARCHAR(100))");
        }
        
        try {
            // Create Xid for transaction
            Xid xid = new TestXid(1, "global-tx-1".getBytes(), "branch-1".getBytes());
            
            // Start XA transaction
            xaResource.start(xid, XAResource.TMNOFLAGS);
            log.info("XA transaction started with XID: {}", xid);
            
            // Insert data within XA transaction
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO " + tableName + " (id, name) VALUES (?, ?)")) {
                ps.setInt(1, 1);
                ps.setString(2, "Test Name");
                int rows = ps.executeUpdate();
                assertEquals(1, rows, "Should insert 1 row");
            }
            
            // End XA transaction
            xaResource.end(xid, XAResource.TMSUCCESS);
            log.info("XA transaction ended");
            
            // Prepare transaction (Phase 1 of 2PC)
            int prepareResult = xaResource.prepare(xid);
            assertTrue(prepareResult == XAResource.XA_OK || prepareResult == XAResource.XA_RDONLY,
                    "Prepare should return XA_OK or XA_RDONLY");
            log.info("XA transaction prepared, result: {}", prepareResult);
            
            // Commit transaction (Phase 2 of 2PC)
            if (prepareResult == XAResource.XA_OK) {
                xaResource.commit(xid, false);
                log.info("XA transaction committed (two-phase)");
            } else {
                log.info("XA transaction committed (read-only optimization)");
            }
            
            // Verify data was committed by starting a new transaction and reading
            Xid xid2 = new TestXid(2, "global-tx-2".getBytes(), "branch-2".getBytes());
            xaResource.start(xid2, XAResource.TMNOFLAGS);
            
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT name FROM " + tableName + " WHERE id = ?")) {
                ps.setInt(1, 1);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "Should find inserted row");
                    assertEquals("Test Name", rs.getString("name"), "Name should match");
                }
            }
            
            xaResource.end(xid2, XAResource.TMSUCCESS);
            xaResource.commit(xid2, true); // One-phase commit for read-only
            
        } finally {
            // Cleanup: drop test table
            try (Statement stmt = connection.createStatement()) {
                stmt.executeUpdate("DROP TABLE " + tableName);
            } catch (Exception e) {
                log.warn("Error dropping test table: {}", e.getMessage());
            }
        }
    }

    /**
     * Test XA transaction rollback.
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_xa_connection.csv")
    public void testXATransactionRollback(String driverClass, String url, String user, String password) throws Exception {
        setUp(driverClass, url, user, password);
        
        XAResource xaResource = xaConnection.getXAResource();
        
        // Create test table
        String tableName = "xa_rollback_test_" + System.currentTimeMillis();
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("CREATE TABLE " + tableName + " (id INT PRIMARY KEY, name VARCHAR(100))");
        }
        
        try {
            // Start XA transaction
            Xid xid = new TestXid(3, "global-tx-3".getBytes(), "branch-3".getBytes());
            xaResource.start(xid, XAResource.TMNOFLAGS);
            
            // Insert data
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO " + tableName + " (id, name) VALUES (?, ?)")) {
                ps.setInt(1, 1);
                ps.setString(2, "Should be rolled back");
                ps.executeUpdate();
            }
            
            // End transaction
            xaResource.end(xid, XAResource.TMSUCCESS);
            
            // Rollback instead of commit
            xaResource.rollback(xid);
            log.info("XA transaction rolled back");
            
            // Verify data was NOT committed
            Xid xid2 = new TestXid(4, "global-tx-4".getBytes(), "branch-4".getBytes());
            xaResource.start(xid2, XAResource.TMNOFLAGS);
            
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT COUNT(*) FROM " + tableName + " WHERE id = ?")) {
                ps.setInt(1, 1);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(0, rs.getInt(1), "Should not find rolled-back row");
                }
            }
            
            xaResource.end(xid2, XAResource.TMSUCCESS);
            xaResource.commit(xid2, true);
            
        } finally {
            // Cleanup
            try (Statement stmt = connection.createStatement()) {
                stmt.executeUpdate("DROP TABLE " + tableName);
            } catch (Exception e) {
                log.warn("Error dropping test table: {}", e.getMessage());
            }
        }
    }

    /**
     * Test transaction timeout functionality.
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_xa_connection.csv")
    public void testXATransactionTimeout(String driverClass, String url, String user, String password) throws Exception {
        setUp(driverClass, url, user, password);
        
        XAResource xaResource = xaConnection.getXAResource();
        
        // Set transaction timeout
        boolean timeoutSet = xaResource.setTransactionTimeout(300); // 5 minutes
        assertTrue(timeoutSet, "Setting transaction timeout should succeed");
        
        // Get transaction timeout
        int timeout = xaResource.getTransactionTimeout();
        assertEquals(300, timeout, "Transaction timeout should be 300 seconds");
        
        // Reset to 0
        xaResource.setTransactionTimeout(0);
        assertEquals(0, xaResource.getTransactionTimeout(), "Transaction timeout should be reset to 0");
    }

    /**
     * Test one-phase commit optimization.
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_xa_connection.csv")
    public void testXAOnePhaseCommit(String driverClass, String url, String user, String password) throws Exception {
        setUp(driverClass, url, user, password);
        
        XAResource xaResource = xaConnection.getXAResource();
        
        // Create test table
        String tableName = "xa_one_phase_test_" + System.currentTimeMillis();
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("CREATE TABLE " + tableName + " (id INT PRIMARY KEY, value INT)");
        }
        
        try {
            Xid xid = new TestXid(5, "global-tx-5".getBytes(), "branch-5".getBytes());
            xaResource.start(xid, XAResource.TMNOFLAGS);
            
            // Insert data
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO " + tableName + " (id, value) VALUES (?, ?)")) {
                ps.setInt(1, 1);
                ps.setInt(2, 100);
                ps.executeUpdate();
            }
            
            xaResource.end(xid, XAResource.TMSUCCESS);
            
            // One-phase commit (skip prepare phase)
            xaResource.commit(xid, true);
            log.info("XA one-phase commit completed");
            
            // Verify commit worked
            Xid xid2 = new TestXid(6, "global-tx-6".getBytes(), "branch-6".getBytes());
            xaResource.start(xid2, XAResource.TMNOFLAGS);
            
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT value FROM " + tableName + " WHERE id = ?")) {
                ps.setInt(1, 1);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(100, rs.getInt("value"));
                }
            }
            
            xaResource.end(xid2, XAResource.TMSUCCESS);
            xaResource.commit(xid2, true);
            
        } finally {
            try (Statement stmt = connection.createStatement()) {
                stmt.executeUpdate("DROP TABLE " + tableName);
            } catch (Exception e) {
                log.warn("Error dropping test table: {}", e.getMessage());
            }
        }
    }

    /**
     * Simple Xid implementation for testing.
     */
    private static class TestXid implements Xid {
        private final int formatId;
        private final byte[] globalTransactionId;
        private final byte[] branchQualifier;

        public TestXid(int formatId, byte[] globalTransactionId, byte[] branchQualifier) {
            this.formatId = formatId;
            this.globalTransactionId = globalTransactionId;
            this.branchQualifier = branchQualifier;
        }

        @Override
        public int getFormatId() {
            return formatId;
        }

        @Override
        public byte[] getGlobalTransactionId() {
            return globalTransactionId;
        }

        @Override
        public byte[] getBranchQualifier() {
            return branchQualifier;
        }
    }
}
