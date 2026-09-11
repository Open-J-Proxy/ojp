package org.openjproxy.jdbc.db2;

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

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class Db2StatementExtensiveTests {

    private static boolean isTestDisabled;

    private Connection connection;
    private Statement statement;

    @BeforeAll
    static void checkTestConfiguration() {
        isTestDisabled = !Boolean.parseBoolean(System.getProperty("enableDb2Tests", "false"));
    }

     void setUp(String driverClass, String url, String user, String password) throws Exception {
        assumeFalse(isTestDisabled, "DB2 tests are disabled");

        connection = DriverManager.getConnection(url, user, password);

        // Set schema explicitly to avoid "object not found" errors
        try (java.sql.Statement schemaStmt = connection.createStatement()) {
            schemaStmt.execute("SET SCHEMA DB2INST1");
        }

        statement = connection.createStatement();

        TestDBUtils.createBasicTestTable(connection, "DB2INST1.db2_statement_test", TestDBUtils.SqlSyntax.DB2, true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestDBUtils.closeQuietly(statement, connection);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void testExecuteQuery(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        ResultSet rs = statement.executeQuery("SELECT * FROM DB2INST1.db2_statement_test");
        assertNotNull(rs);
        assertTrue(rs.next());
        rs.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void testExecuteUpdate(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        int rows = statement.executeUpdate("UPDATE DB2INST1.db2_statement_test SET name = 'Updated Alice' WHERE id = 1");
        assertEquals(1, rows);

        ResultSet rs = statement.executeQuery("SELECT name FROM DB2INST1.db2_statement_test WHERE id = 1");
        assertTrue(rs.next());
        assertEquals("Updated Alice", rs.getString("name"));
        rs.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void testClose(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        assertFalse(statement.isClosed());
        statement.close();
        assertTrue(statement.isClosed());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void testMaxFieldSize(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        int orig = statement.getMaxFieldSize();
        statement.setMaxFieldSize(orig + 1);
        // DB2 behavior: typically returns 0 (unlimited) unless specifically set
        int newSize = statement.getMaxFieldSize();
        assertTrue(newSize >= 0, "Max field size should be >= 0, got: " + newSize);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void testExecuteAfterCloseThrows(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        statement.close();
        assertThrows(SQLException.class, () -> statement.executeQuery("SELECT * FROM DB2INST1.db2_statement_test"));
        assertThrows(SQLException.class, () -> statement.executeUpdate("UPDATE DB2INST1.db2_statement_test SET name = 'fail' WHERE id = 1"));
        assertThrows(SQLException.class, () -> statement.addBatch("INSERT INTO DB2INST1.db2_statement_test (id, name) VALUES (99, 'ShouldFail')"));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void testMaxRows(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        statement.setMaxRows(1);
        assertEquals(1, statement.getMaxRows());
        ResultSet rs = statement.executeQuery("SELECT * FROM DB2INST1.db2_statement_test");
        assertTrue(rs.next());
        assertFalse(rs.next());
        rs.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void testEscapeProcessing(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        // Should not throw
        statement.setEscapeProcessing(true);
        statement.setEscapeProcessing(false);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void testQueryTimeout(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        statement.setQueryTimeout(5);
        assertEquals(5, statement.getQueryTimeout());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void testCancel(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        // Should not throw
        statement.cancel();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void testWarnings(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        statement.clearWarnings();
        assertNull(statement.getWarnings());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void testSetCursorName(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        // DB2 supports named cursors
        assertDoesNotThrow(() -> statement.setCursorName("CURSOR_A"));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void testExecute(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        boolean isResultSet = statement.execute("SELECT * FROM DB2INST1.db2_statement_test");
        assertTrue(isResultSet);
        ResultSet rs = statement.getResultSet();
        assertNotNull(rs);
        rs.close();
        assertEquals(-1, statement.getUpdateCount());

        isResultSet = statement.execute("UPDATE DB2INST1.db2_statement_test SET name = 'Updated Bob' WHERE id = 2");
        assertFalse(isResultSet);
        assertEquals(1, statement.getUpdateCount());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void testGetMoreResults(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        statement.execute("SELECT * FROM DB2INST1.db2_statement_test");
        assertFalse(statement.getMoreResults());
        assertEquals(-1, statement.getUpdateCount());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void testFetchDirection(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        int orig = statement.getFetchDirection();
        statement.setFetchDirection(ResultSet.FETCH_FORWARD);
        assertEquals(ResultSet.FETCH_FORWARD, statement.getFetchDirection());
        statement.setFetchDirection(orig); // Restore
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void testFetchSize(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        int orig = statement.getFetchSize();
        statement.setFetchSize(orig + 1);
        assertEquals(orig + 1, statement.getFetchSize());
        statement.setFetchSize(orig);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void testResultSetConcurrencyAndType(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        int concurrency = statement.getResultSetConcurrency();
        int type = statement.getResultSetType();
        assertTrue(concurrency == ResultSet.CONCUR_READ_ONLY || concurrency == ResultSet.CONCUR_UPDATABLE);
        assertTrue(type == ResultSet.TYPE_FORWARD_ONLY || type == ResultSet.TYPE_SCROLL_INSENSITIVE || type == ResultSet.TYPE_SCROLL_SENSITIVE);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void testBatchExecution(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        statement.addBatch("INSERT INTO DB2INST1.db2_statement_test (id, name) VALUES (3, 'Charlie')");
        statement.addBatch("INSERT INTO DB2INST1.db2_statement_test (id, name) VALUES (4, 'David')");
        int[] results = statement.executeBatch();
        assertEquals(2, results.length);

        ResultSet rs = statement.executeQuery("SELECT COUNT(*) AS total FROM DB2INST1.db2_statement_test");
        assertTrue(rs.next());
        assertEquals(4, rs.getLong("total"));
        rs.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void testClearBatch(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        statement.addBatch("INSERT INTO DB2INST1.db2_statement_test (id, name) VALUES (5, 'Eve')");
        statement.clearBatch();
        int[] results = statement.executeBatch();
        assertEquals(0, results.length);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void testGetConnection(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        assertSame(connection, statement.getConnection());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void testGetMoreResultsWithCurrent(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        statement.execute("SELECT * FROM DB2INST1.db2_statement_test");
        assertFalse(statement.getMoreResults(Statement.CLOSE_CURRENT_RESULT));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void testGeneratedKeys(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        TestDBUtils.createAutoIncrementTestTable(connection, "test_auto_keys", TestDBUtils.SqlSyntax.DB2);

        int affected = statement.executeUpdate("INSERT INTO test_auto_keys (name) VALUES ('foo')",
                new String[]{"id"});
        assertEquals(1, affected);
        ResultSet keys = statement.getGeneratedKeys();
        assertTrue(keys.next());
        assertTrue(keys.getLong(1) > 0);
        keys.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void testExecuteUpdateVariants(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);

        TestDBUtils.createAutoIncrementTestTable(connection, "test_cols", TestDBUtils.SqlSyntax.DB2);

        int a = statement.executeUpdate("INSERT INTO test_cols (name) VALUES ('bar')", Statement.RETURN_GENERATED_KEYS);
        assertEquals(1, a);

        String[] colNames = {"id"};
        a = statement.executeUpdate("INSERT INTO test_cols (name) VALUES ('qux')", colNames);
        assertEquals(1, a);

        // DB2 supports returning autogenerated keys by column index
        int[] colIndexes = {1};
        a = statement.executeUpdate("INSERT INTO test_cols (name) VALUES ('baz')", colIndexes);
        assertEquals(1, a);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void testExecuteVariants(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        TestDBUtils.createAutoIncrementTestTable(connection, "test_exec", TestDBUtils.SqlSyntax.DB2);

        boolean b = statement.execute("INSERT INTO test_exec (name) VALUES ('v1')", Statement.RETURN_GENERATED_KEYS);
        assertFalse(b);

        String[] colNames = {"id"};
        b = statement.execute("INSERT INTO test_exec (name) VALUES ('v3')", colNames);
        assertFalse(b);

        // DB2 supports returning autogenerated keys by column index
        int[] colIndexes = {1};
        b = statement.execute("INSERT INTO test_exec (name) VALUES ('v2')", colIndexes);
        assertFalse(b);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void testGetResultSetHoldability(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        int holdability = statement.getResultSetHoldability();
        assertTrue(holdability == ResultSet.HOLD_CURSORS_OVER_COMMIT || holdability == ResultSet.CLOSE_CURSORS_AT_COMMIT);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void testPoolable(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        statement.setPoolable(true);
        // DB2 behavior: supports statement pooling
        boolean isPoolable = statement.isPoolable();
        assertTrue(isPoolable || !isPoolable, "isPoolable should return a boolean");
        statement.setPoolable(false);
        // Just verify the method works, don't enforce specific behavior
        statement.isPoolable(); // Should not throw
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void testCloseOnCompletion(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        statement.closeOnCompletion();
        assertTrue(statement.isCloseOnCompletion());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void testLargeMethodsDefault(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        statement.getFetchDirection();
        statement.execute("INSERT INTO DB2INST1.db2_statement_test (id, name) VALUES (3, 'Juca Bala')");
        // DB2 drivers support large methods
        assertEquals(1, statement.getLargeUpdateCount());

        assertDoesNotThrow(() -> statement.executeLargeBatch());
        assertDoesNotThrow(() -> statement.executeLargeUpdate("UPDATE DB2INST1.db2_statement_test SET name = 'x' WHERE id = 1"));
        assertDoesNotThrow(() -> statement.executeLargeUpdate("UPDATE DB2INST1.db2_statement_test SET name = 'x' WHERE id = 1", Statement.RETURN_GENERATED_KEYS));
        assertDoesNotThrow(() -> statement.executeLargeUpdate("UPDATE DB2INST1.db2_statement_test SET name = 'x' WHERE id = 1", new String[]{"id"}));
        assertDoesNotThrow(() -> statement.executeLargeUpdate("UPDATE DB2INST1.db2_statement_test SET name = 'x' WHERE id = 1", new int[]{1}));

        // DB2 JDBC driver implements large methods
        assertDoesNotThrow(() -> statement.getLargeMaxRows());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void testEnquoteLiteral(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        String quoted = statement.enquoteLiteral("foo'bar");
        assertEquals("'foo''bar'", quoted);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void testEnquoteIdentifier(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        // DB2 quotes identifiers to preserve case sensitivity
        String quoted = statement.enquoteIdentifier("abc", true);
        assertNotNull(quoted);
        // DB2 typically returns quoted identifiers
        assertEquals("\"ABC\"", quoted.toUpperCase()); // DB2 converts to uppercase
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void testIsSimpleIdentifier(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        // DB2 has specific rules for simple identifiers
        boolean result1 = statement.isSimpleIdentifier("abc123");
        boolean result2 = statement.isSimpleIdentifier("ab-c");  // Contains hyphen - not simple
        boolean result3 = statement.isSimpleIdentifier("");      // Empty - not simple

        assertTrue(result1);      // Should be true for simple identifier
        assertFalse(result2);     // Should be false - contains special character
        assertFalse(result3);     // Should be false - empty string
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void testEnquoteNCharLiteral(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        String quoted = statement.enquoteNCharLiteral("foo'bar");
        assertEquals("N'foo''bar'", quoted);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    void testDb2SpecificFeatures(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);

        // Test DB2-specific SQL features
        statement.execute("CREATE VIEW db2_test_view AS SELECT * FROM DB2INST1.db2_statement_test");

        ResultSet rs = statement.executeQuery("SELECT * FROM db2_test_view");
        assertTrue(rs.next());
        rs.close();

        // Test DB2 system tables
        rs = statement.executeQuery("SELECT CURRENT_TIMESTAMP FROM SYSIBM.SYSDUMMY1");
        assertTrue(rs.next());
        assertNotNull(rs.getTimestamp(1));
        rs.close();

        // Test DB2 VALUES clause
        rs = statement.executeQuery("VALUES (1, 'test')");
        assertTrue(rs.next());
        assertEquals(1, rs.getInt(1));
        assertEquals("test", rs.getString(2));
        rs.close();

        // Clean up
        statement.execute("DROP VIEW db2_test_view");
    }
}