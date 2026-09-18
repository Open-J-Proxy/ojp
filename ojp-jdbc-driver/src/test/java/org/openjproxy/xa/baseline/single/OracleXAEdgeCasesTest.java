package org.openjproxy.xa.baseline.single;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.openjproxy.xa.baseline.common.XATestBase;
import org.openjproxy.xa.baseline.containers.OracleXAContainer;
import org.openjproxy.xa.baseline.containers.OracleXATestContainer;

import javax.sql.XAConnection;
import javax.sql.XADataSource;
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 5: Oracle XA Edge Cases and Protocol Violations Test Suite
 * 
 * Tests 33 edge cases categorized by priority:
 * - 15 Protocol Violations (HIGH priority)
 * - 8 Resource Lifecycle Violations (HIGH priority)
 * - 10 Common Developer Mistakes (HIGH priority)
 * 
 * These tests validate that Oracle correctly handles error conditions and protocol violations
 * according to the XA specification. Tests establish baseline behavior for comparison with OJP.
 * 
 * These tests are disabled by default and only run when -DenableOracleTests=true
 */
@EnabledIf("org.openjproxy.xa.baseline.containers.OracleXATestContainer#isEnabled")
public class OracleXAEdgeCasesTest extends XATestBase {

    @Override
    protected String getDatabaseType() {
        return "Oracle";
    }

    @Override
    protected XADataSource createXADataSource() throws SQLException {
        return OracleXABasicTest.staticXADataSource;
    }

    // ===========================================================================================
    // PROTOCOL VIOLATIONS (15 tests - HIGH priority)
    // ===========================================================================================

    /**
     * Test Case 3.1: Start Before Previous Transaction Ended
     * Call start() with new XID while previous transaction still active
     * Expected: XAException(XAER_PROTO)
     */
    @Test
    void testStartBeforePreviousTransactionEnded() throws Exception {
        XAConnection xaConn = xaConnection;
        XAResource xaRes = xaConn.getXAResource();
        
        Xid xid1 = createXid();
        Xid xid2 = createXid();
        
        // Start first transaction
        xaRes.start(xid1, XAResource.TMNOFLAGS);
        
        // Try to start second transaction without ending first
        XAException exception = assertThrows(XAException.class, () -> {
            xaRes.start(xid2, XAResource.TMNOFLAGS);
        });
        
        // Should be protocol error
        assertEquals(XAException.XAER_PROTO, exception.errorCode,
            "Starting new transaction before ending previous should throw XAER_PROTO");
        
        // Cleanup
        xaRes.end(xid1, XAResource.TMFAIL);
        xaRes.rollback(xid1);
    }

    /**
     * Test Case 3.2: End Before Start
     * Call end() without calling start() first
     * Expected: XAException(XAER_PROTO or XAER_NOTA)
     */
    @Test
    void testEndBeforeStart() throws Exception {
        XAConnection xaConn = xaConnection;
        XAResource xaRes = xaConn.getXAResource();
        
        Xid xid = createXid();
        
        // Try to end without start
        XAException exception = assertThrows(XAException.class, () -> {
            xaRes.end(xid, XAResource.TMSUCCESS);
        });
        
        // Should be protocol error or not found
        assertTrue(exception.errorCode == XAException.XAER_PROTO || 
                   exception.errorCode == XAException.XAER_NOTA,
            "Ending non-existent transaction should throw XAER_PROTO or XAER_NOTA, got: " + exception.errorCode);
    }

    /**
     * Test Case 3.3: Prepare Before End
     * Call prepare() without calling end() first
     * Expected: XAException(XAER_PROTO)
     */
    @Test
    void testPrepareBeforeEnd() throws Exception {
        XAConnection xaConn = xaConnection;
        XAResource xaRes = xaConn.getXAResource();
        Connection conn = xaConn.getConnection();
        
        Xid xid = createXid();
        
        // Start transaction
        xaRes.start(xid, XAResource.TMNOFLAGS);
        
        // Try to prepare without end
        XAException exception = assertThrows(XAException.class, () -> {
            xaRes.prepare(xid);
        });
        
        assertEquals(XAException.XAER_PROTO, exception.errorCode,
            "Preparing before end should throw XAER_PROTO");
        
        // Cleanup
        xaRes.end(xid, XAResource.TMFAIL);
        xaRes.rollback(xid);
    }

