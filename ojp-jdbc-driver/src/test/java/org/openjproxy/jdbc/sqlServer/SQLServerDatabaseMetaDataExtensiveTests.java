package org.openjproxy.jdbc.sqlServer;

import org.openjproxy.jdbc.testutil.SQLServerConnectionProvider;
import org.openjproxy.jdbc.testutil.TestDBUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@EnabledIf("org.openjproxy.jdbc.testutil.SQLServerTestContainer#isEnabled")
public class SQLServerDatabaseMetaDataExtensiveTests {

    private static boolean isTestDisabled;
    private static Connection connection;

    @BeforeAll
    static void checkTestConfiguration() {
        isTestDisabled = !Boolean.parseBoolean(System.getProperty("enableSqlServerTests", "false"));
    }

    public void setUp(String driverClass, String url, String user, String password) throws Exception {
        assumeFalse(isTestDisabled, "SQL Server tests are disabled");
        
        connection = DriverManager.getConnection(url, user, password);
        TestDBUtils.createBasicTestTable(connection, "sqlserver_db_metadata_test", TestDBUtils.SqlSyntax.SQLSERVER, true);
    }

    @AfterAll
    static void teardown() throws Exception {
        TestDBUtils.closeQuietly(connection);
    }

    @ParameterizedTest
    @ArgumentsSource(SQLServerConnectionProvider.class)
    void allDatabaseMetaDataMethodsShouldWorkAndBeAsserted(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        DatabaseMetaData meta = connection.getMetaData();

        // 1–5: Basic database information (SQL Server-specific values)
        assertTrue( meta.allProceduresAreCallable());
        assertTrue( meta.allTablesAreSelectable());
        assertTrue(meta.getURL().contains("sqlserver") || meta.getURL().contains(":1433"));
        assertNotNull(meta.getUserName()); // SQL Server username
        assertFalse( meta.isReadOnly());

        // 6–10: Null handling and database product info (SQL Server-specific behaviors)
        assertFalse( meta.nullsAreSortedHigh());  // SQL Server behavior
        assertTrue( meta.nullsAreSortedLow());   // SQL Server sorts nulls low
        assertFalse( meta.nullsAreSortedAtStart());
        assertFalse( meta.nullsAreSortedAtEnd());
        assertTrue(meta.getDatabaseProductName().toLowerCase().contains("microsoft"));

        // 11–15: Version information
        assertNotNull(meta.getDatabaseProductVersion());
        assertTrue(meta.getDriverName().toLowerCase().contains("microsoft"));
        assertNotNull(meta.getDriverVersion());
        assertTrue(meta.getDriverMajorVersion() >= 12); // SQL Server driver version
        assertTrue(meta.getDriverMinorVersion() >= 0);

        // 16–20: File handling and identifiers
        assertFalse( meta.usesLocalFiles());
        assertFalse( meta.usesLocalFilePerTable());
        assertTrue( meta.supportsMixedCaseIdentifiers());
        assertFalse( meta.storesUpperCaseIdentifiers()); // SQL Server doesn't force uppercase
        assertFalse( meta.storesLowerCaseIdentifiers()); // SQL Server doesn't force lowercase

        // 21–25: Quoted identifiers
        assertTrue( meta.storesMixedCaseIdentifiers()); // SQL Server preserves case
        assertTrue( meta.supportsMixedCaseQuotedIdentifiers());
        assertFalse( meta.storesUpperCaseQuotedIdentifiers());
        assertFalse( meta.storesLowerCaseQuotedIdentifiers());
        assertTrue( meta.storesMixedCaseQuotedIdentifiers()); // SQL Server behavior

        // 26–30: String handling and functions
        assertTrue(meta.getIdentifierQuoteString().equals("[") || meta.getIdentifierQuoteString().equals("\""));
        assertNotNull(meta.getSQLKeywords());
        assertNotNull(meta.getNumericFunctions());
        assertNotNull(meta.getStringFunctions());
        assertNotNull(meta.getSystemFunctions());

        // 31–35: Date/time functions and search escape characters
        assertNotNull(meta.getTimeDateFunctions());
        assertEquals("\\", meta.getSearchStringEscape()); // SQL Server escape for LIKE
        assertNotNull(meta.getExtraNameCharacters());
        assertTrue( meta.supportsAlterTableWithAddColumn());
        assertTrue( meta.supportsAlterTableWithDropColumn());

        // 36–40: Column operations and table correlation names
        assertTrue( meta.supportsColumnAliasing());
        assertTrue( meta.nullPlusNonNullIsNull());  // SQL Server: NULL + 'text' = NULL
        assertTrue( meta.supportsConvert());
        assertTrue( meta.supportsTableCorrelationNames());
        assertFalse( meta.supportsDifferentTableCorrelationNames());

        // 41–45: Expression handling and ORDER BY
        assertTrue( meta.supportsExpressionsInOrderBy());
        assertTrue( meta.supportsOrderByUnrelated());
        assertTrue( meta.supportsGroupBy());
        assertTrue( meta.supportsGroupByUnrelated());
        assertTrue( meta.supportsGroupByBeyondSelect());

        // 46–50: LIKE operations and escape characters
        assertTrue( meta.supportsLikeEscapeClause());
        assertTrue( meta.supportsMultipleResultSets()); // Depends on driver implementation
        assertTrue( meta.supportsMultipleTransactions());
        assertTrue( meta.supportsNonNullableColumns());
        assertTrue( meta.supportsMinimumSQLGrammar());

        // 51–55: SQL grammar support levels
        assertTrue( meta.supportsCoreSQLGrammar());
        assertFalse( meta.supportsExtendedSQLGrammar());
        assertTrue( meta.supportsANSI92EntryLevelSQL());
        assertFalse( meta.supportsANSI92IntermediateSQL());
        assertFalse( meta.supportsANSI92FullSQL()); // SQL Server doesn't fully support ANSI 92

        // 56–60: Outer joins and schema operations
        assertTrue( meta.supportsOuterJoins());
        assertTrue( meta.supportsFullOuterJoins());
        assertTrue( meta.supportsLimitedOuterJoins());
        assertNotNull(meta.getSchemaTerm());
        assertNotNull(meta.getProcedureTerm());

        // 61–65: Catalog and cursor operations
        assertNotNull(meta.getCatalogTerm());
        assertTrue( meta.isCatalogAtStart());
        assertEquals(".", meta.getCatalogSeparator());
        assertTrue( meta.supportsSchemasInDataManipulation());
        assertTrue( meta.supportsSchemasInProcedureCalls());

        // 66–70: Schema and catalog support in various contexts
        assertTrue( meta.supportsSchemasInTableDefinitions());
        assertTrue( meta.supportsSchemasInIndexDefinitions());
        assertTrue( meta.supportsSchemasInPrivilegeDefinitions());
        assertTrue( meta.supportsCatalogsInDataManipulation());
        assertTrue( meta.supportsCatalogsInProcedureCalls());

        // 71–75: Catalog support in definitions
        assertTrue( meta.supportsCatalogsInTableDefinitions());
        assertTrue( meta.supportsCatalogsInIndexDefinitions());
        assertTrue( meta.supportsCatalogsInPrivilegeDefinitions());
        assertTrue( meta.supportsPositionedDelete());
        assertTrue( meta.supportsPositionedUpdate());

        // 76–80: SELECT FOR UPDATE and stored procedures
        assertFalse( meta.supportsSelectForUpdate()); // SQL Server doesn't support SELECT FOR UPDATE
        assertTrue( meta.supportsStoredProcedures());
        assertTrue( meta.supportsSubqueriesInComparisons());
        assertTrue( meta.supportsSubqueriesInExists());
        assertTrue( meta.supportsSubqueriesInIns());

        // 81–85: Subquery support and correlation names
        assertTrue( meta.supportsSubqueriesInQuantifieds());
        assertTrue( meta.supportsCorrelatedSubqueries());
        assertTrue( meta.supportsUnion());
        assertTrue( meta.supportsUnionAll());
        assertFalse( meta.supportsOpenCursorsAcrossCommit());

        // 86–90: Cursor and statement persistence
        assertFalse( meta.supportsOpenCursorsAcrossRollback()); // SQL Server behavior
        assertTrue( meta.supportsOpenStatementsAcrossCommit());
        assertTrue( meta.supportsOpenStatementsAcrossRollback());
        assertEquals(0, meta.getMaxBinaryLiteralLength());
        assertEquals(0, meta.getMaxCharLiteralLength());

        // 91–95: Maximum lengths and limits
        assertTrue(meta.getMaxColumnNameLength() > 0);
        assertTrue(meta.getMaxColumnsInGroupBy() >= 0);
        assertTrue(meta.getMaxColumnsInIndex() >= 0);
        assertTrue(meta.getMaxColumnsInOrderBy() >= 0);
        assertTrue(meta.getMaxColumnsInSelect() >= 0);

        // 96–100: More maximum limits
        assertTrue(meta.getMaxColumnsInTable() > 0);
        assertTrue(meta.getMaxConnections() >= 0);
        assertTrue(meta.getMaxCursorNameLength() >= 0);
        assertTrue(meta.getMaxIndexLength() >= 0);
        assertTrue(meta.getMaxSchemaNameLength() > 0);

        // 101–105: Procedure and statement limits
        assertTrue(meta.getMaxProcedureNameLength() > 0);
        assertTrue(meta.getMaxCatalogNameLength() > 0);
        assertTrue(meta.getMaxRowSize() > 0);
        assertFalse( meta.doesMaxRowSizeIncludeBlobs()); // SQL Server behavior
        assertTrue(meta.getMaxStatementLength() > 0);

        // 106–110: More limits and transaction support
        assertTrue(meta.getMaxStatements() >= 0);
        assertTrue(meta.getMaxTableNameLength() > 0);
        assertTrue(meta.getMaxTablesInSelect() >= 0);
        assertTrue(meta.getMaxUserNameLength() > 0);
        assertTrue(meta.getDefaultTransactionIsolation() >= 0);

        // 111–115: Transaction and DDL support
        assertTrue( meta.supportsTransactions());
        assertTrue( meta.supportsDataDefinitionAndDataManipulationTransactions());
        assertFalse( meta.supportsDataManipulationTransactionsOnly());
        assertFalse( meta.dataDefinitionCausesTransactionCommit()); // SQL Server behavior
        assertFalse( meta.dataDefinitionIgnoredInTransactions());

        // Clean up
        TestDBUtils.cleanupTestTables(connection, "sqlserver_db_metadata_test");
    }

