package org.openjproxy.grpc.client;

import com.openjproxy.grpc.ConnectionDetails;
import com.openjproxy.grpc.SessionInfo;
import com.openjproxy.grpc.StatementServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.constants.CommonConstants;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages multinode connections to OJP servers with round-robin routing,
 * session stickiness, and failover support.
 */
@Slf4j
public class MultinodeConnectionManager {

    private static final String DNS_PREFIX = "dns:///";

    private final List<ServerEndpoint> serverEndpoints;
    private final Map<ServerEndpoint, ChannelAndStub> channelMap;
    private final Map<String, ServerEndpoint> sessionToServerMap; // sessionUUID -> server
    private final AtomicInteger roundRobinCounter;
    private final int retryAttempts;
    private final long retryDelayMs;

    public MultinodeConnectionManager(List<ServerEndpoint> serverEndpoints) {
        this(serverEndpoints, CommonConstants.DEFAULT_MULTINODE_RETRY_ATTEMPTS,
             CommonConstants.DEFAULT_MULTINODE_RETRY_DELAY_MS);
    }

    public MultinodeConnectionManager(List<ServerEndpoint> serverEndpoints,
                                    int retryAttempts, long retryDelayMs) {
        if (serverEndpoints == null || serverEndpoints.isEmpty()) {
            throw new IllegalArgumentException("Server endpoints list cannot be null or empty");
        }

        this.serverEndpoints = List.copyOf(serverEndpoints);
        this.channelMap = new ConcurrentHashMap<>();
        this.sessionToServerMap = new ConcurrentHashMap<>();
        this.roundRobinCounter = new AtomicInteger(0);
        this.retryAttempts = retryAttempts;
        this.retryDelayMs = retryDelayMs;

        // Initialize channels and stubs for all servers
        initializeConnections();

        log.info("MultinodeConnectionManager initialized with {} servers: {}",
                serverEndpoints.size(), serverEndpoints);
    }

    private void initializeConnections() {
        for (ServerEndpoint endpoint : serverEndpoints) {
            try {
                createChannelAndStub(endpoint);
                log.debug("Successfully initialized connection to {}", endpoint.getAddress());
            } catch (Exception e) {
                log.warn("Failed to initialize connection to {}: {}", endpoint.getAddress(), e.getMessage());
                endpoint.setHealthy(false);
                endpoint.setLastFailureTime(System.currentTimeMillis());
            }
        }
    }

    private ChannelAndStub createChannelAndStub(ServerEndpoint endpoint) {
        String target = DNS_PREFIX + endpoint.getHost() + ":" + endpoint.getPort();
        ManagedChannel channel = ManagedChannelBuilder.forTarget(target)
                .usePlaintext()
                .build();

        StatementServiceGrpc.StatementServiceBlockingStub blockingStub =
                StatementServiceGrpc.newBlockingStub(channel);
        StatementServiceGrpc.StatementServiceStub asyncStub =
                StatementServiceGrpc.newStub(channel);

        ChannelAndStub channelAndStub = new ChannelAndStub(channel, blockingStub, asyncStub);
        channelMap.put(endpoint, channelAndStub);

        return channelAndStub;
    }

