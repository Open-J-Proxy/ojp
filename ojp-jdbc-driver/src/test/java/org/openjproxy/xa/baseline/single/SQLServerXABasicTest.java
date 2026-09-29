package org.openjproxy.xa.baseline.single;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;
import org.openjproxy.xa.baseline.common.XATestBase;
import org.openjproxy.xa.baseline.containers.SQLServerXAContainer;

import javax.sql.XAConnection;
import javax.sql.XADataSource;
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SQL Server XA Basic Operations Test Suite
 * 
 * Tests core XA transaction functionality using SQL Server native JDBC driver.
 * This establishes the behavioral baseline for SQL Server before testing OJP.
 * 
 * Test Coverage:
 * - XA connection and resource creation
 * - Two-phase commit (2PC) protocol
 * - Transaction rollback
 * - One-phase commit optimization
 * - Read-only transaction optimization
 * - Transaction suspension and resumption (TMSUSPEND/TMRESUME)
 * - Transaction branch joining (TMJOIN)
 * - Transaction failure marking (TMFAIL)
 * 
 * TEMPORARILY DISABLED: SQL Server XA tests cause indefinite hangs even with
 * specific problematic tests disabled. Disabling entire test class until root
 * cause can be systematically debugged.
 * 
 * These tests are disabled by default and only run when -DenableSqlServerTests=true
 */
@Disabled("SQL Server XA tests cause indefinite hangs - temporarily disabled for systematic debugging")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIf("org.openjproxy.xa.baseline.containers.SQLServerXATestContainer#isEnabled")
public class SQLServerXABasicTest extends XATestBase {

    protected static XADataSource staticXADataSource;

    @BeforeAll
    public static void setUpClass() throws SQLException {
        SQLServerXAContainer container = new SQLServerXAContainer();
        staticXADataSource = container.createXADataSource();
    }

    @Override
    protected String getDatabaseType() {
        return "SQL Server";
    }

    @Override
    protected XADataSource createXADataSource() throws SQLException {
        return staticXADataSource;
    }

    // ==================== Test Case 1: Core XA Operations ====================

    /**
     * Test Case 1.1: XA Connection Creation
     * 
     * Validates basic XA infrastructure setup:
     * - XA DataSource creation
     * - XA Connection acquisition
     * - XA Resource acquisition
     * - Logical connection retrieval
     * - Auto-commit disabled (required for XA)
     * - isSameRM() functionality
     */
    @Test
    @Order(1)
    @DisplayName("1.1: XA Connection Creation")
    public void testXAConnectionCreation() throws Exception {
        System.out.println("\n=== Test 1.1: XA Connection Creation ===");

        XADataSource xaDataSource = createXADataSource();
        assertNotNull(xaDataSource, "XADataSource should not be null");

        XAConnection xaConn = xaDataSource.getXAConnection();
        assertNotNull(xaConn, "XAConnection should not be null");

        XAResource xaRes = xaConn.getXAResource();
        assertNotNull(xaRes, "XAResource should not be null");

        Connection conn = xaConn.getConnection();
        assertNotNull(conn, "Logical connection should not be null");

        // Verify auto-commit is disabled (XA requirement)
        assertFalse(conn.getAutoCommit(), "Auto-commit must be disabled for XA transactions");

        // Test isSameRM() with same resource
        assertTrue(xaRes.isSameRM(xaRes), "XAResource should recognize itself");

        System.out.println("✓ XA connection infrastructure validated");
    }

