package org.openjproxy.grpc.server.action.transaction;

import com.openjproxy.grpc.DbName;
import com.openjproxy.grpc.OpResult;
import com.openjproxy.grpc.ResultType;
import com.openjproxy.grpc.SessionInfo;
import com.openjproxy.grpc.SqlErrorType;
import com.openjproxy.grpc.StatementRequest;
import io.grpc.stub.StreamObserver;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.openjproxy.constants.CommonConstants;
import org.openjproxy.grpc.ProtoConverter;
import org.openjproxy.grpc.dto.Parameter;
import org.openjproxy.grpc.server.ConnectionAcquisitionManager;
import org.openjproxy.grpc.server.ConnectionSessionDTO;
import org.openjproxy.grpc.server.LobDataBlocksInputStream;
import org.openjproxy.grpc.server.SessionManager;
import org.openjproxy.grpc.server.action.Action;
import org.openjproxy.grpc.server.action.ActionContext;
import org.openjproxy.grpc.server.cache.QueryCacheHelper;
import org.openjproxy.grpc.server.sql.SqlSessionAffinityDetector;
import org.openjproxy.grpc.server.statement.EagerCloseHelper;
import org.openjproxy.grpc.server.statement.ParameterHandler;
import org.openjproxy.grpc.server.statement.StatementFactory;
import org.openjproxy.grpc.server.utils.StatementRequestValidator;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.openjproxy.grpc.server.action.streaming.SessionConnectionHelper.sessionConnection;
import static org.openjproxy.grpc.server.action.transaction.CommandExecutionHelper.executeWithResilience;

/**
 * Action to execute SQL update statements (INSERT, UPDATE, DELETE).
 * Handles both regular updates and batch operations, with support for prepared
 * statements, LOB parameters, session affinity, and slow query segregation.
 *
 * @see Action
 */
@SuppressWarnings("java:S6548")
@Slf4j
public class ExecuteUpdateAction implements Action<StatementRequest, OpResult> {

    private static final String UPDATE = "update";

    private static final ExecuteUpdateAction INSTANCE = new ExecuteUpdateAction();

    /**
     * Private constructor for singleton.
     */
    private ExecuteUpdateAction() {
    }

    /**
     * Returns the singleton instance of this action.
     *
     * @return the singleton instance
     */
    public static ExecuteUpdateAction getInstance() {
        return INSTANCE;
    }

    /**
     * Executes an SQL update statement with session validation, circuit breaker
     * checks, and slow query segregation.
     *
     * @param context          the action context containing shared state and
     *                         services
     * @param request          the statement request with SQL and parameters
     * @param responseObserver the gRPC response observer for sending the result
     */
    @SneakyThrows
    @Override
    public void execute(ActionContext context, StatementRequest request, StreamObserver<OpResult> responseObserver) {
        log.debug("Executing update {}", request.getSql());

        executeWithResilience(context, request, responseObserver,
                () -> {
                    OpResult result = executeUpdateInternal(context, request);
                    responseObserver.onNext(result);
                    responseObserver.onCompleted();
                },
                SqlErrorType.SQL_EXCEPTION, UPDATE);
    }

