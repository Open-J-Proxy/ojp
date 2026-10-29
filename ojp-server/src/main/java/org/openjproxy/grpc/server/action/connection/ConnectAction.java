package org.openjproxy.grpc.server.action.connection;

import com.openjproxy.grpc.ConnectionDetails;
import com.openjproxy.grpc.SessionInfo;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.database.DatabaseUtils;
import org.openjproxy.datasource.ConnectionPoolProviderRegistry;
import org.openjproxy.datasource.PoolConfig;
import org.openjproxy.constants.CommonConstants;
import org.openjproxy.grpc.server.MultinodePoolCoordinator;
import org.openjproxy.grpc.server.MultinodeXaCoordinator;
import org.openjproxy.grpc.server.AdmissionControlManager;
import org.openjproxy.grpc.server.UnpooledConnectionDetails;
import org.openjproxy.grpc.server.action.Action;
import org.openjproxy.grpc.server.action.ActionContext;
import org.openjproxy.grpc.server.action.util.ProcessClusterHealthAction;
import org.openjproxy.grpc.server.pool.ConnectionPoolConfigurer;
import org.openjproxy.grpc.server.pool.DataSourceConfigurationManager;
import org.openjproxy.grpc.server.pool.PreparedStatementCachePropertyTranslator;
import org.openjproxy.grpc.server.readwrite.ReadWriteConfiguration;
import org.openjproxy.grpc.server.readwrite.ReadWriteDataSourceManager;
import org.openjproxy.grpc.server.utils.ConnectionHashGenerator;
import org.openjproxy.grpc.server.utils.UrlParser;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static org.openjproxy.grpc.server.GrpcExceptionHandler.sendSQLExceptionMetadata;

/**
 * Action to establish a database connection (regular or XA).
 * Handles connection pooling, multinode coordination, and both pooled/unpooled modes.
 *
 * This action is implemented as a singleton for thread-safety and memory efficiency.
 * It is stateless and receives all necessary context via parameters.
 */
@Slf4j
public class ConnectAction implements Action<ConnectionDetails, SessionInfo> {

    private static final ConnectAction INSTANCE = new ConnectAction();

    // Lock objects for synchronizing pool creation per connection hash.
    // Using ReentrantLock for virtual thread compatibility.
    // Note: In practice, connection hashes are bounded by the finite set of database credentials
    // used by the application, so this map won't grow indefinitely.
    private final Map<String, Lock> poolCreationLocks = new ConcurrentHashMap<>();

    private ConnectAction() {
        // Private constructor prevents external instantiation
    }

    /**
     * Get a lock for synchronizing pool creation for a specific connection hash.
     * This prevents race conditions where multiple threads try to create pools simultaneously.
     */
    private Lock getPoolCreationLock(String connHash) {
        return poolCreationLocks.computeIfAbsent(connHash, k -> new ReentrantLock());
    }

    public static ConnectAction getInstance() {
        return INSTANCE;
    }

    @Override
    public void execute(ActionContext context, ConnectionDetails connectionDetails, StreamObserver<SessionInfo> responseObserver) {
        // Handle empty connection details (health check)
        if (connectionDetails.getUrl().isBlank() &&
            connectionDetails.getUser().isBlank() &&
            connectionDetails.getPassword().isBlank()) {
            // Empty connection details - return empty session info - used for initial health checks only
            responseObserver.onNext(SessionInfo.newBuilder().build());
            responseObserver.onCompleted();
            return;
        }

        String connHash = ConnectionHashGenerator.hashConnectionDetails(connectionDetails);

        // Use default XA configuration values (deprecated pass-through properties no longer supported)
        int maxXaTransactions = org.openjproxy.constants.CommonConstants.DEFAULT_MAX_XA_TRANSACTIONS;
        long xaStartTimeoutMillis = org.openjproxy.constants.CommonConstants.DEFAULT_XA_START_TIMEOUT_MILLIS;

        log.info("connect connHash = {}, isXA = {}, maxXaTransactions = {}, xaStartTimeout = {}ms",
                connHash, connectionDetails.getIsXA(), maxXaTransactions, xaStartTimeoutMillis);

        // Check if this is an XA connection request
        if (connectionDetails.getIsXA()) {
            handleXAConnection(context, connectionDetails, connHash, maxXaTransactions, xaStartTimeoutMillis, responseObserver);
            return;
        }

        // Handle non-XA connection
        handleRegularConnection(context, connectionDetails, connHash, responseObserver);
    }

