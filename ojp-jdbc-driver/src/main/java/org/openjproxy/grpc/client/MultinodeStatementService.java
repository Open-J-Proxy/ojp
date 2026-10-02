package org.openjproxy.grpc.client;

import com.openjproxy.grpc.CallResourceRequest;
import com.openjproxy.grpc.CallResourceResponse;
import com.openjproxy.grpc.ConnectionDetails;
import com.openjproxy.grpc.LobDataBlock;
import com.openjproxy.grpc.LobReference;
import com.openjproxy.grpc.OpResult;
import com.openjproxy.grpc.SessionInfo;
import com.openjproxy.grpc.StatementServiceGrpc;
import io.grpc.StatusRuntimeException;
import org.openjproxy.grpc.dto.Parameter;
import org.openjproxy.jdbc.Connection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Multinode implementation of StatementService that provides:
 * - Round-robin load balancing across multiple OJP servers
 * - Session stickiness (once a session is bound to a server, all requests for that session go to the same server)
 * - Automatic failover on connection-level errors
 * - Thread-safe concurrent request handling
 *
 * This class delegates to StatementServiceGrpcClient instances, one per server endpoint.
 */
public class MultinodeStatementService implements StatementService {

    private static final Logger log = LoggerFactory.getLogger(MultinodeStatementService.class);

    private final MultinodeConnectionManager connectionManager;
    private final Map<ServerEndpoint, StatementServiceGrpcClient> clientMap;
    private final String originalUrl;

    /**
     * Creates a new MultinodeStatementService.
     *
     * @param connectionManager The connection manager to use for server selection and session tracking
     * @param originalUrl The original multinode URL (for logging and debugging)
     */
    public MultinodeStatementService(MultinodeConnectionManager connectionManager, String originalUrl) {
        this.connectionManager = connectionManager;
        this.clientMap = new ConcurrentHashMap<>();
        this.originalUrl = originalUrl;

        log.info("MultinodeStatementService initialized with {} servers from URL: {}",
                connectionManager.getServerEndpoints().size(), originalUrl);
    }

    /**
     * Gets or creates a StatementServiceGrpcClient for a specific server endpoint.
     * The client uses the same gRPC channel and stubs as the MultinodeConnectionManager
     * to ensure session continuity.
     *
     * This method checks if the cached client is still using the current channel/stubs
     * from the connection manager. If not (e.g., after server recovery), it creates
     * a new client with the updated stubs.
     */
    private StatementServiceGrpcClient getClient(ServerEndpoint endpoint) {
        // Get the current channel and stubs from the connection manager
        MultinodeConnectionManager.ChannelAndStub currentChannelAndStub =
                connectionManager.getChannelAndStub(endpoint);

        if (currentChannelAndStub == null) {
            log.error("Unable to get channel and stub for endpoint: {}", endpoint.getAddress());
            throw new RuntimeException("Unable to initialize client for endpoint: " + endpoint.getAddress());
        }

        // Check if we have a cached client
        StatementServiceGrpcClient cachedClient = clientMap.get(endpoint);

        if (cachedClient != null) {
            try {
                // Check if the cached client is still using the current stubs
                java.lang.reflect.Field blockingStubField = StatementServiceGrpcClient.class
                    .getDeclaredField("statemetServiceBlockingStub");
                blockingStubField.setAccessible(true);
                StatementServiceGrpc.StatementServiceBlockingStub cachedStub =
                    (StatementServiceGrpc.StatementServiceBlockingStub) blockingStubField.get(cachedClient);

                // If the stub is the same as the current one, reuse the client
                if (cachedStub == currentChannelAndStub.blockingStub) {
                    return cachedClient;
                }

                // The stubs have changed (server was recovered), remove the old client
                log.info("Stubs changed for endpoint {} (likely after recovery), creating new client",
                        endpoint.getAddress());
                clientMap.remove(endpoint);

            } catch (Exception e) {
                log.warn("Error checking cached client stubs for {}: {}", endpoint.getAddress(), e.getMessage());
                // If we can't check, remove the cached client to be safe
                clientMap.remove(endpoint);
            }
        }

        // Create a new client
        log.info("Creating new StatementServiceGrpcClient for endpoint: {}", endpoint.getAddress());

        log.info("Got channel and stub for endpoint {}: blockingStub={}, asyncStub={}",
            endpoint.getAddress(),
            System.identityHashCode(currentChannelAndStub.blockingStub),
            System.identityHashCode(currentChannelAndStub.asyncStub));

        // Create a client and inject the connection manager's stubs into it
        // This ensures all operations use the same gRPC connection where the session was created
        StatementServiceGrpcClient client = new StatementServiceGrpcClient();

        try {
            // Use reflection to set the stubs to the connection manager's stubs
            // This is necessary because StatementServiceGrpcClient doesn't have a constructor
            // that accepts stubs, and we need to use the same channel for session continuity
            java.lang.reflect.Field blockingStubField = StatementServiceGrpcClient.class
                .getDeclaredField("statemetServiceBlockingStub");
            blockingStubField.setAccessible(true);
            blockingStubField.set(client, currentChannelAndStub.blockingStub);

            java.lang.reflect.Field asyncStubField = StatementServiceGrpcClient.class
                .getDeclaredField("statemetServiceStub");
            asyncStubField.setAccessible(true);
            asyncStubField.set(client, currentChannelAndStub.asyncStub);

            log.info("Initialized StatementServiceGrpcClient with connection manager's stubs for endpoint: {}",
                endpoint.getAddress());
        } catch (Exception e) {
            log.error("Failed to initialize client for endpoint {}: {}", endpoint.getAddress(), e.getMessage(), e);
            throw new RuntimeException("Failed to initialize client for endpoint: " + endpoint.getAddress(), e);
        }

        // Cache the new client
        clientMap.put(endpoint, client);

        return client;
    }

