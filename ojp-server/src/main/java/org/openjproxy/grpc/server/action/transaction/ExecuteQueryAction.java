package org.openjproxy.grpc.server.action.transaction;

import com.openjproxy.grpc.OpResult;
import com.openjproxy.grpc.StatementRequest;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.openjproxy.grpc.ProtoConverter;
import org.openjproxy.grpc.dto.Parameter;
import org.openjproxy.grpc.server.ConnectionSessionDTO;
import org.openjproxy.grpc.server.Session;
import org.openjproxy.grpc.server.action.Action;
import org.openjproxy.grpc.server.action.ActionContext;
import org.openjproxy.grpc.server.cache.CacheConfiguration;
import org.openjproxy.grpc.server.cache.QueryCacheHelper;
import org.openjproxy.grpc.server.readwrite.ReadReplicaSelector;
import org.openjproxy.grpc.server.readwrite.ReadWriteDataSourceRegistry;
import org.openjproxy.grpc.server.readwrite.ReadWriteSqlClassifier;
import org.openjproxy.grpc.server.statement.StatementFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.openjproxy.grpc.server.action.session.ResultSetHelper.handleResultSet;
import static org.openjproxy.grpc.server.action.streaming.SessionConnectionHelper.sessionConnection;
import static org.openjproxy.grpc.server.action.transaction.CommandExecutionHelper.executeWithResilience;

@Slf4j
public class ExecuteQueryAction implements Action<StatementRequest, OpResult> {

    private static final ExecuteQueryAction INSTANCE = new ExecuteQueryAction();

    private static final ReadReplicaSelector REPLICA_SELECTOR = new ReadReplicaSelector();

    /**
     * Private constructor for singleton.
     */
    private ExecuteQueryAction() {
    }

    /**
     * Returns the singleton instance of this action.
     *
     * @return the singleton instance
     */
    public static ExecuteQueryAction getInstance() {
        return INSTANCE;
    }

    @Override
    public void execute(ActionContext context, StatementRequest request, StreamObserver<OpResult> responseObserver) {
        log.debug("Executing query for {}", request.getSql());

        executeWithResilience(context, request, responseObserver, () ->
                        executeQueryInternal(context, request, responseObserver),
                null, "query");
    }