    /**
     * Handle XA connection establishment.
     */
    private void handleXAConnection(ActionContext context, ConnectionDetails connectionDetails, String connHash,
                                    int maxXaTransactions, long xaStartTimeoutMillis,
                                    StreamObserver<SessionInfo> responseObserver) {
        // Check if multinode configuration is present for XA coordination
        List<String> serverEndpoints = connectionDetails.getServerEndpointsList();
        int actualMaxXaTransactions = maxXaTransactions;

        if (serverEndpoints != null && !serverEndpoints.isEmpty()) {
            // Multinode: calculate divided XA transaction limits
            MultinodeXaCoordinator.XaAllocation xaAllocation =
                    context.getXaCoordinator().calculateXaLimits(connHash, maxXaTransactions, serverEndpoints);

            actualMaxXaTransactions = xaAllocation.getCurrentMaxTransactions();

            log.info("Multinode XA coordination enabled for {}: {} servers, divided max transactions: {}",
                    connHash, serverEndpoints.size(), actualMaxXaTransactions);
        }

        // Branch based on XA pooling configuration
        // XA Pool Provider SPI (always enabled)
        if (context.getXaPoolProvider() != null) {
            HandleXAConnectionWithPoolingAction.getInstance().execute(
                context, connectionDetails, connHash, actualMaxXaTransactions,
                xaStartTimeoutMillis, responseObserver);
        } else {
            log.error("XA Pool Provider not initialized");
            responseObserver.onError(Status.INTERNAL
                    .withDescription("XA Pool Provider not available")
                    .asRuntimeException());
        }
    }