    /**
     * Test Case 3.4: Commit Without Prepare (Two-Phase Mode)
     * Call commit(xid, false) without calling prepare() first
     * Expected: XAException(XAER_PROTO) OR auto-prepare (database-specific)
     */
    @Test
    void testCommitWithoutPrepare() throws Exception {
        XAConnection xaConn = xaConnection;
        XAResource xaRes = xaConn.getXAResource();
        Connection conn = xaConn.getConnection();
        
        Xid xid = createXid();
        
        // Start and end transaction
        xaRes.start(xid, XAResource.TMNOFLAGS);
        insertTestData(conn, "test-commit-without-prepare", 1);
        xaRes.end(xid, XAResource.TMSUCCESS);
        
        // Try to commit without prepare (two-phase mode)
        try {
            xaRes.commit(xid, false);
            // Oracle may auto-prepare or throw exception - document behavior
            System.out.println("Oracle auto-prepared transaction (no exception thrown)");
        } catch (XAException e) {
            assertEquals(XAException.XAER_PROTO, e.errorCode,
                "Committing without prepare should throw XAER_PROTO");
            // Cleanup
            xaRes.rollback(xid);
        }
    }

    /**
     * Test Case 3.5: Double Prepare
     * Call prepare() twice on same XID
     * Expected: XAException(XAER_PROTO or XAER_NOTA)
     */
    @Test
    void testDoublePrepare() throws Exception {
        XAConnection xaConn = xaConnection;
        XAResource xaRes = xaConn.getXAResource();
        Connection conn = xaConn.getConnection();
        
        Xid xid = createXid();
        
        // Complete first prepare
        xaRes.start(xid, XAResource.TMNOFLAGS);
        insertTestData(conn, "test-double-prepare", 1);
        xaRes.end(xid, XAResource.TMSUCCESS);
        int result = xaRes.prepare(xid);
        
        // Try to prepare again
        XAException exception = assertThrows(XAException.class, () -> {
            xaRes.prepare(xid);
        });
        
        assertTrue(exception.errorCode == XAException.XAER_PROTO || 
                   exception.errorCode == XAException.XAER_NOTA,
            "Double prepare should throw XAER_PROTO or XAER_NOTA");
        
        // Cleanup
        if (result != XAResource.XA_RDONLY) {
            xaRes.rollback(xid);
        }
    }

    /**
     * Test Case 3.6: Double Commit
     * Call commit() twice on same XID
     * Expected: XAException(XAER_NOTA) - XID not found after first commit
     */
    @Test
    void testDoubleCommit() throws Exception {
        XAConnection xaConn = xaConnection;
        XAResource xaRes = xaConn.getXAResource();
        Connection conn = xaConn.getConnection();
        
        Xid xid = createXid();
        
        // Complete first commit
        xaRes.start(xid, XAResource.TMNOFLAGS);
        insertTestData(conn, "test-double-commit", 1);
        xaRes.end(xid, XAResource.TMSUCCESS);
        int result = xaRes.prepare(xid);
        if (result != XAResource.XA_RDONLY) {
            xaRes.commit(xid, false);
        }
        
        // Try to commit again
        XAException exception = assertThrows(XAException.class, () -> {
            xaRes.commit(xid, false);
        });
        
        assertEquals(XAException.XAER_NOTA, exception.errorCode,
            "Double commit should throw XAER_NOTA (XID not found)");
    }

    /**
     * Test Case 3.7: Reuse XID After Commit
     * Try to start new transaction with previously committed XID
     * Expected: XAException(XAER_DUPID or XAER_NOTA)
     */
    @Test
    void testReuseXidAfterCommit() throws Exception {
        XAConnection xaConn = xaConnection;
        XAResource xaRes = xaConn.getXAResource();
        Connection conn = xaConn.getConnection();
        
        Xid xid = createXid();
        
        // Complete first transaction
        xaRes.start(xid, XAResource.TMNOFLAGS);
        insertTestData(conn, "test-reuse-xid", 1);
        xaRes.end(xid, XAResource.TMSUCCESS);
        xaRes.commit(xid, true); // One-phase commit
        
        // Try to reuse same XID
        XAException exception = assertThrows(XAException.class, () -> {
            xaRes.start(xid, XAResource.TMNOFLAGS);
        });
        
        assertTrue(exception.errorCode == XAException.XAER_DUPID || 
                   exception.errorCode == XAException.XAER_NOTA,
            "Reusing XID should throw XAER_DUPID or XAER_NOTA");
    }

    /**
     * Test Case 3.8: Double Rollback
     * Call rollback() twice on same XID
     * Expected: XAException(XAER_NOTA)
     */
    @Test
    void testDoubleRollback() throws Exception {
        XAConnection xaConn = xaConnection;
        XAResource xaRes = xaConn.getXAResource();
        Connection conn = xaConn.getConnection();
        
        Xid xid = createXid();
        
        // Complete first rollback
        xaRes.start(xid, XAResource.TMNOFLAGS);
        insertTestData(conn, "test-double-rollback", 1);
        xaRes.end(xid, XAResource.TMSUCCESS);
        xaRes.rollback(xid);
        
        // Try to rollback again
        XAException exception = assertThrows(XAException.class, () -> {
            xaRes.rollback(xid);
        });
        
        assertEquals(XAException.XAER_NOTA, exception.errorCode,
            "Double rollback should throw XAER_NOTA");
    }

