package org.openjproxy.jdbc.mySql;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Tests for MySQL-specific functionality that is not covered by database-agnostic tests.
 * Includes features like ON DUPLICATE KEY UPDATE, SELECT ... FOR UPDATE, SHOW commands, etc.
 */
class MySQLSpecificFeaturesIntegrationTest {

    private static boolean isMySQLTestEnabled;
    private static boolean isMariaDBTestEnabled;

    @BeforeAll
    static void checkTestConfiguration() {
        isMySQLTestEnabled = Boolean.parseBoolean(System.getProperty("enableMySQLTests", "false"));
        isMariaDBTestEnabled = Boolean.parseBoolean(System.getProperty("enableMariaDBTests", "false"));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    void onDuplicateKeyUpdateTestSuccessful(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException {
        // Skip MySQL tests if not enabled
        if (url.toLowerCase().contains("mysql") && !isMySQLTestEnabled) {
            assumeFalse(true, "Skipping MySQL tests");
        }
        
        // Skip MariaDB tests if not enabled
        if (url.toLowerCase().contains("mariadb") && !isMariaDBTestEnabled) {
            assumeFalse(true, "Skipping MariaDB tests");
        }

        Connection conn = DriverManager.getConnection(url, user, pwd);

        System.out.println("Testing ON DUPLICATE KEY UPDATE for url -> " + url);

        try (Statement stmt = conn.createStatement()) {
            // Drop table if exists
            try {
                stmt.execute("DROP TABLE mysql_upsert_test");
            } catch (SQLException e) {
                // Ignore - table might not exist
            }

            // Create table with unique constraint
            stmt.execute("CREATE TABLE mysql_upsert_test (" +
                    "id INT PRIMARY KEY, " +
                    "name VARCHAR(100), " +
                    "count_val INT DEFAULT 1" +
                    ")");

            // Insert initial data
            stmt.execute("INSERT INTO mysql_upsert_test (id, name, count_val) VALUES (1, 'Test Item', 1)");

            // Test ON DUPLICATE KEY UPDATE
            stmt.execute("INSERT INTO mysql_upsert_test (id, name, count_val) VALUES (1, 'Updated Item', 2) " +
                    "ON DUPLICATE KEY UPDATE name = VALUES(name), count_val = count_val + VALUES(count_val)");

            // Verify the upsert worked correctly
            ResultSet rs = stmt.executeQuery("SELECT id, name, count_val FROM mysql_upsert_test WHERE id = 1");
            assertTrue(rs.next());
            assertEquals(1, rs.getInt("id"));
            assertEquals("Updated Item", rs.getString("name"));
            assertEquals(3, rs.getInt("count_val")); // 1 + 2

            rs.close();
        }

        conn.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    void selectForUpdateTestSuccessful(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException {
        // Skip MySQL tests if not enabled
        if (url.toLowerCase().contains("mysql") && !isMySQLTestEnabled) {
            assumeFalse(true, "Skipping MySQL tests");
        }
        
        // Skip MariaDB tests if not enabled
        if (url.toLowerCase().contains("mariadb") && !isMariaDBTestEnabled) {
            assumeFalse(true, "Skipping MariaDB tests");
        }
        
        Connection conn = DriverManager.getConnection(url, user, pwd);

        System.out.println("Testing SELECT ... FOR UPDATE for url -> " + url);

        try (Statement stmt = conn.createStatement()) {
            // Drop table if exists
            try {
                stmt.execute("DROP TABLE mysql_lock_test");
            } catch (SQLException e) {
                // Ignore - table might not exist
            }

            // Create table
            stmt.execute("CREATE TABLE mysql_lock_test (" +
                    "id INT PRIMARY KEY, " +
                    "balance DECIMAL(10,2)" +
                    ")");

            // Insert test data
            stmt.execute("INSERT INTO mysql_lock_test (id, balance) VALUES (1, 100.00)");

            // Disable autocommit to test locking
            conn.setAutoCommit(false);

            // Test SELECT ... FOR UPDATE
            ResultSet rs = stmt.executeQuery("SELECT id, balance FROM mysql_lock_test WHERE id = 1 FOR UPDATE");
            assertTrue(rs.next());
            assertEquals(1, rs.getInt("id"));
            assertEquals(100.00, rs.getDouble("balance"), 0.01);

            // Update the locked row
            stmt.executeUpdate("UPDATE mysql_lock_test SET balance = balance - 50.00 WHERE id = 1");

            // Commit the transaction
            conn.commit();

            // Verify the update
            rs = stmt.executeQuery("SELECT balance FROM mysql_lock_test WHERE id = 1");
            assertTrue(rs.next());
            assertEquals(50.00, rs.getDouble("balance"), 0.01);

            rs.close();
        }

        conn.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    void showTablesTestSuccessful(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException {
        // Skip MySQL tests if not enabled
        if (url.toLowerCase().contains("mysql") && !isMySQLTestEnabled) {
            assumeFalse(true, "Skipping MySQL tests");
        }
        
        // Skip MariaDB tests if not enabled
        if (url.toLowerCase().contains("mariadb") && !isMariaDBTestEnabled) {
            assumeFalse(true, "Skipping MariaDB tests");
        }
        
        Connection conn = DriverManager.getConnection(url, user, pwd);

        System.out.println("Testing SHOW TABLES for url -> " + url);

        try (Statement stmt = conn.createStatement()) {
            // Create a test table
            try {
                stmt.execute("DROP TABLE mysql_show_test");
            } catch (SQLException e) {
                // Ignore - table might not exist
            }

            stmt.execute("CREATE TABLE mysql_show_test (id INT PRIMARY KEY)");

            // Test SHOW TABLES
            ResultSet rs = stmt.executeQuery("SHOW TABLES");
            boolean foundTable = false;
            while (rs.next()) {
                String tableName = rs.getString(1);
                if ("mysql_show_test".equals(tableName)) {
                    foundTable = true;
                    break;
                }
            }
            //"Table mysql_show_test should be found in SHOW TABLES",
            Assertions.assertTrue( foundTable);

            rs.close();
        }

        conn.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    void autoIncrementAndLastInsertIdTestSuccessful(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException {
        // Skip MySQL tests if not enabled
        if (url.toLowerCase().contains("mysql") && !isMySQLTestEnabled) {
            assumeFalse(true, "Skipping MySQL tests");
        }
        
        // Skip MariaDB tests if not enabled
        if (url.toLowerCase().contains("mariadb") && !isMariaDBTestEnabled) {
            assumeFalse(true, "Skipping MariaDB tests");
        }
        
        Connection conn = DriverManager.getConnection(url, user, pwd);

        System.out.println("Testing AUTO_INCREMENT and LAST_INSERT_ID() for url -> " + url);

        try (Statement stmt = conn.createStatement()) {
            // Drop table if exists
            try {
                stmt.execute("DROP TABLE mysql_auto_increment_test");
            } catch (SQLException e) {
                // Ignore - table might not exist
            }

            // Create table with auto-increment
            stmt.execute("CREATE TABLE mysql_auto_increment_test (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "name VARCHAR(100)" +
                    ")");

            // Insert data and test LAST_INSERT_ID()
            stmt.execute("INSERT INTO mysql_auto_increment_test (name) VALUES ('First Item')");
            ResultSet rs = stmt.executeQuery("SELECT LAST_INSERT_ID()");
            assertTrue(rs.next());
            long firstId = rs.getLong(1);
            assertTrue(firstId > 0);

            // Insert another item
            stmt.execute("INSERT INTO mysql_auto_increment_test (name) VALUES ('Second Item')");
            rs = stmt.executeQuery("SELECT LAST_INSERT_ID()");
            assertTrue(rs.next());
            long secondId = rs.getLong(1);
            assertEquals(firstId + 1, secondId);

            // Verify the data
            rs = stmt.executeQuery("SELECT id, name FROM mysql_auto_increment_test ORDER BY id");
            assertTrue(rs.next());
            assertEquals(firstId, rs.getLong("id"));
            assertEquals("First Item", rs.getString("name"));
            
            assertTrue(rs.next());
            assertEquals(secondId, rs.getLong("id"));
            assertEquals("Second Item", rs.getString("name"));

            rs.close();
        }

        conn.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    void mysqlInformationSchemaTestSuccessful(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException {
        // Skip MySQL tests if not enabled
        if (url.toLowerCase().contains("mysql") && !isMySQLTestEnabled) {
            assumeFalse(true, "Skipping MySQL tests");
        }
        
        // Skip MariaDB tests if not enabled
        if (url.toLowerCase().contains("mariadb") && !isMariaDBTestEnabled) {
            assumeFalse(true, "Skipping MariaDB tests");
        }
        
        Connection conn = DriverManager.getConnection(url, user, pwd);

        System.out.println("Testing MySQL INFORMATION_SCHEMA queries for url -> " + url);

        try (Statement stmt = conn.createStatement()) {
            // Test INFORMATION_SCHEMA.TABLES
            ResultSet rs = stmt.executeQuery(
                    "SELECT TABLE_NAME, TABLE_TYPE FROM INFORMATION_SCHEMA.TABLES " +
                    "WHERE TABLE_SCHEMA = DATABASE() LIMIT 10"
            );
            
            // Should have at least some results
            boolean hasResults = false;
            while (rs.next()) {
                hasResults = true;
                String tableName = rs.getString("TABLE_NAME");
                String tableType = rs.getString("TABLE_TYPE");
                assertNotNull(tableName);
                assertNotNull(tableType);
            }
            // Note: We can't assert hasResults because the database might be empty in tests
            
            rs.close();

            // Test INFORMATION_SCHEMA.COLUMNS
            rs = stmt.executeQuery(
                    "SELECT COLUMN_NAME, DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS " +
                    "WHERE TABLE_SCHEMA = DATABASE() LIMIT 10"
            );
            
            // Validate the query runs without error
            while (rs.next()) {
                String columnName = rs.getString("COLUMN_NAME");
                String dataType = rs.getString("DATA_TYPE");
                // Just verify these can be read without error
                assertNotNull(columnName);
                assertNotNull(dataType);
            }

            rs.close();
        }

        conn.close();
    }
}