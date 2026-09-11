package org.openjproxy.jdbc.oracle;

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

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Integration tests for SQL session affinity feature with Oracle database.
 * Tests that global temporary tables work correctly across multiple requests
 * by ensuring session affinity.
 */

 class OracleSessionAffinityIntegrationTest {
    private static final Logger logger = LoggerFactory.getLogger(OracleSessionAffinityIntegrationTest.class);
    private static boolean isTestDisabled;

    @BeforeAll
     static void checkTestConfiguration() {
        isTestDisabled = !Boolean.parseBoolean(System.getProperty("enableOracleTests", "false"));
    }

    /**
     * Tests that global temporary tables work across multiple SQL statements.
     * This verifies that CREATE GLOBAL TEMPORARY TABLE triggers session affinity
     * and subsequent operations use the same session/connection.
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
     void testTemporaryTableSessionAffinity(String driverClass, String url, String user, String pwd)             throws SQLException {
        assumeFalse(isTestDisabled, "Oracle tests are disabled");
        logger.info("Testing temporay table with Driver: {}", driverClass);
        logger.info("Testing temporary table session affinity for Oracle: {}", url);

        Connection conn = DriverManager.getConnection(url, user, pwd);

        try (Statement stmt = conn.createStatement()) {
            // Oracle global temp tables are permanent, just truncate
            try {
                stmt.execute("TRUNCATE TABLE temp_session_test");
            } catch (SQLException e) {
                // Table doesn't exist, will create it
                try {
                    logger.debug("Creating Oracle global temporary table");
                    stmt.execute("CREATE GLOBAL TEMPORARY TABLE temp_session_test (id INT, value VARCHAR2(100)) ON COMMIT PRESERVE ROWS");
                } catch (SQLException ex) {
                    // Might already exist from another session, just truncate
                    stmt.execute("TRUNCATE TABLE temp_session_test");
                }
            }

            // Insert data into temporary table (should use same session)
            logger.debug("Inserting data into temporary table");
            stmt.execute("INSERT INTO temp_session_test VALUES (1, 'test_value')");

            // Query temporary table (should use same session)
            logger.debug("Querying temporary table");
            ResultSet rs = stmt.executeQuery("SELECT id, value FROM temp_session_test");
            
            // Verify data was inserted and retrieved successfully
            assertTrue(rs.next(), "Should have at least one row in temporary table");
            assertEquals(1, rs.getInt("id"), "Session data should match");
            assertEquals("test_value", rs.getString("value"), "Session data should match");
            
            // Verify no more rows
            assertFalse(rs.next(), "Should have only one row");
            
            rs.close();
            
            logger.info("Oracle temporary table session affinity test passed");

        } finally {
            // Cleanup
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("TRUNCATE TABLE temp_session_test");
            } catch (SQLException e) {
                logger.warn("Error during cleanup: {}", e.getMessage());
            }
            conn.close();
        }
    }

    /**
     * Tests that multiple temporary table operations work correctly.
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
     void testComplexTemporaryTableOperations(String driverClass, String url, String user, String pwd)             throws SQLException {
        assumeFalse(isTestDisabled, "Oracle tests are disabled");
        logger.info("Testing temporay table with Driver: {}", driverClass);
        logger.info("Testing complex temporary table operations for Oracle: {}", url);

        Connection conn = DriverManager.getConnection(url, user, pwd);

        try (Statement stmt = conn.createStatement()) {
            // Oracle global temp tables are permanent, just truncate
            try {
                stmt.execute("TRUNCATE TABLE temp_complex");
            } catch (SQLException e) {
                // Table doesn't exist, create it
                try {
                    logger.debug("Creating complex temp table");
                    stmt.execute("CREATE GLOBAL TEMPORARY TABLE temp_complex (id INT PRIMARY KEY, name VARCHAR2(100), amount DECIMAL(10,2)) ON COMMIT PRESERVE ROWS");
                } catch (SQLException ex) {
                    // Might already exist, just truncate
                    stmt.execute("TRUNCATE TABLE temp_complex");
                }
            }

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
                    assertEquals(250.75, amount, 0.01); // Updated
                } else if (id == 3) {
                    assertEquals("Charlie", name);
                    assertEquals(150.25, amount, 0.01);
                }
            }
            
            assertEquals(3, rowCount, "Should have 3 rows");
            rs.close();
            
            logger.info("Oracle complex temporary table operations test passed");

        } finally {
            // Cleanup
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("TRUNCATE TABLE temp_complex");
            } catch (SQLException e) {
                logger.warn("Error during cleanup: {}", e.getMessage());
            }
            conn.close();
        }
    }

    /**
     * Tests that temp table persists within same session across transactions.
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testTemporaryTablePersistenceAcrossTransactions(String driverClass, String url, String user, String pwd)             throws SQLException {
        assumeFalse(isTestDisabled, "Oracle tests are disabled");
        logger.info("Testing temporay table with Driver: {}", driverClass);
        logger.info("Testing temporary table persistence across transactions for Oracle: {}", url);

        Connection conn = DriverManager.getConnection(url, user, pwd);

        try (Statement stmt = conn.createStatement()) {
            // Oracle global temp tables are permanent, just truncate
            try {
                stmt.execute("TRUNCATE TABLE temp_persist");
            } catch (SQLException e) {
                // Table doesn't exist, create it
                try {
                    stmt.execute("CREATE GLOBAL TEMPORARY TABLE temp_persist (id INT, data VARCHAR2(100)) ON COMMIT PRESERVE ROWS");
                } catch (SQLException ex) {
                    // Might already exist, just truncate
                    stmt.execute("TRUNCATE TABLE temp_persist");
                }
            }

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

            logger.info("Oracle temporary table persistence across transactions test passed");

        } finally {
            // Cleanup
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("TRUNCATE TABLE temp_persist");
            } catch (SQLException e) {
                logger.warn("Error during cleanup: {}", e.getMessage());
            }
            conn.close();
        }
    }
}
