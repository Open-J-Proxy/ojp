package org.openjproxy.xa.baseline.distributed;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;
import org.openjproxy.xa.baseline.common.XATestBase;
import org.openjproxy.xa.baseline.common.XidGenerator;
import org.openjproxy.xa.baseline.containers.DB2XAContainer;
import org.openjproxy.xa.baseline.containers.OracleXAContainer;
import org.openjproxy.xa.baseline.containers.SQLServerXAContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.XAConnection;
import javax.sql.XADataSource;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Two-Phase Commit Distributed Transaction Tests
 * 
 * Tests multi-database XA transactions using native JDBC drivers to establish
 * behavioral baselines before testing with OJP.
 * 
 * These tests validate that XA transactions can coordinate commits and rollbacks
 * across multiple databases atomically.
 * 
 * These tests are disabled by default and only run when all three databases are enabled:
 * -DenableOracleTests=true -DenableSqlServerTests=true -DenableDb2Tests=true
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Disabled("Requires all three databases to be enabled: -DenableOracleTests=true -DenableSqlServerTests=true -DenableDb2Tests=true")
public class TwoPhaseCommitTest extends XATestBase {

    private static final Logger logger = LoggerFactory.getLogger(TwoPhaseCommitTest.class);
    
    private static OracleXAContainer oracleContainer;
    private static SQLServerXAContainer sqlServerContainer;
    private static DB2XAContainer db2Container;
    
    @BeforeAll
    public static void setUpContainers() {
        logger.info("Setting up containers for distributed transaction tests...");
        
        // Create database container wrappers (use singletons internally)
        oracleContainer = new OracleXAContainer();
        logger.info("Oracle container ready: {}", oracleContainer.getJdbcUrl());
        
        sqlServerContainer = new SQLServerXAContainer();
        logger.info("SQL Server container ready: {}", sqlServerContainer.getJdbcUrl());
        
        db2Container = new DB2XAContainer();
        logger.info("DB2 container ready: {}", db2Container.getJdbcUrl());
        
        logger.info("All containers are ready");
    }
    
    @AfterAll
    public static void tearDownContainers() {
        logger.info("Test completed - singleton containers managed by shutdown hooks");
        // No explicit stop needed - singleton containers are managed by shutdown hooks
    }

    @Override
    protected String getDatabaseType() {
        return "Distributed";
    }

    @Override
    protected XADataSource createXADataSource() throws SQLException {
        // Return Oracle datasource as default (tests create their own as needed)
        return oracleContainer.createXADataSource();
    }