    /**
     * Test Case 3.9: Rollback After Commit
     * Try to rollback a committed transaction
     * Expected: XAException(XAER_NOTA)
     */
    @Test
    void testRollbackAfterCommit() throws Exception {
        XAConnection xaConn = xaConnection;
        XAResource xaRes = xaConn.getXAResource();
        Connection conn = xaConn.getConnection();
        
        Xid xid = createXid();
        
        // Commit transaction
        xaRes.start(xid, XAResource.TMNOFLAGS);
        insertTestData(conn, "test-rollback-after-commit", 1);
        xaRes.end(xid, XAResource.TMSUCCESS);
        xaRes.commit(xid, true);
        
        // Try to rollback
        XAException exception = assertThrows(XAException.class, () -> {
            xaRes.rollback(xid);
        });
        
        assertEquals(XAException.XAER_NOTA, exception.errorCode,
            "Rollback after commit should throw XAER_NOTA");
    }

    /**
     * Test Case 3.10: Commit After Rollback
     * Try to commit a rolled-back transaction
     * Expected: XAException(XAER_NOTA)
     */
    @Test
    void testCommitAfterRollback() throws Exception {
        XAConnection xaConn = xaConnection;
        XAResource xaRes = xaConn.getXAResource();
        Connection conn = xaConn.getConnection();
        
        Xid xid = createXid();
        
        // Rollback transaction
        xaRes.start(xid, XAResource.TMNOFLAGS);
        insertTestData(conn, "test-commit-after-rollback", 1);
        xaRes.end(xid, XAResource.TMSUCCESS);
        xaRes.rollback(xid);
        
        // Try to commit
        XAException exception = assertThrows(XAException.class, () -> {
            xaRes.commit(xid, true);
        });
        
        assertEquals(XAException.XAER_NOTA, exception.errorCode,
            "Commit after rollback should throw XAER_NOTA");
    }

    /**
     * Test Case 3.11: Start with TMJOIN Without Existing Transaction
     * Use TMJOIN flag without an existing transaction to join
     * Expected: XAException(XAER_NOTA or XAER_PROTO)
     */
    @Test
    void testJoinWithoutExistingTransaction() throws Exception {
        XAConnection xaConn = xaConnection;
        XAResource xaRes = xaConn.getXAResource();
        
        Xid xid = createXid();
        
        // Try to join non-existent transaction
        XAException exception = assertThrows(XAException.class, () -> {
            xaRes.start(xid, XAResource.TMJOIN);
        });
        
        assertTrue(exception.errorCode == XAException.XAER_NOTA || 
                   exception.errorCode == XAException.XAER_PROTO,
            "TMJOIN without existing transaction should throw XAER_NOTA or XAER_PROTO");
    }

    /**
     * Test Case 3.12: Resume Without Suspend
     * Use TMRESUME flag without previous TMSUSPEND
     * Expected: XAException(XAER_PROTO)
     */
    @Test
    void testResumeWithoutSuspend() throws Exception {
        XAConnection xaConn = xaConnection;
        XAResource xaRes = xaConn.getXAResource();
        
        Xid xid = createXid();
        
        // Try to resume without suspend
        XAException exception = assertThrows(XAException.class, () -> {
            xaRes.start(xid, XAResource.TMRESUME);
        });
        
        assertTrue(exception.errorCode == XAException.XAER_PROTO || 
                   exception.errorCode == XAException.XAER_NOTA,
            "TMRESUME without TMSUSPEND should throw XAER_PROTO or XAER_NOTA");
    }

    /**
     * Test Case 3.13: Multiple End Calls
     * Call end() multiple times on same transaction
     * Expected: XAException(XAER_PROTO)
     */
    @Test
    void testMultipleEndCalls() throws Exception {
        XAConnection xaConn = xaConnection;
        XAResource xaRes = xaConn.getXAResource();
        
        Xid xid = createXid();
        
        // Start and end once
        xaRes.start(xid, XAResource.TMNOFLAGS);
        xaRes.end(xid, XAResource.TMSUCCESS);
        
        // Try to end again
        XAException exception = assertThrows(XAException.class, () -> {
            xaRes.end(xid, XAResource.TMSUCCESS);
        });
        
        assertTrue(exception.errorCode == XAException.XAER_PROTO || 
                   exception.errorCode == XAException.XAER_NOTA,
            "Multiple end calls should throw XAER_PROTO or XAER_NOTA");
        
        // Cleanup
        xaRes.rollback(xid);
    }

