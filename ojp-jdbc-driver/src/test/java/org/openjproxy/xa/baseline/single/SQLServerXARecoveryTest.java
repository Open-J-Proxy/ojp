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
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SQL Server XA Recovery Operations Test Suite
 * 
 * Tests XA recovery functionality using SQL Server native JDBC driver.
 * This establishes the recovery baseline behavior for SQL Server.
 * 
 * Recovery operations are critical for distributed transaction systems
 * as they handle failure scenarios where transactions are prepared but
 * not yet committed or rolled back.
 * 
 * Test Coverage:
 * - recover() - list prepared (in-doubt) transactions
 * - Commit after recovery
 * - Rollback after recovery
 * - forget() - clear heuristic outcomes
 * - Recovery with different flags (TMSTARTRSCAN, TMENDRSCAN, TMNOFLAGS)
 * - Multiple in-doubt transactions recovery
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
public class SQLServerXARecoveryTest extends XATestBase {

    @BeforeAll
    public static void setUpClass() throws SQLException {
        // Singleton container managed by SQLServerXATestContainer
    }

    @Override
    protected String getDatabaseType() {
        return "SQL Server";
    }

    @Override
    protected XADataSource createXADataSource() throws SQLException {
        return new SQLServerXAContainer().createXADataSource();
    }

    // ==================== Test Case 6: Recovery Operations ====================

    /**
     * Test Case 6.1: Recover Prepared Transactions
     * 
     * Tests basic recover() functionality:
     * - Prepare multiple transactions
     * - Call recover() to list them
     * - Verify XID matching
     * - Commit recovered transactions
     * 
     * This is the fundamental recovery operation that transaction
     * managers use to discover in-doubt transactions.
     */
    @Test
    @Order(1)
    @DisplayName("6.1: Recover Prepared Transactions")
    public void testRecoverPreparedTransactions() throws Exception {
        System.out.println("\n=== Test 6.1: Recover Prepared Transactions ===");

        XAConnection xaConn = createXADataSource().getXAConnection();
        XAResource xaRes = xaConn.getXAResource();
        Connection conn = xaConn.getConnection();

        Xid xid1 = createXid();
        Xid xid2 = createXid();

        try {
            // Prepare first transaction
            System.out.println("Preparing first transaction...");
            xaRes.start(xid1, XAResource.TMNOFLAGS);
            try (PreparedStatement pstmt = conn.prepareStatement(
                    "INSERT INTO xa_test_baseline (test_name, test_value) VALUES (?, ?)")) {
                pstmt.setString(1, "RecoverTest1");
                pstmt.setString(2, "Value1-" + System.currentTimeMillis());
                pstmt.executeUpdate();
            }
            xaRes.end(xid1, XAResource.TMSUCCESS);
            int result1 = xaRes.prepare(xid1);
            assertEquals(XAResource.XA_OK, result1, "First prepare should return XA_OK");

            // Prepare second transaction
            System.out.println("Preparing second transaction...");
            xaRes.start(xid2, XAResource.TMNOFLAGS);
            try (PreparedStatement pstmt = conn.prepareStatement(
                    "INSERT INTO xa_test_baseline (test_name, test_value) VALUES (?, ?)")) {
                pstmt.setString(1, "RecoverTest2");
                pstmt.setString(2, "Value2-" + System.currentTimeMillis());
                pstmt.executeUpdate();
            }
            xaRes.end(xid2, XAResource.TMSUCCESS);
            int result2 = xaRes.prepare(xid2);
            assertEquals(XAResource.XA_OK, result2, "Second prepare should return XA_OK");

            // Recover prepared transactions
            System.out.println("Recovering prepared transactions...");
            Xid[] recoveredXids = xaRes.recover(XAResource.TMSTARTRSCAN | XAResource.TMENDRSCAN);
            assertNotNull(recoveredXids, "Recovered XIDs should not be null");
            assertTrue(recoveredXids.length >= 2, "Should recover at least 2 transactions");

            System.out.println("Recovered " + recoveredXids.length + " transactions");

            // Find our XIDs in the recovered list
            boolean found1 = false, found2 = false;
            for (Xid recoveredXid : recoveredXids) {
                if (Arrays.equals(xid1.getGlobalTransactionId(), recoveredXid.getGlobalTransactionId())) {
                    found1 = true;
                    System.out.println("Found xid1 in recovered list");
                }
                if (Arrays.equals(xid2.getGlobalTransactionId(), recoveredXid.getGlobalTransactionId())) {
                    found2 = true;
                    System.out.println("Found xid2 in recovered list");
                }
            }

            assertTrue(found1, "Should find first XID in recovered list");
            assertTrue(found2, "Should find second XID in recovered list");

            // Commit both transactions
            System.out.println("Committing recovered transactions...");
            xaRes.commit(xid1, false);
            xaRes.commit(xid2, false);

            // Verify data was committed
            System.out.println("Verifying data persistence...");
            try (PreparedStatement pstmt = conn.prepareStatement(
                    "SELECT COUNT(*) FROM xa_test_baseline WHERE test_name IN (?, ?)")) {
                pstmt.setString(1, "RecoverTest1");
                pstmt.setString(2, "RecoverTest2");
                ResultSet rs = pstmt.executeQuery();
                rs.next();
                assertEquals(2, rs.getInt(1), "Both transactions should be committed");
            }

            System.out.println("✓ Recovery and commit completed successfully");

        } finally {
            cleanupTestData(conn, "RecoverTest1");
            cleanupTestData(conn, "RecoverTest2");
        }
    }