    /**
     * Internal method for executing updates without segregation logic.
     *
     * @param actionContext the action context with session manager and connection
     *                      info
     * @param request       the statement request with SQL and parameters
     * @return the operation result (row count or batch statement UUID)
     * @throws SQLException if the update fails
     */
    @SuppressWarnings("java:S2095")
    private OpResult executeUpdateInternal(ActionContext actionContext, StatementRequest request) throws SQLException {
        OpResult eagerResult = tryExecuteUpdateEagerClose(actionContext, request);
        if (eagerResult != null) {
            return eagerResult;
        }

        ConnectionSessionDTO dto = null;
        UpdateExecutionResult executionResult = new UpdateExecutionResult();

        var sessionManager = actionContext.getSessionManager();

        try {
            // Check if SQL requires session affinity (temporary tables, session variables,
            // etc.)
            boolean requiresSessionAffinity = SqlSessionAffinityDetector.requiresSessionAffinity(request.getSql());
            boolean requiresGeneratedKeys = StatementRequestValidator.requiresGeneratedKeysTracking(request);

            dto = sessionConnection(actionContext, request.getSession(),
                    StatementRequestValidator.isAddBatchOperation(request)
                            || requiresGeneratedKeys
                            || requiresSessionAffinity);

            List<Parameter> params = ProtoConverter.fromProtoList(request.getParametersList());
            PreparedStatement ps = dto.getSession() != null && !dto.getSession().getSessionUUID().isBlank()
                    && !request.getStatementUUID().isBlank()
                    ? sessionManager.getPreparedStatement(dto.getSession(), request.getStatementUUID())
                    : null;

            executeSqlAndCaptureOutcome(sessionManager, dto, request, params, ps, requiresGeneratedKeys, executionResult);

            OpResult result = buildOpResult(request, dto.getSession(), executionResult.psUUID, executionResult.updated,
                    executionResult.generatedKeysUuid, actionContext);

            // Phase 9: Cache Invalidation (after successful update)
            org.openjproxy.grpc.server.cache.QueryCacheHelper.invalidateCacheIfEnabled(actionContext, dto.getSession(), request.getSql());

            // Read/write splitting: mark write for sticky session (routes subsequent reads to primary)
            markStickySessionAfterWrite(actionContext, dto);


            return result;
        } finally {
            closeStatementAndConnectionIfNoSession(dto, executionResult.statement);
        }
    }

    private OpResult tryExecuteUpdateEagerClose(ActionContext actionContext, StatementRequest request) throws SQLException {
        if (!isEagerCloseEnabledForRequest(actionContext, request)) {
            return null;
        }
        if (!EagerCloseHelper.canEagerCloseExecuteUpdate(request, request.getSession())) {
            return null;
        }
        log.debug("Attempting eager-close path for executeUpdate");
        return executeUpdateEagerClose(actionContext, request);
    }

    private boolean isEagerCloseEnabledForRequest(ActionContext actionContext, StatementRequest request) {
        if (request.getSession() == null) {
            return actionContext.getServerConfiguration().isStatementEagerCloseEnabled();
        }
        String connHash = request.getSession().getConnHash();
        return actionContext.getStatementEagerCloseEnabledByConnHash()
                .getOrDefault(connHash, actionContext.getServerConfiguration().isStatementEagerCloseEnabled());
    }

    private void executeSqlAndCaptureOutcome(SessionManager sessionManager, ConnectionSessionDTO dto,
                                             StatementRequest request, List<Parameter> params,
                                             PreparedStatement existingPreparedStatement,
                                             boolean requiresGeneratedKeys,
                                             UpdateExecutionResult result) throws SQLException {
        PreparedStatement ps = existingPreparedStatement;
        if (CollectionUtils.isNotEmpty(params) || ps != null || requiresGeneratedKeys) {
            if (!request.getStatementUUID().isEmpty() && ps != null) {
                bindLobsAndParameters(sessionManager, dto, ps, params);
            } else {
                ps = StatementFactory.createPreparedStatement(sessionManager, dto, request.getSql(), params,
                        request);
                result.generatedKeysUuid = registerForGeneratedKeys(sessionManager, dto, request, ps);
            }
            if (StatementRequestValidator.isAddBatchOperation(request)) {
                result.psUUID = addBatchAndGetStatementUUID(sessionManager, dto, ps, request);
            } else {
                result.updated = ps.executeUpdate();
            }
            result.statement = ps;
            return;
        }
        result.statement = StatementFactory.createStatement(sessionManager, dto.getConnection(), request);
        result.updated = result.statement.executeUpdate(request.getSql());
    }