    /**
     * Enhances SessionInfo with current cluster health status.
     * Creates a new SessionInfo with the clusterHealth field populated based on
     * the current health status of all servers in the cluster.
     *
     * @param sessionInfo The original session info
     * @return A new SessionInfo with cluster health populated
     */
    private SessionInfo withClusterHealth(SessionInfo sessionInfo) {
        if (sessionInfo == null) {
            return null;
        }

        String clusterHealth = connectionManager.generateClusterHealth();

        log.debug("[XA-REBALANCE] withClusterHealth called: connHash={}, isXA={}, original clusterHealth={}, new clusterHealth={}",
                sessionInfo.getConnHash(), sessionInfo.getIsXA(), sessionInfo.getClusterHealth(), clusterHealth);

        SessionInfo enhanced = SessionInfo.newBuilder(sessionInfo)
                .setClusterHealth(clusterHealth)
                .build();

        return enhanced;
    }

    /**
     * Checks if a session was created (sessionUUID went from empty to non-empty) and binds it to the server.
     * Also ensures existing sessions remain bound to the correct server.
     * This is called after operations that may create a session (e.g., startTransaction).
     *
     * @param requestSessionInfo The SessionInfo sent in the request
     * @param responseSessionInfo The SessionInfo received in the response
     * @param server The server that handled the request
     */
    private void checkAndBindSession(SessionInfo requestSessionInfo, SessionInfo responseSessionInfo, ServerEndpoint server) {
        // Check if session was created in this call (request had no UUID, response has UUID)
        boolean requestHadNoSession = requestSessionInfo == null ||
                                      requestSessionInfo.getSessionUUID() == null ||
                                      requestSessionInfo.getSessionUUID().isEmpty();
        boolean responseHasSession = responseSessionInfo != null &&
                                      responseSessionInfo.getSessionUUID() != null &&
                                      !responseSessionInfo.getSessionUUID().isEmpty();

        if (responseHasSession) {
            String sessionUUID = responseSessionInfo.getSessionUUID();
            String targetServer = responseSessionInfo.getTargetServer();

            // Check if session is already bound
            String currentBinding = connectionManager.getBoundTargetServer(sessionUUID);

            if (currentBinding == null) {
                // Session not bound yet - bind it now
                if (targetServer != null && !targetServer.isEmpty()) {
                    connectionManager.bindSession(sessionUUID, targetServer);
                    log.info("Session {} bound to target server {} (was unbound)", sessionUUID, targetServer);
                } else {
                    // Fallback: use server address if targetServer not provided
                    String serverAddress = server.getHost() + ":" + server.getPort();
                    connectionManager.bindSession(sessionUUID, serverAddress);
                    log.warn("Session {} bound to server {} (no targetServer in response, was unbound)",
                            sessionUUID, serverAddress);
                }
            } else {
                // Session already bound - verify it matches
                String expectedServer = (targetServer != null && !targetServer.isEmpty())
                    ? targetServer
                    : (server.getHost() + ":" + server.getPort());

                if (!currentBinding.equals(expectedServer)) {
                    log.error("Session {} binding mismatch - currently bound to {}, response indicates {}. Re-binding to response server.",
                            sessionUUID, currentBinding, expectedServer);
                    // Re-bind to the server that actually handled the request
                    if (targetServer != null && !targetServer.isEmpty()) {
                        connectionManager.bindSession(sessionUUID, targetServer);
                    } else {
                        connectionManager.bindSession(sessionUUID, expectedServer);
                    }
                } else {
                    log.debug("Session {} binding verified: {}", sessionUUID, currentBinding);
                }
            }
        }
    }

    /**
     * Executes an operation that returns OpResult with session stickiness and binding check.
     * This wrapper handles binding newly-created sessions to servers.
     *
     * <p>If the server returns {@code Status.NOT_FOUND} (pool lost on server restart) and
     * the request has no active session, the driver invalidates its cached connHash,
     * issues a fresh {@code connect()} RPC, and retries the operation once transparently.</p>
     *
     * @param requestSessionInfo The session info for determining which server to use
     * @param operation The operation to execute
     * @return The OpResult result
     * @throws SQLException if the operation fails
     */
    private OpResult executeOpResultWithSessionStickinessAndBinding(SessionInfo requestSessionInfo,
                                                                      ThrowingFunction<StatementServiceGrpcClient, OpResult> operation)
            throws SQLException {
        return retryOnServerDraining(requestSessionInfo,
                () -> executeOpResultWithSessionStickinessAndBindingOnce(requestSessionInfo, operation));
    }

