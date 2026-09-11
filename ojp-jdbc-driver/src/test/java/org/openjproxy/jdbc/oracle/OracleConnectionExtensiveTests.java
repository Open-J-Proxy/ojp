package org.openjproxy.jdbc.oracle;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.openjproxy.jdbc.testutil.TestDBUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Oracle-specific integration tests to validate OJP functionality with Oracle Database.
 * These tests verify that OJP can properly handle Oracle-specific SQL syntax and data types.
 */
@Slf4j
public class OracleConnectionExtensiveTests {

    private static boolean isOracleTestEnabled;

    @BeforeAll
    static void setup() {
        isOracleTestEnabled = Boolean.parseBoolean(System.getProperty("enableOracleTests", "false"));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testOracleBasicConnection(String driverClass, String url, String user, String pwd) throws SQLException {
        Assumptions.assumeFalse(!isOracleTestEnabled, "Skipping Oracle tests");
        
        log.info("Testing Oracle connection with URL: {}", url);
        
        try (Connection connection = DriverManager.getConnection(url, user, pwd)) {
            assertTrue(connection.isValid(5), "Connection should be valid");
            
            // Test basic Oracle functionality
            try (Statement statement = connection.createStatement()) {
                // Create a simple test table
                TestDBUtils.createBasicTestTable(connection, "oracle_test_table", TestDBUtils.SqlSyntax.ORACLE, true);
                
                // Verify data was inserted correctly
                try (ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM oracle_test_table")) {
                    assertTrue(rs.next());
                    assertEquals(2, rs.getInt(1), "Should have 2 test records");
                }
                
                // Test Oracle-specific NUMBER data type
                statement.execute("INSERT INTO oracle_test_table (id, name) VALUES (3, 'Charlie')");
                
                try (ResultSet rs = statement.executeQuery("SELECT id, name FROM oracle_test_table WHERE id = 3")) {
                    assertTrue(rs.next());
                    assertEquals(3, rs.getInt("id"));
                    assertEquals("Charlie", rs.getString("name"));
                }
                
                // Clean up
                TestDBUtils.cleanupTestTables(connection, "oracle_test_table");
            }
        }
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testOracleDataTypes(String driverClass, String url, String user, String pwd) throws SQLException {
        Assumptions.assumeFalse(!isOracleTestEnabled, "Skipping Oracle tests");
        
        log.info("Testing Oracle data types with URL: {}", url);
        
        try (Connection connection = DriverManager.getConnection(url, user, pwd)) {
            // Create and test Oracle-specific data types
            TestDBUtils.createMultiTypeTestTable(connection, "oracle_multitype_test", TestDBUtils.SqlSyntax.ORACLE);
            
            try (Statement statement = connection.createStatement()) {
                // Insert test data with Oracle-specific values
                statement.execute("INSERT INTO oracle_multitype_test " +
                        "(val_int, val_varchar, val_double_precision, val_bigint, val_tinyint, val_smallint, " +
                        "val_boolean, val_decimal, val_float, val_date, val_timestamp) VALUES " +
                        "(123, 'Test String', 123.456, 9876543210, 1, 32767, 1, 99.99, 3.14, " +
                        "DATE '2023-12-25', TIMESTAMP '2023-12-25 10:30:00')");
                
                // Verify the data was inserted and can be retrieved
                try (ResultSet rs = statement.executeQuery("SELECT * FROM oracle_multitype_test")) {
                    assertTrue(rs.next());
                    assertEquals(123, rs.getInt("val_int"));
                    assertEquals("Test String", rs.getString("val_varchar"));
                    assertEquals(123.456, rs.getDouble("val_double_precision"), 0.001);
                    assertEquals(9876543210L, rs.getLong("val_bigint"));
                    assertEquals(1, rs.getInt("val_tinyint"));
                    assertEquals(32767, rs.getInt("val_smallint"));
                    assertEquals(1, rs.getInt("val_boolean"));
                }
                
                // Clean up
                TestDBUtils.cleanupTestTables(connection, "oracle_multitype_test");
            }
        }
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testOracleAutoIncrementSequence(String driverClass, String url, String user, String pwd) throws SQLException {
        Assumptions.assumeFalse(!isOracleTestEnabled, "Skipping Oracle tests");
        
        log.info("Testing Oracle auto-increment (IDENTITY) with URL: {}", url);
        
        try (Connection connection = DriverManager.getConnection(url, user, pwd)) {
            // Create table with Oracle IDENTITY column
            TestDBUtils.createAutoIncrementTestTable(connection, "oracle_identity_test", TestDBUtils.SqlSyntax.ORACLE);
            
            try (Statement statement = connection.createStatement()) {
                // Insert data without specifying ID (should auto-increment)
                statement.execute("INSERT INTO oracle_identity_test (name) VALUES ('Auto ID 1')");
                statement.execute("INSERT INTO oracle_identity_test (name) VALUES ('Auto ID 2')");
                
                // Verify auto-increment worked
                try (ResultSet rs = statement.executeQuery("SELECT id, name FROM oracle_identity_test ORDER BY id")) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt("id"));
                    assertEquals("Auto ID 1", rs.getString("name"));
                    
                    assertTrue(rs.next());
                    assertEquals(2, rs.getInt("id"));
                    assertEquals("Auto ID 2", rs.getString("name"));
                }
                
                // Clean up
                TestDBUtils.cleanupTestTables(connection, "oracle_identity_test");
            }
        }
    }
}