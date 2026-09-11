package org.openjproxy.jdbc;

import com.openjproxy.grpc.CallResourceRequest;
import com.openjproxy.grpc.CallResourceResponse;
import com.openjproxy.grpc.CallType;
import com.openjproxy.grpc.ParameterValue;
import com.openjproxy.grpc.ResourceType;
import com.openjproxy.grpc.SessionInfo;
import com.openjproxy.grpc.TargetCall;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.grpc.ProtoConverter;
import org.openjproxy.grpc.client.StatementService;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.RowIdLifetime;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class DatabaseMetaData implements java.sql.DatabaseMetaData {

    private static final Object NULL_VALUE = new Object();

    private final StatementService statementService;
    private final org.openjproxy.jdbc.Connection connection;
    private final Statement statement;
    private final ConcurrentHashMap<String, Object> metadataCache = new ConcurrentHashMap<>();

    public DatabaseMetaData(SessionInfo session, StatementService statementService,
                            org.openjproxy.jdbc.Connection connection, Statement statement) {
        this.statementService = statementService;
        this.connection = connection;
        this.statement = statement;
    }

    private CallResourceRequest.Builder newCallBuilder() {
        log.debug("newCallBuilder called");
        return CallResourceRequest.newBuilder()
                .setSession(this.connection.getSession())
                .setResourceType(ResourceType.RES_CONNECTION)
                .setResourceUUID(this.connection.getSession().getSessionUUID());
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        log.debug("unwrap: {}", iface);
        throw new SQLException("Unwrap not supported.");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        log.debug("isWrapperFor: {}", iface);
        throw new SQLException("isWrappedFor not supported.");
    }

    @Override
    public boolean allProceduresAreCallable() throws SQLException {
        log.debug("allProceduresAreCallable called");
        return this.retrieveMetadataAttribute(CallType.CALL_ALL, "ProceduresAreCallable", Boolean.class);
    }

    @Override
    public boolean allTablesAreSelectable() throws SQLException {
        log.debug("allTablesAreSelectable called");
        return this.retrieveMetadataAttribute(CallType.CALL_ALL, "TablesAreSelectable", Boolean.class);
    }

    @Override
    public String getURL() throws SQLException {
        log.debug("getURL called");
        return this.retrieveMetadataAttribute(CallType.CALL_GET, "URL", String.class);
    }

    @Override
    public String getUserName() throws SQLException {
        log.debug("getUserName called");
        return this.retrieveMetadataAttribute(CallType.CALL_GET, "UserName", String.class);
    }

    @Override
    public boolean isReadOnly() throws SQLException {
        log.debug("isReadOnly called");
        return this.retrieveMetadataAttribute(CallType.CALL_IS, "ReadOnly", Boolean.class);
    }

    @Override
    public boolean nullsAreSortedHigh() throws SQLException {
        log.debug("nullsAreSortedHigh called");
        return this.retrieveMetadataAttribute(CallType.CALL_NULLS, "AreSortedHigh", Boolean.class);
    }

    @Override
    public boolean nullsAreSortedLow() throws SQLException {
        log.debug("nullsAreSortedLow called");
        return this.retrieveMetadataAttribute(CallType.CALL_NULLS, "AreSortedLow", Boolean.class);
    }

    @Override
    public boolean nullsAreSortedAtStart() throws SQLException {
        log.debug("nullsAreSortedAtStart called");
        return this.retrieveMetadataAttribute(CallType.CALL_NULLS, "AreSortedAtStart", Boolean.class);
    }

    @Override
    public boolean nullsAreSortedAtEnd() throws SQLException {
        log.debug("nullsAreSortedAtEnd called");
        return this.retrieveMetadataAttribute(CallType.CALL_NULLS, "AreSortedAtEnd", Boolean.class);
    }

    @Override
    public String getDatabaseProductName() throws SQLException {
        log.debug("getDatabaseProductName called");
        return this.retrieveMetadataAttribute(CallType.CALL_GET, "DatabaseProductName", String.class);
    }

    @Override
    public String getDatabaseProductVersion() throws SQLException {
        log.debug("getDatabaseProductVersion called");
        return this.retrieveMetadataAttribute(CallType.CALL_GET, "DatabaseProductVersion", String.class);
    }

    @Override
    public String getDriverName() throws SQLException {
        log.debug("getDriverName called");
        return this.retrieveMetadataAttribute(CallType.CALL_GET, "DriverName", String.class);
    }

    @Override
    public String getDriverVersion() throws SQLException {
        log.debug("getDriverVersion called");
        return this.retrieveMetadataAttribute(CallType.CALL_GET, "DriverVersion", String.class);
    }

    @SneakyThrows
    @Override
    public int getDriverMajorVersion() {
        log.debug("getDriverMajorVersion called");
        return this.retrieveMetadataAttribute(CallType.CALL_GET, "DriverMajorVersion", Integer.class);
    }

    @SneakyThrows
    @Override
    public int getDriverMinorVersion() {
        log.debug("getDriverMinorVersion called");
        return this.retrieveMetadataAttribute(CallType.CALL_GET, "DriverMinorVersion", Integer.class);
    }

    @Override
    public boolean usesLocalFiles() throws SQLException {
        log.debug("usesLocalFiles called");
        return this.retrieveMetadataAttribute(CallType.CALL_USES, "LocalFiles", Boolean.class);
    }

    @Override
    public boolean usesLocalFilePerTable() throws SQLException {
        log.debug("usesLocalFilePerTable called");
        return this.retrieveMetadataAttribute(CallType.CALL_USES, "LocalFilePerTable", Boolean.class);
    }

    @Override
    public boolean supportsMixedCaseIdentifiers() throws SQLException {
        log.debug("supportsMixedCaseIdentifiers called");
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "MixedCaseIdentifiers", Boolean.class);
    }

    @Override
    public boolean storesUpperCaseIdentifiers() throws SQLException {
        log.debug("storesUpperCaseIdentifiers called");
        return this.retrieveMetadataAttribute(CallType.CALL_STORES, "UpperCaseIdentifiers", Boolean.class);
    }

    @Override
    public boolean storesLowerCaseIdentifiers() throws SQLException {
        log.debug("storesLowerCaseIdentifiers called");
        return this.retrieveMetadataAttribute(CallType.CALL_STORES, "LowerCaseIdentifiers", Boolean.class);
    }

    @Override
    public boolean storesMixedCaseIdentifiers() throws SQLException {
        log.debug("storesMixedCaseIdentifiers called");
        return this.retrieveMetadataAttribute(CallType.CALL_STORES, "MixedCaseIdentifiers", Boolean.class);
    }

    @Override
    public boolean supportsMixedCaseQuotedIdentifiers() throws SQLException {
        log.debug("supportsMixedCaseQuotedIdentifiers called");
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "MixedCaseQuotedIdentifiers", Boolean.class);
    }

    @Override
    public boolean storesUpperCaseQuotedIdentifiers() throws SQLException {
        log.debug("storesUpperCaseQuotedIdentifiers called");
        return this.retrieveMetadataAttribute(CallType.CALL_STORES, "UpperCaseQuotedIdentifiers", Boolean.class);
    }

    @Override
    public boolean storesLowerCaseQuotedIdentifiers() throws SQLException {
        log.debug("storesLowerCaseQuotedIdentifiers called");
        return this.retrieveMetadataAttribute(CallType.CALL_STORES, "LowerCaseQuotedIdentifiers", Boolean.class);
    }

    @Override
    public boolean storesMixedCaseQuotedIdentifiers() throws SQLException {
        log.debug("storesMixedCaseQuotedIdentifiers called");
        return this.retrieveMetadataAttribute(CallType.CALL_STORES, "MixedCaseQuotedIdentifiers", Boolean.class);
    }

    @Override
    public String getIdentifierQuoteString() throws SQLException {
        log.debug("getIdentifierQuoteString called");
        return this.retrieveMetadataAttribute(CallType.CALL_GET, "IdentifierQuoteString", String.class);
    }

    @Override
    public String getSQLKeywords() throws SQLException {
        log.debug("getSQLKeywords called");
        return this.retrieveMetadataAttribute(CallType.CALL_GET, "SQLKeywords", String.class);
    }

    @Override
    public String getNumericFunctions() throws SQLException {
        log.debug("getNumericFunctions called");
        return this.retrieveMetadataAttribute(CallType.CALL_GET, "NumericFunctions", String.class);
    }

    @Override
    public String getStringFunctions() throws SQLException {
        log.debug("getStringFunctions called");
        return this.retrieveMetadataAttribute(CallType.CALL_GET, "StringFunctions", String.class);
    }

    @Override
    public String getSystemFunctions() throws SQLException {
        log.debug("getSystemFunctions called");
        return this.retrieveMetadataAttribute(CallType.CALL_GET, "SystemFunctions", String.class);
    }

    @Override
    public String getTimeDateFunctions() throws SQLException {
        log.debug("getTimeDateFunctions called");
        return this.retrieveMetadataAttribute(CallType.CALL_GET, "TimeDateFunctions", String.class);
    }

    @Override
    public String getSearchStringEscape() throws SQLException {
        log.debug("getSearchStringEscape called");
        return this.retrieveMetadataAttribute(CallType.CALL_GET, "SearchStringEscape", String.class);
    }

    @Override
    public String getExtraNameCharacters() throws SQLException {
        log.debug("getExtraNameCharacters called");
        return this.retrieveMetadataAttribute(CallType.CALL_GET, "ExtraNameCharacters", String.class);
    }

    @Override
    public boolean supportsAlterTableWithAddColumn() throws SQLException {
        log.debug("supportsAlterTableWithAddColumn called");
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "AlterTableWithAddColumn", Boolean.class);
    }

    @Override
    public boolean supportsAlterTableWithDropColumn() throws SQLException {
        log.debug("supportsAlterTableWithDropColumn called");
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "AlterTableWithDropColumn", Boolean.class);
    }

    @Override
    public boolean supportsColumnAliasing() throws SQLException {
        log.debug("supportsColumnAliasing called");
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "ColumnAliasing", Boolean.class);
    }

    @Override
    public boolean nullPlusNonNullIsNull() throws SQLException {
        log.debug("nullPlusNonNullIsNull called");
        return this.retrieveMetadataAttribute(CallType.CALL_NULL, "PlusNonNullIsNull", Boolean.class);
    }

    @Override
    public boolean supportsConvert() throws SQLException {
        log.debug("supportsConvert called");
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "Convert", Boolean.class);
    }

    @Override
    public boolean supportsConvert(int fromType, int toType) throws SQLException {
        log.debug("supportsConvert: {}, {}", fromType, toType);
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "Convert", Boolean.class,
                Arrays.asList(fromType, toType));
    }

    @Override
    public boolean supportsTableCorrelationNames() throws SQLException {
        log.debug("supportsTableCorrelationNames called");
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "TableCorrelationNames", Boolean.class);
    }

    @Override
    public boolean supportsDifferentTableCorrelationNames() throws SQLException {
        log.debug("supportsDifferentTableCorrelationNames called");
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "DifferentTableCorrelationNames", Boolean.class);
    }

    @Override
    public boolean supportsExpressionsInOrderBy() throws SQLException {
        log.debug("supportsExpressionsInOrderBy called");
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "ExpressionsInOrderBy", Boolean.class);
    }

    @Override
    public boolean supportsOrderByUnrelated() throws SQLException {
        log.debug("supportsOrderByUnrelated called");
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "OrderByUnrelated", Boolean.class);
    }

    @Override
    public boolean supportsGroupBy() throws SQLException {
        log.debug("supportsGroupBy called");
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "GroupBy", Boolean.class);
    }

    @Override
    public boolean supportsGroupByUnrelated() throws SQLException {
        log.debug("supportsGroupByUnrelated called");
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "GroupByUnrelated", Boolean.class);
    }

    @Override
    public boolean supportsGroupByBeyondSelect() throws SQLException {
        log.debug("supportsGroupByBeyondSelect called");
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "GroupByBeyondSelect", Boolean.class);
    }

    @Override
    public boolean supportsLikeEscapeClause() throws SQLException {
        log.debug("supportsLikeEscapeClause called");
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "LikeEscapeClause", Boolean.class);
    }

    @Override
    public boolean supportsMultipleResultSets() throws SQLException {
        log.debug("supportsMultipleResultSets called");
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "MultipleResultSets", Boolean.class);
    }

    @Override
    public boolean supportsMultipleTransactions() throws SQLException {
        log.debug("supportsMultipleTransactions called");
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "MultipleTransactions", Boolean.class);
    }

    @Override
    public boolean supportsNonNullableColumns() throws SQLException {
        log.debug("supportsNonNullableColumns called");
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "NonNullableColumns", Boolean.class);
    }

    @Override
    public boolean supportsMinimumSQLGrammar() throws SQLException {
        log.debug("supportsMinimumSQLGrammar called");
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "MinimumSQLGrammar", Boolean.class);
    }

    @Override
    public boolean supportsCoreSQLGrammar() throws SQLException {
        log.debug("supportsCoreSQLGrammar called");
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "CoreSQLGrammar", Boolean.class);
    }

    @Override
    public boolean supportsExtendedSQLGrammar() throws SQLException {
        log.debug("supportsExtendedSQLGrammar called");
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "ExtendedSQLGrammar", Boolean.class);
    }

    @Override
    public boolean supportsANSI92EntryLevelSQL() throws SQLException {
        log.debug("supportsANSI92EntryLevelSQL called");
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "ANSI92EntryLevelSQL", Boolean.class);
    }

    @Override
    public boolean supportsANSI92IntermediateSQL() throws SQLException {
        log.debug("supportsANSI92IntermediateSQL called");
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "ANSI92IntermediateSQL", Boolean.class);
    }

    @Override
    public boolean supportsANSI92FullSQL() throws SQLException {
        log.debug("supportsANSI92FullSQL called");
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "ANSI92FullSQL", Boolean.class);
    }

    @Override
    public boolean supportsIntegrityEnhancementFacility() throws SQLException {
        log.debug("supportsIntegrityEnhancementFacility called");
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "IntegrityEnhancementFacility", Boolean.class);
    }

    @Override
    public boolean supportsOuterJoins() throws SQLException {
        log.debug("supportsOuterJoins called");
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "OuterJoins", Boolean.class);
    }

    @Override
    public boolean supportsFullOuterJoins() throws SQLException {
        log.debug("supportsFullOuterJoins called");
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "FullOuterJoins", Boolean.class);
    }

    @Override
    public boolean supportsLimitedOuterJoins() throws SQLException {
        log.debug("supportsLimitedOuterJoins called");
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "LimitedOuterJoins", Boolean.class);
    }

    @Override
    public String getSchemaTerm() throws SQLException {
        log.debug("getSchemaTerm called");
        return this.retrieveMetadataAttribute(CallType.CALL_GET, "SchemaTerm", String.class);
    }

    @Override
    public String getProcedureTerm() throws SQLException {
        log.debug("getProcedureTerm called");
        return this.retrieveMetadataAttribute(CallType.CALL_GET, "ProcedureTerm", String.class);
    }

    @Override
    public String getCatalogTerm() throws SQLException {
        log.debug("getCatalogTerm called");
        return this.retrieveMetadataAttribute(CallType.CALL_GET, "CatalogTerm", String.class);
    }

    @Override
    public boolean isCatalogAtStart() throws SQLException {
        log.debug("isCatalogAtStart called");
        return this.retrieveMetadataAttribute(CallType.CALL_IS, "CatalogAtStart", Boolean.class);
    }

    @Override
    public String getCatalogSeparator() throws SQLException {
        log.debug("getCatalogSeparator called");
        return this.retrieveMetadataAttribute(CallType.CALL_GET, "CatalogSeparator", String.class);
    }

    @Override
    public boolean supportsSchemasInDataManipulation() throws SQLException {
        log.debug("supportsSchemasInDataManipulation called");
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "SchemasInDataManipulation", Boolean.class);
    }

    @Override
    public boolean supportsSchemasInProcedureCalls() throws SQLException {
        log.debug("supportsSchemasInProcedureCalls called");
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "SchemasInProcedureCalls", Boolean.class);
    }

    @Override
    public boolean supportsSchemasInTableDefinitions() throws SQLException {
        log.debug("supportsSchemasInTableDefinitions called");
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "SchemasInTableDefinitions", Boolean.class);
    }

    @Override
    public boolean supportsSchemasInIndexDefinitions() throws SQLException {
        log.debug("supportsSchemasInIndexDefinitions called");
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "SchemasInIndexDefinitions", Boolean.class);
    }

    @Override
    public boolean supportsSchemasInPrivilegeDefinitions() throws SQLException {
        log.debug("supportsSchemasInPrivilegeDefinitions called");
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "SchemasInIndexDefinitions", Boolean.class);
    }

    @Override
    public boolean supportsCatalogsInDataManipulation() throws SQLException {
        log.debug("supportsCatalogsInDataManipulation called");
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "CatalogsInDataManipulation", Boolean.class);
    }

    @Override
    public boolean supportsCatalogsInProcedureCalls() throws SQLException {
        log.debug("supportsCatalogsInProcedureCalls called");
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "CatalogsInProcedureCalls", Boolean.class);
    }

    @Override
    public boolean supportsCatalogsInTableDefinitions() throws SQLException {
        log.debug("supportsCatalogsInTableDefinitions called");
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "CatalogsInTableDefinitions", Boolean.class);
    }

    @Override
    public boolean supportsCatalogsInIndexDefinitions() throws SQLException {
        log.debug("supportsCatalogsInIndexDefinitions called");
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "CatalogsInIndexDefinitions", Boolean.class);
    }

    @Override
    public boolean supportsCatalogsInPrivilegeDefinitions() throws SQLException {
        log.debug("supportsCatalogsInPrivilegeDefinitions called");
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "CatalogsInPrivilegeDefinitions", Boolean.class);
    }

    @Override
    public boolean supportsPositionedDelete() throws SQLException {
        log.debug("supportsPositionedDelete called");
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "PositionedDelete", Boolean.class);
    }

    @Override
    public boolean supportsPositionedUpdate() throws SQLException {
        log.debug("supportsPositionedUpdate called");
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "PositionedUpdate", Boolean.class);
    }

    @Override
    public boolean supportsSelectForUpdate() throws SQLException {
        log.debug("supportsSelectForUpdate called");
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "SelectForUpdate", Boolean.class);
    }

    @Override
    public boolean supportsStoredProcedures() throws SQLException {
        log.debug("supportsStoredProcedures called");
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "StoredProcedures", Boolean.class);
    }

    @Override
    public boolean supportsSubqueriesInComparisons() throws SQLException {
        log.debug("supportsSubqueriesInComparisons called");
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "SubqueriesInComparisons", Boolean.class);
    }

    @Override
    public boolean supportsSubqueriesInExists() throws SQLException {
        log.debug("supportsSubqueriesInExists called");
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "SubqueriesInExists", Boolean.class);
    }

    @Override
    public boolean supportsSubqueriesInIns() throws SQLException {
        log.debug("supportsSubqueriesInIns called");
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "SubqueriesInIns", Boolean.class);
    }

    @Override
    public boolean supportsSubqueriesInQuantifieds() throws SQLException {
        log.debug("supportsSubqueriesInQuantifieds called");
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "SubqueriesInQuantifieds", Boolean.class);
    }

    @Override
    public boolean supportsCorrelatedSubqueries() throws SQLException {
        log.debug("supportsCorrelatedSubqueries called");
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "CorrelatedSubqueries", Boolean.class);
    }

    @Override
    public boolean supportsUnion() throws SQLException {
        log.debug("supportsUnion called");
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "Union", Boolean.class);
    }

    @Override
    public boolean supportsUnionAll() throws SQLException {
        log.debug("supportsUnionAll called");
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "UnionAll", Boolean.class);
    }

    @Override
    public boolean supportsOpenCursorsAcrossCommit() throws SQLException {
        log.debug("supportsOpenCursorsAcrossCommit called");
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "OpenCursorsAcrossCommit", Boolean.class);
    }

    @Override
    public boolean supportsOpenCursorsAcrossRollback() throws SQLException {
        log.debug("supportsOpenCursorsAcrossRollback called");
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "OpenCursorsAcrossRollback", Boolean.class);
    }

    @Override
    public boolean supportsOpenStatementsAcrossCommit() throws SQLException {
        log.debug("supportsOpenStatementsAcrossCommit called");
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "OpenStatementsAcrossCommit", Boolean.class);
    }

    @Override
    public boolean supportsOpenStatementsAcrossRollback() throws SQLException {
        log.debug("supportsOpenStatementsAcrossRollback called");
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "OpenStatementsAcrossRollback", Boolean.class);
    }

    @Override
    public int getMaxBinaryLiteralLength() throws SQLException {
        log.debug("getMaxBinaryLiteralLength called");
        return this.retrieveMetadataAttribute(CallType.CALL_GET, "MaxBinaryLiteralLength", Integer.class);
    }

    @Override
    public int getMaxCharLiteralLength() throws SQLException {
        log.debug("getMaxCharLiteralLength called");
        return this.retrieveMetadataAttribute(CallType.CALL_GET, "MaxCharLiteralLength", Integer.class);
    }

    @Override
    public int getMaxColumnNameLength() throws SQLException {
        log.debug("getMaxColumnNameLength called");
        return this.retrieveMetadataAttribute(CallType.CALL_GET, "MaxColumnNameLength", Integer.class);
    }

    @Override
    public int getMaxColumnsInGroupBy() throws SQLException {
        log.debug("getMaxColumnsInGroupBy called");
        return this.retrieveMetadataAttribute(CallType.CALL_GET, "MaxColumnsInGroupBy", Integer.class);
    }

    @Override
    public int getMaxColumnsInIndex() throws SQLException {
        log.debug("getMaxColumnsInIndex called");
        return this.retrieveMetadataAttribute(CallType.CALL_GET, "MaxColumnsInIndex", Integer.class);
    }

    @Override
    public int getMaxColumnsInOrderBy() throws SQLException {
        log.debug("getMaxColumnsInOrderBy called");
        return this.retrieveMetadataAttribute(CallType.CALL_GET, "MaxColumnsInOrderBy", Integer.class);
    }

    @Override
    public int getMaxColumnsInSelect() throws SQLException {
        log.debug("getMaxColumnsInSelect called");
        return this.retrieveMetadataAttribute(CallType.CALL_GET, "MaxColumnsInSelect", Integer.class);
    }

    @Override
    public int getMaxColumnsInTable() throws SQLException {
        log.debug("getMaxColumnsInTable called");
        return this.retrieveMetadataAttribute(CallType.CALL_GET, "MaxColumnsInTable", Integer.class);
    }

    @Override
    public int getMaxConnections() throws SQLException {
        log.debug("getMaxConnections called");
        return this.retrieveMetadataAttribute(CallType.CALL_GET, "MaxConnections", Integer.class);
    }

    @Override
    public int getMaxCursorNameLength() throws SQLException {
        log.debug("getMaxCursorNameLength called");
        return this.retrieveMetadataAttribute(CallType.CALL_GET, "MaxCursorNameLength", Integer.class);
    }

    @Override
    public int getMaxIndexLength() throws SQLException {
        log.debug("getMaxIndexLength called");
        return this.retrieveMetadataAttribute(CallType.CALL_GET, "MaxIndexLength", Integer.class);
    }

    @Override
    public int getMaxSchemaNameLength() throws SQLException {
        log.debug("getMaxSchemaNameLength called");
        return this.retrieveMetadataAttribute(CallType.CALL_GET, "MaxSchemaNameLength", Integer.class);
    }

    @Override
    public int getMaxProcedureNameLength() throws SQLException {
        log.debug("getMaxProcedureNameLength called");
        return this.retrieveMetadataAttribute(CallType.CALL_GET, "MaxProcedureNameLength", Integer.class);
    }

    @Override
    public int getMaxCatalogNameLength() throws SQLException {
        log.debug("getMaxCatalogNameLength called");
        return this.retrieveMetadataAttribute(CallType.CALL_GET, "MaxCatalogNameLength", Integer.class);
    }

    @Override
    public int getMaxRowSize() throws SQLException {
        log.debug("getMaxRowSize called");
        return this.retrieveMetadataAttribute(CallType.CALL_GET, "MaxRowSize", Integer.class);
    }

    @Override
    public boolean doesMaxRowSizeIncludeBlobs() throws SQLException {
        log.debug("doesMaxRowSizeIncludeBlobs called");
        return this.retrieveMetadataAttribute(CallType.CALL_DOES, "MaxRowSizeIncludeBlobs", Boolean.class);
    }

    @Override
    public int getMaxStatementLength() throws SQLException {
        log.debug("getMaxStatementLength called");
        return this.retrieveMetadataAttribute(CallType.CALL_GET, "MaxStatementLength", Integer.class);
    }

    @Override
    public int getMaxStatements() throws SQLException {
        log.debug("getMaxStatements called");
        return this.retrieveMetadataAttribute(CallType.CALL_GET, "MaxStatements", Integer.class);
    }

    @Override
    public int getMaxTableNameLength() throws SQLException {
        log.debug("getMaxTableNameLength called");
        return this.retrieveMetadataAttribute(CallType.CALL_GET, "MaxTableNameLength", Integer.class);
    }

    @Override
    public int getMaxTablesInSelect() throws SQLException {
        log.debug("getMaxTablesInSelect called");
        return this.retrieveMetadataAttribute(CallType.CALL_GET, "MaxTablesInSelect", Integer.class);
    }

    @Override
    public int getMaxUserNameLength() throws SQLException {
        log.debug("getMaxUserNameLength called");
        return this.retrieveMetadataAttribute(CallType.CALL_GET, "MaxUserNameLength", Integer.class);
    }

    @Override
    public int getDefaultTransactionIsolation() throws SQLException {
        log.debug("getDefaultTransactionIsolation called");
        return this.retrieveMetadataAttribute(CallType.CALL_GET, "DefaultTransactionIsolation", Integer.class);
    }

    @Override
    public boolean supportsTransactions() throws SQLException {
        log.debug("supportsTransactions called");
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "Transactions", Boolean.class);
    }

    @Override
    public boolean supportsTransactionIsolationLevel(int level) throws SQLException {
        log.debug("supportsTransactionIsolationLevel: {}", level);
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "TransactionIsolationLevel", Boolean.class,
                Arrays.asList(level));
    }

    @Override
    public boolean supportsDataDefinitionAndDataManipulationTransactions() throws SQLException {
        log.debug("supportsDataDefinitionAndDataManipulationTransactions called");
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "DataDefinitionAndDataManipulationTransactions",
                Boolean.class);
    }

    @Override
    public boolean supportsDataManipulationTransactionsOnly() throws SQLException {
        log.debug("supportsDataManipulationTransactionsOnly called");
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "DataManipulationTransactionsOnly", Boolean.class);
    }

    @Override
    public boolean dataDefinitionCausesTransactionCommit() throws SQLException {
        log.debug("dataDefinitionCausesTransactionCommit called");
        return this.retrieveMetadataAttribute(CallType.CALL_DATA, "DefinitionCausesTransactionCommit", Boolean.class);
    }

    @Override
    public boolean dataDefinitionIgnoredInTransactions() throws SQLException {
        log.debug("dataDefinitionIgnoredInTransactions called");
        return this.retrieveMetadataAttribute(CallType.CALL_DATA, "DefinitionIgnoredInTransactions", Boolean.class);
    }

    @Override
    public ResultSet getProcedures(String catalog, String schemaPattern, String procedureNamePattern) throws SQLException {
        log.debug("getProcedures: {}, {}, {}", catalog, schemaPattern, procedureNamePattern);
        String resultSetUUID = this.retrieveMetadataAttribute(CallType.CALL_GET, "Procedures", String.class,
                Arrays.asList(catalog, schemaPattern, procedureNamePattern));
        return new RemoteProxyResultSet(resultSetUUID, this.statementService, this.connection, this.statement);
    }

    @Override
    public ResultSet getProcedureColumns(String catalog, String schemaPattern, String procedureNamePattern, String columnNamePattern) throws SQLException {
        log.debug("getProcedureColumns: {}, {}, {}, {}", catalog, schemaPattern, procedureNamePattern, columnNamePattern);
        String resultSetUUID = this.retrieveMetadataAttribute(CallType.CALL_GET, "ProcedureColumns", String.class,
                Arrays.asList(catalog, schemaPattern, procedureNamePattern, columnNamePattern));
        return new RemoteProxyResultSet(resultSetUUID, this.statementService, this.connection, this.statement);
    }

    @Override
    public ResultSet getTables(String catalog, String schemaPattern, String tableNamePattern, String[] types) throws SQLException {
        log.debug("getTables: {}, {}, {}, <String[]>", catalog, schemaPattern, tableNamePattern);
        String resultSetUUID = this.retrieveMetadataAttribute(CallType.CALL_GET, "Tables", String.class,
                Arrays.asList(catalog, schemaPattern, tableNamePattern, types));
        return new RemoteProxyResultSet(resultSetUUID, this.statementService, this.connection, this.statement);
    }

    @Override
    public ResultSet getSchemas() throws SQLException {
        log.debug("getSchemas called");
        String resultSetUUID = this.retrieveMetadataAttribute(CallType.CALL_GET, "Schemas", String.class);
        return new RemoteProxyResultSet(resultSetUUID, this.statementService, this.connection, this.statement);
    }

    @Override
    public ResultSet getCatalogs() throws SQLException {
        log.debug("getCatalogs called");
        String resultSetUUID = this.retrieveMetadataAttribute(CallType.CALL_GET, "Catalogs", String.class);
        return new RemoteProxyResultSet(resultSetUUID, this.statementService, this.connection, this.statement);
    }

    @Override
    public ResultSet getTableTypes() throws SQLException {
        log.debug("getTableTypes called");
        String resultSetUUID = this.retrieveMetadataAttribute(CallType.CALL_GET, "TableTypes", String.class);
        return new RemoteProxyResultSet(resultSetUUID, this.statementService, this.connection, this.statement);
    }

    @Override
    public ResultSet getColumns(String catalog, String schemaPattern, String tableNamePattern, String columnNamePattern) throws SQLException {
        log.debug("getColumns: {}, {}, {}, {}", catalog, schemaPattern, tableNamePattern, columnNamePattern);
        String resultSetUUID = this.retrieveMetadataAttribute(CallType.CALL_GET, "Columns", String.class,
                Arrays.asList(catalog, schemaPattern, tableNamePattern, columnNamePattern));
        return new RemoteProxyResultSet(resultSetUUID, this.statementService, this.connection, this.statement);
    }

    @Override
    public ResultSet getColumnPrivileges(String catalog, String schema, String table, String columnNamePattern) throws SQLException {
        log.debug("getColumnPrivileges: {}, {}, {}, {}", catalog, schema, table, columnNamePattern);
        String resultSetUUID = this.retrieveMetadataAttribute(CallType.CALL_GET, "ColumnPrivileges", String.class,
                Arrays.asList(catalog, schema, table, columnNamePattern));
        return new RemoteProxyResultSet(resultSetUUID, this.statementService, this.connection, this.statement);
    }

    @Override
    public ResultSet getTablePrivileges(String catalog, String schemaPattern, String tableNamePattern) throws SQLException {
        log.debug("getTablePrivileges: {}, {}, {}", catalog, schemaPattern, tableNamePattern);
        String resultSetUUID = this.retrieveMetadataAttribute(CallType.CALL_GET, "TablePrivileges", String.class,
                Arrays.asList(catalog, schemaPattern, tableNamePattern));
        return new RemoteProxyResultSet(resultSetUUID, this.statementService, this.connection, this.statement);
    }

    @Override
    public ResultSet getBestRowIdentifier(String catalog, String schema, String table, int scope, boolean nullable) throws SQLException {
        log.debug("getBestRowIdentifier: {}, {}, {}, {}, {}", catalog, schema, table, scope, nullable);
        String resultSetUUID = this.retrieveMetadataAttribute(CallType.CALL_GET, "BestRowIdentifier", String.class,
                Arrays.asList(catalog, schema, table, scope, nullable));
        return new RemoteProxyResultSet(resultSetUUID, this.statementService, this.connection, this.statement);
    }

    @Override
    public ResultSet getVersionColumns(String catalog, String schema, String table) throws SQLException {
        log.debug("getVersionColumns: {}, {}, {}", catalog, schema, table);
        String resultSetUUID = this.retrieveMetadataAttribute(CallType.CALL_GET, "VersionColumns", String.class,
                Arrays.asList(catalog, schema, table));
        return new RemoteProxyResultSet(resultSetUUID, this.statementService, this.connection, this.statement);
    }

    @Override
    public ResultSet getPrimaryKeys(String catalog, String schema, String table) throws SQLException {
        log.debug("getPrimaryKeys: {}, {}, {}", catalog, schema, table);
        String resultSetUUID = this.retrieveMetadataAttribute(CallType.CALL_GET, "PrimaryKeys", String.class,
                Arrays.asList(catalog, schema, table));
        return new RemoteProxyResultSet(resultSetUUID, this.statementService, this.connection, this.statement);
    }

    @Override
    public ResultSet getImportedKeys(String catalog, String schema, String table) throws SQLException {
        log.debug("getImportedKeys: {}, {}, {}", catalog, schema, table);
        String resultSetUUID = this.retrieveMetadataAttribute(CallType.CALL_GET, "ImportedKeys", String.class,
                Arrays.asList(catalog, schema, table));
        return new RemoteProxyResultSet(resultSetUUID, this.statementService, this.connection, this.statement);
    }

    @Override
    public ResultSet getExportedKeys(String catalog, String schema, String table) throws SQLException {
        log.debug("getExportedKeys: {}, {}, {}", catalog, schema, table);
        String resultSetUUID = this.retrieveMetadataAttribute(CallType.CALL_GET, "ExportedKeys", String.class,
                Arrays.asList(catalog, schema, table));
        return new RemoteProxyResultSet(resultSetUUID, this.statementService, this.connection, this.statement);
    }

    @Override
    public ResultSet getCrossReference(String parentCatalog, String parentSchema, String parentTable, String foreignCatalog,
                                       String foreignSchema, String foreignTable) throws SQLException {
        log.debug("getCrossReference: {}, {}, {}, {}, {}, {}", parentCatalog, parentSchema, parentTable, foreignCatalog, foreignSchema, foreignTable);
        String resultSetUUID = this.retrieveMetadataAttribute(CallType.CALL_GET, "CrossReference", String.class,
                Arrays.asList(parentCatalog, parentSchema, parentTable, foreignCatalog, foreignSchema, foreignTable));
        return new RemoteProxyResultSet(resultSetUUID, this.statementService, this.connection, this.statement);
    }

    @Override
    public ResultSet getTypeInfo() throws SQLException {
        log.debug("getTypeInfo called");
        String resultSetUUID = this.retrieveMetadataAttribute(CallType.CALL_GET, "TypeInfo", String.class);
        return new RemoteProxyResultSet(resultSetUUID, this.statementService, this.connection, this.statement);
    }

    @Override
    public ResultSet getIndexInfo(String catalog, String schema, String table, boolean unique, boolean approximate) throws SQLException {
        log.debug("getIndexInfo: {}, {}, {}, {}, {}", catalog, schema, table, unique, approximate);
        String resultSetUUID = this.retrieveMetadataAttribute(CallType.CALL_GET, "IndexInfo", String.class,
                Arrays.asList(catalog, schema, table, unique, approximate));
        return new RemoteProxyResultSet(resultSetUUID, this.statementService, this.connection, this.statement);
    }

    @Override
    public boolean supportsResultSetType(int type) throws SQLException {
        log.debug("supportsResultSetType: {}", type);
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "ResultSetType", Boolean.class,
                Arrays.asList(type));
    }

    @Override
    public boolean supportsResultSetConcurrency(int type, int concurrency) throws SQLException {
        log.debug("supportsResultSetConcurrency: {}, {}", type, concurrency);
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "ResultSetConcurrency", Boolean.class,
                Arrays.asList(type, concurrency));
    }

    @Override
    public boolean ownUpdatesAreVisible(int type) throws SQLException {
        log.debug("ownUpdatesAreVisible: {}", type);
        return this.retrieveMetadataAttribute(CallType.CALL_OWN, "UpdatesAreVisible", Boolean.class,
                Arrays.asList(type));
    }

    @Override
    public boolean ownDeletesAreVisible(int type) throws SQLException {
        log.debug("ownDeletesAreVisible: {}", type);
        return this.retrieveMetadataAttribute(CallType.CALL_OWN, "DeletesAreVisible", Boolean.class,
                Arrays.asList(type));
    }

    @Override
    public boolean ownInsertsAreVisible(int type) throws SQLException {
        log.debug("ownInsertsAreVisible: {}", type);
        return this.retrieveMetadataAttribute(CallType.CALL_OWN, "InsertsAreVisible", Boolean.class,
                Arrays.asList(type));
    }

    @Override
    public boolean othersUpdatesAreVisible(int type) throws SQLException {
        log.debug("othersUpdatesAreVisible: {}", type);
        return this.retrieveMetadataAttribute(CallType.CALL_OTHERS, "UpdatesAreVisible", Boolean.class,
                Arrays.asList(type));
    }

    @Override
    public boolean othersDeletesAreVisible(int type) throws SQLException {
        log.debug("othersDeletesAreVisible: {}", type);
        return this.retrieveMetadataAttribute(CallType.CALL_OTHERS, "DeletesAreVisible", Boolean.class,
                Arrays.asList(type));
    }

    @Override
    public boolean othersInsertsAreVisible(int type) throws SQLException {
        log.debug("othersInsertsAreVisible: {}", type);
        return this.retrieveMetadataAttribute(CallType.CALL_OTHERS, "InsertsAreVisible", Boolean.class,
                Arrays.asList(type));
    }

    @Override
    public boolean updatesAreDetected(int type) throws SQLException {
        log.debug("updatesAreDetected: {}", type);
        return this.retrieveMetadataAttribute(CallType.CALL_UPDATES, "AreDetected", Boolean.class,
                Arrays.asList(type));
    }

    @Override
    public boolean deletesAreDetected(int type) throws SQLException {
        log.debug("deletesAreDetected: {}", type);
        return this.retrieveMetadataAttribute(CallType.CALL_DELETES, "AreDetected", Boolean.class,
                Arrays.asList(type));
    }

    @Override
    public boolean insertsAreDetected(int type) throws SQLException {
        log.debug("insertsAreDetected: {}", type);
        return this.retrieveMetadataAttribute(CallType.CALL_INSERTS, "AreDetected", Boolean.class,
                Arrays.asList(type));
    }

    @Override
    public boolean supportsBatchUpdates() throws SQLException {
        log.debug("supportsBatchUpdates called");
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "BatchUpdates", Boolean.class);
    }

    @Override
    public ResultSet getUDTs(String catalog, String schemaPattern, String typeNamePattern, int[] types) throws SQLException {
        log.debug("getUDTs: {}, {}, {}, <int[]>", catalog, schemaPattern, typeNamePattern);
        String resultSetUUID = this.retrieveMetadataAttribute(CallType.CALL_GET, "UDTs", String.class,
                Arrays.asList(catalog, schemaPattern, typeNamePattern, types));
        return new RemoteProxyResultSet(resultSetUUID, this.statementService, this.connection, this.statement);
    }

    @Override
    public Connection getConnection() throws SQLException {
        log.debug("getConnection called");
        return (this.statement != null) ? statement.getConnection() : this.connection;
    }

    @Override
    public boolean supportsSavepoints() throws SQLException {
        log.debug("supportsSavepoints called");
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "Savepoints", Boolean.class);
    }

    @Override
    public boolean supportsNamedParameters() throws SQLException {
        log.debug("supportsNamedParameters called");
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "NamedParameters", Boolean.class);
    }

    @Override
    public boolean supportsMultipleOpenResults() throws SQLException {
        log.debug("supportsMultipleOpenResults called");
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "MultipleOpenResults", Boolean.class);
    }

    @Override
    public boolean supportsGetGeneratedKeys() throws SQLException {
        log.debug("supportsGetGeneratedKeys called");
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "GetGeneratedKeys", Boolean.class);
    }

    @Override
    public ResultSet getSuperTypes(String catalog, String schemaPattern, String typeNamePattern) throws SQLException {
        log.debug("getSuperTypes: {}, {}, {}", catalog, schemaPattern, typeNamePattern);
        String resultSetUUID = this.retrieveMetadataAttribute(CallType.CALL_GET, "SuperTypes", String.class,
                Arrays.asList(catalog, schemaPattern, typeNamePattern));
        return new RemoteProxyResultSet(resultSetUUID, this.statementService, this.connection, this.statement);
    }

    @Override
    public ResultSet getSuperTables(String catalog, String schemaPattern, String tableNamePattern) throws SQLException {
        log.debug("getSuperTables: {}, {}, {}", catalog, schemaPattern, tableNamePattern);
        String resultSetUUID = this.retrieveMetadataAttribute(CallType.CALL_GET, "SuperTables", String.class,
                Arrays.asList(catalog, schemaPattern, tableNamePattern));
        return new RemoteProxyResultSet(resultSetUUID, this.statementService, this.connection, this.statement);
    }

    @Override
    public ResultSet getAttributes(String catalog, String schemaPattern, String typeNamePattern, String attributeNamePattern)
            throws SQLException {
        log.debug("getAttributes: {}, {}, {}, {}", catalog, schemaPattern, typeNamePattern, attributeNamePattern);
        String resultSetUUID = this.retrieveMetadataAttribute(CallType.CALL_GET, "Attributes", String.class,
                Arrays.asList(catalog, schemaPattern, typeNamePattern, attributeNamePattern));
        return new RemoteProxyResultSet(resultSetUUID, this.statementService, this.connection, this.statement);
    }

    @Override
    public boolean supportsResultSetHoldability(int holdability) throws SQLException {
        log.debug("supportsResultSetHoldability: {}", holdability);
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "ResultSetHoldability", Boolean.class,
                Arrays.asList(holdability));
    }

    @Override
    public int getResultSetHoldability() throws SQLException {
        log.debug("getResultSetHoldability called");
        return this.retrieveMetadataAttribute(CallType.CALL_GET, "ResultSetHoldability", Integer.class);
    }

    @Override
    public int getDatabaseMajorVersion() throws SQLException {
        log.debug("getDatabaseMajorVersion called");
        return this.retrieveMetadataAttribute(CallType.CALL_GET, "DatabaseMajorVersion", Integer.class);
    }

    @Override
    public int getDatabaseMinorVersion() throws SQLException {
        log.debug("getDatabaseMinorVersion called");
        return this.retrieveMetadataAttribute(CallType.CALL_GET, "DatabaseMinorVersion", Integer.class);
    }

    @Override
    public int getJDBCMajorVersion() throws SQLException {
        log.debug("getJDBCMajorVersion called");
        return this.retrieveMetadataAttribute(CallType.CALL_GET, "JDBCMajorVersion", Integer.class);
    }

    @Override
    public int getJDBCMinorVersion() throws SQLException {
        log.debug("getJDBCMinorVersion called");
        return this.retrieveMetadataAttribute(CallType.CALL_GET, "JDBCMinorVersion", Integer.class);
    }

    @Override
    public int getSQLStateType() throws SQLException {
        log.debug("getSQLStateType called");
        return this.retrieveMetadataAttribute(CallType.CALL_GET, "SQLStateType", Integer.class);
    }

    @Override
    public boolean locatorsUpdateCopy() throws SQLException {
        log.debug("locatorsUpdateCopy called");
        return this.retrieveMetadataAttribute(CallType.CALL_LOCATORS, "UpdateCopy", Boolean.class);
    }

    @Override
    public boolean supportsStatementPooling() throws SQLException {
        log.debug("supportsStatementPooling called");
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "StatementPooling", Boolean.class);
    }

    @Override
    public RowIdLifetime getRowIdLifetime() throws SQLException {
        log.debug("getRowIdLifetime called");
        return this.retrieveMetadataAttribute(CallType.CALL_GET, "RowIdLifetime", RowIdLifetime.class);
    }

    @Override
    public ResultSet getSchemas(String catalog, String schemaPattern) throws SQLException {
        log.debug("getSchemas: {}, {}", catalog, schemaPattern);
        String resultSetUUID = this.retrieveMetadataAttribute(CallType.CALL_GET, "Schemas", String.class,
                Arrays.asList(catalog, schemaPattern));
        return new RemoteProxyResultSet(resultSetUUID, this.statementService, this.connection, this.statement);
    }

    @Override
    public boolean supportsStoredFunctionsUsingCallSyntax() throws SQLException {
        log.debug("supportsStoredFunctionsUsingCallSyntax called");
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "StoredFunctionsUsingCallSyntax", Boolean.class);
    }

    @Override
    public boolean autoCommitFailureClosesAllResultSets() throws SQLException {
        log.debug("autoCommitFailureClosesAllResultSets called");
        return this.retrieveMetadataAttribute(CallType.CALL_AUTO, "CommitFailureClosesAllResultSets", Boolean.class);
    }

    @Override
    public ResultSet getClientInfoProperties() throws SQLException {
        log.debug("getClientInfoProperties called");
        String resultSetUUID = this.retrieveMetadataAttribute(CallType.CALL_GET, "ClientInfoProperties", String.class);
        return new RemoteProxyResultSet(resultSetUUID, this.statementService, this.connection, this.statement);
    }

    @Override
    public ResultSet getFunctions(String catalog, String schemaPattern, String functionNamePattern) throws SQLException {
        log.debug("getFunctions: {}, {}, {}", catalog, schemaPattern, functionNamePattern);
        String resultSetUUID = this.retrieveMetadataAttribute(CallType.CALL_GET, "Functions", String.class,
                Arrays.asList(catalog, schemaPattern, functionNamePattern));
        return new RemoteProxyResultSet(resultSetUUID, this.statementService, this.connection, this.statement);
    }

    @Override
    public ResultSet getFunctionColumns(String catalog, String schemaPattern, String functionNamePattern, String columnNamePattern) throws SQLException {
        log.debug("getFunctionColumns: {}, {}, {}, {}", catalog, schemaPattern, functionNamePattern, columnNamePattern);
        String resultSetUUID = this.retrieveMetadataAttribute(CallType.CALL_GET, "FunctionColumns", String.class,
                Arrays.asList(catalog, schemaPattern, functionNamePattern, columnNamePattern));
        return new RemoteProxyResultSet(resultSetUUID, this.statementService, this.connection, this.statement);
    }

    @Override
    public ResultSet getPseudoColumns(String catalog, String schemaPattern, String tableNamePattern, String columnNamePattern) throws SQLException {
        log.debug("getPseudoColumns: {}, {}, {}, {}", catalog, schemaPattern, tableNamePattern, columnNamePattern);
        String resultSetUUID = this.retrieveMetadataAttribute(CallType.CALL_GET, "PseudoColumns", String.class,
                Arrays.asList(catalog, schemaPattern, tableNamePattern, columnNamePattern));
        return new RemoteProxyResultSet(resultSetUUID, this.statementService, this.connection, this.statement);
    }

    @Override
    public boolean generatedKeyAlwaysReturned() throws SQLException {
        log.debug("generatedKeyAlwaysReturned called");
        return this.retrieveMetadataAttribute(CallType.CALL_GENERATED, "KeyAlwaysReturned", Boolean.class);
    }

    @Override
    public long getMaxLogicalLobSize() throws SQLException {
        log.debug("getMaxLogicalLobSize called");
        return this.retrieveMetadataAttribute(CallType.CALL_GET, "MaxLogicalLobSize", Long.class);
    }

    @Override
    public boolean supportsRefCursors() throws SQLException {
        log.debug("supportsRefCursors called");
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "RefCursors", Boolean.class);
    }

    @Override
    public boolean supportsSharding() throws SQLException {
        log.debug("supportsSharding called");
        return this.retrieveMetadataAttribute(CallType.CALL_SUPPORTS, "Sharding", Boolean.class);
    }
    private <T> T retrieveMetadataAttribute(CallType callType, String attrName, Class returnType) throws SQLException {
        log.debug("retrieveMetadataAttribute: {}, {}", callType, attrName);
        return this.retrieveMetadataAttribute(callType, attrName, returnType, Constants.EMPTY_OBJECT_LIST);
    }

    private <T> T retrieveMetadataAttribute(CallType callType, String attrName, Class returnType, List<Object> params) throws SQLException {
        log.debug("retrieveMetadataAttribute: {}, {}, <params>", callType, attrName);
        String cacheKey = callType + "|" + attrName + "|" + params;
        Object cached = this.metadataCache.get(cacheKey);
        if (cached != null) {
            return (T) (cached == NULL_VALUE ? null : cached);
        }

        CallResourceRequest.Builder reqBuilder = this.newCallBuilder();
        reqBuilder
            .setTarget(
                TargetCall.newBuilder()
                        .setCallType(CallType.CALL_GET)
                        .setResourceName("MetaData")
                        .setNextCall(TargetCall.newBuilder()
                                .setCallType(callType)
                                .setResourceName(attrName)
                                .addAllParams(ProtoConverter.objectListToParameterValues(params))
                                .build())
                        .build()
        );
        CallResourceResponse response = this.statementService.callResource(reqBuilder.build());
        this.connection.setSession(response.getSession());

        List<ParameterValue> values = response.getValuesList();
        if (values.isEmpty()) {
            this.metadataCache.put(cacheKey, NULL_VALUE);
            return null;
        }

        Object result = ProtoConverter.fromParameterValue(values.get(0));
        this.metadataCache.put(cacheKey, result == null ? NULL_VALUE : result);
        return (T) result;
    }
}
