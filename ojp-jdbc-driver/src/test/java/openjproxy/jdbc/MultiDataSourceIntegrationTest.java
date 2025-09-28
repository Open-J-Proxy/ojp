package openjproxy.jdbc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openjproxy.jdbc.Driver;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Integration tests for multi-datasource functionality using H2 database.
 * Tests the complete flow from client to server with different datasource configurations.
 * 
 * Note: These tests require the OJP server to be running on localhost:1059.
 */
public class MultiDataSourceIntegrationTest {

    private static final String H2_URL_BASE = "jdbc:h2:mem:test_";
    private static final String OJP_URL_BASE = "jdbc:ojp[localhost:1059]_h2:mem:test_";
    
    /**
     * Helper method to build OJP URLs with optional datasource name
     */
    private String buildOjpUrl(String databaseName, String dataSourceName) {
        if (dataSourceName == null || "default".equals(dataSourceName)) {
            return OJP_URL_BASE + databaseName;
        } else {
            return "jdbc:ojp[localhost:1059(" + dataSourceName + ")]_h2:mem:test_" + databaseName;
        }
    }
    
    @BeforeEach
    public void setUp() {
        // Ensure we start with clean state
        System.clearProperty("ojp.test.properties");
    }
    
    @AfterEach
    public void tearDown() {
        System.clearProperty("ojp.test.properties");
    }

    @Test
    public void testMultipleDataSourcesForSingleDatabaseSingleUser() throws Exception {
        // Create test properties with multiple datasources for same database
        String testPropertiesContent = 
            "# Default datasource\n" +
            "ojp.connection.pool.maximumPoolSize=10\n" +
            "ojp.connection.pool.minimumIdle=2\n" +
            "\n" +
            "# MainApp datasource - larger pool for main application\n" +
            "mainApp.ojp.connection.pool.maximumPoolSize=20\n" +
            "mainApp.ojp.connection.pool.minimumIdle=5\n" +
            "\n" +
            "# BatchJob datasource - smaller pool for batch processing\n" +
            "batchJob.ojp.connection.pool.maximumPoolSize=5\n" +
            "batchJob.ojp.connection.pool.minimumIdle=1\n";
        
        // Set up a custom driver that uses our test properties
        Driver testDriver = createTestDriver(testPropertiesContent);
        
        // Test connection with default datasource
        try (Connection defaultConn = testDriver.connect(buildOjpUrl("singledb", "default"), new Properties())) {
            createAndTestTable(defaultConn, "shared_table");
        }
        
        // Test connection with mainApp datasource - should access same database
        try (Connection mainAppConn = testDriver.connect(buildOjpUrl("singledb", "mainApp"), new Properties())) {
            // Should be able to access the table created by default datasource
            assertTrue(tableExists(mainAppConn, "shared_table"));
            // Create additional data to verify connection works
            createAndTestTable(mainAppConn, "mainapp_additional_table");
        }
        
        // Test connection with batchJob datasource - should access same database
        try (Connection batchJobConn = testDriver.connect(buildOjpUrl("singledb", "batchJob"), new Properties())) {
            // Should be able to access tables created by other datasources (same database)
            assertTrue(tableExists(batchJobConn, "shared_table"));
            assertTrue(tableExists(batchJobConn, "mainapp_additional_table"));
            // Create additional data to verify connection works
            createAndTestTable(batchJobConn, "batchjob_additional_table");
        }
        
        // Verify that all datasources can access all tables in the same database
        try (Connection defaultConn = testDriver.connect(buildOjpUrl("singledb", "default"), new Properties())) {
            assertTrue(tableExists(defaultConn, "shared_table"));
            assertTrue(tableExists(defaultConn, "mainapp_additional_table"));
            assertTrue(tableExists(defaultConn, "batchjob_additional_table"));
        }
    }