    private OpResult executeOpResultWithSessionStickinessAndBindingOnce(SessionInfo requestSessionInfo,
                                                                          ThrowingFunction<StatementServiceGrpcClient, OpResult> operation)
            throws SQLException {
        // Get the appropriate server based on session binding or round-robin
        String sessionKey = (requestSessionInfo != null && requestSessionInfo.getSessionUUID() != null && !requestSessionInfo.getSessionUUID().isEmpty())
                ? requestSessionInfo.getSessionUUID() : null;
        ServerEndpoint server = connectionManager.affinityServer(sessionKey);

        log.debug("executeOpResultWithSessionStickinessAndBinding: session={}, server={}",
            requestSessionInfo != null ? requestSessionInfo.getSessionUUID() : "null",
            server != null ? server.getAddress() : "null");

        try {
            // Get the channel and stub for the selected server
            MultinodeConnectionManager.ChannelAndStub channelAndStub =
                    connectionManager.getChannelAndStub(server);

            if (channelAndStub == null) {
                throw new SQLException("Unable to get channel for server: " + server.getAddress());
            }

            // Get or create the client for this endpoint
            StatementServiceGrpcClient client = getClient(server);

            // Execute the operation
            OpResult result = operation.apply(client);

            // Check if result contains a session and bind it
            if (result != null && result.hasSession()) {
                SessionInfo responseSessionInfo = result.getSession();
                checkAndBindSession(requestSessionInfo, responseSessionInfo, server);
            }

            return result;

        } catch (StatusRuntimeException e) {
            // Pool-not-found: the server restarted and lost pool state.
            // Reconnect (recreates the pool) and retry the operation once.
            // This is only safe for stateless (no session UUID) calls: if a
            // session was already in progress the transaction is lost and the
            // application must handle that itself.
            if (GrpcExceptionHandler.isPoolNotFoundException(e)
                    && (requestSessionInfo == null || requestSessionInfo.getSessionUUID().isEmpty())) {
                log.warn("NOT_FOUND from server {} – pool lost, reconnecting and retrying", server.getAddress());
                String connHash = requestSessionInfo != null ? requestSessionInfo.getConnHash() : null;
                if (connHash != null) {
                    connectionManager.invalidateConnHash(connHash);
                    connectionManager.reconnectForConnHash(connHash);
                }
                // Retry once after reconnect (let any exception from retry propagate naturally)
                try {
                    StatementServiceGrpcClient retryClient = getClient(server);
                    OpResult result = operation.apply(retryClient);
                    if (result != null && result.hasSession()) {
                        checkAndBindSession(requestSessionInfo, result.getSession(), server);
                    }
                    return result;
                } catch (StatusRuntimeException retryEx) {
                    SQLException sqlEx;
                    try {
                        throw GrpcExceptionHandler.handle(retryEx);
                    } catch (SQLException ex) {
                        sqlEx = ex;
                    }
                    throw sqlEx;
                } catch (SQLException retryEx) {
                    throw retryEx;
                } catch (Exception retryEx) {
                    throw new SQLException("Retry after reconnect failed: " + retryEx.getMessage(), retryEx);
                }
            }

            // Let GrpcExceptionHandler convert the exception
            SQLException sqlEx;
            try {
                throw GrpcExceptionHandler.handle(e);
            } catch (SQLException ex) {
                sqlEx = ex;
            }

            // Only mark server unhealthy for connection-level errors
            if (connectionManager.isConnectionLevelError(e)) {
                log.warn("Connection-level error on server {}: {}", server.getAddress(), sqlEx.getMessage());
                // Notify connection manager to mark server unhealthy and invalidate sessions/connections
                connectionManager.handleServerFailure(server, e);
            } else {
                log.debug("Database-level error on server {}: {}", server.getAddress(), sqlEx.getMessage());
            }

            throw sqlEx;
        } catch (SQLException e) {
            markDrainingIfServerDraining(server, e);
            throw e;
        } catch (Exception e) {
            throw new SQLException("Unexpected error executing operation: " + e.getMessage(), e);
        }
    }

    /**
     * Executes an operation that returns Iterator<OpResult> with session stickiness and binding check.
     * This wrapper handles binding newly-created sessions to servers by checking the first result.
     *
     * <p>If the server returns {@code Status.NOT_FOUND} (pool lost on server restart) and
     * the request has no active session, the driver invalidates its cached connHash,
     * issues a fresh {@code connect()} RPC, and retries the operation once transparently.</p>
     *
     * @param requestSessionInfo The session info for determining which server to use
     * @param operation The operation to execute
     * @return The Iterator<OpResult> result
     * @throws SQLException if the operation fails
     */
    private Iterator<OpResult> executeIteratorWithSessionStickinessAndBinding(SessionInfo requestSessionInfo,
                                                                                ThrowingFunction<StatementServiceGrpcClient, Iterator<OpResult>> operation)
            throws SQLException {
        return retryOnServerDraining(requestSessionInfo,
                () -> executeIteratorWithSessionStickinessAndBindingOnce(requestSessionInfo, operation));
    }

    private Iterator<OpResult> executeIteratorWithSessionStickinessAndBindingOnce(SessionInfo requestSessionInfo,
                                                                                    ThrowingFunction<StatementServiceGrpcClient, Iterator<OpResult>> operation)
            throws SQLException {
        // Get the appropriate server based on session binding or round-robin
        String sessionKey = (requestSessionInfo != null && requestSessionInfo.getSessionUUID() != null && !requestSessionInfo.getSessionUUID().isEmpty())
                ? requestSessionInfo.getSessionUUID() : null;
        ServerEndpoint server = connectionManager.affinityServer(sessionKey);

        log.debug("executeIteratorWithSessionStickinessAndBinding: session={}, server={}",
            requestSessionInfo != null ? requestSessionInfo.getSessionUUID() : "null",
            server != null ? server.getAddress() : "null");

        try {
            // Get the channel and stub for the selected server
            MultinodeConnectionManager.ChannelAndStub channelAndStub =
                    connectionManager.getChannelAndStub(server);

            if (channelAndStub == null) {
                throw new SQLException("Unable to get channel for server: " + server.getAddress());
            }

            // Get or create the client for this endpoint
            StatementServiceGrpcClient client = getClient(server);

            // Execute the operation
            Iterator<OpResult> resultIterator = tryExecuteIteratorWithPoolRecovery(
                    requestSessionInfo, server, client, operation);

            // Wrap the iterator to check and bind session from the first result
            return new Iterator<OpResult>() {
                private boolean firstResultProcessed = false;

                @Override
                public boolean hasNext() {
                    return resultIterator.hasNext();
                }

                @Override
                public OpResult next() {
                    OpResult result = resultIterator.next();

                    // Check and bind session from the first result
                    if (!firstResultProcessed && result != null && result.hasSession()) {
                        SessionInfo responseSessionInfo = result.getSession();
                        try {
                            checkAndBindSession(requestSessionInfo, responseSessionInfo, server);
                        } catch (Exception e) {
                            log.warn("Failed to check/bind session from query result: {}", e.getMessage());
                        }
                        firstResultProcessed = true;
                    }

                    return result;
                }
            };

        } catch (StatusRuntimeException e) {
            // Let GrpcExceptionHandler convert the exception
            SQLException sqlEx;
            try {
                throw GrpcExceptionHandler.handle(e);
            } catch (SQLException ex) {
                sqlEx = ex;
            }

            // Only mark server unhealthy for connection-level errors
            if (connectionManager.isConnectionLevelError(e)) {
                log.warn("Connection-level error on server {}: {}", server.getAddress(), sqlEx.getMessage());
                // Notify connection manager to mark server unhealthy and invalidate sessions/connections
                connectionManager.handleServerFailure(server, e);
            } else {
                log.debug("Database-level error on server {}: {}", server.getAddress(), sqlEx.getMessage());
            }

            throw sqlEx;
        } catch (SQLException e) {
            markDrainingIfServerDraining(server, e);
            throw e;
        } catch (Exception e) {
            throw new SQLException("Unexpected error executing operation: " + e.getMessage(), e);
        }
    }