    /**
     * Test Case 9.1: Two-Database Transaction (Same Type - Oracle to Oracle)
     * 
     * Validates that a distributed transaction across two Oracle databases
     * commits atomically using two-phase commit protocol.
     * 
     * Flow:
     * 1. Start XA transaction on both Oracle connections
     * 2. Insert data in both databases
     * 3. End transactions
     * 4. Prepare both resources (Phase 1 of 2PC)
     * 5. Commit both resources (Phase 2 of 2PC)
     * 6. Verify data committed in both databases
     */
    @Test
    @Order(1)
    @DisplayName("9.1: Two Oracle databases - distributed commit")
    public void testTwoOracleDatabases_DistributedCommit() throws Exception {
        XADataSource xaDataSource1 = oracleContainer.createXADataSource();
        XADataSource xaDataSource2 = oracleContainer.createXADataSource();
        
        XAConnection xaConn1 = xaDataSource1.getXAConnection();
        XAConnection xaConn2 = xaDataSource2.getXAConnection();
        
        XAResource xaRes1 = xaConn1.getXAResource();
        XAResource xaRes2 = xaConn2.getXAResource();
        
        Connection conn1 = xaConn1.getConnection();
        Connection conn2 = xaConn2.getConnection();
        
        // Generate global XID and branch XIDs
        String globalTxId = "DIST-2PC-ORACLE-" + System.currentTimeMillis();
        Xid branchXid1 = XidGenerator.createBranchXid(1, globalTxId, "branch-1");
        Xid branchXid2 = XidGenerator.createBranchXid(1, globalTxId, "branch-2");
        
        try {
            // Phase: Start transactions
            xaRes1.start(branchXid1, XAResource.TMNOFLAGS);
            xaRes2.start(branchXid2, XAResource.TMNOFLAGS);
            
            // Phase: Execute work
            try (PreparedStatement ps1 = conn1.prepareStatement(
                    "INSERT INTO xa_test_baseline (test_name, test_value) VALUES (?, ?)")) {
                ps1.setString(1, "dist_oracle1");
                ps1.setString(2, "value_from_db1");
                ps1.executeUpdate();
            }
            
            try (PreparedStatement ps2 = conn2.prepareStatement(
                    "INSERT INTO xa_test_baseline (test_name, test_value) VALUES (?, ?)")) {
                ps2.setString(1, "dist_oracle2");
                ps2.setString(2, "value_from_db2");
                ps2.executeUpdate();
            }
            
            // Phase: End transactions
            xaRes1.end(branchXid1, XAResource.TMSUCCESS);
            xaRes2.end(branchXid2, XAResource.TMSUCCESS);
            
            // Phase 1 of 2PC: Prepare
            int prepare1 = xaRes1.prepare(branchXid1);
            int prepare2 = xaRes2.prepare(branchXid2);
            
            assertEquals(XAResource.XA_OK, prepare1, "First resource should be ready to commit");
            assertEquals(XAResource.XA_OK, prepare2, "Second resource should be ready to commit");
            
            // Phase 2 of 2PC: Commit
            xaRes1.commit(branchXid1, false);
            xaRes2.commit(branchXid2, false);
            
            // Verify: Both inserts should be visible
            assertTrue(verifyDataExists(conn1, "dist_oracle1", "value_from_db1"),
                    "Data should exist in first Oracle database");
            assertTrue(verifyDataExists(conn2, "dist_oracle2", "value_from_db2"),
                    "Data should exist in second Oracle database");
            
        } finally {
            cleanupTestData(conn1, "dist_oracle1");
            cleanupTestData(conn2, "dist_oracle2");
        }
    }

    /**
     * Test Case 9.2: Two-Database Transaction (Mixed Types - Oracle + SQL Server)
     * 
     * Validates that a distributed transaction across different database vendors
     * commits atomically using two-phase commit protocol.
     * 
     * This is the most common real-world scenario for distributed transactions.
     */
    @Test
    @Order(2)
    @DisplayName("9.2: Oracle + SQL Server - distributed commit")
    public void testOracleAndSQLServer_DistributedCommit() throws Exception {
        XADataSource oracleXADS = oracleContainer.createXADataSource();
        XADataSource sqlServerXADS = sqlServerContainer.createXADataSource();
        
        XAConnection oracleXAConn = oracleXADS.getXAConnection();
        XAConnection sqlServerXAConn = sqlServerXADS.getXAConnection();
        
        XAResource oracleXARes = oracleXAConn.getXAResource();
        XAResource sqlServerXARes = sqlServerXAConn.getXAResource();
        
        Connection oracleConn = oracleXAConn.getConnection();
        Connection sqlServerConn = sqlServerXAConn.getConnection();
        
        String globalTxId = "DIST-ORA-SQL-" + System.currentTimeMillis();
        Xid oracleXid = XidGenerator.createBranchXid(1, globalTxId, "oracle-branch");
        Xid sqlServerXid = XidGenerator.createBranchXid(1, globalTxId, "sqlserver-branch");
        
        try {
            // Start distributed transaction
            oracleXARes.start(oracleXid, XAResource.TMNOFLAGS);
            sqlServerXARes.start(sqlServerXid, XAResource.TMNOFLAGS);
            
            // Execute work on both databases
            try (PreparedStatement ps = oracleConn.prepareStatement(
                    "INSERT INTO xa_test_baseline (test_name, test_value) VALUES (?, ?)")) {
                ps.setString(1, "dist_mixed_oracle");
                ps.setString(2, "from_oracle");
                ps.executeUpdate();
            }
            
            try (PreparedStatement ps = sqlServerConn.prepareStatement(
                    "INSERT INTO xa_test_baseline (test_name, test_value) VALUES (?, ?)")) {
                ps.setString(1, "dist_mixed_sqlserver");
                ps.setString(2, "from_sqlserver");
                ps.executeUpdate();
            }
            
            // End transactions
            oracleXARes.end(oracleXid, XAResource.TMSUCCESS);
            sqlServerXARes.end(sqlServerXid, XAResource.TMSUCCESS);
            
            // Two-phase commit
            int oraclePrepare = oracleXARes.prepare(oracleXid);
            int sqlServerPrepare = sqlServerXARes.prepare(sqlServerXid);
            
            assertTrue(oraclePrepare == XAResource.XA_OK || oraclePrepare == XAResource.XA_RDONLY,
                    "Oracle should prepare successfully");
            assertTrue(sqlServerPrepare == XAResource.XA_OK || sqlServerPrepare == XAResource.XA_RDONLY,
                    "SQL Server should prepare successfully");
            
            // Commit both
            if (oraclePrepare == XAResource.XA_OK) {
                oracleXARes.commit(oracleXid, false);
            }
            if (sqlServerPrepare == XAResource.XA_OK) {
                sqlServerXARes.commit(sqlServerXid, false);
            }
            
            // Verify atomicity
            assertTrue(verifyDataExists(oracleConn, "dist_mixed_oracle", "from_oracle"),
                    "Oracle data should be committed");
            assertTrue(verifyDataExists(sqlServerConn, "dist_mixed_sqlserver", "from_sqlserver"),
                    "SQL Server data should be committed");
            
        } finally {
            cleanupTestData(oracleConn, "dist_mixed_oracle");
            cleanupTestData(sqlServerConn, "dist_mixed_sqlserver");
        }
    }