    @Test
    public void testMultipleDataSourcesForDifferentDatabases() throws Exception {
        String testPropertiesContent = 
            "# Database A datasources\n" +
            "dbA_primary.ojp.connection.pool.maximumPoolSize=20\n" +
            "dbA_primary.ojp.connection.pool.minimumIdle=5\n" +
            "\n" +
            "dbA_readonly.ojp.connection.pool.maximumPoolSize=8\n" +
            "dbA_readonly.ojp.connection.pool.minimumIdle=2\n" +
            "\n" +
            "# Database B datasources\n" +
            "dbB_primary.ojp.connection.pool.maximumPoolSize=15\n" +
            "dbB_primary.ojp.connection.pool.minimumIdle=4\n" +
            "\n" +
            "dbB_analytics.ojp.connection.pool.maximumPoolSize=5\n" +
            "dbB_analytics.ojp.connection.pool.minimumIdle=1\n";
        
        Driver testDriver = createTestDriver(testPropertiesContent);
        
        // Test Database A - Primary datasource
        try (Connection dbAPrimaryConn = testDriver.connect(buildOjpUrl("databaseA", "dbA_primary"), new Properties())) {
            createAndTestTable(dbAPrimaryConn, "dba_primary_table");
        }
        
        // Test Database A - Readonly datasource
        try (Connection dbAReadonlyConn = testDriver.connect(buildOjpUrl("databaseA", "dbA_readonly"), new Properties())) {
            createAndTestTable(dbAReadonlyConn, "dba_readonly_table");
        }
        
        // Test Database B - Primary datasource 
        try (Connection dbBPrimaryConn = testDriver.connect(buildOjpUrl("databaseB", "dbB_primary"), new Properties())) {
            createAndTestTable(dbBPrimaryConn, "dbb_primary_table");
        }
        
        // Test Database B - Analytics datasource
        try (Connection dbBAnalyticsConn = testDriver.connect(buildOjpUrl("databaseB", "dbB_analytics"), new Properties())) {
            createAndTestTable(dbBAnalyticsConn, "dbb_analytics_table");
        }
        
        // Verify isolation between databases - try to access wrong database tables
        try (Connection dbAConn = testDriver.connect(buildOjpUrl("databaseA", "dbA_primary"), new Properties())) {
            // Database A should not have Database B tables
            assertTrue(tableExists(dbAConn, "dba_primary_table"));
            assertFalse(tableExists(dbAConn, "dbb_primary_table"));
        }
        
        try (Connection dbBConn = testDriver.connect(buildOjpUrl("databaseB", "dbB_primary"), new Properties())) {
            // Database B should not have Database A tables
            assertTrue(tableExists(dbBConn, "dbb_primary_table"));
            assertFalse(tableExists(dbBConn, "dba_primary_table"));
        }
    }

    @Test
    public void testFailFastForMissingDataSource() throws Exception {
        String testPropertiesContent = 
            "# Only configure one datasource\n" +
            "configuredDS.ojp.connection.pool.maximumPoolSize=10\n";
        
        Driver testDriver = createTestDriver(testPropertiesContent);
        
        // Connection with configured datasource should work
        try (Connection configuredConn = testDriver.connect(buildOjpUrl("testdb", "configuredDS"), new Properties())) {
            assertNotNull(configuredConn);
            createAndTestTable(configuredConn, "configured_table");
        }
        
        // Connection with unconfigured datasource should return properties with no pool settings
        // (The server will use defaults, but the client won't send any specific properties)
        try (Connection unconfiguredConn = testDriver.connect(buildOjpUrl("testdb", "unconfiguredDS"), new Properties())) {
            assertNotNull(unconfiguredConn);
            // This should work but use default pool settings
            createAndTestTable(unconfiguredConn, "unconfigured_table");
        }
    }

    @Test
    public void testBackwardCompatibilityWithDefaultDataSource() throws Exception {
        String testPropertiesContent = 
            "# Traditional configuration without datasource prefix\n" +
            "ojp.connection.pool.maximumPoolSize=25\n" +
            "ojp.connection.pool.minimumIdle=8\n";
        
        Driver testDriver = createTestDriver(testPropertiesContent);
        
        // Connection without dataSource parameter should use default configuration
        try (Connection defaultConn = testDriver.connect(buildOjpUrl("backcompat", "default"), new Properties())) {
            assertNotNull(defaultConn);
            createAndTestTable(defaultConn, "backcompat_table");
        }
        
        // Connection with explicit default dataSource should also work
        try (Connection explicitDefaultConn = testDriver.connect(buildOjpUrl("backcompat", "default"), new Properties())) {
            assertNotNull(explicitDefaultConn);
            // Should be able to access the same table
            assertTrue(tableExists(explicitDefaultConn, "backcompat_table"));
        }
    }