    /**
     * Handle regular (non-XA) connection establishment.
     */
    private void handleRegularConnection(ActionContext context, ConnectionDetails connectionDetails, String connHash,
                                        StreamObserver<SessionInfo> responseObserver) {
        // Use ReentrantLock for virtual thread compatibility.
        // Lock ONLY during pool creation/check to prevent duplicate pool creation without
        // blocking subsequent connection borrows from an already-created pool.
        Lock lock = getPoolCreationLock(connHash);
        lock.lock();
        try {
            // Handle non-XA connection - check if pooling is enabled
            DataSource ds = context.getDatasourceMap().get(connHash);
            UnpooledConnectionDetails unpooledDetails =
                    context.getUnpooledConnectionDetailsMap().get(connHash);

            if (ds == null && unpooledDetails == null) {
                try {
                    // Get datasource-specific configuration from client properties
                    Properties clientProperties = ConnectionPoolConfigurer.extractClientProperties(connectionDetails);
                    DataSourceConfigurationManager.DataSourceConfiguration dsConfig =
                            DataSourceConfigurationManager.getConfiguration(clientProperties);

                    // Check if pooling is enabled
                    if (!dsConfig.isPoolEnabled()) {
                        // Unpooled mode: store connection details for direct connection creation
                        unpooledDetails = UnpooledConnectionDetails.builder()
                                .url(UrlParser.parseUrl(connectionDetails.getUrl()))
                                .username(connectionDetails.getUser())
                                .password(connectionDetails.getPassword())
                                .connectionTimeout(dsConfig.getConnectionTimeout())
                                .build();
                        context.getUnpooledConnectionDetailsMap().put(connHash, unpooledDetails);

                        log.info("Unpooled (passthrough) mode enabled for dataSource '{}' with connHash: {}",
                                dsConfig.getDataSourceName(), connHash);
                    } else {
                        // Pooled mode: create datasource with Connection Pool SPI (HikariCP by default)
                        // Get pool sizes - apply multinode coordination if needed
                        int maxPoolSize = dsConfig.getMaximumPoolSize();
                        int minIdle = dsConfig.getMinimumIdle();

                        List<String> serverEndpoints = connectionDetails.getServerEndpointsList();
                        if (serverEndpoints != null && !serverEndpoints.isEmpty()) {
                            // Multinode: calculate divided pool sizes
                            MultinodePoolCoordinator.PoolAllocation allocation =
                                    ConnectionPoolConfigurer.getPoolCoordinator().calculatePoolSizes(
                                            connHash, maxPoolSize, minIdle, serverEndpoints);

                            maxPoolSize = allocation.getCurrentMaxPoolSize();
                            minIdle = allocation.getCurrentMinIdle();

                            log.info("Multinode pool coordination enabled for {}: {} servers, divided pool sizes: max={}, min={}",
                                    connHash, serverEndpoints.size(), maxPoolSize, minIdle);
                        }

                        // Get transaction isolation from configuration, default to READ_COMMITTED
                        Integer configuredTransactionIsolation = dsConfig.getDefaultTransactionIsolation();
                        Integer defaultTransactionIsolation = configuredTransactionIsolation != null
                                ? configuredTransactionIsolation
                                : java.sql.Connection.TRANSACTION_READ_COMMITTED;

                        if (configuredTransactionIsolation == null) {
                            log.info("No transaction isolation configured for {}, using default READ_COMMITTED", connHash);
                        } else {
                            log.info("Using configured transaction isolation level for {}: {}",
                                    connHash, configuredTransactionIsolation);
                        }

                        String parsedUrl = UrlParser.parseUrl(connectionDetails.getUrl());
                        Map<String, String> statementCacheProperties =
                                PreparedStatementCachePropertyTranslator.buildNonXaProperties(
                                        context.getServerConfiguration(), parsedUrl);

                        // Build PoolConfig with transaction isolation (configured or default)
                        PoolConfig poolConfig = PoolConfig.builder()
                                .url(parsedUrl)
                                .username(connectionDetails.getUser())
                                .password(connectionDetails.getPassword())
                                .maxPoolSize(maxPoolSize)
                                .minIdle(minIdle)
                                .connectionTimeoutMs(CommonConstants.FAIL_FAST_POOL_CONNECTION_TIMEOUT_MS)
                                .idleTimeoutMs(dsConfig.getIdleTimeout())
                                .maxLifetimeMs(dsConfig.getMaxLifetime())
                                .leakDetectionThresholdMs(dsConfig.getLeakDetectionThreshold())
                                .defaultTransactionIsolation(defaultTransactionIsolation)
                                .properties(statementCacheProperties)
                                .metricsPrefix("OJP-Pool-" + dsConfig.getDataSourceName())
                                .build();

                        log.info("Connection timeout model for {}: admissionTimeout={}ms, backendPoolTimeout={}ms (fail-fast, provider clamped if needed)",
                                connHash, dsConfig.getConnectionTimeout(), CommonConstants.FAIL_FAST_POOL_CONNECTION_TIMEOUT_MS);

                        // Create DataSource with properly configured transaction isolation
                        ds = ConnectionPoolProviderRegistry.createDataSource(poolConfig);
                        log.info("Created DataSource with transaction isolation level: {}", defaultTransactionIsolation);

                        context.getDatasourceMap().put(connHash, ds);

                        // Create a slow query segregation manager for this datasource
                        CreateSlowQuerySegregationManagerAction.getInstance()
                                .execute(context, connHash, maxPoolSize, dsConfig.getConnectionTimeout());

                        log.info("Created new DataSource for dataSource '{}' with connHash: {} using provider: {}, maxPoolSize={}, minIdle={}",
                                dsConfig.getDataSourceName(), connHash,
                                ConnectionPoolProviderRegistry.getDefaultProvider().map(p -> p.id()).orElse("unknown"),
                                maxPoolSize, minIdle);

                        // Setup read/write splitting if configured
                        setupReadWriteSplitting(context, connectionDetails, connHash, ds, dsConfig.getDataSourceName());
                    }

                } catch (Exception e) {
                    log.error("Failed to create datasource for connection hash {}: {}", connHash, e.getMessage(), e);
                    SQLException sqlException = new SQLException("Failed to create datasource: " + e.getMessage(), e);
                    sendSQLExceptionMetadata(sqlException, responseObserver);
                    return;
                }
            }
        } finally {
            lock.unlock();
        }

        // If the pool already existed and read/write splitting was not yet registered for this
        // connHash, attempt setup now. The setupReadWriteSplitting implementation is idempotent:
        // it skips silently when the primary is already mapped and replicas are registered.
        org.openjproxy.grpc.server.readwrite.ReadWriteDataSourceRegistry readWriteRegistry =
                context.getReadWriteDataSourceRegistry();
        if (readWriteRegistry != null
                && readWriteRegistry.getPrimaryName(connHash) == null
                && connectionDetails.getPropertiesCount() > 0) {
            DataSource existingDs = context.getDatasourceMap().get(connHash);
            if (existingDs != null) {
                try {
                    Properties clientProps = ConnectionPoolConfigurer.extractClientProperties(connectionDetails);
                    DataSourceConfigurationManager.DataSourceConfiguration dsConf =
                            DataSourceConfigurationManager.getConfiguration(clientProps);
                    setupReadWriteSplitting(context, connectionDetails, connHash, existingDs,
                            dsConf.getDataSourceName());
                } catch (Exception e) {
                    log.error("Failed to setup read/write splitting for connHash {} (pool already existed): {}",
                            connHash, e.getMessage(), e);
                    // Non-fatal: continue without read/write splitting
                }
            }
        }

        // Process cluster health from ConnectionDetails if provided.
        // This supports the driver's proactive cluster health push: after detecting a peer
        // server failure or recovery, the driver calls connect() on healthy servers with an
        // updated clusterHealth embedded in ConnectionDetails.  Because the pool may already
        // exist on those servers (no-op for pool creation), we process the cluster health
        // here so the server can resize its pool even when no SQL operations are active.
        if (!connectionDetails.getClusterHealth().isEmpty()) {
            SessionInfo clusterHealthSession = SessionInfo.newBuilder()
                    .setConnHash(connHash)
                    .setClusterHealth(connectionDetails.getClusterHealth())
                    .build();
            ProcessClusterHealthAction.getInstance().execute(context, clusterHealthSession);
        }

        // Parse and store cache configuration from properties
        if (connectionDetails.getPropertiesCount() > 0) {
            try {
                org.openjproxy.grpc.server.cache.CacheConfiguration cacheConfig =
                    org.openjproxy.grpc.server.cache.QueryCacheHelper.parseCacheConfiguration(
                        connectionDetails.getUrl(),
                        connectionDetails.getPropertiesList(),
                        connHash);

                // Validate cache configuration
                if (cacheConfig != null && cacheConfig.isEnabled()) {
                    org.openjproxy.grpc.server.cache.CacheConfigurationValidator.ValidationResult validation =
                        org.openjproxy.grpc.server.cache.CacheConfigurationValidator.validate(cacheConfig);

                    // Log warnings
                    for (String warning : validation.getWarnings()) {
                        log.warn("Cache configuration warning for connHash '{}': {}", connHash, warning);
                    }

                    // Check for errors
                    if (!validation.isValid()) {
                        String errorMsg = "Invalid cache configuration: " + String.join("; ", validation.getErrors());
                        log.error("Cache configuration rejected for connHash '{}': {}", connHash, errorMsg);
                        // Disable caching but allow connection
                        cacheConfig = new org.openjproxy.grpc.server.cache.CacheConfiguration(
                            cacheConfig.getDatasourceName(), false, java.util.List.of());
                    }
                }

                context.getCacheConfigurationMap().put(connHash, cacheConfig);

                if (cacheConfig.isEnabled()) {
                    log.info("Cache configuration stored for connHash '{}': {} rules",
                        connHash, cacheConfig.getRules().size());
                } else {
                    log.debug("Cache configuration disabled for connHash '{}'", connHash);
                }
            } catch (Exception e) {
                log.error("Failed to parse cache configuration for connHash '{}': {}",
                    connHash, e.getMessage());
                // Continue without cache - caching will be disabled for this connection
            }
        } else {
            log.debug("No properties provided for connHash '{}'", connHash);
        }

        // registerClientUUID is safe to call outside the lock: each client has a unique clientUUID,
        // so concurrent calls write to different map keys and cannot race with one another.
        context.getSessionManager().registerClientUUID(connHash, connectionDetails.getClientUUID());

        // For regular connections, just return session info without creating a session yet (lazy allocation)
        // Server does not populate targetServer - client will set it on future requests
        int clientCount = context.getSessionManager().getClientCount(connHash);
        int maxAdmission = 0;
        int observedPeakValue = 0;
        AdmissionControlManager acm = context.getAdmissionControlManagers().get(connHash);
        if (acm != null && acm.isEnabled() && acm.getSlotManager() != null) {
            maxAdmission = acm.getSlotManager().getEffectiveMaxAdmission();
            observedPeakValue = acm.getSlotManager().getObservedPeak();
        }

        SessionInfo sessionInfo = SessionInfo.newBuilder()
                .setConnHash(connHash)
                .setClientUUID(connectionDetails.getClientUUID())
                .setIsXA(false)
                .setClientCount(clientCount)
                .setMaxAdmission(maxAdmission)
                .setObservedPeak(observedPeakValue)
                .build();

        responseObserver.onNext(sessionInfo);

        context.getDbNameMap().put(connHash, DatabaseUtils.resolveDbName(connectionDetails.getUrl()));

        responseObserver.onCompleted();
    }

