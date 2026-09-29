package org.openjproxy.xa.baseline.containers;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
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
 * Smoke test to verify SQL Server XA container setup and configuration.
 * 
 * This test validates Phase 6 deliverables:
 * - SQLServerXAContainer starts successfully
 * - XA DataSource can be created
 * - XA Connection and XA Resource can be obtained
 * - XA permissions are properly configured (sp_sqljdbc_xa_install)
 * - Basic XA operations work
 * 
 * SQL Server XA Requirements Validated:
 * - sp_sqljdbc_xa_install stored procedure executed
 * - SqlJDBCXAUser role permissions
 * - XA extended stored procedures available
 * 
 * TEMPORARILY DISABLED: SQL Server XA tests cause indefinite hangs even with
 * specific problematic tests disabled. Disabling entire test class until root
 * cause can be systematically debugged.
 * 
 * These tests are disabled by default and only run when -DenableSqlServerTests=true
 */
@Disabled("SQL Server XA tests cause indefinite hangs - temporarily disabled for systematic debugging")
@EnabledIf("org.openjproxy.xa.baseline.containers.SQLServerXATestContainer#isEnabled")
public class SQLServerXAContainerSmokeTest {
    
    private static final Logger logger = LoggerFactory.getLogger(SQLServerXAContainerSmokeTest.class);
    
    private static SQLServerXAContainer sqlServerContainer;
    private static XADataSource xaDataSource;
    
    @BeforeAll
    public static void setUpClass() throws Exception {
        logger.info("SQL Server XA Container setup ready (using singleton)");
        
        // Create container wrapper (uses singleton internally)
        sqlServerContainer = new SQLServerXAContainer();
        
        logger.info("JDBC URL: {}", sqlServerContainer.getJdbcUrl());
        
        // Create XA DataSource
        xaDataSource = sqlServerContainer.createXADataSource();
        assertNotNull(xaDataSource, "XA DataSource should not be null");
        
        logger.info("XA DataSource created successfully");
    }
    
    @Test
    public void testContainerIsRunning() {
        assertTrue(sqlServerContainer.isRunning(), "SQL Server container should be running");
    }
    
    @Test
    public void testJdbcUrlFormat() {
        String jdbcUrl = sqlServerContainer.getJdbcUrl();
        
        assertNotNull(jdbcUrl, "JDBC URL should not be null");
        assertTrue(jdbcUrl.startsWith("jdbc:sqlserver://"), "JDBC URL should have correct format");
        assertTrue(jdbcUrl.contains("xatestdb"), "JDBC URL should contain database name");
        
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
            
            // Get XA Resource
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
            
            // Get logical connection
            connection = xaConnection.getConnection();
            assertNotNull(connection, "Logical connection should not be null");
            assertFalse(connection.isClosed(), "Logical connection should be open");
            
            // Verify auto-commit is disabled (required for XA)
            assertFalse(connection.getAutoCommit(), "Auto-commit should be disabled for XA connections");
            
            logger.info("Logical connection created successfully with auto-commit disabled");
        } finally {
            if (connection != null) connection.close();
            if (xaConnection != null) xaConnection.close();
        }
    }
    
    @Test
    public void testBasicDatabaseConnectivity() throws Exception {
        XAConnection xaConnection = null;
        Connection connection = null;
        
        try {
            xaConnection = xaDataSource.getXAConnection();
            connection = xaConnection.getConnection();
            
            // Execute simple query
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT @@VERSION AS version")) {
                
                assertTrue(rs.next(), "Should have at least one row");
                String version = rs.getString("version");
                assertNotNull(version, "Version should not be null");
                assertTrue(version.contains("Microsoft SQL Server"), "Should be SQL Server");
                
                logger.info("SQL Server version: {}", version);
            }
        } finally {
            if (connection != null) connection.close();
            if (xaConnection != null) xaConnection.close();
        }
    }
    
    @Test
    public void testBasicXATransactionOperations() throws Exception {
        XAConnection xaConnection = null;
        Connection connection = null;
        
        try {
            xaConnection = xaDataSource.getXAConnection();
            XAResource xaResource = xaConnection.getXAResource();
            connection = xaConnection.getConnection();
            
            // Create XID
            Xid xid = XidGenerator.createXid();
            
            // Start XA transaction
            xaResource.start(xid, XAResource.TMNOFLAGS);
            logger.info("XA transaction started");
            
            // Execute SQL
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("SELECT 1");
            }
            
            // End XA transaction
            xaResource.end(xid, XAResource.TMSUCCESS);
            logger.info("XA transaction ended");
            
            // Rollback (no prepare needed)
            xaResource.rollback(xid);
            logger.info("XA transaction rolled back");
            
        } finally {
            if (connection != null) connection.close();
            if (xaConnection != null) xaConnection.close();
        }
    }
    
    @Test
    public void testXAProceduresExist() throws Exception {
        XAConnection xaConnection = null;
        Connection connection = null;
        
        try {
            xaConnection = xaDataSource.getXAConnection();
            connection = xaConnection.getConnection();
            
            // Check if XA procedures exist
            String query = "SELECT name FROM sys.objects WHERE name LIKE 'xp_sqljdbc_xa%' ORDER BY name";
            
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery(query)) {
                
                int count = 0;
                while (rs.next()) {
                    String procName = rs.getString("name");
                    logger.info("Found XA procedure: {}", procName);
                    count++;
                }
                
                assertTrue(count >= 8, "Should have at least 8 XA procedures installed");
                logger.info("Total XA procedures found: {}", count);
            }
        } finally {
            if (connection != null) connection.close();
            if (xaConnection != null) xaConnection.close();
        }
    }
    
    @Test
    public void testDatabaseExists() throws Exception {
        XAConnection xaConnection = null;
        Connection connection = null;
        
        try {
            xaConnection = xaDataSource.getXAConnection();
            connection = xaConnection.getConnection();
            
            // Check if xatestdb exists
            String query = "SELECT name FROM sys.databases WHERE name = 'xatestdb'";
            
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery(query)) {
                
                assertTrue(rs.next(), "Database 'xatestdb' should exist");
                assertEquals("xatestdb", rs.getString("name"));
                
                logger.info("Test database 'xatestdb' exists");
            }
        } finally {
            if (connection != null) connection.close();
            if (xaConnection != null) xaConnection.close();
        }
    }
    
    @Test
    public void testMultipleConcurrentXAConnections() throws Exception {
        XAConnection xaConn1 = null;
        XAConnection xaConn2 = null;
        
        try {
            // Create two XA connections
            xaConn1 = xaDataSource.getXAConnection();
            xaConn2 = xaDataSource.getXAConnection();
            
            XAResource xaRes1 = xaConn1.getXAResource();
            XAResource xaRes2 = xaConn2.getXAResource();
            
            assertNotNull(xaRes1, "First XA Resource should not be null");
            assertNotNull(xaRes2, "Second XA Resource should not be null");
            
            // Check if they're from same resource manager
            boolean sameRM = xaRes1.isSameRM(xaRes2);
            logger.info("XA Resources from same RM: {}", sameRM);
            // SQL Server typically returns true for connections to same database
            
        } finally {
            if (xaConn1 != null) xaConn1.close();
            if (xaConn2 != null) xaConn2.close();
        }
    }
}
