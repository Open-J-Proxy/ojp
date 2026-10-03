package org.openjproxy.grpc.server.action.connection;

import com.openjproxy.grpc.ConnectionDetails;
import com.openjproxy.grpc.SessionInfo;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.database.DatabaseUtils;
import org.openjproxy.constants.CommonConstants;
import org.openjproxy.grpc.server.AdmissionControlManager;
import org.openjproxy.grpc.server.MultinodePoolCoordinator;
import org.openjproxy.grpc.server.Session;
import org.openjproxy.grpc.server.action.ActionContext;
import org.openjproxy.grpc.server.action.util.ProcessClusterHealthAction;
import org.openjproxy.grpc.server.pool.ConnectionPoolConfigurer;
import org.openjproxy.grpc.server.pool.DatabaseConnectionBudgetManager;
import org.openjproxy.grpc.server.pool.DataSourceConfigurationManager;
import org.openjproxy.grpc.server.pool.PreparedStatementCachePropertyTranslator;
import org.openjproxy.grpc.server.utils.UrlParser;
import org.openjproxy.xa.pool.XABackendSession;
import org.openjproxy.xa.pool.XATransactionRegistry;

import javax.sql.XAConnection;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static org.openjproxy.grpc.server.GrpcExceptionHandler.sendSQLExceptionMetadata;

/**
 * Helper action for handling XA connection with pooling.
 * This is extracted from handleXAConnectionWithPooling method.
 *
 * This action is implemented as a singleton for thread-safety and memory efficiency.
 */
@Slf4j
public class HandleXAConnectionWithPoolingAction {

    private static final HandleXAConnectionWithPoolingAction INSTANCE = new HandleXAConnectionWithPoolingAction();

    // Lock objects for synchronizing registry creation per connection hash
    // Using ReentrantLock for virtual thread compatibility
    // Note: In practice, connection hashes are bounded by the finite set of database credentials
    // used by the application, so this map won't grow indefinitely
    private final Map<String, Lock> registryLocks = new ConcurrentHashMap<>();

    private HandleXAConnectionWithPoolingAction() {
        // Private constructor prevents external instantiation
    }

    public static HandleXAConnectionWithPoolingAction getInstance() {
        return INSTANCE;
    }

    public void execute(ActionContext context, ConnectionDetails connectionDetails, String connHash,
                       int actualMaxXaTransactions, long xaStartTimeoutMillis,
                       StreamObserver<SessionInfo> responseObserver) {
        log.info("Using XA Pool Provider SPI for connHash: {}", connHash);

        // Use ReentrantLock for virtual thread compatibility
        // Lock ONLY during registry creation/check, not during session borrowing
        // This prevents race conditions in registry creation while avoiding deadlocks during pool borrowing
        Lock lock = getRegistryLock(connHash);
        XATransactionRegistry registry;

        lock.lock();
        try {
            registry = getOrCreateRegistry(context, connectionDetails, connHash, actualMaxXaTransactions, xaStartTimeoutMillis, responseObserver);
            if (registry == null) {
                // Unpooled mode or error - already handled
                return;
            }
        } finally {
            lock.unlock();
        }

        // Now borrow from pool WITHOUT holding the lock
        // This allows multiple threads to borrow concurrently from the same pool
        borrowSessionAndRespond(context, connectionDetails, connHash, actualMaxXaTransactions, xaStartTimeoutMillis, registry, responseObserver);
    }

    /**
     * Get a lock for synchronizing registry operations for a specific connection hash.
     * This prevents race conditions where multiple threads try to create registries simultaneously.
     * Uses ReentrantLock for virtual thread compatibility.
     */
    private Lock getRegistryLock(String connHash) {
        return registryLocks.computeIfAbsent(connHash, k -> new ReentrantLock());
    }