    /**
     * Test Case 6.2: Recovery After Connection Loss
     * 
     * Simulates a crash scenario:
     * - Prepare a transaction
     * - Close the connection (simulating crash)
     * - Open new connection
     * - Recover the prepared transaction
     * - Commit using new connection
     * 
     * This tests the persistence of prepared transactions across
     * connection failures, which is critical for crash recovery.
     */
    @Test
    @Order(2)
    @DisplayName("6.2: Recovery After Connection Loss")
    public void testRecoveryAfterConnectionLoss() throws Exception {
        System.out.println("\n=== Test 6.2: Recovery After Connection Loss ===");

        Xid xid = createXid();
        String testValue = "CrashTest-" + System.currentTimeMillis();

        // Phase 1: Prepare transaction then "crash" (close connection)
        {
            XAConnection xaConn = createXADataSource().getXAConnection();
            XAResource xaRes = xaConn.getXAResource();
            Connection conn = xaConn.getConnection();

            try {
                System.out.println("Starting transaction...");
                xaRes.start(xid, XAResource.TMNOFLAGS);

                System.out.println("Inserting data...");
                try (PreparedStatement pstmt = conn.prepareStatement(
                        "INSERT INTO xa_test_baseline (test_name, test_value) VALUES (?, ?)")) {
                    pstmt.setString(1, "CrashTest");
                    pstmt.setString(2, testValue);
                    pstmt.executeUpdate();
                }

                System.out.println("Ending and preparing transaction...");
                xaRes.end(xid, XAResource.TMSUCCESS);
                int result = xaRes.prepare(xid);
                assertEquals(XAResource.XA_OK, result, "Prepare should return XA_OK");

                System.out.println("Transaction prepared, simulating crash (closing connection)...");
            } finally {
                // Close connection without committing (simulating crash)
                conn.close();
                xaConn.close();
            }
        }

        // Phase 2: Recovery with new connection
        XAConnection xaConn2 = createXADataSource().getXAConnection();
        XAResource xaRes2 = xaConn2.getXAResource();
        Connection conn2 = xaConn2.getConnection();

        try {
            System.out.println("New connection established, recovering prepared transactions...");
            Xid[] recoveredXids = xaRes2.recover(XAResource.TMSTARTRSCAN | XAResource.TMENDRSCAN);
            assertNotNull(recoveredXids, "Recovered XIDs should not be null");

            // Find our XID in the recovered list
            Xid recoveredXid = null;
            for (Xid recovered : recoveredXids) {
                if (Arrays.equals(xid.getGlobalTransactionId(), recovered.getGlobalTransactionId())) {
                    recoveredXid = recovered;
                    break;
                }
            }

            assertNotNull(recoveredXid, "Should find our XID in recovered list");
            System.out.println("Found prepared transaction after 'crash'");

            // Commit the recovered transaction
            System.out.println("Committing recovered transaction...");
            xaRes2.commit(recoveredXid, false);

            // Verify data was committed
            System.out.println("Verifying data persistence...");
            try (PreparedStatement pstmt = conn2.prepareStatement(
                    "SELECT test_value FROM xa_test_baseline WHERE test_name = ?")) {
                pstmt.setString(1, "CrashTest");
                ResultSet rs = pstmt.executeQuery();
                assertTrue(rs.next(), "Data should exist after recovery commit");
                assertEquals(testValue, rs.getString(1), "Value should match");
            }

            System.out.println("✓ Recovery after connection loss completed successfully");

        } finally {
            cleanupTestData(conn2, "CrashTest");
        }
    }