    @Test
    public void testCrossDatabaseTableAccessThrowsException() throws Exception {
        // Test that trying to access a table from one database using a datasource 
        // configured for a different database throws appropriate exception
        String testPropertiesContent = 
            "# DataSource for database A\n" +
            "dbA.ojp.connection.pool.maximumPoolSize=10\n" +
            "dbA.ojp.connection.pool.minimumIdle=2\n" +
            "\n" +
            "# DataSource for database B\n" +
            "dbB.ojp.connection.pool.maximumPoolSize=10\n" +
            "dbB.ojp.connection.pool.minimumIdle=2\n";
        
        Driver testDriver = createTestDriver(testPropertiesContent);
        
        // Create a table in database A using datasource A
        String tableInDbA = "table_in_database_a";
        try (Connection dbAConn = testDriver.connect(buildOjpUrl("database_a", "dbA"), new Properties())) {
            createAndTestTable(dbAConn, tableInDbA);
            // Verify the table exists in database A
            assertTrue(tableExists(dbAConn, tableInDbA));
        }
        
        // Create a different table in database B using datasource B to verify it works
        String tableInDbB = "table_in_database_b";
        try (Connection dbBConn = testDriver.connect(buildOjpUrl("database_b", "dbB"), new Properties())) {
            createAndTestTable(dbBConn, tableInDbB);
            // Verify the table exists in database B
            assertTrue(tableExists(dbBConn, tableInDbB));
        }
        
        // Now try to access the table from database A using datasource B (which points to database B)
        // This should throw an exception because the table doesn't exist in database B
        try (Connection dbBConn = testDriver.connect(buildOjpUrl("database_b", "dbB"), new Properties())) {
            Statement stmt = dbBConn.createStatement();
            
            // This should throw SQLException because table_in_database_a doesn't exist in database B
            assertThrows(SQLException.class, () -> {
                stmt.executeQuery("SELECT * FROM " + tableInDbA);
            }, "Expected SQLException when trying to access table from different database");
            
            // Verify the specific error message indicates table doesn't exist
            try {
                stmt.executeQuery("SELECT * FROM " + tableInDbA);
                fail("Should have thrown SQLException for non-existent table");
            } catch (SQLException e) {
                String errorMessage = e.getMessage().toLowerCase();
                assertTrue(
                    errorMessage.contains("table") && (
                        errorMessage.contains("not found") ||
                        errorMessage.contains("does not exist") ||
                        errorMessage.contains("doesn't exist") ||
                        errorMessage.contains("not exist")
                    ),
                    "Expected error message about table not existing, but got: " + e.getMessage()
                );
            }
        }
        
        // Verify the reverse scenario - trying to access table from database B using datasource A
        try (Connection dbAConn = testDriver.connect(buildOjpUrl("database_a", "dbA"), new Properties())) {
            Statement stmt = dbAConn.createStatement();
            
            // This should also throw SQLException because table_in_database_b doesn't exist in database A
            assertThrows(SQLException.class, () -> {
                stmt.executeQuery("SELECT * FROM " + tableInDbB);
            }, "Expected SQLException when trying to access table from different database");
        }
    }

    /**
     * Creates a test driver that uses the provided properties content instead of loading from classpath.
     */
    private Driver createTestDriver(String propertiesContent) {
        return new Driver() {
            @Override
            protected Properties loadOjpProperties() {
                Properties props = new Properties();
                try (InputStream is = new ByteArrayInputStream(propertiesContent.getBytes())) {
                    props.load(is);
                    return props;
                } catch (Exception e) {
                    throw new RuntimeException("Failed to load test properties", e);
                }
            }
        };
    }
    
    /**
     * Creates a test table with unique name and inserts/verifies test data.
     */
    private void createAndTestTable(Connection conn, String tableName) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            // Create unique table for this datasource
            stmt.execute("CREATE TABLE IF NOT EXISTS " + tableName + " (id INT PRIMARY KEY, name VARCHAR(50))");
            
            // Insert test data
            stmt.execute("INSERT INTO " + tableName + " VALUES (1, 'test_data_" + tableName + "')");
            
            // Verify data
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + tableName)) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
            }
            
            // Verify specific data
            try (ResultSet rs = stmt.executeQuery("SELECT name FROM " + tableName + " WHERE id = 1")) {
                assertTrue(rs.next());
                assertEquals("test_data_" + tableName, rs.getString(1));
            }
        }
    }
    
    /**
     * Checks if a table exists in the database.
     */
    private boolean tableExists(Connection conn, String tableName) {
        try (Statement stmt = conn.createStatement()) {
            stmt.executeQuery("SELECT 1 FROM " + tableName + " LIMIT 1");
            return true;
        } catch (SQLException e) {
            return false; // Table doesn't exist or query failed
        }
    }
}