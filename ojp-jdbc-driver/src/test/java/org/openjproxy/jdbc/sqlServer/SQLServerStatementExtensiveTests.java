package org.openjproxy.jdbc.sqlServer;

import org.openjproxy.jdbc.testutil.SQLServerConnectionProvider;
import org.openjproxy.jdbc.testutil.TestDBUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * SQL Server-specific Statement integration tests.
 * Tests SQL Server-specific Statement functionality and SQL syntax.
 */
@EnabledIf("org.openjproxy.jdbc.testutil.SQLServerTestContainer#isEnabled")
public class SQLServerStatementExtensiveTests {

    private static boolean isTestDisabled;

    @BeforeAll
    static void checkTestConfiguration() {
        isTestDisabled = !Boolean.parseBoolean(System.getProperty("enableSqlServerTests", "false"));
    }

    @ParameterizedTest
    @ArgumentsSource(SQLServerConnectionProvider.class)
    void testSqlServerBasicStatementOperations(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "SQL Server tests are disabled");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);
        System.out.println("Testing SQL Server basic Statement operations for url -> " + url);

        TestDBUtils.createBasicTestTable(conn, "sqlserver_stmt_basic_test", TestDBUtils.SqlSyntax.SQLSERVER, true);

        Statement stmt = conn.createStatement();

        // Test INSERT
        int insertCount = stmt.executeUpdate(
                "INSERT INTO sqlserver_stmt_basic_test (id, name) VALUES (100, N'Statement Test')"
        );
        assertEquals(1, insertCount);

        // Test SELECT
        ResultSet rs = stmt.executeQuery("SELECT * FROM sqlserver_stmt_basic_test WHERE id = 100");
        assertTrue(rs.next());
        assertEquals(100, rs.getInt("id"));
        assertEquals("Statement Test", rs.getString("name"));
        rs.close();

        // Test UPDATE
        int updateCount = stmt.executeUpdate(
                "UPDATE sqlserver_stmt_basic_test SET name = N'Updated Test' WHERE id = 100"
        );
        assertEquals(1, updateCount);

        // Verify update
        rs = stmt.executeQuery("SELECT name FROM sqlserver_stmt_basic_test WHERE id = 100");
        assertTrue(rs.next());
        assertEquals("Updated Test", rs.getString("name"));
        rs.close();

        // Test DELETE
        int deleteCount = stmt.executeUpdate("DELETE FROM sqlserver_stmt_basic_test WHERE id = 100");
        assertEquals(1, deleteCount);

        // Verify delete
        rs = stmt.executeQuery("SELECT COUNT(*) FROM sqlserver_stmt_basic_test");
        assertTrue(rs.next());
        assertEquals(2, rs.getInt(1));
        rs.close();

        stmt.close();
        TestDBUtils.cleanupTestTables(conn, "sqlserver_stmt_basic_test");
        conn.close();
    }

    @ParameterizedTest
    @ArgumentsSource(SQLServerConnectionProvider.class)
    void testSqlServerSpecificSyntax(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "SQL Server tests are disabled");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);
        System.out.println("Testing SQL Server specific syntax for url -> " + url);

        Statement stmt = conn.createStatement();

        // Create table with SQL Server-specific features
        stmt.execute("IF OBJECT_ID('sqlserver_syntax_test', 'U') IS NOT NULL DROP TABLE sqlserver_syntax_test");
        
        stmt.execute("CREATE TABLE sqlserver_syntax_test (" +
                "id INT IDENTITY(1,1) PRIMARY KEY, " +
                "name NVARCHAR(50) NOT NULL, " +
                "created_date DATETIME2 DEFAULT GETDATE(), " +
                "guid_col UNIQUEIDENTIFIER DEFAULT NEWID())");

        // Test SQL Server-specific INSERT with IDENTITY
        stmt.execute("INSERT INTO sqlserver_syntax_test (name) VALUES (N'Test 1')");
        stmt.execute("INSERT INTO sqlserver_syntax_test (name) VALUES (N'Test 2')");
        stmt.execute("INSERT INTO sqlserver_syntax_test (name) VALUES (N'Test 3')");

        // Test SQL Server-specific TOP clause
        ResultSet rs = stmt.executeQuery("SELECT TOP 2 id, name FROM sqlserver_syntax_test ORDER BY id");
        int count = 0;
        while (rs.next()) {
            count++;
            assertEquals(count, rs.getInt("id"));
            assertEquals("Test " + count, rs.getString("name"));
        }
        assertEquals(2, count);
        rs.close();

        // Test SQL Server-specific OFFSET/FETCH
        rs = stmt.executeQuery(
                "SELECT id, name FROM sqlserver_syntax_test ORDER BY id OFFSET 1 ROWS FETCH NEXT 1 ROWS ONLY"
        );
        assertTrue(rs.next());
        assertEquals(2, rs.getInt("id"));
        assertEquals("Test 2", rs.getString("name"));
        assertFalse(rs.next());
        rs.close();

        // Test SQL Server-specific OUTPUT clause
        ResultSet outputRs = stmt.executeQuery(
                "INSERT INTO sqlserver_syntax_test (name) OUTPUT INSERTED.id VALUES (N'Test with OUTPUT')"
        );
        assertTrue(outputRs.next());
        int insertedId = outputRs.getInt(1);
        System.out.println("Inserted ID: " + insertedId);
        outputRs.close();

        stmt.close();
        TestDBUtils.cleanupTestTables(conn, "sqlserver_syntax_test");
        conn.close();
    }

    @ParameterizedTest
    @ArgumentsSource(SQLServerConnectionProvider.class)
    void testSqlServerBatchStatements(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "SQL Server tests are disabled");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);
        System.out.println("Testing SQL Server batch statements for url -> " + url);

        TestDBUtils.createBasicTestTable(conn, "sqlserver_batch_stmt_test", TestDBUtils.SqlSyntax.SQLSERVER, false);

        Statement stmt = conn.createStatement();

        // Add multiple statements to batch
        stmt.addBatch("INSERT INTO sqlserver_batch_stmt_test (id, name) VALUES (10, N'Batch 1')");
        stmt.addBatch("INSERT INTO sqlserver_batch_stmt_test (id, name) VALUES (20, N'Batch 2')");
        stmt.addBatch("INSERT INTO sqlserver_batch_stmt_test (id, name) VALUES (30, N'Batch 3')");
        stmt.addBatch("UPDATE sqlserver_batch_stmt_test SET name = N'Updated Batch 1' WHERE id = 10");

        // Execute batch
        int[] results = stmt.executeBatch();
        assertEquals(4, results.length);
        for (int i = 0; i < 3; i++) {
            assertEquals(1, results[i]); // Each INSERT affects 1 row
        }
        assertEquals(1, results[3]); // UPDATE affects 1 row

        // Verify batch results
        ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM sqlserver_batch_stmt_test");
        assertTrue(rs.next());
        assertEquals(3, rs.getInt(1));
        rs.close();

        rs = stmt.executeQuery("SELECT name FROM sqlserver_batch_stmt_test WHERE id = 10");

        assertTrue(rs.next());
        assertEquals("Updated Batch 1", rs.getString(1));
        rs.close();

        stmt.close();
        TestDBUtils.cleanupTestTables(conn, "sqlserver_batch_stmt_test");
        conn.close();
    }

    @ParameterizedTest
    @ArgumentsSource(SQLServerConnectionProvider.class)
    void testSqlServerStoredProcedures(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "SQL Server tests are disabled");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);
        System.out.println("Testing SQL Server stored procedures for url -> " + url);

        Statement stmt = conn.createStatement();

        TestDBUtils.createBasicTestTable(conn, "sqlserver_proc_test", TestDBUtils.SqlSyntax.SQLSERVER, false);

        // Create a simple stored procedure
        try {
            stmt.execute("DROP PROCEDURE IF EXISTS GetTestData");
        } catch (SQLException e) {
            // Ignore if procedure doesn't exist
        }

        stmt.execute("CREATE PROCEDURE GetTestData @id INT WITH EXECUTE AS OWNER AS " +
                "BEGIN " +
                "SELECT * FROM sqlserver_proc_test WHERE id = @id " +
                "END");

        // Insert test data
        stmt.execute("INSERT INTO sqlserver_proc_test (id, name) VALUES (11, N'Procedure Test')");

        // Test calling stored procedure
        CallableStatement cs = conn.prepareCall("{call GetTestData(?)}");
        cs.setInt(1, 11);
        ResultSet rs = cs.executeQuery();

        assertTrue(rs.next());
        assertEquals(11, rs.getInt("id"));
        assertEquals("Procedure Test", rs.getString("name"));
        rs.close();
        cs.close();

        // Clean up
        stmt.execute("DROP PROCEDURE GetTestData");
        stmt.close();
        TestDBUtils.cleanupTestTables(conn, "sqlserver_proc_test");
        conn.close();
    }

    @ParameterizedTest
    @ArgumentsSource(SQLServerConnectionProvider.class)
    void testSqlServerTransactionStatements(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "SQL Server tests are disabled");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);
        System.out.println("Testing SQL Server transaction statements for url -> " + url);

        TestDBUtils.cleanupTestTables(conn, "sqlserver_txn_stmt_test");
        TestDBUtils.createBasicTestTable(conn, "sqlserver_txn_stmt_test", TestDBUtils.SqlSyntax.SQLSERVER, false);

        Statement stmt = conn.createStatement();
        conn.setAutoCommit(false);

        try {
            // Insert data in transaction
            stmt.execute("INSERT INTO sqlserver_txn_stmt_test (id, name) VALUES (100, N'Transaction Test')");

            // Verify data exists in transaction
            ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM sqlserver_txn_stmt_test");
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
            rs.close();

            // Test explicit transaction commands
            stmt.execute("SAVE TRANSACTION SP1");
            
            stmt.execute("INSERT INTO sqlserver_txn_stmt_test (id, name) VALUES (200, N'Savepoint Test')");
            
            // Verify both records
            rs = stmt.executeQuery("SELECT COUNT(*) FROM sqlserver_txn_stmt_test");
            assertTrue(rs.next());
            assertEquals(2, rs.getInt(1));
            rs.close();

            // Rollback to savepoint
            stmt.execute("ROLLBACK TRANSACTION SP1");

            // Should only have first record
            rs = stmt.executeQuery("SELECT COUNT(*) FROM sqlserver_txn_stmt_test");
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
            rs.close();

            conn.commit();

        } finally {
            conn.setAutoCommit(true);
            stmt.close();
            TestDBUtils.cleanupTestTables(conn, "sqlserver_txn_stmt_test");
            conn.close();
        }
    }

    @ParameterizedTest
    @ArgumentsSource(SQLServerConnectionProvider.class)
    void testSqlServerLargeQueries(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "SQL Server tests are disabled");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);
        System.out.println("Testing SQL Server large queries for url -> " + url);

        TestDBUtils.createBasicTestTable(conn, "sqlserver_large_query_test", TestDBUtils.SqlSyntax.SQLSERVER, false);

        Statement stmt = conn.createStatement();

        // Insert multiple records
        for (int i = 1; i <= 100; i++) {
            stmt.execute("INSERT INTO sqlserver_large_query_test (id, name) VALUES (" + i + ", N'Record " + i + "')");
        }

        // Test large result set
        ResultSet rs = stmt.executeQuery("SELECT * FROM sqlserver_large_query_test ORDER BY id");
        //There are two default records in the table that are not related to this test.
        int count = 0;
        while (rs.next()) {
            count++;
            assertEquals(count, rs.getInt("id"));
            assertEquals("Record " + count, rs.getString("name"));
        }
        assertEquals(100, count);
        rs.close();

        stmt.close();
        TestDBUtils.cleanupTestTables(conn, "sqlserver_large_query_test");
        conn.close();
    }

    @ParameterizedTest
    @ArgumentsSource(SQLServerConnectionProvider.class)
    void testSqlServerStatementProperties(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "SQL Server tests are disabled");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);
        System.out.println("Testing SQL Server statement properties for url -> " + url);

        Statement stmt = conn.createStatement();

        // Test default properties
        assertEquals(ResultSet.TYPE_FORWARD_ONLY, stmt.getResultSetType());
        assertEquals(ResultSet.CONCUR_READ_ONLY, stmt.getResultSetConcurrency());

        // Test fetch size
        stmt.setFetchSize(50);
        assertEquals(50, stmt.getFetchSize());

        // Test max rows
        stmt.setMaxRows(100);
        assertEquals(100, stmt.getMaxRows());

        // Test query timeout
        stmt.setQueryTimeout(30);
        assertEquals(30, stmt.getQueryTimeout());

        // Test escape processing
        stmt.setEscapeProcessing(false);
        stmt.setEscapeProcessing(true);

        stmt.close();

        // Test different result set types
        Statement scrollableStmt = conn.createStatement(
                ResultSet.TYPE_SCROLL_INSENSITIVE,
                ResultSet.CONCUR_READ_ONLY
        );
        assertEquals(ResultSet.TYPE_FORWARD_ONLY, scrollableStmt.getResultSetType());
        scrollableStmt.close();

        conn.close();
    }
}