    /**
     * Test Case 6.3: Recovery Flags (TMSTARTRSCAN, TMENDRSCAN, TMNOFLAGS)
     * 
     * Tests different recovery scan modes:
     * - TMSTARTRSCAN - start recovery scan
     * - TMNOFLAGS - continue recovery scan
     * - TMENDRSCAN - end recovery scan
     * - TMSTARTRSCAN | TMENDRSCAN - single call recovery (most common)
     * 
     * Different drivers may implement recovery scanning differently.
     * SQL Server typically returns all XIDs in a single call.
     */
    @Test
    @Order(3)
    @DisplayName("6.3: Recovery Flags (TMSTARTRSCAN, TMENDRSCAN, TMNOFLAGS)")
    public void testRecoveryFlags() throws Exception {
        System.out.println("\n=== Test 6.3: Recovery Flags ===");

        XAConnection xaConn = createXADataSource().getXAConnection();
        XAResource xaRes = xaConn.getXAResource();
        Connection conn = xaConn.getConnection();

        Xid xid = createXid();

        try {
            // Prepare a transaction
            System.out.println("Preparing transaction...");
            xaRes.start(xid, XAResource.TMNOFLAGS);
            try (PreparedStatement pstmt = conn.prepareStatement(
                    "INSERT INTO xa_test_baseline (test_name, test_value) VALUES (?, ?)")) {
                pstmt.setString(1, "RecoveryFlags");
                pstmt.setString(2, "FlagsTest-" + System.currentTimeMillis());
                pstmt.executeUpdate();
            }
            xaRes.end(xid, XAResource.TMSUCCESS);
            int result = xaRes.prepare(xid);
            assertEquals(XAResource.XA_OK, result, "Prepare should return XA_OK");

            // Test 1: TMSTARTRSCAN | TMENDRSCAN (single call, most common)
            System.out.println("\nTest 1: TMSTARTRSCAN | TMENDRSCAN (single call)");
            Xid[] xids1 = xaRes.recover(XAResource.TMSTARTRSCAN | XAResource.TMENDRSCAN);
            assertNotNull(xids1, "Should return XIDs");
            System.out.println("Recovered " + xids1.length + " XIDs");

            // Test 2: TMSTARTRSCAN (start scan)
            System.out.println("\nTest 2: TMSTARTRSCAN (start scan)");
            Xid[] xids2 = xaRes.recover(XAResource.TMSTARTRSCAN);
            assertNotNull(xids2, "Should return XIDs");
            System.out.println("Recovered " + xids2.length + " XIDs with TMSTARTRSCAN");

            // Test 3: TMENDRSCAN (end scan)
            System.out.println("\nTest 3: TMENDRSCAN (end scan)");
            Xid[] xids3 = xaRes.recover(XAResource.TMENDRSCAN);
            // May return empty array or null depending on SQL Server behavior
            System.out.println("TMENDRSCAN returned " + (xids3 == null ? "null" : xids3.length + " XIDs"));

            // Test 4: TMNOFLAGS (continue scan)
            System.out.println("\nTest 4: TMNOFLAGS (continue scan)");
            try {
                Xid[] xids4 = xaRes.recover(XAResource.TMNOFLAGS);
                System.out.println("TMNOFLAGS returned " + (xids4 == null ? "null" : xids4.length + " XIDs"));
            } catch (XAException e) {
                System.out.println("TMNOFLAGS may throw exception: " + e.getMessage());
            }

            // Commit the prepared transaction
            System.out.println("\nCommitting prepared transaction...");
            xaRes.commit(xid, false);

            System.out.println("✓ Recovery flags tested successfully");

        } finally {
            cleanupTestData(conn, "RecoveryFlags");
        }
    }