    /**
     * Test Case 9.3: Distributed Transaction Rollback
     * 
     * Validates that when a distributed transaction is rolled back, no changes
     * are committed in any participating database.
     */
    @Test
    @Order(3)
    @DisplayName("9.3: Oracle + DB2 - distributed rollback")
    public void testOracleAndDB2_DistributedRollback() throws Exception {
        XADataSource oracleXADS = oracleContainer.createXADataSource();
        XADataSource db2XADS = db2Container.createXADataSource();
        
        XAConnection oracleXAConn = oracleXADS.getXAConnection();
        XAConnection db2XAConn = db2XADS.getXAConnection();
        
        XAResource oracleXARes = oracleXAConn.getXAResource();
        XAResource db2XARes = db2XAConn.getXAResource();
        
        Connection oracleConn = oracleXAConn.getConnection();
        Connection db2Conn = db2XAConn.getConnection();
        
        String globalTxId = "DIST-ROLLBACK-" + System.currentTimeMillis();
        Xid oracleXid = XidGenerator.createBranchXid(1, globalTxId, "oracle-branch");
        Xid db2Xid = XidGenerator.createBranchXid(1, globalTxId, "db2-branch");
        
        try {
            // Start distributed transaction
            oracleXARes.start(oracleXid, XAResource.TMNOFLAGS);
            db2XARes.start(db2Xid, XAResource.TMNOFLAGS);
            
            // Execute work
            try (PreparedStatement ps = oracleConn.prepareStatement(
                    "INSERT INTO xa_test_baseline (test_name, test_value) VALUES (?, ?)")) {
                ps.setString(1, "dist_rollback_oracle");
                ps.setString(2, "should_rollback");
                ps.executeUpdate();
            }
            
            try (PreparedStatement ps = db2Conn.prepareStatement(
                    "INSERT INTO xa_test_baseline (test_name, test_value) VALUES (?, ?)")) {
                ps.setString(1, "dist_rollback_db2");
                ps.setString(2, "should_rollback");
                ps.executeUpdate();
            }
            
            // End transactions
            oracleXARes.end(oracleXid, XAResource.TMSUCCESS);
            db2XARes.end(db2Xid, XAResource.TMSUCCESS);
            
            // Rollback instead of commit
            oracleXARes.rollback(oracleXid);
            db2XARes.rollback(db2Xid);
            
            // Verify: No data should be committed
            assertFalse(verifyDataExists(oracleConn, "dist_rollback_oracle", "should_rollback"),
                    "Oracle data should NOT be committed");
            assertFalse(verifyDataExists(db2Conn, "dist_rollback_db2", "should_rollback"),
                    "DB2 data should NOT be committed");
            
        } finally {
            // Cleanup in case data leaked
            cleanupTestData(oracleConn, "dist_rollback_oracle");
            cleanupTestData(db2Conn, "dist_rollback_db2");
        }
    }