    /**
     * Get or create the XA registry for the given connection hash.
     * This method is called while holding the registry lock to prevent race conditions.
     * Returns null if unpooled mode or if an error occurred (error already sent to client).
     */
    private XATransactionRegistry getOrCreateRegistry(ActionContext context, ConnectionDetails connectionDetails, String connHash,
                       int actualMaxXaTransactions, long xaStartTimeoutMillis,
                       StreamObserver<SessionInfo> responseObserver) {

        // Get current serverEndpoints configuration
        List<String> currentServerEndpoints = connectionDetails.getServerEndpointsList();
        String currentEndpointsHash = (currentServerEndpoints == null || currentServerEndpoints.isEmpty())
                ? "NONE"
                : String.join(",", currentServerEndpoints);

        // Check if we already have an XA registry for this connection hash
        // NOTE: This method is called while holding the registry lock to prevent race conditions
        XATransactionRegistry registry = context.getXaRegistries().get(connHash);
        log.info("XA registry cache lookup for {}: exists={}, current serverEndpoints hash: {}",
                connHash, registry != null, currentEndpointsHash);

        // Calculate what the pool sizes SHOULD be based on current configuration
        int expectedMaxPoolSize;
        int expectedMinIdle;
        boolean poolEnabled;
        try {
            Properties clientProperties = ConnectionPoolConfigurer.extractClientProperties(connectionDetails);
            DataSourceConfigurationManager.XADataSourceConfiguration xaConfig =
                    DataSourceConfigurationManager.getXAConfiguration(clientProperties);
            expectedMaxPoolSize = xaConfig.getMaximumPoolSize();
            expectedMinIdle = xaConfig.getMinimumIdle();
            poolEnabled = xaConfig.isPoolEnabled();

            // For validation purposes, compute the initial divided pool sizes directly.
            // Do NOT call calculatePoolSizes() here: it would overwrite the existing allocation
            // with a fresh one (healthyServers = totalServers), resetting any health-driven state
            // and introducing a race condition with processClusterHealth() where the allocation
            // can be overwritten between updateHealthyServers() and the resize call, causing
            // the pool to be resized to the wrong (pre-health-update) divided size.
            if (currentServerEndpoints != null && !currentServerEndpoints.isEmpty()) {
                int totalServers = currentServerEndpoints.size();
                expectedMaxPoolSize = (int) Math.ceil((double) expectedMaxPoolSize / totalServers);
                expectedMinIdle = (int) Math.ceil((double) expectedMinIdle / totalServers);
            }
            DatabaseConnectionBudgetManager budgetManager = DatabaseConnectionBudgetManager.getInstance();
            expectedMaxPoolSize = budgetManager.getMaximumPoolSize("xa:" + connHash, expectedMaxPoolSize);
            expectedMinIdle = budgetManager.getMinimumIdle("xa:" + connHash, expectedMinIdle);
        } catch (Exception e) {
            log.warn("Failed to calculate expected pool sizes, will skip validation: {}", e.getMessage());
            expectedMaxPoolSize = -1;
            expectedMinIdle = -1;
            poolEnabled = true; // Default to pooled mode if config fails
        }

        // Check if registry exists and needs recreation due to configuration mismatch
        boolean needsRecreation = false;
        if (registry != null) {
            String registryEndpointsHash = registry.getServerEndpointsHash();
            int registryMaxPool = registry.getMaxPoolSize();
            int registryMinIdle = registry.getMinIdle();

            // Check if serverEndpoints changed
            if (registryEndpointsHash == null || !registryEndpointsHash.equals(currentEndpointsHash)) {
                log.warn("XA registry for {} has serverEndpoints mismatch: registry='{}' vs current='{}'. Will recreate.",
                        connHash, registryEndpointsHash, currentEndpointsHash);
                needsRecreation = true;
            }
            // Check if pool sizes don't match expected values (indicates wrong coordination on first creation)
            else if (expectedMaxPoolSize > 0 && registryMaxPool != expectedMaxPoolSize) {
                log.warn("XA registry for {} has maxPoolSize mismatch: registry={} vs expected={}. Will recreate with correct multinode coordination.",
                        connHash, registryMaxPool, expectedMaxPoolSize);
                needsRecreation = true;
            }
            else if (expectedMinIdle > 0 && registryMinIdle != expectedMinIdle) {
                log.warn("XA registry for {} has minIdle mismatch: registry={} vs expected={}. Will recreate with correct multinode coordination.",
                        connHash, registryMinIdle, expectedMinIdle);
                needsRecreation = true;
            }

            if (needsRecreation) {
                // Close and remove old registry
                try {
                    registry.close();
                } catch (Exception e) {
                    log.warn("Failed to close old XA registry during recreation: {}", e.getMessage());
                }
                DatabaseConnectionBudgetManager.getInstance().unregisterPool("xa:" + connHash);
                context.getXaRegistries().remove(connHash);
                registry = null;
                // Reset health tracker so the next processClusterHealth() call will re-evaluate
                // cluster health and properly resize the new pool for current server health state.
                context.getClusterHealthTracker().removeTracking(connHash);
            }
        }

        if (registry == null) {
            log.info("Creating NEW XA registry for connHash: {} with serverEndpoints: {}", connHash, currentEndpointsHash);

            // Check if XA pooling is enabled
            if (!poolEnabled) {
                log.info("XA unpooled mode enabled for connHash: {}", connHash);

                // Handle unpooled XA connection (releases lock before handling)
                // Return null to signal unpooled mode was handled
                HandleUnpooledXAConnectionAction.getInstance().execute(context, connectionDetails, connHash, responseObserver);
                return null;
            }

            DatabaseConnectionBudgetManager.Registration budgetRegistration = null;
            Object pooledXaDataSource = null;
            try {
                // Parse URL to remove OJP-specific prefix (same as non-XA path)
                String parsedUrl = UrlParser.parseUrl(connectionDetails.getUrl());

                // Get XA datasource configuration from client properties (uses XA-specific properties)
                Properties clientProperties = ConnectionPoolConfigurer.extractClientProperties(connectionDetails);
                DataSourceConfigurationManager.XADataSourceConfiguration xaConfig =
                        DataSourceConfigurationManager.getXAConfiguration(clientProperties);

                // Get default pool sizes from XA configuration
                int maxPoolSize = xaConfig.getMaximumPoolSize();
                int minIdle = xaConfig.getMinimumIdle();

                log.info("XA pool BEFORE multinode coordination for {}: requested max={}, min={}",
                        connHash, maxPoolSize, minIdle);

                // Apply multinode pool coordination if server endpoints provided
                List<String> serverEndpoints = connectionDetails.getServerEndpointsList();
                log.info("XA serverEndpoints list: null={}, size={}, endpoints={}",
                        serverEndpoints == null,
                        serverEndpoints == null ? 0 : serverEndpoints.size(),
                        serverEndpoints);

                if (serverEndpoints != null && !serverEndpoints.isEmpty()) {
                    // Multinode: divide pool sizes among servers
                    MultinodePoolCoordinator.PoolAllocation allocation =
                            ConnectionPoolConfigurer.getPoolCoordinator().calculatePoolSizes(
                                    connHash, maxPoolSize, minIdle, serverEndpoints);

                    maxPoolSize = allocation.getCurrentMaxPoolSize();
                    minIdle = allocation.getCurrentMinIdle();

                    log.info("XA multinode pool coordination for {}: {} servers, divided sizes: max={}, min={}",
                            connHash, serverEndpoints.size(), maxPoolSize, minIdle);
                } else {
                    log.info("XA multinode coordination SKIPPED for {}: serverEndpoints null or empty", connHash);
                }

                DatabaseConnectionBudgetManager budgetManager = DatabaseConnectionBudgetManager.getInstance();
                budgetRegistration = budgetManager.registerPool("xa:" + connHash, parsedUrl,
                        connectionDetails.getUser(), maxPoolSize, minIdle,
                        context.getXaPoolProvider().supportsDynamicResizing());
                maxPoolSize = budgetRegistration.getMaximumPoolSize();
                minIdle = budgetRegistration.getMinimumIdle();

                log.info("XA pool AFTER multinode coordination for {}: final max={}, min={}",
                        connHash, maxPoolSize, minIdle);


                // Build configuration map for XA Pool Provider
                Map<String, String> xaPoolConfig = new HashMap<>();
                xaPoolConfig.put("xa.datasource.className", getXADataSourceClassName(parsedUrl));
                xaPoolConfig.put("xa.url", parsedUrl);
                xaPoolConfig.put("xa.username", connectionDetails.getUser());
                xaPoolConfig.put("xa.password", connectionDetails.getPassword());
                // Use calculated pool sizes (with multinode coordination if applicable)
                xaPoolConfig.put("xa.maxPoolSize", String.valueOf(maxPoolSize));
                xaPoolConfig.put("xa.minIdle", String.valueOf(minIdle));
                xaPoolConfig.put("xa.connectionTimeoutMs",
                        String.valueOf(CommonConstants.FAIL_FAST_POOL_CONNECTION_TIMEOUT_MS));
                xaPoolConfig.put("xa.idleTimeoutMs", String.valueOf(xaConfig.getIdleTimeout()));
                xaPoolConfig.put("xa.maxLifetimeMs", String.valueOf(xaConfig.getMaxLifetime()));
                // Evictor configuration
                xaPoolConfig.put("xa.timeBetweenEvictionRunsMs", String.valueOf(xaConfig.getTimeBetweenEvictionRuns()));
                xaPoolConfig.put("xa.numTestsPerEvictionRun", String.valueOf(xaConfig.getNumTestsPerEvictionRun()));
                xaPoolConfig.put("xa.softMinEvictableIdleTimeMs", String.valueOf(xaConfig.getSoftMinEvictableIdleTime()));
                xaPoolConfig.putAll(PreparedStatementCachePropertyTranslator.buildXaProperties(
                        context.getServerConfiguration(), parsedUrl));

                // Create pooled XA DataSource via provider
                log.info("[XA-POOL-CREATE] Creating XA pool for connHash={}, serverEndpointsHash={}, config=(max={}, min={})",
                        connHash, currentEndpointsHash, maxPoolSize, minIdle);
                pooledXaDataSource = context.getXaPoolProvider().createXADataSource(xaPoolConfig);

                // Create XA Transaction Registry with serverEndpoints hash and pool sizes for validation
                registry = new XATransactionRegistry(context.getXaPoolProvider(), pooledXaDataSource, currentEndpointsHash, maxPoolSize, minIdle);

                // Initialize pool with minIdle connections immediately after creation
                // Without this, the pool starts empty and only creates connections on demand
                log.info("[XA-POOL-INIT] Initializing XA pool with minIdle={} connections for connHash={}", minIdle, connHash);
                registry.resizeBackendPool(maxPoolSize, minIdle);
                XATransactionRegistry createdRegistry = registry;
                budgetManager.attachPool(budgetRegistration, (newMaximum, newMinimum) -> {
                    createdRegistry.resizeBackendPool(newMaximum, newMinimum);
                    AdmissionControlManager admissionControlManager =
                            context.getAdmissionControlManagers().get(connHash);
                    if (admissionControlManager != null) {
                        admissionControlManager.resizeCapacity(newMaximum);
                    }
                },
                        maxPoolSize, minIdle);

                // Create slow query segregation manager for XA
                CreateSlowQuerySegregationManagerAction.getInstance().execute(
                        context, connHash, maxPoolSize, true, xaConfig.getConnectionTimeout());
                context.getXaRegistries().put(connHash, registry);

                log.info("[XA-POOL-CREATE] Successfully created XA pool for connHash={} - maxPoolSize={}, minIdle={}, multinode={}, poolObject={}",
                        connHash, maxPoolSize, minIdle, serverEndpoints != null && !serverEndpoints.isEmpty(),
                        pooledXaDataSource.getClass().getSimpleName());

            } catch (Exception e) {
                DatabaseConnectionBudgetManager.getInstance().unregisterPool(budgetRegistration);
                if (pooledXaDataSource instanceof javax.sql.XADataSource) {
                    try {
                        context.getXaPoolProvider().closeXADataSource((javax.sql.XADataSource) pooledXaDataSource);
                    } catch (Exception closeFailure) {
                        e.addSuppressed(closeFailure);
                    }
                }
                log.error("[XA-POOL-CREATE] FAILED to create XA Pool Provider registry for connHash={}, serverEndpointsHash={}: {}",
                        connHash, currentEndpointsHash, e.getMessage(), e);
                SQLException sqlException = new SQLException("Failed to create XA pool: " + e.getMessage(), e);
                sendSQLExceptionMetadata(sqlException, responseObserver);
                return null;  // Error was sent to client
            }
        } else {
            log.debug("[XA-POOL-REUSE] Reusing EXISTING XA registry for connHash={} (pool already created, cached sizes: max={}, min={})",
                    connHash, registry.getMaxPoolSize(), registry.getMinIdle());
        }

        return registry;
    }