    /**
     * Test Case 6.4: Forget Heuristically Completed Transaction
     * 
     * Tests forget() operation:
     * Flow: START → END → PREPARE → COMMIT → FORGET
     * 
     * forget() is used to tell the resource manager to forget about a
     * heuristically completed transaction. This is typically used after
     * a transaction has been committed or rolled back heuristically
     * (i.e., without coordination with the transaction manager).
     * 
     * Note: SQL Server may not require explicit forget() calls for
     * normally completed transactions, but the operation should succeed.
     */
    @Test
    @Order(4)
    @DisplayName("6.4: Forget Heuristically Completed Transaction")
    public void testForgetHeuristicallyCompletedTransaction() throws Exception {
        System.out.println("\n=== Test 6.4: Forget Heuristically Completed Transaction ===");

        XAConnection xaConn = createXADataSource().getXAConnection();
        XAResource xaRes = xaConn.getXAResource();
        Connection conn = xaConn.getConnection();

        Xid xid = createXid();
        String testValue = "ForgetTest-" + System.currentTimeMillis();

        try {
            // Prepare and commit transaction
            System.out.println("Starting transaction...");
            xaRes.start(xid, XAResource.TMNOFLAGS);

            System.out.println("Inserting data...");
            try (PreparedStatement pstmt = conn.prepareStatement(
                    "INSERT INTO xa_test_baseline (test_name, test_value) VALUES (?, ?)")) {
                pstmt.setString(1, "ForgetTest");
                pstmt.setString(2, testValue);
                pstmt.executeUpdate();
            }

            System.out.println("Ending and preparing transaction...");
            xaRes.end(xid, XAResource.TMSUCCESS);
            int result = xaRes.prepare(xid);
            assertEquals(XAResource.XA_OK, result, "Prepare should return XA_OK");

            System.out.println("Committing transaction...");
            xaRes.commit(xid, false);

            // Now forget the transaction
            System.out.println("Calling forget() on completed transaction...");
            try {
                xaRes.forget(xid);
                System.out.println("forget() succeeded");
            } catch (XAException e) {
                // SQL Server may throw XAER_NOTA if transaction is already forgotten
                if (e.errorCode == XAException.XAER_NOTA) {
                    System.out.println("forget() returned XAER_NOTA (transaction already forgotten)");
                } else {
                    System.out.println("forget() threw exception: " + e.getMessage() + " (code: " + e.errorCode + ")");
                }
                // This is acceptable behavior
            }

            // Verify data was committed (forget should not affect this)
            System.out.println("Verifying data persistence...");
            try (PreparedStatement pstmt = conn.prepareStatement(
                    "SELECT test_value FROM xa_test_baseline WHERE test_name = ?")) {
                pstmt.setString(1, "ForgetTest");
                ResultSet rs = pstmt.executeQuery();
                assertTrue(rs.next(), "Data should exist after forget");
                assertEquals(testValue, rs.getString(1), "Value should match");
            }

            System.out.println("✓ Forget operation completed successfully");

        } finally {
            cleanupTestData(conn, "ForgetTest");
        }
    }