    /**
     * Establishes a connection using round-robin routing among healthy servers.
     */
    public SessionInfo connect(ConnectionDetails connectionDetails) throws SQLException {
        int attempts = 0;
        SQLException lastException = null;

        while (retryAttempts == -1 || attempts < retryAttempts) {
            ServerEndpoint selectedServer = selectHealthyServer();

            if (selectedServer == null) {
                // No healthy servers available, wait and retry
                if (attempts > 0) {
                    try {
                        Thread.sleep(retryDelayMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new SQLException("Connection attempt interrupted", e);
                    }
                }
                attempts++;
                continue;
            }

            try {
                ChannelAndStub channelAndStub = channelMap.get(selectedServer);
                if (channelAndStub == null) {
                    channelAndStub = createChannelAndStub(selectedServer);
                }

                SessionInfo sessionInfo = channelAndStub.blockingStub.connect(connectionDetails);

                // Mark server as healthy
                selectedServer.setHealthy(true);
                selectedServer.setLastFailureTime(0);

                // Associate session with server for session stickiness
                if (sessionInfo.getSessionUUID() != null && !sessionInfo.getSessionUUID().isEmpty()) {
                    sessionToServerMap.put(sessionInfo.getSessionUUID(), selectedServer);
                    log.debug("Session {} associated with server {}",
                            sessionInfo.getSessionUUID(), selectedServer.getAddress());
                }

                log.debug("Successfully connected to server {}", selectedServer.getAddress());
                return sessionInfo;

            } catch (StatusRuntimeException e) {
                lastException = handleStatusRuntimeException(e);
                handleServerFailure(selectedServer, e);

                log.warn("Connection failed to server {}: {}",
                        selectedServer.getAddress(), e.getMessage());

                attempts++;
                if (retryAttempts != -1 && attempts >= retryAttempts) {
                    break;
                }

                try {
                    Thread.sleep(retryDelayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new SQLException("Connection attempt interrupted", ie);
                }
            }
        }

        throw new SQLException("Failed to connect to any server after " + attempts + " attempts. " +
                "Last error: " + (lastException != null ? lastException.getMessage() : "No healthy servers available"));
    }

    /**
     * Gets the appropriate server for a session-bound request.
     */
    public ServerEndpoint getServerForSession(SessionInfo sessionInfo) {
        if (sessionInfo == null || sessionInfo.getSessionUUID() == null ||
            sessionInfo.getSessionUUID().isEmpty()) {
            // No session or session UUID, use round-robin
            return selectHealthyServer();
        }

        ServerEndpoint sessionServer = sessionToServerMap.get(sessionInfo.getSessionUUID());
        if (sessionServer != null && sessionServer.isHealthy()) {
            return sessionServer;
        }

        // Session server is unhealthy or not found, remove from map and use round-robin
        if (sessionServer != null) {
            sessionToServerMap.remove(sessionInfo.getSessionUUID());
            log.warn("Session {} server {} is unhealthy, using round-robin for next request",
                    sessionInfo.getSessionUUID(), sessionServer.getAddress());
        }

        return selectHealthyServer();
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
                .toList();

        if (healthyServers.isEmpty()) {
            // No healthy servers, try to recover some servers
            attemptServerRecovery();
            healthyServers = serverEndpoints.stream()
                    .filter(ServerEndpoint::isHealthy)
                    .toList();
        }

        if (healthyServers.isEmpty()) {
            log.error("No healthy servers available");
            return null;
        }

        // Round-robin selection among healthy servers
        int index = Math.abs(roundRobinCounter.getAndIncrement()) % healthyServers.size();
        ServerEndpoint selected = healthyServers.get(index);

        log.debug("Selected server {} for request (round-robin)", selected.getAddress());
        return selected;
    }

    private void handleServerFailure(ServerEndpoint endpoint, Exception exception) {
        endpoint.setHealthy(false);
        endpoint.setLastFailureTime(System.currentTimeMillis());

        log.warn("Marked server {} as unhealthy due to: {}",
                endpoint.getAddress(), exception.getMessage());

        // Remove the failed channel
        ChannelAndStub channelAndStub = channelMap.remove(endpoint);
        if (channelAndStub != null) {
            try {
                channelAndStub.channel.shutdown();
            } catch (Exception e) {
                log.debug("Error shutting down channel for {}: {}", endpoint.getAddress(), e.getMessage());
            }
        }
    }

    private void attemptServerRecovery() {
        long currentTime = System.currentTimeMillis();

        for (ServerEndpoint endpoint : serverEndpoints) {
            if (!endpoint.isHealthy() &&
                (currentTime - endpoint.getLastFailureTime()) > retryDelayMs) {

                try {
                    log.debug("Attempting to recover server {}", endpoint.getAddress());
                    createChannelAndStub(endpoint);
                    endpoint.setHealthy(true);
                    endpoint.setLastFailureTime(0);
                    log.info("Successfully recovered server {}", endpoint.getAddress());
                } catch (Exception e) {
                    endpoint.setLastFailureTime(currentTime);
                    log.debug("Server {} recovery attempt failed: {}",
                            endpoint.getAddress(), e.getMessage());
                }
            }
        }
    }

    private SQLException handleStatusRuntimeException(StatusRuntimeException e) {
        try {
            GrpcExceptionHandler.handle(e);
            // If handle() doesn't throw an exception, create a generic SQLException
            return new SQLException("gRPC call failed: " + e.getMessage(), e);
        } catch (SQLException sqlEx) {
            return sqlEx;
        }
    }

    /**
     * Terminates a session and removes its server association.
     */
    public void terminateSession(SessionInfo sessionInfo) {
        if (sessionInfo != null && sessionInfo.getSessionUUID() != null) {
            sessionToServerMap.remove(sessionInfo.getSessionUUID());
            log.debug("Removed session {} from server association map", sessionInfo.getSessionUUID());
        }
    }

    /**
     * Gets the list of configured server endpoints.
     */
    public List<ServerEndpoint> getServerEndpoints() {
        return List.copyOf(serverEndpoints);
    }

    /**
     * Shuts down all connections.
     */
    public void shutdown() {
        log.info("Shutting down MultinodeConnectionManager");

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
