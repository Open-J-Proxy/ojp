package org.openjproxy.jdbc.h2;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration tests for SQL session affinity feature with H2 database.
 * Tests that temporary tables work correctly across multiple requests
 * by ensuring session affinity.
 */

class H2SessionAffinityIntegrationTest {
    private static final Logger logger = LoggerFactory.getLogger(H2SessionAffinityIntegrationTest.class);

    private static boolean isH2TestEnabled;

    @BeforeAll
    static void checkTestConfiguration() {
        isH2TestEnabled = Boolean.parseBoolean(System.getProperty("enableH2Tests", "false"));
    }

    /**
     * Tests that temporary tables work across multiple SQL statements.
     * This verifies that CREATE LOCAL TEMPORARY TABLE triggers session affinity
     * and subsequent operations use the same session/connection.
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void testTemporaryTableSessionAffinity(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeTrue(isH2TestEnabled, "Skipping H2 tests - not enabled");
        logger.info("Testing temporay table with Driver: {}", driverClass);
        logger.info("Testing temporary table session affinity for H2: {}", url);

        try (Connection conn = DriverManager.getConnection(url, user, pwd); Statement stmt = conn.createStatement()) {
            // Drop temp table if it exists (cleanup from previous run)
            try {
                stmt.execute("DROP TABLE IF EXISTS temp_session_test");
            } catch (SQLException e) {
                // Ignore - table might not exist
            }

            // Create temporary table (this should trigger session affinity)
            logger.debug("Creating H2 local temporary table");
            stmt.execute("CREATE LOCAL TEMPORARY TABLE temp_session_test (id INT, \"value\" VARCHAR(100))");

            // Insert data into temporary table (should use same session)
            logger.debug("Inserting data into temporary table");
            stmt.execute("INSERT INTO temp_session_test VALUES (1, 'test_value')");

            // Query temporary table (should use same session)
            logger.debug("Querying temporary table");
            ResultSet resultSet = stmt.executeQuery("SELECT id, \"value\" FROM temp_session_test");

            // Verify data was inserted and retrieved successfully
            assertTrue(resultSet.next(), "Should have at least one row in temporary table");
            assertEquals(1, resultSet.getInt("id"), "Session data should match");
            assertEquals("test_value", resultSet.getString("value"), "Session data should match");

            // Verify no more rows
            assertFalse(resultSet.next(), "Should have only one row");

            resultSet.close();

            logger.info("H2 temporary table session affinity test passed");

        }
    }

    /**
     * Tests that multiple temporary table operations work correctly.
     * This includes creating, inserting, updating, and querying temp tables.
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void testComplexTemporaryTableOperations(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeTrue(isH2TestEnabled, "Skipping H2 tests - not enabled");
        logger.info("Testing temporay table with Driver: {}", driverClass);
        logger.info("Testing complex temporary table operations for H2: {}", url);

        try (Connection conn = DriverManager.getConnection(url, user, pwd); Statement stmt = conn.createStatement()) {
            // Cleanup
            try {
                stmt.execute("DROP TABLE IF EXISTS temp_complex");
            } catch (SQLException e) {
                // Ignore
            }

            // Create temporary table
            logger.debug("Creating complex temp table");
            stmt.execute("CREATE LOCAL TEMPORARY TABLE temp_complex (id INT PRIMARY KEY, name VARCHAR(100), amount DECIMAL(10,2))");

            // Insert multiple rows
            logger.debug("Inserting multiple rows");
            stmt.execute("INSERT INTO temp_complex VALUES (1, 'Alice', 100.50)");
            stmt.execute("INSERT INTO temp_complex VALUES (2, 'Bob', 200.75)");
            stmt.execute("INSERT INTO temp_complex VALUES (3, 'Charlie', 150.25)");

            // Update a row
            logger.debug("Updating a row");
            stmt.executeUpdate("UPDATE temp_complex SET amount = amount + 50.00 WHERE id = 2");

            // Query and verify
            logger.debug("Querying temp table");
            ResultSet rs = stmt.executeQuery("SELECT id, name, amount FROM temp_complex ORDER BY id");

            int rowCount = 0;
            while (rs.next()) {
                rowCount++;
                int id = rs.getInt("id");
                String name = rs.getString("name");
                double amount = rs.getDouble("amount");

                if (id == 1) {
                    assertEquals("Alice", name);
                    assertEquals(100.50, amount, 0.01);
                } else if (id == 2) {
                    assertEquals("Bob", name);
                    assertEquals(250.75, amount, 0.01); // Updated from 200.75 to 250.75
                } else if (id == 3) {
                    assertEquals("Charlie", name);
                    assertEquals(150.25, amount, 0.01);
                }
            }

            assertEquals(3, rowCount, "Should have 3 rows");
            rs.close();

            logger.info("H2 complex temporary table operations test passed");

        }
    }

    /**
     * Tests that temp table created outside transaction persists within same session.
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void testTemporaryTablePersistenceAcrossTransactions(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeTrue(isH2TestEnabled, "Skipping H2 tests - not enabled");
        logger.info("Testing temporay table with Driver: {}", driverClass);
        logger.info("Testing temporary table persistence across transactions for H2: {}", url);

        try (Connection conn = DriverManager.getConnection(url, user, pwd); Statement stmt = conn.createStatement()) {
            // Cleanup
            try {
                stmt.execute("DROP TABLE IF EXISTS temp_persist");
            } catch (SQLException e) {
                // Ignore
            }

            // Create temp table
            stmt.execute("CREATE LOCAL TEMPORARY TABLE temp_persist (id INT, data VARCHAR(100))");

            // Start transaction and insert
            conn.setAutoCommit(false);
            stmt.execute("INSERT INTO temp_persist VALUES (1, 'in_transaction')");
            conn.commit();

            // Start another transaction and query (should still see the temp table)
            conn.setAutoCommit(false);
            ResultSet rs = stmt.executeQuery("SELECT * FROM temp_persist WHERE id = 1");
            assertTrue(rs.next(), "Should find row inserted in previous transaction");
            assertEquals("in_transaction", rs.getString("data"), "Data should match");
            rs.close();
            conn.commit();

            logger.info("H2 temporary table persistence across transactions test passed");

        }
    }
}
