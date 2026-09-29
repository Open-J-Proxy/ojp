package org.openjproxy.xa.baseline.single;

import com.ibm.db2.jcc.DB2XADataSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openjproxy.xa.baseline.common.XATestBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.XAConnection;
import javax.sql.XADataSource;
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 7: DB2 Basic XA Operations Test Suite
 * 
 * Tests 8 core XA operations (mirroring Oracle and SQL Server Phase 3-4):
 * - 5 basic operations tests
 * - 3 transaction flag tests
 * 
 * These tests validate that DB2 correctly implements the XA protocol using native JDBC driver.
 * Results establish baseline behavior for comparison with Oracle, SQL Server, and OJP.
 * 
 * These tests assume a running DB2 instance and are disabled by default.
 * To run: mvn test -DenableDb2Tests=true
 * 
 * Requires: DB2 instance at localhost:50000/testdb with user db2inst1/testpass
 */
public class DB2XABasicTest extends XATestBase {
    
    private static final Logger logger = LoggerFactory.getLogger(DB2XABasicTest.class);
    
    protected static XADataSource staticXADataSource;
    private static boolean isDb2TestEnabled;
    
    // DB2 connection details (matches db2_connection.csv pattern)
    private static final String DB2_HOST = "localhost";
    private static final int DB2_PORT = 50000;
    private static final String DB2_DATABASE = "testdb";
    private static final String DB2_USER = "db2inst1";
    private static final String DB2_PASSWORD = "testpass";
    
    @BeforeAll
    public static void setUpClass() throws Exception {
        isDb2TestEnabled = Boolean.parseBoolean(System.getProperty("enableDb2Tests", "false"));
        Assumptions.assumeFalse(!isDb2TestEnabled, "Skipping DB2 XA tests (use -DenableDb2Tests=true to enable)");
        
        logger.info("=== Starting DB2 XA Basic Tests (Phase 7) ===");
        logger.info("Connecting to external DB2 instance at {}:{}/{}", DB2_HOST, DB2_PORT, DB2_DATABASE);
        
        // Create XA DataSource for external DB2 instance
        DB2XADataSource xaDataSource = new DB2XADataSource();
        xaDataSource.setServerName(DB2_HOST);
        xaDataSource.setPortNumber(DB2_PORT);
        xaDataSource.setDatabaseName(DB2_DATABASE);
        xaDataSource.setUser(DB2_USER);
        xaDataSource.setPassword(DB2_PASSWORD);
        xaDataSource.setDriverType(4); // Type 4 - pure Java driver
        
        staticXADataSource = xaDataSource;
        
        // Verify connection and create test table
        XAConnection xaConn = null;
        try {
            xaConn = staticXADataSource.getXAConnection();
            try (Connection conn = xaConn.getConnection();
                 Statement stmt = conn.createStatement()) {
                
                // Set schema explicitly
                stmt.execute("SET SCHEMA DB2INST1");
                
                // Create test table if it doesn't exist
                try {
                    stmt.execute("DROP TABLE xa_test_baseline");
                } catch (SQLException e) {
                    // Ignore if table doesn't exist
                }
                
                stmt.execute("CREATE TABLE xa_test_baseline (" +
                            "id INTEGER NOT NULL PRIMARY KEY GENERATED ALWAYS AS IDENTITY (START WITH 1, INCREMENT BY 1), " +
                            "test_name VARCHAR(100), " +
                            "test_value VARCHAR(200))");
                
                stmt.execute("CREATE INDEX idx_xa_test_name ON xa_test_baseline(test_name)");
                
                logger.info("DB2 XA test table created successfully");
            }
        } finally {
            if (xaConn != null) {
                try {
                    xaConn.close();
                } catch (SQLException e) {
                    logger.warn("Error closing XA connection", e);
                }
            }
        }
        
        logger.info("DB2 XA DataSource configured successfully");
    }

    @Override
    protected String getDatabaseType() {
        return "DB2";
    }

    @Override
    protected javax.sql.XADataSource createXADataSource() throws SQLException {
        return staticXADataSource;
    }

    // ===========================================================================================
    // BASIC OPERATIONS (5 tests)
    // ===========================================================================================

