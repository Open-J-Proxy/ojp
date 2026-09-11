package org.openjproxy.jdbc.h2;

import org.openjproxy.jdbc.testutil.TestDBUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
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

public class H2StatementExtensiveTests {

    private static boolean isH2TestEnabled;

    private Connection connection;
    private Statement statement;

    @BeforeAll
    static void setupClass() {
        isH2TestEnabled = Boolean.parseBoolean(System.getProperty("enableH2Tests", "false"));
    }

    public void setUp(String driverClass, String url, String user, String password) throws Exception {
        Assumptions.assumeTrue(isH2TestEnabled, "Skipping H2 tests - not enabled");
        connection = DriverManager.getConnection(url, user, password);
        statement = connection.createStatement();
        TestDBUtils.createBasicTestTable(connection, "h2_statement_test", TestDBUtils.SqlSyntax.H2, true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestDBUtils.closeQuietly(statement, connection);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void testExecuteQuery(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        ResultSet rs = statement.executeQuery("SELECT * FROM h2_statement_test");
        assertNotNull(rs);
        assertTrue(rs.next());
        rs.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void testGetUpdateCountIsMinusOneAfterExecuteQuery(String driverClass, String url, String user, String password) throws Exception {
        // Regression test for PR #580: getUpdateCount() must return -1 (not 0) right after
        // executeQuery(), otherwise clients relying on getUpdateCount()==-1 to detect the end
        // of result processing (e.g. DataGrip) believe an update result is still pending.
        this.setUp(driverClass, url, user, password);
        ResultSet rs = statement.executeQuery("SELECT * FROM h2_statement_test");
        assertEquals(-1, statement.getUpdateCount());
        rs.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void testGetUpdateCountResetsToMinusOneWhenQueryFollowsAnUpdate(String driverClass, String url, String user, String password) throws Exception {
        // Regression test for PR #580: execute() on an UPDATE sets a valid (>=0) update count,
        // but a subsequent executeQuery() on the same Statement must reset it back to -1.
        this.setUp(driverClass, url, user, password);
        boolean isResultSet = statement.execute("UPDATE h2_statement_test SET name = 'Updated Alice' WHERE id = 1");
        assertFalse(isResultSet);
        assertEquals(1, statement.getUpdateCount());

        ResultSet rs = statement.executeQuery("SELECT name FROM h2_statement_test WHERE id = 1");
        assertEquals(-1, statement.getUpdateCount());
        rs.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void testExecuteUpdate(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        int rows = statement.executeUpdate("UPDATE h2_statement_test SET name = 'Updated Alice' WHERE id = 1");
        assertEquals(1, rows);

        ResultSet rs = statement.executeQuery("SELECT name FROM h2_statement_test WHERE id = 1");
        assertTrue(rs.next());
        assertEquals("Updated Alice", rs.getString("name"));
        rs.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void testClose(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        assertFalse(statement.isClosed());
        statement.close();
        assertTrue(statement.isClosed());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void testMaxFieldSize(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        int orig = statement.getMaxFieldSize();
        statement.setMaxFieldSize(orig + 1);
        assertEquals(0, statement.getMaxFieldSize());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void testExecuteAfterCloseThrows(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        statement.close();
        assertThrows(SQLException.class, () -> statement.executeQuery("SELECT * FROM h2_statement_test"));
        assertThrows(SQLException.class, () -> statement.executeUpdate("UPDATE h2_statement_test SET name = 'fail' WHERE id = 1"));
        assertThrows(SQLException.class, () -> statement.addBatch("INSERT INTO h2_statement_test (id, name) VALUES (99, 'ShouldFail')"));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void testMaxRows(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        statement.setMaxRows(1);
        assertEquals(1, statement.getMaxRows());
        ResultSet rs = statement.executeQuery("SELECT * FROM h2_statement_test");
        assertTrue(rs.next());
        assertFalse(rs.next());
        rs.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void testEscapeProcessing(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        // Should not throw
        statement.setEscapeProcessing(true);
        statement.setEscapeProcessing(false);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void testQueryTimeout(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        statement.setQueryTimeout(5);
        assertEquals(5, statement.getQueryTimeout());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void testCancel(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        // Should not throw
        statement.cancel();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void testWarnings(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        statement.clearWarnings();
        assertNull(statement.getWarnings());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void testSetCursorName(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        // No-op in most drivers; should not throw
        statement.setCursorName("CURSOR_A");
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void testExecute(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        boolean isResultSet = statement.execute("SELECT * FROM h2_statement_test");
        assertTrue(isResultSet);
        ResultSet rs = statement.getResultSet();
        assertNotNull(rs);
        rs.close();
        assertEquals(-1, statement.getUpdateCount());

        isResultSet = statement.execute("UPDATE h2_statement_test SET name = 'Updated Bob' WHERE id = 2");
        assertFalse(isResultSet);
        assertEquals(1, statement.getUpdateCount());
    }

    /**
     * Regression test for {@code SqlStatementClassifier}: a plain {@code startsWith("SELECT")}
     * check missed a CTE (WITH ...), which is common in tools like SQL IDEs. execute() must
     * route it to executeQuery so a ResultSet, not an update count, is produced.
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void testExecuteWithCommonTableExpression(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        boolean isResultSet = statement.execute(
                "WITH cte AS (SELECT * FROM h2_statement_test) SELECT * FROM cte");
        assertTrue(isResultSet);
        ResultSet rs = statement.getResultSet();
        assertNotNull(rs);
        assertTrue(rs.next());
        rs.close();
        assertEquals(-1, statement.getUpdateCount());
    }

    /**
     * Regression test for {@code SqlStatementClassifier}: a leading SQL comment before SELECT
     * must not prevent the statement from being recognized as query-shaped.
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void testExecuteWithLeadingCommentBeforeSelect(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        boolean isResultSet = statement.execute(
                "-- a leading comment\nSELECT * FROM h2_statement_test");
        assertTrue(isResultSet);
        ResultSet rs = statement.getResultSet();
        assertNotNull(rs);
        rs.close();
        assertEquals(-1, statement.getUpdateCount());
    }

    /**
     * Regression test for {@code SqlStatementClassifier}: H2's {@code VALUES} table constructor
     * is query-shaped even though it does not start with SELECT.
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void testExecuteWithValuesStatement(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        boolean isResultSet = statement.execute("VALUES (1), (2), (3)");
        assertTrue(isResultSet);
        ResultSet rs = statement.getResultSet();
        assertNotNull(rs);
        assertTrue(rs.next());
        rs.close();
        assertEquals(-1, statement.getUpdateCount());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void testGetMoreResults(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        statement.execute("SELECT * FROM h2_statement_test");
        assertFalse(statement.getMoreResults());
        assertEquals(-1, statement.getUpdateCount());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void testFetchDirection(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        int orig = statement.getFetchDirection();
        statement.setFetchDirection(ResultSet.FETCH_FORWARD);
        assertEquals(ResultSet.FETCH_FORWARD, statement.getFetchDirection());
        statement.setFetchDirection(orig); // Restore
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void testFetchSize(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        int orig = statement.getFetchSize();
        statement.setFetchSize(orig + 1);
        assertEquals(orig + 1, statement.getFetchSize());
        statement.setFetchSize(orig);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void testResultSetConcurrencyAndType(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        int concurrency = statement.getResultSetConcurrency();
        int type = statement.getResultSetType();
        assertTrue(concurrency == ResultSet.CONCUR_READ_ONLY || concurrency == ResultSet.CONCUR_UPDATABLE);
        assertTrue(type == ResultSet.TYPE_FORWARD_ONLY || type == ResultSet.TYPE_SCROLL_INSENSITIVE || type == ResultSet.TYPE_SCROLL_SENSITIVE);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void testBatchExecution(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        statement.addBatch("INSERT INTO h2_statement_test (id, name) VALUES (3, 'Charlie')");
        statement.addBatch("INSERT INTO h2_statement_test (id, name) VALUES (4, 'David')");
        int[] results = statement.executeBatch();
        assertEquals(2, results.length);

        ResultSet rs = statement.executeQuery("SELECT COUNT(*) AS total FROM h2_statement_test");
        assertTrue(rs.next());
        assertEquals(4, rs.getLong("total"));
        rs.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void testClearBatch(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        statement.addBatch("INSERT INTO h2_statement_test (id, name) VALUES (5, 'Eve')");
        statement.clearBatch();
        int[] results = statement.executeBatch();
        assertEquals(0, results.length);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void testGetConnection(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        assertSame(connection, statement.getConnection());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void testGetMoreResultsWithCurrent(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        statement.execute("SELECT * FROM h2_statement_test");
        assertFalse(statement.getMoreResults(Statement.CLOSE_CURRENT_RESULT));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void testGeneratedKeys(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        TestDBUtils.createAutoIncrementTestTable(connection, "test_auto_keys", TestDBUtils.SqlSyntax.H2);
        int affected = statement.executeUpdate("INSERT INTO test_auto_keys (name) VALUES ('foo')", Statement.RETURN_GENERATED_KEYS);
        assertEquals(1, affected);
        ResultSet keys = statement.getGeneratedKeys();
        assertTrue(keys.next());
        assertTrue(keys.getInt(1) > 0);
        keys.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void testExecuteUpdateVariants(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        TestDBUtils.createAutoIncrementTestTable(connection, "test_cols", TestDBUtils.SqlSyntax.H2);
        int a = statement.executeUpdate("INSERT INTO test_cols (name) VALUES ('bar')", Statement.RETURN_GENERATED_KEYS);
        assertEquals(1, a);

        int[] colIndexes = {1};
        a = statement.executeUpdate("INSERT INTO test_cols (name) VALUES ('baz')", colIndexes);
        assertEquals(1, a);

        String[] colNames = {"id"};
        a = statement.executeUpdate("INSERT INTO test_cols (name) VALUES ('qux')", colNames);
        assertEquals(1, a);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void testExecuteVariants(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        TestDBUtils.createAutoIncrementTestTable(connection, "test_exec", TestDBUtils.SqlSyntax.H2);
        boolean b = statement.execute("INSERT INTO test_exec (name) VALUES ('v1')", Statement.RETURN_GENERATED_KEYS);
        assertFalse(b);

        int[] colIndexes = {1};
        b = statement.execute("INSERT INTO test_exec (name) VALUES ('v2')", colIndexes);
        assertFalse(b);

        String[] colNames = {"id"};
        b = statement.execute("INSERT INTO test_exec (name) VALUES ('v3')", colNames);
        assertFalse(b);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void testGetResultSetHoldability(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        int holdability = statement.getResultSetHoldability();
        assertTrue(holdability == ResultSet.HOLD_CURSORS_OVER_COMMIT || holdability == ResultSet.CLOSE_CURSORS_AT_COMMIT);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void testPoolable(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        statement.setPoolable(true);
        assertFalse(statement.isPoolable());
        statement.setPoolable(false);
        assertFalse(statement.isPoolable());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void testCloseOnCompletion(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        statement.closeOnCompletion();
        assertTrue(statement.isCloseOnCompletion());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void testLargeMethodsDefault(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        this.statement.getFetchDirection();
        statement.execute("INSERT INTO h2_statement_test (id, name) VALUES (3, 'Juca Bala')");
        // Most drivers will throw or return default for these methods
        assertEquals(1, statement.getLargeUpdateCount());
        assertDoesNotThrow(() -> statement.setLargeMaxRows(10L));
        assertEquals(10L, statement.getLargeMaxRows());
        assertDoesNotThrow(() -> statement.executeLargeBatch());
        assertDoesNotThrow(() -> statement.executeLargeUpdate("UPDATE h2_statement_test SET name = 'x' WHERE id = 1"));
        assertDoesNotThrow(() -> statement.executeLargeUpdate("UPDATE h2_statement_test SET name = 'x' WHERE id = 1", Statement.RETURN_GENERATED_KEYS));
        assertDoesNotThrow(() -> statement.executeLargeUpdate("UPDATE h2_statement_test SET name = 'x' WHERE id = 1", new int[]{1}));
        assertDoesNotThrow(() -> statement.executeLargeUpdate("UPDATE h2_statement_test SET name = 'x' WHERE id = 1", new String[]{"id"}));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void testEnquoteLiteral(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        String quoted = statement.enquoteLiteral("foo'bar");
        assertEquals("'foo''bar'", quoted);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void testEnquoteIdentifier(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        assertEquals("\"abc\"", statement.enquoteIdentifier("abc", false));
        assertEquals("\"abc\"", statement.enquoteIdentifier("abc", true));
        assertEquals("\"ab-c\"", statement.enquoteIdentifier("ab-c", false));
        assertEquals("\"\"", statement.enquoteIdentifier("", false));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void testIsSimpleIdentifier(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        assertFalse(statement.isSimpleIdentifier("abc123"));
        assertFalse(statement.isSimpleIdentifier("ab-c"));
        assertFalse(statement.isSimpleIdentifier(""));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void testEnquoteNCharLiteral(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        String quoted = statement.enquoteNCharLiteral("foo'bar");
        assertEquals("N'foo''bar'", quoted);
    }
}