package org.openjproxy.grpc.server.action.resource;

import com.openjproxy.grpc.CallResourceRequest;
import com.openjproxy.grpc.CallResourceResponse;
import com.openjproxy.grpc.CallType;
import com.openjproxy.grpc.DbName;
import com.openjproxy.grpc.ResourceType;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.constants.CommonConstants;
import org.openjproxy.grpc.ProtoConverter;
import org.openjproxy.grpc.server.ConnectionSessionDTO;
import org.openjproxy.grpc.server.action.Action;
import org.openjproxy.grpc.server.action.ActionContext;
import org.openjproxy.grpc.server.action.util.ProcessClusterHealthAction;
import org.openjproxy.grpc.server.utils.MethodNameGenerator;
import org.openjproxy.grpc.server.utils.MethodReflectionUtils;
import org.openjproxy.grpc.server.JavaSqlInterfacesConverter;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.openjproxy.grpc.server.Constants.EMPTY_LIST;
import static org.openjproxy.grpc.server.Constants.EMPTY_MAP;
import static org.openjproxy.grpc.server.GrpcExceptionHandler.sendSQLExceptionMetadata;
import static org.openjproxy.grpc.server.action.streaming.SessionConnectionHelper.sessionConnection;

/**
 * Action that invokes methods on JDBC resources (ResultSet, LOB, Statement, Connection, etc.)
 * via reflection.
 *
 * <p>This action handles the {@code callResource} gRPC operation, allowing clients to call
 * arbitrary methods on server-side JDBC resources. It resolves the resource by type and UUID
 * from the session, invokes the target method with the provided parameters, and returns the
 * result converted to protobuf format.
 *
 * <p>Supported resource types include:
 * <ul>
 *   <li>{@link ResourceType#RES_RESULT_SET} - ResultSet instances</li>
 *   <li>{@link ResourceType#RES_LOB} - LOB (Large Object) instances</li>
 *   <li>{@link ResourceType#RES_STATEMENT} - Statement instances (creates new if UUID is blank)</li>
 *   <li>{@link ResourceType#RES_PREPARED_STATEMENT} - PreparedStatement instances</li>
 *   <li>{@link ResourceType#RES_CALLABLE_STATEMENT} - CallableStatement instances</li>
 *   <li>{@link ResourceType#RES_CONNECTION} - Connection instances</li>
 *   <li>{@link ResourceType#RES_SAVEPOINT} - Savepoint instances</li>
 * </ul>
 *
 * <p>Chained calls are supported (e.g., {@code getMetadata().isAutoIncrement(int column)}).
 * Returned ResultSet, Array, CallableStatement, and Savepoint instances are automatically
 * registered with the session manager and replaced by UUIDs in the response.
 *
 * <p>DB2 has special handling for ResultSet metadata retrieval, which is delegated to
 * {@link #db2SpecialResultSetMetadata}.
 *
 * <p>This class is a thread-safe singleton. Use {@link #getInstance()} to obtain the instance.
 *
 * @see Action
 * @see ActionContext
 */
@Slf4j
@SuppressWarnings("java:S6548")
public class CallResourceAction implements Action<CallResourceRequest, CallResourceResponse> {

    private static final CallResourceAction INSTANCE = new CallResourceAction();
    private static final String RESULT_SET_METADATA_ATTR_PREFIX = "rsMetadata|";

    private CallResourceAction() {
        // Private constructor prevents external instantiation
    }

    /**
     * Returns the singleton instance of this action.
     *
     * @return the sole instance of {@code CallResourceAction}
     */
    public static CallResourceAction getInstance() {
        return INSTANCE;
    }

