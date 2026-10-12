package org.openjproxy.jdbc.mySql;

import org.openjproxy.jdbc.testutil.MySQLCompatibleDatabase;
import org.openjproxy.jdbc.testutil.TestDBUtils;
import org.junit.Assert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

public abstract class MySQLCompatibleStatementExtensiveTests {

    private Connection connection;
    private Statement statement;

    protected abstract MySQLCompatibleDatabase database();

    public void setUp(String driverClass, String url, String user, String password) throws Exception {
        database().assumeEnabledAndVerifyServerDriver(url, user, password);

        connection = DriverManager.getConnection(url, user, password);
        statement = connection.createStatement();
        TestDBUtils.createBasicTestTable(connection, "mysql_statement_test", TestDBUtils.SqlSyntax.MYSQL, true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestDBUtils.closeQuietly(statement, connection);
    }

    @ParameterizedTest
    @MethodSource("connectionDetails")
    void testExecuteQuery(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        ResultSet rs = statement.executeQuery("SELECT * FROM mysql_statement_test");
        Assertions.assertNotNull(rs);
        assertTrue(rs.next());
        rs.close();
    }

    @ParameterizedTest
    @MethodSource("connectionDetails")
    void testExecuteUpdate(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        int rows = statement.executeUpdate("UPDATE mysql_statement_test SET name = 'Updated Alice' WHERE id = 1");
        assertEquals(1, rows);

        ResultSet rs = statement.executeQuery("SELECT name FROM mysql_statement_test WHERE id = 1");
        assertTrue(rs.next());
        assertEquals("Updated Alice", rs.getString("name"));
        rs.close();
    }

    @ParameterizedTest
    @MethodSource("connectionDetails")
    void testClose(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        assertFalse(statement.isClosed());
        statement.close();
        assertTrue(statement.isClosed());
    }

    @ParameterizedTest
    @MethodSource("connectionDetails")
    void testMaxFieldSize(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        int orig = statement.getMaxFieldSize();
        if (url.toLowerCase().contains("mysql"))
            assertTrue(orig > 0);
        else
            Assertions.assertEquals(0, orig);
        statement.setMaxFieldSize(5);
        if (url.toLowerCase().contains("mysql"))
            assertEquals(5, statement.getMaxFieldSize());
        else
            assertEquals(0, statement.getMaxFieldSize());
    }

    @ParameterizedTest
    @MethodSource("connectionDetails")
    void testMaxRows(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        assertEquals(0, statement.getMaxRows());
        statement.setMaxRows(10);
        assertEquals(10, statement.getMaxRows());
        statement.setMaxRows(0);
        assertEquals(0, statement.getMaxRows());
    }

    @ParameterizedTest
    @MethodSource("connectionDetails")
    void testQueryTimeout(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        assertEquals(0, statement.getQueryTimeout());
        statement.setQueryTimeout(30);
        assertEquals(30, statement.getQueryTimeout());
        statement.setQueryTimeout(0);
        assertEquals(0, statement.getQueryTimeout());
    }

    @ParameterizedTest
    @MethodSource("connectionDetails")
    void testWarnings(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        // Initial warnings might be null
        statement.getWarnings();
        statement.clearWarnings();
        Assertions.assertNull(statement.getWarnings());
    }

    @ParameterizedTest
    @MethodSource("connectionDetails")
    void testExecute(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        boolean hasResultSet = statement.execute("SELECT * FROM mysql_statement_test");
        assertTrue(hasResultSet);
        
        ResultSet rs = statement.getResultSet();
        Assertions.assertNotNull(rs);
        assertTrue(rs.next());
        rs.close();
        
        hasResultSet = statement.execute("UPDATE mysql_statement_test SET name = 'Test' WHERE id = 1");
        assertFalse(hasResultSet);
        assertEquals(1, statement.getUpdateCount());
    }

    @ParameterizedTest
    @MethodSource("connectionDetails")
    void testGetResultSet(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        statement.execute("SELECT * FROM mysql_statement_test");
        ResultSet rs = statement.getResultSet();
        Assertions.assertNotNull(rs);
        rs.close();
    }

    @ParameterizedTest
    @MethodSource("connectionDetails")
    void testGetUpdateCount(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        statement.execute("UPDATE mysql_statement_test SET name = 'Test Update' WHERE id = 1");
        assertEquals(1, statement.getUpdateCount());
        
        statement.execute("SELECT * FROM mysql_statement_test");
        assertEquals(-1, statement.getUpdateCount());
    }

    @ParameterizedTest
    @MethodSource("connectionDetails")
    void testGetMoreResults(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        statement.execute("SELECT * FROM mysql_statement_test");
        if (url.toLowerCase().contains("mysql"))
            assertFalse(statement.getMoreResults());
        else
            Assert.assertThrows(SQLException.class, () -> statement.getMoreResults());
    }

    @ParameterizedTest
    @MethodSource("connectionDetails")
    void testFetchDirection(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        assertEquals(ResultSet.FETCH_FORWARD, statement.getFetchDirection());
        statement.setFetchDirection(ResultSet.FETCH_FORWARD);
        assertEquals(ResultSet.FETCH_FORWARD, statement.getFetchDirection());
    }

    @ParameterizedTest
    @MethodSource("connectionDetails")
    void testFetchSize(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        int originalFetchSize = statement.getFetchSize();
        statement.setFetchSize(100);
        assertEquals(100, statement.getFetchSize());
        statement.setFetchSize(originalFetchSize);
    }

    @ParameterizedTest
    @MethodSource("connectionDetails")
    void testResultSetConcurrency(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        int concurrency = statement.getResultSetConcurrency();
        assertTrue(concurrency == ResultSet.CONCUR_READ_ONLY || concurrency == ResultSet.CONCUR_UPDATABLE);
    }

    @ParameterizedTest
    @MethodSource("connectionDetails")
    void testResultSetType(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        int type = statement.getResultSetType();
        assertTrue(type == ResultSet.TYPE_FORWARD_ONLY ||
                   type == ResultSet.TYPE_SCROLL_INSENSITIVE || 
                   type == ResultSet.TYPE_SCROLL_SENSITIVE);
    }

    @ParameterizedTest
    @MethodSource("connectionDetails")
    void testAddBatch(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        statement.addBatch("INSERT INTO mysql_statement_test (id, name) VALUES (10, 'Batch1')");
        statement.addBatch("INSERT INTO mysql_statement_test (id, name) VALUES (11, 'Batch2')");
        statement.clearBatch();
        
        statement.addBatch("INSERT INTO mysql_statement_test (id, name) VALUES (12, 'Batch3')");
        int[] results = statement.executeBatch();
        assertEquals(1, results.length);
        assertEquals(1, results[0]);
    }

    @ParameterizedTest
    @MethodSource("connectionDetails")
    void testGetConnection(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        Assertions.assertSame(connection, statement.getConnection());
    }

    @ParameterizedTest
    @MethodSource("connectionDetails")
    void testGetGeneratedKeys(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        
        // Create table with auto-increment
        try {
            statement.execute("DROP TABLE mysql_auto_test");
        } catch (SQLException e) {
            // Ignore if table doesn't exist
        }
        statement.execute("CREATE TABLE mysql_auto_test (id INT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(100))");
        
        statement.execute("INSERT INTO mysql_auto_test (name) VALUES ('Test')", Statement.RETURN_GENERATED_KEYS);
        ResultSet keys = statement.getGeneratedKeys();
        Assertions.assertNotNull(keys);
        assertTrue(keys.next());
        assertTrue(keys.getInt(1) > 0);
        keys.close();
        
        statement.execute("DROP TABLE mysql_auto_test");
    }

    @ParameterizedTest
    @MethodSource("connectionDetails")
    void testExecuteUpdateWithGeneratedKeys(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        
        // Create table with auto-increment
        try{
            statement.execute("DROP TABLE mysql_auto_test2");
        } catch (SQLException e) {
            // Ignore if table doesn't exist
        }
        statement.execute("CREATE TABLE mysql_auto_test2 (id INT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(100))");
        
        int rows = statement.executeUpdate("INSERT INTO mysql_auto_test2 (name) VALUES ('Test')", Statement.RETURN_GENERATED_KEYS);
        assertEquals(1, rows);
        
        ResultSet keys = statement.getGeneratedKeys();
        Assertions.assertNotNull(keys);
        assertTrue(keys.next());
        assertTrue(keys.getInt(1) > 0);
        keys.close();
        
        statement.execute("DROP TABLE mysql_auto_test2");
    }

    @ParameterizedTest
    @MethodSource("connectionDetails")
    void testResultSetHoldability(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        int holdability = statement.getResultSetHoldability();
        assertTrue(holdability == ResultSet.HOLD_CURSORS_OVER_COMMIT ||
                   holdability == ResultSet.CLOSE_CURSORS_AT_COMMIT);
    }

    @ParameterizedTest
    @MethodSource("connectionDetails")
    void testCancel(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        // Test that cancel doesn't throw an exception
        statement.cancel();
    }

    @ParameterizedTest
    @MethodSource("connectionDetails")
    void testEscapeProcessing(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        statement.setEscapeProcessing(true);
        statement.setEscapeProcessing(false);
        // Just verify these calls don't throw exceptions
    }

    @ParameterizedTest
    @MethodSource("connectionDetails")
    void testCursorName(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        // MySQL may not support named cursors in all configurations
        if (url.toLowerCase().contains("mysql"))
            statement.setCursorName("test_cursor");
        else
            Assert.assertThrows(SQLException.class, () -> statement.setCursorName("test_cursor"));
    }

    @ParameterizedTest
    @MethodSource("connectionDetails")
    void testPoolable(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        boolean poolable = statement.isPoolable();
        statement.setPoolable(!poolable);
        if (url.toLowerCase().contains("mysql"))
            assertEquals(!poolable, statement.isPoolable());
        else
            assertFalse( statement.isPoolable());


        statement.setPoolable(poolable);
    }
}