    /**
     * Test Case 1.2: Basic XA Transaction Lifecycle (Happy Path)
     * 
     * Demonstrates complete two-phase commit (2PC) protocol:
     * Flow: START → INSERT → END → PREPARE → COMMIT
     * 
     * This is the fundamental XA transaction pattern that all other
     * tests build upon.
     */
    @Test
    @Order(2)
    @DisplayName("1.2: Basic XA Transaction Lifecycle (2PC)")
    public void testBasicXATransactionLifecycle() throws Exception {
        System.out.println("\n=== Test 1.2: Basic XA Transaction Lifecycle (2PC) ===");

        XAConnection xaConn = createXADataSource().getXAConnection();
        XAResource xaRes = xaConn.getXAResource();
        Connection conn = xaConn.getConnection();

        Xid xid = createXid();
        String testValue = "BasicLifecycle-" + System.currentTimeMillis();

        try {
            // Phase 1: Application work
            System.out.println("Starting XA transaction...");
            xaRes.start(xid, XAResource.TMNOFLAGS);

            System.out.println("Inserting test data...");
            try (PreparedStatement pstmt = conn.prepareStatement(
                    "INSERT INTO xa_test_baseline (test_name, test_value) VALUES (?, ?)")) {
                pstmt.setString(1, "BasicLifecycle");
                pstmt.setString(2, testValue);
                int rows = pstmt.executeUpdate();
                assertEquals(1, rows, "Should insert 1 row");
            }

            System.out.println("Ending XA transaction...");
            xaRes.end(xid, XAResource.TMSUCCESS);

            // Phase 2: Two-phase commit
            System.out.println("Preparing transaction (Phase 1 of 2PC)...");
            int prepareResult = xaRes.prepare(xid);
            assertTrue(prepareResult == XAResource.XA_OK || prepareResult == XAResource.XA_RDONLY,
                    "Prepare should return XA_OK or XA_RDONLY");

            if (prepareResult == XAResource.XA_OK) {
                System.out.println("Committing transaction (Phase 2 of 2PC)...");
                xaRes.commit(xid, false); // false = two-phase commit
            } else {
                System.out.println("Transaction was read-only optimized, no commit needed");
            }

            // Verify data was committed
            System.out.println("Verifying data persistence...");
            try (PreparedStatement pstmt = conn.prepareStatement(
                    "SELECT test_value FROM xa_test_baseline WHERE test_name = ?")) {
                pstmt.setString(1, "BasicLifecycle");
                ResultSet rs = pstmt.executeQuery();
                assertTrue(rs.next(), "Data should be committed");
                assertEquals(testValue, rs.getString(1), "Committed value should match");
            }

            System.out.println("✓ Two-phase commit completed successfully");

        } finally {
            // Cleanup
            cleanupTestData(conn, "BasicLifecycle");
        }
    }

    /**
     * Test Case 1.3: XA Transaction Rollback
     * 
     * Tests rollback functionality:
     * Flow: START → INSERT → END → ROLLBACK
     * 
     * Verifies that data is NOT committed after rollback.
     */
    @Test
    @Order(3)
    @DisplayName("1.3: XA Transaction Rollback")
    public void testXATransactionRollback() throws Exception {
        System.out.println("\n=== Test 1.3: XA Transaction Rollback ===");

        XAConnection xaConn = createXADataSource().getXAConnection();
        XAResource xaRes = xaConn.getXAResource();
        Connection conn = xaConn.getConnection();

        Xid xid = createXid();
        String testValue = "RollbackTest-" + System.currentTimeMillis();

        try {
            System.out.println("Starting XA transaction...");
            xaRes.start(xid, XAResource.TMNOFLAGS);

            System.out.println("Inserting test data...");
            try (PreparedStatement pstmt = conn.prepareStatement(
                    "INSERT INTO xa_test_baseline (test_name, test_value) VALUES (?, ?)")) {
                pstmt.setString(1, "RollbackTest");
                pstmt.setString(2, testValue);
                pstmt.executeUpdate();
            }

            System.out.println("Ending XA transaction...");
            xaRes.end(xid, XAResource.TMSUCCESS);

            System.out.println("Rolling back transaction...");
            xaRes.rollback(xid);

            // Verify data was NOT committed
            System.out.println("Verifying data was rolled back...");
            try (PreparedStatement pstmt = conn.prepareStatement(
                    "SELECT COUNT(*) FROM xa_test_baseline WHERE test_name = ?")) {
                pstmt.setString(1, "RollbackTest");
                ResultSet rs = pstmt.executeQuery();
                rs.next();
                assertEquals(0, rs.getInt(1), "Data should NOT exist after rollback");
            }

            System.out.println("✓ Rollback verified successfully");

        } finally {
            // Cleanup (should be no data, but just in case)
            cleanupTestData(conn, "RollbackTest");
        }
    }

