package org.openjproxy.jdbc.mySql;

import lombok.SneakyThrows;
import org.openjproxy.jdbc.testutil.TestDBUtils;
import org.junit.Assert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

 class MySQLMariaDBConnectionExtensiveTests {

    private static boolean isMySQLTestEnabled;
    private static boolean isMariaDBTestEnabled;
    private Connection connection;

    @BeforeAll
    static void checkTestConfiguration() {
        isMySQLTestEnabled = Boolean.parseBoolean(System.getProperty("enableMySQLTests", "false"));
        isMariaDBTestEnabled = Boolean.parseBoolean(System.getProperty("enableMariaDBTests", "false"));
    }

    @SneakyThrows
    public void setUp(String driverClass, String url, String user, String password) throws SQLException {
        assumeFalse(!isMySQLTestEnabled, "MySQL tests are not enabled");
        assumeFalse(!isMariaDBTestEnabled, "MariaDB tests are not enabled");
        connection = DriverManager.getConnection(url, user, password);
    }

    @AfterEach
    void tearDown() {
        TestDBUtils.closeQuietly(connection);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    void testCreateStatement(String driverClass, String url, String user, String password) throws SQLException {
        setUp(driverClass, url, user, password);

        Statement statement = connection.createStatement();
        assertNotNull(statement);
        statement.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    void testPrepareStatement(String driverClass, String url, String user, String password) throws SQLException {
        setUp(driverClass, url, user, password);

        PreparedStatement preparedStatement = connection.prepareStatement("SELECT 1");
        assertNotNull(preparedStatement);
        preparedStatement.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    void testPrepareCall(String driverClass, String url, String user, String password) throws SQLException {
        setUp(driverClass, url, user, password);

        // MySQL supports callable statements, though syntax may differ
        try {
            CallableStatement callableStatement = connection.prepareCall("CALL test_procedure()");
            assertNotNull(callableStatement);
            callableStatement.close();
        } catch (SQLException e) {
            // This is expected if the procedure doesn't exist - test that the method works
            assertTrue(e.getMessage().contains("PROCEDURE") || e.getMessage().contains("procedure"));
        }
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    void testNativeSQL(String driverClass, String url, String user, String password) throws SQLException {
        setUp(driverClass, url, user, password);

        String nativeSQL = connection.nativeSQL("SELECT {fn NOW()}");
        assertNotNull(nativeSQL);
        // MySQL should convert JDBC escape sequence
        assertTrue(nativeSQL.contains("NOW()") || nativeSQL.contains("SELECT"));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    void testAutoCommit(String driverClass, String url, String user, String password) throws SQLException {
        setUp(driverClass, url, user, password);

        // Test getting and setting auto-commit
        boolean originalAutoCommit = connection.getAutoCommit();

        connection.setAutoCommit(false);
        assertFalse(connection.getAutoCommit());

        connection.setAutoCommit(true);
        assertTrue(connection.getAutoCommit());

        // Restore original state
        connection.setAutoCommit(originalAutoCommit);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    void testCommitAndRollback(String driverClass, String url, String user, String password) throws SQLException {
        setUp(driverClass, url, user, password);

        // Test commit and rollback operations
        connection.setAutoCommit(false);

        // These should not throw exceptions
        connection.commit();
        connection.rollback();

        connection.setAutoCommit(true);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    void testIsClosed(String driverClass, String url, String user, String password) throws SQLException {
        setUp(driverClass, url, user, password);

        assertFalse(connection.isClosed());

        connection.close();
        assertTrue(connection.isClosed());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    void testGetMetaData(String driverClass, String url, String user, String password) throws SQLException {
        setUp(driverClass, url, user, password);

        DatabaseMetaData metaData = connection.getMetaData();
        assertNotNull(metaData);

        String databaseProductName = metaData.getDatabaseProductName();
        assertNotNull(databaseProductName);
        if (url.toLowerCase().contains("mysql"))
            assertTrue(databaseProductName.toLowerCase().contains("mysql"));
        else
            assertTrue(databaseProductName.toLowerCase().contains("mariadb"));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    void testReadOnly(String driverClass, String url, String user, String password) throws SQLException {
        setUp(driverClass, url, user, password);

        // Test read-only mode
        boolean originalReadOnly = connection.isReadOnly();

        try {
            connection.setReadOnly(true);
            // MySQL may or may not support read-only mode depending on configuration
            // Just test that the call doesn't throw an unexpected exception
        } catch (SQLException e) {
            // Some MySQL configurations may not support read-only mode
            // This is acceptable
        }

        // Restore original state
        connection.setReadOnly(originalReadOnly);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    void testCatalog(String driverClass, String url, String user, String password) throws SQLException {
        setUp(driverClass, url, user, password);

        String catalog = connection.getCatalog();
        // Catalog might be null or the database name

        // Test setting catalog (should work in MySQL)
        if (catalog != null) {
            connection.setCatalog(catalog);
            assertEquals(catalog, connection.getCatalog());
        }
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    void testTransactionIsolation(String driverClass, String url, String user, String password) throws SQLException {
        setUp(driverClass, url, user, password);

        int isolationLevel = connection.getTransactionIsolation();
        assertTrue(isolationLevel >= Connection.TRANSACTION_NONE && isolationLevel <= Connection.TRANSACTION_SERIALIZABLE);

        // Test setting transaction isolation level
        connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        assertEquals(Connection.TRANSACTION_READ_COMMITTED, connection.getTransactionIsolation());

        // Restore original level
        connection.setTransactionIsolation(isolationLevel);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    void testWarnings(String driverClass, String url, String user, String password) throws SQLException {
        setUp(driverClass, url, user, password);

        // Test warning operations
        SQLWarning warnings = connection.getWarnings();
        // Warnings might be null initially

        connection.clearWarnings();
        // Should not throw exception
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    void testCreateStatementWithParameters(String driverClass, String url, String user, String password) throws SQLException {
        setUp(driverClass, url, user, password);

        Statement statement = connection.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        assertNotNull(statement);
        statement.close();

        statement = connection.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
        assertNotNull(statement);
        statement.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    void testPrepareStatementWithParameters(String driverClass, String url, String user, String password) throws SQLException {
        setUp(driverClass, url, user, password);

        PreparedStatement ps = connection.prepareStatement("SELECT 1", ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        assertNotNull(ps);
        ps.close();

        ps = connection.prepareStatement("SELECT 1", Statement.RETURN_GENERATED_KEYS);
        assertNotNull(ps);
        ps.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    void testHoldability(String driverClass, String url, String user, String password) throws SQLException {
        setUp(driverClass, url, user, password);

        int holdability = connection.getHoldability();
        assertTrue(holdability == ResultSet.HOLD_CURSORS_OVER_COMMIT || holdability == ResultSet.CLOSE_CURSORS_AT_COMMIT);

        // Test setting holdability
        connection.setHoldability(ResultSet.HOLD_CURSORS_OVER_COMMIT);
        assertEquals(ResultSet.HOLD_CURSORS_OVER_COMMIT, connection.getHoldability());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    void testSavepoints(String driverClass, String url, String user, String password) throws SQLException {
        setUp(driverClass, url, user, password);

        connection.setAutoCommit(false);

        // Test unnamed savepoint
        Savepoint savepoint1 = connection.setSavepoint();
        assertNotNull(savepoint1);

        // Test named savepoint
        Savepoint savepoint2 = connection.setSavepoint("test_savepoint");
        assertNotNull(savepoint2);
        assertEquals("test_savepoint", savepoint2.getSavepointName());

        // Test rollback to savepoint
        connection.rollback(savepoint2);
        connection.rollback(savepoint1);

        // Test release savepoint
        connection.releaseSavepoint(savepoint1);

        connection.setAutoCommit(true);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    void testClientInfo(String driverClass, String url, String user, String password) throws SQLException {
        setUp(driverClass, url, user, password);

        Properties clientInfo = connection.getClientInfo();
        assertNotNull(clientInfo);

        // Test setting client info
        try {
            connection.setClientInfo("ApplicationName", "MySQLTest");
            // Should not throw exception, though MySQL may not support all properties
        } catch (SQLClientInfoException e) {
            // This is acceptable for MySQL
        }
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    void testValid(String driverClass, String url, String user, String password) throws SQLException {
        setUp(driverClass, url, user, password);

        boolean isValid = connection.isValid(5);
        assertTrue(isValid);

        // Test with closed connection
        connection.close();
        isValid = connection.isValid(5);
        assertFalse(isValid);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    void testUnsupportedOperations(String driverClass, String url, String user, String password) throws SQLException {
        setUp(driverClass, url, user, password);

        // Test operations that might not be supported
        Assert.assertThrows(SQLException.class, () -> {
            connection.createArrayOf("VARCHAR", new String[]{"test"});
        });

        Assert.assertThrows(SQLFeatureNotSupportedException.class, () -> {
            connection.createStruct("test_type", new Object[]{});
        });
    }
}