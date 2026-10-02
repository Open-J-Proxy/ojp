package org.openjproxy.grpc.client;

import com.openjproxy.grpc.ConnectionDetails;
import com.openjproxy.grpc.SessionInfo;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Validates server health by attempting a direct connection.
 * Used to detect when a failed server has recovered.
 */
public class HealthCheckValidator {

    private static final Logger log = LoggerFactory.getLogger(HealthCheckValidator.class);

    /**
     * Outcome of a heartbeat health check.
     */
    public enum Result {
        /** The server answered the heartbeat. */
        HEALTHY,
        /** The server answered but rejected the heartbeat because it is draining (graceful shutdown). */
        DRAINING,
        /** The server did not answer (down, unreachable, timeout, ...). */
        UNREACHABLE
    }

    private final HealthCheckConfig config;
    private final MultinodeConnectionManager connectionManager;

    public HealthCheckValidator(HealthCheckConfig config, MultinodeConnectionManager connectionManager) {
        this.config = config;
        this.connectionManager = connectionManager;
    }

    /**
     * Validates if a server is healthy by attempting a connection.
     *
     * @param endpoint The server endpoint to validate
     * @param connectionDetails Connection details to use for validation
     * @return true if server is healthy, false otherwise
     */
    public boolean validateServer(ServerEndpoint endpoint, ConnectionDetails connectionDetails) {
        if (endpoint == null) {
            return false;
        }

        log.debug("Validating server health: {}", endpoint.getAddress());

        // Check if this is a heartbeat check (empty connection details)
        // Do this BEFORE attempting connection to avoid channel errors
        if (StringUtils.isBlank(connectionDetails.getUrl()) &&
            StringUtils.isBlank(connectionDetails.getUser()) &&
            StringUtils.isBlank(connectionDetails.getPassword())) {
            log.debug("Using heartbeat health check for {}", endpoint.getAddress());
            return heartbeat(endpoint, connectionDetails) == Result.HEALTHY;
        }

        return validateWithCredentials(endpoint, connectionDetails);
    }

    private Result heartbeat(ServerEndpoint endpoint, ConnectionDetails connectionDetails) {
        try {
            // Attempt to get channel and stub for this server
            MultinodeConnectionManager.ChannelAndStub channelAndStub =
                connectionManager.getChannelAndStub(endpoint);

            if (channelAndStub == null) {
                log.debug("No channel available for {}, attempting to create", endpoint.getAddress());
                channelAndStub = connectionManager.createChannelAndStubForEndpoint(endpoint);
            }

            // Try heartbeat connection
            log.debug("Attempting heartbeat connection to {}", endpoint.getAddress());
            SessionInfo sessionInfo = channelAndStub.blockingStub.connect(connectionDetails);

            log.info("Server {} heartbeat health check PASSED", endpoint.getAddress());
            return Result.HEALTHY;

        } catch (Exception e) {
            if (GrpcExceptionHandler.isServerDrainingError(e)) {
                log.debug("Server {} heartbeat health check: server is draining", endpoint.getAddress());
                return Result.DRAINING;
            }
            log.debug("Server {} heartbeat health check FAILED: {}", endpoint.getAddress(), e.getMessage());
            return Result.UNREACHABLE;
        }
    }

    private boolean validateWithCredentials(ServerEndpoint endpoint, ConnectionDetails connectionDetails) {
        // Full connection test with actual credentials
        try {
            // Attempt to get channel and stub for this server
            MultinodeConnectionManager.ChannelAndStub channelAndStub =
                connectionManager.getChannelAndStub(endpoint);

            if (channelAndStub == null) {
                log.debug("No channel available for {}, attempting to create", endpoint.getAddress());
                channelAndStub = connectionManager.createChannelAndStubForEndpoint(endpoint);
            }

            // Try to establish a test connection
            log.debug("Attempting test connection to {}", endpoint.getAddress());
            SessionInfo sessionInfo = channelAndStub.blockingStub.connect(connectionDetails);

            if (sessionInfo != null && sessionInfo.getSessionUUID() != null &&
                !sessionInfo.getSessionUUID().isEmpty()) {

                // Connection successful
                log.info("Server {} health check PASSED", endpoint.getAddress());

                // Close the test session
                try {
                    connectionManager.closeSession(sessionInfo.getSessionUUID());
                } catch (Exception e) {
                    log.debug("Failed to close test session {}: {}",
                        sessionInfo.getSessionUUID(), e.getMessage());
                }

                return true;
            }

            log.debug("Server {} health check FAILED - no valid session returned", endpoint.getAddress());
            return false;

        } catch (Exception e) {
            log.debug("Server {} health check FAILED: {}", endpoint.getAddress(), e.getMessage());
            return false;
        }
    }

    /**
     * Validates if a server is healthy using a lightweight heartbeat connection.
     * Sends a CONNECT request with empty credentials; the server responds with an
     * empty SessionInfo without creating a real session.  A successful response
     * means the gRPC transport is reachable; an exception means it is not.
     *
     * @param endpoint The server endpoint to validate
     * @return true if the server is reachable, false otherwise
     */
    public boolean validateServer(ServerEndpoint endpoint) {
        return checkServer(endpoint) == Result.HEALTHY;
    }

    /**
     * Runs a lightweight heartbeat and reports whether the server is healthy, draining or unreachable.
     *
     * @param endpoint The server endpoint to check
     * @return the heartbeat result
     */
    public Result checkServer(ServerEndpoint endpoint) {
        if (endpoint == null) {
            return Result.UNREACHABLE;
        }
        // Create minimal connection details for health check
        ConnectionDetails connectionDetails = ConnectionDetails.newBuilder()
            .setUrl("") // Empty URL for health check
            .setUser("")
            .setPassword("")
            .build();

        return heartbeat(endpoint, connectionDetails);
    }
}