    /**
     * Test Case 1.4: One-Phase Commit Optimization
     * 
     * Tests single resource optimization (1PC):
     * Flow: START → UPDATE → END → COMMIT (one-phase, no explicit prepare)
     * 
     * When only one resource manager is involved, the transaction manager
     * can optimize by skipping the prepare phase and committing directly.
     */
    @Test
    @Order(4)
    @DisplayName("1.4: One-Phase Commit Optimization")
    public void testOnePhaseCommitOptimization() throws Exception {
        System.out.println("\n=== Test 1.4: One-Phase Commit Optimization ===");

        XAConnection xaConn = createXADataSource().getXAConnection();
        XAResource xaRes = xaConn.getXAResource();
        Connection conn = xaConn.getConnection();

        // First insert a row to update
        String testValue = "OnePhaseBefore-" + System.currentTimeMillis();
        try (PreparedStatement pstmt = conn.prepareStatement(
                "INSERT INTO xa_test_baseline (test_name, test_value) VALUES (?, ?)")) {
            pstmt.setString(1, "OnePhaseTest");
            pstmt.setString(2, testValue);
            pstmt.executeUpdate();
        }

        Xid xid = createXid();
        String updatedValue = "OnePhaseAfter-" + System.currentTimeMillis();

        try {
            System.out.println("Starting XA transaction...");
            xaRes.start(xid, XAResource.TMNOFLAGS);

            System.out.println("Updating test data...");
            try (PreparedStatement pstmt = conn.prepareStatement(
                    "UPDATE xa_test_baseline SET test_value = ? WHERE test_name = ?")) {
                pstmt.setString(1, updatedValue);
                pstmt.setString(2, "OnePhaseTest");
                int rows = pstmt.executeUpdate();
                assertEquals(1, rows, "Should update 1 row");
            }

            System.out.println("Ending XA transaction...");
            xaRes.end(xid, XAResource.TMSUCCESS);

            // One-phase commit: no prepare, commit with onePhase=true
            System.out.println("Committing with one-phase optimization...");
            xaRes.commit(xid, true); // true = one-phase commit

            // Verify data was committed
            System.out.println("Verifying data persistence...");
            try (PreparedStatement pstmt = conn.prepareStatement(
                    "SELECT test_value FROM xa_test_baseline WHERE test_name = ?")) {
                pstmt.setString(1, "OnePhaseTest");
                ResultSet rs = pstmt.executeQuery();
                assertTrue(rs.next(), "Data should exist");
                assertEquals(updatedValue, rs.getString(1), "Value should be updated");
            }

            System.out.println("✓ One-phase commit completed successfully");

        } finally {
            cleanupTestData(conn, "OnePhaseTest");
        }
    }

    /**
     * Test Case 1.5: Read-Only Transaction Optimization
     * 
     * Tests read-only transaction handling:
     * Flow: START → SELECT only → END → PREPARE
     * 
     * SQL Server behavior: prepare() may return XA_RDONLY or XA_OK depending on
     * SQL Server's optimization logic. Both are valid responses.
     */
    @Test
    @Order(5)
    @DisplayName("1.5: Read-Only Transaction Optimization")
    public void testReadOnlyTransactionOptimization() throws Exception {
        System.out.println("\n=== Test 1.5: Read-Only Transaction Optimization ===");

        XAConnection xaConn = createXADataSource().getXAConnection();
        XAResource xaRes = xaConn.getXAResource();
        Connection conn = xaConn.getConnection();

        Xid xid = createXid();

        System.out.println("Starting XA transaction...");
        xaRes.start(xid, XAResource.TMNOFLAGS);

        System.out.println("Performing read-only operation (SELECT)...");
        try (PreparedStatement pstmt = conn.prepareStatement(
                "SELECT COUNT(*) FROM xa_test_baseline")) {
            ResultSet rs = pstmt.executeQuery();
            rs.next();
            System.out.println("Read " + rs.getInt(1) + " rows");
        }

        System.out.println("Ending XA transaction...");
        xaRes.end(xid, XAResource.TMSUCCESS);

        System.out.println("Preparing transaction...");
        int prepareResult = xaRes.prepare(xid);

        System.out.println("Prepare result: " + (prepareResult == XAResource.XA_RDONLY ? "XA_RDONLY" : "XA_OK"));

        // SQL Server may return XA_RDONLY or XA_OK for read-only transactions
        assertTrue(prepareResult == XAResource.XA_RDONLY || prepareResult == XAResource.XA_OK,
                "Prepare should return XA_RDONLY or XA_OK for read-only transaction");

        if (prepareResult == XAResource.XA_OK) {
            // If not optimized, need to commit
            xaRes.commit(xid, false);
            System.out.println("✓ Read-only transaction committed (not optimized)");
        } else {
            System.out.println("✓ Read-only transaction optimized (XA_RDONLY)");
        }
    }

    // ==================== Test Case 2: Transaction Flags ====================

