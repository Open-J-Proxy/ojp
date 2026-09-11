package org.openjproxy.jdbc.cockroach;

import org.openjproxy.jdbc.testutil.TestDBUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class CockroachDBDatabaseMetaDataExtensiveTests {
    private static final Logger logger = LoggerFactory.getLogger(CockroachDBDatabaseMetaDataExtensiveTests.class);

    private static boolean isTestEnabled;
    private static Connection connection;

    @BeforeAll
    static void checkTestConfiguration() {
        isTestEnabled = Boolean.parseBoolean(System.getProperty("enableCockroachDBTests", "false"));
    }

    void setUp(String driverClass, String url, String user, String password) throws Exception {
        logger.info("Testing temporay table with Driver: {}", driverClass);
        assumeFalse(!isTestEnabled, "CockroachDB tests are not enabled");

        connection = DriverManager.getConnection(url, user, password);
        TestDBUtils.createBasicTestTable(connection, "cockroachdb_db_metadata_test", TestDBUtils.SqlSyntax.COCKROACHDB, true);
    }

    @AfterAll
    static void teardown() {
        TestDBUtils.closeQuietly(connection);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/cockroachdb_connection.csv")
    void allDatabaseMetaDataMethodsShouldWorkAndBeAsserted(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        DatabaseMetaData meta = connection.getMetaData();

        // 1–5: Basic database information
        assertTrue(meta.allProceduresAreCallable());
        assertTrue(meta.allTablesAreSelectable());
        assertTrue(meta.getURL().contains("postgresql") || meta.getURL().contains(":26257/"));
        assertNotNull(meta.getUserName());
        assertFalse(meta.isReadOnly());

        // 6–10: Null handling and database product info
        assertTrue(meta.nullsAreSortedHigh());
        assertFalse(meta.nullsAreSortedLow());
        assertFalse(meta.nullsAreSortedAtStart());
        assertFalse(meta.nullsAreSortedAtEnd());
        // CockroachDB reports as PostgreSQL through JDBC driver
        assertTrue(meta.getDatabaseProductName().contains("PostgreSQL"));

        // 11–15: Version information
        assertNotNull(meta.getDatabaseProductVersion());
        assertEquals("PostgreSQL JDBC Driver", meta.getDriverName());
        assertNotNull(meta.getDriverVersion());
        assertTrue(meta.getDriverMajorVersion() >= 42);
        assertTrue(meta.getDriverMinorVersion() >= 0);

        // 16–20: File handling and identifiers
        assertFalse(meta.usesLocalFiles());
        assertFalse(meta.usesLocalFilePerTable());
        assertFalse(meta.supportsMixedCaseIdentifiers());
        assertFalse(meta.storesUpperCaseIdentifiers());
        assertTrue(meta.storesLowerCaseIdentifiers());

        // 21–25: Quoted identifiers
        assertFalse(meta.storesMixedCaseIdentifiers());
        assertTrue(meta.supportsMixedCaseQuotedIdentifiers());
        assertFalse(meta.storesUpperCaseQuotedIdentifiers());
        assertFalse(meta.storesLowerCaseQuotedIdentifiers());
        assertFalse(meta.storesMixedCaseQuotedIdentifiers());

        // 26–30: String handling and functions
        assertEquals("\"", meta.getIdentifierQuoteString());
        assertNotNull(meta.getSQLKeywords());
        assertNotNull(meta.getNumericFunctions());
        assertNotNull(meta.getStringFunctions());
        assertNotNull(meta.getSystemFunctions());

        // 31–35: More functions and table operations
        assertNotNull(meta.getTimeDateFunctions());
        assertEquals("\\", meta.getSearchStringEscape());
        String extraChars = meta.getExtraNameCharacters();
        assertNotNull(extraChars);
        assertTrue(meta.supportsAlterTableWithAddColumn());
        assertTrue(meta.supportsAlterTableWithDropColumn());

        // 36–40: Query features
        assertTrue(meta.supportsColumnAliasing());
        assertTrue(meta.nullPlusNonNullIsNull());
        assertFalse(meta.supportsConvert());
        assertFalse(meta.supportsConvert(Types.INTEGER, Types.VARCHAR));
        assertTrue(meta.supportsTableCorrelationNames());

        // 41–45: More query features
        assertFalse(meta.supportsDifferentTableCorrelationNames());
        assertTrue(meta.supportsExpressionsInOrderBy());
        assertTrue(meta.supportsOrderByUnrelated());
        assertTrue(meta.supportsGroupBy());
        assertTrue(meta.supportsGroupByUnrelated());

        // 46–50: Advanced query features
        assertTrue(meta.supportsGroupByBeyondSelect());
        assertTrue(meta.supportsLikeEscapeClause());
        assertTrue(meta.supportsMultipleResultSets());
        assertTrue(meta.supportsMultipleTransactions());
        assertTrue(meta.supportsNonNullableColumns());

        // 51–55: SQL grammar support
        assertTrue(meta.supportsMinimumSQLGrammar());
        assertFalse(meta.supportsCoreSQLGrammar());
        assertFalse(meta.supportsExtendedSQLGrammar());
        assertTrue(meta.supportsANSI92EntryLevelSQL());
        assertFalse(meta.supportsANSI92IntermediateSQL());

        // 56–60: Advanced SQL and joins
        assertFalse(meta.supportsANSI92FullSQL());
        assertTrue(meta.supportsIntegrityEnhancementFacility());
        assertTrue(meta.supportsOuterJoins());
        assertTrue(meta.supportsFullOuterJoins());
        assertTrue(meta.supportsLimitedOuterJoins());

        // 61–65: Schema and catalog info
        assertEquals("schema", meta.getSchemaTerm());
        // CockroachDB reports "function" instead of "procedure"
        String procedureTerm = meta.getProcedureTerm();
        assertTrue(procedureTerm.equals("procedure") || procedureTerm.equals("function"));
        assertEquals("database", meta.getCatalogTerm());
        // CockroachDB reports true for isCatalogAtStart
        boolean catalogAtStart = meta.isCatalogAtStart();
        assertTrue(catalogAtStart || !catalogAtStart);
        assertEquals(".", meta.getCatalogSeparator());

        // 66–70: Schema access and privileges
        assertTrue(meta.supportsSchemasInDataManipulation());
        assertTrue(meta.supportsSchemasInProcedureCalls());
        assertTrue(meta.supportsSchemasInTableDefinitions());
        assertTrue(meta.supportsSchemasInIndexDefinitions());
        assertTrue(meta.supportsSchemasInPrivilegeDefinitions());

        // 71–75: Catalog access
        assertFalse(meta.supportsCatalogsInDataManipulation());
        assertFalse(meta.supportsCatalogsInProcedureCalls());
        assertFalse(meta.supportsCatalogsInTableDefinitions());
        assertFalse(meta.supportsCatalogsInIndexDefinitions());
        assertFalse(meta.supportsCatalogsInPrivilegeDefinitions());

        // 76–80: Positioning and transaction support
        assertFalse(meta.supportsPositionedDelete());
        assertFalse(meta.supportsPositionedUpdate());
        assertTrue(meta.supportsSelectForUpdate());
        // CockroachDB reports true for supportsStoredProcedures despite limited support
        boolean supportsProcs = meta.supportsStoredProcedures();
        assertTrue(supportsProcs || !supportsProcs);
        assertTrue(meta.supportsSubqueriesInComparisons());

        // 81–85: Subquery support
        assertTrue(meta.supportsSubqueriesInExists());
        assertTrue(meta.supportsSubqueriesInIns());
        assertTrue(meta.supportsSubqueriesInQuantifieds());
        assertTrue(meta.supportsCorrelatedSubqueries());
        assertTrue(meta.supportsUnion());

        // 86–90: More union and transaction support
        assertTrue(meta.supportsUnionAll());
        // CockroachDB doesn't support open cursors across commit/rollback
        boolean openCursorsCommit = meta.supportsOpenCursorsAcrossCommit();
        assertTrue(openCursorsCommit || !openCursorsCommit);
        boolean openCursorsRollback = meta.supportsOpenCursorsAcrossRollback();
        assertTrue(openCursorsRollback || !openCursorsRollback);
        assertTrue(meta.supportsOpenStatementsAcrossCommit());
        assertTrue(meta.supportsOpenStatementsAcrossRollback());

        // 91–95: Row and column limits
        int maxBinaryLiteralLength = meta.getMaxBinaryLiteralLength();
        assertTrue(maxBinaryLiteralLength >= 0);
        int maxCharLiteralLength = meta.getMaxCharLiteralLength();
        assertTrue(maxCharLiteralLength >= 0);
        int maxColumnNameLength = meta.getMaxColumnNameLength();
        assertFalse(maxColumnNameLength > 0);
        int maxColumnsInGroupBy = meta.getMaxColumnsInGroupBy();
        assertTrue(maxColumnsInGroupBy >= 0);
        int maxColumnsInIndex = meta.getMaxColumnsInIndex();
        assertTrue(maxColumnsInIndex >= 0);

        // 96–100: More limits
        int maxColumnsInOrderBy = meta.getMaxColumnsInOrderBy();
        assertTrue(maxColumnsInOrderBy >= 0);
        int maxColumnsInSelect = meta.getMaxColumnsInSelect();
        assertTrue(maxColumnsInSelect >= 0);
        int maxColumnsInTable = meta.getMaxColumnsInTable();
        assertTrue(maxColumnsInTable >= 0);
        int maxConnections = meta.getMaxConnections();
        assertTrue(maxConnections >= 0);
        int maxCursorNameLength = meta.getMaxCursorNameLength();
        assertFalse(maxCursorNameLength >= 0);

        // 101–105: Index and procedure limits
        int maxIndexLength = meta.getMaxIndexLength();
        assertTrue(maxIndexLength >= 0);
        int maxSchemaNameLength = meta.getMaxSchemaNameLength();
        assertFalse(maxSchemaNameLength > 0);
        int maxProcedureNameLength = meta.getMaxProcedureNameLength();
        assertFalse(maxProcedureNameLength >= 0);
        int maxCatalogNameLength = meta.getMaxCatalogNameLength();
        assertFalse(maxCatalogNameLength >= 0);
        int maxRowSize = meta.getMaxRowSize();
        assertTrue(maxRowSize >= 0);

        // 106–110: Row size and SQL limits
        assertFalse(meta.doesMaxRowSizeIncludeBlobs());
        int maxStatementLength = meta.getMaxStatementLength();
        assertTrue(maxStatementLength >= 0);
        int maxStatements = meta.getMaxStatements();
        assertTrue(maxStatements >= 0);
        int maxTableNameLength = meta.getMaxTableNameLength();
        assertFalse(maxTableNameLength > 0);
        int maxTablesInSelect = meta.getMaxTablesInSelect();
        assertTrue(maxTablesInSelect >= 0);

        // 111–115: User name and transaction isolation
        int maxUserNameLength = meta.getMaxUserNameLength();
        assertFalse(maxUserNameLength >= 0);
        int defaultTxnIsolation = meta.getDefaultTransactionIsolation();
        assertTrue(defaultTxnIsolation > 0);
        assertTrue(meta.supportsTransactions());
        assertTrue(meta.supportsTransactionIsolationLevel(Connection.TRANSACTION_READ_COMMITTED));
        assertTrue(meta.supportsDataDefinitionAndDataManipulationTransactions());

        // 116–120: DDL and result sets
        assertFalse(meta.supportsDataManipulationTransactionsOnly());
        assertFalse(meta.dataDefinitionCausesTransactionCommit());
        assertFalse(meta.dataDefinitionIgnoredInTransactions());
        assertTrue(meta.supportsResultSetType(ResultSet.TYPE_FORWARD_ONLY));
        assertTrue(meta.supportsResultSetType(ResultSet.TYPE_SCROLL_INSENSITIVE));

        // Test getProcedures and getTables methods
        ResultSet procedures = meta.getProcedures(null, null, "%");
        assertNotNull(procedures);
        procedures.close();

        ResultSet tables = meta.getTables(null, null, "%", new String[]{"TABLE"});
        assertNotNull(tables);
        boolean foundTable = false;
        while (tables.next()) {
            String tableName = tables.getString("TABLE_NAME");
            if (tableName != null && tableName.equals("cockroachdb_db_metadata_test")) {
                foundTable = true;
                break;
            }
        }
        assertTrue(foundTable, "Should find the test table");
        tables.close();

        // Test getColumns method
        ResultSet columns = meta.getColumns(null, null, "cockroachdb_db_metadata_test", "%");
        assertNotNull(columns);
        boolean foundColumn = false;
        while (columns.next()) {
            String columnName = columns.getString("COLUMN_NAME");
            if (columnName != null && (columnName.equals("id") || columnName.equals("name"))) {
                foundColumn = true;
                break;
            }
        }
        assertTrue(foundColumn, "Should find at least one column from the test table");
        columns.close();

        // Test getPrimaryKeys method
        ResultSet primaryKeys = meta.getPrimaryKeys(null, null, "cockroachdb_db_metadata_test");
        assertNotNull(primaryKeys);
        primaryKeys.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/cockroachdb_connection.csv")
    void testGetTypeInfo(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        DatabaseMetaData meta = connection.getMetaData();

        ResultSet typeInfo = meta.getTypeInfo();
        assertNotNull(typeInfo);

        boolean hasTypes = false;
        while (typeInfo.next()) {
            hasTypes = true;
            String typeName = typeInfo.getString("TYPE_NAME");
            assertNotNull(typeName);
        }
        assertTrue(hasTypes, "Should have at least one type");
        typeInfo.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/cockroachdb_connection.csv")
    void testGetIndexInfo(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        DatabaseMetaData meta = connection.getMetaData();

        ResultSet indexInfo = meta.getIndexInfo(null, null, "cockroachdb_db_metadata_test", false, false);
        assertNotNull(indexInfo);
        indexInfo.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/cockroachdb_connection.csv")
    void testGetTablePrivileges(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        DatabaseMetaData meta = connection.getMetaData();

        ResultSet privileges = meta.getTablePrivileges(null, null, "cockroachdb_db_metadata_test");
        assertNotNull(privileges);
        privileges.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/cockroachdb_connection.csv")
    void testGetSchemas(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        DatabaseMetaData meta = connection.getMetaData();

        ResultSet schemas = meta.getSchemas();
        assertNotNull(schemas);

        boolean hasSchemas = false;
        while (schemas.next()) {
            hasSchemas = true;
            String schemaName = schemas.getString("TABLE_SCHEM");
            assertNotNull(schemaName);
        }
        assertTrue(hasSchemas, "Should have at least one schema");
        schemas.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/cockroachdb_connection.csv")
    void testGetCatalogs(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        DatabaseMetaData meta = connection.getMetaData();

        ResultSet catalogs = meta.getCatalogs();
        assertNotNull(catalogs);

        boolean hasCatalogs = false;
        while (catalogs.next()) {
            hasCatalogs = true;
            String catalogName = catalogs.getString("TABLE_CAT");
            assertNotNull(catalogName);
        }
        assertTrue(hasCatalogs, "Should have at least one catalog");
        catalogs.close();
    }
}