    /**
     * Attempts to execute a query operation, handling pool-not-found by reconnecting and retrying once.
     */
    private Iterator<OpResult> tryExecuteIteratorWithPoolRecovery(
            SessionInfo requestSessionInfo,
            ServerEndpoint server,
            StatementServiceGrpcClient client,
            ThrowingFunction<StatementServiceGrpcClient, Iterator<OpResult>> operation) throws Exception {
        try {
            Iterator<OpResult> iterator = operation.apply(client);
            if (iterator != null && (requestSessionInfo == null || requestSessionInfo.getSessionUUID().isEmpty())) {
                // Server-streaming errors only surface when the stream is read. For requests
                // without a session, read the first response now so that retryable rejections
                // (pool not found, server draining) are handled here instead of in the ResultSet.
                // hasNext() buffers the element, so the caller still sees the full stream.
                iterator.hasNext();
            }
            return iterator;
        } catch (StatusRuntimeException e) {
            if (GrpcExceptionHandler.isPoolNotFoundException(e)
                    && (requestSessionInfo == null || requestSessionInfo.getSessionUUID().isEmpty())) {
                log.warn("NOT_FOUND from server {} – pool lost, reconnecting and retrying query", server.getAddress());
                String connHash = requestSessionInfo != null ? requestSessionInfo.getConnHash() : null;
                if (connHash != null) {
                    connectionManager.invalidateConnHash(connHash);
                    connectionManager.reconnectForConnHash(connHash);
                }
                return operation.apply(getClient(server));
            }
            throw e;
        }
    }

    /**
     * Executes an operation that returns SessionInfo with session stickiness and binding check.
     * This wrapper handles binding newly-created sessions to servers.
     *
     * @param requestSessionInfo The session info for determining which server to use
     * @param operation The operation to execute
     * @return The SessionInfo result
     * @throws SQLException if the operation fails
     */
    private SessionInfo executeWithSessionStickinessAndBinding(SessionInfo requestSessionInfo,
                                                                ThrowingFunction<StatementServiceGrpcClient, SessionInfo> operation)
            throws SQLException {
        return retryOnServerDraining(requestSessionInfo,
                () -> executeWithSessionStickinessAndBindingOnce(requestSessionInfo, operation));
    }