    /**
     * Binds LOB streams to the prepared statement and adds parameters.
     * For Postgres, waits for LOB streams to be fully consumed before continuing.
     *
     * @param sessionManager the session manager holding LOB data
     * @param dto            the connection and session DTO
     * @param ps             the prepared statement to bind parameters to
     * @param params         the parameters to bind
     * @throws SQLException if binding fails
     */
    @SuppressWarnings("unchecked")
    private void bindLobsAndParameters(SessionManager sessionManager, ConnectionSessionDTO dto,
                                       PreparedStatement ps, List<Parameter> params) throws SQLException {
        Collection<Object> lobs = sessionManager.getLobs(dto.getSession());
        for (Object o : lobs) {
            LobDataBlocksInputStream lobIS = (LobDataBlocksInputStream) o;
            Map<String, Object> metadata = (Map<String, Object>) sessionManager.getAttr(dto.getSession(),
                    lobIS.getUuid());
            Integer parameterIndex = (Integer) metadata
                    .get(CommonConstants.PREPARED_STATEMENT_BINARY_STREAM_INDEX + "");
            ps.setBinaryStream(parameterIndex, lobIS);
        }
        if (DbName.POSTGRES.equals(dto.getDbName())) {
            sessionManager.waitLobStreamsConsumption(dto.getSession());
        }
        ParameterHandler.addParametersPreparedStatement(sessionManager, dto.getSession(), ps, params);
    }

    /**
     * Registers the prepared statement for generated-key tracking when requested
     * (via RETURN_GENERATED_KEYS, column indexes, or column names), and returns
     * the assigned statement UUID. Returns an empty string when tracking is not
     * required.
     *
     * @param sessionManager the session manager for statement registration
     * @param dto            the connection and session DTO
     * @param request        the statement request
     * @param ps             the prepared statement to register
     * @return the registered statement UUID, or an empty string if not applicable
     * @throws SQLException if registration fails
     */
    private String registerForGeneratedKeys(SessionManager sessionManager, ConnectionSessionDTO dto,
                                            StatementRequest request, PreparedStatement ps) throws SQLException {
        if (StatementRequestValidator.requiresGeneratedKeysTracking(request)
                && !StatementRequestValidator.isAddBatchOperation(request)) {
            return sessionManager.registerPreparedStatement(dto.getSession(), ps);
        }
        return "";
    }

    /**
     * Adds the prepared statement to the batch and returns the statement UUID,
     * either by registering a new prepared statement or reusing the existing one.
     *
     * @param sessionManager the session manager for statement registration
     * @param dto            the connection and session DTO
     * @param ps             the prepared statement to add to the batch
     * @param request        the statement request
     * @return the statement UUID (newly registered or from the request)
     * @throws SQLException if adding to batch or registering fails
     */
    private String addBatchAndGetStatementUUID(SessionManager sessionManager, ConnectionSessionDTO dto,
                                               PreparedStatement ps, StatementRequest request) throws SQLException {
        ps.addBatch();
        if (request.getStatementUUID().isBlank()) {
            return sessionManager.registerPreparedStatement(dto.getSession(), ps);
        } else {
            return request.getStatementUUID();
        }
    }

    /**
     * Builds the appropriate {@link OpResult} based on whether it was an add-batch
     * operation (returns UUID) or a regular update (returns row count). Sets the
     * generated-keys UUID on the result when one was registered.
     *
     * @param request           the statement request
     * @param sessionInfo       the session info to include in the result
     * @param psUUID            the prepared statement UUID (for batch operations)
     * @param updated           the row count (for regular updates)
     * @param generatedKeysUuid the registered prepared statement UUID for
     *                          generated-key tracking, or empty string if not used
     * @return the built {@link OpResult}
     */
    private OpResult buildOpResult(StatementRequest request, SessionInfo sessionInfo,
                                   String psUUID, int updated, String generatedKeysUuid,
                                   ActionContext context) {
        SessionInfo enrichedSession = org.openjproxy.grpc.server.utils.SessionInfoUtils
                .enrichWithThrottle(sessionInfo, context);
        OpResult.Builder builder = OpResult.newBuilder().setSession(enrichedSession);
        if (!generatedKeysUuid.isEmpty()) {
            builder.setUuid(generatedKeysUuid);
        }
        if (StatementRequestValidator.isAddBatchOperation(request)) {
            return builder.setType(ResultType.UUID_STRING).setUuidValue(psUUID).build();
        }
        return builder.setType(ResultType.INTEGER).setIntValue(updated).build();
    }

