package openjdbcproxy.jdbc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the new data source-based connection pool configuration.
 * Tests multiple scenarios using H2 in-memory databases to ensure data sources are properly isolated.
 */
public class DataSourcePoolConfigurationIntegrationTest {

    private static final String BASE_H2_URL = "jdbc:ojp[localhost:1059]_h2:mem:";
    private static final String USER = "sa";
    private static final String PASSWORD = "";

    @BeforeEach
    public void setUp() throws SQLException {
        // Ensure driver is loaded
        try {
            Class.forName("org.openjdbcproxy.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            fail("Driver not found: " + e.getMessage());
        }
    }

    @AfterEach
    public void tearDown() {
        // Cleanup is handled by H2's in-memory database lifecycle
    }

    @Test
    @DisplayName("Test multiple data sources for single database and single user")
    public void testMultipleDataSourcesSingleDatabaseSingleUser() throws SQLException {
        String dbUrl = BASE_H2_URL + "testdb1";
        
        // Create connections using different data sources but same database/user
        Connection fastConn = createConnectionWithDataSource(dbUrl, "fast", USER, PASSWORD);
        Connection slowConn = createConnectionWithDataSource(dbUrl, "slow", USER, PASSWORD);
        Connection defaultConn = createConnectionWithDataSource(dbUrl, null, USER, PASSWORD);  // default data source
        
        try {
            // All connections should work and access the same database
            // Create a table through one connection
            createAndInsertTestData(fastConn, "shared_table", "fast_data");
            
            // Verify all connections can access the same data (they share the same database)
            assertTableContainsData(fastConn, "shared_table", "fast_data");
            assertTableContainsData(slowConn, "shared_table", "fast_data");
            assertTableContainsData(defaultConn, "shared_table", "fast_data");
            
            // Test that all connections can perform operations
            Statement fastStmt = fastConn.createStatement();
            Statement slowStmt = slowConn.createStatement();
            Statement defaultStmt = defaultConn.createStatement();
            
            // Insert through different connections
            fastStmt.executeUpdate("INSERT INTO shared_table (id, data) VALUES (2, 'fast_insert')");
            slowStmt.executeUpdate("INSERT INTO shared_table (id, data) VALUES (3, 'slow_insert')");
            defaultStmt.executeUpdate("INSERT INTO shared_table (id, data) VALUES (4, 'default_insert')");
            
            // Verify all data is accessible through all connections
            ResultSet rs = fastStmt.executeQuery("SELECT COUNT(*) FROM shared_table");
            rs.next();
            assertEquals(4, rs.getInt(1), "Should have 4 rows total");
            rs.close();
            
            fastStmt.close();
            slowStmt.close();
            defaultStmt.close();
            
        } finally {
            fastConn.close();
            slowConn.close();
            defaultConn.close();
        }
    }

    @Test
    @DisplayName("Test multiple data sources for single database with different users")
    public void testMultipleDataSourcesSingleDatabaseDifferentUsers() throws SQLException {
        String dbUrl = BASE_H2_URL + "testdb2";
        
        // Note: H2 doesn't enforce user security in memory mode, but we can still test the configuration
        Connection adminConn = createConnectionWithDataSource(dbUrl, "admin", "admin", PASSWORD);
        Connection userConn = createConnectionWithDataSource(dbUrl, "user", "user", PASSWORD);
        
        try {
            // Create unique tables for each user role
            createAndInsertTestData(adminConn, "admin_config", "admin_data");
            createAndInsertTestData(userConn, "user_data", "user_info");
            
            // Verify isolation
            assertTableContainsData(adminConn, "admin_config", "admin_data");
            assertTableContainsData(userConn, "user_data", "user_info");
            
        } finally {
            adminConn.close();
            userConn.close();
        }
    }

    @Test
    @DisplayName("Test multiple data sources for different databases")
    public void testMultipleDataSourcesDifferentDatabases() throws SQLException {
        String primaryDbUrl = BASE_H2_URL + "primary_db";
        String analyticsDbUrl = BASE_H2_URL + "analytics_db";
        String reportsDbUrl = BASE_H2_URL + "reports_db";
        
        Connection primaryConn = createConnectionWithDataSource(primaryDbUrl, "primary", USER, PASSWORD);
        Connection analyticsConn = createConnectionWithDataSource(analyticsDbUrl, "analytics", USER, PASSWORD);
        Connection reportsConn = createConnectionWithDataSource(reportsDbUrl, "reports", USER, PASSWORD);
        
        try {
            // Create unique tables in each database
            createAndInsertTestData(primaryConn, "transactions", "transaction_data");
            createAndInsertTestData(analyticsConn, "metrics", "analytics_data");
            createAndInsertTestData(reportsConn, "summaries", "report_data");
            
            // Verify each database has its own data
            assertTableContainsData(primaryConn, "transactions", "transaction_data");
            assertTableContainsData(analyticsConn, "metrics", "analytics_data");
            assertTableContainsData(reportsConn, "summaries", "report_data");
            
            // Verify cross-database isolation (tables don't exist in other databases)
            assertTableNotExists(primaryConn, "metrics");
            assertTableNotExists(analyticsConn, "summaries");
            assertTableNotExists(reportsConn, "transactions");
            
        } finally {
            primaryConn.close();
            analyticsConn.close();
            reportsConn.close();
        }
    }

    @Test
    @DisplayName("Test error handling for non-existent data source")
    public void testNonExistentDataSourceFailsFast() {
        String dbUrl = BASE_H2_URL + "testdb3";
        
        Exception exception = assertThrows(Exception.class, () -> {
            createConnectionWithDataSource(dbUrl, "nonexistent", USER, PASSWORD);
        });
        
        assertTrue(exception.getMessage().contains("Data source 'nonexistent' not found"));
        assertTrue(exception.getMessage().contains("Available data sources"));
    }

    @Test
    @DisplayName("Test default data source when no dataSourceName specified")
    public void testDefaultDataSourceUsage() throws SQLException {
        String dbUrl = BASE_H2_URL + "testdb4";
        
        Properties props = new Properties();
        props.setProperty("user", USER);
        props.setProperty("password", PASSWORD);
        // No dataSourceName property - should use default
        
        Connection conn = DriverManager.getConnection(dbUrl, props);
        
        try {
            createAndInsertTestData(conn, "default_test", "default_value");
            assertTableContainsData(conn, "default_test", "default_value");
        } finally {
            conn.close();
        }
    }

    /**
     * Creates a connection with the specified data source name.
     */
    private Connection createConnectionWithDataSource(String url, String dataSourceName, String user, String password) 
            throws SQLException {
        String finalUrl = url;
        
        if (dataSourceName != null) {
            // Convert from "jdbc:ojp[localhost:1059]_h2:mem:testdb" 
            // to "jdbc:ojp[localhost:1059>dataSourceName]_h2:mem:testdb"
            finalUrl = url.replace("ojp[localhost:1059]", "ojp[localhost:1059>" + dataSourceName + "]");
        }
        
        return DriverManager.getConnection(finalUrl, user, password);
    }

    /**
     * Creates a test table and inserts data.
     */
    private void createAndInsertTestData(Connection conn, String tableName, String testData) throws SQLException {
        Statement stmt = conn.createStatement();
        
        // Drop table if it exists (for cleanup)
        try {
            stmt.executeUpdate(String.format("DROP TABLE IF EXISTS %s", tableName));
        } catch (SQLException e) {
            // Ignore - table might not exist
        }
        
        // Create table
        String createSql = String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, data VARCHAR(100))", tableName);
        stmt.executeUpdate(createSql);
        
        // Insert test data
        String insertSql = String.format(
            "INSERT INTO %s (id, data) VALUES (1, '%s')", tableName, testData);
        stmt.executeUpdate(insertSql);
        
        stmt.close();
    }

    /**
     * Asserts that a table contains the expected data.
     */
    private void assertTableContainsData(Connection conn, String tableName, String expectedData) throws SQLException {
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(String.format("SELECT data FROM %s WHERE id = 1", tableName));
        
        assertTrue(rs.next(), "Table " + tableName + " should contain data");
        assertEquals(expectedData, rs.getString("data"), 
            "Table " + tableName + " should contain expected data");
        assertFalse(rs.next(), "Table " + tableName + " should contain only one row");
        
        rs.close();
        stmt.close();
    }

    /**
     * Asserts that a table does not exist (or query fails).
     */
    private void assertTableNotExists(Connection conn, String tableName) {
        assertThrows(SQLException.class, () -> {
            Statement stmt = conn.createStatement();
            stmt.executeQuery(String.format("SELECT COUNT(*) FROM %s", tableName));
            stmt.close();
        }, "Table " + tableName + " should not exist in this database/connection");
    }
}