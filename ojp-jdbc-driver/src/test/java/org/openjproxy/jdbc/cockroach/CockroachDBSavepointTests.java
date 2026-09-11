package org.openjproxy.jdbc.cockroach;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class CockroachDBSavepointTests {

    private static boolean isTestEnabled;
    private Connection connection;

    @BeforeAll
    static void checkTestConfiguration() {
        isTestEnabled = Boolean.parseBoolean(System.getProperty("enableCockroachDBTests", "false"));
    }

    @SneakyThrows
    void setUp(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(!isTestEnabled, "CockroachDB tests are not enabled");

        connection = DriverManager.getConnection(url, user, pwd);
        connection.setAutoCommit(true);
        Statement stmt = connection.createStatement();
        try {
            stmt.execute("DROP TABLE savepoint_test_table");
        } catch (SQLException e) {
            // Table might not exist
        }

        // CockroachDB-specific CREATE TABLE syntax
        stmt.execute(
                "CREATE TABLE savepoint_test_table (id INT PRIMARY KEY, name VARCHAR(255))"
        );
        stmt.close();

        connection.setAutoCommit(false);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) {
            connection.close();
        }
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/cockroachdb_connection.csv")
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
    @CsvFileSource(resources = "/cockroachdb_connection.csv")
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
    @CsvFileSource(resources = "/cockroachdb_connection.csv")
    void testMultipleSavepoints(String driverClass, String url, String user, String pwd) throws SQLException {
        setUp(driverClass, url, user, pwd);

        connection.createStatement().execute("INSERT INTO savepoint_test_table (id, name) VALUES (1, 'Alice')");
        Savepoint sp1 = connection.setSavepoint("sp1");

        connection.createStatement().execute("INSERT INTO savepoint_test_table (id, name) VALUES (2, 'Bob')");
        Savepoint sp2 = connection.setSavepoint("sp2");

        connection.createStatement().execute("INSERT INTO savepoint_test_table (id, name) VALUES (3, 'Charlie')");

        // Rollback to sp2 (removes Charlie, keeps Alice and Bob)
        connection.rollback(sp2);

        ResultSet resultSet = connection.createStatement().executeQuery("SELECT COUNT(*) AS cnt FROM savepoint_test_table");
        assertTrue(resultSet.next());
        assertEquals(2, resultSet.getInt("cnt"));
        resultSet.close();

        // Rollback to sp1 (removes Bob, keeps only Alice)
        connection.rollback(sp1);

        resultSet = connection.createStatement().executeQuery("SELECT COUNT(*) AS cnt FROM savepoint_test_table");
        assertTrue(resultSet.next());
        assertEquals(1, resultSet.getInt("cnt"));
        resultSet.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/cockroachdb_connection.csv")
    void testReleaseSavepoint(String driverClass, String url, String user, String pwd) throws SQLException {
        setUp(driverClass, url, user, pwd);

        connection.createStatement().execute("INSERT INTO savepoint_test_table (id, name) VALUES (1, 'Alice')");
        Savepoint savepoint = connection.setSavepoint("sp_release");

        connection.createStatement().execute("INSERT INTO savepoint_test_table (id, name) VALUES (2, 'Bob')");

        // Release the savepoint
        connection.releaseSavepoint(savepoint);

        // Commit the transaction
        connection.commit();

        ResultSet resultSet = connection.createStatement().executeQuery("SELECT COUNT(*) AS cnt FROM savepoint_test_table");
        assertTrue(resultSet.next());
        assertEquals(2, resultSet.getInt("cnt")); // Both rows should be committed
        resultSet.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/cockroachdb_connection.csv")
    void testSavepointWithRollback(String driverClass, String url, String user, String pwd) throws SQLException {
        setUp(driverClass, url, user, pwd);

        connection.createStatement().execute("INSERT INTO savepoint_test_table (id, name) VALUES (1, 'Alice')");
        connection.setSavepoint("sp1");

        connection.createStatement().execute("INSERT INTO savepoint_test_table (id, name) VALUES (2, 'Bob')");

        // Full rollback (ignores savepoint, rolls back everything)
        connection.rollback();

        ResultSet resultSet = connection.createStatement().executeQuery("SELECT COUNT(*) AS cnt FROM savepoint_test_table");
        assertTrue(resultSet.next());
        assertEquals(0, resultSet.getInt("cnt")); // All rows should be rolled back
        resultSet.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/cockroachdb_connection.csv")
    void testSavepointWithCommit(String driverClass, String url, String user, String pwd) throws SQLException {
        setUp(driverClass, url, user, pwd);

        connection.createStatement().execute("INSERT INTO savepoint_test_table (id, name) VALUES (1, 'Alice')");
        connection.setSavepoint("sp1");

        connection.createStatement().execute("INSERT INTO savepoint_test_table (id, name) VALUES (2, 'Bob')");

        // Commit makes all savepoints invalid
        connection.commit();

        ResultSet resultSet = connection.createStatement().executeQuery("SELECT COUNT(*) AS cnt FROM savepoint_test_table");
        assertTrue(resultSet.next());
        assertEquals(2, resultSet.getInt("cnt")); // Both rows should be committed
        resultSet.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/cockroachdb_connection.csv")
    void testNestedSavepoints(String driverClass, String url, String user, String pwd) throws SQLException {
        setUp(driverClass, url, user, pwd);

        connection.createStatement().execute("INSERT INTO savepoint_test_table (id, name) VALUES (1, 'Alice')");
        connection.setSavepoint("level1");

        connection.createStatement().execute("INSERT INTO savepoint_test_table (id, name) VALUES (2, 'Bob')");
        Savepoint sp2 = connection.setSavepoint("level2");

        connection.createStatement().execute("INSERT INTO savepoint_test_table (id, name) VALUES (3, 'Charlie')");
        connection.setSavepoint("level3");

        connection.createStatement().execute("INSERT INTO savepoint_test_table (id, name) VALUES (4, 'David')");

        // Rollback to level 2 (removes David and Charlie)
        connection.rollback(sp2);

        ResultSet resultSet = connection.createStatement().executeQuery("SELECT name FROM savepoint_test_table ORDER BY id");
        assertTrue(resultSet.next());
        assertEquals("Alice", resultSet.getString("name"));
        assertTrue(resultSet.next());
        assertEquals("Bob", resultSet.getString("name"));
        assertFalse(resultSet.next());
        resultSet.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/cockroachdb_connection.csv")
    void testSavepointAfterError(String driverClass, String url, String user, String pwd) throws SQLException {
        setUp(driverClass, url, user, pwd);

        connection.createStatement().execute("INSERT INTO savepoint_test_table (id, name) VALUES (1, 'Alice')");
        Savepoint sp1 = connection.setSavepoint("sp_error");

        try {
            // This should fail due to duplicate key
            connection.createStatement().execute("INSERT INTO savepoint_test_table (id, name) VALUES (1, 'Duplicate')");
            fail("Should have thrown SQLException");
        } catch (SQLException e) {
            // Expected - rollback to savepoint
            connection.rollback(sp1);
        }

        // Insert a different record
        connection.createStatement().execute("INSERT INTO savepoint_test_table (id, name) VALUES (2, 'Bob')");
        connection.commit();

        ResultSet resultSet = connection.createStatement().executeQuery("SELECT name FROM savepoint_test_table ORDER BY id");
        assertTrue(resultSet.next());
        assertEquals("Alice", resultSet.getString("name"));
        assertTrue(resultSet.next());
        assertEquals("Bob", resultSet.getString("name"));
        assertFalse(resultSet.next());
        resultSet.close();
    }
}