    /**
     * Test Case 3.14: SQL Operations After End But Before Start
     * Execute SQL when no XA transaction is active
     * Expected: May succeed (auto-commit) or fail - document behavior
     */
    @Test
    void testSqlOperationsWithoutActiveTransaction() throws Exception {
        XAConnection xaConn = xaConnection;
        XAResource xaRes = xaConn.getXAResource();
        Connection conn = xaConn.getConnection();
        
        // Verify auto-commit is disabled on XA connection
        assertFalse(conn.getAutoCommit(), "Auto-commit should be disabled on XA connection");
        
        // Try to execute SQL without active XA transaction
        // Behavior may vary - Oracle typically requires an active transaction
        try {
            insertTestData(conn, "test-no-xa-transaction", 1);
            // If no exception, check if data was committed (shouldn't be with auto-commit off)
            assertFalse(verifyDataExists(conn, "test-no-xa-transaction"),
                "Data should not be committed without XA transaction");
        } catch (SQLException e) {
            // Some databases may throw exception - document this behavior
            System.out.println("Oracle threw exception for SQL without XA transaction: " + e.getMessage());
        }
    }

    /**
     * Test Case 3.15: Prepare on Read-Only Transaction Then Commit
     * Call commit() after receiving XA_RDONLY from prepare()
     * Expected: XAException(XAER_NOTA) - transaction already completed
     */
    @Test
    void testCommitAfterReadOnlyPrepare() throws Exception {
        XAConnection xaConn = xaConnection;
        XAResource xaRes = xaConn.getXAResource();
        Connection conn = xaConn.getConnection();
        
        Xid xid = createXid();
        
        // Create read-only transaction
        xaRes.start(xid, XAResource.TMNOFLAGS);
        // Only SELECT, no modifications
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM xa_test_baseline WHERE test_name = ?")) {
            ps.setString(1, "anything");
            ps.executeQuery();
        }
        xaRes.end(xid, XAResource.TMSUCCESS);
        
        int result = xaRes.prepare(xid);
        
