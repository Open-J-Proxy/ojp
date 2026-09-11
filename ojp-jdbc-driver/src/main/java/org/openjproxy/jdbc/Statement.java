package org.openjproxy.jdbc;

import com.openjproxy.grpc.CallResourceRequest;
import com.openjproxy.grpc.CallResourceResponse;
import com.openjproxy.grpc.CallType;
import com.openjproxy.grpc.OpResult;
import com.openjproxy.grpc.ParameterValue;
import com.openjproxy.grpc.ResourceType;
import com.openjproxy.grpc.TargetCall;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.grpc.ProtoConverter;
import org.openjproxy.grpc.client.StatementService;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLTransientConnectionException;
import java.sql.SQLWarning;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.openjproxy.grpc.client.GrpcExceptionHandler.handle;
import static org.openjproxy.jdbc.Constants.EMPTY_PARAMETERS_LIST;

@Slf4j
public class Statement implements java.sql.Statement {

    private final Connection connection;
    private final StatementService statementService;
    private final Map<String, Object> properties;
    @Setter
    @Getter
    private String statementUUID;
    private int maxRows;
    private ResourceType resourceType;

    protected boolean closed;
    protected ResultSet lastResultSet;
    // JDBC contract: -1 means "no update count" (i.e., the last executed statement returned a
    // ResultSet, or no statement has been executed yet). 0 has a different, valid meaning
    // ("an update statement affected 0 rows") and must never be used as the default here,
    // otherwise callers relying on getUpdateCount()==-1 to detect "no more results" (e.g. DataGrip)
    // will incorrectly believe another result is pending and hang waiting for it.
    protected int lastUpdateCount = -1;
    // Once the server has told us there are no more results for the current execution,
    // cache that terminal state locally to avoid a network round-trip on every redundant
    // getMoreResults() call (some clients poll this repeatedly after the query has finished).
    private boolean moreResultsExhausted;

    protected void resetMoreResultsState() {
        this.moreResultsExhausted = false;
        this.lastUpdateCount = -1;
    }

    public Statement(Connection connection, StatementService statementService) {
        this(connection, statementService, null);
    }

    public Statement(Connection connection, StatementService statementService, Map<String, Object> properties) {
        this(connection, statementService, properties, ResourceType.RES_STATEMENT);
    }

    public Statement(Connection connection, StatementService statementService, Map<String, Object> properties,
                     ResourceType resourceType) {
        log.debug("Statement: constructor(connection, statementService, properties, resourceType) called");
        this.connection = connection;
        this.statementService = statementService;
        this.properties = properties;
        this.resourceType = resourceType;
        this.closed = false;
    }

    protected void checkClosed() throws SQLException {
        if (this.closed) {
            throw new SQLException("Statement is closed.");
        }
    }

    /**
     * Attempts to acquire a throttle slot before executing a statement.
     * Returns true if a slot was acquired (caller must call releaseThrottle after the work),
     * or false if throttling is disabled.
     * Throws SQLTransientException immediately if the limit is reached.
     */
    protected boolean acquireThrottle(ClientThrottleManager throttle, ClientThrottleMode mode,
                                    boolean inTransaction) throws SQLException {
        if (throttle == null) {
            return false;
        }
        if (!throttle.tryAcquire(mode, inTransaction)) {
            throw new SQLTransientConnectionException(
                    "Client throttle limit reached; request rejected to avoid overloading the database");
        }
        return true;
    }

    /**
     * Extracts the overload lane from a gRPC RESOURCE_EXHAUSTED trailer
     * ({@code ojp-overload-lane}). Returns {@link ClientThrottleManager.OverloadLane#UNKNOWN}
     * when no trailer is present (server pre-Phase-D or non-overload error).
     */
    private static ClientThrottleManager.OverloadLane extractLane(StatusRuntimeException sre) {
        io.grpc.Metadata trailers = sre.getTrailers();
        if (trailers == null) {
            return ClientThrottleManager.OverloadLane.UNKNOWN;
        }
        io.grpc.Metadata.Key<String> key = io.grpc.Metadata.Key.of(ClientThrottleManager.OVERLOAD_LANE_HEADER,
                io.grpc.Metadata.ASCII_STRING_MARSHALLER);
        return ClientThrottleManager.OverloadLane.parse(trailers.get(key));
    }