    /**
     * Executes the call resource operation: resolves the target resource, invokes the
     * requested method via reflection, and sends the result (or error) to the response observer.
     *
     * <p>Processes cluster health from the request, validates the session, resolves the resource
     * by type and UUID, handles special cases (e.g., DB2 metadata, Savepoint resolution), and
     * supports chained calls for nested method invocations.
     *
     * @param context         the action context containing session manager and database metadata
     * @param request         the call resource request with session, resource type, UUID, and target method
     * @param responseObserver the gRPC observer to receive the response or error metadata
     */
    @Override
    public void execute(ActionContext context, CallResourceRequest request, StreamObserver<CallResourceResponse> responseObserver) {
        // Process cluster health from the request
        ProcessClusterHealthAction.getInstance().execute(context, request.getSession());

        try {
            if (!request.hasSession()) {
                throw new SQLException("No active session.");
            }

            CallResourceResponse.Builder responseBuilder = CallResourceResponse.newBuilder();

            if (this.db2SpecialResultSetMetadata(context, request, responseObserver)) {
                return;
            }

            Object resource;
            switch (request.getResourceType()) {
                case RES_RESULT_SET:
                    resource = context.getSessionManager().getResultSet(request.getSession(), request.getResourceUUID());
                    break;
                case RES_LOB:
                    resource = context.getSessionManager().getLob(request.getSession(), request.getResourceUUID());
                    break;
                case RES_STATEMENT: {
                    ConnectionSessionDTO csDto = sessionConnection(context, request.getSession(), true);
                    responseBuilder.setSession(csDto.getSession());
                    java.sql.Statement statement;
                    if (!request.getResourceUUID().isBlank()) {
                        statement = context.getSessionManager().getStatement(csDto.getSession(), request.getResourceUUID());
                    } else {
                        statement = csDto.getConnection().createStatement();
                        String uuid = context.getSessionManager().registerStatement(csDto.getSession(), statement);
                        responseBuilder.setResourceUUID(uuid);
                    }
                    resource = statement;
                    break;
                }
                case RES_PREPARED_STATEMENT: {
                    ConnectionSessionDTO csDto = sessionConnection(context, request.getSession(), true);
                    responseBuilder.setSession(csDto.getSession());
                    PreparedStatement ps;
                    if (!request.getResourceUUID().isBlank()) {
                        ps = context.getSessionManager().getPreparedStatement(request.getSession(), request.getResourceUUID());
                    } else {
                        Map<String, Object> mapProperties = EMPTY_MAP;
                        if (!request.getPropertiesList().isEmpty()) {
                            mapProperties = ProtoConverter.propertiesFromProto(request.getPropertiesList());
                        }
                        ps = csDto.getConnection().prepareStatement((String) mapProperties.get(CommonConstants.PREPARED_STATEMENT_SQL_KEY));
                        String uuid = context.getSessionManager().registerPreparedStatement(csDto.getSession(), ps);
                        responseBuilder.setResourceUUID(uuid);
                    }
                    resource = ps;
                    break;
                }
                case RES_CALLABLE_STATEMENT:
                    resource = context.getSessionManager().getCallableStatement(request.getSession(), request.getResourceUUID());
                    break;
                case RES_CONNECTION: {
                    ConnectionSessionDTO csDto = sessionConnection(context, request.getSession(), true);
                    responseBuilder.setSession(csDto.getSession());
                    resource = csDto.getConnection();
                    break;
                }
                case RES_SAVEPOINT:
                    resource = context.getSessionManager().getAttr(request.getSession(), request.getResourceUUID());
                    break;
                default:
                    throw new RuntimeException("Resource type invalid");
            }

            if (responseBuilder.getSession() == null || responseBuilder.getSession().getSessionUUID().isBlank()) {
                responseBuilder.setSession(request.getSession());
            }

            List<Object> paramsReceived = (request.getTarget().getParamsCount() > 0) ?
                    ProtoConverter.parameterValuesToObjectList(request.getTarget().getParamsList()) : EMPTY_LIST;
            Class<?> clazz = resource.getClass();
            if ((!paramsReceived.isEmpty()) &&
                    ((CallType.CALL_RELEASE.equals(request.getTarget().getCallType()) &&
                            "Savepoint".equalsIgnoreCase(request.getTarget().getResourceName())) ||
                            (CallType.CALL_ROLLBACK.equals(request.getTarget().getCallType()))
                    )
            ) {
                Savepoint savepoint = (Savepoint) context.getSessionManager().getAttr(request.getSession(),
                        (String) paramsReceived.get(0));
                paramsReceived.set(0, savepoint);
            }
            Method method = MethodReflectionUtils.findMethodByName(JavaSqlInterfacesConverter.interfaceClass(clazz),
                    MethodNameGenerator.methodName(request.getTarget()), paramsReceived);
            java.lang.reflect.Parameter[] params = method.getParameters();
            Object resultFirstLevel;
            if (params != null && params.length > 0) {
                resultFirstLevel = method.invoke(resource, paramsReceived.toArray());
                if (resultFirstLevel instanceof CallableStatement cs) {
                    resultFirstLevel = context.getSessionManager().registerCallableStatement(responseBuilder.getSession(), cs);
                }
            } else {
                resultFirstLevel = method.invoke(resource);
                if (resultFirstLevel instanceof ResultSet rs) {
                    resultFirstLevel = context.getSessionManager().registerResultSet(responseBuilder.getSession(), rs);
                } else if (resultFirstLevel instanceof Array array) {
                    String arrayUUID = UUID.randomUUID().toString();
                    context.getSessionManager().registerAttr(responseBuilder.getSession(), arrayUUID, array);
                    resultFirstLevel = arrayUUID;
                }
            }
            if (resultFirstLevel instanceof Savepoint sp) {
                String uuid = UUID.randomUUID().toString();
                resultFirstLevel = uuid;
                context.getSessionManager().registerAttr(responseBuilder.getSession(), uuid, sp);
            }
            if (resultFirstLevel instanceof SQLWarning warning) {
                // SQLWarning (and vendor-specific subclasses such as SQLServerWarning) cannot be
                // transported as-is because ProtoConverter only supports primitives/Map/List/Properties.
                // Flatten the entire warning chain to a List<Map> so that message, sqlState,
                // vendorCode, and all chained warnings are preserved across the gRPC boundary.
                resultFirstLevel = sqlWarningToList(warning);
            }
            if (request.getTarget().hasNextCall()) {
                //Second level calls, for cases like getMetadata().isAutoIncrement(int column)
                Class<?> clazzNext = resultFirstLevel.getClass();
                List<Object> paramsReceived2 = (request.getTarget().getNextCall().getParamsCount() > 0) ?
                        ProtoConverter.parameterValuesToObjectList(request.getTarget().getNextCall().getParamsList()) :
                        EMPTY_LIST;
                Method methodNext = MethodReflectionUtils.findMethodByName(JavaSqlInterfacesConverter.interfaceClass(clazzNext),
                        MethodNameGenerator.methodName(request.getTarget().getNextCall()),
                        paramsReceived2);
                params = methodNext.getParameters();
                Object resultSecondLevel;
                if (params != null && params.length > 0) {
                    resultSecondLevel = methodNext.invoke(resultFirstLevel, paramsReceived2.toArray());
                } else {
                    resultSecondLevel = methodNext.invoke(resultFirstLevel);
                }
                if (resultSecondLevel instanceof ResultSet rs) {
                    resultSecondLevel = context.getSessionManager().registerResultSet(responseBuilder.getSession(), rs);
                }
                responseBuilder.addValues(ProtoConverter.toParameterValue(resultSecondLevel));
            } else {
                responseBuilder.addValues(ProtoConverter.toParameterValue(resultFirstLevel));
            }

            responseObserver.onNext(responseBuilder.build());
            responseObserver.onCompleted();
        } catch (SQLException se) {
            sendSQLExceptionMetadata(se, responseObserver);
        } catch (InvocationTargetException e) {
            if (e.getTargetException() instanceof SQLException sqlException) {
                sendSQLExceptionMetadata(sqlException, responseObserver);
            } else {
                sendSQLExceptionMetadata(new SQLException("Unable to call resource: " + e.getTargetException().getMessage()),
                        responseObserver);
            }
        } catch (Exception e) {
            sendSQLExceptionMetadata(new SQLException("Unable to call resource: " + e.getMessage(), e), responseObserver);
        }
    }