    /**
     * Executes a non-transactional DML update using the eager-close path.
     *
     * <p>Acquires a physical connection, executes the update, and immediately
     * releases all JDBC resources using try-with-resources. No server-side session
     * is created or modified.
     *
     * <p>Returns {@code null} when the pooled DataSource is unavailable for the
     * given connection hash, signalling the caller to fall back to the standard path.
     *
     * @param actionContext the action context
     * @param request       the statement request
     * @return the operation result, or {@code null} if the standard path should be used
     * @throws SQLException if the update fails
     */
    private OpResult executeUpdateEagerClose(ActionContext actionContext, StatementRequest request)
            throws SQLException {
        String connHash = request.getSession().getConnHash();
        DataSource dataSource = actionContext.getDatasourceMap().get(connHash);
        if (dataSource == null) {
            // Not a pooled connection — fall through to the standard path
            return null;
        }

        List<Parameter> params = ProtoConverter.fromProtoList(request.getParametersList());
        int updated;

        try (Connection conn = ConnectionAcquisitionManager.acquireConnection(dataSource, connHash)) {
            if (CollectionUtils.isNotEmpty(params)) {
                try (PreparedStatement ps = conn.prepareStatement(request.getSql())) {
                    ParameterHandler.addParametersPreparedStatement(
                            actionContext.getSessionManager(), request.getSession(), ps, params);
                    updated = ps.executeUpdate();
                }
            } else {
                try (Statement stmt = conn.createStatement()) {
                    updated = stmt.executeUpdate(request.getSql());
                }
            }
        }

        QueryCacheHelper.invalidateCacheIfEnabled(actionContext, request.getSession(), request.getSql());
        return OpResult.newBuilder()
                .setType(ResultType.INTEGER)
                .setSession(request.getSession())
                .setIntValue(updated)
                .build();
    }

    /**
     * Closes the statement and its connection when there is no session (stateless
     * execution). This must be done when the connection was obtained without a
     * session, as it would otherwise be left open.
     *
     * @param dto  the connection and session DTO, may be {@code null} if
     *             {@code sessionConnection} was never reached
     * @param stmt the statement to close (may be null)
     */
    private void closeStatementAndConnectionIfNoSession(ConnectionSessionDTO dto, Statement stmt) {
        if ((dto == null || dto.getSession() == null || dto.getSession().getSessionUUID().isEmpty()) && stmt != null) {
            try {
                stmt.close();
            } catch (SQLException e) {
                log.error("Failure closing statement: {}", e.getMessage(), e);
            }
            try {
                stmt.getConnection().close();
            } catch (SQLException e) {
                log.error("Failure closing connection: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * Marks a write on the sticky-session tracker so that subsequent reads within the
     * configured window are routed to the primary (read-your-writes guarantee).
     * This is a no-op when read/write splitting is not configured.
     *
     * @param actionContext the action context
     * @param dto           the connection and session DTO used for the write
     */
    private void markStickySessionAfterWrite(ActionContext actionContext, ConnectionSessionDTO dto) {
        var registry = actionContext.getReadWriteDataSourceRegistry();
        if (registry == null || dto.getSession() == null) {
            return;
        }
        String connHash = dto.getSession().getConnHash();
        String primaryName = registry.getPrimaryName(connHash);
        if (primaryName != null) {
            registry.markWrite(primaryName);
            log.debug("Read/write splitting: sticky session marked for primary '{}' after write", primaryName);
        }
    }

    private static final class UpdateExecutionResult {
        private int updated;
        private String psUUID = "";
        private String generatedKeysUuid = "";
        private Statement statement;
    }
}
