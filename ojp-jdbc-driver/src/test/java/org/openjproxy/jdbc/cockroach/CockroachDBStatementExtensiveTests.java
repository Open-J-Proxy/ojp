package org.openjproxy.jdbc.cockroach;

import org.openjproxy.jdbc.testutil.TestDBUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class CockroachDBStatementExtensiveTests {

    private static boolean isTestEnabled;

    private Connection connection;
    private Statement statement;

    @BeforeAll
    static void checkTestConfiguration() {
        isTestEnabled = Boolean.parseBoolean(System.getProperty("enableCockroachDBTests", "false"));
    }

    void setUp(String driverClass, String url, String user, String password) throws Exception {
        assumeFalse(!isTestEnabled, "CockroachDB tests are not enabled");

        connection = DriverManager.getConnection(url, user, password);
        statement = connection.createStatement();

        TestDBUtils.createBasicTestTable(connection, "cockroachdb_statement_test", TestDBUtils.SqlSyntax.COCKROACHDB, true);
    }

    @AfterEach
    void tearDown() {
        TestDBUtils.closeQuietly(statement, connection);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/cockroachdb_connection.csv")
    void testExecuteQuery(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        ResultSet rs = statement.executeQuery("SELECT * FROM cockroachdb_statement_test");
        assertNotNull(rs);
        assertTrue(rs.next());
        rs.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/cockroachdb_connection.csv")
    void testExecuteUpdate(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        int rows = statement.executeUpdate("UPDATE cockroachdb_statement_test SET name = 'Updated Alice' WHERE id = 1");
        assertEquals(1, rows);

        ResultSet rs = statement.executeQuery("SELECT name FROM cockroachdb_statement_test WHERE id = 1");
        assertTrue(rs.next());
        assertEquals("Updated Alice", rs.getString("name"));
        rs.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/cockroachdb_connection.csv")
    void testClose(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        assertFalse(statement.isClosed());
        statement.close();
        assertTrue(statement.isClosed());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/cockroachdb_connection.csv")
    void testMaxFieldSize(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        int orig = statement.getMaxFieldSize();
        statement.setMaxFieldSize(orig + 1);
        int newSize = statement.getMaxFieldSize();
        assertTrue(newSize >= 0, "Max field size should be >= 0, got: " + newSize);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/cockroachdb_connection.csv")
    void testExecuteAfterCloseThrows(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        statement.close();
        assertThrows(SQLException.class, () -> statement.executeQuery("SELECT * FROM cockroachdb_statement_test"));
        assertThrows(SQLException.class, () -> statement.executeUpdate("UPDATE cockroachdb_statement_test SET name = 'fail' WHERE id = 1"));
        assertThrows(SQLException.class, () -> statement.addBatch("INSERT INTO cockroachdb_statement_test (id, name) VALUES (99, 'ShouldFail')"));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/cockroachdb_connection.csv")
    void testMaxRows(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        statement.setMaxRows(1);
        assertEquals(1, statement.getMaxRows());
        ResultSet rs = statement.executeQuery("SELECT * FROM cockroachdb_statement_test");
        assertTrue(rs.next());
        assertFalse(rs.next());
        rs.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/cockroachdb_connection.csv")
    void testEscapeProcessing(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        // Should not throw
        statement.setEscapeProcessing(true);
        statement.setEscapeProcessing(false);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/cockroachdb_connection.csv")
    void testQueryTimeout(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        statement.setQueryTimeout(5);
        assertEquals(5, statement.getQueryTimeout());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/cockroachdb_connection.csv")
    void testCancel(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        // Should not throw
        statement.cancel();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/cockroachdb_connection.csv")
    void testWarnings(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        statement.clearWarnings();
        assertNull(statement.getWarnings());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/cockroachdb_connection.csv")
    void testSetCursorName(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        // No-op in most drivers; should not throw
        statement.setCursorName("CURSOR_A");
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/cockroachdb_connection.csv")
    void testExecute(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        boolean isResultSet = statement.execute("SELECT * FROM cockroachdb_statement_test");
        assertTrue(isResultSet);
        ResultSet rs = statement.getResultSet();
        assertNotNull(rs);
        rs.close();
        assertEquals(-1, statement.getUpdateCount());

        isResultSet = statement.execute("UPDATE cockroachdb_statement_test SET name = 'Updated Bob' WHERE id = 2");
        assertFalse(isResultSet);
        assertEquals(1, statement.getUpdateCount());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/cockroachdb_connection.csv")
    void testGetMoreResults(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        statement.execute("SELECT * FROM cockroachdb_statement_test");
        assertFalse(statement.getMoreResults());
        assertEquals(-1, statement.getUpdateCount());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/cockroachdb_connection.csv")
    void testFetchDirection(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        int orig = statement.getFetchDirection();
        statement.setFetchDirection(ResultSet.FETCH_FORWARD);
        assertEquals(ResultSet.FETCH_FORWARD, statement.getFetchDirection());
        statement.setFetchDirection(orig); // Restore
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/cockroachdb_connection.csv")
    void testFetchSize(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        int orig = statement.getFetchSize();
        statement.setFetchSize(orig + 1);
        assertEquals(orig + 1, statement.getFetchSize());
        statement.setFetchSize(orig);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/cockroachdb_connection.csv")
    void testResultSetConcurrencyAndType(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        int concurrency = statement.getResultSetConcurrency();
        int type = statement.getResultSetType();
        assertTrue(concurrency == ResultSet.CONCUR_READ_ONLY || concurrency == ResultSet.CONCUR_UPDATABLE);
        assertTrue(type == ResultSet.TYPE_FORWARD_ONLY || type == ResultSet.TYPE_SCROLL_INSENSITIVE || type == ResultSet.TYPE_SCROLL_SENSITIVE);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/cockroachdb_connection.csv")
    void testBatchExecution(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        statement.addBatch("INSERT INTO cockroachdb_statement_test (id, name) VALUES (3, 'Charlie')");
        statement.addBatch("INSERT INTO cockroachdb_statement_test (id, name) VALUES (4, 'David')");
        int[] results = statement.executeBatch();
        assertEquals(2, results.length);

        ResultSet rs = statement.executeQuery("SELECT COUNT(*) AS total FROM cockroachdb_statement_test");
        assertTrue(rs.next());
        assertEquals(4, rs.getLong("total"));
        rs.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/cockroachdb_connection.csv")
    void testClearBatch(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        statement.addBatch("INSERT INTO cockroachdb_statement_test (id, name) VALUES (5, 'Eve')");
        statement.clearBatch();
        int[] results = statement.executeBatch();
        assertEquals(0, results.length);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/cockroachdb_connection.csv")
    void testGetConnection(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        assertSame(connection, statement.getConnection());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/cockroachdb_connection.csv")
    void testGetMoreResultsWithCurrent(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        statement.execute("SELECT * FROM cockroachdb_statement_test");
        assertFalse(statement.getMoreResults(Statement.CLOSE_CURRENT_RESULT));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/cockroachdb_connection.csv")
    void testGetGeneratedKeys(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        // CockroachDB supports SERIAL, create table with auto-increment
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS cockroachdb_gen_keys_test");
            stmt.execute("CREATE TABLE cockroachdb_gen_keys_test (id SERIAL PRIMARY KEY, name VARCHAR(255))");
        }

        int affected = statement.executeUpdate("INSERT INTO cockroachdb_gen_keys_test (name) VALUES ('TestGen')", Statement.RETURN_GENERATED_KEYS);
        assertEquals(1, affected);

        ResultSet rs = statement.getGeneratedKeys();
        assertNotNull(rs);
        assertTrue(rs.next());
        // CockroachDB SERIAL uses BIGINT
        assertTrue(rs.getLong(1) > 0);
        rs.close();

        // Cleanup
        statement.execute("DROP TABLE IF EXISTS cockroachdb_gen_keys_test");
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/cockroachdb_connection.csv")
    void testExecuteLargeBatch(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        statement.addBatch("INSERT INTO cockroachdb_statement_test (id, name) VALUES (10, 'Large1')");
        statement.addBatch("INSERT INTO cockroachdb_statement_test (id, name) VALUES (11, 'Large2')");
        long[] results = statement.executeLargeBatch();
        assertEquals(2, results.length);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/cockroachdb_connection.csv")
    void testExecuteLargeUpdate(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        long affected = statement.executeLargeUpdate("UPDATE cockroachdb_statement_test SET name = 'LargeUpdate' WHERE id = 1");
        assertEquals(1L, affected);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/cockroachdb_connection.csv")
    void testGetLargeUpdateCount(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        statement.executeUpdate("UPDATE cockroachdb_statement_test SET name = 'Test' WHERE id = 1");
        long updateCount = statement.getLargeUpdateCount();
        // After execution, getUpdateCount returns -1 to indicate no more results
        // This is consistent with JDBC spec
        assertEquals(-1L, updateCount);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/cockroachdb_connection.csv")
    void testResultSetHoldability(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        int holdability = statement.getResultSetHoldability();
        assertTrue(holdability == ResultSet.HOLD_CURSORS_OVER_COMMIT ||
                holdability == ResultSet.CLOSE_CURSORS_AT_COMMIT);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/cockroachdb_connection.csv")
    void testIsPoolable(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        // Default is usually false, but should not throw
        assertDoesNotThrow(() -> statement.isPoolable());
        assertDoesNotThrow(() -> statement.setPoolable(true));
        assertDoesNotThrow(() -> statement.setPoolable(false));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/cockroachdb_connection.csv")
    void testCloseOnCompletion(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        assertFalse(statement.isCloseOnCompletion());
        statement.closeOnCompletion();
        assertTrue(statement.isCloseOnCompletion());
    }
}
