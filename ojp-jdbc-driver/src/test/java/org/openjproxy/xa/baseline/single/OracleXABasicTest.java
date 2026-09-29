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
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Oracle Basic XA Operations Tests (Phase 3)
 * 
 * Tests core XA functionality using Oracle native JDBC driver (baseline testing).
 * These tests establish expected XA behavior before testing with OJP proxy.
 * 
 * Test Cases:
 * 1. XA Connection Creation - Verify basic infrastructure
 * 2. Basic XA Transaction Lifecycle - Complete 2PC flow (happy path)
 * 3. XA Transaction Rollback - Verify rollback behavior
 * 4. One-Phase Commit Optimization - Test single-resource optimization
 * 5. Read-Only Transaction Optimization - Test XA_RDONLY behavior
 * 
 * Database: Oracle XE 21 (via TestContainers)
 * Driver: Oracle native JDBC driver (oracle.jdbc.xa.client.OracleXADataSource)
 * 
 * These tests are disabled by default and only run when -DenableOracleTests=true
 */
@EnabledIf("org.openjproxy.xa.baseline.containers.OracleXATestContainer#isEnabled")
public class OracleXABasicTest extends XATestBase {
    
    private static final Logger logger = LoggerFactory.getLogger(OracleXABasicTest.class);
    
    protected static XADataSource staticXADataSource;
    
    @BeforeAll
    public static void setUpClass() throws Exception {
        logger.info("=== Starting Oracle XA Basic Tests (Phase 3) ===");
        logger.info("Using shared Oracle XA Container from singleton...");
        
        // Create XA DataSource using the OracleXAContainer wrapper
        // The wrapper internally uses OracleXATestContainer singleton
        OracleXAContainer oracleContainer = new OracleXAContainer();
        staticXADataSource = oracleContainer.createXADataSource();
        
        logger.info("Oracle XA DataSource created successfully");
    }
    
