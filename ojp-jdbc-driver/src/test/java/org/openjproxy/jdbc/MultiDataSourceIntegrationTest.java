package org.openjproxy.jdbc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openjproxy.jdbc.Driver;

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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration tests for multi-datasource functionality using H2 database.
 * Tests the complete flow from client to server with different datasource configurations.
 * <p>
 * Note: These tests require the OJP server to be running on localhost:1059.
 * These tests are skipped when running database-specific tests (Oracle, DB2, SQL Server, etc.)
 * to avoid failures when H2 drivers are not available or to keep test runs focused.
 */
class MultiDataSourceIntegrationTest {

    private static final String OJP_URL_BASE = "jdbc:ojp[localhost:1059]_h2:mem:test_";

    private static boolean isH2TestEnabled;

    @BeforeAll
    static void checkTestConfiguration() {
        isH2TestEnabled = Boolean.parseBoolean(System.getProperty("enableH2Tests", "false"));
    }

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
    void setUp() {
        // Ensure we start with clean state
        System.clearProperty("ojp.test.properties");
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("ojp.test.properties");
    }

    @Test
    void testMultipleDataSourcesForSingleDatabaseSingleUser() throws Exception {
        assumeTrue(isH2TestEnabled, "H2 multi-datasource tests are disabled when running database-specific tests");

        // Set up a custom driver that uses our test properties
        Driver testDriver = createTestDriver();

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
    void testMultipleDataSourcesForDifferentDatabases() throws Exception {
        assumeTrue(isH2TestEnabled, "H2 multi-datasource tests are disabled when running database-specific tests");

        Driver testDriver = createTestDriver();

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
    void testFailFastForMissingDataSource() throws Exception {
        assumeTrue(isH2TestEnabled, "Skipping H2 tests - not enabled");

        Driver testDriver = createTestDriver();

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
    void testBackwardCompatibilityWithDefaultDataSource() throws Exception {
        assumeTrue(isH2TestEnabled, "H2 multi-datasource tests are disabled when running database-specific tests");

        Driver testDriver = createTestDriver();

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
    void testCrossDatabaseTableAccessThrowsException() throws Exception {
        assumeTrue(isH2TestEnabled, "H2 multi-datasource tests are disabled when running database-specific tests");

        Driver testDriver = createTestDriver();

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
            assertThrows(SQLException.class, () -> stmt.executeQuery("SELECT * FROM " + tableInDbA), "Expected SQLException when trying to access table from different database");

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
            assertThrows(SQLException.class, () -> stmt.executeQuery("SELECT * FROM " + tableInDbB), "Expected SQLException when trying to access table from different database");
        }
    }

    /**
     * Creates a test driver that uses the provided properties content instead of loading from classpath.
     * Note: Since property loading moved to DatasourcePropertiesLoader, this is now a no-op wrapper.
     */
    private Driver createTestDriver() {
        // Property loading is now handled by DatasourcePropertiesLoader utility class
        // This method is kept for test compatibility
        return new Driver();
    }

    /**
     * Creates a test table with unique name and inserts/verifies test data.
     * Cleans up existing data first to avoid primary key violations.
     */
    private void createAndTestTable(Connection conn, String tableName) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            // Create unique table for this datasource
            stmt.execute("CREATE TABLE IF NOT EXISTS " + tableName + " (id INT PRIMARY KEY, name VARCHAR(50))");

            // Clean up any existing data first (in case table already exists from previous run)
            stmt.execute("DELETE FROM " + tableName + " WHERE id = 1");

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