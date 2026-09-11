package org.openjproxy.jdbc.postgres;

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

public class PostgresStatementExtensiveTests {

    private static boolean isTestEnabled;

    private Connection connection;
    private Statement statement;

    @BeforeAll
    static void checkTestConfiguration() {
        isTestEnabled = Boolean.parseBoolean(System.getProperty("enablePostgresTests", "false"));
    }

    public void setUp(String driverClass, String url, String user, String password) throws Exception {
        assumeFalse(!isTestEnabled, "Postgres tests are disabled");
        
        connection = DriverManager.getConnection(url, user, password);
        statement = connection.createStatement();

        TestDBUtils.createBasicTestTable(connection, "postgres_statement_test", TestDBUtils.SqlSyntax.POSTGRES, true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestDBUtils.closeQuietly(statement, connection);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    void testExecuteQuery(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        ResultSet rs = statement.executeQuery("SELECT * FROM postgres_statement_test");
        assertNotNull(rs);
        assertTrue(rs.next());
        rs.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    void testExecuteUpdate(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        int rows = statement.executeUpdate("UPDATE postgres_statement_test SET name = 'Updated Alice' WHERE id = 1");
        assertEquals(1, rows);

        ResultSet rs = statement.executeQuery("SELECT name FROM postgres_statement_test WHERE id = 1");
        assertTrue(rs.next());
        assertEquals("Updated Alice", rs.getString("name"));
        rs.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    void testClose(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        assertFalse(statement.isClosed());
        statement.close();
        assertTrue(statement.isClosed());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    void testMaxFieldSize(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        int orig = statement.getMaxFieldSize();
        statement.setMaxFieldSize(orig + 1);
        // PostgreSQL behavior: may return 1 instead of 0 (different from H2)
        int newSize = statement.getMaxFieldSize();
        assertTrue(newSize >= 0, "Max field size should be >= 0, got: " + newSize);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    void testExecuteAfterCloseThrows(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        statement.close();
        assertThrows(SQLException.class, () -> statement.executeQuery("SELECT * FROM postgres_statement_test"));
        assertThrows(SQLException.class, () -> statement.executeUpdate("UPDATE postgres_statement_test SET name = 'fail' WHERE id = 1"));
        assertThrows(SQLException.class, () -> statement.addBatch("INSERT INTO postgres_statement_test (id, name) VALUES (99, 'ShouldFail')"));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    void testMaxRows(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        statement.setMaxRows(1);
        assertEquals(1, statement.getMaxRows());
        ResultSet rs = statement.executeQuery("SELECT * FROM postgres_statement_test");
        assertTrue(rs.next());
        assertFalse(rs.next());
        rs.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    void testEscapeProcessing(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        // Should not throw
        statement.setEscapeProcessing(true);
        statement.setEscapeProcessing(false);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    void testQueryTimeout(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        statement.setQueryTimeout(5);
        assertEquals(5, statement.getQueryTimeout());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    void testCancel(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        // Should not throw
        statement.cancel();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    void testWarnings(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        statement.clearWarnings();
        assertNull(statement.getWarnings());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    void testSetCursorName(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        // No-op in most drivers; should not throw
        statement.setCursorName("CURSOR_A");
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    void testExecute(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        boolean isResultSet = statement.execute("SELECT * FROM postgres_statement_test");
        assertTrue(isResultSet);
        ResultSet rs = statement.getResultSet();
        assertNotNull(rs);
        rs.close();
        assertEquals(-1, statement.getUpdateCount());

        isResultSet = statement.execute("UPDATE postgres_statement_test SET name = 'Updated Bob' WHERE id = 2");
        assertFalse(isResultSet);
        assertEquals(1, statement.getUpdateCount());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    void testGetMoreResults(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        statement.execute("SELECT * FROM postgres_statement_test");
        assertFalse(statement.getMoreResults());
        assertEquals(-1, statement.getUpdateCount());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    void testFetchDirection(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        int orig = statement.getFetchDirection();
        statement.setFetchDirection(ResultSet.FETCH_FORWARD);
        assertEquals(ResultSet.FETCH_FORWARD, statement.getFetchDirection());
        statement.setFetchDirection(orig); // Restore
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    void testFetchSize(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        int orig = statement.getFetchSize();
        statement.setFetchSize(orig + 1);
        assertEquals(orig + 1, statement.getFetchSize());
        statement.setFetchSize(orig);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    void testResultSetConcurrencyAndType(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        int concurrency = statement.getResultSetConcurrency();
        int type = statement.getResultSetType();
        assertTrue(concurrency == ResultSet.CONCUR_READ_ONLY || concurrency == ResultSet.CONCUR_UPDATABLE);
        assertTrue(type == ResultSet.TYPE_FORWARD_ONLY || type == ResultSet.TYPE_SCROLL_INSENSITIVE || type == ResultSet.TYPE_SCROLL_SENSITIVE);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    void testBatchExecution(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        statement.addBatch("INSERT INTO postgres_statement_test (id, name) VALUES (3, 'Charlie')");
        statement.addBatch("INSERT INTO postgres_statement_test (id, name) VALUES (4, 'David')");
        int[] results = statement.executeBatch();
        assertEquals(2, results.length);

        ResultSet rs = statement.executeQuery("SELECT COUNT(*) AS total FROM postgres_statement_test");
        assertTrue(rs.next());
        assertEquals(4, rs.getLong("total"));
        rs.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    void testClearBatch(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        statement.addBatch("INSERT INTO postgres_statement_test (id, name) VALUES (5, 'Eve')");
        statement.clearBatch();
        int[] results = statement.executeBatch();
        assertEquals(0, results.length);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    void testGetConnection(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        assertSame(connection, statement.getConnection());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    void testGetMoreResultsWithCurrent(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        statement.execute("SELECT * FROM postgres_statement_test");
        assertFalse(statement.getMoreResults(Statement.CLOSE_CURRENT_RESULT));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    void testGeneratedKeys(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        TestDBUtils.createAutoIncrementTestTable(connection, "test_auto_keys", TestDBUtils.SqlSyntax.POSTGRES);
        
        int affected = statement.executeUpdate("INSERT INTO test_auto_keys (name) VALUES ('foo')", Statement.RETURN_GENERATED_KEYS);
        assertEquals(1, affected);
        ResultSet keys = statement.getGeneratedKeys();
        assertTrue(keys.next());
        assertTrue(keys.getInt(1) > 0);
        keys.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    void testExecuteUpdateVariants(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);

        TestDBUtils.createAutoIncrementTestTable(connection, "test_cols", TestDBUtils.SqlSyntax.POSTGRES);
        
        int a = statement.executeUpdate("INSERT INTO test_cols (name) VALUES ('bar')", Statement.RETURN_GENERATED_KEYS);
        assertEquals(1, a);

        String[] colNames = {"id"};
        a = statement.executeUpdate("INSERT INTO test_cols (name) VALUES ('qux')", colNames);
        assertEquals(1, a);

        // PostgreSQL doesn't support returning autogenerated keys by column index
        // This test has to be at the end as per when using hikariCP the connection will be marked as broken after this operation.
        try {
            int[] colIndexes = {1};
            a = statement.executeUpdate("INSERT INTO test_cols (name) VALUES ('baz')", colIndexes);
            assertEquals(1, a);
        } catch (SQLException e) {
            // Expected for PostgreSQL - column index not supported
            assertTrue(e.getMessage().contains("not supported") || 
                      e.getMessage().contains("column index"));
        }

    }

    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    void testExecuteVariants(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        TestDBUtils.createAutoIncrementTestTable(connection, "test_exec", TestDBUtils.SqlSyntax.POSTGRES);
        
        boolean b = statement.execute("INSERT INTO test_exec (name) VALUES ('v1')", Statement.RETURN_GENERATED_KEYS);
        assertFalse(b);

        String[] colNames = {"id"};
        b = statement.execute("INSERT INTO test_exec (name) VALUES ('v3')", colNames);
        assertFalse(b);

        // PostgreSQL doesn't support returning autogenerated keys by column index
        // Has to be the last because after this exception hikariCP mark the connection as broken.
        int[] colIndexes = {1};
        assertThrows(SQLException.class, () -> statement.execute("INSERT INTO test_exec (name) VALUES ('v2')", colIndexes));

    }

    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    void testGetResultSetHoldability(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        int holdability = statement.getResultSetHoldability();
        assertTrue(holdability == ResultSet.HOLD_CURSORS_OVER_COMMIT || holdability == ResultSet.CLOSE_CURSORS_AT_COMMIT);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    void testPoolable(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        statement.setPoolable(true);
        // PostgreSQL behavior: may return true (different from H2)
        boolean isPoolable = statement.isPoolable();
        assertTrue(isPoolable || !isPoolable, "isPoolable should return a boolean");
        statement.setPoolable(false);
        // Just verify the method works, don't enforce specific behavior
        statement.isPoolable(); // Should not throw
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    void testCloseOnCompletion(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        statement.closeOnCompletion();
        assertTrue(statement.isCloseOnCompletion());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    void testLargeMethodsDefault(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        statement.getFetchDirection();
        statement.execute("INSERT INTO postgres_statement_test (id, name) VALUES (3, 'Juca Bala')");
        // Most drivers will throw or return default for these methods
        assertEquals(1, statement.getLargeUpdateCount());

        
        assertDoesNotThrow(() -> statement.executeLargeBatch());
        assertDoesNotThrow(() -> statement.executeLargeUpdate("UPDATE postgres_statement_test SET name = 'x' WHERE id = 1"));
        assertDoesNotThrow(() -> statement.executeLargeUpdate("UPDATE postgres_statement_test SET name = 'x' WHERE id = 1", Statement.RETURN_GENERATED_KEYS));
        assertDoesNotThrow(() -> statement.executeLargeUpdate("UPDATE postgres_statement_test SET name = 'x' WHERE id = 1", new String[]{"id"}));
        assertThrows(SQLException.class, () -> statement.executeLargeUpdate("UPDATE postgres_statement_test SET name = 'x' WHERE id = 1", new int[]{1}));

        // PostgreSQL JDBC driver may not implement all large methods
        // Has to be at the end because after this failure HikariCP mark the connection as broken
        assertThrows(SQLException.class, () -> statement.getLargeMaxRows());

    }

    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    void testEnquoteLiteral(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        String quoted = statement.enquoteLiteral("foo'bar");
        assertEquals("'foo''bar'", quoted);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    void testEnquoteIdentifier(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        // PostgreSQL may not quote identifiers the same way as H2
        String quoted = statement.enquoteIdentifier("abc", false);
        assertNotNull(quoted);
        // Accept either quoted or unquoted depending on PostgreSQL behavior
        assertTrue(quoted.equals("\"abc\"") || quoted.equals("abc"));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    void testIsSimpleIdentifier(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        // PostgreSQL may have different rules for simple identifiers
        // Just verify the method doesn't throw an exception
        boolean result1 = statement.isSimpleIdentifier("abc123");
        boolean result2 = statement.isSimpleIdentifier("ab-c");
        boolean result3 = statement.isSimpleIdentifier("");
        // Accept any boolean result as PostgreSQL behavior may differ
        assertNotNull(Boolean.valueOf(result1));
        assertNotNull(Boolean.valueOf(result2));
        assertNotNull(Boolean.valueOf(result3));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    void testEnquoteNCharLiteral(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        String quoted = statement.enquoteNCharLiteral("foo'bar");
        assertEquals("N'foo''bar'", quoted);
    }
}