    /**
     * Internal method for executing queries without segregation logic.
     */
    private void executeQueryInternal(ActionContext actionContext, StatementRequest request, StreamObserver<OpResult> responseObserver)
            throws SQLException {

        // Read/write splitting: route reads to a replica when applicable
        DataSource replicaDs = resolveReadReplicaDataSource(actionContext, request);

        // Always start a session (startSessionIfNone=true) so the session UUID is returned
        // to the driver — the client may need it for metadata access on the result set,
        // statement, or connection.  For the replica path, a lazy dual-datasource session is
        // created; the actual replica connection is resolved below via the Session object.
        ConnectionSessionDTO dto = sessionConnection(actionContext, request.getSession(), true, replicaDs);

        // Resolve the JDBC connection to use for execution:
        //   - replica path  → session.getOrCreateReplicaConnection() (lazy; never allocates primary)
        //   - primary path  → dto.getConnection() (already acquired by sessionConnection)
        Connection execConn;
        if (replicaDs != null) {
            Session activeSession = actionContext.getSessionManager().getSession(dto.getSession());
            if (activeSession == null) {
                throw new SQLException("Session not found for UUID: " + dto.getSession().getSessionUUID()
                        + ". Cannot obtain replica connection.");
            }
            execConn = activeSession.getOrCreateReplicaConnection(replicaDs);
            log.debug("[RW-SPLIT] executeQueryInternal: using REPLICA connection for sessionUUID={}, connHash={}",
                    dto.getSession().getSessionUUID(), request.getSession().getConnHash());
        } else {
            execConn = dto.getConnection();
            log.debug("[RW-SPLIT] executeQueryInternal: using PRIMARY connection for sessionUUID={}, connHash={}",
                    dto.getSession().getSessionUUID(), request.getSession().getConnHash());
        }

        // Build an execution DTO with the resolved connection so StatementFactory and
        // downstream helpers receive the correct connection regardless of routing.
        ConnectionSessionDTO execDto = ConnectionSessionDTO.builder()
                .session(dto.getSession())
                .connection(execConn)
                .dbName(dto.getDbName())
                .build();

        // Phase 6: Cache Lookup (before query execution) - with graceful degradation
        String sql = request.getSql();
        CacheConfiguration cacheConfig = QueryCacheHelper.getCacheConfiguration(actionContext, dto.getSession());

        if (cacheConfig != null && cacheConfig.isEnabled()) {
            try {
                String datasourceName = dto.getSession().getConnHash();
                List<Parameter> params = ProtoConverter.fromProtoList(request.getParametersList());

                com.openjproxy.grpc.OpQueryResultProto cachedProto = QueryCacheHelper.getCachedResult(
                        cacheConfig, sql, params, datasourceName);

                if (cachedProto != null) {
                    // CACHE HIT - Return cached proto directly (no conversion needed)
                    OpResult result = OpResult.newBuilder()
                            .setQueryResult(cachedProto)
                            .build();
                    responseObserver.onNext(result);
                    responseObserver.onCompleted();
                    return;  // Skip database execution
                }
            } catch (Exception e) {
                // Graceful degradation - cache failure doesn't block query execution
                log.error("Cache lookup failed, falling back to database: datasource={}, sql={}, error={}",
                        dto.getSession().getConnHash(),
                        sql.substring(0, Math.min(sql.length(), 50)),
                        e.getMessage());
                // Continue to database execution
            }
        }

        // Phase 2: SQL Enhancement with timing
        long enhancementStartTime = System.nanoTime();

        var sqlEnhancerEngine = actionContext.getSqlEnhancerEngine();
        var datasourceMap = actionContext.getDatasourceMap();
        var sessionManager = actionContext.getSessionManager();

        if (sqlEnhancerEngine.isEnabled()) {
            // Ensure schema is loaded before enhancement (on-demand, only once)
            try {
                // Get the DataSource for this connection
                String dsKey = dto.getSession().getConnHash();
                DataSource dataSource = datasourceMap.get(dsKey);

                if (dataSource != null) {
                    // Get catalog and schema from the execution connection
                    Connection connection = execConn;
                    String catalogName = connection.getCatalog();
                    String schemaName = connection.getSchema();

                    // PostgreSQL: Use "public" schema if schema name is null or empty
                    // This ensures tables created in the default schema are visible to Calcite
                    if ((schemaName == null || schemaName.isEmpty()) &&
                            connection.getMetaData().getDatabaseProductName().equalsIgnoreCase("PostgreSQL")) {
                        schemaName = "public";
                        log.debug("Using default PostgreSQL 'public' schema for schema loading");
                    }

                    // Ensure schema is loaded (thread-safe, idempotent)
                    sqlEnhancerEngine.ensureSchemaLoaded(dataSource, catalogName, schemaName);
                } else {
                    log.debug("No DataSource found for connection hash: {}", dsKey);
                }
            } catch (Exception e) {
                // Log but don't fail - enhancement can proceed without schema
                log.warn("Failed to ensure schema loaded: {}", e.getMessage());
            }

            org.openjproxy.grpc.server.sql.SqlEnhancementResult result = sqlEnhancerEngine.enhance(sql);
            sql = result.getEnhancedSql();

            long enhancementDuration = (System.nanoTime() - enhancementStartTime) / 1_000_000L;

            if (result.isModified()) {
                log.debug("SQL was enhanced in {}ms: {} -> {}", enhancementDuration,
                        request.getSql().substring(0, Math.min(request.getSql().length(), 50)),
                        sql.substring(0, Math.min(sql.length(), 50)));
            } else if (enhancementDuration > 10) {
                log.debug("SQL enhancement took {}ms (no modifications)", enhancementDuration);
            }
        }

        List<Parameter> params = ProtoConverter.fromProtoList(request.getParametersList());

        // Phase 7: Wrap response observer for cache storage (if caching enabled)
        StreamObserver<OpResult> finalObserver = QueryCacheHelper.wrapWithCaching(
                responseObserver, cacheConfig, sql, params, dto.getSession().getConnHash());

        if (CollectionUtils.isNotEmpty(params)) {
            PreparedStatement ps = StatementFactory.createPreparedStatement(sessionManager, execDto, sql, params, request);
            String resultSetUUID = sessionManager.registerResultSet(dto.getSession(), ps.executeQuery());
            handleResultSet(actionContext, dto.getSession(), resultSetUUID, finalObserver);
        } else {
            Statement stmt = StatementFactory.createStatement(sessionManager, execDto.getConnection(), request);
            String resultSetUUID = sessionManager.registerResultSet(dto.getSession(),
                    stmt.executeQuery(sql));
            handleResultSet(actionContext, dto.getSession(), resultSetUUID, finalObserver);
        }
    }