    /**
     * Handles DB2-specific ResultSet metadata retrieval.
     *
     * <p>DB2 stores ResultSet metadata separately from the ResultSet. When the request targets
     * {@code getMetadata()} on a ResultSet for a DB2 connection, this method retrieves the
     * cached metadata from the session and invokes the requested metadata method (e.g.,
     * {@code isAutoIncrement(int column)}) directly.
     *
     * @param context         the action context
     * @param request         the call resource request
     * @param responseObserver the gRPC observer to receive the response
     * @return {@code true} if the DB2 special case was handled and the response was sent;
     *         {@code false} if the request does not match this case and normal processing should continue
     * @throws SQLException if invoking the metadata method fails
     */
    private boolean db2SpecialResultSetMetadata(ActionContext context, CallResourceRequest request, StreamObserver<CallResourceResponse> responseObserver) throws SQLException {
        if (DbName.DB2.equals(context.getDbNameMap().get(request.getSession().getConnHash())) &&
                ResourceType.RES_RESULT_SET.equals(request.getResourceType()) &&
                CallType.CALL_GET.equals(request.getTarget().getCallType()) &&
                "Metadata".equalsIgnoreCase(request.getTarget().getResourceName())) {
            ResultSetMetaData resultSetMetaData = (ResultSetMetaData) context.getSessionManager().getAttr(request.getSession(),
                    RESULT_SET_METADATA_ATTR_PREFIX + request.getResourceUUID());
            List<Object> paramsReceived = (request.getTarget().getNextCall().getParamsCount() > 0) ?
                    ProtoConverter.parameterValuesToObjectList(request.getTarget().getNextCall().getParamsList()) :
                    EMPTY_LIST;
            Method methodNext = MethodReflectionUtils.findMethodByName(ResultSetMetaData.class,
                    MethodNameGenerator.methodName(request.getTarget().getNextCall()),
                    paramsReceived);
            try {
                Object metadataResult = methodNext.invoke(resultSetMetaData, paramsReceived.toArray());
                responseObserver.onNext(CallResourceResponse.newBuilder()
                        .setSession(request.getSession())
                        .addValues(ProtoConverter.toParameterValue(metadataResult))
                        .build());
                responseObserver.onCompleted();
                return true;
            } catch (Exception e) {
                throw new SQLException("Failed to call DB2 special result set metadata", e);
            }
        }
        return false;
    }

    /**
     * Converts a {@link SQLWarning} chain to a {@code List<Map<String, Object>>} suitable for
     * transport via {@link org.openjproxy.grpc.transport.ProtoSerialization}.
     *
     * <p>Each map in the list represents one warning node and contains:
     * <ul>
     *   <li>{@code "message"} – the warning message (may be null)</li>
     *   <li>{@code "sqlState"} – the SQLSTATE code (may be null per JDBC spec)</li>
     *   <li>{@code "vendorCode"} – the database-specific vendor error code (int)</li>
     * </ul>
     *
     * <p>The list is ordered from head to tail following {@link SQLWarning#getNextWarning()}.
     *
     * @param head the first node of the warning chain; must not be null
     * @return an ordered list of warning attribute maps
     */
    private List<Object> sqlWarningToList(SQLWarning head) {
        List<Object> list = new ArrayList<>();
        SQLWarning cursor = head;
        while (cursor != null) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("message", cursor.getMessage());
            entry.put("sqlState", cursor.getSQLState());
            entry.put("vendorCode", cursor.getErrorCode());
            list.add(entry);
            cursor = cursor.getNextWarning();
        }
        return list;
    }
}
