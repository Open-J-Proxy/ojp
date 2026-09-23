package org.openjproxy.xa.baseline.containers;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.openjproxy.xa.baseline.common.XidGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.XAConnection;
import javax.sql.XADataSource;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke test to verify Oracle XA container setup and configuration.
 * 
 * This test validates Phase 2 deliverables:
 * - OracleXAContainer starts successfully
 * - XA DataSource can be created
 * - XA Connection and XA Resource can be obtained
 * - XA permissions are properly configured
 * - Basic XA operations work
 * 
 * These tests are disabled by default and only run when -DenableOracleTests=true
 */
@EnabledIf("org.openjproxy.xa.baseline.containers.OracleXATestContainer#isEnabled")
public class OracleXAContainerSmokeTest {
    
    private static final Logger logger = LoggerFactory.getLogger(OracleXAContainerSmokeTest.class);
    
    private static OracleXAContainer oracleContainer;
    private static XADataSource xaDataSource;
    
    @BeforeAll
    public static void setUpClass() throws Exception {
        logger.info("Setting up Oracle XA Container for smoke test...");
        
        // Create Oracle container wrapper (uses singleton internally)
        oracleContainer = new OracleXAContainer();
        
        logger.info("Oracle XA Container is ready");
        logger.info("JDBC URL: {}", oracleContainer.getJdbcUrl());
        
        // Create XA DataSource
        xaDataSource = oracleContainer.createXADataSource();
        assertNotNull(xaDataSource, "XA DataSource should not be null");
        
        logger.info("XA DataSource created successfully");
    }
    
    @AfterAll
    public static void tearDownClass() {
        logger.info("Test completed - singleton container managed by shutdown hook");
        // No explicit stop needed - singleton container is managed by shutdown hook
    }
    
    @Test
    public void testContainerIsRunning() {
        assertTrue(oracleContainer.isRunning(), "Oracle container should be running");
    }
    
    @Test
    public void testJdbcUrlFormat() {
        String jdbcUrl = oracleContainer.getJdbcUrl();
        
        assertNotNull(jdbcUrl, "JDBC URL should not be null");
        assertTrue(jdbcUrl.startsWith("jdbc:oracle:thin:@//"), "JDBC URL should have correct format");
        assertTrue(jdbcUrl.contains("XEPDB1"), "JDBC URL should contain database name");
        
        logger.info("JDBC URL format is correct: {}", jdbcUrl);
    }
    
    @Test
    public void testXADataSourceCreation() throws Exception {
        assertNotNull(xaDataSource, "XA DataSource should be created");
    }
    
    @Test
    public void testXAConnectionCreation() throws Exception {
        XAConnection xaConnection = null;
        
        try {
            // Get XA Connection from DataSource
            xaConnection = xaDataSource.getXAConnection();
            assertNotNull(xaConnection, "XA Connection should not be null");
            
            logger.info("XA Connection created successfully");
        } finally {
            if (xaConnection != null) {
                xaConnection.close();
            }
        }
    }
    
    @Test
    public void testXAResourceCreation() throws Exception {
        XAConnection xaConnection = null;
        
        try {
            xaConnection = xaDataSource.getXAConnection();
            
            // Get XA Resource from XA Connection
            XAResource xaResource = xaConnection.getXAResource();
            assertNotNull(xaResource, "XA Resource should not be null");
            
            logger.info("XA Resource obtained successfully");
        } finally {
            if (xaConnection != null) {
                xaConnection.close();
            }
        }
    }
    
    @Test
    public void testLogicalConnectionCreation() throws Exception {
        XAConnection xaConnection = null;
        Connection connection = null;
        
        try {
            xaConnection = xaDataSource.getXAConnection();
            
            // Get logical connection for SQL operations
            connection = xaConnection.getConnection();
            assertNotNull(connection, "Logical connection should not be null");
            assertFalse(connection.getAutoCommit(), "Auto-commit should be disabled on XA connection");
            
            logger.info("Logical connection created with auto-commit disabled");
        } finally {
            if (connection != null) {
                connection.close();
            }
            if (xaConnection != null) {
                xaConnection.close();
            }
        }
    }
    