    /**
     * Test Case 9.4: Distributed Transaction Partial Prepare Failure
     * 
     * Validates handling when one resource fails during the prepare phase.
     * According to XA spec, if any resource fails prepare, all must rollback.
     */
    @Test
    @Order(4)
    @DisplayName("9.4: SQL Server + DB2 - partial prepare failure handling")
    public void testSQLServerAndDB2_PartialPrepareFailure() throws Exception {
        XADataSource sqlServerXADS = sqlServerContainer.createXADataSource();
        XADataSource db2XADS = db2Container.createXADataSource();
        
        XAConnection sqlServerXAConn = sqlServerXADS.getXAConnection();
        XAConnection db2XAConn = db2XADS.getXAConnection();
        
        XAResource sqlServerXARes = sqlServerXAConn.getXAResource();
        XAResource db2XARes = db2XAConn.getXAResource();
        
        Connection sqlServerConn = sqlServerXAConn.getConnection();
        Connection db2Conn = db2XAConn.getConnection();
        
        String globalTxId = "DIST-FAIL-" + System.currentTimeMillis();
        Xid sqlServerXid = XidGenerator.createBranchXid(1, globalTxId, "sqlserver-branch");
        Xid db2Xid = XidGenerator.createBranchXid(1, globalTxId, "db2-branch");
        
        try {
            // Start transactions
            sqlServerXARes.start(sqlServerXid, XAResource.TMNOFLAGS);
            db2XARes.start(db2Xid, XAResource.TMNOFLAGS);
            
            // Execute work
            try (PreparedStatement ps = sqlServerConn.prepareStatement(
                    "INSERT INTO xa_test_baseline (test_name, test_value) VALUES (?, ?)")) {
                ps.setString(1, "dist_fail_sqlserver");
                ps.setString(2, "should_fail");
                ps.executeUpdate();
            }
            
            try (PreparedStatement ps = db2Conn.prepareStatement(
                    "INSERT INTO xa_test_baseline (test_name, test_value) VALUES (?, ?)")) {
                ps.setString(1, "dist_fail_db2");
                ps.setString(2, "should_fail");
                ps.executeUpdate();
            }
            
            // End first transaction successfully
            sqlServerXARes.end(sqlServerXid, XAResource.TMSUCCESS);
            
            // End second transaction with failure flag
            db2XARes.end(db2Xid, XAResource.TMFAIL);
            
            // Try to prepare first resource - should succeed
            int sqlServerPrepare = sqlServerXARes.prepare(sqlServerXid);
            assertTrue(sqlServerPrepare == XAResource.XA_OK || sqlServerPrepare == XAResource.XA_RDONLY,
                    "SQL Server prepare should succeed");
            
            // Second resource marked as failed - cannot prepare
            // Instead of prepare, we must rollback
            
            // Rollback both resources since one failed
            if (sqlServerPrepare == XAResource.XA_OK) {
                sqlServerXARes.rollback(sqlServerXid);
            }
            db2XARes.rollback(db2Xid);
            
            // Verify: No data should be committed (atomicity preserved)
            assertFalse(verifyDataExists(sqlServerConn, "dist_fail_sqlserver", "should_fail"),
                    "SQL Server data should NOT be committed");
            assertFalse(verifyDataExists(db2Conn, "dist_fail_db2", "should_fail"),
                    "DB2 data should NOT be committed");
            
        } finally {
            cleanupTestData(sqlServerConn, "dist_fail_sqlserver");
            cleanupTestData(db2Conn, "dist_fail_db2");
        }
    }

    /**
     * Helper method to verify if data exists in the database
     */
    private boolean verifyDataExists(Connection conn, String testName, String expectedValue) throws Exception {
        String sql = "SELECT test_value FROM xa_test_baseline WHERE test_name = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, testName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String actualValue = rs.getString("test_value");
                    return expectedValue.equals(actualValue);
                }
                return false;
            }
        }
    }

    /**
     * Helper method to cleanup test data
     */
    private void cleanupTestData(Connection conn, String testName) {
        try {
            String sql = "DELETE FROM xa_test_baseline WHERE test_name = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, testName);
                ps.executeUpdate();
            }
            conn.commit();
        } catch (Exception e) {
            // Best effort cleanup
        }
    }
}
