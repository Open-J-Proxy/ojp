package org.openjproxy.jdbc.postgres;

import org.openjproxy.jdbc.testutil.TestDBUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

public class PostgresDatabaseMetaDataExtensiveTests {

    private static boolean isTestEnabled;
    private static Connection connection;

    @BeforeAll
    static void checkTestConfiguration() {
        isTestEnabled = Boolean.parseBoolean(System.getProperty("enablePostgresTests", "false"));
    }

    public void setUp(String driverClass, String url, String user, String password) throws Exception {
        assumeFalse(!isTestEnabled, "Postgres tests are disabled");
        
        connection = DriverManager.getConnection(url, user, password);
        TestDBUtils.createBasicTestTable(connection, "postgres_db_metadata_test", TestDBUtils.SqlSyntax.POSTGRES, true);
    }

    @AfterAll
    static void teardown() throws Exception {
        TestDBUtils.closeQuietly(connection);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    void allDatabaseMetaDataMethodsShouldWorkAndBeAsserted(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        DatabaseMetaData meta = connection.getMetaData();

        // 1–5: Basic database information (PostgreSQL-specific values)
        assertTrue( meta.allProceduresAreCallable());
        assertTrue( meta.allTablesAreSelectable());
        assertTrue(meta.getURL().contains("postgresql") || meta.getURL().contains(":5432/"));
        assertNotNull(meta.getUserName()); // PostgreSQL username
        assertFalse( meta.isReadOnly());

        // 6–10: Null handling and database product info (PostgreSQL-specific behaviors)
        assertTrue( meta.nullsAreSortedHigh());  // PostgreSQL behavior
        assertFalse( meta.nullsAreSortedLow());
        assertFalse( meta.nullsAreSortedAtStart());
        assertFalse( meta.nullsAreSortedAtEnd()); // PostgreSQL behavior
        assertEquals("PostgreSQL", meta.getDatabaseProductName());

        // 11–15: Version information
        assertNotNull(meta.getDatabaseProductVersion());
        assertEquals("PostgreSQL JDBC Driver", meta.getDriverName());
        assertNotNull(meta.getDriverVersion());
        assertTrue(meta.getDriverMajorVersion() >= 42); // PostgreSQL driver version
        assertTrue(meta.getDriverMinorVersion() >= 0);

        // 16–20: File handling and identifiers
        assertFalse( meta.usesLocalFiles());
        assertFalse( meta.usesLocalFilePerTable());
        assertFalse( meta.supportsMixedCaseIdentifiers());
        assertFalse( meta.storesUpperCaseIdentifiers());
        assertTrue( meta.storesLowerCaseIdentifiers()); // PostgreSQL stores lowercase

        // 21–25: Quoted identifiers
        assertFalse( meta.storesMixedCaseIdentifiers());
        assertTrue( meta.supportsMixedCaseQuotedIdentifiers());
        assertFalse( meta.storesUpperCaseQuotedIdentifiers());
        assertFalse( meta.storesLowerCaseQuotedIdentifiers());
        assertFalse( meta.storesMixedCaseQuotedIdentifiers()); // PostgreSQL behavior

        // 26–30: String handling and functions
        assertEquals("\"", meta.getIdentifierQuoteString());
        assertNotNull(meta.getSQLKeywords());
        assertNotNull(meta.getNumericFunctions());
        assertNotNull(meta.getStringFunctions());
        assertNotNull(meta.getSystemFunctions());

        // 31–35: More functions and table operations
        assertNotNull(meta.getTimeDateFunctions());
        assertEquals("\\", meta.getSearchStringEscape());
        // PostgreSQL may not allow extra name characters beyond standard ones
        String extraChars = meta.getExtraNameCharacters();
        assertNotNull(extraChars); // Accept any non-null value
        assertTrue( meta.supportsAlterTableWithAddColumn());
        assertTrue( meta.supportsAlterTableWithDropColumn());

        // 36–40: Query features
        assertTrue( meta.supportsColumnAliasing());
        assertTrue( meta.nullPlusNonNullIsNull());
        assertFalse( meta.supportsConvert()); // PostgreSQL behavior differs from H2
        assertFalse( meta.supportsConvert(Types.INTEGER, Types.VARCHAR)); // PostgreSQL behavior
        assertTrue( meta.supportsTableCorrelationNames());

        // 41–45: More query features
        assertFalse( meta.supportsDifferentTableCorrelationNames());
        assertTrue( meta.supportsExpressionsInOrderBy());
        assertTrue( meta.supportsOrderByUnrelated());
        assertTrue( meta.supportsGroupBy());
        assertTrue( meta.supportsGroupByUnrelated());

        // 46–50: Advanced query features
        assertTrue( meta.supportsGroupByBeyondSelect());
        assertTrue( meta.supportsLikeEscapeClause());
        assertTrue( meta.supportsMultipleResultSets()); // PostgreSQL supports multiple result sets
        assertTrue( meta.supportsMultipleTransactions());
        assertTrue( meta.supportsNonNullableColumns());

        // 51–55: SQL grammar support
        assertTrue( meta.supportsMinimumSQLGrammar());
        assertFalse( meta.supportsCoreSQLGrammar());
        assertFalse( meta.supportsExtendedSQLGrammar());
        assertTrue( meta.supportsANSI92EntryLevelSQL());
        assertFalse( meta.supportsANSI92IntermediateSQL());

        // 56–60: Advanced SQL and joins
        assertFalse( meta.supportsANSI92FullSQL());
        assertTrue( meta.supportsIntegrityEnhancementFacility());
        assertTrue( meta.supportsOuterJoins());
        assertTrue( meta.supportsFullOuterJoins());
        assertTrue( meta.supportsLimitedOuterJoins());

        // 61–65: Schema and catalog terminology
        assertEquals("schema", meta.getSchemaTerm());
        assertEquals("function", meta.getProcedureTerm()); // PostgreSQL uses functions
        assertEquals("database", meta.getCatalogTerm());
        assertTrue( meta.isCatalogAtStart());
        assertEquals(".", meta.getCatalogSeparator());

        // 66–75: Schema and catalog support
        assertTrue( meta.supportsSchemasInDataManipulation());
        assertTrue( meta.supportsSchemasInProcedureCalls());
        assertTrue( meta.supportsSchemasInTableDefinitions());
        assertTrue( meta.supportsSchemasInIndexDefinitions());
        assertTrue( meta.supportsSchemasInPrivilegeDefinitions());
        assertFalse( meta.supportsCatalogsInDataManipulation());
        assertFalse( meta.supportsCatalogsInProcedureCalls());
        assertFalse( meta.supportsCatalogsInTableDefinitions());
        assertFalse( meta.supportsCatalogsInIndexDefinitions());
        assertFalse( meta.supportsCatalogsInPrivilegeDefinitions());

        // 76–90: Cursor and subquery support
        assertFalse( meta.supportsPositionedDelete());
        assertFalse( meta.supportsPositionedUpdate());
        assertTrue( meta.supportsSelectForUpdate());
        assertTrue( meta.supportsStoredProcedures());
        assertTrue( meta.supportsSubqueriesInComparisons());
        assertTrue( meta.supportsSubqueriesInExists());
        assertTrue( meta.supportsSubqueriesInIns());
        assertTrue( meta.supportsSubqueriesInQuantifieds());
        assertTrue( meta.supportsCorrelatedSubqueries());
        assertTrue( meta.supportsUnion());
        assertTrue( meta.supportsUnionAll());
        assertFalse( meta.supportsOpenCursorsAcrossCommit());
        assertFalse( meta.supportsOpenCursorsAcrossRollback());
        assertTrue( meta.supportsOpenStatementsAcrossCommit());
        assertTrue( meta.supportsOpenStatementsAcrossRollback());

        // 91–111: Limits (PostgreSQL typically has no limits or very high limits)
        assertEquals(0, meta.getMaxBinaryLiteralLength());
        assertEquals(0, meta.getMaxCharLiteralLength());
        assertEquals(63, meta.getMaxColumnNameLength()); // PostgreSQL identifier limit
        assertEquals(0, meta.getMaxColumnsInGroupBy());
        assertEquals(32, meta.getMaxColumnsInIndex()); // PostgreSQL index column limit
        assertEquals(0, meta.getMaxColumnsInOrderBy());
        assertEquals(0, meta.getMaxColumnsInSelect()); // PostgreSQL column limit
        assertEquals(1600, meta.getMaxColumnsInTable());
        assertEquals(8192, meta.getMaxConnections());
        assertEquals(63, meta.getMaxCursorNameLength());
        assertEquals(0, meta.getMaxIndexLength());
        assertEquals(63, meta.getMaxSchemaNameLength());
        assertEquals(63, meta.getMaxProcedureNameLength());
        assertEquals(63, meta.getMaxCatalogNameLength());
        assertEquals(1073741824, meta.getMaxRowSize());
        assertFalse( meta.doesMaxRowSizeIncludeBlobs());
        assertEquals(0, meta.getMaxStatementLength());
        assertEquals(0, meta.getMaxStatements());
        assertEquals(63, meta.getMaxTableNameLength());
        assertEquals(0, meta.getMaxTablesInSelect());
        assertEquals(63, meta.getMaxUserNameLength());
        assertEquals(Connection.TRANSACTION_READ_COMMITTED, meta.getDefaultTransactionIsolation());

        // 112–118: Transaction support
        assertTrue( meta.supportsTransactions());
        assertTrue( meta.supportsTransactionIsolationLevel(Connection.TRANSACTION_READ_COMMITTED));
        assertTrue( meta.supportsDataDefinitionAndDataManipulationTransactions());
        assertFalse( meta.supportsDataManipulationTransactionsOnly());
        assertFalse( meta.dataDefinitionCausesTransactionCommit());
        assertFalse( meta.dataDefinitionIgnoredInTransactions());

        // 119–174: ResultSets for metadata queries
        try (ResultSet rs = meta.getProcedures(null, null, null)) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getProcedureColumns(null, null, null, null)) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getTables(null, null, null, new String[]{"TABLE"})) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getSchemas()) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getCatalogs()) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getTableTypes()) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getColumns(null, null, null, null)) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getColumnPrivileges(null, null, "postgres_db_metadata_test", null)) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getTablePrivileges(null, null, null)) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getBestRowIdentifier(null, null, "postgres_db_metadata_test", DatabaseMetaData.bestRowSession, false)) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getVersionColumns(null, null, "postgres_db_metadata_test")) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getPrimaryKeys(null, null, "postgres_db_metadata_test")) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getImportedKeys(null, null, "postgres_db_metadata_test")) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getExportedKeys(null, null, "postgres_db_metadata_test")) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getCrossReference(null, null, "postgres_db_metadata_test", null, null, "postgres_db_metadata_test")) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getTypeInfo()) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getIndexInfo(null, null, "postgres_db_metadata_test", false, false)) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getUDTs(null, null, null, null)) {
            TestDBUtils.validateAllRows(rs);
        }
        assertNotNull(meta.getConnection());
        assertTrue( meta.supportsSavepoints());
        assertFalse( meta.supportsNamedParameters());
        assertFalse( meta.supportsMultipleOpenResults());
        assertTrue( meta.supportsGetGeneratedKeys());

        assertEquals(ResultSet.HOLD_CURSORS_OVER_COMMIT, meta.getResultSetHoldability());
        assertTrue(meta.getDatabaseMajorVersion() >= 10); // Modern PostgreSQL
        assertTrue(meta.getDatabaseMinorVersion() >= 0);
        assertEquals(4, meta.getJDBCMajorVersion());
        assertTrue(meta.getJDBCMinorVersion() >= 2);
        assertEquals(DatabaseMetaData.sqlStateSQL, meta.getSQLStateType());
        assertTrue( meta.locatorsUpdateCopy());
        assertFalse( meta.supportsStatementPooling());

        try (ResultSet rs = meta.getSchemas(null, null)) {
            TestDBUtils.validateAllRows(rs);
        }
        assertTrue( meta.supportsStoredFunctionsUsingCallSyntax());
        assertFalse( meta.autoCommitFailureClosesAllResultSets());
        try (ResultSet rs = meta.getClientInfoProperties()) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getFunctions(null, null, null)) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getFunctionColumns(null, null, null, null)) {
            TestDBUtils.validateAllRows(rs);
        }
        assertTrue( meta.generatedKeyAlwaysReturned());
        assertEquals(0, meta.getMaxLogicalLobSize());
        assertTrue( meta.supportsRefCursors());
        assertFalse( meta.supportsSharding());

        // 175–177: ResultSet/Concurrency methods
        assertTrue( meta.supportsResultSetType(ResultSet.TYPE_FORWARD_ONLY));
        assertTrue( meta.supportsResultSetConcurrency(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY));
        assertTrue( meta.ownUpdatesAreVisible(ResultSet.TYPE_FORWARD_ONLY));
        assertTrue( meta.ownDeletesAreVisible(ResultSet.TYPE_FORWARD_ONLY));
        assertTrue( meta.ownInsertsAreVisible(ResultSet.TYPE_FORWARD_ONLY));
        assertFalse( meta.othersUpdatesAreVisible(ResultSet.TYPE_FORWARD_ONLY));
        assertFalse( meta.othersDeletesAreVisible(ResultSet.TYPE_FORWARD_ONLY));
        assertFalse( meta.othersInsertsAreVisible(ResultSet.TYPE_FORWARD_ONLY));
        assertFalse( meta.updatesAreDetected(ResultSet.TYPE_FORWARD_ONLY));
        assertFalse( meta.deletesAreDetected(ResultSet.TYPE_FORWARD_ONLY));
        assertFalse( meta.insertsAreDetected(ResultSet.TYPE_FORWARD_ONLY));
        assertTrue( meta.supportsBatchUpdates());

        // These tests has to be at the end as per when using hikariCP the connection will be marked as broken after this operations.
        assertTrue( meta.supportsResultSetHoldability(ResultSet.HOLD_CURSORS_OVER_COMMIT));
        assertThrows(SQLException.class, () -> meta.getSuperTypes(null, null, null));
        assertThrows(SQLException.class, () -> meta.getSuperTables(null, null, null));
        assertThrows(SQLException.class, () -> meta.getAttributes(null, null, null, null));
        assertThrows(SQLException.class, () -> meta.getRowIdLifetime());
        assertThrows(SQLException.class, () -> meta.getPseudoColumns(null, null, null, null));

    }
}