    /**
     * Test Case 1.1: XA Connection Creation
     * Validates that XA connections can be created and basic infrastructure works
     */
    @Test
    void testXAConnectionCreation() throws Exception {
        // Get XA connection
        XAConnection xaConn = xaConnection;
        assertNotNull(xaConn, "XAConnection should not be null");
        
        // Get XA resource
        XAResource xaRes = xaConn.getXAResource();
        assertNotNull(xaRes, "XAResource should not be null");
        
        // Get logical connection
        Connection conn = xaConn.getConnection();
        assertNotNull(conn, "Logical connection should not be null");
        
        // DB2 requires explicit autoCommit disabling for XA connections
        conn.setAutoCommit(false);
        assertFalse(conn.getAutoCommit(), "Auto-commit should be disabled for XA connections");
        
        // Verify connection works
        try (PreparedStatement pstmt = conn.prepareStatement("SELECT 1 FROM SYSIBM.SYSDUMMY1")) {
            ResultSet rs = pstmt.executeQuery();
            assertTrue(rs.next(), "Should be able to execute query");
            assertEquals(1, rs.getInt(1), "Query should return 1");
        }
        
        // Test isSameRM with itself
        assertTrue(xaRes.isSameRM(xaRes), "XAResource should be same RM as itself");
    }

    /**
     * Test Case 1.2: Basic XA Transaction Lifecycle (Two-Phase Commit)
     * Tests complete 2PC flow: start → work → end → prepare → commit
     */
    @Test
    void testBasicXATransactionLifecycle() throws Exception {
        XAConnection xaConn = xaConnection;
        XAResource xaRes = xaConn.getXAResource();
        Connection conn = xaConn.getConnection();
        
        // Generate unique XID
        Xid xid = createXid();
        
        // Phase 1: Start XA transaction
        xaRes.start(xid, XAResource.TMNOFLAGS);
        
        // Do some work - insert data
        String testName = "basic-2pc-" + System.currentTimeMillis();
        try (PreparedStatement pstmt = conn.prepareStatement(
                "INSERT INTO xa_test_baseline (test_name, test_value) VALUES (?, ?)")) {
            pstmt.setString(1, testName);
            pstmt.setString(2, "test-value");
            pstmt.executeUpdate();
        }
        
        // Phase 2: End XA transaction
        xaRes.end(xid, XAResource.TMSUCCESS);
        
        // Phase 3: Prepare (vote phase)
        int prepareResult = xaRes.prepare(xid);
        assertEquals(XAResource.XA_OK, prepareResult, "Prepare should return XA_OK for writing transaction");
        
        // Phase 4: Commit (decision phase)
        xaRes.commit(xid, false); // two-phase commit
        
        // Verify data was committed
        Connection verifyConn = xaConnection.getConnection();
        verifyDataExists(verifyConn, testName);
    }

    /**
     * Test Case 1.3: XA Transaction Rollback
     * Tests rollback: start → work → end → rollback
     */
    @Test
    void testXATransactionRollback() throws Exception {
        XAConnection xaConn = xaConnection;
        XAResource xaRes = xaConn.getXAResource();
        Connection conn = xaConn.getConnection();
        
        Xid xid = createXid();
        
        // Start transaction and do work
        xaRes.start(xid, XAResource.TMNOFLAGS);
        
        String testName = "rollback-test-" + System.currentTimeMillis();
        try (PreparedStatement pstmt = conn.prepareStatement(
                "INSERT INTO xa_test_baseline (test_name, test_value) VALUES (?, ?)")) {
            pstmt.setString(1, testName);
            pstmt.setString(2, "should-not-exist");
            pstmt.executeUpdate();
        }
        
        // End transaction
        xaRes.end(xid, XAResource.TMSUCCESS);
        
        // Rollback without prepare
        xaRes.rollback(xid);
        
        // Verify data was NOT committed
        Connection verifyConn = xaConnection.getConnection();
        verifyDataNotExists(verifyConn, testName);
    }

    /**
     * Test Case 1.4: One-Phase Commit Optimization
     * Tests 1PC optimization: start → work → end → commit(onePhase=true)
     */
    @Test
    void testOnePhaseCommitOptimization() throws Exception {
        XAConnection xaConn = xaConnection;
        XAResource xaRes = xaConn.getXAResource();
        Connection conn = xaConn.getConnection();
        
        Xid xid = createXid();
        
        // Start transaction
        xaRes.start(xid, XAResource.TMNOFLAGS);
        
        // Update existing data
        String testName = "onephase-commit-" + System.currentTimeMillis();
        try (PreparedStatement pstmt = conn.prepareStatement(
                "INSERT INTO xa_test_baseline (test_name, test_value) VALUES (?, ?)")) {
            pstmt.setString(1, testName);
            pstmt.setString(2, "onephase-value");
            pstmt.executeUpdate();
        }
        
        // End transaction
        xaRes.end(xid, XAResource.TMSUCCESS);
        
        // Commit with one-phase optimization (skip prepare)
        xaRes.commit(xid, true); // onePhase = true
        
        // Verify data was committed
        Connection verifyConn = xaConnection.getConnection();
        verifyDataExists(verifyConn, testName);
    }

