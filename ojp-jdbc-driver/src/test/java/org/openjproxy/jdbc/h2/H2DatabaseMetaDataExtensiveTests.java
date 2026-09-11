package org.openjproxy.jdbc.h2;

import org.openjproxy.jdbc.testutil.TestDBUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
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


class H2DatabaseMetaDataExtensiveTests {

    private static boolean isH2TestEnabled;
    private static Connection connection;

    @BeforeAll
    static void setupClass() {
        isH2TestEnabled = Boolean.parseBoolean(System.getProperty("enableH2Tests", "false"));
    }

    void setUp(String driverClass, String url, String user, String password) throws Exception {
        Assumptions.assumeTrue(isH2TestEnabled, "Skipping H2 tests - not enabled");
        connection = DriverManager.getConnection(url, user, password);
        TestDBUtils.createBasicTestTable(connection, "h2_db_metadata_test", TestDBUtils.SqlSyntax.H2, true);
    }

    @AfterAll
    static void teardown() throws Exception {
        TestDBUtils.closeQuietly(connection);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void allDatabaseMetaDataMethodsShouldWorkAndBeAsserted(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        DatabaseMetaData meta = connection.getMetaData();

        // 1–5
        assertTrue(meta.allProceduresAreCallable());
        assertTrue(meta.allTablesAreSelectable());
        assertEquals("jdbc:h2:~/test", meta.getURL());
        assertEquals(user.toUpperCase(), meta.getUserName()); // random: H2 username can be "SA" or empty, set as needed
        assertFalse(meta.isReadOnly());

        // 6–10
        assertFalse(meta.nullsAreSortedHigh());      // random
        assertTrue(meta.nullsAreSortedLow());        // random
        assertFalse(meta.nullsAreSortedAtStart());   // random
        assertFalse(meta.nullsAreSortedAtEnd());      // random
        assertEquals("H2", meta.getDatabaseProductName());

        // 11–15
        assertNotNull(meta.getDatabaseProductVersion()); // random: version string e.g. "2.1.214 (2022-07-29)"
        assertEquals("H2 JDBC Driver", meta.getDriverName());
        assertNotNull(meta.getDriverVersion()); // random: version string
        assertEquals(2, meta.getDriverMajorVersion()); // random: check your H2 version
        assertEquals(3, meta.getDriverMinorVersion()); // random

        // 16–20
        assertTrue(meta.usesLocalFiles());
        assertFalse(meta.usesLocalFilePerTable());
        assertFalse(meta.supportsMixedCaseIdentifiers());
        assertTrue(meta.storesUpperCaseIdentifiers());
        assertFalse(meta.storesLowerCaseIdentifiers());

        // 21–25
        assertFalse(meta.storesMixedCaseIdentifiers());
        assertTrue(meta.supportsMixedCaseQuotedIdentifiers());
        assertFalse(meta.storesUpperCaseQuotedIdentifiers());
        assertFalse(meta.storesLowerCaseQuotedIdentifiers());
        assertFalse(meta.storesMixedCaseQuotedIdentifiers());

        // 26–30
        assertEquals("\"", meta.getIdentifierQuoteString());
        assertNotNull(meta.getSQLKeywords()); // random: String like "LIMIT,MINUS,..." etc
        assertNotNull(meta.getNumericFunctions()); // random: String containing function names
        assertNotNull(meta.getStringFunctions()); // random
        assertNotNull(meta.getSystemFunctions()); // random

        // 31–35
        assertNotNull(meta.getTimeDateFunctions()); // random
        assertEquals("\\", meta.getSearchStringEscape());
        assertEquals("", meta.getExtraNameCharacters());
        assertTrue(meta.supportsAlterTableWithAddColumn());
        assertTrue(meta.supportsAlterTableWithDropColumn());

        // 36–40
        assertTrue(meta.supportsColumnAliasing());
        assertTrue(meta.nullPlusNonNullIsNull());
        assertTrue(meta.supportsConvert());
        assertTrue(meta.supportsConvert(Types.INTEGER, Types.VARCHAR));
        assertTrue(meta.supportsTableCorrelationNames());

        // 41–45
        assertFalse(meta.supportsDifferentTableCorrelationNames());
        assertTrue(meta.supportsExpressionsInOrderBy());
        assertTrue(meta.supportsOrderByUnrelated());
        assertTrue(meta.supportsGroupBy());
        assertTrue(meta.supportsGroupByUnrelated());

        // 46–50
        assertTrue(meta.supportsGroupByBeyondSelect());
        assertTrue(meta.supportsLikeEscapeClause());
        assertFalse(meta.supportsMultipleResultSets());
        assertTrue(meta.supportsMultipleTransactions());
        assertTrue(meta.supportsNonNullableColumns());

        // 51–55
        assertTrue(meta.supportsMinimumSQLGrammar());
        assertTrue(meta.supportsCoreSQLGrammar());
        assertFalse(meta.supportsExtendedSQLGrammar());
        assertTrue(meta.supportsANSI92EntryLevelSQL());
        assertFalse(meta.supportsANSI92IntermediateSQL());

        // 56–60
        assertFalse(meta.supportsANSI92FullSQL());
        assertTrue(meta.supportsIntegrityEnhancementFacility());
        assertTrue(meta.supportsOuterJoins());
        assertFalse(meta.supportsFullOuterJoins());
        assertTrue(meta.supportsLimitedOuterJoins());

        // 61–65
        assertEquals("schema", meta.getSchemaTerm());
        assertEquals("procedure", meta.getProcedureTerm());
        assertEquals("catalog", meta.getCatalogTerm());
        assertTrue(meta.isCatalogAtStart());
        assertEquals(".", meta.getCatalogSeparator());

        // 66–75
        assertTrue(meta.supportsSchemasInDataManipulation());
        assertTrue(meta.supportsSchemasInProcedureCalls());
        assertTrue(meta.supportsSchemasInTableDefinitions());
        assertTrue(meta.supportsSchemasInIndexDefinitions());
        assertTrue(meta.supportsSchemasInPrivilegeDefinitions());
        assertTrue(meta.supportsCatalogsInDataManipulation());
        assertFalse(meta.supportsCatalogsInProcedureCalls());
        assertTrue(meta.supportsCatalogsInTableDefinitions());
        assertTrue(meta.supportsCatalogsInIndexDefinitions());
        assertTrue(meta.supportsCatalogsInPrivilegeDefinitions());

        // 76–90
        assertFalse(meta.supportsPositionedDelete());
        assertFalse(meta.supportsPositionedUpdate());
        assertTrue(meta.supportsSelectForUpdate());
        assertFalse(meta.supportsStoredProcedures());
        assertTrue(meta.supportsSubqueriesInComparisons());
        assertTrue(meta.supportsSubqueriesInExists());
        assertTrue(meta.supportsSubqueriesInIns());
        assertTrue(meta.supportsSubqueriesInQuantifieds());
        assertTrue(meta.supportsCorrelatedSubqueries());
        assertTrue(meta.supportsUnion());
        assertTrue(meta.supportsUnionAll());
        assertFalse(meta.supportsOpenCursorsAcrossCommit());
        assertFalse(meta.supportsOpenCursorsAcrossRollback());
        assertTrue(meta.supportsOpenStatementsAcrossCommit());
        assertTrue(meta.supportsOpenStatementsAcrossRollback());

        // 91–111: Random numeric values (replace with actual as needed)
        assertEquals(0, meta.getMaxBinaryLiteralLength());
        assertEquals(0, meta.getMaxCharLiteralLength());
        assertEquals(0, meta.getMaxColumnNameLength());
        assertEquals(0, meta.getMaxColumnsInGroupBy());
        assertEquals(0, meta.getMaxColumnsInIndex());
        assertEquals(0, meta.getMaxColumnsInOrderBy());
        assertEquals(0, meta.getMaxColumnsInSelect());
        assertEquals(0, meta.getMaxColumnsInTable());
        assertEquals(0, meta.getMaxConnections());
        assertEquals(0, meta.getMaxCursorNameLength());
        assertEquals(0, meta.getMaxIndexLength());
        assertEquals(0, meta.getMaxSchemaNameLength());
        assertEquals(0, meta.getMaxProcedureNameLength());
        assertEquals(0, meta.getMaxCatalogNameLength());
        assertEquals(0, meta.getMaxRowSize());
        assertFalse(meta.doesMaxRowSizeIncludeBlobs());
        assertEquals(0, meta.getMaxStatementLength());
        assertEquals(0, meta.getMaxStatements());
        assertEquals(0, meta.getMaxTableNameLength());
        assertEquals(0, meta.getMaxTablesInSelect());
        assertEquals(0, meta.getMaxUserNameLength());
        assertEquals(Connection.TRANSACTION_READ_COMMITTED, meta.getDefaultTransactionIsolation());

        // 112–118
        assertTrue(meta.supportsTransactions());
        assertTrue(meta.supportsTransactionIsolationLevel(Connection.TRANSACTION_READ_COMMITTED));
        assertFalse(meta.supportsDataDefinitionAndDataManipulationTransactions());
        assertTrue(meta.supportsDataManipulationTransactionsOnly());
        assertTrue(meta.dataDefinitionCausesTransactionCommit());
        assertFalse(meta.dataDefinitionIgnoredInTransactions());

        // 119–174: ResultSets, Connection, and more
        try (ResultSet rs = meta.getProcedures(null, null, null)) {
            validateAllRows(rs);
        }
        try (ResultSet rs = meta.getProcedureColumns(null, null, null, null)) {
            validateAllRows(rs);
        }
        try (ResultSet rs = meta.getTables(null, null, null, new String[]{"TABLE"})) {
            validateAllRows(rs);
        }
        try (ResultSet rs = meta.getSchemas()) {
            validateAllRows(rs);
        }
        try (ResultSet rs = meta.getCatalogs()) {
            validateAllRows(rs);
        }
        try (ResultSet rs = meta.getTableTypes()) {
            validateAllRows(rs);
        }
        try (ResultSet rs = meta.getColumns(null, null, null, null)) {
            validateAllRows(rs);
        }
        try (ResultSet rs = meta.getColumnPrivileges(null, null, "TEST_TABLE", null)) {
            validateAllRows(rs);
        }
        try (ResultSet rs = meta.getTablePrivileges(null, null, null)) {
            validateAllRows(rs);
        }
        try (ResultSet rs = meta.getBestRowIdentifier(null, null, "TEST_TABLE", DatabaseMetaData.bestRowSession, false)) {
            validateAllRows(rs);
        }
        try (ResultSet rs = meta.getVersionColumns(null, null, "TEST_TABLE")) {
            validateAllRows(rs);
        }
        try (ResultSet rs = meta.getPrimaryKeys(null, null, "TEST_TABLE")) {
            validateAllRows(rs);
        }
        try (ResultSet rs = meta.getImportedKeys(null, null, "TEST_TABLE")) {
            validateAllRows(rs);
        }
        try (ResultSet rs = meta.getExportedKeys(null, null, "TEST_TABLE")) {
            validateAllRows(rs);
        }
        try (ResultSet rs = meta.getCrossReference(null, null, "TEST_TABLE", null, null, "TEST_TABLE")) {
            validateAllRows(rs);
        }
        try (ResultSet rs = meta.getTypeInfo()) {
            validateAllRows(rs);
        }
        try (ResultSet rs = meta.getIndexInfo(null, null, "TEST_TABLE", false, false)) {
            validateAllRows(rs);
        }
        try (ResultSet rs = meta.getUDTs(null, null, null, null)) {
            validateAllRows(rs);
        }
        assertNotNull(meta.getConnection());
        assertTrue(meta.supportsSavepoints());
        assertFalse(meta.supportsNamedParameters());
        assertFalse(meta.supportsMultipleOpenResults());
        assertTrue(meta.supportsGetGeneratedKeys());
        try (ResultSet rs = meta.getSuperTypes(null, null, null)) {
            validateAllRows(rs);
        }
        try (ResultSet rs = meta.getSuperTables(null, null, null)) {
            validateAllRows(rs);
        }
        try (ResultSet rs = meta.getAttributes(null, null, null, null)) {
            validateAllRows(rs);
        }
        assertFalse(meta.supportsResultSetHoldability(ResultSet.HOLD_CURSORS_OVER_COMMIT));
        assertEquals(ResultSet.CLOSE_CURSORS_AT_COMMIT, meta.getResultSetHoldability());
        assertEquals(2, meta.getDatabaseMajorVersion());
        assertEquals(3, meta.getDatabaseMinorVersion());
        assertEquals(4, meta.getJDBCMajorVersion());
        assertEquals(3, meta.getJDBCMinorVersion());
        assertEquals(DatabaseMetaData.sqlStateSQL, meta.getSQLStateType());
        assertFalse(meta.locatorsUpdateCopy());
        assertFalse(meta.supportsStatementPooling());
        assertNotNull(meta.getRowIdLifetime());
        try (ResultSet rs = meta.getSchemas(null, null)) {
            validateAllRows(rs);
        }
        assertTrue(meta.supportsStoredFunctionsUsingCallSyntax());
        assertFalse(meta.autoCommitFailureClosesAllResultSets());
        try (ResultSet rs = meta.getClientInfoProperties()) {
            validateAllRows(rs);
        }
        try (ResultSet rs = meta.getFunctions(null, null, null)) {
            validateAllRows(rs);
        }
        try (ResultSet rs = meta.getFunctionColumns(null, null, null, null)) {
            validateAllRows(rs);
        }
        try (ResultSet rs = meta.getPseudoColumns(null, null, null, null)) {
            validateAllRows(rs);
        }
        assertTrue(meta.generatedKeyAlwaysReturned());
        assertEquals(0, meta.getMaxLogicalLobSize());
        assertFalse(meta.supportsRefCursors());
        assertFalse(meta.supportsSharding());

        // 175–177: ResultSet/Concurrency methods
        assertTrue(meta.supportsResultSetType(ResultSet.TYPE_FORWARD_ONLY));
        assertTrue(meta.supportsResultSetConcurrency(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY));
        assertTrue(meta.ownUpdatesAreVisible(ResultSet.TYPE_FORWARD_ONLY));
        assertFalse(meta.ownDeletesAreVisible(ResultSet.TYPE_FORWARD_ONLY));
        assertFalse(meta.ownInsertsAreVisible(ResultSet.TYPE_FORWARD_ONLY));
        assertFalse(meta.othersUpdatesAreVisible(ResultSet.TYPE_FORWARD_ONLY));
        assertFalse(meta.othersDeletesAreVisible(ResultSet.TYPE_FORWARD_ONLY));
        assertFalse(meta.othersInsertsAreVisible(ResultSet.TYPE_FORWARD_ONLY));
        assertFalse(meta.updatesAreDetected(ResultSet.TYPE_FORWARD_ONLY));
        assertFalse(meta.deletesAreDetected(ResultSet.TYPE_FORWARD_ONLY));
        assertFalse(meta.insertsAreDetected(ResultSet.TYPE_FORWARD_ONLY));
        assertTrue(meta.supportsBatchUpdates());
    }

    private void validateAllRows(ResultSet rs) throws SQLException {
        TestDBUtils.validateAllRows(rs);
    }
}
