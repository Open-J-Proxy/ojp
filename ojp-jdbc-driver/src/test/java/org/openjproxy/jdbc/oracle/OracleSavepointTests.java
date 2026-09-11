package org.openjproxy.jdbc.oracle;

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

public class OracleSavepointTests {

    private static boolean isTestDisabled;
    private Connection connection;

    @BeforeAll
    static void checkTestConfiguration() {
        isTestDisabled = !Boolean.parseBoolean(System.getProperty("enableOracleTests", "false"));
    }

    @SneakyThrows
    public void setUp(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "Oracle tests are disabled");
        
        connection = DriverManager.getConnection(url, user, pwd);
        connection.setAutoCommit(false);
        
        Statement stmt = connection.createStatement();
        try {
            stmt.execute("DROP TABLE savepoint_test_table");
        } catch (SQLException e) {
            // Table might not exist
        }
        
        // Oracle-specific CREATE TABLE syntax
        stmt.execute(
            "CREATE TABLE savepoint_test_table (id NUMBER(10) PRIMARY KEY, name VARCHAR2(255))"
        );
        stmt.close();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) connection.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testUnnamedSavepoint(String driverClass, String url, String user, String pwd) throws SQLException {
        setUp(driverClass, url, user, pwd);
        
        connection.createStatement().execute("INSERT INTO savepoint_test_table (id, name) VALUES (1, 'Alice')");
        Savepoint savepoint = connection.setSavepoint();

        connection.createStatement().execute("INSERT INTO savepoint_test_table (id, name) VALUES (2, 'Bob')");
        connection.rollback(savepoint);

        ResultSet resultSet = connection.createStatement().executeQuery("SELECT * FROM savepoint_test_table ORDER BY id DESC");
        assertTrue(resultSet.next());
        assertEquals(1, resultSet.getInt("id"));
        assertEquals("Alice", resultSet.getString("name"));
        assertFalse(resultSet.next()); // Should only have one row
        resultSet.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testNamedSavepoint(String driverClass, String url, String user, String pwd) throws SQLException {
        setUp(driverClass, url, user, pwd);
        
        connection.createStatement().execute("INSERT INTO savepoint_test_table (id, name) VALUES (1, 'Alice')");
        Savepoint savepoint = connection.setSavepoint("sp1");
        
        assertEquals("sp1", savepoint.getSavepointName());

        connection.createStatement().execute("INSERT INTO savepoint_test_table (id, name) VALUES (2, 'Bob')");
        connection.createStatement().execute("INSERT INTO savepoint_test_table (id, name) VALUES (3, 'Charlie')");
        
        connection.rollback(savepoint);

        ResultSet resultSet = connection.createStatement().executeQuery("SELECT COUNT(*) AS cnt FROM savepoint_test_table");
        assertTrue(resultSet.next());
        assertEquals(1, resultSet.getInt("cnt")); // Should only have Alice
        resultSet.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testMultipleSavepoints(String driverClass, String url, String user, String pwd) throws SQLException {
        setUp(driverClass, url, user, pwd);
        
        connection.createStatement().execute("INSERT INTO savepoint_test_table (id, name) VALUES (1, 'Alice')");
        Savepoint sp1 = connection.setSavepoint("sp1");

        connection.createStatement().execute("INSERT INTO savepoint_test_table (id, name) VALUES (2, 'Bob')");
        Savepoint sp2 = connection.setSavepoint("sp2");

        connection.createStatement().execute("INSERT INTO savepoint_test_table (id, name) VALUES (3, 'Charlie')");
        
        // Rollback to sp2 - should remove Charlie but keep Bob
        connection.rollback(sp2);
        
        ResultSet resultSet = connection.createStatement().executeQuery("SELECT COUNT(*) AS cnt FROM savepoint_test_table");
        assertTrue(resultSet.next());
        assertEquals(2, resultSet.getInt("cnt")); // Should have Alice and Bob
        resultSet.close();
        
        // Now rollback to sp1 - should only keep Alice
        connection.rollback(sp1);
        
        resultSet = connection.createStatement().executeQuery("SELECT COUNT(*) AS cnt FROM savepoint_test_table");
        assertTrue(resultSet.next());
        assertEquals(1, resultSet.getInt("cnt")); // Should only have Alice
        resultSet.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testReleaseSavepoint(String driverClass, String url, String user, String pwd) throws SQLException {
        setUp(driverClass, url, user, pwd);
        
        connection.createStatement().execute("INSERT INTO savepoint_test_table (id, name) VALUES (1, 'Alice')");
        Savepoint savepoint = connection.setSavepoint("sp1");

        connection.createStatement().execute("INSERT INTO savepoint_test_table (id, name) VALUES (2, 'Bob')");
        
        // Oracle does not support releaseSavepoint (ORA-17023: Unsupported feature).
        // The savepoint remains active after the failed release.
        assertThrows(SQLException.class, () -> connection.releaseSavepoint(savepoint));

        // Both rows remain because releaseSavepoint failed and no rollback occurred
        ResultSet resultSet = connection.createStatement().executeQuery("SELECT COUNT(*) AS cnt FROM savepoint_test_table");
        assertTrue(resultSet.next());
        assertEquals(2, resultSet.getInt("cnt")); // Should have both Alice and Bob
        resultSet.close();

        // Savepoint is still active after the failed release; rollback to it succeeds
        connection.rollback(savepoint);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testSavepointAfterCommit(String driverClass, String url, String user, String pwd) throws SQLException {
        setUp(driverClass, url, user, pwd);
        
        connection.createStatement().execute("INSERT INTO savepoint_test_table (id, name) VALUES (1, 'Alice')");
        Savepoint savepoint = connection.setSavepoint("sp1");

        connection.createStatement().execute("INSERT INTO savepoint_test_table (id, name) VALUES (2, 'Bob')");
        
        // Commit the transaction
        connection.commit();

        // After commit Oracle invalidates all savepoints (ORA-01086: savepoint never established
        // or is invalid). Rolling back to a post-commit savepoint must throw.
        assertThrows(SQLException.class, () -> connection.rollback(savepoint));

        // Data should still be there after commit
        ResultSet resultSet = connection.createStatement().executeQuery("SELECT COUNT(*) AS cnt FROM savepoint_test_table");
        assertTrue(resultSet.next());
        assertEquals(2, resultSet.getInt("cnt")); // Should have both Alice and Bob
        resultSet.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testSavepointMetadata(String driverClass, String url, String user, String pwd) throws SQLException {
        setUp(driverClass, url, user, pwd);
        
        // Test that Oracle supports savepoints
        assertTrue(connection.getMetaData().supportsSavepoints());
        
        // Create savepoints and verify their properties
        Savepoint unnamedSp = connection.setSavepoint();
        assertNotNull(unnamedSp);
        assertTrue(unnamedSp.getSavepointId() > 0);
        assertThrows(SQLException.class, () -> unnamedSp.getSavepointName());
        
        Savepoint namedSp = connection.setSavepoint("test_sp");
        assertNotNull(namedSp);
        assertEquals("test_sp", namedSp.getSavepointName());
        assertThrows(SQLException.class, () -> namedSp.getSavepointId());
        
        // Clean up - Oracle does not support releaseSavepoint (ORA-17023)
        assertThrows(SQLException.class, () -> connection.releaseSavepoint(unnamedSp));
        assertThrows(SQLException.class, () -> connection.releaseSavepoint(namedSp));
    }
}