    /**
     * Determines whether this read query should be routed to a replica, and if so
     * returns the selected replica {@link DataSource}.
     *
     * <p>Routing to a replica is skipped when any of the following is true:
     * <ul>
     *   <li>No {@link ReadWriteDataSourceRegistry} is available in the context.</li>
     *   <li>The request is inside an explicit transaction (primary connection exists
     *       and has autoCommit=false).</li>
     *   <li>The SQL is not a read-only statement.</li>
     *   <li>The primary has no replicas registered.</li>
     *   <li>A sticky-session window is currently active for the primary.</li>
     * </ul>
     *
     * <p>Note: the presence of a {@code sessionUUID} alone does <em>not</em> block
     * replica routing.  The session UUID may exist from a previous autoCommit SELECT
     * on the same connection, and in that case routing to the replica is still safe.
     *
     * @param context the action context
     * @param request the statement request
     * @return a replica {@link DataSource}, or {@code null} when the primary should be used
     */
    private DataSource resolveReadReplicaDataSource(ActionContext context, StatementRequest request) {
        ReadWriteDataSourceRegistry registry = context.getReadWriteDataSourceRegistry();
        if (registry == null) {
            return null;
        }

        String sessionUUID = request.getSession().getSessionUUID();
        String connHash = request.getSession().getConnHash();
        log.debug("[RW-SPLIT] resolveReadReplicaDataSource: connHash={}, sessionUUID={}, sql={}",
                connHash, sessionUUID,
                request.getSql().length() > 60 ? request.getSql().substring(0, 60) + "..." : request.getSql());

        // Block replica routing only when there is an active transaction on the primary.
        // A session UUID being present is not sufficient — it may come from a previous
        // autoCommit SELECT.  We check the actual transaction state via hasActiveTransaction()
        // which does NOT trigger lazy primary connection acquisition.
        if (!sessionUUID.isBlank()) {
            Session existingSession = context.getSessionManager().getSession(request.getSession());
            if (existingSession == null) {
                // Session has expired or been invalidated; fall back to primary to avoid
                // routing to replica with unknown session state.
                log.debug("[RW-SPLIT] session not found for UUID={}, routing to primary", sessionUUID);
                return null;
            }
            if (existingSession.hasActiveTransaction()) {
                log.debug("[RW-SPLIT] active transaction on session={}, routing to primary", sessionUUID);
                return null;  // active transaction → must stay on primary
            }
        }

        // Only route read-only SQL to replicas
        ReadWriteSqlClassifier.QueryType queryType = ReadWriteSqlClassifier.classify(request.getSql());
        if (queryType != ReadWriteSqlClassifier.QueryType.READ) {
            log.debug("[RW-SPLIT] SQL classified as {}, routing to primary (connHash={})", queryType, connHash);
            return null;
        }

        String primaryName = registry.getPrimaryName(connHash);
        if (primaryName == null) {
            log.debug("[RW-SPLIT] no primary mapping for connHash={}, routing to primary", connHash);
            return null;
        }

        // Honour sticky session: after a write, reads go to primary until timeout expires
        if (registry.isStickyActive(primaryName)) {
            log.debug("Read/write splitting: sticky session active for primary '{}', routing to primary", primaryName);
            return null;
        }

        List<DataSource> replicas = registry.getReplicas(primaryName);
        if (replicas.isEmpty()) {
            log.debug("[RW-SPLIT] no replicas registered for primary='{}', routing to primary", primaryName);
            return null;
        }

        DataSource selected = REPLICA_SELECTOR.select(primaryName, replicas, registry.getStrategy(primaryName));
        log.debug("[RW-SPLIT] routed READ to replica for primary='{}', connHash={}, sessionUUID={}",
                primaryName, connHash, sessionUUID);
        return selected;
    }
}
