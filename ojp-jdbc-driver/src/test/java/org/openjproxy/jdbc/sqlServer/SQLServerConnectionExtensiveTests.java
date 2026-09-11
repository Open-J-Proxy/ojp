package org.openjproxy.jdbc.sqlServer;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.openjproxy.jdbc.testutil.SQLServerConnectionProvider;
import org.openjproxy.jdbc.testutil.TestDBUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SQL Server-specific integration tests to validate OJP functionality with SQL Server.
 * These tests verify that OJP can properly handle SQL Server-specific SQL syntax and data types.
 */
@Slf4j
@EnabledIf("org.openjproxy.jdbc.testutil.SQLServerTestContainer#isEnabled")
public class SQLServerConnectionExtensiveTests {

    private static boolean isTestDisabled;

    @BeforeAll
    static void setup() {
        isTestDisabled = !Boolean.parseBoolean(System.getProperty("enableSqlServerTests", "false"));
    }

    @ParameterizedTest
    @ArgumentsSource(SQLServerConnectionProvider.class)
    void testSqlServerBasicConnection(String driverClass, String url, String user, String pwd) throws SQLException {
        Assumptions.assumeFalse(isTestDisabled, "SQL Server tests are disabled");
        
        log.info("Testing SQL Server connection with URL: {}", url);
        
        try (Connection connection = DriverManager.getConnection(url, user, pwd)) {
            assertTrue(connection.isValid(5), "Connection should be valid");
            
            // Test basic SQL Server functionality
            try (Statement statement = connection.createStatement()) {
                // Create a simple test table
                TestDBUtils.createBasicTestTable(connection, "sqlserver_test_table", TestDBUtils.SqlSyntax.SQLSERVER, true);
                
                // Verify data was inserted correctly
                try (ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM sqlserver_test_table")) {
                    assertTrue(rs.next());
                    assertEquals(2, rs.getInt(1), "Should have 2 test records");
                }
                
                // Test SQL Server-specific INT data type
                statement.execute("INSERT INTO sqlserver_test_table (id, name) VALUES (3, N'Charlie')");
                
                try (ResultSet rs = statement.executeQuery("SELECT id, name FROM sqlserver_test_table WHERE id = 3")) {
                    assertTrue(rs.next());
                    assertEquals(3, rs.getInt("id"));
                    assertEquals("Charlie", rs.getString("name"));
                }
                
                // Clean up
                TestDBUtils.cleanupTestTables(connection, "sqlserver_test_table");
            }
        }
    }

    @ParameterizedTest
    @ArgumentsSource(SQLServerConnectionProvider.class)
    void testSqlServerDataTypes(String driverClass, String url, String user, String pwd) throws SQLException {
        Assumptions.assumeFalse(isTestDisabled, "SQL Server tests are disabled");
        
        log.info("Testing SQL Server data types with URL: {}", url);
        
        try (Connection connection = DriverManager.getConnection(url, user, pwd)) {
            // Create and test SQL Server-specific data types
            TestDBUtils.createMultiTypeTestTable(connection, "sqlserver_multitype_test", TestDBUtils.SqlSyntax.SQLSERVER);
            
            try (Statement statement = connection.createStatement()) {
                // Insert test data with SQL Server-specific values
                statement.execute("INSERT INTO sqlserver_multitype_test " +
                        "(val_int, val_varchar, val_double_precision, val_bigint, val_tinyint, val_smallint, " +
                        "val_boolean, val_decimal, val_float, val_date, val_time, val_timestamp) VALUES " +
                        "(123, N'Test String', 123.456, 9876543210, 255, 32767, 1, 99.99, 3.14, " +
                        "'2023-12-25', '10:30:00', '2023-12-25 10:30:00')");
                
                // Verify the data was inserted and can be retrieved
                try (ResultSet rs = statement.executeQuery("SELECT * FROM sqlserver_multitype_test")) {
                    assertTrue(rs.next());
                    assertEquals(123, rs.getInt("val_int"));
                    assertEquals("Test String", rs.getString("val_varchar"));
                    assertEquals(123.456, rs.getDouble("val_double_precision"), 0.001);
                    assertEquals(9876543210L, rs.getLong("val_bigint"));
                    assertEquals(255, rs.getInt("val_tinyint"));  // SQL Server TINYINT is 0-255
                    assertEquals(32767, rs.getInt("val_smallint"));
                    assertTrue( rs.getBoolean("val_boolean"));  // SQL Server BIT
                }
                
                // Clean up
                TestDBUtils.cleanupTestTables(connection, "sqlserver_multitype_test");
            }
        }
    }