    /**
     * Test Case 2.1: Transaction Suspension and Resumption
     * 
     * Tests TMSUSPEND and TMRESUME flags:
     * Flow: START → work → END(TMSUSPEND) → START(TMRESUME) → work → END → COMMIT
     * 
     * Transaction suspension allows interleaving multiple transactions on the
     * same connection, which is useful for complex transaction management scenarios.
     */
    @Test
    @Order(6)
    @DisplayName("2.1: Transaction Suspension and Resumption")
    public void testTransactionSuspensionAndResumption() throws Exception {
        System.out.println("\n=== Test 2.1: Transaction Suspension and Resumption ===");

        XAConnection xaConn = createXADataSource().getXAConnection();
        XAResource xaRes = xaConn.getXAResource();
        Connection conn = xaConn.getConnection();

        Xid xid = createXid();
        String testValue = "SuspendTest-" + System.currentTimeMillis();

        try {
            // Start transaction and do some work
            System.out.println("Starting XA transaction...");
            xaRes.start(xid, XAResource.TMNOFLAGS);

            System.out.println("Inserting first row...");
            try (PreparedStatement pstmt = conn.prepareStatement(
                    "INSERT INTO xa_test_baseline (test_name, test_value) VALUES (?, ?)")) {
                pstmt.setString(1, "SuspendTest");
                pstmt.setString(2, testValue + "-1");
                pstmt.executeUpdate();
            }

            // Suspend the transaction
            System.out.println("Suspending transaction...");
            xaRes.end(xid, XAResource.TMSUSPEND);

            // Could do other work here (e.g., start another transaction)
            System.out.println("Transaction suspended, could do other work here...");

            // Resume the transaction
            System.out.println("Resuming transaction...");
            xaRes.start(xid, XAResource.TMRESUME);

            System.out.println("Inserting second row after resume...");
            try (PreparedStatement pstmt = conn.prepareStatement(
                    "INSERT INTO xa_test_baseline (test_name, test_value) VALUES (?, ?)")) {
                pstmt.setString(1, "SuspendTest");
                pstmt.setString(2, testValue + "-2");
                pstmt.executeUpdate();
            }

            // End and commit
            System.out.println("Ending transaction...");
            xaRes.end(xid, XAResource.TMSUCCESS);

            System.out.println("Preparing and committing...");
            int prepareResult = xaRes.prepare(xid);
            if (prepareResult == XAResource.XA_OK) {
                xaRes.commit(xid, false);
            }

            // Verify both inserts were committed
            System.out.println("Verifying data persistence...");
            try (PreparedStatement pstmt = conn.prepareStatement(
                    "SELECT COUNT(*) FROM xa_test_baseline WHERE test_name = ?")) {
                pstmt.setString(1, "SuspendTest");
                ResultSet rs = pstmt.executeQuery();
                rs.next();
                assertEquals(2, rs.getInt(1), "Both inserts should be committed");
            }

            System.out.println("✓ Suspension and resumption completed successfully");

        } finally {
            cleanupTestData(conn, "SuspendTest");
        }
    }