    /**
     * Sets up read/write splitting for the given datasource if configured.
     * Creates and registers replica datasources in the ReadWriteDataSourceRegistry.
     *
     * @param context           action context
     * @param connectionDetails connection details with properties
     * @param connHash          connection hash for the primary
     * @param ds                primary datasource (already created)
     * @param datasourceName    name of the datasource
     */
    private void setupReadWriteSplitting(ActionContext context, ConnectionDetails connectionDetails,
                                         String connHash, DataSource ds, String datasourceName) {
        if (context.getReadWriteDataSourceRegistry() == null) {
            log.debug("ReadWriteDataSourceRegistry not available, skipping read/write splitting setup");
            return;
        }

        try {
            ReadWriteDataSourceManager rwManager = new ReadWriteDataSourceManager(
                    context.getReadWriteDataSourceRegistry());

            ReadWriteConfiguration config = rwManager.setupReadWriteSplitting(
                    connectionDetails, connHash, ds, datasourceName);

            if (config != null) {
                log.info("Read/write splitting successfully configured for datasource '{}'", datasourceName);
            }
        } catch (Exception e) {
            log.error("Failed to setup read/write splitting for datasource '{}': {}",
                    datasourceName, e.getMessage(), e);
            // Non-fatal: continue without read/write splitting
        }
    }
}
