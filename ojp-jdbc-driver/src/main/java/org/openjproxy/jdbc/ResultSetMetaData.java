package org.openjproxy.jdbc;

import com.openjproxy.grpc.CallResourceRequest;
import com.openjproxy.grpc.CallResourceResponse;
import com.openjproxy.grpc.CallType;
import com.openjproxy.grpc.ParameterValue;
import com.openjproxy.grpc.ResourceType;
import com.openjproxy.grpc.TargetCall;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.openjproxy.grpc.ProtoConverter;
import org.openjproxy.grpc.client.StatementService;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class ResultSetMetaData implements java.sql.ResultSetMetaData {

    // Sentinel used to cache a legitimately null attribute value, since ConcurrentHashMap does not allow null values.
    private static final Object NULL_VALUE = new Object();

    private final StatementService statementService;
    private final RemoteProxyResultSet resultSet;
    private final PreparedStatement ps;

    // Metadata is immutable for the lifetime of a given ResultSet/PreparedStatement, so every attribute
    // is cached on first access to avoid a network round trip per column per row (e.g. tools that call
    // getMetaData() attributes repeatedly while rendering results).
    private final Map<String, Object> metadataCache = new ConcurrentHashMap<>();

    public ResultSetMetaData(RemoteProxyResultSet resultSet, StatementService statementService) {
        this.resultSet = resultSet;
        this.statementService = statementService;
        this.ps = null;
    }

    public ResultSetMetaData(PreparedStatement ps, StatementService statementService) {
        this.ps = ps;
        this.statementService = statementService;
        this.resultSet = null;
    }

    @Override
    public int getColumnCount() throws SQLException {
        log.debug("getColumnCount called");
        if (resultSet instanceof org.openjproxy.jdbc.ResultSet) {
            org.openjproxy.jdbc.ResultSet rs = (org.openjproxy.jdbc.ResultSet) resultSet;
            return rs.getLabelsMap().size();
        } else {
            return this.retrieveMetadataAttribute(CallType.CALL_GET, "ColumnCount",-1, Integer.class);
        }
    }

    private CallResourceRequest.Builder newCallBuilder() throws SQLException {
        log.debug("newCallBuilder called");
        if (this.resultSet != null) {
            return CallResourceRequest.newBuilder()
                    .setSession(this.resultSet.getConnection().getSession())
                    .setResourceType(ResourceType.RES_RESULT_SET)
                    .setResourceUUID(this.resultSet.getResultSetUUID());
        } else if (this.ps != null) {
            CallResourceRequest.Builder builder = CallResourceRequest.newBuilder()
                    .setSession(this.ps.getConnection().getSession())
                    .setResourceType(ResourceType.RES_PREPARED_STATEMENT);

            if (this.ps.getProperties() != null) {
                builder.addAllProperties(ProtoConverter.propertiesToProto(this.ps.getProperties()));
            }

            if (StringUtils.isNotBlank(this.ps.getStatementUUID())) {
                builder.setResourceUUID(this.ps.getStatementUUID());
            }
            return builder;
        }
        throw new RuntimeException("A result set or a prepared statement reference is required.");
    }

    @Override
    public boolean isAutoIncrement(int column) throws SQLException {
        log.debug("isAutoIncrement: {}", column);
        return this.retrieveMetadataAttribute(CallType.CALL_IS, "AutoIncrement", column, Boolean.class);
    }

    @Override
    public boolean isCaseSensitive(int column) throws SQLException {
        log.debug("isCaseSensitive: {}", column);
        return this.retrieveMetadataAttribute(CallType.CALL_IS, "CaseSensitive", column, Boolean.class);
    }

    @Override
    public boolean isSearchable(int column) throws SQLException {
        log.debug("isSearchable: {}", column);
        return this.retrieveMetadataAttribute(CallType.CALL_IS, "Searchable", column, Boolean.class);
    }

    @Override
    public boolean isCurrency(int column) throws SQLException {
        log.debug("isCurrency: {}", column);
        return this.retrieveMetadataAttribute(CallType.CALL_IS, "Currency", column, Boolean.class);
    }

    @Override
    public int isNullable(int column) throws SQLException {
        log.debug("isNullable: {}", column);
        return this.retrieveMetadataAttribute(CallType.CALL_IS, "Nullable", column, Integer.class);
    }

    @Override
    public boolean isSigned(int column) throws SQLException {
        log.debug("isSigned: {}", column);
        return this.retrieveMetadataAttribute(CallType.CALL_IS, "Signed", column, Boolean.class);
    }

    @Override
    public int getColumnDisplaySize(int column) throws SQLException {
        log.debug("getColumnDisplaySize: {}", column);
        return this.retrieveMetadataAttribute(CallType.CALL_GET, "ColumnDisplaySize", column, Integer.class);
    }

    @Override
    public String getColumnLabel(int column) throws SQLException {
        log.debug("getColumnLabel: {}", column);
        return this.retrieveMetadataAttribute(CallType.CALL_GET, "ColumnLabel", column, String.class);
    }

    @Override
    public String getColumnName(int column) throws SQLException {
        log.debug("getColumnName: {}", column);
        return this.retrieveMetadataAttribute(CallType.CALL_GET, "ColumnName", column, String.class);
    }

    @Override
    public String getSchemaName(int column) throws SQLException {
        log.debug("getSchemaName: {}", column);
        return this.retrieveMetadataAttribute(CallType.CALL_GET, "SchemaName", column, String.class);
    }

    @Override
    public int getPrecision(int column) throws SQLException {
        log.debug("getPrecision: {}", column);
        return this.retrieveMetadataAttribute(CallType.CALL_GET, "Precision", column, Integer.class);
    }

    @Override
    public int getScale(int column) throws SQLException {
        log.debug("getScale: {}", column);
        return this.retrieveMetadataAttribute(CallType.CALL_GET, "Scale", column, Integer.class);
    }

    @Override
    public String getTableName(int column) throws SQLException {
        log.debug("getTableName: {}", column);
        return this.retrieveMetadataAttribute(CallType.CALL_GET, "TableName", column, String.class);
    }

    @Override
    public String getCatalogName(int column) throws SQLException {
        log.debug("getCatalogName: {}", column);
        return this.retrieveMetadataAttribute(CallType.CALL_GET, "CatalogName", column, String.class);
    }

    @Override
    public int getColumnType(int column) throws SQLException {
        log.debug("getColumnType: {}", column);
        return this.retrieveMetadataAttribute(CallType.CALL_GET, "ColumnType", column, Integer.class);
    }

    @Override
    public String getColumnTypeName(int column) throws SQLException {
        log.debug("getColumnTypeName: {}", column);
        return this.retrieveMetadataAttribute(CallType.CALL_GET, "ColumnTypeName", column, String.class);
    }

    @Override
    public boolean isReadOnly(int column) throws SQLException {
        log.debug("isReadOnly: {}", column);
        return this.retrieveMetadataAttribute(CallType.CALL_IS, "ReadOnly", column, Boolean.class);
    }

    @Override
    public boolean isWritable(int column) throws SQLException {
        log.debug("isWritable: {}", column);
        return this.retrieveMetadataAttribute(CallType.CALL_IS, "Writable", column, Boolean.class);
    }

    @Override
    public boolean isDefinitelyWritable(int column) throws SQLException {
        log.debug("isDefinitelyWritable: {}", column);
        return this.retrieveMetadataAttribute(CallType.CALL_IS, "DefinitelyWritable", column, Boolean.class);
    }

    @Override
    public String getColumnClassName(int column) throws SQLException {
        log.debug("getColumnClassName: {}", column);
        return this.retrieveMetadataAttribute(CallType.CALL_GET, "ColumnClassName", column, String.class);
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

    private <T> T retrieveMetadataAttribute(CallType callType, String attrName, Integer column,  Class returnType) throws SQLException {
        log.debug("retrieveMetadataAttribute: {}, {}, {}, {}", callType, attrName, column, returnType);
        String cacheKey = attrName + "|" + column;
        Object cached = this.metadataCache.get(cacheKey);
        if (cached != null) {
            return (T) (cached == NULL_VALUE ? null : cached);
        }

        CallResourceRequest.Builder reqBuilder = this.newCallBuilder();
        List<Object> params = Constants.EMPTY_OBJECT_LIST;
        if (column > -1) {
            params = Arrays.asList(Integer.valueOf(column));
        }
        reqBuilder.setTarget(
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
        if (this.resultSet != null) {
            this.resultSet.getConnection().setSession(response.getSession());
        } else if (this.ps != null) {
            this.ps.getConnection().setSession(response.getSession());
        }

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