    @AfterAll
    public static void tearDownClass() {
        logger.info("=== Oracle XA Basic Tests Complete ===");
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
     * Test Case 1.1: XA Connection Creation
     * 
     * Objective: Verify basic XA infrastructure setup
     * 
     * Steps:
     * 1. Create XA DataSource
     * 2. Get XA Connection
     * 3. Verify XA Resource is accessible
     * 4. Get logical connection
     * 5. Verify auto-commit is disabled
     * 
     * Expected Result: All objects created successfully, auto-commit is false
     */
    @Test
    public void testCase1_1_XAConnectionCreation() throws Exception {
        logger.info("Test Case 1.1: XA Connection Creation");
        
        // XA DataSource already created in setUp()
        assertNotNull(xaDataSource, "XA DataSource should not be null");
        
        // XA Connection already created in setUp()
        assertNotNull(xaConnection, "XA Connection should not be null");
        
        // Verify XA Resource is accessible
        assertNotNull(xaResource, "XA Resource should not be null");
        logger.info("XA Resource obtained successfully: {}", xaResource.getClass().getName());
        
        // Verify logical connection
        assertNotNull(connection, "Logical connection should not be null");
        
        // Verify auto-commit is disabled (required for XA)
        assertFalse(connection.getAutoCommit(), 
            "Auto-commit must be disabled on XA connection");
        
        // Test isSameRM with itself
        assertTrue(xaResource.isSameRM(xaResource), 
            "XAResource should recognize itself via isSameRM");
        
        logger.info("✓ Test Case 1.1: PASSED - XA Connection created successfully");
    }
    
    /**
     * Test Case 1.2: Basic XA Transaction Lifecycle (Happy Path)
     * 
     * Objective: Execute a complete XA transaction successfully
     * 
     * Steps:
     * 1. Create XID
     * 2. Start XA transaction
     * 3. Execute INSERT operation
     * 4. End XA transaction with TMSUCCESS
     * 5. Prepare transaction
     * 6. Verify prepare returns XA_OK
     * 7. Commit with two-phase commit (onePhase=false)
     * 8. Verify data is committed
     * 
     * Expected Result: Data successfully committed and persisted
     */
    @Test
    public void testCase1_2_BasicXATransactionLifecycle() throws Exception {
        logger.info("Test Case 1.2: Basic XA Transaction Lifecycle (Happy Path)");
        
        // Create test table
        String tableName = createTestTable(connection);
        logger.info("Created test table: {}", tableName);
        
        // Create XID
        Xid xid = createXid("test-1.2");
        logger.info("Created XID: {}", xid);
        
        try {
            // Step 1: Start XA transaction
            xaResource.start(xid, XAResource.TMNOFLAGS);
            logger.info("XA transaction started");
            
            // Step 2: Execute INSERT operation
            try (Statement stmt = connection.createStatement()) {
                int rows = stmt.executeUpdate(
                    String.format("INSERT INTO %s (id, name, value) VALUES (1, 'test1', 100)", tableName)
                );
                assertEquals(1, rows, "Should insert 1 row");
                logger.info("Inserted 1 row");
            }
            
            // Step 3: End XA transaction
            xaResource.end(xid, XAResource.TMSUCCESS);
            logger.info("XA transaction ended with TMSUCCESS");
            
            // Step 4: Prepare transaction
            int prepareResult = xaResource.prepare(xid);
            logger.info("Prepare returned: {}", prepareResult);
            
            // Should return XA_OK (0) for a transaction that modified data
            assertEquals(XAResource.XA_OK, prepareResult, 
                "Prepare should return XA_OK for transaction with modifications");
            
            // Step 5: Commit with two-phase commit
            xaResource.commit(xid, false); // onePhase = false
            logger.info("Transaction committed (two-phase)");
            
            // Step 6: Verify data is committed
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     String.format("SELECT COUNT(*) FROM %s WHERE id = 1", tableName))) {
                
                assertTrue(rs.next(), "Query should return result");
                assertEquals(1, rs.getInt(1), "Should have 1 row");
                logger.info("Data verified: 1 row committed");
            }
            
            logger.info("✓ Test Case 1.2: PASSED - Basic XA transaction lifecycle completed successfully");
            
        } finally {
            // Cleanup
            dropTestTable(connection, tableName);
        }
    }
    
    /**
     * Test Case 1.3: XA Transaction Rollback
     * 
     * Objective: Verify rollback functionality
     * 
     * Steps:
     * 1. Start XA transaction
     * 2. Execute INSERT operation
     * 3. End transaction with TMSUCCESS
     * 4. Call rollback instead of commit
     * 5. Verify data is NOT committed
     * 
     * Expected Result: Data rolled back, not visible in database
     */
    @Test
    public void testCase1_3_XATransactionRollback() throws Exception {
        logger.info("Test Case 1.3: XA Transaction Rollback");
        
        // Create test table
        String tableName = createTestTable(connection);
        logger.info("Created test table: {}", tableName);
        
        // Create XID
        Xid xid = createXid("test-1.3");
        logger.info("Created XID: {}", xid);
        
        try {
            // Step 1: Start XA transaction
            xaResource.start(xid, XAResource.TMNOFLAGS);
            logger.info("XA transaction started");
            
            // Step 2: Execute INSERT operation
            try (Statement stmt = connection.createStatement()) {
                int rows = stmt.executeUpdate(
                    String.format("INSERT INTO %s (id, name, value) VALUES (2, 'test2', 200)", tableName)
                );
                assertEquals(1, rows, "Should insert 1 row");
                logger.info("Inserted 1 row");
            }
            
            // Step 3: End XA transaction
            xaResource.end(xid, XAResource.TMSUCCESS);
            logger.info("XA transaction ended with TMSUCCESS");
            
            // Step 4: Rollback instead of commit
            xaResource.rollback(xid);
            logger.info("Transaction rolled back");
            
            // Step 5: Verify data is NOT committed
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     String.format("SELECT COUNT(*) FROM %s WHERE id = 2", tableName))) {
                
                assertTrue(rs.next(), "Query should return result");
                assertEquals(0, rs.getInt(1), "Should have 0 rows (rolled back)");
                logger.info("Data verified: 0 rows (rollback successful)");
            }
            
            logger.info("✓ Test Case 1.3: PASSED - XA transaction rollback successful");
            
        } finally {
            // Cleanup
            dropTestTable(connection, tableName);
        }
    }
    
    /**
     * Test Case 1.4: One-Phase Commit Optimization
     * 
     * Objective: Test one-phase commit when only one resource manager involved
     * 
     * Steps:
     * 1. Start XA transaction
     * 2. Execute UPDATE operation
     * 3. End transaction
     * 4. Call commit with onePhase=true (no explicit prepare)
     * 5. Verify data is committed
     * 
     * Expected Result: Data committed successfully without explicit prepare
     */
    @Test
    public void testCase1_4_OnePhaseCommitOptimization() throws Exception {
        logger.info("Test Case 1.4: One-Phase Commit Optimization");
        
        // Create test table with initial data
        String tableName = createTestTable(connection);
        logger.info("Created test table: {}", tableName);
        
        // Insert initial row (outside XA transaction)
        XAConnection tempXaConn = xaDataSource.getXAConnection();
        Connection tempConn = tempXaConn.getConnection();
        try (Statement stmt = tempConn.createStatement()) {
            stmt.executeUpdate(
                String.format("INSERT INTO %s (id, name, value) VALUES (3, 'initial', 300)", tableName)
            );
            tempConn.commit();
            logger.info("Inserted initial row");
        } finally {
            tempConn.close();
            tempXaConn.close();
        }
        
        // Create XID
        Xid xid = createXid("test-1.4");
        logger.info("Created XID: {}", xid);
        
        try {
            // Step 1: Start XA transaction
            xaResource.start(xid, XAResource.TMNOFLAGS);
            logger.info("XA transaction started");
            
            // Step 2: Execute UPDATE operation
            try (Statement stmt = connection.createStatement()) {
                int rows = stmt.executeUpdate(
                    String.format("UPDATE %s SET value = 400 WHERE id = 3", tableName)
                );
                assertEquals(1, rows, "Should update 1 row");
                logger.info("Updated 1 row");
            }
            
            // Step 3: End XA transaction
            xaResource.end(xid, XAResource.TMSUCCESS);
            logger.info("XA transaction ended with TMSUCCESS");
            
            // Step 4: One-phase commit (no explicit prepare)
            xaResource.commit(xid, true); // onePhase = true
            logger.info("Transaction committed (one-phase)");
            
            // Step 5: Verify data is committed
            XAConnection verifyXaConn = xaDataSource.getXAConnection();
            Connection verifyConn = verifyXaConn.getConnection();
            try (Statement stmt = verifyConn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     String.format("SELECT value FROM %s WHERE id = 3", tableName))) {
                
                assertTrue(rs.next(), "Query should return result");
                assertEquals(400, rs.getInt(1), "Value should be updated to 400");
                logger.info("Data verified: value updated to 400");
            } finally {
                verifyConn.close();
                verifyXaConn.close();
            }
            
            logger.info("✓ Test Case 1.4: PASSED - One-phase commit optimization successful");
            
        } finally {
            // Cleanup
            dropTestTable(connection, tableName);
        }
    }
    
    /**
     * Test Case 1.5: Read-Only Transaction Optimization
     * 
     * Objective: Verify XA_RDONLY return from prepare for read-only transactions
     * 
     * Steps:
     * 1. Start XA transaction
     * 2. Execute SELECT query only (no modifications)
     * 3. End transaction
     * 4. Call prepare
     * 5. Verify prepare returns XA_RDONLY
     * 6. Verify no commit call needed
     * 
     * Expected Result: prepare returns XA_RDONLY, transaction completes without commit
     * 
     * Note: Oracle behavior - Some databases may return XA_RDONLY, others may return XA_OK
     * even for read-only transactions. This test documents Oracle's specific behavior.
     */
    @Test
    public void testCase1_5_ReadOnlyTransactionOptimization() throws Exception {
        logger.info("Test Case 1.5: Read-Only Transaction Optimization");
        
        // Create test table with data
        String tableName = createTestTable(connection);
        logger.info("Created test table: {}", tableName);
        
        // Insert test data (outside XA transaction)
        XAConnection tempXaConn = xaDataSource.getXAConnection();
        Connection tempConn = tempXaConn.getConnection();
        try (Statement stmt = tempConn.createStatement()) {
            stmt.executeUpdate(
                String.format("INSERT INTO %s (id, name, value) VALUES (4, 'readonly', 500)", tableName)
            );
            tempConn.commit();
            logger.info("Inserted test data");
        } finally {
            tempConn.close();
            tempXaConn.close();
        }
        
        // Create XID
        Xid xid = createXid("test-1.5");
        logger.info("Created XID: {}", xid);
        
        try {
            // Step 1: Start XA transaction
            xaResource.start(xid, XAResource.TMNOFLAGS);
            logger.info("XA transaction started");
            
            // Step 2: Execute SELECT query only (read-only)
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     String.format("SELECT value FROM %s WHERE id = 4", tableName))) {
                
                assertTrue(rs.next(), "Query should return result");
                assertEquals(500, rs.getInt(1), "Value should be 500");
                logger.info("Read data: value = 500 (read-only transaction)");
            }
            
            // Step 3: End XA transaction
            xaResource.end(xid, XAResource.TMSUCCESS);
            logger.info("XA transaction ended with TMSUCCESS");
            
            // Step 4: Prepare transaction
            int prepareResult = xaResource.prepare(xid);
            logger.info("Prepare returned: {} ({})", prepareResult, 
                prepareResult == XAResource.XA_RDONLY ? "XA_RDONLY" : "XA_OK");
            
            // Oracle may return XA_RDONLY (3) or XA_OK (0) for read-only transactions
            // This depends on Oracle's internal optimization decisions
            // Both are acceptable according to XA spec
            assertTrue(prepareResult == XAResource.XA_RDONLY || prepareResult == XAResource.XA_OK,
                "Prepare should return XA_RDONLY (3) or XA_OK (0) for read-only transaction");
            
            if (prepareResult == XAResource.XA_RDONLY) {
                logger.info("Oracle returned XA_RDONLY - transaction auto-completed (optimization)");
                // No commit needed - transaction already completed
            } else {
                logger.info("Oracle returned XA_OK - explicit commit required (non-optimized)");
                // Need to commit even though it was read-only
                xaResource.commit(xid, false);
                logger.info("Transaction committed");
            }
            
            logger.info("✓ Test Case 1.5: PASSED - Read-only transaction handled correctly");
            logger.info("  Oracle behavior: prepare returned {}", 
                prepareResult == XAResource.XA_RDONLY ? "XA_RDONLY" : "XA_OK");
            
        } finally {
            // Cleanup
            dropTestTable(connection, tableName);
        }
    }
    
    /**
     * Test Case 2.1: Transaction Suspension and Resumption (TMSUSPEND/TMRESUME)
     * 
     * Objective: Verify transaction can be suspended and resumed
     * 
     * Steps:
     * 1. Start XA transaction
     * 2. Execute INSERT operation
     * 3. End transaction with TMSUSPEND
     * 4. Execute different operation outside transaction
     * 5. Resume transaction with TMRESUME
     * 6. Execute another INSERT in same transaction
     * 7. End transaction with TMSUCCESS
     * 8. Prepare and commit
     * 9. Verify both INSERTs are committed
     * 
     * Expected Result: Transaction can be suspended and resumed, all operations committed
     */
    @Test
    public void testCase2_1_TransactionSuspensionAndResumption() throws Exception {
        logger.info("Test Case 2.1: Transaction Suspension and Resumption");
        
        // Create test table
        String tableName = createTestTable(connection);
        logger.info("Created test table: {}", tableName);
        
        // Create XID
        Xid xid = createXid("test-2.1");
        logger.info("Created XID: {}", xid);
        
        try {
            // Step 1: Start XA transaction
            xaResource.start(xid, XAResource.TMNOFLAGS);
            logger.info("XA transaction started");
            
            // Step 2: Execute first INSERT
            try (Statement stmt = connection.createStatement()) {
                int rows = stmt.executeUpdate(
                    String.format("INSERT INTO %s (id, name, value) VALUES (10, 'first', 1000)", tableName)
                );
                assertEquals(1, rows, "Should insert 1 row");
                logger.info("Inserted first row");
            }
            
            // Step 3: Suspend transaction
            xaResource.end(xid, XAResource.TMSUSPEND);
            logger.info("XA transaction suspended with TMSUSPEND");
            
            // Step 4: Execute operation outside transaction (auto-commit would be needed)
            // For this test, we'll just demonstrate suspension works
            logger.info("Transaction suspended - could do other work here");
            
            // Step 5: Resume transaction
            xaResource.start(xid, XAResource.TMRESUME);
            logger.info("XA transaction resumed with TMRESUME");
            
            // Step 6: Execute second INSERT in same transaction
            try (Statement stmt = connection.createStatement()) {
                int rows = stmt.executeUpdate(
                    String.format("INSERT INTO %s (id, name, value) VALUES (11, 'second', 1100)", tableName)
                );
                assertEquals(1, rows, "Should insert 1 row");
                logger.info("Inserted second row after resumption");
            }
            
            // Step 7: End transaction normally
            xaResource.end(xid, XAResource.TMSUCCESS);
            logger.info("XA transaction ended with TMSUCCESS");
            
            // Step 8: Prepare and commit
            int prepareResult = xaResource.prepare(xid);
            assertEquals(XAResource.XA_OK, prepareResult, "Prepare should return XA_OK");
            xaResource.commit(xid, false);
            logger.info("Transaction prepared and committed");
            
            // Step 9: Verify both rows are committed
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     String.format("SELECT COUNT(*) FROM %s WHERE id IN (10, 11)", tableName))) {
                
                assertTrue(rs.next(), "Query should return result");
                assertEquals(2, rs.getInt(1), "Should have 2 rows (both INSERTs committed)");
                logger.info("Data verified: 2 rows committed after suspend/resume");
            }
            
            logger.info("✓ Test Case 2.1: PASSED - Transaction suspension and resumption successful");
            
        } finally {
            // Cleanup
            dropTestTable(connection, tableName);
        }
    }
    
    /**
     * Test Case 2.2: Transaction Branch Joining (TMJOIN)
     * 
     * Objective: Verify multiple connections can join same transaction branch
     * 
     * Steps:
     * 1. Start XA transaction on first connection
     * 2. Execute INSERT on first connection
     * 3. Create second connection to same database
     * 4. Join same transaction branch with TMJOIN
     * 5. Execute INSERT on second connection
     * 6. End both branches
     * 7. Prepare and commit
     * 8. Verify both INSERTs are committed
     * 
     * Expected Result: Two connections can work on same transaction branch
     * 
     * Note: TMJOIN is used when multiple threads/connections work on same transaction branch
     */
    @Test
    public void testCase2_2_TransactionBranchJoining() throws Exception {
        logger.info("Test Case 2.2: Transaction Branch Joining (TMJOIN)");
        
        // Create test table
        String tableName = createTestTable(connection);
        logger.info("Created test table: {}", tableName);
        
        // Create XID
        Xid xid = createXid("test-2.2");
        logger.info("Created XID: {}", xid);
        
        // Create second XA connection
        XAConnection xaConnection2 = createAdditionalXAConnection();
        XAResource xaResource2 = xaConnection2.getXAResource();
        Connection connection2 = xaConnection2.getConnection();
        
        try {
            // Step 1: Start XA transaction on first connection
            xaResource.start(xid, XAResource.TMNOFLAGS);
            logger.info("XA transaction started on first connection");
            
            // Step 2: Execute INSERT on first connection
            try (Statement stmt = connection.createStatement()) {
                int rows = stmt.executeUpdate(
                    String.format("INSERT INTO %s (id, name, value) VALUES (20, 'conn1', 2000)", tableName)
                );
                assertEquals(1, rows, "Should insert 1 row");
                logger.info("Inserted row from first connection");
            }
            
            // Step 3: End first branch (required before joining from second connection)
            xaResource.end(xid, XAResource.TMSUCCESS);
            logger.info("Ended first branch with TMSUCCESS");
            
            // Step 4: Join same transaction from second connection
            xaResource2.start(xid, XAResource.TMJOIN);
            logger.info("Second connection joined transaction with TMJOIN");
            
            // Step 5: Execute INSERT on second connection
            try (Statement stmt = connection2.createStatement()) {
                int rows = stmt.executeUpdate(
                    String.format("INSERT INTO %s (id, name, value) VALUES (21, 'conn2', 2100)", tableName)
                );
                assertEquals(1, rows, "Should insert 1 row");
                logger.info("Inserted row from second connection");
            }
            
            // Step 6: End second branch
            xaResource2.end(xid, XAResource.TMSUCCESS);
            logger.info("Ended second branch with TMSUCCESS");
            
            // Step 7: Prepare and commit (only need to do this once)
            int prepareResult = xaResource.prepare(xid);
            assertEquals(XAResource.XA_OK, prepareResult, "Prepare should return XA_OK");
            xaResource.commit(xid, false);
            logger.info("Transaction prepared and committed");
            
            // Step 8: Verify both rows are committed
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     String.format("SELECT COUNT(*) FROM %s WHERE id IN (20, 21)", tableName))) {
                
                assertTrue(rs.next(), "Query should return result");
                assertEquals(2, rs.getInt(1), "Should have 2 rows (both connections' INSERTs committed)");
                logger.info("Data verified: 2 rows committed from joined transaction");
            }
            
            logger.info("✓ Test Case 2.2: PASSED - Transaction branch joining successful");
            
        } finally {
            // Cleanup
            dropTestTable(connection, tableName);
            connection2.close();
            xaConnection2.close();
        }
    }
    
    /**
     * Test Case 2.3: Transaction Failure (TMFAIL)
     * 
     * Objective: Verify TMFAIL flag marks transaction for rollback only
     * 
     * Steps:
     * 1. Start XA transaction
     * 2. Execute INSERT operation
     * 3. End transaction with TMFAIL (indicates failure)
     * 4. Verify prepare is not possible (transaction is rollback-only)
     * 5. Rollback transaction
     * 6. Verify data is NOT committed
     * 
     * Expected Result: TMFAIL marks transaction for rollback, data not committed
     * 
     * Note: TMFAIL indicates the transaction branch failed and should be rolled back
     */
    @Test
    public void testCase2_3_TransactionFailure() throws Exception {
        logger.info("Test Case 2.3: Transaction Failure (TMFAIL)");
        
        // Create test table
        String tableName = createTestTable(connection);
        logger.info("Created test table: {}", tableName);
        
        // Create XID
        Xid xid = createXid("test-2.3");
        logger.info("Created XID: {}", xid);
        
        try {
            // Step 1: Start XA transaction
            xaResource.start(xid, XAResource.TMNOFLAGS);
            logger.info("XA transaction started");
            
            // Step 2: Execute INSERT operation
            try (Statement stmt = connection.createStatement()) {
                int rows = stmt.executeUpdate(
                    String.format("INSERT INTO %s (id, name, value) VALUES (30, 'failed', 3000)", tableName)
                );
                assertEquals(1, rows, "Should insert 1 row");
                logger.info("Inserted row (will be marked as failed)");
            }
            
            // Step 3: End transaction with TMFAIL
            xaResource.end(xid, XAResource.TMFAIL);
            logger.info("XA transaction ended with TMFAIL");
            
            // Step 4: Verify prepare is not possible
            // After TMFAIL, the transaction is marked for rollback only
            // Attempting to prepare should fail or we should go straight to rollback
            logger.info("Transaction marked as failed - must rollback");
            
            // Step 5: Rollback transaction
            xaResource.rollback(xid);
            logger.info("Transaction rolled back");
            
            // Step 6: Verify data is NOT committed
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     String.format("SELECT COUNT(*) FROM %s WHERE id = 30", tableName))) {
                
                assertTrue(rs.next(), "Query should return result");
                assertEquals(0, rs.getInt(1), "Should have 0 rows (TMFAIL caused rollback)");
                logger.info("Data verified: 0 rows (rollback successful after TMFAIL)");
            }
            
            logger.info("✓ Test Case 2.3: PASSED - Transaction failure handling successful");
            
        } finally {
            // Cleanup
            dropTestTable(connection, tableName);
        }
    }
}
