package org.openjproxy.jdbc;

import com.openjproxy.grpc.CallResourceRequest;
import com.openjproxy.grpc.CallResourceResponse;
import com.openjproxy.grpc.CallType;
import com.openjproxy.grpc.ParameterValue;
import com.openjproxy.grpc.ResourceType;
import com.openjproxy.grpc.TargetCall;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.grpc.ProtoConverter;
import org.openjproxy.grpc.client.StatementService;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * Proxy implementation of {@link java.sql.Array} backed by a server-side array object.
 */
@Slf4j
public class Array implements java.sql.Array {

    @Getter
    private final Connection connection;
    private final StatementService statementService;
    @Getter
    private final String uuid;

    public Array(Connection connection, StatementService statementService, String uuid) {
        this.connection = connection;
        this.statementService = statementService;
        this.uuid = uuid;
    }

    @Override
    public String getBaseTypeName() throws SQLException {
        log.debug("getBaseTypeName called");
        return this.callProxy(CallType.CALL_GET, "BaseTypeName", String.class);
    }

    @Override
    public int getBaseType() throws SQLException {
        log.debug("getBaseType called");
        Integer result = this.callProxy(CallType.CALL_GET, "BaseType", Integer.class);
        return result != null ? result : 0;
    }

    @Override
    public Object getArray() throws SQLException {
        log.debug("getArray called");
        return toObjectArray(this.callProxy(CallType.CALL_GET, "Array", Object.class));
    }

    @Override
    public Object getArray(Map<String, Class<?>> map) throws SQLException {
        log.debug("getArray: <Map> called");
        return toObjectArray(this.callProxy(CallType.CALL_GET, "Array", Object.class, List.of(map)));
    }

    @Override
    public Object getArray(long index, int count) throws SQLException {
        log.debug("getArray: {}, {} called", index, count);
        return toObjectArray(this.callProxy(CallType.CALL_GET, "Array", Object.class, List.of(index, count)));
    }

    @Override
    public Object getArray(long index, int count, Map<String, Class<?>> map) throws SQLException {
        log.debug("getArray: {}, {}, <Map> called", index, count);
        return toObjectArray(this.callProxy(CallType.CALL_GET, "Array", Object.class, List.of(index, count, map)));
    }

    @Override
    public ResultSet getResultSet() throws SQLException {
        log.debug("getResultSet called");
        return resultSetFromUuid(this.callProxy(CallType.CALL_GET, "ResultSet", String.class));
    }

    @Override
    public ResultSet getResultSet(Map<String, Class<?>> map) throws SQLException {
        log.debug("getResultSet: <Map> called");
        return resultSetFromUuid(this.callProxy(CallType.CALL_GET, "ResultSet", String.class, List.of(map)));
    }

    @Override
    public ResultSet getResultSet(long index, int count) throws SQLException {
        log.debug("getResultSet: {}, {} called", index, count);
        return resultSetFromUuid(this.callProxy(CallType.CALL_GET, "ResultSet", String.class, List.of(index, count)));
    }

    @Override
    public ResultSet getResultSet(long index, int count, Map<String, Class<?>> map) throws SQLException {
        log.debug("getResultSet: {}, {}, <Map> called", index, count);
        return resultSetFromUuid(this.callProxy(CallType.CALL_GET, "ResultSet", String.class, List.of(index, count, map)));
    }

    @Override
    public void free() throws SQLException {
        log.debug("free called");
        this.callProxy(CallType.CALL_CLOSE, "", Void.class);
    }

    private ResultSet resultSetFromUuid(String resultSetUUID) {
        if (resultSetUUID == null || resultSetUUID.isBlank()) {
            return null;
        }
        return new RemoteProxyResultSet(resultSetUUID, this.statementService, this.connection, null);
    }

    private Object[] toObjectArray(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof List<?>) {
            return ((List<?>) raw).toArray();
        }
        if (raw instanceof Object[]) {
            return (Object[]) raw;
        }
        if (raw.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(raw);
            Object[] converted = new Object[length];
            for (int i = 0; i < length; i++) {
                converted[i] = java.lang.reflect.Array.get(raw, i);
            }
            return converted;
        }
        return new Object[]{raw};
    }

    private CallResourceRequest.Builder newCallBuilder() {
        return CallResourceRequest.newBuilder()
                .setSession(this.connection.getSession())
                .setResourceType(ResourceType.RES_SAVEPOINT)
                .setResourceUUID(this.uuid);
    }

    private <T> T callProxy(CallType callType, String target, Class<?> returnType) throws SQLException {
        return this.callProxy(callType, target, returnType, Constants.EMPTY_OBJECT_LIST);
    }

    private <T> T callProxy(CallType callType, String target, Class<?> returnType, List<Object> params) throws SQLException {
        CallResourceRequest.Builder reqBuilder = this.newCallBuilder();
        reqBuilder.setTarget(
                TargetCall.newBuilder()
                        .setCallType(callType)
                        .setResourceName(target)
                        .addAllParams(ProtoConverter.objectListToParameterValues(params))
                        .build()
        );
        CallResourceResponse response = this.statementService.callResource(reqBuilder.build());
        this.connection.setSession(response.getSession());
        if (Void.class.equals(returnType)) {
            return null;
        }
        List<ParameterValue> values = response.getValuesList();
        if (values.isEmpty()) {
            return null;
        }
        Object result = ProtoConverter.fromParameterValue(values.get(0));
        return (T) result;
    }
}
