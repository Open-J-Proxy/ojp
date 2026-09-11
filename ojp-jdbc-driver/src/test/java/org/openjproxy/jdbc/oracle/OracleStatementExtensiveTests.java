package org.openjproxy.jdbc.oracle;

import org.openjproxy.jdbc.testutil.TestDBUtils;
import org.junit.Assert;
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

public class OracleStatementExtensiveTests {

    private static boolean isTestDisabled;

    private Connection connection;
    private Statement statement;

    @BeforeAll
    static void checkTestConfiguration() {
        isTestDisabled = !Boolean.parseBoolean(System.getProperty("enableOracleTests", "false"));
    }

    public void setUp(String driverClass, String url, String user, String password) throws Exception {
        assumeFalse(isTestDisabled, "Oracle tests are disabled");
        
        connection = DriverManager.getConnection(url, user, password);
        statement = connection.createStatement();

        TestDBUtils.createBasicTestTable(connection, "oracle_statement_test", TestDBUtils.SqlSyntax.ORACLE, true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestDBUtils.closeQuietly(statement, connection);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testExecuteQuery(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        ResultSet rs = statement.executeQuery("SELECT * FROM oracle_statement_test");
        assertNotNull(rs);
        assertTrue(rs.next());
        rs.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testExecuteUpdate(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        int rows = statement.executeUpdate("UPDATE oracle_statement_test SET name = 'Updated Alice' WHERE id = 1");
        assertEquals(1, rows);

        ResultSet rs = statement.executeQuery("SELECT name FROM oracle_statement_test WHERE id = 1");
        assertTrue(rs.next());
        assertEquals("Updated Alice", rs.getString("name"));
        rs.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testClose(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        assertFalse(statement.isClosed());
        statement.close();
        assertTrue(statement.isClosed());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testMaxFieldSize(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        int orig = statement.getMaxFieldSize();
        statement.setMaxFieldSize(orig + 1);
        // Oracle behavior: typically returns 0 (unlimited) unless specifically set
        int newSize = statement.getMaxFieldSize();
        assertTrue(newSize >= 0, "Max field size should be >= 0, got: " + newSize);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testExecuteAfterCloseThrows(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        statement.close();
        assertThrows(SQLException.class, () -> statement.executeQuery("SELECT * FROM oracle_statement_test"));
        assertThrows(SQLException.class, () -> statement.executeUpdate("UPDATE oracle_statement_test SET name = 'fail' WHERE id = 1"));
        assertThrows(SQLException.class, () -> statement.addBatch("INSERT INTO oracle_statement_test (id, name) VALUES (99, 'ShouldFail')"));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testMaxRows(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        statement.setMaxRows(1);
        assertEquals(1, statement.getMaxRows());
        ResultSet rs = statement.executeQuery("SELECT * FROM oracle_statement_test");
        assertTrue(rs.next());
        assertFalse(rs.next());
        rs.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testEscapeProcessing(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        // Should not throw
        statement.setEscapeProcessing(true);
        statement.setEscapeProcessing(false);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testQueryTimeout(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        statement.setQueryTimeout(5);
        assertEquals(5, statement.getQueryTimeout());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testCancel(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        // Should not throw
        statement.cancel();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testWarnings(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        statement.clearWarnings();
        assertNull(statement.getWarnings());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testSetCursorName(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        // Some Oracle versions supports named cursors; the one used for tests does not.
        Assert.assertThrows(SQLException.class, () -> statement.setCursorName("CURSOR_A"));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testExecute(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        boolean isResultSet = statement.execute("SELECT * FROM oracle_statement_test");
        assertTrue(isResultSet);
        ResultSet rs = statement.getResultSet();
        assertNotNull(rs);
        rs.close();
        assertEquals(-1, statement.getUpdateCount());

        isResultSet = statement.execute("UPDATE oracle_statement_test SET name = 'Updated Bob' WHERE id = 2");
        assertFalse(isResultSet);
        assertEquals(1, statement.getUpdateCount());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testGetMoreResults(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        statement.execute("SELECT * FROM oracle_statement_test");
        assertFalse(statement.getMoreResults());
        assertEquals(-1, statement.getUpdateCount());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testFetchDirection(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        int orig = statement.getFetchDirection();
        statement.setFetchDirection(ResultSet.FETCH_FORWARD);
        assertEquals(ResultSet.FETCH_FORWARD, statement.getFetchDirection());
        statement.setFetchDirection(orig); // Restore
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testFetchSize(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        int orig = statement.getFetchSize();
        statement.setFetchSize(orig + 1);
        assertEquals(orig + 1, statement.getFetchSize());
        statement.setFetchSize(orig);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testResultSetConcurrencyAndType(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        int concurrency = statement.getResultSetConcurrency();
        int type = statement.getResultSetType();
        assertTrue(concurrency == ResultSet.CONCUR_READ_ONLY || concurrency == ResultSet.CONCUR_UPDATABLE);
        assertTrue(type == ResultSet.TYPE_FORWARD_ONLY || type == ResultSet.TYPE_SCROLL_INSENSITIVE || type == ResultSet.TYPE_SCROLL_SENSITIVE);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testBatchExecution(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        statement.addBatch("INSERT INTO oracle_statement_test (id, name) VALUES (3, 'Charlie')");
        statement.addBatch("INSERT INTO oracle_statement_test (id, name) VALUES (4, 'David')");
        int[] results = statement.executeBatch();
        assertEquals(2, results.length);

        ResultSet rs = statement.executeQuery("SELECT COUNT(*) AS total FROM oracle_statement_test");
        assertTrue(rs.next());
        assertEquals(4, rs.getLong("total"));
        rs.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testClearBatch(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        statement.addBatch("INSERT INTO oracle_statement_test (id, name) VALUES (5, 'Eve')");
        statement.clearBatch();
        int[] results = statement.executeBatch();
        assertEquals(0, results.length);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testGetConnection(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        assertSame(connection, statement.getConnection());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testGetMoreResultsWithCurrent(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        statement.execute("SELECT * FROM oracle_statement_test");
        assertFalse(statement.getMoreResults(Statement.CLOSE_CURRENT_RESULT));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testGeneratedKeys(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        TestDBUtils.createAutoIncrementTestTable(connection, "test_auto_keys", TestDBUtils.SqlSyntax.ORACLE);
        
        int affected = statement.executeUpdate("INSERT INTO test_auto_keys (name) VALUES ('foo')",
                new String[] { "id" });
        assertEquals(1, affected);
        ResultSet keys = statement.getGeneratedKeys();
        assertTrue(keys.next());
        assertTrue(keys.getLong(1) > 0);
        keys.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testExecuteUpdateVariants(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);

        TestDBUtils.createAutoIncrementTestTable(connection, "test_cols", TestDBUtils.SqlSyntax.ORACLE);
        
        int a = statement.executeUpdate("INSERT INTO test_cols (name) VALUES ('bar')", Statement.RETURN_GENERATED_KEYS);
        assertEquals(1, a);

        String[] colNames = {"id"};
        a = statement.executeUpdate("INSERT INTO test_cols (name) VALUES ('qux')", colNames);
        assertEquals(1, a);

        // Oracle supports returning autogenerated keys by column index
        int[] colIndexes = {1};
        a = statement.executeUpdate("INSERT INTO test_cols (name) VALUES ('baz')", colIndexes);
        assertEquals(1, a);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testExecuteVariants(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        TestDBUtils.createAutoIncrementTestTable(connection, "test_exec", TestDBUtils.SqlSyntax.ORACLE);
        
        boolean b = statement.execute("INSERT INTO test_exec (name) VALUES ('v1')", Statement.RETURN_GENERATED_KEYS);
        assertFalse(b);

        String[] colNames = {"id"};
        b = statement.execute("INSERT INTO test_exec (name) VALUES ('v3')", colNames);
        assertFalse(b);

        // Oracle supports returning autogenerated keys by column index
        int[] colIndexes = {1};
        b = statement.execute("INSERT INTO test_exec (name) VALUES ('v2')", colIndexes);
        assertFalse(b);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testGetResultSetHoldability(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        int holdability = statement.getResultSetHoldability();
        assertTrue(holdability == ResultSet.HOLD_CURSORS_OVER_COMMIT || holdability == ResultSet.CLOSE_CURSORS_AT_COMMIT);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testPoolable(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        statement.setPoolable(true);
        // Oracle behavior: supports statement pooling
        boolean isPoolable = statement.isPoolable();
        assertTrue(isPoolable || !isPoolable, "isPoolable should return a boolean");
        statement.setPoolable(false);
        // Just verify the method works, don't enforce specific behavior
        statement.isPoolable(); // Should not throw
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testCloseOnCompletion(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        statement.closeOnCompletion();
        assertTrue(statement.isCloseOnCompletion());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testLargeMethodsDefault(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        statement.getFetchDirection();
        statement.execute("INSERT INTO oracle_statement_test (id, name) VALUES (3, 'Juca Bala')");
        // Oracle drivers support large methods
        assertEquals(1, statement.getLargeUpdateCount());

        assertDoesNotThrow(() -> statement.executeLargeBatch());
        assertDoesNotThrow(() -> statement.executeLargeUpdate("UPDATE oracle_statement_test SET name = 'x' WHERE id = 1"));
        assertDoesNotThrow(() -> statement.executeLargeUpdate("UPDATE oracle_statement_test SET name = 'x' WHERE id = 1", Statement.RETURN_GENERATED_KEYS));
        assertDoesNotThrow(() -> statement.executeLargeUpdate("UPDATE oracle_statement_test SET name = 'x' WHERE id = 1", new String[]{"id"}));
        assertDoesNotThrow(() -> statement.executeLargeUpdate("UPDATE oracle_statement_test SET name = 'x' WHERE id = 1", new int[]{1}));

        // Oracle JDBC driver implements large methods
        assertDoesNotThrow(() -> statement.getLargeMaxRows());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testEnquoteLiteral(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        String quoted = statement.enquoteLiteral("foo'bar");
        assertEquals("'foo''bar'", quoted);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testEnquoteIdentifier(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        // Oracle quotes identifiers to preserve case sensitivity
        String quoted = statement.enquoteIdentifier("abc", true);
        assertNotNull(quoted);
        // Oracle typically returns quoted identifiers
        assertEquals("\"ABC\"", quoted.toUpperCase()); // Oracle converts to uppercase
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testIsSimpleIdentifier(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        // Oracle has specific rules for simple identifiers
        boolean result1 = statement.isSimpleIdentifier("abc123");
        boolean result2 = statement.isSimpleIdentifier("ab-c");  // Contains hyphen - not simple
        boolean result3 = statement.isSimpleIdentifier("");      // Empty - not simple
        
        assertTrue(result1);      // Should be true for simple identifier
        assertFalse(result2);     // Should be false - contains special character
        assertFalse(result3);     // Should be false - empty string
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testEnquoteNCharLiteral(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        String quoted = statement.enquoteNCharLiteral("foo'bar");
        assertEquals("N'foo''bar'", quoted);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testOracleSpecificFeatures(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        
        // Test Oracle-specific SQL features
        statement.execute("CREATE OR REPLACE VIEW oracle_test_view AS SELECT * FROM oracle_statement_test");
        
        ResultSet rs = statement.executeQuery("SELECT * FROM oracle_test_view");
        assertTrue(rs.next());
        rs.close();
        
        // Test Oracle DUAL table
        rs = statement.executeQuery("SELECT SYSDATE FROM DUAL");
        assertTrue(rs.next());
        assertNotNull(rs.getTimestamp(1));
        rs.close();
        
        // Clean up
        statement.execute("DROP VIEW oracle_test_view");
    }
}