    private SessionInfo executeWithSessionStickinessAndBindingOnce(SessionInfo requestSessionInfo,
                                                                    ThrowingFunction<StatementServiceGrpcClient, SessionInfo> operation)
            throws SQLException {
        // Get the appropriate server based on session binding or round-robin
        String sessionKey = (requestSessionInfo != null && requestSessionInfo.getSessionUUID() != null && !requestSessionInfo.getSessionUUID().isEmpty())
                ? requestSessionInfo.getSessionUUID() : null;
        ServerEndpoint server = connectionManager.affinityServer(sessionKey);

        log.debug("executeWithSessionStickinessAndBinding: session={}, server={}",
            requestSessionInfo != null ? requestSessionInfo.getSessionUUID() : "null",
            server != null ? server.getAddress() : "null");

        try {
            // Get the channel and stub for the selected server
            MultinodeConnectionManager.ChannelAndStub channelAndStub =
                    connectionManager.getChannelAndStub(server);

            if (channelAndStub == null) {
                throw new SQLException("Unable to get channel for server: " + server.getAddress());
            }

            // Get or create the client for this endpoint
            StatementServiceGrpcClient client = getClient(server);

            // Execute the operation
            SessionInfo responseSessionInfo = operation.apply(client);

            // Check if a session was created and bind it
            checkAndBindSession(requestSessionInfo, responseSessionInfo, server);

            return responseSessionInfo;

        } catch (StatusRuntimeException e) {
            // Let GrpcExceptionHandler convert the exception
            SQLException sqlEx;
            try {
                throw GrpcExceptionHandler.handle(e);
            } catch (SQLException ex) {
                sqlEx = ex;
            }

            // Only mark server unhealthy for connection-level errors
            if (connectionManager.isConnectionLevelError(e)) {
                log.warn("Connection-level error on server {}: {}", server.getAddress(), sqlEx.getMessage());
                // Notify connection manager to mark server unhealthy and invalidate sessions/connections
                connectionManager.handleServerFailure(server, e);
            } else {
                log.debug("Database-level error on server {}: {}", server.getAddress(), sqlEx.getMessage());
            }

            throw sqlEx;

        } catch (SQLException e) {
            markDrainingIfServerDraining(server, e);
            throw e;
        } catch (Exception e) {
            throw new SQLException("Unexpected error executing operation on server " +
                    server.getAddress() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public SessionInfo connect(ConnectionDetails connectionDetails) throws SQLException {
        // Use the connection manager to handle connection with retry and failover logic
        return connectionManager.connect(connectionDetails);
    }

    @Override
    public OpResult executeUpdate(SessionInfo sessionInfo, String sql, List<Parameter> params,
                                  Map<String, Object> properties) throws SQLException {
        return executeUpdate(sessionInfo, sql, params, "", properties);
    }

    @Override
    public OpResult executeUpdate(SessionInfo sessionInfo, String sql, List<Parameter> params,
                                  String statementUUID, Map<String, Object> properties) throws SQLException {
        SessionInfo enhancedSessionInfo = withClusterHealth(sessionInfo);
        return executeOpResultWithSessionStickinessAndBinding(enhancedSessionInfo, client ->
            client.executeUpdate(enhancedSessionInfo, sql, params, statementUUID, properties)
        );
    }

    @Override
    public Iterator<OpResult> executeQuery(SessionInfo sessionInfo, String sql, List<Parameter> params,
                                           Map<String, Object> properties) throws SQLException {
        return executeQuery(sessionInfo, sql, params, "", properties);
    }

    @Override
    public Iterator<OpResult> executeQuery(SessionInfo sessionInfo, String sql, List<Parameter> params,
                                           String statementUUID, Map<String, Object> properties) throws SQLException {
        // For executeQuery, we execute with binding check and wrap the iterator to check subsequent results
        SessionInfo enhancedSessionInfo = withClusterHealth(sessionInfo);
        return executeIteratorWithSessionStickinessAndBinding(enhancedSessionInfo, client ->
            client.executeQuery(enhancedSessionInfo, sql, params, statementUUID, properties)
        );
    }

    @Override
    public OpResult fetchNextRows(SessionInfo sessionInfo, String resultSetUUID, int size) throws SQLException {
        SessionInfo enhancedSessionInfo = withClusterHealth(sessionInfo);
        return executeWithSessionStickiness(enhancedSessionInfo, client ->
            client.fetchNextRows(enhancedSessionInfo, resultSetUUID, size)
        );
    }

    @Override
    public LobReference createLob(Connection connection, Iterator<LobDataBlock> lobDataBlock) throws SQLException {
        SessionInfo requestSessionInfo = connection.getSession();
        SessionInfo enhancedSessionInfo = withClusterHealth(requestSessionInfo);
        String sessionKey = (enhancedSessionInfo != null && enhancedSessionInfo.getSessionUUID() != null
                && !enhancedSessionInfo.getSessionUUID().isEmpty())
                ? enhancedSessionInfo.getSessionUUID() : null;
        ServerEndpoint server = connectionManager.affinityServer(sessionKey);

        try {
            MultinodeConnectionManager.ChannelAndStub channelAndStub = connectionManager.getChannelAndStub(server);
            if (channelAndStub == null) {
                throw new SQLException("Unable to get channel for server: " + server.getAddress());
            }

            StatementServiceGrpcClient client = getClient(server);
            LobReference result = client.createLob(connection, lobDataBlock);

            // Bind any new session UUID returned in the LobReference.
            // This handles lazy session creation: when the server creates a session during LOB
            // upload and returns a new sessionUUID, we must bind it so subsequent operations
            // (e.g. executeUpdate) are routed to the same server.
            if (result != null && result.hasSession()) {
                checkAndBindSession(requestSessionInfo, result.getSession(), server);
            }

            return result;

        } catch (StatusRuntimeException e) {
            SQLException sqlEx;
            try {
                throw GrpcExceptionHandler.handle(e);
            } catch (SQLException ex) {
                sqlEx = ex;
            }

            if (connectionManager.isConnectionLevelError(e)) {
                log.warn("Connection-level error on server {}: {}", server.getAddress(), sqlEx.getMessage());
                connectionManager.handleServerFailure(server, e);
            } else {
                log.debug("Database-level error on server {}: {}", server.getAddress(), sqlEx.getMessage());
            }

            throw sqlEx;
        } catch (SQLException e) {
            markDrainingIfServerDraining(server, e);
            throw e;
        } catch (Exception e) {
            throw new SQLException("Unexpected error creating LOB on server " +
                    server.getAddress() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public Iterator<LobDataBlock> readLob(LobReference lobReference, long pos, int length) throws SQLException {
        SessionInfo sessionInfo = lobReference.getSession();
        return executeWithSessionStickiness(sessionInfo, client ->
            client.readLob(lobReference, pos, length)
        );
    }

    @Override
    public void terminateSession(SessionInfo session) throws SQLException {
        SQLException terminationFailure = null;

        try {
            // For sessions with a UUID, terminate only on the bound server
            if (session != null && session.getSessionUUID() != null && !session.getSessionUUID().isEmpty()) {
                String boundServer = connectionManager.getBoundTargetServer(session.getSessionUUID());
                if (boundServer != null) {
                    log.info("Terminating session {} on bound server {}", session.getSessionUUID(), boundServer);
                    ServerEndpoint server = connectionManager.affinityServer(session.getSessionUUID());
                    StatementServiceGrpcClient client = getClient(server);
                    client.terminateSession(session);
                    log.info("Successfully terminated session {} on server {}",
                            session.getSessionUUID(), server.getAddress());
                } else {
                    log.info("Session {} not bound, attempting termination on all connected servers",
                            session.getSessionUUID());
                    terminationFailure = terminateSessionOnServers(connectionManager.getServersForConnHash(
                            session.getConnHash()), session);
                }
            } else {
                log.info("No sessionUUID, attempting termination on all connected servers");
                terminationFailure = terminateSessionOnServers(connectionManager.getServersForConnHash(
                        session != null ? session.getConnHash() : null), session);
            }
        } finally {
            connectionManager.terminateSession(session);
        }

        if (terminationFailure != null) {
            throw terminationFailure;
        }
    }

    private SQLException terminateSessionOnServers(List<ServerEndpoint> serversToTerminate, SessionInfo session) {
        SQLException firstFailure = null;

        if (serversToTerminate == null || serversToTerminate.isEmpty()) {
            return null;
        }

        for (ServerEndpoint server : serversToTerminate) {
            try {
                StatementServiceGrpcClient client = getClient(server);
                client.terminateSession(session);
                log.debug("Terminated session on server {}", server.getAddress());
            } catch (SQLException e) {
                log.warn("Error terminating session on server {}: {}", server.getAddress(), e.getMessage());
                if (firstFailure == null) {
                    firstFailure = e;
                }
            }
        }

        return firstFailure;
    }

    @Override
    public SessionInfo startTransaction(SessionInfo session) throws SQLException {
        SessionInfo enhancedSessionInfo = withClusterHealth(session);
        return executeWithSessionStickinessAndBinding(enhancedSessionInfo, client ->
            client.startTransaction(enhancedSessionInfo)
        );
    }

    @Override
    public SessionInfo commitTransaction(SessionInfo session) throws SQLException {
        SessionInfo enhancedSessionInfo = withClusterHealth(session);
        return executeWithSessionStickinessAndBinding(enhancedSessionInfo, client ->
            client.commitTransaction(enhancedSessionInfo)
        );
    }

    @Override
    public SessionInfo rollbackTransaction(SessionInfo session) throws SQLException {
        SessionInfo enhancedSessionInfo = withClusterHealth(session);
        return executeWithSessionStickinessAndBinding(enhancedSessionInfo, client ->
            client.rollbackTransaction(enhancedSessionInfo)
        );
    }

    @Override
    public CallResourceResponse callResource(CallResourceRequest request) throws SQLException {
        SessionInfo sessionInfo = request.getSession();
        SessionInfo enhancedSessionInfo = withClusterHealth(sessionInfo);
        CallResourceRequest enhancedRequest = CallResourceRequest.newBuilder(request)
                .setSession(enhancedSessionInfo)
                .build();
        return executeCallResourceWithSessionStickinessAndBinding(enhancedSessionInfo, client ->
            client.callResource(enhancedRequest)
        );
    }

    /**
     * Executes a callResource operation with session stickiness and binding check.
     * This is needed because callResource can trigger lazy session creation on the server
     * (e.g., for DatabaseMetaData calls before any transaction is started). When the server
     * creates a new session, the response will contain the new sessionUUID which must be
     * bound to the server that handled the request so subsequent operations are routed correctly.
     *
     * <p>This method follows the same pattern as {@link #executeOpResultWithSessionStickinessAndBinding}:
     * it selects a server via session affinity or round-robin, executes the operation, and then
     * calls {@code checkAndBindSession} to register any newly-created session UUID in
     * {@code sessionToServerMap}.
     *
     * @param requestSessionInfo The session info for determining which server to use
     * @param operation The operation to execute
     * @return The CallResourceResponse result
     * @throws SQLException if the operation fails
     */
    private CallResourceResponse executeCallResourceWithSessionStickinessAndBinding(
            SessionInfo requestSessionInfo,
            ThrowingFunction<StatementServiceGrpcClient, CallResourceResponse> operation)
            throws SQLException {
        return retryOnServerDraining(requestSessionInfo,
                () -> executeCallResourceWithSessionStickinessAndBindingOnce(requestSessionInfo, operation));
    }

    private CallResourceResponse executeCallResourceWithSessionStickinessAndBindingOnce(
            SessionInfo requestSessionInfo,
            ThrowingFunction<StatementServiceGrpcClient, CallResourceResponse> operation)
            throws SQLException {
        String sessionKey = (requestSessionInfo != null && requestSessionInfo.getSessionUUID() != null
                && !requestSessionInfo.getSessionUUID().isEmpty())
                ? requestSessionInfo.getSessionUUID() : null;
        ServerEndpoint server = connectionManager.affinityServer(sessionKey);

        log.debug("executeCallResourceWithSessionStickinessAndBinding: session={}, server={}",
                requestSessionInfo != null ? requestSessionInfo.getSessionUUID() : "null",
                server != null ? server.getAddress() : "null");

        try {
            MultinodeConnectionManager.ChannelAndStub channelAndStub =
                    connectionManager.getChannelAndStub(server);

            if (channelAndStub == null) {
                throw new SQLException("Unable to get channel for server: " + server.getAddress());
            }

            StatementServiceGrpcClient client = getClient(server);

            CallResourceResponse result = operation.apply(client);

            // Check if response contains a session and bind it.
            // This handles the case where the server created a new session lazily (e.g.,
            // for DatabaseMetaData calls) and the client must remember which server owns it.
            if (result != null && result.hasSession()) {
                checkAndBindSession(requestSessionInfo, result.getSession(), server);
            }

            return result;

        } catch (StatusRuntimeException e) {
            SQLException sqlEx;
            try {
                throw GrpcExceptionHandler.handle(e);
            } catch (SQLException ex) {
                sqlEx = ex;
            }

            if (connectionManager.isConnectionLevelError(e)) {
                log.warn("Connection-level error on server {}: {}", server.getAddress(), sqlEx.getMessage());
                connectionManager.handleServerFailure(server, e);
            } else {
                log.debug("Database-level error on server {}: {}", server.getAddress(), sqlEx.getMessage());
            }

            throw sqlEx;
        } catch (SQLException e) {
            markDrainingIfServerDraining(server, e);
            throw e;
        } catch (Exception e) {
            throw new SQLException("Unexpected error executing operation on server " +
                    server.getAddress() + ": " + e.getMessage(), e);
        }
    }

    // XA Transaction Operations
    @Override
    public com.openjproxy.grpc.XaResponse xaStart(com.openjproxy.grpc.XaStartRequest request) throws SQLException {
        SessionInfo sessionInfo = request.getSession();
        SessionInfo enhancedSessionInfo = withClusterHealth(sessionInfo);
        com.openjproxy.grpc.XaStartRequest enhancedRequest = com.openjproxy.grpc.XaStartRequest.newBuilder(request)
                .setSession(enhancedSessionInfo)
                .build();
        return executeWithSessionStickiness(enhancedSessionInfo, client ->
            client.xaStart(enhancedRequest)
        );
    }

    @Override
    public com.openjproxy.grpc.XaResponse xaEnd(com.openjproxy.grpc.XaEndRequest request) throws SQLException {
        SessionInfo sessionInfo = request.getSession();
        SessionInfo enhancedSessionInfo = withClusterHealth(sessionInfo);
        com.openjproxy.grpc.XaEndRequest enhancedRequest = com.openjproxy.grpc.XaEndRequest.newBuilder(request)
                .setSession(enhancedSessionInfo)
                .build();
        return executeWithSessionStickiness(enhancedSessionInfo, client ->
            client.xaEnd(enhancedRequest)
        );
    }

    @Override
    public com.openjproxy.grpc.XaPrepareResponse xaPrepare(com.openjproxy.grpc.XaPrepareRequest request) throws SQLException {
        SessionInfo sessionInfo = request.getSession();
        SessionInfo enhancedSessionInfo = withClusterHealth(sessionInfo);
        com.openjproxy.grpc.XaPrepareRequest enhancedRequest = com.openjproxy.grpc.XaPrepareRequest.newBuilder(request)
                .setSession(enhancedSessionInfo)
                .build();
        return executeWithSessionStickiness(enhancedSessionInfo, client ->
            client.xaPrepare(enhancedRequest)
        );
    }

    @Override
    public com.openjproxy.grpc.XaResponse xaCommit(com.openjproxy.grpc.XaCommitRequest request) throws SQLException {
        SessionInfo sessionInfo = request.getSession();
        SessionInfo enhancedSessionInfo = withClusterHealth(sessionInfo);
        com.openjproxy.grpc.XaCommitRequest enhancedRequest = com.openjproxy.grpc.XaCommitRequest.newBuilder(request)
                .setSession(enhancedSessionInfo)
                .build();
        return executeWithSessionStickiness(enhancedSessionInfo, client ->
            client.xaCommit(enhancedRequest)
        );
    }

    @Override
    public com.openjproxy.grpc.XaResponse xaRollback(com.openjproxy.grpc.XaRollbackRequest request) throws SQLException {
        SessionInfo sessionInfo = request.getSession();
        SessionInfo enhancedSessionInfo = withClusterHealth(sessionInfo);
        com.openjproxy.grpc.XaRollbackRequest enhancedRequest = com.openjproxy.grpc.XaRollbackRequest.newBuilder(request)
                .setSession(enhancedSessionInfo)
                .build();
        return executeWithSessionStickiness(enhancedSessionInfo, client ->
            client.xaRollback(enhancedRequest)
        );
    }

    @Override
    public com.openjproxy.grpc.XaRecoverResponse xaRecover(com.openjproxy.grpc.XaRecoverRequest request) throws SQLException {
        SessionInfo sessionInfo = request.getSession();
        SessionInfo enhancedSessionInfo = withClusterHealth(sessionInfo);
        com.openjproxy.grpc.XaRecoverRequest enhancedRequest = com.openjproxy.grpc.XaRecoverRequest.newBuilder(request)
                .setSession(enhancedSessionInfo)
                .build();
        return executeWithSessionStickiness(enhancedSessionInfo, client ->
            client.xaRecover(enhancedRequest)
        );
    }

    @Override
    public com.openjproxy.grpc.XaResponse xaForget(com.openjproxy.grpc.XaForgetRequest request) throws SQLException {
        SessionInfo sessionInfo = request.getSession();
        SessionInfo enhancedSessionInfo = withClusterHealth(sessionInfo);
        com.openjproxy.grpc.XaForgetRequest enhancedRequest = com.openjproxy.grpc.XaForgetRequest.newBuilder(request)
                .setSession(enhancedSessionInfo)
                .build();
        return executeWithSessionStickiness(enhancedSessionInfo, client ->
            client.xaForget(enhancedRequest)
        );
    }

    @Override
    public com.openjproxy.grpc.XaSetTransactionTimeoutResponse xaSetTransactionTimeout(
            com.openjproxy.grpc.XaSetTransactionTimeoutRequest request) throws SQLException {
        SessionInfo sessionInfo = request.getSession();
        SessionInfo enhancedSessionInfo = withClusterHealth(sessionInfo);
        com.openjproxy.grpc.XaSetTransactionTimeoutRequest enhancedRequest = com.openjproxy.grpc.XaSetTransactionTimeoutRequest.newBuilder(request)
                .setSession(enhancedSessionInfo)
                .build();
        return executeWithSessionStickiness(enhancedSessionInfo, client ->
            client.xaSetTransactionTimeout(enhancedRequest)
        );
    }

    @Override
    public com.openjproxy.grpc.XaGetTransactionTimeoutResponse xaGetTransactionTimeout(
            com.openjproxy.grpc.XaGetTransactionTimeoutRequest request) throws SQLException {
        SessionInfo sessionInfo = request.getSession();
        SessionInfo enhancedSessionInfo = withClusterHealth(sessionInfo);
        com.openjproxy.grpc.XaGetTransactionTimeoutRequest enhancedRequest = com.openjproxy.grpc.XaGetTransactionTimeoutRequest.newBuilder(request)
                .setSession(enhancedSessionInfo)
                .build();
        return executeWithSessionStickiness(enhancedSessionInfo, client ->
            client.xaGetTransactionTimeout(enhancedRequest)
        );
    }

    @Override
    public com.openjproxy.grpc.XaIsSameRMResponse xaIsSameRM(
            com.openjproxy.grpc.XaIsSameRMRequest request) throws SQLException {
        // For isSameRM, use the first session for server selection
        SessionInfo sessionInfo1 = request.getSession1();
        SessionInfo sessionInfo2 = request.getSession2();
        SessionInfo enhancedSessionInfo1 = withClusterHealth(sessionInfo1);
        SessionInfo enhancedSessionInfo2 = withClusterHealth(sessionInfo2);
        com.openjproxy.grpc.XaIsSameRMRequest enhancedRequest = com.openjproxy.grpc.XaIsSameRMRequest.newBuilder(request)
                .setSession1(enhancedSessionInfo1)
                .setSession2(enhancedSessionInfo2)
                .build();
        return executeWithSessionStickiness(enhancedSessionInfo1, client ->
            client.xaIsSameRM(enhancedRequest)
        );
    }

    /**
     * Executes an operation with session stickiness and selective error handling.
     * If the session is bound to a server, uses that server.
     * Connection-level errors do not trigger failover for session-bound requests (session stickiness).
     * Database-level errors are propagated without marking servers unhealthy.
     *
     * @param sessionInfo The session info for determining which server to use
     * @param operation The operation to execute
     * @param <T> The return type of the operation
     * @return The result of the operation
     * @throws SQLException if the operation fails
     */
    private <T> T executeWithSessionStickiness(SessionInfo sessionInfo,
                                               ThrowingFunction<StatementServiceGrpcClient, T> operation)
            throws SQLException {
        // Get the appropriate server based on session binding or round-robin
        String sessionKey = (sessionInfo != null && sessionInfo.getSessionUUID() != null && !sessionInfo.getSessionUUID().isEmpty())
                ? sessionInfo.getSessionUUID() : null;
        ServerEndpoint server = connectionManager.affinityServer(sessionKey);

        log.debug("executeWithSessionStickiness: session={}, server={}",
            sessionInfo != null ? sessionInfo.getSessionUUID() : "null",
            server != null ? server.getAddress() : "null");

        try {
            // Get the channel and stub for the selected server
            MultinodeConnectionManager.ChannelAndStub channelAndStub =
                    connectionManager.getChannelAndStub(server);

            if (channelAndStub == null) {
                throw new SQLException("Unable to get channel for server: " + server.getAddress());
            }

            log.debug("Got channelAndStub for server {}: blockingStub={}, asyncStub={}",
                server.getAddress(),
                System.identityHashCode(channelAndStub.blockingStub),
                System.identityHashCode(channelAndStub.asyncStub));

            // Get or create the client for this endpoint
            StatementServiceGrpcClient client = getClient(server);

            log.debug("Using client for server {}, about to execute operation", server.getAddress());

            // Execute the operation
            return operation.apply(client);

        } catch (StatusRuntimeException e) {
            // Let GrpcExceptionHandler convert the exception
            try {
                // Convert and capture the SQLException if possible.
                throw GrpcExceptionHandler.handle(e);
            } catch (SQLException | StatusRuntimeException ex) {
                invalidateSessionIfConnectionLevelError(sessionInfo, e, server);
                throw ex;
            }
        } catch (SQLException e) {
            // Already a SQLException, just throw it
            markDrainingIfServerDraining(server, e);
            throw e;
        } catch (Exception e) {
            // Unexpected exception
            throw new SQLException("Unexpected error executing operation on server " +
                    server.getAddress() + ": " + e.getMessage(), e);
        }
    }

    private void invalidateSessionIfConnectionLevelError(SessionInfo sessionInfo, StatusRuntimeException e, ServerEndpoint server) {
        // Only mark server unhealthy for connection-level errors
        // Database-level errors (e.g., syntax errors, constraint violations) should not affect server health
        if (connectionManager.isConnectionLevelError(e)) {
            log.warn("Connection-level error on server {}: {}", server.getAddress(), e.getMessage());
            // Notify connection manager to mark server unhealthy and invalidate sessions/connections
            connectionManager.handleServerFailure(server, e);
            // Note: For session-bound requests, we don't failover - we throw the exception
            // This enforces session stickiness as per the requirements
        } else {
            log.debug("Database-level error on server {}: {}", server.getAddress(), e.getMessage());
        }
    }

    /**
     * Runs {@code call}; if it fails because the server is draining (graceful shutdown) and the
     * request has no session yet, retries it on another healthy server. Draining rejections happen
     * before any SQL runs on the server, so a retry can never execute a statement twice.
     * Requests that belong to an existing session are never retried: they stay on their server.
     */
    private <T> T retryOnServerDraining(SessionInfo requestSessionInfo, ThrowingSupplier<T> call)
            throws SQLException {
        boolean hasSession = requestSessionInfo != null
                && requestSessionInfo.getSessionUUID() != null
                && !requestSessionInfo.getSessionUUID().isEmpty();
        int maxAttempts = Math.max(1, connectionManager.getServerEndpoints().size());
        int attempt = 1;
        while (true) {
            try {
                return call.get();
            } catch (SQLException e) {
                if (hasSession || attempt >= maxAttempts || !GrpcExceptionHandler.isServerDrainingError(e)) {
                    throw e;
                }
                attempt++;
                log.info("Server draining; retrying request without session on another server (attempt {}/{})",
                        attempt, maxAttempts);
            }
        }
    }

    private void markDrainingIfServerDraining(ServerEndpoint server, Exception e) {
        if (server != null && GrpcExceptionHandler.isServerDrainingError(e)) {
            connectionManager.markDraining(server);
        }
    }

    /**
     * Supplier that may throw SQLException.
     */
    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws SQLException;
    }

    /**
     * Functional interface for operations that throw SQLException.
     */
    @FunctionalInterface
    private interface ThrowingFunction<T, R> {
        R apply(T t) throws SQLException;
    }

    /**
     * Phase 2: Gets the connection manager for registering health listeners.
     *
     * @return The connection manager
     */
    public MultinodeConnectionManager getConnectionManager() {
        return connectionManager;
    }

    /**
     * Shuts down all connections managed by this service.
     */
    public void shutdown() {
        log.info("Shutting down MultinodeStatementService");
        connectionManager.shutdown();
        clientMap.clear();
    }
}
