package org.openjproxy.jdbc.cockroach;

import org.openjproxy.jdbc.testutil.TestDBUtils;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.openjproxy.jdbc.testutil.TestDBUtils.SqlSyntax.COCKROACHDB;
import static org.junit.jupiter.api.Assertions.*;

/**
 * CockroachDB-specific integration tests to validate OJP functionality with CockroachDB.
 * These tests verify that OJP can properly handle CockroachDB-specific SQL syntax and features.
 */

class CockroachDBConnectionExtensiveTests {
    private static final Logger logger = LoggerFactory.getLogger(CockroachDBConnectionExtensiveTests.class);
    private static boolean isTestEnabled;

    @BeforeAll
     static void setup() {
        isTestEnabled = Boolean.parseBoolean(System.getProperty("enableCockroachDBTests", "false"));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/cockroachdb_connection.csv")
     void testCockroachDBBasicConnection(String driverClass, String url, String user, String pwd) throws SQLException {
        logger.info("Testing temporay table with Driver: {}", driverClass);
        Assumptions.assumeFalse(!isTestEnabled, "CockroachDB tests are not enabled");

        try (Connection connection = DriverManager.getConnection(url, user, pwd)) {
            assertNotNull(connection);
            assertFalse(connection.isClosed());

            try (Statement statement = connection.createStatement()) {
                // Create a basic table
                TestDBUtils.createBasicTestTable(connection, "cockroachdb_test_table", COCKROACHDB, false);

                // Insert test data
                statement.execute("INSERT INTO cockroachdb_test_table (id, name) VALUES (1, 'Alice')");
                statement.execute("INSERT INTO cockroachdb_test_table (id, name) VALUES (2, 'Bob')");
                statement.execute("INSERT INTO cockroachdb_test_table (id, name) VALUES (3, 'Charlie')");

                // Query data
                try (ResultSet rs = statement.executeQuery("SELECT * FROM cockroachdb_test_table ORDER BY id")) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt("id"));
                    assertEquals("Alice", rs.getString("name"));

                    assertTrue(rs.next());
                    assertEquals(2, rs.getInt("id"));
                    assertEquals("Bob", rs.getString("name"));

                    assertTrue(rs.next());
                    assertEquals(3, rs.getInt("id"));
                    assertEquals("Charlie", rs.getString("name"));
                }

                // Clean up
                TestDBUtils.cleanupTestTables(connection, "cockroachdb_test_table");
            }
        }
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/cockroachdb_connection.csv")
     void testCockroachDBAutoIncrement(String driverClass, String url, String user, String pwd) throws SQLException {
        logger.info("Testing temporay table with Driver: {}", driverClass);
        Assumptions.assumeFalse(!isTestEnabled, "CockroachDB tests are not enabled");

        try (Connection connection = DriverManager.getConnection(url, user, pwd)) {
            try (Statement statement = connection.createStatement()) {
                // Create table with SERIAL (auto-increment)
                TestDBUtils.createAutoIncrementTestTable(connection, "cockroachdb_serial_test", COCKROACHDB);

                // Insert data without specifying id
                statement.execute("INSERT INTO cockroachdb_serial_test (name) VALUES ('Test 1')");
                statement.execute("INSERT INTO cockroachdb_serial_test (name) VALUES ('Test 2')");

                // Query and verify auto-incremented ids
                try (ResultSet rs = statement.executeQuery("SELECT * FROM cockroachdb_serial_test ORDER BY id")) {
                    assertTrue(rs.next());
                    // CockroachDB SERIAL uses BIGINT, so use getLong instead of getInt
                    assertTrue(rs.getLong("id") > 0);
                    assertEquals("Test 1", rs.getString("name"));

                    assertTrue(rs.next());
                    assertTrue(rs.getLong("id") > 0);
                    assertEquals("Test 2", rs.getString("name"));
                }

                // Clean up
                TestDBUtils.cleanupTestTables(connection, "cockroachdb_serial_test");
            }
        }
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/cockroachdb_connection.csv")
     void testCockroachDBTransactionHandling(String driverClass, String url, String user, String pwd) throws SQLException {
        logger.info("Testing temporay table with Driver: {}", driverClass);
        Assumptions.assumeFalse(!isTestEnabled, "CockroachDB tests are not enabled");

        try (Connection connection = DriverManager.getConnection(url, user, pwd)) {
            // Create table in auto-commit mode first
            try (Statement statement = connection.createStatement()) {
                // Drop table if exists first
                try {
                    statement.execute("DROP TABLE IF EXISTS cockroachdb_transaction_test");
                } catch (SQLException e) {
                    // Ignore
                }

                // Create table
                statement.execute("CREATE TABLE cockroachdb_transaction_test (id INT PRIMARY KEY, name VARCHAR(100))");
            }

            // Now test transactions
            connection.setAutoCommit(false);

            try (Statement statement = connection.createStatement()) {
                // Insert data in transaction
                statement.execute("INSERT INTO cockroachdb_transaction_test (id, name) VALUES (1, 'Transaction Test')");

                // Verify data before commit
                try (ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM cockroachdb_transaction_test")) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt(1));
                }

                // Rollback transaction
                connection.rollback();

                // Verify data was rolled back
                try (ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM cockroachdb_transaction_test")) {
                    assertTrue(rs.next());
                    assertEquals(0, rs.getInt(1));
                }

                // Insert data again and commit
                statement.execute("INSERT INTO cockroachdb_transaction_test (id, name) VALUES (1, 'Committed Test')");
                connection.commit();

                // Verify data persisted
                try (ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM cockroachdb_transaction_test")) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt(1));
                }

                connection.commit();
            }

            // Clean up in auto-commit mode
            connection.setAutoCommit(true);
            try (Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE IF EXISTS cockroachdb_transaction_test");
            }
        }
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/cockroachdb_connection.csv")
     void testCockroachDBPreparedStatements(String driverClass, String url, String user, String pwd) throws SQLException {
        logger.info("Testing temporay table with Driver: {}", driverClass);
        Assumptions.assumeFalse(!isTestEnabled, "CockroachDB tests are not enabled");

        try (Connection connection = DriverManager.getConnection(url, user, pwd)) {
            TestDBUtils.createBasicTestTable(connection, "cockroachdb_prepared_test", COCKROACHDB, false);

            // Insert using prepared statement
            try (PreparedStatement pstmt = connection.prepareStatement(
                    "INSERT INTO cockroachdb_prepared_test (id, name) VALUES (?, ?)")) {
                pstmt.setInt(1, 1);
                pstmt.setString(2, "PreparedTest1");
                pstmt.executeUpdate();

                pstmt.setInt(1, 2);
                pstmt.setString(2, "PreparedTest2");
                pstmt.executeUpdate();
            }

            // Query using prepared statement
            try (PreparedStatement pstmt = connection.prepareStatement(
                    "SELECT * FROM cockroachdb_prepared_test WHERE id = ?")) {
                pstmt.setInt(1, 1);
                try (ResultSet rs = pstmt.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt("id"));
                    assertEquals("PreparedTest1", rs.getString("name"));
                }
            }

            // Clean up
            TestDBUtils.cleanupTestTables(connection, "cockroachdb_prepared_test");
        }
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/cockroachdb_connection.csv")
     void testCockroachDBDataTypes(String driverClass, String url, String user, String pwd) throws SQLException {
        logger.info("Testing temporay table with Driver: {}", driverClass);
        Assumptions.assumeFalse(!isTestEnabled, "CockroachDB tests are not enabled");

        try (Connection connection = DriverManager.getConnection(url, user, pwd)) {
            try (Statement statement = connection.createStatement()) {
                // Create table with various data types
                statement.execute("DROP TABLE IF EXISTS cockroachdb_types_test");
                statement.execute("CREATE TABLE cockroachdb_types_test (" +
                        "id SERIAL PRIMARY KEY, " +
                        "int_col INT, " +
                        "bigint_col BIGINT, " +
                        "float_col FLOAT, " +
                        "decimal_col DECIMAL(10,2), " +
                        "varchar_col VARCHAR(100), " +
                        "text_col TEXT, " +
                        "bool_col BOOLEAN, " +
                        "date_col DATE, " +
                        "timestamp_col TIMESTAMP" +
                        ")");

                // Insert test data
                statement.execute("INSERT INTO cockroachdb_types_test " +
                        "(int_col, bigint_col, float_col, decimal_col, varchar_col, text_col, bool_col, date_col, timestamp_col) " +
                        "VALUES " +
                        "(42, 9223372036854775807, 3.14, 12345.67, 'varchar test', 'text test', true, '2024-01-15', '2024-01-15 10:30:00')");

                // Query and verify data types
                try (ResultSet rs = statement.executeQuery("SELECT * FROM cockroachdb_types_test")) {
                    assertTrue(rs.next());
                    assertEquals(42, rs.getInt("int_col"));
                    assertEquals(9223372036854775807L, rs.getLong("bigint_col"));
                    // CockroachDB returns Double for FLOAT columns
                    assertEquals(3.14, rs.getDouble("float_col"), 0.01);
                    assertEquals(12345.67, rs.getDouble("decimal_col"), 0.01);
                    assertEquals("varchar test", rs.getString("varchar_col"));
                    assertEquals("text test", rs.getString("text_col"));
                    assertTrue(rs.getBoolean("bool_col"));
                    assertNotNull(rs.getDate("date_col"));
                    assertNotNull(rs.getTimestamp("timestamp_col"));
                }

                // Clean up
                TestDBUtils.cleanupTestTables(connection, "cockroachdb_types_test");
            }
        }
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/cockroachdb_connection.csv")
     void testCockroachDBJoins(String driverClass, String url, String user, String pwd) throws SQLException {
        logger.info("Testing temporay table with Driver: {}", driverClass);
        Assumptions.assumeFalse(!isTestEnabled, "CockroachDB tests are not enabled");

        try (Connection connection = DriverManager.getConnection(url, user, pwd)) {
            try (Statement statement = connection.createStatement()) {
                // Create two tables for join test
                statement.execute("DROP TABLE IF EXISTS cockroachdb_orders");
                statement.execute("DROP TABLE IF EXISTS cockroachdb_customers");

                statement.execute("CREATE TABLE cockroachdb_customers (" +
                        "customer_id INT PRIMARY KEY, " +
                        "customer_name VARCHAR(100)" +
                        ")");

                statement.execute("CREATE TABLE cockroachdb_orders (" +
                        "order_id INT PRIMARY KEY, " +
                        "customer_id INT, " +
                        "order_amount DECIMAL(10,2)" +
                        ")");

                // Insert test data
                statement.execute("INSERT INTO cockroachdb_customers VALUES (1, 'John Doe')");
                statement.execute("INSERT INTO cockroachdb_customers VALUES (2, 'Jane Smith')");
                statement.execute("INSERT INTO cockroachdb_orders VALUES (101, 1, 100.50)");
                statement.execute("INSERT INTO cockroachdb_orders VALUES (102, 1, 200.75)");
                statement.execute("INSERT INTO cockroachdb_orders VALUES (103, 2, 150.00)");

                // Test JOIN query
                try (ResultSet rs = statement.executeQuery(
                        "SELECT c.customer_name, o.order_id, o.order_amount " +
                        "FROM cockroachdb_customers c " +
                        "INNER JOIN cockroachdb_orders o ON c.customer_id = o.customer_id " +
                        "ORDER BY o.order_id")) {
                    
                    assertTrue(rs.next());
                    assertEquals("John Doe", rs.getString("customer_name"));
                    assertEquals(101, rs.getInt("order_id"));

                    assertTrue(rs.next());
                    assertEquals("John Doe", rs.getString("customer_name"));
                    assertEquals(102, rs.getInt("order_id"));

                    assertTrue(rs.next());
                    assertEquals("Jane Smith", rs.getString("customer_name"));
                    assertEquals(103, rs.getInt("order_id"));
                }

                // Clean up
                TestDBUtils.cleanupTestTables(connection, "cockroachdb_orders", "cockroachdb_customers");
            }
        }
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/cockroachdb_connection.csv")
     void testCockroachDBMetadata(String driverClass, String url, String user, String pwd) throws SQLException {
        logger.info("Testing temporay table with Driver: {}", driverClass);
        Assumptions.assumeFalse(!isTestEnabled, "CockroachDB tests are not enabled");

        try (Connection connection = DriverManager.getConnection(url, user, pwd)) {
            DatabaseMetaData metadata = connection.getMetaData();

            assertNotNull(metadata);
            assertTrue(metadata.getDatabaseProductName().toLowerCase().contains("cockroach") || 
                       metadata.getDatabaseProductName().toLowerCase().contains("postgres"));
            assertNotNull(metadata.getDatabaseProductVersion());

            logger.info("CockroachDB/Database product name: {}", metadata.getDatabaseProductName());
            logger.info("CockroachDB/Database version: {}", metadata.getDatabaseProductVersion());
            logger.info("Driver version: {}", metadata.getDriverVersion());
        }
    }
}
