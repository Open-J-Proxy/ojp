package org.openjproxy.xa.baseline.single;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.openjproxy.xa.baseline.common.XATestBase;
import org.openjproxy.xa.baseline.containers.OracleXAContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.XAConnection;
import javax.sql.XADataSource;
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Oracle XA Recovery Tests (Phase 4)
 * 
 * Tests XA recovery functionality using Oracle native JDBC driver (baseline testing).
 * Recovery is critical for handling prepared transactions after crashes or connection loss.
 * 
 * Test Cases:
 * 6.1: Recover Prepared Transactions - List in-doubt transactions
 * 6.2: Recovery After Connection Loss - Recover and complete from new connection
 * 6.3: Recovery Flags - Test TMSTARTRSCAN, TMENDRSCAN, TMNOFLAGS
 * 6.4: Forget Heuristically Completed Transaction - Test forget() operation
 * 6.5: Multiple In-Doubt Transactions Recovery - Recover multiple prepared transactions
 * 
 * Database: Oracle XE 21 (via TestContainers)
 * Driver: Oracle native JDBC driver (oracle.jdbc.xa.client.OracleXADataSource)
 * 
 * These tests are disabled by default and only run when -DenableOracleTests=true
 */
@EnabledIf("org.openjproxy.xa.baseline.containers.OracleXATestContainer#isEnabled")
public class OracleXARecoveryTest extends XATestBase {
    
    private static final Logger logger = LoggerFactory.getLogger(OracleXARecoveryTest.class);
    
    private static XADataSource staticXADataSource;
    
    @BeforeAll
    public static void setUpClass() throws Exception {
        logger.info("=== Starting Oracle XA Recovery Tests (Phase 4) ===");
        logger.info("Using shared Oracle XA Container from singleton...");
        
        // Create XA DataSource using the OracleXAContainer wrapper
        // The wrapper internally uses OracleXATestContainer singleton
        OracleXAContainer oracleContainer = new OracleXAContainer();
        staticXADataSource = oracleContainer.createXADataSource();
        
        logger.info("Oracle XA DataSource created successfully");
    }
    
    @AfterAll
    public static void tearDownClass() {
        logger.info("=== Oracle XA Recovery Tests Complete ===");
        // Note: Singleton container managed by OracleXATestContainer, no need to stop here
    }
    
    @Override
    protected XADataSource createXADataSource() throws SQLException {
        return staticXADataSource;
    }
    
    @Override
    protected String getDatabaseType() {
        return "Oracle";
    }
    
    /**
     * Test Case 6.1: Recover Prepared Transactions
     * 
     * Objective: Verify recover() returns list of prepared transactions
     * 
     * Steps:
     * 1. Start XA transaction
     * 2. Execute INSERT
     * 3. End and prepare transaction (leave it in-doubt)
     * 4. Call recover() to get list of prepared XIDs
     * 5. Verify our XID is in the list
     * 6. Commit the recovered transaction
     * 7. Verify data is committed
     * 
     * Expected Result: recover() lists prepared transactions, can commit them
     */
    @Test
    public void testCase6_1_RecoverPreparedTransactions() throws Exception {
        logger.info("Test Case 6.1: Recover Prepared Transactions");
        
        // Create test table
        String tableName = createTestTable(connection);
        logger.info("Created test table: {}", tableName);
        
        // Create XID with unique identifier
        Xid xid = createXid("test-6.1-recover");
        logger.info("Created XID: {}", xid);
        
        try {
            // Step 1-3: Prepare a transaction (leave it in-doubt)
            xaResource.start(xid, XAResource.TMNOFLAGS);
            logger.info("XA transaction started");
            
            try (Statement stmt = connection.createStatement()) {
                stmt.executeUpdate(
                    String.format("INSERT INTO %s (id, name, value) VALUES (100, 'recover', 10000)", tableName)
                );
                logger.info("Inserted row");
            }
            
            xaResource.end(xid, XAResource.TMSUCCESS);
            logger.info("XA transaction ended");
            
            int prepareResult = xaResource.prepare(xid);
            assertEquals(XAResource.XA_OK, prepareResult, "Prepare should return XA_OK");
            logger.info("Transaction prepared (in-doubt state)");
            
            // Step 4: Call recover() to get list of prepared XIDs
            Xid[] recoveredXids = xaResource.recover(XAResource.TMSTARTRSCAN | XAResource.TMENDRSCAN);
            logger.info("Recovered {} prepared transaction(s)", recoveredXids != null ? recoveredXids.length : 0);
            
            // Step 5: Verify our XID is in the list
            assertNotNull(recoveredXids, "Recovered XIDs should not be null");
            boolean found = false;
            for (Xid recoveredXid : recoveredXids) {
                if (Arrays.equals(xid.getGlobalTransactionId(), recoveredXid.getGlobalTransactionId()) &&
                    Arrays.equals(xid.getBranchQualifier(), recoveredXid.getBranchQualifier())) {
                    found = true;
                    logger.info("Found our prepared transaction in recovery list");
                    break;
                }
            }
            assertTrue(found, "Our XID should be in the recovered list");
            
            // Step 6: Commit the recovered transaction
            xaResource.commit(xid, false);
            logger.info("Recovered transaction committed");
            
            // Step 7: Verify data is committed
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     String.format("SELECT COUNT(*) FROM %s WHERE id = 100", tableName))) {
                
                assertTrue(rs.next(), "Query should return result");
                assertEquals(1, rs.getInt(1), "Should have 1 row (committed after recovery)");
                logger.info("Data verified: row committed after recovery");
            }
            