    @ParameterizedTest
    @ArgumentsSource(SQLServerConnectionProvider.class)
    void testSqlServerSpecificSyntax(String driverClass, String url, String user, String pwd) throws SQLException {
        Assumptions.assumeFalse(isTestDisabled, "SQL Server tests are disabled");
        
        log.info("Testing SQL Server specific syntax with URL: {}", url);
        
        try (Connection connection = DriverManager.getConnection(url, user, pwd)) {
            try (Statement statement = connection.createStatement()) {
                // Test SQL Server-specific syntax features
                
                // Create table with IDENTITY column
                statement.execute("IF OBJECT_ID('sqlserver_identity_test', 'U') IS NOT NULL DROP TABLE sqlserver_identity_test");
                statement.execute("CREATE TABLE sqlserver_identity_test (" +
                        "id INT IDENTITY(1,1) PRIMARY KEY, " +
                        "name NVARCHAR(50), " +
                        "created_date DATETIME2 DEFAULT GETDATE())");
                
                // Insert data without specifying identity column
                statement.execute("INSERT INTO sqlserver_identity_test (name) VALUES (N'Test 1')");
                statement.execute("INSERT INTO sqlserver_identity_test (name) VALUES (N'Test 2')");
                
                // Verify identity values were generated
                try (ResultSet rs = statement.executeQuery("SELECT id, name FROM sqlserver_identity_test ORDER BY id")) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt("id"));
                    assertEquals("Test 1", rs.getString("name"));
                    
                    assertTrue(rs.next());
                    assertEquals(2, rs.getInt("id"));
                    assertEquals("Test 2", rs.getString("name"));
                }
                
                // Test SQL Server TOP clause
                try (ResultSet rs = statement.executeQuery("SELECT TOP 1 id, name FROM sqlserver_identity_test ORDER BY id DESC")) {
                    assertTrue(rs.next());
                    assertEquals(2, rs.getInt("id"));
                    assertEquals("Test 2", rs.getString("name"));
                }
                
                // Clean up
                TestDBUtils.cleanupTestTables(connection, "sqlserver_identity_test");
            }
        }
    }

    @ParameterizedTest
    @ArgumentsSource(SQLServerConnectionProvider.class)
    void testSqlServerUnicodeSupport(String driverClass, String url, String user, String pwd) throws SQLException {
        Assumptions.assumeFalse(isTestDisabled, "SQL Server tests are disabled");
        
        log.info("Testing SQL Server Unicode support with URL: {}", url);
        
        try (Connection connection = DriverManager.getConnection(url, user, pwd)) {
            try (Statement statement = connection.createStatement()) {
                // Create table with Unicode columns
                statement.execute("IF OBJECT_ID('sqlserver_unicode_test', 'U') IS NOT NULL DROP TABLE sqlserver_unicode_test");
                statement.execute("CREATE TABLE sqlserver_unicode_test (" +
                        "id INT PRIMARY KEY, " +
                        "unicode_text NVARCHAR(100), " +
                        "regular_text VARCHAR(100))");
                
                // Insert Unicode data
                statement.execute("INSERT INTO sqlserver_unicode_test (id, unicode_text, regular_text) VALUES " +
                        "(1, N'Unicode: こんにちは 中文 🚀', 'Regular ASCII text')");
                
                // Verify Unicode data was stored and retrieved correctly
                try (ResultSet rs = statement.executeQuery("SELECT unicode_text, regular_text FROM sqlserver_unicode_test WHERE id = 1")) {
                    assertTrue(rs.next());
                    assertEquals("Unicode: こんにちは 中文 🚀", rs.getString("unicode_text"));
                    assertEquals("Regular ASCII text", rs.getString("regular_text"));
                }
                
                // Clean up
                TestDBUtils.cleanupTestTables(connection, "sqlserver_unicode_test");
            }
        }
    }

    @ParameterizedTest
    @ArgumentsSource(SQLServerConnectionProvider.class)
    void testSqlServerTransactionHandling(String driverClass, String url, String user, String pwd) throws SQLException {
        Assumptions.assumeFalse(isTestDisabled, "SQL Server tests are disabled");
        
        log.info("Testing SQL Server transaction handling with URL: {}", url);
        
        try (Connection connection = DriverManager.getConnection(url, user, pwd)) {
            
            try (Statement statement = connection.createStatement()) {
                // Create test table
                TestDBUtils.createBasicTestTable(connection, "sqlserver_transaction_test", TestDBUtils.SqlSyntax.SQLSERVER, true);

                connection.setAutoCommit(false);//Start transaction.

                // Insert data in transaction
                statement.execute("INSERT INTO sqlserver_transaction_test (id, name) VALUES (10, N'Transaction Test')");
                
                // Verify data exists before commit
                try (ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM sqlserver_transaction_test WHERE id = 10")) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt(1));
                }
                
                // Rollback transaction
                connection.rollback();
                
                // Verify data was rolled back
                try (ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM sqlserver_transaction_test WHERE id = 10")) {
                    assertTrue(rs.next());
                    assertEquals(0, rs.getInt(1));
                }
                
                // Insert again and commit
                statement.execute("INSERT INTO sqlserver_transaction_test (id, name) VALUES (11, N'Committed Test')");
                connection.commit();
                
                // Verify data persisted after commit
                try (ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM sqlserver_transaction_test WHERE id = 11")) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt(1));
                }
                
                // Clean up
                TestDBUtils.cleanupTestTables(connection, "sqlserver_transaction_test");
                connection.commit();
            }
        }
    }

    @ParameterizedTest
    @ArgumentsSource(SQLServerConnectionProvider.class)
    void testSqlServerMetadata(String driverClass, String url, String user, String pwd) throws SQLException {
        Assumptions.assumeFalse(isTestDisabled, "SQL Server tests are disabled");
        
        log.info("Testing SQL Server metadata with URL: {}", url);
        
        try (Connection connection = DriverManager.getConnection(url, user, pwd)) {
            // Test database metadata
            var metadata = connection.getMetaData();
            assertTrue(metadata.getDatabaseProductName().toLowerCase().contains("microsoft"));
            assertTrue(metadata.getDatabaseProductVersion().length() > 0);
            
            // Test connection metadata
            assertTrue(connection.getCatalog() != null || connection.getSchema() != null);
            
            log.info("SQL Server version: {}", metadata.getDatabaseProductVersion());
            log.info("Driver version: {}", metadata.getDriverVersion());
        }
    }
}