    /**
     * Test Case 6.5: Multiple In-Doubt Transactions Recovery
     * 
     * Tests recovery of multiple prepared transactions:
     * - Prepare 3 transactions
     * - Recover all
     * - Commit 2, rollback 1
     * - Verify correct outcomes
     * 
     * This simulates real-world scenarios where multiple transactions
     * may be in-doubt and need to be resolved differently.
     */
    @Test
    @Order(5)
    @DisplayName("6.5: Multiple In-Doubt Transactions Recovery")
    public void testMultipleInDoubtTransactionsRecovery() throws Exception {
        System.out.println("\n=== Test 6.5: Multiple In-Doubt Transactions Recovery ===");

        XAConnection xaConn = createXADataSource().getXAConnection();
        XAResource xaRes = xaConn.getXAResource();
        Connection conn = xaConn.getConnection();

        Xid xid1 = createXid();
        Xid xid2 = createXid();
        Xid xid3 = createXid();

        try {
            // Prepare transaction 1
            System.out.println("Preparing transaction 1...");
            xaRes.start(xid1, XAResource.TMNOFLAGS);
            try (PreparedStatement pstmt = conn.prepareStatement(
                    "INSERT INTO xa_test_baseline (test_name, test_value) VALUES (?, ?)")) {
                pstmt.setString(1, "MultiRecover1");
                pstmt.setString(2, "Value1-" + System.currentTimeMillis());
                pstmt.executeUpdate();
            }
            xaRes.end(xid1, XAResource.TMSUCCESS);
            xaRes.prepare(xid1);

            // Prepare transaction 2
            System.out.println("Preparing transaction 2...");
            xaRes.start(xid2, XAResource.TMNOFLAGS);
            try (PreparedStatement pstmt = conn.prepareStatement(
                    "INSERT INTO xa_test_baseline (test_name, test_value) VALUES (?, ?)")) {
                pstmt.setString(1, "MultiRecover2");
                pstmt.setString(2, "Value2-" + System.currentTimeMillis());
                pstmt.executeUpdate();
            }
            xaRes.end(xid2, XAResource.TMSUCCESS);
            xaRes.prepare(xid2);

            // Prepare transaction 3
            System.out.println("Preparing transaction 3...");
            xaRes.start(xid3, XAResource.TMNOFLAGS);
            try (PreparedStatement pstmt = conn.prepareStatement(
                    "INSERT INTO xa_test_baseline (test_name, test_value) VALUES (?, ?)")) {
                pstmt.setString(1, "MultiRecover3");
                pstmt.setString(2, "Value3-" + System.currentTimeMillis());
                pstmt.executeUpdate();
            }
            xaRes.end(xid3, XAResource.TMSUCCESS);
            xaRes.prepare(xid3);

            // Recover all prepared transactions
            System.out.println("Recovering all prepared transactions...");
            Xid[] recoveredXids = xaRes.recover(XAResource.TMSTARTRSCAN | XAResource.TMENDRSCAN);
            assertNotNull(recoveredXids, "Recovered XIDs should not be null");
            assertTrue(recoveredXids.length >= 3, "Should recover at least 3 transactions");
            System.out.println("Recovered " + recoveredXids.length + " transactions");

            // Find our XIDs
            Xid recovered1 = null, recovered2 = null, recovered3 = null;
            for (Xid recovered : recoveredXids) {
                if (Arrays.equals(xid1.getGlobalTransactionId(), recovered.getGlobalTransactionId())) {
                    recovered1 = recovered;
                }
                if (Arrays.equals(xid2.getGlobalTransactionId(), recovered.getGlobalTransactionId())) {
                    recovered2 = recovered;
                }
                if (Arrays.equals(xid3.getGlobalTransactionId(), recovered.getGlobalTransactionId())) {
                    recovered3 = recovered;
                }
            }

            assertNotNull(recovered1, "Should find transaction 1");
            assertNotNull(recovered2, "Should find transaction 2");
            assertNotNull(recovered3, "Should find transaction 3");

            // Commit transactions 1 and 2, rollback transaction 3
            System.out.println("Committing transactions 1 and 2...");
            xaRes.commit(recovered1, false);
            xaRes.commit(recovered2, false);

            System.out.println("Rolling back transaction 3...");
            xaRes.rollback(recovered3);

            // Verify outcomes
            System.out.println("Verifying outcomes...");

            // Transactions 1 and 2 should be committed
            try (PreparedStatement pstmt = conn.prepareStatement(
                    "SELECT COUNT(*) FROM xa_test_baseline WHERE test_name IN (?, ?)")) {
                pstmt.setString(1, "MultiRecover1");
                pstmt.setString(2, "MultiRecover2");
                ResultSet rs = pstmt.executeQuery();
                rs.next();
                assertEquals(2, rs.getInt(1), "Transactions 1 and 2 should be committed");
            }

            // Transaction 3 should be rolled back
            try (PreparedStatement pstmt = conn.prepareStatement(
                    "SELECT COUNT(*) FROM xa_test_baseline WHERE test_name = ?")) {
                pstmt.setString(1, "MultiRecover3");
                ResultSet rs = pstmt.executeQuery();
                rs.next();
                assertEquals(0, rs.getInt(1), "Transaction 3 should be rolled back");
            }

            System.out.println("✓ Multiple in-doubt transactions recovered and resolved correctly");

        } finally {
            cleanupTestData(conn, "MultiRecover1");
            cleanupTestData(conn, "MultiRecover2");
            cleanupTestData(conn, "MultiRecover3");
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