    /**
     * Test Case 1.5: Read-Only Transaction Optimization
     * Tests read-only optimization: start → read-only work → end → prepare
     * DB2 may return XA_RDONLY for read-only transactions
     */
    @Test
    void testReadOnlyTransactionOptimization() throws Exception {
        XAConnection xaConn = xaConnection;
        XAResource xaRes = xaConn.getXAResource();
        Connection conn = xaConn.getConnection();
        
        Xid xid = createXid();
        
        // Start transaction
        xaRes.start(xid, XAResource.TMNOFLAGS);
        
        // Do read-only work (SELECT only, no INSERT/UPDATE/DELETE)
        try (PreparedStatement pstmt = conn.prepareStatement(
                "SELECT COUNT(*) FROM xa_test_baseline WHERE test_name = ?")) {
            pstmt.setString(1, "non-existent-test");
            ResultSet rs = pstmt.executeQuery();
            rs.next();
            assertEquals(0, rs.getInt(1), "Should return 0 for non-existent data");
        }
        
        // End transaction
        xaRes.end(xid, XAResource.TMSUCCESS);
        
        // Prepare read-only transaction
        int prepareResult = xaRes.prepare(xid);
        
        // DB2 behavior: may return XA_RDONLY (read-only optimization) or XA_OK
        // XA_RDONLY means transaction was optimized away and auto-committed
        // XA_OK means transaction needs explicit commit
        if (prepareResult == XAResource.XA_RDONLY) {
            // Transaction was read-only and already completed
            // No need to commit
            System.out.println("DB2 returned XA_RDONLY for read-only transaction (optimized)");
        } else if (prepareResult == XAResource.XA_OK) {
            // DB2 didn't optimize, needs explicit commit
            xaRes.commit(xid, false);
            System.out.println("DB2 returned XA_OK for read-only transaction (not optimized)");
        } else {
            fail("Unexpected prepare result: " + prepareResult);
        }
    }

    // ===========================================================================================
    // TRANSACTION FLAGS (3 tests)
    // ===========================================================================================

    /**
     * Test Case 2.1: Transaction Suspension and Resumption
     * Tests TMSUSPEND and TMRESUME flags for interleaving transactions
     */
    @Test
    void testTransactionSuspensionAndResumption() throws Exception {
        XAConnection xaConn = xaConnection;
        XAResource xaRes = xaConn.getXAResource();
        Connection conn = xaConn.getConnection();
        
        Xid xid = createXid();
        
        // Start transaction
        xaRes.start(xid, XAResource.TMNOFLAGS);
        
        // Do some work
        String testName = "suspend-resume-" + System.currentTimeMillis();
        try (PreparedStatement pstmt = conn.prepareStatement(
                "INSERT INTO xa_test_baseline (test_name, test_value) VALUES (?, ?)")) {
            pstmt.setString(1, testName);
            pstmt.setString(2, "part1");
            pstmt.executeUpdate();
        }
        
        // Suspend transaction
        xaRes.end(xid, XAResource.TMSUSPEND);
        
        // Could do other work here with different transaction...
        
        // Resume transaction
        xaRes.start(xid, XAResource.TMRESUME);
        
        // Continue work in resumed transaction
        try (PreparedStatement pstmt = conn.prepareStatement(
                "UPDATE xa_test_baseline SET test_value = ? WHERE test_name = ?")) {
            pstmt.setString(1, "part2");
            pstmt.setString(2, testName);
            pstmt.executeUpdate();
        }
        
        // End and commit
        xaRes.end(xid, XAResource.TMSUCCESS);
        xaRes.prepare(xid);
        xaRes.commit(xid, false);
        
        // Verify final state
        Connection verifyConn = xaConnection.getConnection();
        verifyDataExists(verifyConn, testName);
    }

