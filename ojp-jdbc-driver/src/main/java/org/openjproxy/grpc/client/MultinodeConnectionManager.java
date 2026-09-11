package org.openjproxy.grpc.client;

import com.openjproxy.grpc.ConnectionDetails;
import com.openjproxy.grpc.PropertyEntry;
import com.openjproxy.grpc.SessionInfo;
import com.openjproxy.grpc.StatementServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import org.openjproxy.constants.CommonConstants;
import org.openjproxy.grpc.GrpcChannelFactory;
import org.openjproxy.grpc.ProtoConverter;
import org.openjproxy.jdbc.DatasourcePropertiesLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Manages multinode connections to OJP servers with load-aware routing,
 * session stickiness, and failover support.
 *
 * Server Selection Strategies:
 * - Load-aware (default): Selects the healthy server with the fewest active connections
 * - Round-robin (legacy): Cycles through healthy servers in order
 *
 * Addressing PR #39 review comments:
 * - Comment #3: Throws exception when session server is unavailable (enforces session stickiness)
 * - Configurable retry attempts and delays (via CommonConstants properties)
 */
public class MultinodeConnectionManager {

    private static final Logger log = LoggerFactory.getLogger(MultinodeConnectionManager.class);
    private static final String DNS_PREFIX = "dns:///";

    private final List<ServerEndpoint> serverEndpoints;
    private final Map<ServerEndpoint, ChannelAndStub> channelMap;
    private final Map<String, ServerEndpoint> sessionToServerMap; // sessionUUID -> server
    private final Map<String, List<ServerEndpoint>> connHashToServersMap; // connHash -> list of servers that received connect()
    private final AtomicInteger roundRobinCounter;
    private final int retryAttempts;
    private final long retryDelayMs;
    private final List<ServerHealthListener> healthListeners; // Phase 2: Listeners for server health changes

    // connHash cache for the connect()-RPC-skip optimisation (non-XA only).
    // Key  : connectionKey  = url + "|" + user + "|" + password + "|" + dataSourceName
    // Value: connHash string returned by the server on the first successful connect()
    private final Map<String, String> connHashByConnectionKey;
    // Stores the full ConnectionDetails per connHash so that we can re-issue a real
    // connect() call when the server signals NOT_FOUND (pool lost on server restart).
    private final Map<String, ConnectionDetails> connectionDetailsByConnHash;

    // Health check and redistribution support
    private final HealthCheckConfig healthCheckConfig;
    private final AtomicLong lastHealthCheckTimestamp;
    private final HealthCheckValidator healthCheckValidator;
    private final ConnectionTracker connectionTracker; // Legacy - kept for backward compatibility
    private final SessionTracker sessionTracker; // New unified tracker
    private final ConnectionRedistributor connectionRedistributor;
    private XAConnectionRedistributor xaConnectionRedistributor;
    private final ScheduledExecutorService healthCheckScheduler;

    public MultinodeConnectionManager(List<ServerEndpoint> serverEndpoints) {
        this(serverEndpoints, CommonConstants.DEFAULT_MULTINODE_RETRY_ATTEMPTS,
             CommonConstants.DEFAULT_MULTINODE_RETRY_DELAY_MS, null);
    }

    public MultinodeConnectionManager(List<ServerEndpoint> serverEndpoints,
                                    int retryAttempts, long retryDelayMs) {
        this(serverEndpoints, retryAttempts, retryDelayMs, null);
    }

    public MultinodeConnectionManager(List<ServerEndpoint> serverEndpoints,
                                    int retryAttempts, long retryDelayMs,
                                    HealthCheckConfig healthCheckConfig) {
        this(serverEndpoints, retryAttempts, retryDelayMs, healthCheckConfig, null);
    }

    public MultinodeConnectionManager(List<ServerEndpoint> serverEndpoints,
                                    int retryAttempts, long retryDelayMs,
                                    HealthCheckConfig healthCheckConfig,
                                    ConnectionTracker connectionTracker) {
        if (serverEndpoints == null || serverEndpoints.isEmpty()) {
            throw new IllegalArgumentException("Server endpoints list cannot be null or empty");
        }

        this.serverEndpoints = List.copyOf(serverEndpoints);
        this.channelMap = new ConcurrentHashMap<>();
        this.sessionToServerMap = new ConcurrentHashMap<>();
        this.connHashToServersMap = new ConcurrentHashMap<>();
        this.connHashByConnectionKey = new ConcurrentHashMap<>();
        this.connectionDetailsByConnHash = new ConcurrentHashMap<>();
        this.roundRobinCounter = new AtomicInteger(0);
        this.retryAttempts = retryAttempts;
        this.retryDelayMs = retryDelayMs;
        this.healthListeners = new ArrayList<>();
        this.healthCheckConfig = healthCheckConfig != null ? healthCheckConfig : HealthCheckConfig.createDefault();
        this.lastHealthCheckTimestamp = new AtomicLong(0);
        this.connectionTracker = connectionTracker != null ? connectionTracker : new ConnectionTracker(); // Legacy
        this.sessionTracker = new SessionTracker();
        this.healthCheckValidator = new HealthCheckValidator(this.healthCheckConfig, this);
        this.connectionRedistributor = new ConnectionRedistributor(this.connectionTracker, this.healthCheckConfig);

        // Initialize channels and stubs for all servers
        initializeConnections();

        // Start periodic health checker
        if (this.healthCheckConfig.isRedistributionEnabled()) {
            this.healthCheckScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ojp-health-checker");
                t.setDaemon(true); // Don't prevent JVM shutdown
                return t;
            });