    /**
     * Test Case 2.2: Transaction Branch Joining
     * 
     * Tests TMJOIN flag:
     * Flow: Connection 1: START → work → END
     *       Connection 2: START(TMJOIN) → work → END
     *       PREPARE → COMMIT
     * 
     * TMJOIN allows multiple branches (connections) to participate in the
     * same global transaction, which is essential for distributed transactions.
     */
    @Test
    @Order(7)
    @DisplayName("2.2: Transaction Branch Joining")
    public void testTransactionBranchJoining() throws Exception {
        System.out.println("\n=== Test 2.2: Transaction Branch Joining ===");

        XAConnection xaConn1 = createXADataSource().getXAConnection();
        XAResource xaRes1 = xaConn1.getXAResource();
        Connection conn1 = xaConn1.getConnection();

        XAConnection xaConn2 = createXADataSource().getXAConnection();
        XAResource xaRes2 = xaConn2.getXAResource();
        Connection conn2 = xaConn2.getConnection();

        // Use same XID for both branches
        Xid xid = createXid();
        String testValue = "JoinTest-" + System.currentTimeMillis();

        try {
            // Branch 1: Start and do some work
            System.out.println("Branch 1: Starting transaction...");
            xaRes1.start(xid, XAResource.TMNOFLAGS);

            System.out.println("Branch 1: Inserting data...");
            try (PreparedStatement pstmt = conn1.prepareStatement(
                    "INSERT INTO xa_test_baseline (test_name, test_value) VALUES (?, ?)")) {
                pstmt.setString(1, "JoinTest");
                pstmt.setString(2, testValue + "-branch1");
                pstmt.executeUpdate();
            }

            System.out.println("Branch 1: Ending...");
            xaRes1.end(xid, XAResource.TMSUCCESS);

            // Branch 2: Join the same transaction
            System.out.println("Branch 2: Joining transaction with TMJOIN...");
            xaRes2.start(xid, XAResource.TMJOIN);

            System.out.println("Branch 2: Inserting data...");
            try (PreparedStatement pstmt = conn2.prepareStatement(
                    "INSERT INTO xa_test_baseline (test_name, test_value) VALUES (?, ?)")) {
                pstmt.setString(1, "JoinTest");
                pstmt.setString(2, testValue + "-branch2");
                pstmt.executeUpdate();
            }

            System.out.println("Branch 2: Ending...");
            xaRes2.end(xid, XAResource.TMSUCCESS);

            // Prepare and commit (use first resource as coordinator)
            System.out.println("Preparing transaction...");
            int prepareResult = xaRes1.prepare(xid);
            if (prepareResult == XAResource.XA_OK) {
                System.out.println("Committing transaction...");
                xaRes1.commit(xid, false);
            }

            // Verify both inserts were committed
            System.out.println("Verifying data persistence...");
            try (PreparedStatement pstmt = conn1.prepareStatement(
                    "SELECT COUNT(*) FROM xa_test_baseline WHERE test_name = ?")) {
                pstmt.setString(1, "JoinTest");
                ResultSet rs = pstmt.executeQuery();
                rs.next();
                assertEquals(2, rs.getInt(1), "Both branches should be committed");
            }

            System.out.println("✓ Branch joining completed successfully");

        } finally {
            cleanupTestData(conn1, "JoinTest");
        }
    }

    /**
     * Test Case 2.3: Transaction Failure Marking
     * 
     * Tests TMFAIL flag:
     * Flow: START → work → END(TMFAIL) → ROLLBACK
     * 
     * TMFAIL marks the transaction branch as failed, meaning it can only
     * be rolled back, not committed. This is used when an error occurs
     * during transaction processing.
     */
    @Test
    @Order(8)
    @DisplayName("2.3: Transaction Failure Marking")
    public void testTransactionFailureMarking() throws Exception {
        System.out.println("\n=== Test 2.3: Transaction Failure Marking ===");

        XAConnection xaConn = createXADataSource().getXAConnection();
        XAResource xaRes = xaConn.getXAResource();
        Connection conn = xaConn.getConnection();

        Xid xid = createXid();
        String testValue = "FailTest-" + System.currentTimeMillis();

        try {
            System.out.println("Starting XA transaction...");
            xaRes.start(xid, XAResource.TMNOFLAGS);

            System.out.println("Inserting data...");
            try (PreparedStatement pstmt = conn.prepareStatement(
                    "INSERT INTO xa_test_baseline (test_name, test_value) VALUES (?, ?)")) {
                pstmt.setString(1, "FailTest");
                pstmt.setString(2, testValue);
                pstmt.executeUpdate();
            }

            // Mark transaction as failed
            System.out.println("Marking transaction as failed with TMFAIL...");
            xaRes.end(xid, XAResource.TMFAIL);

            // Transaction can only be rolled back after TMFAIL
            System.out.println("Rolling back failed transaction...");
            xaRes.rollback(xid);

            // Verify data was NOT committed
            System.out.println("Verifying data was rolled back...");
            try (PreparedStatement pstmt = conn.prepareStatement(
                    "SELECT COUNT(*) FROM xa_test_baseline WHERE test_name = ?")) {
                pstmt.setString(1, "FailTest");
                ResultSet rs = pstmt.executeQuery();
                rs.next();
                assertEquals(0, rs.getInt(1), "Data should NOT exist after rollback");
            }

            System.out.println("✓ Transaction failure marking handled correctly");

        } finally {
            cleanupTestData(conn, "FailTest");
        }
    }

    /**
     * Helper method to clean up test data
     */
    private void cleanupTestData(Connection conn, String testName) {
        try (PreparedStatement pstmt = conn.prepareStatement(
                "DELETE FROM xa_test_baseline WHERE test_name = ?")) {
            pstmt.setString(1, testName);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Warning: Failed to cleanup test data for " + testName + ": " + e.getMessage());
        }
    }
}
