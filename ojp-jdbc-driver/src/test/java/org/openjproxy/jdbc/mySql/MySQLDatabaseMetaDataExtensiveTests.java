package org.openjproxy.jdbc.mySql;

import org.openjproxy.jdbc.testutil.TestDBUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

 class MySQLDatabaseMetaDataExtensiveTests {

    private static boolean isMySQLTestEnabled;
    private static boolean isMariaDBTestEnabled;
    private static Connection connection;

    @BeforeAll
    static void checkTestConfiguration() {
        isMySQLTestEnabled = Boolean.parseBoolean(System.getProperty("enableMySQLTests", "false"));
        isMariaDBTestEnabled = Boolean.parseBoolean(System.getProperty("enableMariaDBTests", "false"));
    }

     void setUp(String driverClass, String url, String user, String password) throws Exception {
        assumeFalse(!isMySQLTestEnabled, "MySQL tests are not enabled");
        assumeFalse(!isMariaDBTestEnabled, "MariaDB tests are not enabled");
        connection = DriverManager.getConnection(url, user, password);
        TestDBUtils.createBasicTestTable(connection, "mysql_db_metadata_test", TestDBUtils.SqlSyntax.MYSQL, true);
    }

    @AfterAll
    static void teardown() {
        TestDBUtils.closeQuietly(connection);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    void testBasicDatabaseMetaDataProperties(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        DatabaseMetaData meta = connection.getMetaData();

        // Basic database properties
        assertNotNull(meta.getDatabaseProductName());
        if (url.toLowerCase().contains("mysql"))
            assertTrue(meta.getDatabaseProductName().toLowerCase().contains("mysql"));
        else
            assertTrue(meta.getDatabaseProductName().toLowerCase().contains("mariadb"));

        assertNotNull(meta.getDatabaseProductVersion());
        assertNotNull(meta.getDriverName());
        assertNotNull(meta.getDriverVersion());
        assertTrue(meta.getDriverMajorVersion() > 0);

        // URL and user info
        assertNotNull(meta.getURL());
        if (url.toLowerCase().contains("mysql"))
            assertTrue(meta.getURL().contains("mysql"));
        else
            assertTrue(meta.getURL().contains("mariadb"));

        assertNotNull(meta.getUserName());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    void testSupportFeatures(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        DatabaseMetaData meta = connection.getMetaData();

        // MySQL typically supports these features
        assertTrue(meta.supportsTransactions());
        if (url.toLowerCase().contains("mysql"))
            assertFalse(meta.supportsDataDefinitionAndDataManipulationTransactions());
        else
            assertTrue(meta.supportsDataDefinitionAndDataManipulationTransactions());
        assertFalse(meta.supportsDataManipulationTransactionsOnly());
        assertTrue(meta.supportsResultSetType(ResultSet.TYPE_FORWARD_ONLY));
        assertTrue(meta.supportsResultSetConcurrency(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY));

        // MySQL supports various SQL features
        assertTrue(meta.supportsAlterTableWithAddColumn());
        assertTrue(meta.supportsAlterTableWithDropColumn());
        assertTrue(meta.supportsUnion());
        assertTrue(meta.supportsUnionAll());
        if (url.toLowerCase().contains("mysql"))
            assertFalse(meta.supportsOrderByUnrelated());
        else
            assertTrue(meta.supportsOrderByUnrelated());
        assertTrue(meta.supportsGroupBy());
        assertTrue(meta.supportsGroupByUnrelated());
        assertTrue(meta.supportsLikeEscapeClause());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    void testIdentifierProperties(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        DatabaseMetaData meta = connection.getMetaData();

        // MySQL identifier properties
        assertNotNull(meta.getIdentifierQuoteString());
        assertEquals("`", meta.getIdentifierQuoteString());
        assertNotNull(meta.getSQLKeywords());
        assertNotNull(meta.getExtraNameCharacters());

        // Case sensitivity - MySQL varies by platform
        // Just test that these methods don't throw exceptions
        meta.supportsMixedCaseIdentifiers();
        meta.storesUpperCaseIdentifiers();
        meta.storesLowerCaseIdentifiers();
        meta.storesMixedCaseIdentifiers();
        meta.supportsMixedCaseQuotedIdentifiers();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    void testTransactionSupport(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        DatabaseMetaData meta = connection.getMetaData();

        // Transaction isolation levels
        assertTrue(meta.supportsTransactionIsolationLevel(Connection.TRANSACTION_READ_COMMITTED));
        assertTrue(meta.supportsTransactionIsolationLevel(Connection.TRANSACTION_READ_UNCOMMITTED));
        assertTrue(meta.supportsTransactionIsolationLevel(Connection.TRANSACTION_REPEATABLE_READ));
        assertTrue(meta.supportsTransactionIsolationLevel(Connection.TRANSACTION_SERIALIZABLE));

        // Default transaction isolation
        int defaultIsolation = meta.getDefaultTransactionIsolation();
        assertTrue(defaultIsolation >= Connection.TRANSACTION_NONE &&
                defaultIsolation <= Connection.TRANSACTION_SERIALIZABLE);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    void testFunctionSupport(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        DatabaseMetaData meta = connection.getMetaData();

        // Function lists should not be null
        assertNotNull(meta.getNumericFunctions());
        assertNotNull(meta.getStringFunctions());
        assertNotNull(meta.getSystemFunctions());
        assertNotNull(meta.getTimeDateFunctions());

        // MySQL should support common functions
        String numericFunctions = meta.getNumericFunctions().toUpperCase();
        String stringFunctions = meta.getStringFunctions().toUpperCase();
        String timeDateFunctions = meta.getTimeDateFunctions().toUpperCase();

        // Verify some common MySQL functions are listed
        assertTrue(numericFunctions.contains("ABS") || numericFunctions.contains("ROUND"));
        assertTrue(stringFunctions.contains("CONCAT") || stringFunctions.contains("LENGTH"));
        assertTrue(timeDateFunctions.contains("NOW") || timeDateFunctions.contains("CURDATE"));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    void testResultSetSupport(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        DatabaseMetaData meta = connection.getMetaData();

        // ResultSet type support
        assertTrue(meta.supportsResultSetType(ResultSet.TYPE_FORWARD_ONLY));
        // MySQL may or may not support scrollable result sets depending on configuration
        meta.supportsResultSetType(ResultSet.TYPE_SCROLL_INSENSITIVE);
        meta.supportsResultSetType(ResultSet.TYPE_SCROLL_SENSITIVE);

        // ResultSet concurrency support
        assertTrue(meta.supportsResultSetConcurrency(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY));
        meta.supportsResultSetConcurrency(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE);

        // ResultSet holdability
        assertTrue(meta.supportsResultSetHoldability(ResultSet.HOLD_CURSORS_OVER_COMMIT) ||
                meta.supportsResultSetHoldability(ResultSet.CLOSE_CURSORS_AT_COMMIT));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    void testGetTables(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        DatabaseMetaData meta = connection.getMetaData();

        // Test getTables method
        ResultSet tables = meta.getTables(null, null, "%", new String[]{"TABLE"});
        assertNotNull(tables);

        boolean foundTestTable = false;
        while (tables.next()) {
            String tableName = tables.getString("TABLE_NAME");
            if ("mysql_db_metadata_test".equals(tableName)) {
                foundTestTable = true;
                assertEquals("TABLE", tables.getString("TABLE_TYPE"));
                break;
            }
        }
        Assertions.assertTrue(foundTestTable);
        tables.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    void testGetColumns(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        DatabaseMetaData meta = connection.getMetaData();

        // Test getColumns method
        ResultSet columns = meta.getColumns(null, null, "mysql_db_metadata_test", "%");
        assertNotNull(columns);

        boolean foundIdColumn = false;
        boolean foundNameColumn = false;
        while (columns.next()) {
            String columnName = columns.getString("COLUMN_NAME");
            if ("id".equals(columnName)) {
                foundIdColumn = true;
                assertEquals("INT", columns.getString("TYPE_NAME").toUpperCase());
            } else if ("name".equals(columnName)) {
                foundNameColumn = true;
                assertEquals("VARCHAR", columns.getString("TYPE_NAME").toUpperCase());
            }
        }
        Assertions.assertTrue(foundIdColumn);
        Assertions.assertTrue(foundNameColumn);
        columns.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    void testGetPrimaryKeys(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        DatabaseMetaData meta = connection.getMetaData();

        // Test getPrimaryKeys method
        ResultSet primaryKeys = meta.getPrimaryKeys(null, null, "mysql_db_metadata_test");
        assertNotNull(primaryKeys);

        boolean foundPrimaryKey = false;
        while (primaryKeys.next()) {
            String columnName = primaryKeys.getString("COLUMN_NAME");
            if ("id".equals(columnName)) {
                foundPrimaryKey = true;
                assertEquals("mysql_db_metadata_test", primaryKeys.getString("TABLE_NAME"));
                assertEquals((short) 1, primaryKeys.getShort("KEY_SEQ"));
                break;
            }
        }
        assertTrue(foundPrimaryKey);
        primaryKeys.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    void testGetTypeInfo(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        DatabaseMetaData meta = connection.getMetaData();

        // Test getTypeInfo method
        ResultSet typeInfo = meta.getTypeInfo();
        assertNotNull(typeInfo);

        boolean foundIntType = false;
        boolean foundVarcharType = false;
        while (typeInfo.next()) {
            String typeName = typeInfo.getString("TYPE_NAME").toUpperCase();
            if (typeName.contains("INT")) {
                foundIntType = true;
            } else if (typeName.contains("VARCHAR")) {
                foundVarcharType = true;
            }
        }
        assertTrue(foundIntType);
        assertTrue(foundVarcharType);
        typeInfo.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    void testMySQLSpecificMetaData(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        DatabaseMetaData meta = connection.getMetaData();

        // MySQL specific features
        assertTrue(meta.supportsSubqueriesInComparisons());
        assertTrue(meta.supportsSubqueriesInExists());
        assertTrue(meta.supportsSubqueriesInIns());
        assertTrue(meta.supportsCorrelatedSubqueries());

        // Batch updates
        assertTrue(meta.supportsBatchUpdates());

        // Savepoints
        assertTrue(meta.supportsSavepoints());

        // Get/Set autocommit
        assertTrue(meta.supportsGetGeneratedKeys());

        // Named parameters in callable statements
        meta.supportsNamedParameters(); // May return false, which is fine

        // Multiple open results
        meta.supportsMultipleOpenResults(); // Depends on MySQL version/config
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    void testLimitsAndSizes(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        DatabaseMetaData meta = connection.getMetaData();

        // Test various limits - these should return reasonable values or 0 if unlimited
        assertTrue(meta.getMaxBinaryLiteralLength() >= 0);
        assertTrue(meta.getMaxCharLiteralLength() >= 0);
        assertTrue(meta.getMaxColumnNameLength() >= 0);
        assertTrue(meta.getMaxColumnsInGroupBy() >= 0);
        assertTrue(meta.getMaxColumnsInIndex() >= 0);
        assertTrue(meta.getMaxColumnsInOrderBy() >= 0);
        assertTrue(meta.getMaxColumnsInSelect() >= 0);
        assertTrue(meta.getMaxColumnsInTable() >= 0);
        assertTrue(meta.getMaxConnections() >= 0);
        assertTrue(meta.getMaxCursorNameLength() >= 0);
        assertTrue(meta.getMaxIndexLength() >= 0);
        assertTrue(meta.getMaxSchemaNameLength() >= 0);
        assertTrue(meta.getMaxProcedureNameLength() >= 0);
        assertTrue(meta.getMaxCatalogNameLength() >= 0);
        assertTrue(meta.getMaxRowSize() >= 0);
        assertTrue(meta.getMaxStatementLength() >= 0);
        assertTrue(meta.getMaxStatements() >= 0);
        assertTrue(meta.getMaxTableNameLength() >= 0);
        assertTrue(meta.getMaxTablesInSelect() >= 0);
        assertTrue(meta.getMaxUserNameLength() >= 0);
    }
}