        if (result == XAResource.XA_RDONLY) {
            // Transaction already committed, try to commit again
            XAException exception = assertThrows(XAException.class, () -> {
                xaRes.commit(xid, false);
            });
            
            assertEquals(XAException.XAER_NOTA, exception.errorCode,
                "Commit after XA_RDONLY should throw XAER_NOTA");
        } else {
            // If not read-only, just cleanup
            xaRes.commit(xid, false);
        }
    }

    // ===========================================================================================
    // RESOURCE LIFECYCLE VIOLATIONS (8 tests - HIGH priority)
    // ===========================================================================================

    /**
     * Test Case 4.1: Manual Commit on XA Connection
     * Call connection.commit() while XA transaction is active
     * Expected: SQLException
     */
    @Test
    void testManualCommitDuringXaTransaction() throws Exception {
        XAConnection xaConn = xaConnection;
        XAResource xaRes = xaConn.getXAResource();
        Connection conn = xaConn.getConnection();
        
        Xid xid = createXid();
        
        xaRes.start(xid, XAResource.TMNOFLAGS);
        insertTestData(conn, "test-manual-commit", 1);
        
        // Try to manually commit
        SQLException exception = assertThrows(SQLException.class, () -> {
            conn.commit();
        });
        
        assertTrue(exception.getMessage().contains("XA") || 
                   exception.getMessage().contains("xa") ||
                   exception.getMessage().contains("transaction"),
            "Manual commit during XA transaction should throw SQLException mentioning XA");
        
        // Cleanup
        xaRes.end(xid, XAResource.TMFAIL);
        xaRes.rollback(xid);
    }

    /**
     * Test Case 4.2: SetAutoCommit(true) During XA Transaction
     * Try to enable auto-commit while XA transaction is active
     * Expected: SQLException or silently ignored
     */
    @Test
    void testSetAutoCommitTrueDuringXaTransaction() throws Exception {
        XAConnection xaConn = xaConnection;
        XAResource xaRes = xaConn.getXAResource();
        Connection conn = xaConn.getConnection();
        
        Xid xid = createXid();
        
        xaRes.start(xid, XAResource.TMNOFLAGS);
        
        // Try to enable auto-commit
        try {
            conn.setAutoCommit(true);
            // If no exception, verify it was actually ignored
            assertFalse(conn.getAutoCommit(),
                "Auto-commit should remain disabled during XA transaction");
        } catch (SQLException e) {
            // Exception is acceptable - document behavior
            assertTrue(e.getMessage().contains("XA") || 
                       e.getMessage().contains("xa") ||
                       e.getMessage().contains("transaction"),
                "SQLException should mention XA transaction");
        }
        
        // Cleanup
        xaRes.end(xid, XAResource.TMFAIL);
        xaRes.rollback(xid);
    }

    /**
     * Test Case 4.3: Use Connection After Close
     * Execute SQL after closing connection
     * Expected: SQLException
     */
    @Test
    void testUseConnectionAfterClose() throws Exception {
        XAConnection xaConn = xaConnection;
        Connection conn = xaConn.getConnection();
        
        conn.close();
        
        // Try to use closed connection
        SQLException exception = assertThrows(SQLException.class, () -> {
            insertTestData(conn, "test-closed-connection", 1);
        });
        
        assertTrue(exception.getMessage().toLowerCase().contains("closed"),
            "Using closed connection should throw SQLException mentioning 'closed'");
    }

    /**
     * Test Case 4.4: XA Operations After Logical Connection Close
     * Close logical connection but try to continue using XAResource
     * Expected: May work or fail - document behavior per database
     */
    @Test
    void testXaOperationsAfterLogicalConnectionClose() throws Exception {
        XAConnection xaConn = xaConnection;
        XAResource xaRes = xaConn.getXAResource();
        Connection conn = xaConn.getConnection();
        
        Xid xid = createXid();
        
        xaRes.start(xid, XAResource.TMNOFLAGS);
        insertTestData(conn, "test-xa-after-close", 1);
        xaRes.end(xid, XAResource.TMSUCCESS);
        
        // Close logical connection
        conn.close();
        
        // Try to continue XA operations
        try {
            xaRes.prepare(xid);
            xaRes.commit(xid, false);
            System.out.println("Oracle allows XA operations after logical connection close");
        } catch (XAException e) {
            System.out.println("Oracle prevents XA operations after logical connection close: " + e.getMessage());
            // Cleanup if failed
            try {
                xaRes.rollback(xid);
            } catch (XAException rollbackEx) {
                // Ignore cleanup failure
            }
        }
    }

    /**
     * Test Case 4.5: Close Connection With Active Transaction
     * Close connection without ending XA transaction
     * Expected: Auto-rollback expected
     */
    @Test
    void testCloseConnectionWithActiveTransaction() throws Exception {
        XAConnection xaConn = xaConnection;
        XAResource xaRes = xaConn.getXAResource();
        Connection conn = xaConn.getConnection();
        
        Xid xid = createXid();
        
        xaRes.start(xid, XAResource.TMNOFLAGS);
        insertTestData(conn, "test-close-active-tx", 1);
        // Don't call end()
        
        // Close connection with active transaction
        conn.close();
        
        // Try to end transaction - should fail
        try {
            xaRes.end(xid, XAResource.TMSUCCESS);
            fail("Should not be able to end transaction after connection close");
        } catch (XAException e) {
            // Expected - transaction was rolled back
            assertTrue(e.errorCode == XAException.XAER_NOTA || 
                       e.errorCode == XAException.XAER_PROTO ||
                       e.errorCode == XAException.XAER_RMFAIL,
                "Expected XAER_NOTA, XAER_PROTO, or XAER_RMFAIL after closing with active transaction");
        }
    }

    /**
     * Test Case 4.6: Close XAConnection With Prepared Transaction
     * Close XAConnection while transaction is in prepared state
     * Expected: Prepared transaction persists, can be recovered
     */
    @Test
    void testCloseXaConnectionWithPreparedTransaction() throws Exception {
        XAConnection xaConn1 = xaConnection;
        XAResource xaRes1 = xaConn1.getXAResource();
        Connection conn1 = xaConn1.getConnection();
        
        Xid xid = createXid();
        
        // Prepare transaction
        xaRes1.start(xid, XAResource.TMNOFLAGS);
        insertTestData(conn1, "test-close-prepared", 1);
        xaRes1.end(xid, XAResource.TMSUCCESS);
        xaRes1.prepare(xid);
        
        // Close connection
        conn1.close();
        xaConn1.close();
        
        // Open new connection and recover
        XAConnection xaConn2 = xaConnection;
        XAResource xaRes2 = xaConn2.getXAResource();
        
        Xid[] recovered = xaRes2.recover(XAResource.TMSTARTRSCAN | XAResource.TMENDRSCAN);
        
        // Find our transaction
        boolean found = false;
        for (Xid recoveredXid : recovered) {
            if (xidsMatch(xid, recoveredXid)) {
                found = true;
                // Commit recovered transaction
                xaRes2.commit(recoveredXid, false);
                break;
            }
        }
        
        assertTrue(found, "Prepared transaction should persist after XAConnection close");
        
        // Verify data was committed
        assertTrue(verifyDataExists(xaConn2.getConnection(), "test-close-prepared"),
            "Data should be committed after recovery and commit");
    }

    /**
     * Test Case 4.7: Use XAResource After XAConnection Close
     * Try to use XAResource after closing XAConnection
     * Expected: XAException or SQLException
     */
    @Test
    void testUseXaResourceAfterXaConnectionClose() throws Exception {
        XAConnection xaConn = xaConnection;
        XAResource xaRes = xaConn.getXAResource();
        
        Xid xid = createXid();
        
        // Close XAConnection
        xaConn.close();
        
        // Try to use XAResource
        assertThrows(Exception.class, () -> {
            xaRes.start(xid, XAResource.TMNOFLAGS);
        }, "Using XAResource after XAConnection close should throw exception");
    }

    /**
     * Test Case 4.8: Resource Leak - Many Unclosed Connections
     * Create many connections without closing them
     * Expected: Eventually hit connection pool limit
     * 
     * Note: This test is commented out as it's resource-intensive and may cause issues
     * in CI environments. Uncomment for local testing if needed.
     */
    // @Test
    // void testResourceLeakManyUnclosedConnections() throws Exception {
    //     List<XAConnection> connections = new ArrayList<>();
    //     
    //     try {
    //         // Try to create many connections
    //         for (int i = 0; i < 100; i++) {
    //             connections.add(xaConnection);
    //         }
    //         
    //         // If we got here, connection pool is large or unlimited
    //         System.out.println("Created 100 connections without hitting limit");
    //     } catch (SQLException e) {
    //         // Expected - hit connection limit
    //         assertTrue(connections.size() > 0,
    //             "Should have created some connections before hitting limit");
    //         System.out.println("Hit connection limit after " + connections.size() + " connections");
    //     } finally {
    //         // Cleanup
    //         for (XAConnection conn : connections) {
    //             try {
    //                 conn.close();
    //             } catch (Exception e) {
    //                 // Ignore cleanup errors
    //             }
    //         }
    //     }
    // }

    // ===========================================================================================
    // COMMON DEVELOPER MISTAKES (10 tests - HIGH priority)
    // ===========================================================================================

    /**
     * Test Case 5.1: Not Checking Prepare Result
     * Always call commit() after prepare() without checking for XA_RDONLY
     * Expected: May throw XAER_NOTA if transaction was read-only
     */
    @Test
    void testNotCheckingPrepareResult() throws Exception {
        XAConnection xaConn = xaConnection;
        XAResource xaRes = xaConn.getXAResource();
        Connection conn = xaConn.getConnection();
        
        Xid xid = createXid();
        
        // Create potentially read-only transaction
        xaRes.start(xid, XAResource.TMNOFLAGS);
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM xa_test_baseline")) {
            ps.executeQuery();
        }
        xaRes.end(xid, XAResource.TMSUCCESS);
        
        int result = xaRes.prepare(xid);
        
        // Mistake: Not checking result, always calling commit
        if (result == XAResource.XA_RDONLY) {
            // This is the mistake - trying to commit read-only transaction
            XAException exception = assertThrows(XAException.class, () -> {
                xaRes.commit(xid, false);
            });
            assertEquals(XAException.XAER_NOTA, exception.errorCode,
                "Commit after XA_RDONLY should throw XAER_NOTA");
        } else {
            // Not read-only, commit succeeds
            xaRes.commit(xid, false);
        }
    }

    /**
     * Test Case 5.2: Mixing One-Phase and Two-Phase Commit
     * Call prepare() then commit() with onePhase=true
     * Expected: XAException (one-phase should not follow prepare)
     */
    @Test
    void testMixingOnePhaseTwoPhaseCommit() throws Exception {
        XAConnection xaConn = xaConnection;
        XAResource xaRes = xaConn.getXAResource();
        Connection conn = xaConn.getConnection();
        
        Xid xid = createXid();
        
        xaRes.start(xid, XAResource.TMNOFLAGS);
        insertTestData(conn, "test-mixed-commit", 1);
        xaRes.end(xid, XAResource.TMSUCCESS);
        
        int result = xaRes.prepare(xid);
        
        if (result != XAResource.XA_RDONLY) {
            // Mistake: Using one-phase commit after prepare
            try {
                xaRes.commit(xid, true); // onePhase=true after prepare
                // Some databases may allow this, others may throw exception
                System.out.println("Oracle allowed one-phase commit after prepare");
            } catch (XAException e) {
                assertTrue(e.errorCode == XAException.XAER_PROTO || 
                           e.errorCode == XAException.XAER_NOTA,
                    "One-phase commit after prepare should throw XAER_PROTO or XAER_NOTA");
            }
        }
    }

    /**
     * Test Case 5.3: Non-Unique Global Transaction IDs
     * Reuse global transaction ID across different transactions
     * Expected: XAException(XAER_DUPID)
     * 
     * Note: This is similar to testReuseXidAfterCommit but focuses on concurrent transactions
     */
    @Test
    void testNonUniqueGlobalTransactionIds() throws Exception {
        XAConnection xaConn1 = xaConnection;
        XAConnection xaConn2 = xaConnection;
        XAResource xaRes1 = xaConn1.getXAResource();
        XAResource xaRes2 = xaConn2.getXAResource();
        
        Xid xid = createXid(); // Same XID for both
        
        // Start first transaction
        xaRes1.start(xid, XAResource.TMNOFLAGS);
        
        // Try to start second transaction with same XID
        XAException exception = assertThrows(XAException.class, () -> {
            xaRes2.start(xid, XAResource.TMNOFLAGS);
        });
        
        assertEquals(XAException.XAER_DUPID, exception.errorCode,
            "Reusing XID in concurrent transaction should throw XAER_DUPID");
        
        // Cleanup
        xaRes1.end(xid, XAResource.TMFAIL);
        xaRes1.rollback(xid);
    }

    /**
     * Test Case 5.4: XID Component Too Long
     * Create XID with globalTransactionId > 64 bytes
     * Expected: XAException or truncation
     */
    @Test
    void testXidComponentTooLong() throws Exception {
        XAConnection xaConn = xaConnection;
        XAResource xaRes = xaConn.getXAResource();
        
        // Create XID with globalTransactionId > 64 bytes
        byte[] gtrid = new byte[100]; // Exceeds 64 byte limit
        for (int i = 0; i < gtrid.length; i++) {
            gtrid[i] = (byte) i;
        }
        byte[] bqual = new byte[10];
        
        Xid invalidXid = new javax.transaction.xa.Xid() {
            @Override
            public int getFormatId() {
                return 1;
            }
            
            @Override
            public byte[] getGlobalTransactionId() {
                return gtrid;
            }
            
            @Override
            public byte[] getBranchQualifier() {
                return bqual;
            }
        };
        
        // Try to use oversized XID
        try {
            xaRes.start(invalidXid, XAResource.TMNOFLAGS);
            xaRes.end(invalidXid, XAResource.TMSUCCESS);
            xaRes.rollback(invalidXid);
            // Some databases may allow this
        } catch (XAException e) {
            // Expected: XID exceeds size limit
            assertTrue(e.errorCode == XAException.XAER_INVAL || e.errorCode == XAException.XAER_NOTA);
        }
    }

    /**
     * Test Case 5.5: Using TMSUCCESS Flag on Failed Transaction
     * Use TMSUCCESS even though transaction encountered errors
     * Expected: May succeed but leads to data inconsistency
     */
    @Test
    void testTmsSuccessOnFailedTransaction() throws Exception {
        XAConnection xaConn = xaConnection;
        XAResource xaRes = xaConn.getXAResource();
        Connection conn = xaConn.getConnection();
        
        Xid xid = createXid();
        
        xaRes.start(xid, XAResource.TMNOFLAGS);
        
        // Cause an error in transaction
        try {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO non_existent_table VALUES (?)")) {
                ps.setString(1, "test");
                ps.executeUpdate();
            }
            fail("Should have thrown SQLException for non-existent table");
        } catch (SQLException e) {
            // Expected error
        }
        
        // Mistake: Using TMSUCCESS despite error
        // Should use TMFAIL instead
        xaRes.end(xid, XAResource.TMSUCCESS);
        
        // Transaction should still be rollback-only
        // Prepare may fail or return error
        try {
            int result = xaRes.prepare(xid);
            // If prepare succeeds, rollback
            if (result != XAResource.XA_RDONLY) {
                xaRes.rollback(xid);
            }
        } catch (XAException e) {
            // Expected - transaction is in bad state
            xaRes.rollback(xid);
        }
    }

    /**
     * Test Case 5.6: Forgetting to End Transaction Before Timeout
     * Let transaction timeout without calling end()
     * Expected: Transaction automatically rolled back
     * 
     * Note: This test takes time to execute (waits for timeout)
     */
    @Test
    void testForgettingToEndTransactionBeforeTimeout() throws Exception {
        XAConnection xaConn = xaConnection;
        XAResource xaRes = xaConn.getXAResource();
        Connection conn = xaConn.getConnection();
        
        Xid xid = createXid();
        
        // Set short timeout
        xaRes.setTransactionTimeout(2); // 2 seconds
        
        xaRes.start(xid, XAResource.TMNOFLAGS);
        insertTestData(conn, "test-timeout-forget-end", 1);
        
        // Wait for timeout (mistake: not calling end)
        Thread.sleep(3000); // Wait 3 seconds
        
        // Try to end - should fail due to timeout
        XAException exception = assertThrows(XAException.class, () -> {
            xaRes.end(xid, XAResource.TMSUCCESS);
        });
        
        assertTrue(exception.errorCode == XAException.XA_RBTIMEOUT || 
                   exception.errorCode == XAException.XAER_NOTA ||
                   exception.errorCode == XAException.XAER_PROTO,
            "Transaction should timeout, got error code: " + exception.errorCode);
        
        // Try to rollback
        try {
            xaRes.rollback(xid);
        } catch (XAException e) {
            // May already be rolled back
        }
        
        // Reset timeout
        xaRes.setTransactionTimeout(0);
    }

    /**
     * Test Case 5.7: Not Handling Heuristic Outcomes
     * Ignore XA_HEUR* exceptions, don't call forget()
     * Expected: Heuristic decisions remain in transaction log
     * 
     * Note: Difficult to reliably trigger heuristic outcomes in testing
     * This test documents the pattern rather than forcing a heuristic outcome
     */
    @Test
    void testNotHandlingHeuristicOutcomes() throws Exception {
        XAConnection xaConn = xaConnection;
        XAResource xaRes = xaConn.getXAResource();
        Connection conn = xaConn.getConnection();
        
        Xid xid = createXid();
        
        // Normal transaction
        xaRes.start(xid, XAResource.TMNOFLAGS);
        insertTestData(conn, "test-heuristic", 1);
        xaRes.end(xid, XAResource.TMSUCCESS);
        xaRes.prepare(xid);
        
        try {
            xaRes.commit(xid, false);
            
            // After commit, should call forget() if heuristic outcome occurred
            // This is the pattern developers should follow:
            // xaRes.forget(xid);
            
        } catch (XAException e) {
            if (e.errorCode >= XAException.XA_HEURCOM && 
                e.errorCode <= XAException.XA_HEURMIX) {
                // Heuristic outcome - should call forget()
                xaRes.forget(xid);
            } else {
                throw e;
            }
        }
    }

    /**
     * Test Case 5.8: Assuming isSameRM() Returns True
     * Not checking isSameRM() result before optimization
     * Expected: Optimization may not work as expected
     */
    @Test
    void testAssumingIsSameRmReturnsTrue() throws Exception {
        XAConnection xaConn1 = xaConnection;
        XAConnection xaConn2 = xaConnection;
        XAResource xaRes1 = xaConn1.getXAResource();
        XAResource xaRes2 = xaConn2.getXAResource();
        
        // Check if they're the same RM
        boolean sameRM = xaRes1.isSameRM(xaRes2);
        
        System.out.println("isSameRM result: " + sameRM);
        
        // Developers should check this before assuming optimizations like TMJOIN work
        // For same RM, can use TMJOIN; for different RMs, cannot
        
        if (sameRM) {
            // Can use TMJOIN
            Xid xid = createXid();
            xaRes1.start(xid, XAResource.TMNOFLAGS);
            insertTestData(xaConn1.getConnection(), "test-same-rm", 1);
            xaRes1.end(xid, XAResource.TMSUCCESS);
            
            // Join from second resource
            xaRes2.start(xid, XAResource.TMJOIN);
            insertTestData(xaConn2.getConnection(), "test-same-rm", 2);
            xaRes2.end(xid, XAResource.TMSUCCESS);
            
            // Cleanup
            xaRes1.rollback(xid);
        } else {
            // Cannot use TMJOIN - would need separate XIDs
            System.out.println("Resources are not the same RM - TMJOIN would fail");
        }
    }

    /**
     * Test Case 5.9: Concurrent Access to Single XAResource
     * Use XAResource from multiple threads without synchronization
     * Expected: Undefined behavior, potential corruption
     * 
     * Note: This test is commented out as it's complex and may cause issues
     * Uncomment for specific concurrency testing if needed
     */
    // @Test
    // void testConcurrentAccessToSingleXaResource() throws Exception {
    //     XAConnection xaConn = xaConnection;
    //     XAResource xaRes = xaConn.getXAResource();
    //     
    //     // Concurrent access from multiple threads
    //     // This is a developer mistake and results are undefined
    // }

    /**
     * Test Case 5.10: Not Cleaning Up After Exception
     * Forget to rollback after exception
     * Expected: Transaction left in limbo
     */
    @Test
    void testNotCleaningUpAfterException() throws Exception {
        XAConnection xaConn = xaConnection;
        XAResource xaRes = xaConn.getXAResource();
        Connection conn = xaConn.getConnection();
        
        Xid xid = createXid();
        
        xaRes.start(xid, XAResource.TMNOFLAGS);
        
        try {
            // Cause an exception
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO non_existent_table VALUES (?)")) {
                ps.setString(1, "test");
                ps.executeUpdate();
            }
            fail("Should have thrown SQLException");
        } catch (SQLException e) {
            // Exception occurred
            // Mistake: Not cleaning up (should call end + rollback)
            // This test shows the problem
        }
        
        // Transaction is still active in a bad state
        // Try to end it
        try {
            xaRes.end(xid, XAResource.TMFAIL);
            xaRes.rollback(xid);
        } catch (XAException e) {
            // May fail if transaction is already rolled back by database
            System.out.println("Cleanup after exception failed: " + e.getMessage());
        }
    }

    // ===========================================================================================
    // HELPER METHODS
    // ===========================================================================================

    /**
     * Check if two XIDs match (same format, gtrid, and bqual)
     */
    private boolean xidsMatch(Xid xid1, Xid xid2) {
        if (xid1.getFormatId() != xid2.getFormatId()) {
            return false;
        }
        
        byte[] gtrid1 = xid1.getGlobalTransactionId();
        byte[] gtrid2 = xid2.getGlobalTransactionId();
        if (!java.util.Arrays.equals(gtrid1, gtrid2)) {
            return false;
        }
        
        byte[] bqual1 = xid1.getBranchQualifier();
        byte[] bqual2 = xid2.getBranchQualifier();
        return java.util.Arrays.equals(bqual1, bqual2);
    }
}
