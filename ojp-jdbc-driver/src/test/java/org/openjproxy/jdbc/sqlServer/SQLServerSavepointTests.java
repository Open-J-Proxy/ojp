package org.openjproxy.jdbc.sqlServer;

import org.openjproxy.jdbc.testutil.SQLServerConnectionProvider;
import org.openjproxy.jdbc.testutil.TestDBUtils;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * SQL Server-specific Savepoint integration tests.
 * Tests SQL Server-specific savepoint functionality and nested transactions.
 */
@EnabledIf("org.openjproxy.jdbc.testutil.SQLServerTestContainer#isEnabled")
class SQLServerSavepointTests {

    private static boolean isTestDisabled;

    @BeforeAll
    static void checkTestConfiguration() {
        isTestDisabled = !Boolean.parseBoolean(System.getProperty("enableSqlServerTests", "false"));
    }

    @ParameterizedTest
    @ArgumentsSource(SQLServerConnectionProvider.class)
    void testSqlServerBasicSavepoints(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "SQL Server tests are disabled");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);
        System.out.println("Testing SQL Server basic savepoints for url -> " + url);

        // Test if savepoints are supported
        DatabaseMetaData metaData = conn.getMetaData();
        boolean supportsNamedSavepoints = metaData.supportsNamedParameters();
        boolean supportsSavepoints = metaData.supportsSavepoints();
        
        if (!supportsSavepoints) {
            System.out.println("Savepoints not supported, skipping test");
            conn.close();
            return;
        }

        TestDBUtils.createBasicTestTable(conn, "sqlserver_savepoint_test", TestDBUtils.SqlSyntax.SQLSERVER, false);

        conn.setAutoCommit(false);

        try {
            // Insert initial data
            PreparedStatement ps1 = conn.prepareStatement(
                    "INSERT INTO sqlserver_savepoint_test (id, name) VALUES (?, ?)"
            );
            ps1.setInt(1, 1);
            ps1.setString(2, "Initial Data");
            ps1.executeUpdate();
            ps1.close();

            // Create savepoint
            Savepoint sp1 = conn.setSavepoint("savepoint1");

            // Insert more data
            PreparedStatement ps2 = conn.prepareStatement(
                    "INSERT INTO sqlserver_savepoint_test (id, name) VALUES (?, ?)"
            );
            ps2.setInt(1, 2);
            ps2.setString(2, "Savepoint Data");
            ps2.executeUpdate();
            ps2.close();

            // Verify both records exist
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM sqlserver_savepoint_test");
            assertTrue(rs.next());
            assertEquals(2, rs.getInt(1));
            rs.close();

            // Rollback to savepoint
            conn.rollback(sp1);

            // Verify only initial data remains
            rs = stmt.executeQuery("SELECT COUNT(*) FROM sqlserver_savepoint_test");
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
            rs.close();

            rs = stmt.executeQuery("SELECT name FROM sqlserver_savepoint_test WHERE id = 1");
            assertTrue(rs.next());
            assertEquals("Initial Data", rs.getString(1));
            rs.close();

            stmt.close();

            // Commit the transaction
            conn.commit();

        } finally {
            conn.setAutoCommit(true);
            TestDBUtils.cleanupTestTables(conn, "sqlserver_savepoint_test");
            conn.close();
        }
    }

    @ParameterizedTest
    @ArgumentsSource(SQLServerConnectionProvider.class)
    void testSqlServerNestedSavepoints(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "SQL Server tests are disabled");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);
        System.out.println("Testing SQL Server nested savepoints for url -> " + url);

        DatabaseMetaData metaData = conn.getMetaData();
        if (!metaData.supportsSavepoints()) {
            System.out.println("Savepoints not supported, skipping test");
            conn.close();
            return;
        }

        TestDBUtils.createBasicTestTable(conn, "sqlserver_nested_savepoint_test", TestDBUtils.SqlSyntax.SQLSERVER, false);

        conn.setAutoCommit(false);

        try {
            // Level 0: Insert initial data
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO sqlserver_nested_savepoint_test (id, name) VALUES (?, ?)"
            );
            ps.setInt(1, 1);
            ps.setString(2, "Level 0");
            ps.executeUpdate();

            // Level 1: Create first savepoint
            Savepoint sp1 = conn.setSavepoint("level1");
            ps.setInt(1, 2);
            ps.setString(2, "Level 1");
            ps.executeUpdate();

            // Level 2: Create second savepoint
            Savepoint sp2 = conn.setSavepoint("level2");
            ps.setInt(1, 3);
            ps.setString(2, "Level 2");
            ps.executeUpdate();

            ps.close();

            // Verify all three records exist
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM sqlserver_nested_savepoint_test");
            assertTrue(rs.next());
            assertEquals(3, rs.getInt(1));
            rs.close();

            // Rollback to level 2 savepoint
            conn.rollback(sp2);

            // Should have 2 records
            rs = stmt.executeQuery("SELECT COUNT(*) FROM sqlserver_nested_savepoint_test");
            assertTrue(rs.next());
            assertEquals(2, rs.getInt(1));
            rs.close();

            // Rollback to level 1 savepoint
            conn.rollback(sp1);

            // Should have 1 record
            rs = stmt.executeQuery("SELECT COUNT(*) FROM sqlserver_nested_savepoint_test");
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
            rs.close();

            rs = stmt.executeQuery("SELECT name FROM sqlserver_nested_savepoint_test WHERE id = 1");
            assertTrue(rs.next());
            assertEquals("Level 0", rs.getString(1));
            rs.close();

            stmt.close();
            conn.commit();

        } finally {
            conn.setAutoCommit(true);
            TestDBUtils.cleanupTestTables(conn, "sqlserver_nested_savepoint_test");
            conn.close();
        }
    }

    @ParameterizedTest
    @ArgumentsSource(SQLServerConnectionProvider.class)
    void testSqlServerSavepointRelease(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "SQL Server tests are disabled");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);
        System.out.println("Testing SQL Server savepoint release for url -> " + url);

        DatabaseMetaData metaData = conn.getMetaData();
        if (!metaData.supportsSavepoints()) {
            System.out.println("Savepoints not supported, skipping test");
            conn.close();
            return;
        }

        TestDBUtils.createBasicTestTable(conn, "sqlserver_savepoint_release_test", TestDBUtils.SqlSyntax.SQLSERVER, false);

        conn.setAutoCommit(false);

        try {
            // Insert initial data
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO sqlserver_savepoint_release_test (id, name) VALUES (?, ?)"
            );
            ps.setInt(1, 1);
            ps.setString(2, "Before Savepoint");
            ps.executeUpdate();

            // Create savepoint
            Savepoint sp = conn.setSavepoint("release_test");

            // Insert data after savepoint
            ps.setInt(1, 2);
            ps.setString(2, "After Savepoint");
            ps.executeUpdate();
            ps.close();

            // Release the savepoint (SQL Server may not support explicit release)
            try {
                conn.releaseSavepoint(sp);
                System.out.println("Savepoint release supported");
            } catch (SQLException e) {
                System.out.println("Savepoint release not supported: " + e.getMessage());
                // This is acceptable for SQL Server
            }

            // Verify data is still there
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM sqlserver_savepoint_release_test");
            assertTrue(rs.next());
            assertEquals(2, rs.getInt(1));
            rs.close();
            stmt.close();

            conn.commit();

        } finally {
            conn.setAutoCommit(true);
            TestDBUtils.cleanupTestTables(conn, "sqlserver_savepoint_release_test");
            conn.close();
        }
    }

    @ParameterizedTest
    @ArgumentsSource(SQLServerConnectionProvider.class)
    void testSqlServerSavepointWithBatch(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "SQL Server tests are disabled");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);
        System.out.println("Testing SQL Server savepoints with batch operations for url -> " + url);

        DatabaseMetaData metaData = conn.getMetaData();
        if (!metaData.supportsSavepoints()) {
            System.out.println("Savepoints not supported, skipping test");
            conn.close();
            return;
        }

        TestDBUtils.createBasicTestTable(conn, "sqlserver_savepoint_batch_test", TestDBUtils.SqlSyntax.SQLSERVER, false);

        conn.setAutoCommit(false);

        try {
            // Insert initial data
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO sqlserver_savepoint_batch_test (id, name) VALUES (?, ?)"
            );
            ps.setInt(1, 1);
            ps.setString(2, "Initial");
            ps.executeUpdate();

            // Create savepoint before batch
            Savepoint sp = conn.setSavepoint("batch_test");

            // Execute batch operations
            for (int i = 2; i <= 5; i++) {
                ps.setInt(1, i);
                ps.setString(2, "Batch " + i);
                ps.addBatch();
            }
            ps.executeBatch();
            ps.close();

            // Verify all data exists
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM sqlserver_savepoint_batch_test");
            assertTrue(rs.next());
            assertEquals(5, rs.getInt(1));
            rs.close();

            // Rollback the batch operations
            conn.rollback(sp);

            // Should only have initial data
            rs = stmt.executeQuery("SELECT COUNT(*) FROM sqlserver_savepoint_batch_test");
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
            rs.close();

            rs = stmt.executeQuery("SELECT name FROM sqlserver_savepoint_batch_test WHERE id = 1");
            assertTrue(rs.next());
            assertEquals("Initial", rs.getString(1));
            rs.close();

            stmt.close();
            conn.commit();

        } finally {
            conn.setAutoCommit(true);
            TestDBUtils.cleanupTestTables(conn, "sqlserver_savepoint_batch_test");
            conn.close();
        }
    }

    @ParameterizedTest
    @ArgumentsSource(SQLServerConnectionProvider.class)
    void testSqlServerSavepointException(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "SQL Server tests are disabled");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);
        System.out.println("Testing SQL Server savepoint exception handling for url -> " + url);

        DatabaseMetaData metaData = conn.getMetaData();
        if (!metaData.supportsSavepoints()) {
            System.out.println("Savepoints not supported, skipping test");
            conn.close();
            return;
        }

        TestDBUtils.createBasicTestTable(conn, "sqlserver_savepoint_exception_test", TestDBUtils.SqlSyntax.SQLSERVER, false);

        conn.setAutoCommit(false);

        try {
            // Insert valid data
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO sqlserver_savepoint_exception_test (id, name) VALUES (?, ?)"
            );
            ps.setInt(1, 1);
            ps.setString(2, "Valid Data");
            ps.executeUpdate();

            // Create savepoint
            Savepoint sp = conn.setSavepoint("exception_test");

            // Try to insert duplicate key (should cause exception)
            try {
                ps.setInt(1, 1); // Duplicate key
                ps.setString(2, "Duplicate");
                ps.executeUpdate();
                Assert.fail("Should have thrown SQLException for duplicate key");
            } catch (SQLException e) {
                // Expected exception
                System.out.println("Caught expected exception: " + e.getMessage());
            }

            ps.close();

            // Rollback to savepoint after exception
            conn.rollback(sp);

            // Insert different valid data
            PreparedStatement ps2 = conn.prepareStatement(
                    "INSERT INTO sqlserver_savepoint_exception_test (id, name) VALUES (?, ?)"
            );
            ps2.setInt(1, 2);
            ps2.setString(2, "Recovery Data");
            ps2.executeUpdate();
            ps2.close();

            // Verify final state
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM sqlserver_savepoint_exception_test");
            assertTrue(rs.next());
            assertEquals(2, rs.getInt(1));
            rs.close();

            rs = stmt.executeQuery("SELECT name FROM sqlserver_savepoint_exception_test ORDER BY id");
            assertTrue(rs.next());
            assertEquals("Valid Data", rs.getString(1));
            assertTrue(rs.next());
            assertEquals("Recovery Data", rs.getString(1));
            rs.close();

            stmt.close();
            conn.commit();

        } finally {
            conn.setAutoCommit(true);
            TestDBUtils.cleanupTestTables(conn, "sqlserver_savepoint_exception_test");
            conn.close();
        }
    }
}