    @Test
    public void testBasicDatabaseConnectivity() throws Exception {
        XAConnection xaConnection = null;
        Connection connection = null;
        Statement statement = null;
        ResultSet resultSet = null;
        
        try {
            xaConnection = xaDataSource.getXAConnection();
            connection = xaConnection.getConnection();
            
            // Execute simple query to verify connectivity
            statement = connection.createStatement();
            resultSet = statement.executeQuery("SELECT 1 FROM DUAL");
            
            assertTrue(resultSet.next(), "Query should return a result");
            assertEquals(1, resultSet.getInt(1), "Query should return value 1");
            
            logger.info("Basic database connectivity verified");
        } finally {
            if (resultSet != null) resultSet.close();
            if (statement != null) statement.close();
            if (connection != null) connection.close();
            if (xaConnection != null) xaConnection.close();
        }
    }
    
    @Test
    public void testXATransactionStart() throws Exception {
        XAConnection xaConnection = null;
        
        try {
            xaConnection = xaDataSource.getXAConnection();
            XAResource xaResource = xaConnection.getXAResource();
            
            // Create a XID and start an XA transaction
            Xid xid = XidGenerator.createXid();
            
            // This is the basic test: can we start an XA transaction?
            xaResource.start(xid, XAResource.TMNOFLAGS);
            
            // End the transaction (required before rollback)
            xaResource.end(xid, XAResource.TMSUCCESS);
            
            // Rollback since this is just a smoke test
            xaResource.rollback(xid);
            
            logger.info("XA transaction start/end/rollback successful");
        } finally {
            if (xaConnection != null) {
                xaConnection.close();
            }
        }
    }
    
    @Test
    public void testXAPermissionsConfigured() throws Exception {
        XAConnection xaConnection = null;
        Connection connection = null;
        Statement statement = null;
        ResultSet resultSet = null;
        
        try {
            xaConnection = xaDataSource.getXAConnection();
            connection = xaConnection.getConnection();
            statement = connection.createStatement();
            
            // Try to query V$XATRANS$ - this requires SELECT privilege
            // This verifies that XA permissions were granted by the setup script
            resultSet = statement.executeQuery("SELECT COUNT(*) FROM V$XATRANS$");
            
            assertTrue(resultSet.next(), "Should be able to query V$XATRANS$");
            
            // The query succeeded, which means permissions are configured
            logger.info("XA permissions verified: Can query V$XATRANS$");
        } finally {
            if (resultSet != null) resultSet.close();
            if (statement != null) statement.close();
            if (connection != null) connection.close();
            if (xaConnection != null) xaConnection.close();
        }
    }
    
    @Test
    public void testTestTableExists() throws Exception {
        XAConnection xaConnection = null;
        Connection connection = null;
        Statement statement = null;
        ResultSet resultSet = null;
        
        try {
            xaConnection = xaDataSource.getXAConnection();
            connection = xaConnection.getConnection();
            statement = connection.createStatement();
            
            // Verify the test table was created by the setup script
            resultSet = statement.executeQuery("SELECT COUNT(*) FROM xa_test_baseline");
            
            assertTrue(resultSet.next(), "Should be able to query xa_test_baseline table");
            
            logger.info("Test table 'xa_test_baseline' exists and is accessible");
        } finally {
            if (resultSet != null) resultSet.close();
            if (statement != null) statement.close();
            if (connection != null) connection.close();
            if (xaConnection != null) xaConnection.close();
        }
    }
    
    @Test
    public void testMultipleXAConnections() throws Exception {
        XAConnection xaConnection1 = null;
        XAConnection xaConnection2 = null;
        
        try {
            // Create two independent XA connections
            xaConnection1 = xaDataSource.getXAConnection();
            xaConnection2 = xaDataSource.getXAConnection();
            
            assertNotNull(xaConnection1, "First XA connection should not be null");
            assertNotNull(xaConnection2, "Second XA connection should not be null");
            
            // Verify they are different objects
            assertNotSame(xaConnection1, xaConnection2, "XA connections should be different objects");
            
            // Verify both can get XA resources
            XAResource xaResource1 = xaConnection1.getXAResource();
            XAResource xaResource2 = xaConnection2.getXAResource();
            
            assertNotNull(xaResource1, "First XA resource should not be null");
            assertNotNull(xaResource2, "Second XA resource should not be null");
            
            logger.info("Multiple XA connections can be created successfully");
        } finally {
            if (xaConnection1 != null) xaConnection1.close();
            if (xaConnection2 != null) xaConnection2.close();
        }
    }
}
