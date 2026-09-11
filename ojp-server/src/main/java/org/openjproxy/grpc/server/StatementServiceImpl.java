package org.openjproxy.grpc.server;

import com.openjproxy.grpc.CallResourceRequest;
import com.openjproxy.grpc.CallResourceResponse;
import com.openjproxy.grpc.ConnectionDetails;
import com.openjproxy.grpc.DbName;
import com.openjproxy.grpc.LobDataBlock;
import com.openjproxy.grpc.LobReference;
import com.openjproxy.grpc.OpResult;
import com.openjproxy.grpc.ReadLobRequest;
import com.openjproxy.grpc.ResultSetFetchRequest;
import com.openjproxy.grpc.SessionInfo;
import com.openjproxy.grpc.SessionTerminationStatus;
import com.openjproxy.grpc.StatementRequest;
import com.openjproxy.grpc.StatementServiceGrpc;
import io.grpc.stub.StreamObserver;
import lombok.Builder;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.grpc.server.action.resource.CallResourceAction;
import org.openjproxy.grpc.server.action.session.TerminateSessionAction;
import org.openjproxy.grpc.server.action.transaction.CommitTransactionAction;
import org.openjproxy.grpc.server.action.transaction.RollbackTransactionAction;
import org.openjproxy.grpc.server.action.transaction.StartTransactionAction;
import org.openjproxy.grpc.server.action.xa.XaCommitAction;
import org.openjproxy.grpc.server.action.xa.XaEndAction;
import org.openjproxy.grpc.server.action.xa.XaPrepareAction;
import org.openjproxy.grpc.server.action.xa.XaRecoverAction;
import org.openjproxy.grpc.server.action.xa.XaRollbackAction;
import org.openjproxy.grpc.server.action.xa.XaStartAction;
import org.openjproxy.xa.pool.XATransactionRegistry;
import org.openjproxy.xa.pool.spi.XAConnectionPoolProvider;

