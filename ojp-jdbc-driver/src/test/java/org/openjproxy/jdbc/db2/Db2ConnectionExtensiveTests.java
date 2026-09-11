package org.openjproxy.jdbc.db2;

import org.openjproxy.jdbc.testutil.TestDBUtils;
import org.junit.jupiter.api.Assumptions;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DB2-specific integration tests to validate OJP functionality with IBM DB2 Database.
 * These tests verify that OJP can properly handle DB2-specific SQL syntax and data types.
 */

class Db2ConnectionExtensiveTests {
    private static final Logger logger = LoggerFactory.getLogger(Db2ConnectionExtensiveTests.class);
    private static boolean isDb2TestEnabled;

    @BeforeAll
    static void setup() {
        isDb2TestEnabled = Boolean.parseBoolean(System.getProperty("enableDb2Tests", "false"));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void testDb2BasicConnection(String driverClass, String url, String user, String pwd) throws SQLException {
        Assumptions.assumeFalse(!isDb2TestEnabled, "Skipping DB2 tests");

        logger.info("Testing DB2 connection with URL: {}", url);

        try (Connection connection = DriverManager.getConnection(url, user, pwd)) {

            // Set schema explicitly to avoid "object not found" errors
            try (Statement schemaStmt = connection.createStatement()) {
                schemaStmt.execute("SET SCHEMA DB2INST1");
            }
            assertTrue(connection.isValid(5), "Connection should be valid");

            // Set schema explicitly to avoid "object not found" errors
            try (Statement schemaStmt = connection.createStatement()) {
                schemaStmt.execute("SET SCHEMA DB2INST1");
            }

            // Test basic DB2 functionality
            try (Statement statement = connection.createStatement()) {
                // Create a simple test table
                TestDBUtils.createBasicTestTable(connection, "DB2INST1.db2_test_table", TestDBUtils.SqlSyntax.DB2, true);

                // Verify data was inserted correctly
                try (ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM DB2INST1.db2_test_table")) {
                    assertTrue(rs.next());
                    assertEquals(2, rs.getInt(1), "Should have 2 test records");
                }

                // Test DB2-specific INTEGER data type
                statement.execute("INSERT INTO DB2INST1.db2_test_table (id, name) VALUES (3, 'Charlie')");

                try (ResultSet rs = statement.executeQuery("SELECT id, name FROM DB2INST1.db2_test_table WHERE id = 3")) {
                    assertTrue(rs.next());
                    assertEquals(3, rs.getInt("id"));
                    assertEquals("Charlie", rs.getString("name"));
                }

                // Clean up
                TestDBUtils.cleanupTestTables(connection, "DB2INST1.db2_test_table");
            }
        }
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void testDb2DataTypes(String driverClass, String url, String user, String pwd) throws SQLException {
        Assumptions.assumeFalse(!isDb2TestEnabled, "Skipping DB2 tests");

        logger.info("Testing DB2 data types with URL: {}", url);

        try (Connection connection = DriverManager.getConnection(url, user, pwd)) {

            // Set schema explicitly to avoid "object not found" errors
            try (Statement schemaStmt = connection.createStatement()) {
                schemaStmt.execute("SET SCHEMA DB2INST1");
            }
            try (Statement statement = connection.createStatement()) {
                // Create table with DB2-specific data types
                try {
                    statement.execute("DROP TABLE db2_multitype_test");
                } catch (SQLException e) {
                    // Ignore if table doesn't exist
                }

                statement.execute("CREATE TABLE db2_multitype_test (" +
                        "val_int INTEGER, " +
                        "val_varchar VARCHAR(50), " +
                        "val_double DOUBLE, " +
                        "val_bigint BIGINT, " +
                        "val_smallint SMALLINT, " +
                        "val_decimal DECIMAL(10,2), " +
                        "val_date DATE, " +
                        "val_timestamp TIMESTAMP)");

                // Insert test data with DB2-specific values
                statement.execute("INSERT INTO db2_multitype_test " +
                        "(val_int, val_varchar, val_double, val_bigint, val_smallint, " +
                        "val_decimal, val_date, val_timestamp) VALUES " +
                        "(123, 'Test String', 123.456, 9876543210, 32767, " +
                        "99.99, DATE('2023-12-25'), TIMESTAMP('2023-12-25-10.30.00'))");

                // Verify the data was inserted and can be retrieved
                try (ResultSet rs = statement.executeQuery("SELECT * FROM db2_multitype_test")) {
                    assertTrue(rs.next());
                    assertEquals(123, rs.getInt("val_int"));
                    assertEquals("Test String", rs.getString("val_varchar"));
                    assertEquals(123.456, rs.getDouble("val_double"), 0.001);
                    assertEquals(9876543210L, rs.getLong("val_bigint"));
                    assertEquals(32767, rs.getInt("val_smallint"));
                    assertEquals(99.99, rs.getBigDecimal("val_decimal").doubleValue(), 0.01);
                }

                // Clean up
                TestDBUtils.cleanupTestTables(connection, "db2_multitype_test");
            }
        }
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void testDb2BooleanHandling(String driverClass, String url, String user, String pwd) throws SQLException {
        Assumptions.assumeFalse(!isDb2TestEnabled, "Skipping DB2 tests");

        logger.info("Testing DB2 boolean handling with URL: {}", url);

        try (Connection connection = DriverManager.getConnection(url, user, pwd)) {

            // Set schema explicitly to avoid "object not found" errors
            try (Statement schemaStmt = connection.createStatement()) {
                schemaStmt.execute("SET SCHEMA DB2INST1");
            }
            try (Statement statement = connection.createStatement()) {
                // Create table with boolean column
                try {
                    statement.execute("DROP TABLE DB2INST1.db2_boolean_test");
                } catch (SQLException e) {
                    // Ignore if table doesn't exist
                }

                statement.execute("CREATE TABLE DB2INST1.db2_boolean_test (id INTEGER, is_active BOOLEAN)");

                // Insert test data with different boolean representations
                statement.execute("INSERT INTO DB2INST1.db2_boolean_test VALUES (1, TRUE)");
                statement.execute("INSERT INTO DB2INST1.db2_boolean_test VALUES (2, FALSE)");
                statement.execute("INSERT INTO DB2INST1.db2_boolean_test VALUES (3, NULL)");

                // Verify boolean values
                try (ResultSet rs = statement.executeQuery("SELECT id, is_active FROM DB2INST1.db2_boolean_test ORDER BY id")) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt("id"));
                    assertTrue(rs.getBoolean("is_active"));

                    assertTrue(rs.next());
                    assertEquals(2, rs.getInt("id"));
                    assertFalse(rs.getBoolean("is_active"));

                    assertTrue(rs.next());
                    assertEquals(3, rs.getInt("id"));
                    assertFalse(rs.getBoolean("is_active")); // NULL should be false
                    assertTrue(rs.wasNull());
                }

                // Clean up
                TestDBUtils.cleanupTestTables(connection, "DB2INST1.db2_boolean_test");
            }
        }
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void testDb2NullVsEmptyString(String driverClass, String url, String user, String pwd) throws SQLException {
        Assumptions.assumeFalse(!isDb2TestEnabled, "Skipping DB2 tests");

        logger.info("Testing DB2 NULL vs empty string handling with URL: {}", url);

        try (Connection connection = DriverManager.getConnection(url, user, pwd)) {

            // Set schema explicitly to avoid "object not found" errors
            try (Statement schemaStmt = connection.createStatement()) {
                schemaStmt.execute("SET SCHEMA DB2INST1");
            }
            try (Statement statement = connection.createStatement()) {
                // Create table for NULL vs empty string testing
                try {
                    statement.execute("DROP TABLE DB2INST1.db2_null_test");
                } catch (SQLException e) {
                    // Ignore if table doesn't exist
                }

                statement.execute("CREATE TABLE DB2INST1.db2_null_test (id INTEGER, text_col VARCHAR(100))");

                // Insert test data with NULL and empty string
                statement.execute("INSERT INTO DB2INST1.db2_null_test VALUES (1, NULL)");
                statement.execute("INSERT INTO DB2INST1.db2_null_test VALUES (2, '')");
                statement.execute("INSERT INTO DB2INST1.db2_null_test VALUES (3, 'Valid Text')");

                // Verify NULL vs empty string handling
                try (ResultSet rs = statement.executeQuery("SELECT id, text_col FROM DB2INST1.db2_null_test ORDER BY id")) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt("id"));
                    assertNull(rs.getString("text_col"));
                    assertTrue(rs.wasNull());

                    assertTrue(rs.next());
                    assertEquals(2, rs.getInt("id"));
                    assertEquals("", rs.getString("text_col"));
                    assertFalse(rs.wasNull());

                    assertTrue(rs.next());
                    assertEquals(3, rs.getInt("id"));
                    assertEquals("Valid Text", rs.getString("text_col"));
                    assertFalse(rs.wasNull());
                }

                // Clean up
                TestDBUtils.cleanupTestTables(connection, "DB2INST1.db2_null_test");
            }
        }
    }
}