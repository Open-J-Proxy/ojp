package org.openjproxy.jdbc.db2;

import lombok.SneakyThrows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class Db2SavepointTests {

    private static boolean isTestDisabled;
    private Connection connection;

    @BeforeAll
    static void checkTestConfiguration() {
        isTestDisabled = !Boolean.parseBoolean(System.getProperty("enableDb2Tests", "false"));
    }

    @SneakyThrows
    public void setUp(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "DB2 tests are disabled");

        connection = DriverManager.getConnection(url, user, pwd);
        connection.setAutoCommit(false);

        // Set schema explicitly to avoid "object not found" errors
        try (Statement schemaStmt = connection.createStatement()) {
            schemaStmt.execute("SET SCHEMA DB2INST1");
        }

        Statement stmt = connection.createStatement();
        try {
            stmt.execute("DROP TABLE DB2INST1.savepoint_test_table");
        } catch (SQLException e) {
            // Table might not exist
        }

        // DB2-specific CREATE TABLE syntax
        stmt.execute(
                "CREATE TABLE DB2INST1.savepoint_test_table (id INTEGER NOT NULL PRIMARY KEY, name VARCHAR(255))"
        );
        stmt.close();
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (connection != null) {
            try {
                Statement stmt = connection.createStatement();
                stmt.execute("DROP TABLE DB2INST1.savepoint_test_table");
                stmt.close();
            } catch (SQLException e) {
                // Ignore
            }
            connection.close();
        }
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void testDb2SavepointBasicOperations(String driverClass, String url, String user, String pwd) throws SQLException {
        setUp(driverClass, url, user, pwd);

        Statement stmt = connection.createStatement();

        // Insert initial data
        stmt.executeUpdate("INSERT INTO DB2INST1.savepoint_test_table (id, name) VALUES (1, 'Initial Record')");

        // Create a savepoint
        Savepoint savepoint1 = connection.setSavepoint("SavePoint1");
        assertNotNull(savepoint1);
        assertEquals("SavePoint1", savepoint1.getSavepointName());

        // Insert more data after savepoint
        stmt.executeUpdate("INSERT INTO DB2INST1.savepoint_test_table (id, name) VALUES (2, 'After Savepoint')");

        // Verify both records exist
        ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM DB2INST1.savepoint_test_table");
        assertTrue(rs.next());
        assertEquals(2, rs.getInt(1));
        rs.close();

        // Rollback to savepoint
        connection.rollback(savepoint1);

        // Verify only initial record exists
        rs = stmt.executeQuery("SELECT COUNT(*) FROM DB2INST1.savepoint_test_table");
        assertTrue(rs.next());
        assertEquals(1, rs.getInt(1));
        rs.close();

        // Verify the remaining record
        rs = stmt.executeQuery("SELECT id, name FROM DB2INST1.savepoint_test_table");
        assertTrue(rs.next());
        assertEquals(1, rs.getInt("id"));
        assertEquals("Initial Record", rs.getString("name"));
        assertFalse(rs.next());
        rs.close();

        stmt.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void testDb2MultipleSavepoints(String driverClass, String url, String user, String pwd) throws SQLException {
        setUp(driverClass, url, user, pwd);

        Statement stmt = connection.createStatement();

        // Insert initial data
        stmt.executeUpdate("INSERT INTO DB2INST1.savepoint_test_table (id, name) VALUES (1, 'Record 1')");

        // Create first savepoint
        Savepoint sp1 = connection.setSavepoint("SP1");

        // Insert more data
        stmt.executeUpdate("INSERT INTO DB2INST1.savepoint_test_table (id, name) VALUES (2, 'Record 2')");

        // Create second savepoint
        Savepoint sp2 = connection.setSavepoint("SP2");

        // Insert more data
        stmt.executeUpdate("INSERT INTO DB2INST1.savepoint_test_table (id, name) VALUES (3, 'Record 3')");

        // Verify all three records exist
        ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM DB2INST1.savepoint_test_table");
        assertTrue(rs.next());
        assertEquals(3, rs.getInt(1));
        rs.close();

        // Rollback to second savepoint (should have 2 records)
        connection.rollback(sp2);
        rs = stmt.executeQuery("SELECT COUNT(*) FROM DB2INST1.savepoint_test_table");
        assertTrue(rs.next());
        assertEquals(2, rs.getInt(1));
        rs.close();

        // Insert another record
        stmt.executeUpdate("INSERT INTO DB2INST1.savepoint_test_table (id, name) VALUES (4, 'Record 4')");

        // Rollback to first savepoint (should have 1 record)
        connection.rollback(sp1);
        rs = stmt.executeQuery("SELECT COUNT(*) FROM DB2INST1.savepoint_test_table");
        assertTrue(rs.next());
        assertEquals(1, rs.getInt(1));
        rs.close();

        stmt.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void testDb2SavepointRelease(String driverClass, String url, String user, String pwd) throws SQLException {
        setUp(driverClass, url, user, pwd);

        Statement stmt = connection.createStatement();

        // Insert initial data
        stmt.executeUpdate("INSERT INTO DB2INST1.savepoint_test_table (id, name) VALUES (1, 'Record 1')");

        // Create savepoint
        Savepoint sp = connection.setSavepoint("ReleaseSP");

        // Insert more data
        stmt.executeUpdate("INSERT INTO DB2INST1.savepoint_test_table (id, name) VALUES (2, 'Record 2')");

        // Release the savepoint
        connection.releaseSavepoint(sp);

        // Try to rollback to released savepoint - DB2 does not allow this (SQLCODE=-880/SQLSTATE=3B001):
        // once a savepoint is released it no longer exists.
        assertThrows(SQLException.class, () -> connection.rollback(sp));

        // Verify data is still there (since savepoint was released, not rolled back)
        ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM DB2INST1.savepoint_test_table");
        assertTrue(rs.next());
        assertEquals(2, rs.getInt(1));
        rs.close();

        stmt.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void testDb2UnnamedSavepoint(String driverClass, String url, String user, String pwd) throws SQLException {
        setUp(driverClass, url, user, pwd);

        Statement stmt = connection.createStatement();

        // Insert initial data
        stmt.executeUpdate("INSERT INTO DB2INST1.savepoint_test_table (id, name) VALUES (1, 'Record 1')");

        // Create unnamed savepoint
        Savepoint unnamedSp = connection.setSavepoint();
        assertNotNull(unnamedSp);
        assertTrue(unnamedSp.getSavepointId() > 0);

        // Insert more data
        stmt.executeUpdate("INSERT INTO DB2INST1.savepoint_test_table (id, name) VALUES (2, 'Record 2')");

        // Rollback to unnamed savepoint
        connection.rollback(unnamedSp);

        // Verify only initial record exists
        ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM DB2INST1.savepoint_test_table");
        assertTrue(rs.next());
        assertEquals(1, rs.getInt(1));
        rs.close();

        stmt.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void testDb2SavepointWithCommit(String driverClass, String url, String user, String pwd) throws SQLException {
        setUp(driverClass, url, user, pwd);

        Statement stmt = connection.createStatement();

        // Insert initial data
        stmt.executeUpdate("INSERT INTO DB2INST1.savepoint_test_table (id, name) VALUES (1, 'Record 1')");

        // Create savepoint
        Savepoint sp = connection.setSavepoint("CommitSP");

        // Insert more data
        stmt.executeUpdate("INSERT INTO DB2INST1.savepoint_test_table (id, name) VALUES (2, 'Record 2')");

        // Commit the transaction
        connection.commit();

        // After commit, the savepoint no longer exists; DB2 throws SQLCODE=-880/SQLSTATE=3B001
        // when ROLLBACK TO SAVEPOINT references a savepoint cleared by the commit.
        assertThrows(SQLException.class, () -> connection.rollback(sp));

        // Verify data is committed
        ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM DB2INST1.savepoint_test_table");
        assertTrue(rs.next());
        assertEquals(2, rs.getInt(1));
        rs.close();

        stmt.close();
    }
}