    /**
     * Borrow a session from the pool and send response to client.
     * This method is called WITHOUT holding the registry lock to allow concurrent borrowing.
     */
    private void borrowSessionAndRespond(ActionContext context, ConnectionDetails connectionDetails, String connHash,
                       int actualMaxXaTransactions, long xaStartTimeoutMillis, XATransactionRegistry registry,
                       StreamObserver<SessionInfo> responseObserver) {

        context.getSessionManager().registerClientUUID(connHash, connectionDetails.getClientUUID());

        // CRITICAL FIX: Call processClusterHealth() BEFORE borrowing session
        // This ensures pool rebalancing happens even when server 1 fails before any XA operations execute
        // Without this, pool exhaustion prevents cluster health propagation and pool never expands
        if (connectionDetails.getClusterHealth() != null && !connectionDetails.getClusterHealth().isEmpty()) {
            // Use the ACTUAL cluster health from the client (not synthetic)
            // The client sends the current health status of all servers
            String actualClusterHealth = connectionDetails.getClusterHealth();

            // Create a temporary SessionInfo with cluster health for processing
            // We don't have the actual sessionInfo yet since we haven't borrowed from the pool
            SessionInfo tempSessionInfo = SessionInfo.newBuilder()
                    .setSessionUUID("temp-for-health-check")
                    .setConnHash(connHash)
                    .setClusterHealth(actualClusterHealth)
                    .build();

            log.debug("[XA-CONNECT-REBALANCE] Calling processClusterHealth BEFORE borrow for connHash={}, clusterHealth={}",
                    connHash, actualClusterHealth);

            // Process cluster health to trigger pool rebalancing if needed
            ProcessClusterHealthAction.getInstance().execute(context, tempSessionInfo);
        } else {
            log.warn("[XA-CONNECT-REBALANCE] No cluster health provided in ConnectionDetails for connHash={}, pool rebalancing may be delayed",
                    connHash);
        }

        // Borrow a XABackendSession from the pool for immediate use
        // Note: Unlike the original "deferred" approach, we allocate eagerly because
        // XA applications expect getConnection() to work immediately, before xaStart()
        XABackendSession backendSession = null;
        try {
            backendSession =
                    (XABackendSession) context.getXaPoolProvider().borrowSession(registry.getPooledXADataSource());

            XAConnection xaConnection = backendSession.getXAConnection();
            Connection connection = backendSession.getConnection();

            // Create XA session with the pooled XAConnection
            SessionInfo sessionInfo = context.getSessionManager().createXASession(
                    connectionDetails.getClientUUID(), connection, xaConnection);

            // Store the XABackendSession reference in the session for later lifecycle management
            Session session = context.getSessionManager().getSession(sessionInfo);
            if (session != null) {
                session.setBackendSession(backendSession);
            }

            log.info("Created XA session (pooled, eager allocation) with client UUID: {} for connHash: {}",
                    connectionDetails.getClientUUID(), connHash);

            // Note: processClusterHealth() already called BEFORE borrowing session (see above)
            // This ensures pool is resized before we try to borrow, preventing exhaustion

            responseObserver.onNext(sessionInfo);
            context.getDbNameMap().put(connHash, DatabaseUtils.resolveDbName(connectionDetails.getUrl()));
            responseObserver.onCompleted();

        } catch (Exception e) {
            log.error("Failed to borrow XABackendSession from pool for connection hash {}: {}",
                    connHash, e.getMessage(), e);

            // CRITICAL FIX: Return the borrowed session back to pool on failure to prevent session leaks
            // This was causing PostgreSQL "too many clients" errors as leaked sessions bypassed pool limits
            if (backendSession != null) {
                try {
                    context.getXaPoolProvider().returnSession(registry.getPooledXADataSource(), backendSession);
                    log.debug("Returned leaked session to pool after connect() failure for connHash: {}", connHash);
                } catch (Exception e2) {
                    log.error("Failed to return session after connect() failure for connHash: {}", connHash, e2);
                    // Try to invalidate instead to prevent corrupted session reuse
                    try {
                        context.getXaPoolProvider().invalidateSession(registry.getPooledXADataSource(), backendSession);
                        log.warn("Invalidated session after failed return for connHash: {}", connHash);
                    } catch (Exception e3) {
                        log.error("Failed to invalidate session after connect() failure for connHash: {}", connHash, e3);
                    }
                }
            }

            SQLException sqlException = new SQLException("Failed to allocate XA session from pool: " + e.getMessage(), e);
            sendSQLExceptionMetadata(sqlException, responseObserver);
            return;
        }
    }

    /**
     * Determine XADataSource class name based on database URL.
     */
    private String getXADataSourceClassName(String url) {
        String lowerUrl = url.toLowerCase();
        if (lowerUrl.contains(":postgresql:")) {
            return "org.postgresql.xa.PGXADataSource";
        } else if (lowerUrl.contains(":oracle:")) {
            return "oracle.jdbc.xa.client.OracleXADataSource";
        } else if (lowerUrl.contains(":sqlserver:")) {
            return "com.microsoft.sqlserver.jdbc.SQLServerXADataSource";
        } else if (lowerUrl.contains(":db2:")) {
            return "com.ibm.db2.jcc.DB2XADataSource";
        } else if (lowerUrl.contains(":mysql:") || lowerUrl.contains(":mariadb:")) {
            return "com.mysql.cj.jdbc.MysqlXADataSource";
        } else {
            throw new IllegalArgumentException("Unsupported database for XA: " + url);
        }
    }
}