    @ParameterizedTest
    @ArgumentsSource(SQLServerConnectionProvider.class)
    void testSqlServerSpecificMetadata(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        DatabaseMetaData meta = connection.getMetaData();

        // Test SQL Server-specific metadata features
        assertNotNull(meta.getDatabaseProductName());
        assertTrue(meta.getDatabaseProductName().toLowerCase().contains("microsoft"));

        // Test SQL Server version information
        String version = meta.getDatabaseProductVersion();
        assertNotNull(version);
        
        // Test SQL Server supports various features
        assertTrue( meta.supportsStoredProcedures());
        assertTrue( meta.supportsBatchUpdates());
        
        // Test SQL Server identifier handling
        assertTrue(meta.getIdentifierQuoteString().equals("[") ||
                             meta.getIdentifierQuoteString().equals("\""));

        // Clean up
        TestDBUtils.cleanupTestTables(connection, "sqlserver_db_metadata_test");
    }

    @ParameterizedTest
    @ArgumentsSource(SQLServerConnectionProvider.class)
    void testGetTables(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        DatabaseMetaData meta = connection.getMetaData();

        ResultSet rs = meta.getTables(null, null, "%", new String[]{"TABLE"});
        TestDBUtils.validateAllRows(rs);
        rs.close();

        // Clean up
        TestDBUtils.cleanupTestTables(connection, "sqlserver_db_metadata_test");
    }

    @ParameterizedTest
    @ArgumentsSource(SQLServerConnectionProvider.class)
    void testGetColumns(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        DatabaseMetaData meta = connection.getMetaData();

        ResultSet rs = meta.getColumns(null, null, "sqlserver_db_metadata_test", "%");
        TestDBUtils.validateAllRows(rs);
        rs.close();

        // Clean up
        TestDBUtils.cleanupTestTables(connection, "sqlserver_db_metadata_test");
    }
}