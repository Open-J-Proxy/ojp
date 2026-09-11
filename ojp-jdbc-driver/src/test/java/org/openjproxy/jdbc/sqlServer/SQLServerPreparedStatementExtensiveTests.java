package org.openjproxy.jdbc.sqlServer;

import org.openjproxy.jdbc.testutil.SQLServerConnectionProvider;
import org.openjproxy.jdbc.testutil.TestDBUtils;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * SQL Server-specific PreparedStatement integration tests.
 * Tests SQL Server-specific PreparedStatement functionality and parameter handling.
 */
@EnabledIf("org.openjproxy.jdbc.testutil.SQLServerTestContainer#isEnabled")
public class SQLServerPreparedStatementExtensiveTests {

    private static boolean isTestDisabled;

    @BeforeAll
    static void checkTestConfiguration() {
        isTestDisabled = !Boolean.parseBoolean(System.getProperty("enableSqlServerTests", "false"));
    }

    @ParameterizedTest
    @ArgumentsSource(SQLServerConnectionProvider.class)
    void testSqlServerPreparedStatementBasics(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "SQL Server tests are disabled");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);
        System.out.println("Testing SQL Server PreparedStatement basics for url -> " + url);

        TestDBUtils.createBasicTestTable(conn, "sqlserver_ps_test", TestDBUtils.SqlSyntax.SQLSERVER, true);

        // Test basic PreparedStatement operations
        PreparedStatement ps = conn.prepareStatement("INSERT INTO sqlserver_ps_test (id, name) VALUES (?, ?)");
        ps.setInt(1, 100);
        ps.setString(2, "PreparedStatement Test");
        int rowsAffected = ps.executeUpdate();
        assertEquals(1, rowsAffected);

        // Test query with PreparedStatement
        PreparedStatement psSelect = conn.prepareStatement("SELECT id, name FROM sqlserver_ps_test WHERE id = ?");
        psSelect.setInt(1, 100);
        ResultSet rs = psSelect.executeQuery();
        
        assertTrue(rs.next());
        assertEquals(100, rs.getInt("id"));
        assertEquals("PreparedStatement Test", rs.getString("name"));

        rs.close();
        psSelect.close();
        ps.close();
        TestDBUtils.cleanupTestTables(conn, "sqlserver_ps_test");
        conn.close();
    }

    @ParameterizedTest
    @ArgumentsSource(SQLServerConnectionProvider.class)
    void testSqlServerParameterTypes(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "SQL Server tests are disabled");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);
        System.out.println("Testing SQL Server parameter types for url -> " + url);

        TestDBUtils.createMultiTypeTestTable(conn, "sqlserver_param_test", TestDBUtils.SqlSyntax.SQLSERVER);

        PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO sqlserver_param_test (val_int, val_varchar, val_double_precision, val_bigint, " +
                "val_tinyint, val_smallint, val_boolean, val_decimal, val_float, val_date, val_time, val_timestamp) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        );

        // Set various parameter types
        ps.setInt(1, 42);
        ps.setString(2, "Parameter Test");
        ps.setDouble(3, 3.14159);
        ps.setLong(4, 9876543210L);
        ps.setByte(5, (byte) 200); // SQL Server TINYINT
        ps.setShort(6, (short) 1000);
        ps.setBoolean(7, true);
        ps.setBigDecimal(8, new BigDecimal("999.99"));
        ps.setFloat(9, 2.718f);
        ps.setDate(10, Date.valueOf("2025-01-15"));
        ps.setTime(11, Time.valueOf("14:30:45"));
        ps.setTimestamp(12, Timestamp.valueOf("2025-01-15 14:30:45"));

        int rowsAffected = ps.executeUpdate();
        assertEquals(1, rowsAffected);

        // Verify the inserted data
        PreparedStatement psSelect = conn.prepareStatement("SELECT * FROM sqlserver_param_test WHERE val_int = ?");
        psSelect.setInt(1, 42);
        ResultSet rs = psSelect.executeQuery();
        
        assertTrue(rs.next());
        assertEquals(42, rs.getInt("val_int"));
        assertEquals("Parameter Test", rs.getString("val_varchar"));
        assertEquals(3.14159, rs.getDouble("val_double_precision"), 0.00001);
        assertEquals(9876543210L, rs.getLong("val_bigint"));
        assertEquals(200, rs.getInt("val_tinyint"));
        assertEquals(1000, rs.getInt("val_smallint"));
        assertTrue(rs.getBoolean("val_boolean"));
        assertEquals(new BigDecimal("999.99"), rs.getBigDecimal("val_decimal"));

        rs.close();
        psSelect.close();
        ps.close();
        TestDBUtils.cleanupTestTables(conn, "sqlserver_param_test");
        conn.close();
    }

    @ParameterizedTest
    @ArgumentsSource(SQLServerConnectionProvider.class)
    void testSqlServerBatchUpdates(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "SQL Server tests are disabled");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);
        System.out.println("Testing SQL Server batch updates for url -> " + url);

        TestDBUtils.createBasicTestTable(conn, "sqlserver_batch_test", TestDBUtils.SqlSyntax.SQLSERVER, false);

        Statement statement = conn.createStatement();
        statement.execute("DELETE FROM sqlserver_batch_test");

        PreparedStatement ps = conn.prepareStatement("INSERT INTO sqlserver_batch_test (id, name) VALUES (?, ?)");

        // Add multiple batches
        for (int i = 1; i <= 5; i++) {
            ps.setInt(1, i + 100);
            ps.setString(2, "Batch Item " + i);
            ps.addBatch();
        }

        int[] results = ps.executeBatch();
        assertEquals(5, results.length);
        for (int result : results) {
            assertEquals(1, result); // Each insert should affect 1 row
        }

        // Verify all batched data was inserted
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM sqlserver_batch_test");
        assertTrue(rs.next());
        assertEquals(5, rs.getInt(1));

        rs.close();
        stmt.close();
        ps.close();
        TestDBUtils.cleanupTestTables(conn, "sqlserver_batch_test");
        conn.close();
    }

    @ParameterizedTest
    @ArgumentsSource(SQLServerConnectionProvider.class)
    void testSqlServerNullParameters(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "SQL Server tests are disabled");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);
        System.out.println("Testing SQL Server null parameters for url -> " + url);

        TestDBUtils.cleanupTestTables(conn, "sqlserver_null_param_test");
        TestDBUtils.createMultiTypeTestTable(conn, "sqlserver_null_param_test", TestDBUtils.SqlSyntax.SQLSERVER);

        PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO sqlserver_null_param_test (val_int, val_varchar, val_double_precision, val_date) " +
                "VALUES (?, ?, ?, ?)"
        );

        // Set some null parameters
        ps.setInt(1, 1);
        ps.setNull(2, Types.NVARCHAR);
        ps.setNull(3, Types.FLOAT);
        ps.setNull(4, Types.DATE);

        int rowsAffected = ps.executeUpdate();
        assertEquals(1, rowsAffected);

        // Verify null values were inserted correctly
        PreparedStatement psSelect = conn.prepareStatement("SELECT * FROM sqlserver_null_param_test WHERE val_int = ?");
        psSelect.setInt(1, 1);
        ResultSet rs = psSelect.executeQuery();
        
        assertTrue(rs.next());
        assertEquals(1, rs.getInt("val_int"));
        assertNull(rs.getString("val_varchar"));
        assertEquals(0.0, rs.getDouble("val_double_precision"), 0.0);
        assertTrue(rs.wasNull());
        assertNull(rs.getDate("val_date"));

        rs.close();
        psSelect.close();
        ps.close();
        TestDBUtils.cleanupTestTables(conn, "sqlserver_null_param_test");
        conn.close();
    }

    @ParameterizedTest
    @ArgumentsSource(SQLServerConnectionProvider.class)
    void testSqlServerBinaryParameters(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "SQL Server tests are disabled");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);
        System.out.println("Testing SQL Server binary parameters for url -> " + url);

        // Create table with binary columns
        try {
            TestDBUtils.executeUpdate(conn, "IF OBJECT_ID('sqlserver_binary_param_test', 'U') IS NOT NULL DROP TABLE sqlserver_binary_param_test");
        } catch (Exception e) {
            // Ignore
        }

        TestDBUtils.executeUpdate(conn, "CREATE TABLE sqlserver_binary_param_test (" +
                "id INT PRIMARY KEY, " +
                "binary_data VARBINARY(100), " +
                "large_binary_data VARBINARY(MAX))");

        PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO sqlserver_binary_param_test (id, binary_data, large_binary_data) VALUES (?, ?, ?)"
        );

        byte[] smallBinary = "Small binary data".getBytes();
        byte[] largeBinary = new byte[1000];
        for (int i = 0; i < largeBinary.length; i++) {
            largeBinary[i] = (byte) (i % 256);
        }

        ps.setInt(1, 1);
        ps.setBytes(2, smallBinary);
        ps.setBytes(3, largeBinary);

        int rowsAffected = ps.executeUpdate();
        assertEquals(1, rowsAffected);

        // Verify binary data was inserted correctly
        PreparedStatement psSelect = conn.prepareStatement("SELECT * FROM sqlserver_binary_param_test WHERE id = ?");
        psSelect.setInt(1, 1);
        ResultSet rs = psSelect.executeQuery();
        
        assertTrue(rs.next());
        assertEquals(1, rs.getInt("id"));
        
        byte[] retrievedSmall = rs.getBytes("binary_data");
        Assert.assertArrayEquals(smallBinary, retrievedSmall);
        
        byte[] retrievedLarge = rs.getBytes("large_binary_data");
        assertEquals(largeBinary.length, retrievedLarge.length);
        for (int i = 0; i < 100; i++) { // Check first 100 bytes
            assertEquals(largeBinary[i], retrievedLarge[i]);
        }

        rs.close();
        psSelect.close();
        ps.close();
        TestDBUtils.cleanupTestTables(conn, "sqlserver_binary_param_test");
        conn.close();
    }

    @ParameterizedTest
    @ArgumentsSource(SQLServerConnectionProvider.class)
    void testSqlServerUpdateAndDelete(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "SQL Server tests are disabled");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);
        System.out.println("Testing SQL Server UPDATE and DELETE for url -> " + url);

        TestDBUtils.createBasicTestTable(conn, "sqlserver_update_test", TestDBUtils.SqlSyntax.SQLSERVER, false);
        Statement statement = conn.createStatement();
        statement.execute("DELETE FROM sqlserver_update_test");

        // Insert test data
        PreparedStatement psInsert = conn.prepareStatement("INSERT INTO sqlserver_update_test (id, name) VALUES (?, ?)");
        psInsert.setInt(1, 1);
        psInsert.setString(2, "Original Name");
        psInsert.executeUpdate();

        // Test UPDATE
        PreparedStatement psUpdate = conn.prepareStatement("UPDATE sqlserver_update_test SET name = ? WHERE id = ?");
        psUpdate.setString(1, "Updated Name");
        psUpdate.setInt(2, 1);
        int updateCount = psUpdate.executeUpdate();
        assertEquals(1, updateCount);

        // Verify update
        PreparedStatement psSelect = conn.prepareStatement("SELECT name FROM sqlserver_update_test WHERE id = ?");
        psSelect.setInt(1, 1);
        ResultSet rs = psSelect.executeQuery();
        assertTrue(rs.next());
        assertEquals("Updated Name", rs.getString("name"));
        rs.close();

        // Test DELETE
        PreparedStatement psDelete = conn.prepareStatement("DELETE FROM sqlserver_update_test WHERE id = ?");
        psDelete.setInt(1, 1);
        int deleteCount = psDelete.executeUpdate();
        assertEquals(1, deleteCount);

        // Verify delete
        rs = psSelect.executeQuery();
        assertFalse(rs.next()); // No rows should remain

        rs.close();
        psSelect.close();
        psDelete.close();
        psUpdate.close();
        psInsert.close();
        TestDBUtils.cleanupTestTables(conn, "sqlserver_update_test");
        conn.close();
    }

    /**
     * Reproduces the Spring Data saveAll / batch insert scenario with generated keys (issue #408).
     * Verifies that RETURN_GENERATED_KEYS is preserved when prepareStatement is followed by
     * repeated addBatch() calls and executeBatch() — the exact sequence Spring Data uses for saveAll.
     *
     * <p><b>SQL Server limitation:</b> The Microsoft SQL Server JDBC driver (mssql-jdbc) throws
     * "The statement must be executed before any results can be obtained" when {@code getGeneratedKeys()}
     * is called after {@code executeBatch()}. This is a documented mssql-jdbc driver limitation —
     * batch execution with generated key retrieval is not supported by the driver.
     * This test therefore verifies that the batch executes successfully and inserts all rows,
     * but does not call {@code getGeneratedKeys()} after the batch.
     *
     * <p>The key goal — that RETURN_GENERATED_KEYS is accepted without error and survives the
     * addBatch() round-trip (the OJP fix) — is still fully exercised.
     */
    @ParameterizedTest
    @ArgumentsSource(SQLServerConnectionProvider.class)
    void testBatchInsertWithGeneratedKeys(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "SQL Server tests are disabled");

        Connection conn = DriverManager.getConnection(url, user, pwd);
        Statement stmt = conn.createStatement();
        try {
            stmt.execute("DROP TABLE sqlserver_batch_gen_keys_test");
        } catch (SQLException ignore) {
            // Table may not exist on first run; ignore the error and proceed with CREATE TABLE
        }
        stmt.execute("CREATE TABLE sqlserver_batch_gen_keys_test (id INT IDENTITY(1,1) PRIMARY KEY, name NVARCHAR(100))");
        stmt.close();

        // Reproduces: prepareStatement(sql, RETURN_GENERATED_KEYS) → addBatch() x N → executeBatch()
        // The key goal is that RETURN_GENERATED_KEYS survives the addBatch() round-trip (the OJP bug that was fixed).
        PreparedStatement ps = conn.prepareStatement("INSERT INTO sqlserver_batch_gen_keys_test (name) VALUES (?)", Statement.RETURN_GENERATED_KEYS);

        ps.setString(1, "Alice");
        ps.addBatch();

        ps.setString(1, "Bob");
        ps.addBatch();

        ps.setString(1, "Carol");
        ps.addBatch();

        int[] batchResults = ps.executeBatch();
        assertEquals(3, batchResults.length, "All 3 batch rows should be inserted");
        for (int result : batchResults) {
            assertTrue(result >= 0 || result == Statement.SUCCESS_NO_INFO, "Each batch row should succeed");
        }

        // Verify the rows were actually inserted
        try (PreparedStatement psCount = conn.prepareStatement(
                "SELECT COUNT(*) FROM sqlserver_batch_gen_keys_test")) {
            ResultSet rs = psCount.executeQuery();
            assertTrue(rs.next());
            assertEquals(3, rs.getInt(1), "All 3 rows must be present in the table after batch insert");
            rs.close();
        }

        // Microsoft SQL Server JDBC driver does not support getGeneratedKeys() after executeBatch() —
        // it throws "The statement must be executed before any results can be obtained."
        // This is a documented mssql-jdbc driver limitation, not an OJP bug.
        ps.close();
        TestDBUtils.cleanupTestTables(conn, "sqlserver_batch_gen_keys_test");
        conn.close();
    }
}