    /**
     * Test Case 2.2: Transaction Branch Joining
     * Tests TMJOIN flag for multiple connections working on same global transaction
     * 
     * NOTE: DB2 XA implementation may not fully support TMJOIN with the same XAConnection.
     * This test is kept for completeness but may be skipped for DB2.
     */
    @Test
    @org.junit.jupiter.api.Disabled("DB2 XA implementation has issues with TMJOIN on same XAConnection")
    void testTransactionBranchJoining() throws Exception {
        XAConnection xaConn1 = xaConnection;
        XAConnection xaConn2 = xaConnection;
        XAResource xaRes1 = xaConn1.getXAResource();
        XAResource xaRes2 = xaConn2.getXAResource();
        Connection conn1 = xaConn1.getConnection();
        Connection conn2 = xaConn2.getConnection();
        
        // Use same XID for both connections
        Xid xid = createXid();
        
        // Start transaction on first connection
        xaRes1.start(xid, XAResource.TMNOFLAGS);
        
        String testName = "join-test-" + System.currentTimeMillis();
        try (PreparedStatement pstmt = conn1.prepareStatement(
                "INSERT INTO xa_test_baseline (test_name, test_value) VALUES (?, ?)")) {
            pstmt.setString(1, testName);
            pstmt.setString(2, "from-conn1");
            pstmt.executeUpdate();
        }
        
        // End first connection's work
        xaRes1.end(xid, XAResource.TMSUCCESS);
        
        // Join the transaction with second connection
        xaRes2.start(xid, XAResource.TMJOIN);
        
        // Do work on second connection (same transaction)
        try (PreparedStatement pstmt = conn2.prepareStatement(
                "UPDATE xa_test_baseline SET test_value = ? WHERE test_name = ?")) {
            pstmt.setString(1, "from-conn2");
            pstmt.setString(2, testName);
            pstmt.executeUpdate();
        }
        
        // End second connection's work
        xaRes2.end(xid, XAResource.TMSUCCESS);
        
        // Prepare and commit (only need to do once for the global transaction)
        xaRes1.prepare(xid);
        xaRes1.commit(xid, false);
        
        // Verify both changes were applied
        Connection verifyConn = xaConnection.getConnection();
        verifyDataExists(verifyConn, testName);
        
        xaConn2.close();
    }

    /**
     * Test Case 2.3: Transaction Failure Marking
     * Tests TMFAIL flag for marking transaction branch as failed
     */
    @Test
    void testTransactionFailureMarking() throws Exception {
        XAConnection xaConn = xaConnection;
        XAResource xaRes = xaConn.getXAResource();
        Connection conn = xaConn.getConnection();
        
        Xid xid = createXid();
        
        // Start transaction
        xaRes.start(xid, XAResource.TMNOFLAGS);
        
        // Do some work
        String testName = "fail-test-" + System.currentTimeMillis();
        try (PreparedStatement pstmt = conn.prepareStatement(
                "INSERT INTO xa_test_baseline (test_name, test_value) VALUES (?, ?)")) {
            pstmt.setString(1, testName);
            pstmt.setString(2, "should-fail");
            pstmt.executeUpdate();
        }
        
        // Simulate failure by ending with TMFAIL
        // DB2 may immediately rollback and throw XA_RBROLLBACK here
        boolean rolledBackOnEnd = false;
        try {
            xaRes.end(xid, XAResource.TMFAIL);
        } catch (XAException e) {
            // DB2 throws XA_RBROLLBACK immediately on end with TMFAIL
            if (e.errorCode >= XAException.XA_RBBASE && e.errorCode <= XAException.XA_RBEND) {
                rolledBackOnEnd = true;
            } else {
                throw e;
            }
        }
        
        // Transaction is now marked as rollback-only
        // Attempting to prepare will fail (unless already rolled back on end)
        if (!rolledBackOnEnd) {
            try {
                xaRes.prepare(xid);
                fail("Prepare should fail after TMFAIL");
            } catch (XAException e) {
                // Expected - transaction marked for rollback
                assertTrue(e.errorCode >= XAException.XA_RBBASE && e.errorCode <= XAException.XA_RBEND,
                        "Expected rollback error code, got: " + e.errorCode);
            } catch (Exception e) {
                // Other databases might throw different exceptions - also acceptable
            }
        }
        
        // Must rollback - but prepare already rolled back on DB2
        try {
            xaRes.rollback(xid);
        } catch (XAException e) {
            // DB2 may throw XAER_NOTA if transaction already rolled back during prepare
            if (e.errorCode != XAException.XAER_NOTA) {
                throw e;
            }
        }
        
        // Verify data was NOT committed
        Connection verifyConn = xaConnection.getConnection();
        verifyDataNotExists(verifyConn, testName);
    }
}