            long intervalMs = this.healthCheckConfig.getHealthCheckIntervalMs();
            this.healthCheckScheduler.scheduleAtFixedRate(
                () -> {
                    try {
                        lastHealthCheckTimestamp.set(System.nanoTime());
                        performHealthCheck();
                    } catch (Exception e) {
                        log.warn("Periodic health check failed: {}", e.getMessage());
                    }
                },
                intervalMs, // Initial delay
                intervalMs, // Period
                TimeUnit.MILLISECONDS
            );
            log.info("Started periodic health checker with interval {}ms", intervalMs);
        } else {
            this.healthCheckScheduler = null;
            log.info("Periodic health checker disabled (redistribution not enabled)");
        }

        log.info("MultinodeConnectionManager initialized with {} servers: {}, health check config: {}",
                serverEndpoints.size(), serverEndpoints, this.healthCheckConfig);
    }

    private void initializeConnections() {
        for (ServerEndpoint endpoint : serverEndpoints) {
            try {
                createChannelAndStub(endpoint);
                endpoint.markHealthy();
                log.debug("Successfully initialized connection to {}", endpoint.getAddress());
            } catch (Exception e) {
                log.warn("Failed to initialize connection to {}: {}", endpoint.getAddress(), e.getMessage());
                endpoint.markUnhealthy();
                endpoint.setLastFailureTime(System.nanoTime());
            }
        }
    }

    private ChannelAndStub createChannelAndStub(ServerEndpoint endpoint) {
        // Synchronize on the endpoint to prevent concurrent channel creation for the same endpoint
        synchronized (endpoint) {
            String target = DNS_PREFIX + endpoint.getHost() + ":" + endpoint.getPort();
            ManagedChannel channel = GrpcChannelFactory.createChannel(target);

            StatementServiceGrpc.StatementServiceBlockingStub blockingStub =
                    StatementServiceGrpc.newBlockingStub(channel);
            StatementServiceGrpc.StatementServiceStub asyncStub =
                    StatementServiceGrpc.newStub(channel);

            ChannelAndStub newChannelAndStub = new ChannelAndStub(channel, blockingStub, asyncStub);

            // Atomically replace old channel with new one and shutdown the old one if it exists
            // This prevents race conditions by doing check-and-replace atomically
            ChannelAndStub oldChannelAndStub = channelMap.put(endpoint, newChannelAndStub);
            if (oldChannelAndStub != null) {
                log.debug("Shutting down replaced channel for {}", endpoint.getAddress());
                try {
                    oldChannelAndStub.channel.shutdown();
                } catch (Exception e) {
                    log.warn("Error shutting down replaced channel for {}: {}", endpoint.getAddress(), e.getMessage());
                }
            }

            return newChannelAndStub;
        }
    }

    /**
     * Establishes a connection by calling connect() on servers.
     *
     * <p><b>XA connections</b> are sent to a <em>single</em> selected healthy server
     * (chosen by the same load-aware / round-robin strategy used for all SQL operations).
     * Connecting to all servers would waste resources by creating unnecessary XA pools
     * on every node for what is a single-server transaction.</p>
     *
     * <p><b>Non-XA connections</b> use a cached connHash after the first successful
     * connect, so subsequent JDBC {@code Connection} objects are created without any
     * gRPC round-trip.  If the server later returns {@code NOT_FOUND} (pool lost after
     * restart), the cache is invalidated and a real connect() is re-issued transparently.</p>
     *
     * @return the SessionInfo from the successful connection
     */
    public SessionInfo connect(ConnectionDetails connectionDetails) throws SQLException {
        boolean isXA = connectionDetails.getIsXA();

        log.info("=== connect() called: isXA={} ===", isXA);

        if (isXA) {
            // XA: connect to exactly one server (load-balanced / round-robin selection).
            log.debug("XA connection: connecting to single selected server");
            return connectToSingleServer(connectionDetails);
        }

        // Non-XA: attempt to use the cached connHash to avoid the round-trip.
        String connectionKey = computeConnectionKey(connectionDetails);
        String cachedConnHash = connHashByConnectionKey.get(connectionKey);

        if (cachedConnHash != null) {
            // Fast path: build SessionInfo locally without contacting the server.
            log.debug("Non-XA connect() skipping RPC – using cached connHash for key ending in '...{}'",
                    connectionKey.substring(Math.max(0, connectionKey.length() - 20)));
            return buildLocalSessionInfo(cachedConnHash, connectionDetails.getClientUUID());
        }

        // First time (or after cache invalidation): do the real connect().
        log.debug("Non-XA connect(): no cached connHash, performing real connect() RPC");
        SessionInfo sessionInfo = connectToAllServers(connectionDetails);

        // Cache the connHash so future connect() calls can skip the RPC.
        if (sessionInfo.getConnHash() != null && !sessionInfo.getConnHash().isEmpty()) {
            connHashByConnectionKey.put(connectionKey, sessionInfo.getConnHash());
            connectionDetailsByConnHash.put(sessionInfo.getConnHash(), connectionDetails);
            log.info("Non-XA connHash cached for fast subsequent connects (connHash={})", sessionInfo.getConnHash());
        }

        return sessionInfo;
    }

    /**
     * Calls {@code connect()} on a single server selected by the configured strategy
     * (least-connections, falling back to round-robin when loads are equal).
     * Used exclusively for XA connections.
     *
     * <p>Each new XA connection binds to exactly one server.  The least-connections
     * strategy ensures that successive XA connections are distributed evenly across
     * the cluster: the first connection goes to an arbitrary server (all counts 0,
     * so round-robin applies); each subsequent one goes to whichever server currently
     * has the lowest XA session count, balancing the load automatically.</p>
     *
     * <p>If the chosen server fails with a connection-level error it is marked
     * unhealthy and the next-best server is tried, until one succeeds or all
     * candidates are exhausted.  Only ONE server ends up with a pool created for
     * this connection.</p>
     */
    private SessionInfo connectToSingleServer(ConnectionDetails connectionDetails) throws SQLException {
        List<ServerEndpoint> attempted = new ArrayList<>();
        SQLException lastException = null;

        while (true) {
            // Re-evaluate the best server on every attempt so we skip newly-marked-unhealthy ones.
            List<ServerEndpoint> healthyServers = serverEndpoints.stream()
                    .filter(ep -> ep.isHealthy() && !attempted.contains(ep))
                    .collect(Collectors.toList());

            if (healthyServers.isEmpty()) {
                String msg = "No healthy servers available for XA connection"
                        + (lastException != null ? ". Last error: " + lastException.getMessage() : "");
                throw new SQLException(msg);
            }

            // Pick the least-loaded server among the remaining candidates.
            ServerEndpoint server = healthCheckConfig.isLoadAwareSelectionEnabled()
                    ? selectByLeastConnections(healthyServers)
                    : selectByRoundRobin(healthyServers);

            if (server == null) {
                throw new SQLException("No server could be selected for XA connection");
            }

            attempted.add(server);

            try {
                ChannelAndStub channelAndStub = channelMap.get(server);
                if (channelAndStub == null) {
                    channelAndStub = createChannelAndStub(server);
                }

                log.info("XA connect: trying server {} ({} attempted so far)", server.getAddress(), attempted.size());
                SessionInfo sessionInfo = channelAndStub.blockingStub.connect(connectionDetails);

                // Mark server healthy on success
                server.setHealthy(true);
                server.setLastFailureTime(0);

                // Bind the XA session to the server that created it
                if (sessionInfo.getSessionUUID() != null && !sessionInfo.getSessionUUID().isEmpty()) {
                    sessionToServerMap.put(sessionInfo.getSessionUUID(), server);
                    sessionTracker.registerSession(sessionInfo.getSessionUUID(), server);
                    log.info("XA session {} bound to server {}", sessionInfo.getSessionUUID(), server.getAddress());
                }

                // Track which server received connect() for this connHash (for terminateSession cleanup)
                if (sessionInfo.getConnHash() != null && !sessionInfo.getConnHash().isEmpty()) {
                    connHashToServersMap.put(sessionInfo.getConnHash(), List.of(server));
                    log.info("Tracked server {} for XA connHash {}", server.getAddress(), sessionInfo.getConnHash());
                }

                log.info("XA connect succeeded on server {}", server.getAddress());
                return sessionInfo;

            } catch (StatusRuntimeException e) {
                // isConnectionLevelError() is used instead of relying on GrpcExceptionHandler.handle()
                // throwing/not-throwing, since handle() now also converts connection-level codes
                // (UNAVAILABLE, DEADLINE_EXCEEDED) into a checked SQLTransientConnectionException.
                boolean isConnectionLevel = GrpcExceptionHandler.isConnectionLevelError(e);
                try {
                    GrpcExceptionHandler.handle(e);
                    lastException = new SQLException("gRPC call failed: " + e.getMessage(), e);
                } catch (SQLException sqlEx) {
                    lastException = sqlEx;
                }

                if (isConnectionLevel) {
                    // Connection-level failure: mark server unhealthy and try the next candidate.
                    handleServerFailure(server, e);
                    log.warn("XA connect failed on server {} with connection-level error, trying next: {}",
                            server.getAddress(), lastException.getMessage());
                } else {
                    // Database-level failure (auth, config, etc.): no point trying other servers.
                    log.warn("XA connect failed on server {} with database-level error, not retrying: {}",
                            server.getAddress(), lastException.getMessage());
                    throw lastException;
                }
            }
        }
    }

    /**
     * Computes a stable cache key from the connection parameters that the server
     * uses to compute its own {@code connHash}.  The key is intentionally kept as
     * a plain string (not hashed) because it is only used as an in-process map key.
     */
    private String computeConnectionKey(ConnectionDetails connectionDetails) {
        String dataSourceName = extractDataSourceName(connectionDetails);
        return connectionDetails.getUrl() + "|" + connectionDetails.getUser()
                + "|" + connectionDetails.getPassword() + "|" + dataSourceName;
    }

    /**
     * Extracts the {@code ojp.datasource.name} property from ConnectionDetails,
     * returning {@code "default"} when absent.
     */
    private String extractDataSourceName(ConnectionDetails connectionDetails) {
        for (com.openjproxy.grpc.PropertyEntry entry : connectionDetails.getPropertiesList()) {
            if ("ojp.datasource.name".equals(entry.getKey())) {
                return entry.getStringValue();
            }
        }
        return "default";
    }

    /**
     * Builds a non-XA SessionInfo locally from a cached connHash.
     * This mirrors what the server returns for a regular (non-XA) connect():
     * connHash + clientUUID, no sessionUUID (lazy allocation).
     */
    private SessionInfo buildLocalSessionInfo(String connHash, String clientUUID) {
        return SessionInfo.newBuilder()
                .setConnHash(connHash)
                .setClientUUID(clientUUID)
                .setIsXA(false)
                .build();
    }

    /**
     * Invalidates the cached connHash for a given connHash value.
     *
     * <p>Called by {@link MultinodeStatementService} when the server returns
     * {@code Status.NOT_FOUND} to signal that the pool for this connHash no
     * longer exists (e.g. after a server restart).  After invalidation the next
     * {@link #connect} call will perform a real gRPC RPC to recreate the pool.</p>
     *
     * @param connHash the connHash whose cache entry should be removed
     */
    public void invalidateConnHash(String connHash) {
        if (connHash == null || connHash.isEmpty()) {
            return;
        }
        // Keep the connectionDetailsByConnHash entry so that reconnectForConnHash() can
        // look up the original ConnectionDetails and re-issue connect(). Only the
        // connection-key → connHash cache is cleared so the next getConnection() call
        // performs a real connect() RPC instead of using the stale cached hash.
        ConnectionDetails stored = connectionDetailsByConnHash.get(connHash);
        if (stored != null) {
            String key = computeConnectionKey(stored);
            connHashByConnectionKey.remove(key);
            log.info("Invalidated cached connHash {} (pool lost on server)", connHash);
        }
    }

    /**
     * Re-issues a real {@code connect()} RPC for the given connHash after the server
     * has signalled that the pool no longer exists.
     *
     * <p>This is the reconnect leg of the NOT_FOUND retry loop in
     * {@link MultinodeStatementService}.  The stored {@link ConnectionDetails} are
     * used so the caller does not need to know the original credentials.</p>
     *
     * @param connHash the connHash for which to reconnect
     * @return the new SessionInfo, or {@code null} if no stored ConnectionDetails exist
     * @throws SQLException if the connect() RPC fails
     */
    public SessionInfo reconnectForConnHash(String connHash) throws SQLException {
        ConnectionDetails stored = connectionDetailsByConnHash.get(connHash);
        if (stored == null) {
            log.warn("reconnectForConnHash: no stored ConnectionDetails for connHash {}", connHash);
            return null;
        }
        log.info("Reconnecting after NOT_FOUND for connHash {}", connHash);
        SessionInfo sessionInfo = connectToAllServers(stored);
        if (sessionInfo.getConnHash() != null && !sessionInfo.getConnHash().isEmpty()) {
            String key = computeConnectionKey(stored);
            connHashByConnectionKey.put(key, sessionInfo.getConnHash());
            connectionDetailsByConnHash.put(sessionInfo.getConnHash(), stored);
            // Remove the old connHash entry now that it has been replaced by the new one.
            // Skip the removal when the server returned the same hash (deterministic case).
            if (!connHash.equals(sessionInfo.getConnHash())) {
                connectionDetailsByConnHash.remove(connHash);
            }
            log.info("Re-cached connHash {} after reconnect (replaced {})", sessionInfo.getConnHash(), connHash);
        }
        return sessionInfo;
    }


    /**
     * Performs health check on all servers.
     * - For XA mode: Proactively checks healthy servers and invalidates sessions when they fail
     * - For all modes: Checks unhealthy servers to see if they've recovered
     */
    private void performHealthCheck() {
        log.info("Performing health check on servers");

        // Proactively check healthy servers to detect failures early.
        // Run when there are active XA sessions OR cached non-XA connection details.
        // This ensures both XA and non-XA pools adapt promptly when a server goes down.
        // The guard prevents spurious "No healthy servers" errors on the very first
        // connect() call before any server has been contacted.
        boolean hasActiveSessions = !sessionToServerMap.isEmpty();
        boolean hasNonXaConnections = !connectionDetailsByConnHash.isEmpty();
        if (hasActiveSessions || hasNonXaConnections) {
            List<ServerEndpoint> healthyServers = serverEndpoints.stream()
                    .filter(ServerEndpoint::isHealthy)
                    .collect(Collectors.toList());

            for (ServerEndpoint endpoint : healthyServers) {
                if (!validateServer(endpoint)) {
                    log.info("Health check: Server {} has become unhealthy", endpoint.getAddress());

                    // Mark server unhealthy
                    endpoint.setHealthy(false);
                    endpoint.setLastFailureTime(System.nanoTime());

                    // XA Mode: Immediately invalidate sessions and connections for the failed server
                    if (hasActiveSessions) {
                        invalidateSessionsAndConnectionsForFailedServer(endpoint);
                    }

                    // Notify listeners
                    notifyServerUnhealthy(endpoint, new Exception("Health check failed"));

                    // Proactively push the updated cluster health to remaining healthy servers
                    // so they resize their pools immediately, even when no SQL operations are
                    // in flight to carry the update via SessionInfo.
                    pushClusterHealthToAllHealthyServers();
                }
            }
        }

        // Check unhealthy servers to see if they've recovered
        List<ServerEndpoint> unhealthyServers = serverEndpoints.stream()
                .filter(endpoint -> !endpoint.isHealthy())
                .collect(Collectors.toList());

        if (unhealthyServers.isEmpty()) {
            log.info("No unhealthy servers to check");
            return;
        }

        log.info("Checking {} unhealthy server(s)", unhealthyServers.size());

        List<ServerEndpoint> recoveredServers = new ArrayList<>();

        // Check each unhealthy server
        for (ServerEndpoint endpoint : unhealthyServers) {
            long timeSinceFailure = (System.nanoTime() - endpoint.getLastFailureTime()) / 1_000_000L;

            // Only check if enough time has passed since last failure
            if (timeSinceFailure >= healthCheckConfig.getHealthCheckThresholdMs()) {
                if (validateServer(endpoint)) {
                    // For non-XA connections: proactively re-initialize pools BEFORE marking the
                    // server healthy. Non-XA connect() calls are cached after the first successful
                    // connect(), so the recovered server never receives a connect() RPC from
                    // subsequent JDBC connections. Without this step, the server would be marked
                    // healthy and begin receiving queries before its HikariCP pool exists, causing
                    // NOT_FOUND errors for every thread that already holds a cached connHash.
                    // Pre-creating the pool here closes that window entirely.
                    if (!connectionDetailsByConnHash.isEmpty()) {
                        reinitializePoolOnRecoveredServer(endpoint);
                    }

                    log.info("Server {} has recovered", endpoint.getAddress());

                    endpoint.markHealthy();
                    recoveredServers.add(endpoint);

                    // Proactively push the updated cluster health to all now-healthy servers
                    // (including the just-recovered one) so their pools resize immediately.
                    // This also causes peer servers that were running at expanded capacity
                    // to shrink back down without waiting for SQL operations to carry the update.
                    pushClusterHealthToAllHealthyServers();

                    notifyServerRecovered(endpoint);
                } else {
                    // Still unhealthy, update timestamp
                    endpoint.setLastFailureTime(System.nanoTime());
                    log.info("Server {} still unhealthy", endpoint.getAddress());
                }
            }
        }

        // For non-XA mode: trigger connection redistribution when servers recover.
        // XA mode handles redistribution via notifyServerRecovered() → XAConnectionRedistributor.
        if (!recoveredServers.isEmpty() && xaConnectionRedistributor == null && healthCheckConfig.isRedistributionEnabled()) {
            log.info("Triggering connection redistribution for {} recovered server(s)",
                    recoveredServers.size());

            List<ServerEndpoint> allHealthyServers = serverEndpoints.stream()
                    .filter(ServerEndpoint::isHealthy)
                    .collect(Collectors.toList());

            try {
                connectionRedistributor.rebalance(recoveredServers, allHealthyServers);
            } catch (Exception e) {
                log.error("Failed to redistribute connections after server recovery: {}",
                        e.getMessage(), e);
            }
        }
    }

    /**
     * Re-initializes the connection pool on a recovered server for non-XA connections.
     *
     * <p>When a server restarts, it loses all in-memory pool state. The non-XA
     * connect()-caching optimisation means that subsequent JDBC connections never
     * call the server's {@code connect()} RPC, so the recovered server would not
     * receive a pool until the NOT_FOUND retry path is triggered by the first SQL
     * request routed to it.</p>
     *
     * <p>By proactively calling {@code connect()} here - before the updated
     * {@code clusterHealth} value propagates to other servers via {@link SessionInfo}
     * in SQL operations - we ensure:</p>
     * <ol>
     *   <li>The recovered server creates its HikariCP pool immediately (with the
     *       correct divided pool size).</li>
     *   <li>HikariCP starts pre-warming connections on the recovered server.</li>
     *   <li>Other servers only reduce their pool sizes (via {@code clusterHealth}
     *       in the next SQL call) after the recovered server is already accepting
     *       connections, preventing a temporary drop in total connections.</li>
     * </ol>
     *
     * @param recoveredServer the server endpoint that has just been marked healthy
     */
    private void reinitializePoolOnRecoveredServer(ServerEndpoint recoveredServer) {
        if (connectionDetailsByConnHash.isEmpty()) {
            log.debug("No stored connection details; skipping pool re-initialization on {}",
                    recoveredServer.getAddress());
            return;
        }

        log.info("Re-initializing pool(s) on recovered server {}", recoveredServer.getAddress());

        // Resolve the channel once for this server (not per connection hash)
        ChannelAndStub channelAndStub = channelMap.get(recoveredServer);
        if (channelAndStub == null) {
            try {
                channelAndStub = createChannelAndStub(recoveredServer);
            } catch (Exception e) {
                log.warn("Failed to obtain channel to recovered server {}; skipping pool re-initialization: {}",
                        recoveredServer.getAddress(), e.getMessage());
                return;
            }
        }

        for (Map.Entry<String, ConnectionDetails> entry : connectionDetailsByConnHash.entrySet()) {
            String connHash = entry.getKey();
            ConnectionDetails connectionDetails = entry.getValue();

            try {
                log.info("Calling connect() on recovered server {} for connHash {}",
                        recoveredServer.getAddress(), connHash);
                channelAndStub.blockingStub.connect(connectionDetails);
                log.info("Pool re-initialized on recovered server {} for connHash {}",
                        recoveredServer.getAddress(), connHash);
            } catch (Exception e) {
                log.warn("Failed to re-initialize pool on recovered server {} for connHash {}: {}",
                        recoveredServer.getAddress(), connHash, e.getMessage());
            }
        }
    }

    /**
     * Invalidates all XA sessions and connections for a server that has become unhealthy.
     *
     * In XA mode, when a server fails, we immediately:
     * 1. Clear client-side session bindings from sessionToServerMap
     * 2. Unregister sessions from SessionTracker
     * 3. Mark Connection objects as invalid (forceInvalid) so pools discard them
     * 4. Close connections to force pool replacement
     *
     * This prevents attempts to use stale sessions on the failed server. When the server
     * recovers, new connections will be created with fresh sessions.
     *
     * @param endpoint The server endpoint that has failed
     */
    private void invalidateSessionsAndConnectionsForFailedServer(ServerEndpoint endpoint) {
        log.info("Invalidating all XA sessions and connections for failed server {}", endpoint.getAddress());

        // Step 1: Remove all session bindings for this server
        List<String> sessionsToInvalidate = sessionToServerMap.entrySet().stream()
                .filter(entry -> entry.getValue().equals(endpoint))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        for (String sessionUUID : sessionsToInvalidate) {
            sessionToServerMap.remove(sessionUUID);
            // Unregister from SessionTracker
            sessionTracker.unregisterSession(sessionUUID);
            log.debug("Removed session binding {} for failed server {}", sessionUUID, endpoint.getAddress());
        }

        // Step 2: Mark all connections for this server as invalid and close them
        Map<ServerEndpoint, List<java.sql.Connection>> distribution = connectionTracker.getDistribution();
        List<java.sql.Connection> connectionsToInvalidate = distribution.get(endpoint);

        if (connectionsToInvalidate != null && !connectionsToInvalidate.isEmpty()) {
            int invalidatedCount = 0;
            for (java.sql.Connection conn : connectionsToInvalidate) {
                if (conn instanceof org.openjproxy.jdbc.Connection) {
                    org.openjproxy.jdbc.Connection ojpConn = (org.openjproxy.jdbc.Connection) conn;
                    ojpConn.markForceInvalid();
                    try {
                        conn.close();
                        invalidatedCount++;
                        log.debug("Invalidated and closed connection {} for failed server {}",
                                System.identityHashCode(conn), endpoint.getAddress());
                    } catch (Exception e) {
                        log.warn("Failed to close connection {} for failed server {}: {}",
                                System.identityHashCode(conn), endpoint.getAddress(), e.getMessage());
                    }
                }
            }

            log.info("Invalidated {} session(s) and {} connection(s) for failed server {}",
                    sessionsToInvalidate.size(), invalidatedCount, endpoint.getAddress());
        } else {
            log.info("Invalidated {} session(s) for failed server {} (no connections tracked)",
                    sessionsToInvalidate.size(), endpoint.getAddress());
        }
    }

    /**
     * Validates if a server is healthy by attempting a simple connection.
     * Returns true if server is responsive, false otherwise.
     */
    private boolean validateServer(ServerEndpoint endpoint) {
        return healthCheckValidator.validateServer(endpoint);
    }

    /**
     * Provides public access to create a channel and stub for an endpoint.
     * Used by HealthCheckValidator for server validation.
     */
    public ChannelAndStub createChannelAndStubForEndpoint(ServerEndpoint endpoint) {
        return createChannelAndStub(endpoint);
    }

    /**
     * Closes a session by its UUID.
     * Used by HealthCheckValidator to clean up test connections.
     */
    public void closeSession(String sessionUUID) throws SQLException {
        if (sessionUUID == null || sessionUUID.isEmpty()) {
            return;
        }

        // Remove from session map
        ServerEndpoint server = sessionToServerMap.remove(sessionUUID);

        if (server != null) {
            log.debug("Closed session {}, was bound to {}", sessionUUID, server.getAddress());
        }

        // TODO: Actually call close on the server - for now just remove from tracking
    }

    /**
     * Gets the connection tracker for integration with XA data sources.
     * @deprecated Use getSessionTracker() for unified tracking
     */
    @Deprecated
    public ConnectionTracker getConnectionTracker() {
        return connectionTracker;
    }

    /**
     * Gets the session tracker for unified session-based load balancing.
     * Replaces ConnectionTracker with lighter-weight session tracking.
     */
    public SessionTracker getSessionTracker() {
        return sessionTracker;
    }

    /**
     * Gets the health check configuration.
     */
    public HealthCheckConfig getHealthCheckConfig() {
        return healthCheckConfig;
    }

    /**
     * Connects to all servers to ensure datasource information is available on all nodes.
     * Used for both XA and non-XA connections in unified mode.
     *
     * Each session is bound directly to the ServerEndpoint object that was just connected to.
     * Only sessions with UUIDs (actual server sessions) are bound.
     */
    private SessionInfo connectToAllServers(ConnectionDetails connectionDetails) throws SQLException {
        SessionInfo primarySessionInfo = null;
        SQLException lastException = null;
        int successfulConnections = 0;
        List<ServerEndpoint> connectedServers = new ArrayList<>();
        List<SessionInfo> allSessionInfos = new ArrayList<>(); // Track all successful sessions
        boolean isXA = connectionDetails.getIsXA();

        // Try to connect to all servers
        for (ServerEndpoint server : serverEndpoints) {
            if (!server.isHealthy()) {
                // Attempt to recover unhealthy servers if enough time has passed
                long currentTime = System.nanoTime();
                if ((currentTime - server.getLastFailureTime()) / 1_000_000L > retryDelayMs) {
                    log.info("Attempting to recover unhealthy server {} during connect()", server.getAddress());
                    try {
                        createChannelAndStub(server);
                        log.info("Re-created gRPC channel for server {} during connect(), will mark healthy only after successful connect()", server.getAddress());
                        // Do NOT mark server healthy here - the server's DB connection pool may not be
                        // ready yet even though the gRPC port is listening (root cause of excess failures:
                        // premature health marking causes all threads to pile onto the recovering server
                        // before any DB slot is available, producing far more failures than pool size).
                        // The server will be marked healthy at line 520 after connect() succeeds.
                        // Continue to attempt connection below
                    } catch (Exception e) {
                        server.setLastFailureTime(currentTime);
                        log.debug("Server {} recovery attempt failed during connect(): {}",
                                server.getAddress(), e.getMessage());
                        continue;  // Skip this server
                    }
                } else {
                    log.debug("Skipping unhealthy server: {} (waiting for retry delay)", server.getAddress());
                    continue;
                }
            }

            try {
                ChannelAndStub channelAndStub = channelMap.get(server);
                if (channelAndStub == null) {
                    channelAndStub = createChannelAndStub(server);
                }

                log.info("Connecting to server {} ({} connection)", server.getAddress(), isXA ? "XA" : "non-XA");
                SessionInfo sessionInfo = channelAndStub.blockingStub.connect(connectionDetails);

                // Mark server as healthy
                server.setHealthy(true);
                server.setLastFailureTime(0);

                // Bind session directly to the ServerEndpoint we just connected to
                // Only bind sessions with UUIDs (actual server sessions, not lazy allocation)
                if (sessionInfo.getSessionUUID() != null && !sessionInfo.getSessionUUID().isEmpty()) {
                    // Direct binding to ServerEndpoint object - no string matching needed
                    sessionToServerMap.put(sessionInfo.getSessionUUID(), server);
                    sessionTracker.registerSession(sessionInfo.getSessionUUID(), server);

                    String connectedServerAddress = server.getHost() + ":" + server.getPort();
                    log.info("Session {} bound to server {} (direct binding to ServerEndpoint)",
                            sessionInfo.getSessionUUID(), connectedServerAddress);
                } else {
                    // No UUID = lazy allocation = no session on server yet = don't bind
                    log.debug("No sessionUUID from server {} - skipping binding (lazy allocation)",
                            server.getAddress());
                }

                log.info("Successfully connected to server {} ({} connection)",
                        server.getAddress(), isXA ? "XA" : "non-XA");
                successfulConnections++;

                // Track that this server received a connect() call
                connectedServers.add(server);

                // Track all successful session infos
                allSessionInfos.add(sessionInfo);

                // Use the first successful connection as the primary
                if (primarySessionInfo == null) {
                    primarySessionInfo = sessionInfo;
                }

            } catch (StatusRuntimeException e) {
                // isConnectionLevelError() is used instead of relying on GrpcExceptionHandler.handle()
                // throwing/not-throwing, since handle() now also converts connection-level codes
                // (UNAVAILABLE, DEADLINE_EXCEEDED) into a checked SQLTransientConnectionException.
                boolean isConnectionLevel = GrpcExceptionHandler.isConnectionLevelError(e);
                try {
                    GrpcExceptionHandler.handle(e);
                    lastException = new SQLException("gRPC call failed: " + e.getMessage(), e);
                } catch (SQLException sqlEx) {
                    lastException = sqlEx; // SQL metadata present: upstream DB error, not OJP proxy failure
                }
                if (isConnectionLevel) {
                    // Only mark server unhealthy for genuine connectivity failures (no SQL metadata)
                    handleServerFailure(server, e);
                } else {
                    log.debug("Not marking server {} unhealthy: database-level error during connect ({})",
                            server.getAddress(), lastException.getMessage());
                }

                log.warn("Connection failed to server {}: {}",
                        server.getAddress(), lastException.getMessage());
            }
        }

        if (primarySessionInfo == null) {
            throw new SQLException("Failed to connect to any server. " +
                    "Last error: " + (lastException != null ? lastException.getMessage() : "No healthy servers available"));
        }

        // CRITICAL FIX: Check if primary session was invalidated by health checker during connect
        // This can happen if health checker runs between binding the session and returning from connect()
        String primarySessionUUID = primarySessionInfo.getSessionUUID();
        if (primarySessionUUID != null && !primarySessionUUID.isEmpty()) {
            if (!sessionToServerMap.containsKey(primarySessionUUID)) {
                log.warn("[RACE-FIX] Primary session {} was invalidated during connect(), searching for valid session",
                        primarySessionUUID);

                // Find a valid session from the other successful connections
                SessionInfo validSession = null;
                for (SessionInfo si : allSessionInfos) {
                    String uuid = si.getSessionUUID();
                    if (uuid != null && !uuid.isEmpty() && sessionToServerMap.containsKey(uuid)) {
                        validSession = si;
                        log.info("[RACE-FIX] Found valid session {} to use instead", uuid);
                        break;
                    }
                }

                if (validSession != null) {
                    primarySessionInfo = validSession;
                } else {
                    // All sessions were invalidated - this is a rare case but possible
                    throw new SQLException("All sessions were invalidated during connection establishment (servers failed during connect)");
                }
            }
        }

        // Track which servers received connect() for this connection hash
        // This is used during terminateSession() to ensure all servers are cleaned up
        if (primarySessionInfo.getConnHash() != null && !primarySessionInfo.getConnHash().isEmpty()) {
            connHashToServersMap.put(primarySessionInfo.getConnHash(), new ArrayList<>(connectedServers));
            log.info("Tracked {} servers for connection hash {}", connectedServers.size(), primarySessionInfo.getConnHash());
        }

        log.info("Connected to {} out of {} servers ({} connection)",
                successfulConnections, serverEndpoints.size(), isXA ? "XA" : "non-XA");
        return primarySessionInfo;
    }


    /**
     * Gets the appropriate server based on session affinity.
     *
     * If sessionKey is null, returns a round-robin selected server.
     * If sessionKey is not null, returns the server bound to that session.
     *
     * @param sessionKey the session identifier (sessionUUID), or null for round-robin
     * @return the server endpoint to use
     * @throws SQLException if session exists but server is unavailable or not bound
     */
    public ServerEndpoint affinityServer(String sessionKey) throws SQLException {
        if (sessionKey == null || sessionKey.isEmpty()) {
            // No session identifier, use round-robin
            log.debug("DIAGNOSTIC: affinityServer called with NULL or EMPTY sessionKey - routing via round-robin. " +
                    "This may cause 'Connection not found' errors if queries reach wrong server. " +
                    "SessionKey value: '{}', isEmpty: {}, isNull: {}",
                    sessionKey,
                    sessionKey != null && sessionKey.isEmpty(),
                    sessionKey == null);
            ServerEndpoint selectedServer = selectHealthyServer();
            if (selectedServer == null) {
                throw new SQLException("No healthy servers available (checked " + serverEndpoints.size() + " servers)");
            }
            return selectedServer;
        }

        log.debug("Looking up server for session: {}", sessionKey);
        ServerEndpoint sessionServer = sessionToServerMap.get(sessionKey);

        log.debug("=== affinityServer lookup: sessionKey={}, found server={}, total sessions in map={} ===",
                sessionKey,
                sessionServer != null ? sessionServer.getAddress() : "NOT_FOUND",
                sessionToServerMap.size());

        // Log session distribution for debugging
        if (sessionServer != null && log.isDebugEnabled()) {
            Map<String, Long> serverDistribution = sessionToServerMap.values().stream()
                    .collect(java.util.stream.Collectors.groupingBy(
                            ServerEndpoint::getAddress,
                            java.util.stream.Collectors.counting()));
            log.debug("Session distribution across servers: {}", serverDistribution);
        }

        // Session must be bound - throw exception if not found
        if (sessionServer == null) {
            log.error("Session {} has no associated server. Available sessions: {}. This indicates the session binding was lost.",
                    sessionKey, sessionToServerMap.keySet());
            throw new SQLException("Session " + sessionKey +
                    " has no associated server. Session may have expired or server may be unavailable. " +
                    "Available bound sessions: " + sessionToServerMap.keySet());
        }

        log.debug("Session {} is bound to server {}", sessionKey, sessionServer.getAddress());

        if (!sessionServer.isHealthy()) {
            // Remove from map and throw exception - do NOT fall back to round-robin
            sessionToServerMap.remove(sessionKey);
            throw new SQLException("Session " + sessionKey +
                    " is bound to server " + sessionServer.getAddress() +
                    " which is currently unavailable. Cannot continue with this session.");
        }

        return sessionServer;
    }


    /**
     * Gets the channel and stub for a specific server.
     */
    public ChannelAndStub getChannelAndStub(ServerEndpoint endpoint) {
        ChannelAndStub channelAndStub = channelMap.get(endpoint);
        if (channelAndStub == null) {
            try {
                channelAndStub = createChannelAndStub(endpoint);
            } catch (Exception e) {
                log.error("Failed to create channel for server {}: {}", endpoint.getAddress(), e.getMessage());
                return null;
            }
        }
        return channelAndStub;
    }

    private ServerEndpoint selectHealthyServer() {
        List<ServerEndpoint> healthyServers = serverEndpoints.stream()
                .filter(ServerEndpoint::isHealthy)
                .collect(Collectors.toList());

        // Only attempt recovery if NO servers are healthy (last resort)
        // Time-based health checks via tryTriggerHealthCheck() handle recovery for normal cases
        if (healthyServers.isEmpty()) {
            log.warn("No healthy servers available, attempting recovery as last resort");
            attemptServerRecovery();

            // Re-check healthy servers after recovery attempt
            healthyServers = serverEndpoints.stream()
                    .filter(ServerEndpoint::isHealthy)
                    .collect(Collectors.toList());
        }

        if (healthyServers.isEmpty()) {
            log.error("No healthy servers available after recovery attempt");
            return null;
        }

        // Choose selection strategy based on configuration
        if (healthCheckConfig.isLoadAwareSelectionEnabled()) {
            return selectByLeastConnections(healthyServers);
        } else {
            return selectByRoundRobin(healthyServers);
        }
    }

    /**
     * Selects the healthy server with the fewest active sessions.
     * This provides automatic load balancing by directing new connections
     * to the least-loaded server.
     *
     * Uses SessionTracker for accurate session counts across both XA and non-XA connections.
     * When all servers have equal session counts, falls back to round-robin selection.
     *
     * @param healthyServers List of healthy servers to choose from
     * @return The server with the lowest session count
     */
    private ServerEndpoint selectByLeastConnections(List<ServerEndpoint> healthyServers) {
        if (healthyServers.isEmpty()) {
            return null;
        }

        // Get current session counts per server from SessionTracker
        Map<ServerEndpoint, Integer> sessionCounts = sessionTracker.getSessionCounts();

        // Check if all servers have the same count
        boolean allEqual = true;
        Integer firstCount = null;
        for (ServerEndpoint server : healthyServers) {
            int count = sessionCounts.getOrDefault(server, 0);
            if (firstCount == null) {
                firstCount = count;
            } else if (firstCount != count) {
                allEqual = false;
                break;
            }
        }

        // If all counts are equal, use true round-robin instead of load-aware
        if (allEqual) {
            log.debug("All servers have equal session load ({}), using round-robin selection", firstCount);
            return selectByRoundRobin(healthyServers);
        }

        // Find server with minimum sessions
        ServerEndpoint selected = healthyServers.stream()
                .min((s1, s2) -> {
                    int count1 = sessionCounts.getOrDefault(s1, 0);
                    int count2 = sessionCounts.getOrDefault(s2, 0);
                    return Integer.compare(count1, count2);
                })
                .orElse(healthyServers.get(0));

        int selectedCount = sessionCounts.getOrDefault(selected, 0);
        log.debug("Selected server {} with {} active sessions (load-aware)",
                selected.getAddress(), selectedCount);

        return selected;
    }

    /**
     * Selects a healthy server using round-robin strategy.
     * This is the legacy selection method that cycles through servers
     * without considering their current load.
     *
     * @param healthyServers List of healthy servers to choose from
     * @return The next server in round-robin order
     */
    private ServerEndpoint selectByRoundRobin(List<ServerEndpoint> healthyServers) {
        if (healthyServers.isEmpty()) {
            return null;
        }

        int index = Math.abs(roundRobinCounter.getAndIncrement()) % healthyServers.size();
        ServerEndpoint selected = healthyServers.get(index);

        log.debug("Selected server {} for request (round-robin)", selected.getAddress());
        return selected;
    }

    /**
     * Handles server failure by marking it unhealthy and invalidating sessions/connections in XA mode.
     * This method can be called from both internal connection attempts and external callers (e.g., MultinodeStatementService)
     * when they detect connection-level failures.
     *
     * @param endpoint The server endpoint that failed
     * @param exception The exception that caused the failure
     */
    public void handleServerFailure(ServerEndpoint endpoint, Exception exception) {
        // Only mark server unhealthy for connection-level failures
        // Database-level errors (e.g., table not found, syntax errors) should not affect server health
        boolean shouldMarkUnhealthy = isConnectionLevelError(exception);

        if (!shouldMarkUnhealthy) {
            log.debug("Server {} encountered database-level error, not marking unhealthy: {}",
                    endpoint.getAddress(), exception.getMessage());
            return;
        }

        // Capture the previous health state so we can detect a healthy→unhealthy transition.
        boolean wasHealthy = endpoint.isHealthy();
        endpoint.setHealthy(false);
        endpoint.setLastFailureTime(System.nanoTime());

        log.warn("Marked server {} as unhealthy due to connection-level error: {}",
                endpoint.getAddress(), exception.getMessage());

        // XA Mode: Immediately invalidate all sessions and connections for the failed server
        // This prevents attempts to use stale sessions after server failure
        if (xaConnectionRedistributor != null) {
            invalidateSessionsAndConnectionsForFailedServer(endpoint);
        }

        // Phase 2: Notify listeners that server became unhealthy
        notifyServerUnhealthy(endpoint, exception);

        // Push the updated cluster health to surviving servers so they can expand their
        // connection pools immediately.
        //
        // Without this, pushClusterHealthToAllHealthyServers() is only called by the
        // periodic health checker when it *itself* detects a server going unhealthy.
        // But when a query thread detects the failure first (via handleServerFailure),
        // the server is already marked unhealthy before the health checker runs.
        // The health checker's proactive loop skips already-unhealthy servers, so the
        // push never happens and surviving servers never resize their pools.
        //
        // Only push on the genuine healthy→unhealthy transition to avoid redundant
        // pushes when handleServerFailure is called multiple times for the same server.
        // Submit to the background scheduler to avoid blocking the calling query thread.
        if (wasHealthy && healthCheckScheduler != null) {
            healthCheckScheduler.submit(() -> {
                try {
                    pushClusterHealthToAllHealthyServers();
                } catch (Exception e) {
                    log.warn("Failed to push cluster health after server failure detection: {}", e.getMessage());
                }
            });
        }

        // Remove the failed channel from the map and shut it down gracefully
        // Using shutdown() instead of shutdownNow() allows in-flight operations to complete
        ChannelAndStub channelAndStub = channelMap.remove(endpoint);
        if (channelAndStub != null) {
            log.debug("Removed channel for {} from map, initiating graceful shutdown",
                    endpoint.getAddress());
            try {
                channelAndStub.channel.shutdown();
                log.debug("Initiated graceful shutdown for channel {}", endpoint.getAddress());
            } catch (Exception e) {
                log.warn("Error shutting down channel for {}: {}", endpoint.getAddress(), e.getMessage());
            }
        }
    }

    /**
     * Determines if an exception represents a connection-level error that should mark a server unhealthy.
     * Connection-level errors include:
     * - UNAVAILABLE: Server not reachable
     * - DEADLINE_EXCEEDED: Request timeout
     * - UNKNOWN: Connection-related unknown errors
     *
     * Database-level errors (e.g., table not found, syntax errors) do not mark servers unhealthy.
     * SQL exceptions from the server are sent with {@code Status.INTERNAL} and are NOT connection-level errors.
     * Pool exhaustion errors do NOT mark servers unhealthy - they indicate resource limits, not connectivity issues.
     */
    public boolean isConnectionLevelError(Exception exception) {
        return GrpcExceptionHandler.isConnectionLevelError(exception);
    }

    private void attemptServerRecovery() {
        long currentTime = System.nanoTime();

        for (ServerEndpoint endpoint : serverEndpoints) {
            if (!endpoint.isHealthy() &&
                (currentTime - endpoint.getLastFailureTime()) / 1_000_000L > retryDelayMs) {

                try {
                    log.debug("Attempting to recover server {}", endpoint.getAddress());
                    createChannelAndStub(endpoint);

                    endpoint.setHealthy(true);
                    endpoint.setLastFailureTime(0);
                    log.info("Successfully recovered server {}", endpoint.getAddress());

                    // Phase 2: Notify listeners that server recovered
                    notifyServerRecovered(endpoint);
                } catch (Exception e) {
                    endpoint.setLastFailureTime(currentTime);
                    log.debug("Server {} recovery attempt failed: {}",
                            endpoint.getAddress(), e.getMessage());
                }
            }
        }
    }

    /**
     * Terminates a session and removes its server association.
     * Also cleans up the connection hash mapping used to track which servers received connect() calls.
     */
    public void terminateSession(SessionInfo sessionInfo) {
        if (sessionInfo != null) {
            // Remove session binding if sessionUUID is present
            if (sessionInfo.getSessionUUID() != null && !sessionInfo.getSessionUUID().isEmpty()) {
                unbindSession(sessionInfo.getSessionUUID());
                log.debug("Removed session {} from server association map", sessionInfo.getSessionUUID());
            }

            // Remove connection hash mapping if present
            if (sessionInfo.getConnHash() != null && !sessionInfo.getConnHash().isEmpty()) {
                connHashToServersMap.remove(sessionInfo.getConnHash());
                log.debug("Removed connection hash {} from server tracking map", sessionInfo.getConnHash());
            }
        }
    }

    /**
     * Binds a session UUID to a target server endpoint (host:port format).
     * This is used for session stickiness - subsequent operations with this sessionUUID
     * will be routed to the bound server.
     *
     * Registers the session with both sessionToServerMap (for backward compatibility)
     * and SessionTracker (for unified load balancing).
     *
     * @param sessionUUID The session identifier
     * @param targetServer The target server in host:port format
     */
    public void bindSession(String sessionUUID, String targetServer) {
        if (sessionUUID == null || sessionUUID.isEmpty()) {
            log.warn("Attempted to bind session with null or empty sessionUUID");
            return;
        }

        if (targetServer == null || targetServer.isEmpty()) {
            log.warn("Attempted to bind session {} with null or empty targetServer", sessionUUID);
            return;
        }

        // Find the matching ServerEndpoint for this targetServer string
        ServerEndpoint matchingEndpoint = null;
        for (ServerEndpoint endpoint : serverEndpoints) {
            String endpointAddress = endpoint.getHost() + ":" + endpoint.getPort();
            if (endpointAddress.equals(targetServer)) {
                matchingEndpoint = endpoint;
                break;
            }
        }

        if (matchingEndpoint != null) {
            ServerEndpoint previous = sessionToServerMap.put(sessionUUID, matchingEndpoint);

            // Register with SessionTracker for unified load balancing
            sessionTracker.registerSession(sessionUUID, matchingEndpoint);

            if (previous == null) {
                log.info("Bound session {} to target server {}", sessionUUID, targetServer);
            } else {
                log.info("Rebound session {} from {} to target server {}",
                        sessionUUID, previous.getAddress(), targetServer);
            }
        } else {
            log.warn("Could not find matching endpoint for targetServer: {}. Available endpoints: {}",
                    targetServer,
                    serverEndpoints.stream()
                            .map(e -> e.getHost() + ":" + e.getPort())
                            .collect(java.util.stream.Collectors.joining(", ")));
        }
    }

    /**
     * Gets the bound server for a given session UUID.
     *
     * @param sessionUUID The session identifier
     * @return The server endpoint string (host:port) if bound, null otherwise
     */
    public String getBoundTargetServer(String sessionUUID) {
        if (sessionUUID == null || sessionUUID.isEmpty()) {
            return null;
        }

        ServerEndpoint endpoint = sessionToServerMap.get(sessionUUID);
        if (endpoint != null) {
            return endpoint.getHost() + ":" + endpoint.getPort();
        }

        return null;
    }

    /**
     * Removes the session binding for a given session UUID.
     * Unregisters from both sessionToServerMap and SessionTracker.
     *
     * @param sessionUUID The session identifier
     */
    public void unbindSession(String sessionUUID) {
        if (sessionUUID != null && !sessionUUID.isEmpty()) {
            ServerEndpoint removed = sessionToServerMap.remove(sessionUUID);

            // Unregister from SessionTracker
            sessionTracker.unregisterSession(sessionUUID);

            if (removed != null) {
                log.debug("Unbound session {} from server {}", sessionUUID, removed.getAddress());
            }
        }
    }

    /**
     * Gets the list of servers that received connect() for a given connection hash.
     * This is used during terminateSession() to ensure all servers that received connect()
     * also receive terminateSession() so they can clean up their resources properly.
     *
     * @param connHash The connection hash
     * @return List of servers that received connect(), or null if not tracked
     */
    public List<ServerEndpoint> getServersForConnHash(String connHash) {
        if (connHash == null || connHash.isEmpty()) {
            return null;
        }
        List<ServerEndpoint> servers = connHashToServersMap.get(connHash);
        return servers != null ? new ArrayList<>(servers) : null;
    }

    /**
     * Gets the list of configured server endpoints.
     */
    public List<ServerEndpoint> getServerEndpoints() {
        return List.copyOf(serverEndpoints);
    }

    /**
     * Gets the configured number of retry attempts.
     */
    public int getRetryAttempts() {
        return retryAttempts;
    }

    /**
     * Gets the configured retry delay in milliseconds.
     */
    public long getRetryDelayMs() {
        return retryDelayMs;
    }

    /**
     * Generates the cluster health status string.
     * Format: "host1:port1(UP);host2:port2(DOWN);host3:port3(UP)"
     *
     * @return Cluster health status string
     */
    public String generateClusterHealth() {
        return serverEndpoints.stream()
                .map(endpoint -> endpoint.getAddress() + "(" + (endpoint.isHealthy() ? "UP" : "DOWN") + ")")
                .collect(Collectors.joining(";"));
    }

    /**
     * Checks if a session is currently bound to a server.
     * Used to detect race conditions where a session is created but then
     * immediately invalidated by the health checker before it can be used.
     *
     * @param sessionUUID The session UUID to check
     * @return true if the session is bound to a server, false otherwise
     */
    public boolean isSessionBound(String sessionUUID) {
        if (sessionUUID == null || sessionUUID.isEmpty()) {
            return false;
        }
        return sessionToServerMap.containsKey(sessionUUID);
    }

    /**
     * Proactively pushes the current cluster health to all healthy servers via a lightweight
     * {@code connect()} RPC that embeds the current {@code clusterHealth} in
     * {@link ConnectionDetails}.
     *
     * <p>This is called whenever the health checker detects a server becoming unhealthy or
     * recovering.  Without this push, pool resizing on healthy servers depends entirely on
     * active SQL operations carrying the {@code clusterHealth} field in {@link SessionInfo}.
     * If the application has no in-flight queries at the moment of the topology change (e.g.
     * the integration-test threads have all finished), the surviving server(s) would never
     * receive the signal and their pools would remain sized for the old cluster configuration
     * indefinitely.</p>
     *
     * <p>The server-side {@code ConnectAction} handles a non-empty {@code clusterHealth} in
     * {@code ConnectionDetails} even when the pool already exists, triggering
     * {@code ProcessClusterHealthAction} so the pool resizes without requiring any SQL
     * activity.</p>
     */
    private void pushClusterHealthToAllHealthyServers() {
        if (connectionDetailsByConnHash.isEmpty()) {
            log.debug("No stored connection details; skipping proactive cluster health push");
            return;
        }

        String currentClusterHealth = generateClusterHealth();
        List<ServerEndpoint> healthyServers = serverEndpoints.stream()
                .filter(ServerEndpoint::isHealthy)
                .collect(Collectors.toList());

        if (healthyServers.isEmpty()) {
            log.debug("No healthy servers to push cluster health to");
            return;
        }

        log.info("Proactively pushing cluster health '{}' to {} healthy server(s)",
                currentClusterHealth, healthyServers.size());

        for (ServerEndpoint server : healthyServers) {
            ChannelAndStub channelAndStub = channelMap.get(server);
            if (channelAndStub == null) {
                log.debug("No channel for server {}; skipping cluster health push", server.getAddress());
                continue;
            }

            for (Map.Entry<String, ConnectionDetails> entry : connectionDetailsByConnHash.entrySet()) {
                String connHash = entry.getKey();
                ConnectionDetails storedDetails = entry.getValue();
                ConnectionDetails updatedDetails = ConnectionDetails.newBuilder(storedDetails)
                        .setClusterHealth(currentClusterHealth)
                        .build();

                try {
                    channelAndStub.blockingStub
                            .withDeadlineAfter(healthCheckConfig.getHealthCheckTimeoutMs(), TimeUnit.MILLISECONDS)
                            .connect(updatedDetails);
                    log.info("Pushed cluster health '{}' to server {} for connHash {}",
                            currentClusterHealth, server.getAddress(), connHash);
                } catch (Exception e) {
                    log.warn("Failed to push cluster health to server {} for connHash {}: {}",
                            server.getAddress(), connHash, e.getMessage());
                }
            }
        }
    }

    /**
     * Shuts down all connections.
     */
    public void shutdown() {
        log.info("Shutting down MultinodeConnectionManager");

        // Shutdown health checker scheduler
        if (healthCheckScheduler != null) {
            healthCheckScheduler.shutdown();
            try {
                if (!healthCheckScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    healthCheckScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                healthCheckScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        for (ChannelAndStub channelAndStub : channelMap.values()) {
            try {
                channelAndStub.channel.shutdown();
            } catch (Exception e) {
                log.warn("Error shutting down channel: {}", e.getMessage());
            }
        }

        channelMap.clear();
        sessionToServerMap.clear();
    }

    /**
     * Phase 2: Adds a server health listener.
     *
     * @param listener The listener to add
     */
    public void addHealthListener(ServerHealthListener listener) {
        if (listener != null && !healthListeners.contains(listener)) {
            healthListeners.add(listener);
            log.debug("Added server health listener: {}", listener.getClass().getSimpleName());
        }
    }

    /**
     * Phase 2: Removes a server health listener.
     *
     * @param listener The listener to remove
     */
    public void removeHealthListener(ServerHealthListener listener) {
        if (listener != null) {
            healthListeners.remove(listener);
            log.debug("Removed server health listener: {}", listener.getClass().getSimpleName());
        }
    }

    /**
     * Sets the XA connection redistributor for rebalancing connections on server recovery.
     *
     * @param redistributor The redistributor to use
     */
    public void setXaConnectionRedistributor(XAConnectionRedistributor redistributor) {
        this.xaConnectionRedistributor = redistributor;
        log.info("XA connection redistributor registered");
    }

    /**
     * Phase 2: Notifies all listeners that a server became unhealthy.
     *
     * @param endpoint The server endpoint that became unhealthy
     * @param exception The exception that caused the failure
     */
    private void notifyServerUnhealthy(ServerEndpoint endpoint, Exception exception) {
        for (ServerHealthListener listener : healthListeners) {
            if (listener == null) {
                continue; // Skip null listeners
            }
            try {
                listener.onServerUnhealthy(endpoint, exception);
            } catch (Exception e) {
                log.error("Error notifying listener of unhealthy server {}: {}",
                        endpoint.getAddress(), e.getMessage(), e);
            }
        }
    }

    /**
     * Phase 2: Notifies all listeners that a server recovered.
     * Also triggers XA connection redistribution if configured.
     *
     * @param endpoint The server endpoint that recovered
     */
    private void notifyServerRecovered(ServerEndpoint endpoint) {
        for (ServerHealthListener listener : healthListeners) {
            if (listener == null) {
                continue; // Skip null listeners
            }
            try {
                listener.onServerRecovered(endpoint);
            } catch (Exception e) {
                log.error("Error notifying listener of recovered server {}: {}",
                        endpoint.getAddress(), e.getMessage(), e);
            }
        }

        // Trigger XA connection redistribution if configured
        if (xaConnectionRedistributor != null && healthCheckConfig.isRedistributionEnabled()) {
            List<ServerEndpoint> allHealthyServers = serverEndpoints.stream()
                    .filter(ServerEndpoint::isHealthy)
                    .collect(Collectors.toList());

            try {
                xaConnectionRedistributor.rebalance(List.of(endpoint), allHealthyServers);
            } catch (Exception e) {
                log.error("Error during XA connection redistribution: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * Extracts the datasource name from ConnectionDetails properties.
     * Returns the value of "ojp.datasource.name" property, or "default" if not found.
     *
     * @param connectionDetails The ConnectionDetails to extract from
     * @return The datasource name, or "default" if not specified
     */
    private String extractDataSourceNameFromConnectionDetails(ConnectionDetails connectionDetails) {
        if (connectionDetails == null || connectionDetails.getPropertiesList().isEmpty()) {
            return "default";
        }

        for (PropertyEntry prop : connectionDetails.getPropertiesList()) {
            if (CommonConstants.DATASOURCE_NAME_PROPERTY.equals(prop.getKey())) {
                return prop.getStringValue();
            }
        }

        return "default";
    }

    /**
     * Rebuilds ConnectionDetails with properties for a specific datasource.
     * Used in XA mode to ensure the selected server receives properties for its datasource.
     *
     * @param originalDetails The original ConnectionDetails
     * @param dataSourceName The datasource name to load properties for
     * @return New ConnectionDetails with updated properties
     */
    private ConnectionDetails rebuildConnectionDetailsWithDataSource(
            ConnectionDetails originalDetails, String dataSourceName) {

        log.info("Reloading properties for datasource: {}", dataSourceName);

        // Load properties for the specified datasource
        Properties dsProperties = DatasourcePropertiesLoader.loadOjpPropertiesForDataSource(dataSourceName);

        // Rebuild ConnectionDetails with new properties
        ConnectionDetails.Builder builder = ConnectionDetails.newBuilder(originalDetails)
                .clearProperties();

        if (dsProperties != null && !dsProperties.isEmpty()) {
            // Convert Properties to Map<String, Object>
            Map<String, Object> propertiesMap = new HashMap<>();
            for (String key : dsProperties.stringPropertyNames()) {
                propertiesMap.put(key, dsProperties.getProperty(key));
            }
            builder.addAllProperties(ProtoConverter.propertiesToProto(propertiesMap));
            log.info("Rebuilt ConnectionDetails with {} properties for datasource: {}",
                    propertiesMap.size(), dataSourceName);
        } else {
            log.info("No properties found for datasource: {}, using defaults", dataSourceName);
        }

        return builder.build();
    }

    /**
     * Inner class to hold channel and stubs together.
     */
    public static class ChannelAndStub {
        public final ManagedChannel channel;
        public final StatementServiceGrpc.StatementServiceBlockingStub blockingStub;
        public final StatementServiceGrpc.StatementServiceStub asyncStub;

        public ChannelAndStub(ManagedChannel channel,
                             StatementServiceGrpc.StatementServiceBlockingStub blockingStub,
                             StatementServiceGrpc.StatementServiceStub asyncStub) {
            this.channel = channel;
            this.blockingStub = blockingStub;
            this.asyncStub = asyncStub;
        }
    }
}