    /**
     * If the exception is a RESOURCE_EXHAUSTED status from the server, notifies the throttle
     * manager to halve its reactive limit (AIMD multiplicative decrease) so that the next
     * request is rejected client-side instead of hitting the still-overloaded server.
     * The original exception is always returned to the caller for rethrowing.
     *
     * <p>Reads the {@code ojp-overload-lane} trailer (when present) and routes through
     * {@link ClientThrottleManager#notifyServerOverload(ClientThrottleManager.OverloadLane)},
     * which suppresses halving for slow-lane and queue-depth signals (cross-lane
     * contamination fix).</p>
     */
    protected StatusRuntimeException onServerOverload(ClientThrottleManager throttle, ClientThrottleMode mode,
                                                      StatusRuntimeException sre) {
        if (throttle != null && mode != ClientThrottleMode.OFF
                && sre.getStatus().getCode() == Status.Code.RESOURCE_EXHAUSTED) {
            throttle.notifyServerOverload(extractLane(sre));
        }
        return sre;
    }

    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
        log.debug("executeQuery: {}", sql);
        checkClosed();
        this.lastUpdateCount = -1;
        resetMoreResultsState();
        ClientThrottleManager throttle = this.connection.getThrottleManager();
        ClientThrottleMode mode = this.connection.getThrottleMode();
        // getAutoCommit() may throw SQLException; evaluate before acquiring a slot
        // so that release() is never called without a matching acquire.
        boolean inTransaction = !this.connection.getAutoCommit();
        boolean acquired = acquireThrottle(throttle, mode, inTransaction);
        try {
            Iterator<OpResult> itResults = this.statementService.executeQuery(this.connection.getSession(), sql,
                    EMPTY_PARAMETERS_LIST, this.statementUUID, this.properties);
            return new ResultSet(itResults, this.statementService, this);
        } catch (StatusRuntimeException sre) {
            throw handle(onServerOverload(throttle, mode, sre));
        } finally {
            if (acquired) {
                throttle.release(mode, inTransaction);
            }
        }
    }

    @Override
    public int executeUpdate(String sql) throws SQLException {
        log.debug("executeUpdate: {}", sql);
        checkClosed();
        resetMoreResultsState();
        ClientThrottleManager throttle = this.connection.getThrottleManager();
        ClientThrottleMode mode = this.connection.getThrottleMode();
        // getAutoCommit() may throw SQLException; evaluate before acquiring a slot
        // so that release() is never called without a matching acquire.
        boolean inTransaction = !this.connection.getAutoCommit();
        boolean acquired = acquireThrottle(throttle, mode, inTransaction);
        try {
            OpResult result = this.statementService.executeUpdate(this.connection.getSession(), sql, EMPTY_PARAMETERS_LIST,
                    this.statementUUID, this.properties);
            this.connection.setSession(result.getSession());
            return result.getIntValue();
        } catch (StatusRuntimeException sre) {
            throw handle(onServerOverload(throttle, mode, sre));
        } finally {
            if (acquired) {
                throttle.release(mode, inTransaction);
            }
        }
    }

    @Override
    public void close() throws SQLException {
        log.debug("close called");
        this.closed = true;
        if (this.getStatementUUID() != null) {
            this.callProxy(CallType.CALL_CLOSE, "", Void.class);
        }
    }

    @Override
    public int getMaxFieldSize() throws SQLException {
        log.debug("getMaxFieldSize called");
        checkClosed();
        return this.callProxy(CallType.CALL_GET, "MaxFieldSize", Integer.class);
    }

    @Override
    public void setMaxFieldSize(int max) throws SQLException {
        log.debug("setMaxFieldSize: {}", max);
        checkClosed();
        this.callProxy(CallType.CALL_SET, "MaxFieldSize", Void.class, Arrays.asList(max));
    }

    @Override
    public int getMaxRows() throws SQLException {
        log.debug("getMaxRows called");
        checkClosed();
        return this.maxRows;
    }

    @Override
    public void setMaxRows(int max) throws SQLException {
        log.debug("setMaxRows: {}", max);
        checkClosed();
        this.callProxy(CallType.CALL_SET, "MaxRows", Void.class, Arrays.asList(max));
        this.maxRows = max;
    }

    @Override
    public void setEscapeProcessing(boolean enable) throws SQLException {
        log.debug("setEscapeProcessing: {}", enable);
        checkClosed();
        this.callProxy(CallType.CALL_SET, "EscapeProcessing", Void.class, Arrays.asList(enable));
    }

    @Override
    public int getQueryTimeout() throws SQLException {
        log.debug("getQueryTimeout called");
        checkClosed();
        return this.callProxy(CallType.CALL_GET, "QueryTimeout", Integer.class);
    }

    @Override
    public void setQueryTimeout(int seconds) throws SQLException {
        log.debug("setQueryTimeout: {}", seconds);
        checkClosed();
        this.callProxy(CallType.CALL_SET, "QueryTimeout", Void.class, Arrays.asList(seconds));
    }

    @Override
    public void cancel() throws SQLException {
        log.debug("cancel called");
        checkClosed();
        this.callProxy(CallType.CALL_CANCEL, "", Void.class);
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        log.debug("getWarnings called");
        checkClosed();
        // The server serializes a SQLWarning chain as List<Map<String,Object>> (see CallResourceAction),
        // because arbitrary Throwable subclasses cannot be transported via ProtoConverter.
        List<?> entries = this.callProxy(CallType.CALL_GET, "Warnings", List.class);
        return SqlWarningUtils.buildWarningChain(entries);
    }

    @Override
    public void clearWarnings() throws SQLException {
        log.debug("clearWarnings called");
        checkClosed();
        this.callProxy(CallType.CALL_CLEAR, "Warnings", Void.class);
    }

    @Override
    public void setCursorName(String name) throws SQLException {
        log.debug("setCursorName: {}", name);
        checkClosed();
        this.callProxy(CallType.CALL_SET, "CursorName", Void.class, Arrays.asList(name));
    }

    @Override
    public boolean execute(String sql) throws SQLException {
        log.debug("execute: {}", sql);
        checkClosed();
        if (SqlStatementClassifier.looksLikeQuery(sql)) {
            // Delegate to executeQuery
            ResultSet resultSet = this.executeQuery(sql);
            // Store the ResultSet for later retrieval if needed
            this.lastResultSet = resultSet;
            this.lastUpdateCount = -1;
            return true; // Indicates a ResultSet was returned
        } else {
            // Delegate to executeUpdate
            this.lastUpdateCount = this.executeUpdate(sql);
            return false; // Indicates no ResultSet was returned
        }
    }

    @Override
    public ResultSet getResultSet() throws SQLException {
        log.debug("getResultSet called");
        checkClosed();
        return this.lastResultSet;
    }

    @Override
    public int getUpdateCount() throws SQLException {
        log.debug("getUpdateCount called");
        checkClosed();
        return this.lastUpdateCount;
    }

    @Override
    public boolean getMoreResults() throws SQLException {
        log.debug("getMoreResults called");
        checkClosed();
        if (this.moreResultsExhausted) {
            return false;
        }
        boolean result = this.callProxy(CallType.CALL_GET, "MoreResults", Boolean.class);
        if (!result) {
            this.moreResultsExhausted = true;
        }
        return result;
    }

    @Override
    public void setFetchDirection(int direction) throws SQLException {
        log.debug("setFetchDirection: {}", direction);
        checkClosed();
        this.callProxy(CallType.CALL_SET, "FetchDirection", Void.class, Arrays.asList(direction));
    }

    @Override
    public int getFetchDirection() throws SQLException {
        log.debug("getFetchDirection called");
        checkClosed();
        return this.callProxy(CallType.CALL_GET, "FetchDirection", Integer.class);
    }

    @Override
    public void setFetchSize(int rows) throws SQLException {
        log.debug("setFetchSize: {}", rows);
        checkClosed();
        this.callProxy(CallType.CALL_SET, "FetchSize", Void.class, Arrays.asList(rows));
    }

    @Override
    public int getFetchSize() throws SQLException {
        log.debug("getFetchSize called");
        checkClosed();
        return this.callProxy(CallType.CALL_GET, "FetchSize", Integer.class);
    }

    @Override
    public int getResultSetConcurrency() throws SQLException {
        log.debug("getResultSetConcurrency called");
        checkClosed();
        return this.callProxy(CallType.CALL_GET, "ResultSetConcurrency", Integer.class);
    }

    @Override
    public int getResultSetType() throws SQLException {
        log.debug("getResultSetType called");
        checkClosed();
        return this.callProxy(CallType.CALL_GET, "ResultSetType", Integer.class);
    }

    @Override
    public void addBatch(String sql) throws SQLException {
        log.debug("addBatch: {}", sql);
        checkClosed();
        this.callProxy(CallType.CALL_ADD, "Batch", Void.class, Arrays.asList(sql));
    }

    @Override
    public void clearBatch() throws SQLException {
        log.debug("clearBatch called");
        checkClosed();
        this.callProxy(CallType.CALL_CLEAR, "Batch", Void.class);
    }

    @Override
    public int[] executeBatch() throws SQLException {
        log.debug("executeBatch called");
        checkClosed();
        resetMoreResultsState();
        return this.callProxy(CallType.CALL_EXECUTE, "Batch", int[].class);
    }

    @Override
    public Connection getConnection() throws SQLException {
        log.debug("getConnection called");
        checkClosed();
        return this.connection;
    }

    @Override
    public boolean getMoreResults(int current) throws SQLException {
        log.debug("getMoreResults: {}", current);
        checkClosed();
        if (this.moreResultsExhausted) {
            return false;
        }
        boolean result = this.callProxy(CallType.CALL_GET, "MoreResults", Boolean.class, Arrays.asList(current));
        if (!result) {
            this.moreResultsExhausted = true;
        }
        return result;
    }

    @Override
    public RemoteProxyResultSet getGeneratedKeys() throws SQLException {
        log.debug("getGeneratedKeys called");
        checkClosed();
        String resultSetUUID = this.callProxy(CallType.CALL_GET, "GeneratedKeys", String.class);
        return new RemoteProxyResultSet(resultSetUUID, this.statementService, this.connection, this);
    }

    @Override
    public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
        log.debug("executeUpdate: {}, autoGeneratedKeys={}", sql, autoGeneratedKeys);
        checkClosed();
        resetMoreResultsState();
        return this.callProxy(CallType.CALL_EXECUTE, "Update", Integer.class, Arrays.asList(sql, autoGeneratedKeys));
    }

    @Override
    public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
        log.debug("executeUpdate: {}, columnIndexes.length={}", sql, columnIndexes != null ? columnIndexes.length : 0);
        checkClosed();
        resetMoreResultsState();
        return this.callProxy(CallType.CALL_EXECUTE, "Update", Integer.class, Arrays.asList(sql, columnIndexes));
    }

    @Override
    public int executeUpdate(String sql, String[] columnNames) throws SQLException {
        log.debug("executeUpdate: {}, columnNames.length={}", sql, columnNames != null ? columnNames.length : 0);
        checkClosed();
        resetMoreResultsState();
        return this.callProxy(CallType.CALL_EXECUTE, "Update", Integer.class, Arrays.asList(sql, columnNames));
    }

    @Override
    public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
        log.debug("execute: {}, autoGeneratedKeys={}", sql, autoGeneratedKeys);
        checkClosed();
        resetMoreResultsState();
        return this.callProxy(CallType.CALL_EXECUTE, "", Boolean.class, Arrays.asList(sql, autoGeneratedKeys));
    }

    @Override
    public boolean execute(String sql, int[] columnIndexes) throws SQLException {
        log.debug("execute: {}, columnIndexes.length={}", sql, columnIndexes != null ? columnIndexes.length : 0);
        checkClosed();
        resetMoreResultsState();
        return this.callProxy(CallType.CALL_EXECUTE, "", Boolean.class, Arrays.asList(sql, columnIndexes));
    }

    @Override
    public boolean execute(String sql, String[] columnNames) throws SQLException {
        log.debug("execute: {}, columnNames.length={}", sql, columnNames != null ? columnNames.length : 0);
        checkClosed();
        resetMoreResultsState();
        return this.callProxy(CallType.CALL_EXECUTE, "", Boolean.class, Arrays.asList(sql, columnNames));
    }

    @Override
    public int getResultSetHoldability() throws SQLException {
        log.debug("getResultSetHoldability called");
        checkClosed();
        return this.callProxy(CallType.CALL_GET, "ResultSetHoldability", Integer.class);
    }

    @Override
    public boolean isClosed() throws SQLException {
        log.debug("isClosed called");
        return this.closed;
    }

    @Override
    public String enquoteIdentifier(String var1, boolean var2) throws SQLException {
        log.debug("enquoteIdentifier: {}, {}", var1, var2);
        checkClosed();
        return this.callProxy(CallType.CALL_ENQUOTE, "Identifier", String.class, Arrays.asList(var1, var2));
    }

    @Override
    public void setPoolable(boolean poolable) throws SQLException {
        log.debug("setPoolable: {}", poolable);
        checkClosed();
        this.callProxy(CallType.CALL_SET, "Poolable", Void.class, Arrays.asList(poolable));
    }

    @Override
    public boolean isPoolable() throws SQLException {
        log.debug("isPoolable called");
        checkClosed();
        return this.callProxy(CallType.CALL_IS, "Poolable", Boolean.class);
    }

    @Override
    public void closeOnCompletion() throws SQLException {
        log.debug("closeOnCompletion called");
        checkClosed();
        this.callProxy(CallType.CALL_CLOSE, "OnCompletion", Void.class);
    }

    @Override
    public boolean isCloseOnCompletion() throws SQLException {
        log.debug("isCloseOnCompletion called");
        checkClosed();
        return this.callProxy(CallType.CALL_IS, "CloseOnCompletion", Boolean.class);
    }

    @Override
    public boolean isSimpleIdentifier(String identifier) throws SQLException {
        log.debug("isSimpleIdentifier: {}", identifier);
        checkClosed();
        return this.callProxy(CallType.CALL_IS, "SimpleIdentifier", Boolean.class,
                Arrays.asList(identifier));
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        log.debug("unwrap: {}", iface);
        checkClosed();
        throw new SQLFeatureNotSupportedException("Not supported.");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        log.debug("isWrapperFor: {}", iface);
        checkClosed();
        throw new SQLFeatureNotSupportedException("Not supported.");
    }

    @Override
    public long getLargeUpdateCount() throws SQLException {
        log.debug("getLargeUpdateCount called");
        checkClosed();
        return this.callProxy(CallType.CALL_GET, "LargeUpdateCount", Long.class);
    }

    @Override
    public void setLargeMaxRows(long max) throws SQLException {
        log.debug("setLargeMaxRows: {}", max);
        checkClosed();
        this.callProxy(CallType.CALL_SET, "LargeMaxRows", Void.class, Arrays.asList(max));
    }

    @Override
    public long getLargeMaxRows() throws SQLException {
        log.debug("getLargeMaxRows called");
        checkClosed();
        return this.callProxy(CallType.CALL_GET, "LargeMaxRows", Long.class);
    }

    @Override
    public long[] executeLargeBatch() throws SQLException {
        log.debug("executeLargeBatch called");
        checkClosed();
        resetMoreResultsState();
        return this.callProxy(CallType.CALL_EXECUTE, "LargeBatch", long[].class);
    }

    @Override
    public long executeLargeUpdate(String sql) throws SQLException {
        log.debug("executeLargeUpdate: {}", sql);
        checkClosed();
        resetMoreResultsState();
        return this.callProxy(CallType.CALL_EXECUTE, "LargeUpdate", Long.class, Arrays.asList(sql));
    }

    @Override
    public long executeLargeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
        log.debug("executeLargeUpdate: {}, autoGeneratedKeys={}", sql, autoGeneratedKeys);
        checkClosed();
        resetMoreResultsState();
        return this.callProxy(CallType.CALL_EXECUTE, "LargeUpdate", Long.class,
                Arrays.asList(sql, autoGeneratedKeys));
    }

    @Override
    public long executeLargeUpdate(String sql, int columnIndexes[]) throws SQLException {
        log.debug("executeLargeUpdate: {}, columnIndexes.length={}", sql, columnIndexes != null ? columnIndexes.length : 0);
        checkClosed();
        resetMoreResultsState();
        return this.callProxy(CallType.CALL_EXECUTE, "LargeUpdate", Long.class,
                Arrays.asList(sql, columnIndexes));
    }

    @Override
    public long executeLargeUpdate(String sql, String columnNames[]) throws SQLException {
        log.debug("executeLargeUpdate: {}, columnNames.length={}", sql, columnNames != null ? columnNames.length : 0);
        checkClosed();
        resetMoreResultsState();
        return this.callProxy(CallType.CALL_EXECUTE, "LargeUpdate", Long.class,
                Arrays.asList(sql, columnNames));
    }

    protected CallResourceRequest.Builder newCallBuilder() {
        log.debug("newCallBuilder called");
        CallResourceRequest.Builder builder = CallResourceRequest.newBuilder()
                .setSession(this.connection.getSession())
                .setResourceType(this.resourceType);
        if (this.statementUUID != null) {
            builder.setResourceUUID(this.statementUUID);
        }
        if (this.properties != null) {
            builder.addAllProperties(ProtoConverter.propertiesToProto(this.properties));
        }
        return builder;
    }

    protected <T> T callProxy(CallType callType, String targetName, Class<?> returnType) throws SQLException {
        log.debug("callProxy: {}, {}, {}", callType, targetName, returnType);
        return this.callProxy(callType, targetName, returnType, Constants.EMPTY_OBJECT_LIST);
    }

    protected <T> T callProxy(CallType callType, String targetName, Class<?> returnType, List<Object> params) throws SQLException {
        log.debug("callProxy: {}, {}, {}, params.size={}", callType, targetName, returnType, params != null ? params.size() : 0);
        CallResourceRequest.Builder reqBuilder = this.newCallBuilder();
        reqBuilder.setTarget(
                TargetCall.newBuilder()
                        .setCallType(callType)
                        .setResourceName(targetName)
                        .addAllParams(ProtoConverter.objectListToParameterValues(params))
                        .build()
        );
        CallResourceResponse response = this.statementService.callResource(reqBuilder.build());
        this.connection.setSession(response.getSession());
        if (this.statementUUID == null && !response.getResourceUUID().isBlank()) {
            this.statementUUID = response.getResourceUUID();
        }
        if (Void.class.equals(returnType)) {
            return null;
        }

        // Convert ParameterValue list back to the expected type
        List<ParameterValue> values = response.getValuesList();
        if (values.isEmpty()) {
            return null;
        }

        Object result = ProtoConverter.fromParameterValue(values.get(0));
        return (T) result;
    }
}
