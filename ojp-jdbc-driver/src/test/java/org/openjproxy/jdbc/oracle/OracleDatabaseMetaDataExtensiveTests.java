package org.openjproxy.jdbc.oracle;

import org.openjproxy.jdbc.testutil.TestDBUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.RowIdLifetime;
import java.sql.SQLException;
import java.sql.Types;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

public class OracleDatabaseMetaDataExtensiveTests {

    private static boolean isTestDisabled;
    private static Connection connection;

    @BeforeAll
    static void checkTestConfiguration() {
        isTestDisabled = !Boolean.parseBoolean(System.getProperty("enableOracleTests", "false"));
    }

    public void setUp(String driverClass, String url, String user, String password) throws Exception {
        assumeFalse(isTestDisabled, "Oracle tests are disabled");
        
        connection = DriverManager.getConnection(url, user, password);
        TestDBUtils.createBasicTestTable(connection, "oracle_db_metadata_test", TestDBUtils.SqlSyntax.ORACLE, true);
    }

    @AfterAll
    static void teardown() throws Exception {
        TestDBUtils.closeQuietly(connection);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void allDatabaseMetaDataMethodsShouldWorkAndBeAsserted(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        DatabaseMetaData meta = connection.getMetaData();

        // 1–5: Basic database information (Oracle-specific values)
        assertFalse( meta.allProceduresAreCallable());
        assertFalse( meta.allTablesAreSelectable());
        assertTrue(meta.getURL().contains("oracle") || meta.getURL().contains(":1521/"));
        assertNotNull(meta.getUserName()); // Oracle username
        assertFalse( meta.isReadOnly());

        // 6–10: Null handling and database product info (Oracle-specific behaviors)
        assertTrue( meta.nullsAreSortedHigh());  // Oracle behavior
        assertFalse( meta.nullsAreSortedLow());
        assertFalse( meta.nullsAreSortedAtStart());
        assertFalse( meta.nullsAreSortedAtEnd()); // Oracle behavior
        assertEquals("Oracle", meta.getDatabaseProductName());

        // 11–15: Version information
        assertNotNull(meta.getDatabaseProductVersion());
        assertEquals("Oracle JDBC driver", meta.getDriverName());
        assertNotNull(meta.getDriverVersion());
        assertTrue(meta.getDriverMajorVersion() >= 21); // Oracle driver version
        assertTrue(meta.getDriverMinorVersion() >= 0);

        // 16–20: File handling and identifiers
        assertFalse( meta.usesLocalFiles());
        assertFalse( meta.usesLocalFilePerTable());
        assertFalse( meta.supportsMixedCaseIdentifiers());
        assertTrue( meta.storesUpperCaseIdentifiers()); // Oracle stores uppercase
        assertFalse( meta.storesLowerCaseIdentifiers()); // Oracle stores uppercase

        // 21–25: Quoted identifiers
        assertFalse( meta.storesMixedCaseIdentifiers());
        assertTrue( meta.supportsMixedCaseQuotedIdentifiers());
        assertFalse( meta.storesUpperCaseQuotedIdentifiers());
        assertFalse( meta.storesLowerCaseQuotedIdentifiers());
        assertTrue( meta.storesMixedCaseQuotedIdentifiers()); // Oracle behavior

        // 26–30: String handling and functions
        assertEquals("\"", meta.getIdentifierQuoteString());
        assertNotNull(meta.getSQLKeywords());
        assertNotNull(meta.getNumericFunctions());
        assertNotNull(meta.getStringFunctions());
        assertNotNull(meta.getSystemFunctions());

        // 31–35: More functions and table operations
        assertNotNull(meta.getTimeDateFunctions());
        assertEquals("/", meta.getSearchStringEscape());
        // Oracle may have extra name characters
        String extraChars = meta.getExtraNameCharacters();
        assertNotNull(extraChars); // Accept any non-null value
        assertTrue( meta.supportsAlterTableWithAddColumn());
        assertFalse( meta.supportsAlterTableWithDropColumn());

        // 36–40: Query features
        assertTrue( meta.supportsColumnAliasing());
        assertTrue( meta.nullPlusNonNullIsNull());
        assertFalse( meta.supportsConvert()); // Oracle behavior differs from PostgreSQL
        assertFalse( meta.supportsConvert(Types.INTEGER, Types.VARCHAR)); // Oracle behavior
        assertTrue( meta.supportsTableCorrelationNames());

        // 41–45: More query features
        assertTrue( meta.supportsDifferentTableCorrelationNames());
        assertTrue( meta.supportsExpressionsInOrderBy());
        assertTrue( meta.supportsOrderByUnrelated());
        assertTrue( meta.supportsGroupBy());
        assertTrue( meta.supportsGroupByUnrelated());

        // 46–50: Advanced query features
        assertTrue( meta.supportsGroupByBeyondSelect());
        assertTrue( meta.supportsLikeEscapeClause());
        assertFalse( meta.supportsMultipleResultSets()); // Oracle supports multiple result sets
        assertTrue( meta.supportsMultipleTransactions());
        assertTrue( meta.supportsNonNullableColumns());

        // 51–55: SQL grammar support
        assertTrue( meta.supportsMinimumSQLGrammar());
        assertTrue( meta.supportsCoreSQLGrammar());
        assertTrue( meta.supportsExtendedSQLGrammar());
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
        assertEquals("procedure", meta.getProcedureTerm()); // Oracle uses procedures
        assertEquals("", meta.getCatalogTerm());
        assertFalse( meta.isCatalogAtStart());
        assertEquals("", meta.getCatalogSeparator());

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
        assertFalse( meta.supportsOpenStatementsAcrossCommit());
        assertFalse( meta.supportsOpenStatementsAcrossRollback());

        // 91–111: Limits (Oracle-specific limits)
        assertEquals(1000, meta.getMaxBinaryLiteralLength());
        assertEquals(2000, meta.getMaxCharLiteralLength()); // Oracle VARCHAR2 limit
        assertEquals(128, meta.getMaxColumnNameLength()); // Oracle identifier limit
        assertEquals(0, meta.getMaxColumnsInGroupBy());
        assertEquals(32, meta.getMaxColumnsInIndex()); // Oracle index column limit
        assertEquals(0, meta.getMaxColumnsInOrderBy());
        assertEquals(0, meta.getMaxColumnsInSelect()); // Oracle column limit
        assertEquals(1000, meta.getMaxColumnsInTable());
        assertEquals(0, meta.getMaxConnections());
        assertEquals(0, meta.getMaxCursorNameLength());
        assertEquals(0, meta.getMaxIndexLength());
        assertEquals(128, meta.getMaxSchemaNameLength());
        assertEquals(128, meta.getMaxProcedureNameLength());
        assertEquals(0, meta.getMaxCatalogNameLength());
        assertEquals(0, meta.getMaxRowSize());
        assertTrue( meta.doesMaxRowSizeIncludeBlobs());
        assertEquals(65535, meta.getMaxStatementLength());
        assertEquals(0, meta.getMaxStatements());
        assertEquals(128, meta.getMaxTableNameLength());
        assertEquals(0, meta.getMaxTablesInSelect());
        assertEquals(128, meta.getMaxUserNameLength());
        assertEquals(Connection.TRANSACTION_READ_COMMITTED, meta.getDefaultTransactionIsolation());

        // 112–118: Transaction support
        assertTrue( meta.supportsTransactions());
        assertTrue( meta.supportsTransactionIsolationLevel(Connection.TRANSACTION_READ_COMMITTED));
        assertTrue( meta.supportsDataDefinitionAndDataManipulationTransactions());
        assertTrue( meta.supportsDataManipulationTransactionsOnly());
        assertTrue( meta.dataDefinitionCausesTransactionCommit());
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
        try (ResultSet rs = meta.getColumnPrivileges(null, null, "ORACLE_DB_METADATA_TEST", null)) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getTablePrivileges(null, null, null)) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getBestRowIdentifier(null, null, "ORACLE_DB_METADATA_TEST", DatabaseMetaData.bestRowSession, false)) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getVersionColumns(null, null, "ORACLE_DB_METADATA_TEST")) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getPrimaryKeys(null, null, "ORACLE_DB_METADATA_TEST")) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getImportedKeys(null, null, "ORACLE_DB_METADATA_TEST")) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getExportedKeys(null, null, "ORACLE_DB_METADATA_TEST")) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getCrossReference(null, null, "ORACLE_DB_METADATA_TEST", null, null, "ORACLE_DB_METADATA_TEST")) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getTypeInfo()) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getIndexInfo(null, null, "ORACLE_DB_METADATA_TEST", false, false)) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getUDTs(null, null, null, null)) {
            TestDBUtils.validateAllRows(rs);
        }
        assertNotNull(meta.getConnection());
        assertTrue( meta.supportsSavepoints());
        assertTrue( meta.supportsNamedParameters());
        assertFalse( meta.supportsMultipleOpenResults());
        assertTrue( meta.supportsGetGeneratedKeys());

        assertEquals(ResultSet.HOLD_CURSORS_OVER_COMMIT, meta.getResultSetHoldability());
        assertTrue(meta.getDatabaseMajorVersion() >= 18); // Modern Oracle
        assertTrue(meta.getDatabaseMinorVersion() >= 0);
        assertEquals(4, meta.getJDBCMajorVersion());
        assertTrue(meta.getJDBCMinorVersion() >= 2);
        assertEquals(DatabaseMetaData.functionColumnUnknown, meta.getSQLStateType());
        assertTrue( meta.locatorsUpdateCopy());
        assertTrue( meta.supportsStatementPooling());

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
        assertFalse( meta.generatedKeyAlwaysReturned());
        assertTrue( meta.supportsRefCursors());
        assertTrue( meta.supportsSharding());

        // 175–177: ResultSet/Concurrency methods
        assertTrue( meta.supportsResultSetType(ResultSet.TYPE_FORWARD_ONLY));
        assertTrue( meta.supportsResultSetConcurrency(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY));
        assertFalse( meta.ownUpdatesAreVisible(ResultSet.TYPE_FORWARD_ONLY));
        assertFalse( meta.ownDeletesAreVisible(ResultSet.TYPE_FORWARD_ONLY));
        assertFalse( meta.ownInsertsAreVisible(ResultSet.TYPE_FORWARD_ONLY));
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

        assertEquals(RowIdLifetime.ROWID_VALID_FOREVER, meta.getRowIdLifetime());
        try (ResultSet rs = meta.getPseudoColumns(null, null, null, null)) {
            TestDBUtils.validateAllRows(rs);
        }
    }
}