import javax.sql.DataSource;
import javax.sql.XADataSource;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class StatementServiceImpl extends StatementServiceGrpc.StatementServiceImplBase {

    // XA Pool Provider for pooling XAConnections (loaded via SPI)
    private XAConnectionPoolProvider xaPoolProvider;

    // SQL Enhancer Engine for query optimization
    private final org.openjproxy.grpc.server.sql.SqlEnhancerEngine sqlEnhancerEngine;

    // Multinode XA coordinator for distributing transaction limits
    private static final MultinodeXaCoordinator XA_COORDINATOR = new MultinodeXaCoordinator();

    // ActionContext for refactored actions
    private final org.openjproxy.grpc.server.action.ActionContext actionContext;

    public StatementServiceImpl(SessionManager sessionManager, CircuitBreakerRegistry circuitBreakerRegistry,
            ServerConfiguration serverConfiguration,
            Map<String, org.openjproxy.grpc.server.cache.CacheConfiguration> cacheConfigurationMap) {
        // Server configuration for creating segregation managers
        this.sqlEnhancerEngine = new org.openjproxy.grpc.server.sql.SqlEnhancerEngine(
                serverConfiguration.isSqlEnhancerEnabled());
        initializeXAPoolProvider();

        // Create SQL statement metrics from the registered OpenTelemetry instance (if available)
        // SQL statement metrics (only used by executeQuery/executeUpdate)
        org.openjproxy.grpc.server.metrics.SqlStatementMetrics sqlStatementMetrics = createSqlStatementMetrics();

        // Initialize ActionContext with all shared state
        // Map for storing XADataSources (native database XADataSource, not Atomikos)
        Map<String, XADataSource> xaDataSourceMap = new ConcurrentHashMap<>();
        // XA Transaction Registries (one per connection hash for isolated transaction
        // management)
        Map<String, XATransactionRegistry> xaRegistries = new ConcurrentHashMap<>();
        // Unpooled connection details map (for passthrough mode when pooling is
        // disabled)
        Map<String, UnpooledConnectionDetails> unpooledConnectionDetailsMap = new ConcurrentHashMap<>();
        // Cluster health tracker for monitoring health changes
        ClusterHealthTracker clusterHealthTracker = new ClusterHealthTracker();
        Map<String, DataSource> datasourceMap = new ConcurrentHashMap<>();
        Map<String, DbName> dbNameMap = new ConcurrentHashMap<>();
        // Per-datasource admission control managers
        Map<String, AdmissionControlManager> admissionControlManagers = new ConcurrentHashMap<>();
        // Per-datasource cache configurations (shared with SessionManager)
        Map<String, org.openjproxy.grpc.server.cache.CacheConfiguration> cacheCfgMap =
                cacheConfigurationMap != null ? cacheConfigurationMap : new ConcurrentHashMap<>();
        // Read/write splitting datasource registry
        org.openjproxy.grpc.server.readwrite.ReadWriteDataSourceRegistry readWriteRegistry =
                new org.openjproxy.grpc.server.readwrite.ReadWriteDataSourceRegistry();


        this.actionContext = new org.openjproxy.grpc.server.action.ActionContext(
                datasourceMap,
                xaDataSourceMap,
                xaRegistries,
                unpooledConnectionDetailsMap,
                dbNameMap,
                admissionControlManagers,
                cacheCfgMap,
                readWriteRegistry,
                xaPoolProvider,
                XA_COORDINATOR,
                clusterHealthTracker,
                sessionManager,
                circuitBreakerRegistry,
                serverConfiguration,
                sqlStatementMetrics,
                sqlEnhancerEngine);
    }

    /**
     * Creates SQL statement metrics using the registered OpenTelemetry instance.
     * Falls back to no-op metrics when OpenTelemetry is unavailable.
     */
    private org.openjproxy.grpc.server.metrics.SqlStatementMetrics createSqlStatementMetrics() {
        io.opentelemetry.api.OpenTelemetry openTelemetry =
                org.openjproxy.xa.pool.commons.metrics.OpenTelemetryHolder.getInstance();
        if (openTelemetry != null) {
            log.info("OpenTelemetry instance available – enabling SQL statement metrics");
            return new org.openjproxy.grpc.server.metrics.OpenTelemetrySqlStatementMetrics(openTelemetry);
        }
        log.info("OpenTelemetry not available – SQL statement metrics disabled (no-op)");
        return org.openjproxy.grpc.server.metrics.NoOpSqlStatementMetrics.INSTANCE;
    }

    /**
     * Initialize XA Pool Provider if XA pooling is enabled in configuration.
     * Loads the provider via ServiceLoader (Commons Pool 2 by default).
     */
    private void initializeXAPoolProvider() {
        // XA pooling is always enabled
        // Select the provider with the HIGHEST priority (100 = highest, 0 = lowest)

        try {
            ServiceLoader<XAConnectionPoolProvider> loader = ServiceLoader.load(XAConnectionPoolProvider.class);
            XAConnectionPoolProvider selectedProvider = null;
            int highestPriority = Integer.MIN_VALUE;

            for (XAConnectionPoolProvider provider : loader) {
                if (provider.isAvailable()) {
                    log.debug("Found available XA Pool Provider: {} (priority: {})",
                            provider.getClass().getName(), provider.getPriority());

                    if (provider.getPriority() > highestPriority) {
                        selectedProvider = provider;
                        highestPriority = provider.getPriority();
                    }
                }
            }

            if (selectedProvider != null) {
                this.xaPoolProvider = selectedProvider;
                log.info("Selected XA Pool Provider: {} (priority: {})",
                        selectedProvider.getClass().getName(), selectedProvider.getPriority());

                // Update ActionContext with initialized provider (if actionContext is already
                // created)
                if (this.actionContext != null) {
                    this.actionContext.setXaPoolProvider(selectedProvider);
                }
            } else {
                log.warn("No available XA Pool Provider found via ServiceLoader, XA pooling will be unavailable");
            }
        } catch (Exception e) {
            log.error("Failed to load XA Pool Provider: {}", e.getMessage(), e);
        }
    }

    @Override
    public void connect(ConnectionDetails connectionDetails, StreamObserver<SessionInfo> responseObserver) {
        org.openjproxy.grpc.server.action.connection.ConnectAction.getInstance()
                .execute(actionContext, connectionDetails, responseObserver);
    }

    @SneakyThrows
    @Override
    public void executeUpdate(StatementRequest request, StreamObserver<OpResult> responseObserver) {
        org.openjproxy.grpc.server.action.transaction.ExecuteUpdateAction.getInstance()
                .execute(actionContext, request, responseObserver);
    }

    @Override
    public void executeQuery(StatementRequest request, StreamObserver<OpResult> responseObserver) {
        org.openjproxy.grpc.server.action.transaction.ExecuteQueryAction.getInstance()
                .execute(actionContext, request, responseObserver);
    }

    @Override
    public void fetchNextRows(ResultSetFetchRequest request, StreamObserver<OpResult> responseObserver) {
        org.openjproxy.grpc.server.action.transaction.FetchNextRowsAction.getInstance()
                .execute(actionContext, request, responseObserver);
    }

    @Override
    public StreamObserver<LobDataBlock> createLob(StreamObserver<LobReference> responseObserver) {
        return org.openjproxy.grpc.server.action.streaming.CreateLobAction.getInstance()
                .execute(actionContext, responseObserver);
    }

    @Override
    public void readLob(ReadLobRequest request, StreamObserver<LobDataBlock> responseObserver) {
        org.openjproxy.grpc.server.action.streaming.ReadLobAction.getInstance()
                .execute(actionContext, request, responseObserver);
    }

    @Builder
    public static class ReadLobContext {
        @Getter
        private InputStream inputStream;
        @Getter
        private Optional<Long> lobLength;
        @Getter
        private Optional<Integer> availableLength;
    }

    @Override
    public void terminateSession(SessionInfo sessionInfo, StreamObserver<SessionTerminationStatus> responseObserver) {
        TerminateSessionAction.getInstance()
                .execute(actionContext, sessionInfo, responseObserver);
    }

    @Override
    public void startTransaction(SessionInfo sessionInfo, StreamObserver<SessionInfo> responseObserver) {
        StartTransactionAction.getInstance()
                .execute(actionContext, sessionInfo, responseObserver);
    }

    @Override
    public void commitTransaction(SessionInfo sessionInfo, StreamObserver<SessionInfo> responseObserver) {
        CommitTransactionAction.getInstance().execute(actionContext, sessionInfo, responseObserver);
    }

    @Override
    public void rollbackTransaction(SessionInfo sessionInfo, StreamObserver<SessionInfo> responseObserver) {
        RollbackTransactionAction.getInstance()
                .execute(actionContext, sessionInfo, responseObserver);
    }

    @Override
    public void callResource(CallResourceRequest request, StreamObserver<CallResourceResponse> responseObserver) {
        CallResourceAction.getInstance().execute(actionContext, request, responseObserver);
    }

    // ===== XA Transaction Operations =====

    @Override
    public void xaStart(com.openjproxy.grpc.XaStartRequest request,
            StreamObserver<com.openjproxy.grpc.XaResponse> responseObserver) {
        XaStartAction.getInstance().execute(actionContext, request, responseObserver);
    }

    @Override
    public void xaEnd(com.openjproxy.grpc.XaEndRequest request,
            StreamObserver<com.openjproxy.grpc.XaResponse> responseObserver) {
        XaEndAction.getInstance().execute(actionContext, request, responseObserver);
    }

    @Override
    public void xaPrepare(com.openjproxy.grpc.XaPrepareRequest request,
            StreamObserver<com.openjproxy.grpc.XaPrepareResponse> responseObserver) {
        XaPrepareAction.getInstance().execute(actionContext, request, responseObserver);
    }

    @Override
    public void xaCommit(com.openjproxy.grpc.XaCommitRequest request,
            StreamObserver<com.openjproxy.grpc.XaResponse> responseObserver) {
        XaCommitAction.getInstance().execute(actionContext, request, responseObserver);
    }

    @Override
    public void xaRollback(com.openjproxy.grpc.XaRollbackRequest request,
            StreamObserver<com.openjproxy.grpc.XaResponse> responseObserver) {
        XaRollbackAction.getInstance()
                .execute(actionContext, request, responseObserver);
    }

    @Override
    public void xaRecover(com.openjproxy.grpc.XaRecoverRequest request,
            StreamObserver<com.openjproxy.grpc.XaRecoverResponse> responseObserver) {
        XaRecoverAction.getInstance().execute(actionContext, request, responseObserver);
    }

    @Override
    public void xaForget(com.openjproxy.grpc.XaForgetRequest request,
            StreamObserver<com.openjproxy.grpc.XaResponse> responseObserver) {
        org.openjproxy.grpc.server.action.transaction.XaForgetAction.getInstance()
                .execute(actionContext, request, responseObserver);
    }

    @Override
    public void xaSetTransactionTimeout(com.openjproxy.grpc.XaSetTransactionTimeoutRequest request,
            StreamObserver<com.openjproxy.grpc.XaSetTransactionTimeoutResponse> responseObserver) {
        org.openjproxy.grpc.server.action.transaction.XaSetTransactionTimeoutAction.getInstance()
                .execute(actionContext, request, responseObserver);
    }

    @Override
    public void xaGetTransactionTimeout(com.openjproxy.grpc.XaGetTransactionTimeoutRequest request,
            StreamObserver<com.openjproxy.grpc.XaGetTransactionTimeoutResponse> responseObserver) {
        org.openjproxy.grpc.server.action.transaction.XaGetTransactionTimeoutAction.getInstance()
                .execute(actionContext, request, responseObserver);

    }

    @Override
    public void xaIsSameRM(com.openjproxy.grpc.XaIsSameRMRequest request,
            StreamObserver<com.openjproxy.grpc.XaIsSameRMResponse> responseObserver) {
        org.openjproxy.grpc.server.action.transaction.XaIsSameRMAction.getInstance()
                .execute(actionContext, request, responseObserver);
    }

    /**
     * Shuts down the SQL enhancer engine and releases associated resources.
     * This method should be called during server shutdown to ensure proper cleanup.
     */
    public void shutdown() {
        if (sqlEnhancerEngine != null) {
            log.info("Shutting down SQL enhancer engine");
            sqlEnhancerEngine.shutdown();
        }
    }
}