            logger.info("✓ Test Case 6.1: PASSED - Recover prepared transactions successful");
            
        } finally {
            // Cleanup
            dropTestTable(connection, tableName);
        }
    }
    
    /**
     * Test Case 6.2: Recovery After Connection Loss
     * 
     * Objective: Verify recovery works after simulated connection loss
     * 
     * Steps:
     * 1. Start XA transaction on first connection
     * 2. Execute INSERT and prepare
     * 3. Close first connection (simulate connection loss)
     * 4. Create new connection
     * 5. Call recover() on new connection
     * 6. Verify our XID is recovered
     * 7. Commit from new connection
     * 8. Verify data is committed
     * 
     * Expected Result: New connection can recover and complete prepared transactions
     */
    @Test
    public void testCase6_2_RecoveryAfterConnectionLoss() throws Exception {
        logger.info("Test Case 6.2: Recovery After Connection Loss");
        
        // Create test table
        String tableName = createTestTable(connection);
        logger.info("Created test table: {}", tableName);
        
        // Create XID
        Xid xid = createXid("test-6.2-connloss");
        logger.info("Created XID: {}", xid);
        
        try {
            // Step 1-2: Prepare transaction on first connection
            xaResource.start(xid, XAResource.TMNOFLAGS);
            
            try (Statement stmt = connection.createStatement()) {
                stmt.executeUpdate(
                    String.format("INSERT INTO %s (id, name, value) VALUES (101, 'connloss', 10100)", tableName)
                );
                logger.info("Inserted row");
            }
            
            xaResource.end(xid, XAResource.TMSUCCESS);
            int prepareResult = xaResource.prepare(xid);
            assertEquals(XAResource.XA_OK, prepareResult);
            logger.info("Transaction prepared on first connection");
            
            // Step 3: Close first connection (simulate connection loss)
            connection.close();
            xaConnection.close();
            logger.info("First connection closed (simulating connection loss)");
            
            // Step 4: Create new connection
            XAConnection newXaConnection = xaDataSource.getXAConnection();
            XAResource newXaResource = newXaConnection.getXAResource();
            Connection newConnection = newXaConnection.getConnection();
            logger.info("New connection created for recovery");
            
            try {
                // Step 5: Call recover() on new connection
                Xid[] recoveredXids = newXaResource.recover(XAResource.TMSTARTRSCAN | XAResource.TMENDRSCAN);
                logger.info("Recovered {} transaction(s) from new connection", 
                    recoveredXids != null ? recoveredXids.length : 0);
                
                // Step 6: Verify our XID is recovered
                assertNotNull(recoveredXids, "Should recover XIDs");
                boolean found = false;
                for (Xid recoveredXid : recoveredXids) {
                    if (Arrays.equals(xid.getGlobalTransactionId(), recoveredXid.getGlobalTransactionId())) {
                        found = true;
                        logger.info("Found prepared transaction from lost connection");
                        break;
                    }
                }
                assertTrue(found, "Should find our prepared transaction");
                
                // Step 7: Commit from new connection
                newXaResource.commit(xid, false);
                logger.info("Transaction committed from new connection");
                
                // Step 8: Verify data is committed
                try (Statement stmt = newConnection.createStatement();
                     ResultSet rs = stmt.executeQuery(
                         String.format("SELECT COUNT(*) FROM %s WHERE id = 101", tableName))) {
                    
                    assertTrue(rs.next(), "Query should return result");
                    assertEquals(1, rs.getInt(1), "Should have 1 row");
                    logger.info("Data verified: committed after connection loss recovery");
                }
                
            } finally {
                newConnection.close();
                newXaConnection.close();
            }
            
            logger.info("✓ Test Case 6.2: PASSED - Recovery after connection loss successful");
            
        } finally {
            // Cleanup - need to recreate connection for cleanup
            xaConnection = xaDataSource.getXAConnection();
            connection = xaConnection.getConnection();
            dropTestTable(connection, tableName);
        }
    }
    
    /**
     * Test Case 6.3: Recovery Flags (TMSTARTRSCAN, TMENDRSCAN, TMNOFLAGS)
     * 
     * Objective: Verify different recovery flags work correctly
     * 
     * Steps:
     * 1. Prepare multiple transactions
     * 2. Test TMSTARTRSCAN flag (start recovery scan)
     * 3. Test TMNOFLAGS (continue recovery scan)
     * 4. Test TMENDRSCAN flag (end recovery scan)
     * 5. Test combined TMSTARTRSCAN | TMENDRSCAN (single call)
     * 6. Cleanup prepared transactions
     * 
     * Expected Result: All recovery flag combinations work correctly
     */
    @Test
    public void testCase6_3_RecoveryFlags() throws Exception {
        logger.info("Test Case 6.3: Recovery Flags");
        
        // Create test table
        String tableName = createTestTable(connection);
        logger.info("Created test table: {}", tableName);
        
        // Create and prepare two transactions
        Xid xid1 = createXid("test-6.3-flag1");
        Xid xid2 = createXid("test-6.3-flag2");
        
        try {
            // Prepare first transaction
            xaResource.start(xid1, XAResource.TMNOFLAGS);
            try (Statement stmt = connection.createStatement()) {
                stmt.executeUpdate(
                    String.format("INSERT INTO %s (id, name, value) VALUES (102, 'flag1', 10200)", tableName)
                );
            }
            xaResource.end(xid1, XAResource.TMSUCCESS);
            xaResource.prepare(xid1);
            logger.info("Prepared first transaction");
            
            // Prepare second transaction (need new connection)
            XAConnection xaConn2 = createAdditionalXAConnection();
            XAResource xaRes2 = xaConn2.getXAResource();
            Connection conn2 = xaConn2.getConnection();
            
            try {
                xaRes2.start(xid2, XAResource.TMNOFLAGS);
                try (Statement stmt = conn2.createStatement()) {
                    stmt.executeUpdate(
                        String.format("INSERT INTO %s (id, name, value) VALUES (103, 'flag2', 10300)", tableName)
                    );
                }
                xaRes2.end(xid2, XAResource.TMSUCCESS);
                xaRes2.prepare(xid2);
                logger.info("Prepared second transaction");
                
            } finally {
                conn2.close();
                xaConn2.close();
            }
            
            // Test recovery with different flags
            
            // Test 1: TMSTARTRSCAN (start scan)
            Xid[] xids1 = xaResource.recover(XAResource.TMSTARTRSCAN);
            logger.info("TMSTARTRSCAN returned {} XID(s)", xids1 != null ? xids1.length : 0);
            assertNotNull(xids1, "TMSTARTRSCAN should return XIDs");
            
            // Test 2: TMNOFLAGS (continue scan)
            Xid[] xids2 = xaResource.recover(XAResource.TMNOFLAGS);
            logger.info("TMNOFLAGS returned {} XID(s)", xids2 != null ? xids2.length : 0);
            // May return empty if all returned in first call
            
            // Test 3: TMENDRSCAN (end scan)
            Xid[] xids3 = xaResource.recover(XAResource.TMENDRSCAN);
            logger.info("TMENDRSCAN returned {} XID(s)", xids3 != null ? xids3.length : 0);
            
            // Test 4: Combined TMSTARTRSCAN | TMENDRSCAN (single call for all)
            Xid[] xidsAll = xaResource.recover(XAResource.TMSTARTRSCAN | XAResource.TMENDRSCAN);
            logger.info("TMSTARTRSCAN|TMENDRSCAN returned {} XID(s)", xidsAll != null ? xidsAll.length : 0);
            assertNotNull(xidsAll, "Combined flags should return XIDs");
            assertTrue(xidsAll.length >= 2, "Should find at least our 2 prepared transactions");
            
            // Cleanup: rollback both transactions
            xaResource.rollback(xid1);
            xaResource.rollback(xid2);
            logger.info("Cleaned up prepared transactions");
            
            logger.info("✓ Test Case 6.3: PASSED - Recovery flags work correctly");
            
        } finally {
            // Cleanup
            dropTestTable(connection, tableName);
        }
    }
    
    /**
     * Test Case 6.4: Forget Heuristically Completed Transaction
     * 
     * Objective: Verify forget() operation for heuristic outcomes
     * 
     * Steps:
     * 1. Prepare a transaction
     * 2. Simulate heuristic outcome (commit outside XA)
     * 3. Call forget() to clear heuristic information
     * 4. Verify forget() completes without error
     * 
     * Expected Result: forget() allows clearing heuristic transaction information
     * 
     * Note: Heuristic outcomes occur when a resource manager makes a commit/rollback
     * decision independently. forget() tells the resource manager to forget about
     * the heuristic outcome.
     */
    @Test
    public void testCase6_4_ForgetHeuristicallyCompletedTransaction() throws Exception {
        logger.info("Test Case 6.4: Forget Heuristically Completed Transaction");
        
        // Create test table
        String tableName = createTestTable(connection);
        logger.info("Created test table: {}", tableName);
        
        // Create XID
        Xid xid = createXid("test-6.4-heuristic");
        logger.info("Created XID: {}", xid);
        
        try {
            // Step 1: Prepare a transaction
            xaResource.start(xid, XAResource.TMNOFLAGS);
            
            try (Statement stmt = connection.createStatement()) {
                stmt.executeUpdate(
                    String.format("INSERT INTO %s (id, name, value) VALUES (104, 'heuristic', 10400)", tableName)
                );
            }
            
            xaResource.end(xid, XAResource.TMSUCCESS);
            int prepareResult = xaResource.prepare(xid);
            assertEquals(XAResource.XA_OK, prepareResult);
            logger.info("Transaction prepared");
            
            // Step 2: Commit the transaction (this will be used to simulate heuristic)
            xaResource.commit(xid, false);
            logger.info("Transaction committed");
            
            // Step 3 & 4: Call forget()
            // Note: In a real heuristic scenario, the resource manager would report
            // a heuristic outcome (XA_HEURCOM, XA_HEURRB, etc.). We're testing that
            // forget() is callable and doesn't throw unexpected errors.
            
            try {
                xaResource.forget(xid);
                logger.info("forget() called successfully (no heuristic info to forget)");
            } catch (XAException e) {
                // forget() may throw XAER_NOTA if there's no heuristic info
                // This is acceptable - it means the XID is not known
                if (e.errorCode == XAException.XAER_NOTA) {
                    logger.info("forget() threw XAER_NOTA (XID not found - acceptable after commit)");
                } else {
                    logger.warn("forget() threw unexpected error: {}", e.errorCode);
                    throw e;
                }
            }
            
            logger.info("✓ Test Case 6.4: PASSED - forget() operation works correctly");
            
        } finally {
            // Cleanup
            dropTestTable(connection, tableName);
        }
    }
    
    /**
     * Test Case 6.5: Multiple In-Doubt Transactions Recovery
     * 
     * Objective: Verify recovery and completion of multiple prepared transactions
     * 
     * Steps:
     * 1. Prepare 3 different transactions
     * 2. Call recover() to get all prepared transactions
     * 3. Verify all 3 transactions are in the list
     * 4. Commit 2 transactions
     * 5. Rollback 1 transaction
     * 6. Verify data state matches commit/rollback decisions
     * 7. Call recover() again to verify list is updated
     * 
     * Expected Result: Multiple prepared transactions can be recovered and completed
     */
    @Test
    public void testCase6_5_MultipleInDoubtTransactionsRecovery() throws Exception {
        logger.info("Test Case 6.5: Multiple In-Doubt Transactions Recovery");
        
        // Create test table
        String tableName = createTestTable(connection);
        logger.info("Created test table: {}", tableName);
        
        // Create 3 XIDs
        Xid xid1 = createXid("test-6.5-multi1");
        Xid xid2 = createXid("test-6.5-multi2");
        Xid xid3 = createXid("test-6.5-multi3");
        
        try {
            // Step 1: Prepare 3 transactions
            
            // Transaction 1
            xaResource.start(xid1, XAResource.TMNOFLAGS);
            try (Statement stmt = connection.createStatement()) {
                stmt.executeUpdate(
                    String.format("INSERT INTO %s (id, name, value) VALUES (105, 'multi1', 10500)", tableName)
                );
            }
            xaResource.end(xid1, XAResource.TMSUCCESS);
            xaResource.prepare(xid1);
            logger.info("Prepared transaction 1");
            
            // Transaction 2 (need new connection)
            XAConnection xaConn2 = createAdditionalXAConnection();
            XAResource xaRes2 = xaConn2.getXAResource();
            Connection conn2 = xaConn2.getConnection();
            
            try {
                xaRes2.start(xid2, XAResource.TMNOFLAGS);
                try (Statement stmt = conn2.createStatement()) {
                    stmt.executeUpdate(
                        String.format("INSERT INTO %s (id, name, value) VALUES (106, 'multi2', 10600)", tableName)
                    );
                }
                xaRes2.end(xid2, XAResource.TMSUCCESS);
                xaRes2.prepare(xid2);
                logger.info("Prepared transaction 2");
            } finally {
                conn2.close();
                xaConn2.close();
            }
            
            // Transaction 3 (need another new connection)
            XAConnection xaConn3 = createAdditionalXAConnection();
            XAResource xaRes3 = xaConn3.getXAResource();
            Connection conn3 = xaConn3.getConnection();
            
            try {
                xaRes3.start(xid3, XAResource.TMNOFLAGS);
                try (Statement stmt = conn3.createStatement()) {
                    stmt.executeUpdate(
                        String.format("INSERT INTO %s (id, name, value) VALUES (107, 'multi3', 10700)", tableName)
                    );
                }
                xaRes3.end(xid3, XAResource.TMSUCCESS);
                xaRes3.prepare(xid3);
                logger.info("Prepared transaction 3");
            } finally {
                conn3.close();
                xaConn3.close();
            }
            
            // Step 2: Call recover() to get all prepared transactions
            Xid[] recoveredXids = xaResource.recover(XAResource.TMSTARTRSCAN | XAResource.TMENDRSCAN);
            logger.info("Recovered {} transaction(s)", recoveredXids != null ? recoveredXids.length : 0);
            
            // Step 3: Verify all 3 transactions are in the list
            assertNotNull(recoveredXids, "Should recover XIDs");
            Set<String> recoveredGlobalIds = new HashSet<>();
            for (Xid xid : recoveredXids) {
                recoveredGlobalIds.add(new String(xid.getGlobalTransactionId()));
            }
            
            String globalId1 = new String(xid1.getGlobalTransactionId());
            String globalId2 = new String(xid2.getGlobalTransactionId());
            String globalId3 = new String(xid3.getGlobalTransactionId());
            
            assertTrue(recoveredGlobalIds.contains(globalId1), "Should find transaction 1");
            assertTrue(recoveredGlobalIds.contains(globalId2), "Should find transaction 2");
            assertTrue(recoveredGlobalIds.contains(globalId3), "Should find transaction 3");
            logger.info("All 3 prepared transactions found in recovery");
            
            // Step 4 & 5: Commit 2, rollback 1
            xaResource.commit(xid1, false);
            logger.info("Committed transaction 1");
            
            xaResource.commit(xid2, false);
            logger.info("Committed transaction 2");
            
            xaResource.rollback(xid3);
            logger.info("Rolled back transaction 3");
            
            // Step 6: Verify data state
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     String.format("SELECT COUNT(*) FROM %s WHERE id IN (105, 106, 107)", tableName))) {
                
                assertTrue(rs.next(), "Query should return result");
                assertEquals(2, rs.getInt(1), "Should have 2 rows (2 committed, 1 rolled back)");
                logger.info("Data verified: 2 committed, 1 rolled back");
            }
            
            // Step 7: Call recover() again to verify list is updated
            Xid[] recoveredXids2 = xaResource.recover(XAResource.TMSTARTRSCAN | XAResource.TMENDRSCAN);
            logger.info("After completion, recovered {} transaction(s)", 
                recoveredXids2 != null ? recoveredXids2.length : 0);
            
            // Our 3 transactions should no longer be in the in-doubt list
            Set<String> recoveredGlobalIds2 = new HashSet<>();
            if (recoveredXids2 != null) {
                for (Xid xid : recoveredXids2) {
                    recoveredGlobalIds2.add(new String(xid.getGlobalTransactionId()));
                }
            }
            
            assertFalse(recoveredGlobalIds2.contains(globalId1), "Transaction 1 should not be in-doubt");
            assertFalse(recoveredGlobalIds2.contains(globalId2), "Transaction 2 should not be in-doubt");
            assertFalse(recoveredGlobalIds2.contains(globalId3), "Transaction 3 should not be in-doubt");
            logger.info("Completed transactions removed from in-doubt list");
            
            logger.info("✓ Test Case 6.5: PASSED - Multiple in-doubt transactions recovery successful");
            
        } finally {
            // Cleanup
            dropTestTable(connection, tableName);
        }
    }
}
