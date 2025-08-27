package org.openjproxy.grpc.server;

import lombok.extern.slf4j.Slf4j;
import org.openjdbcproxy.constants.CommonConstants;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages multinode server coordination and pool rebalancing.
 * This class handles:
 * - Tracking known servers in the cluster
 * - Computing pool size adjustments based on server count
 * - Coordinating pool rebalancing when server topology changes
 */
@Slf4j
public class MultinodeServerManager {

    private final Map<String, List<String>> clientServerMap; // clientUUID -> list of known servers
    private final Map<String, Integer> originalPoolSizes; // connHash -> original max pool size

    public MultinodeServerManager() {
        this.clientServerMap = new ConcurrentHashMap<>();
        this.originalPoolSizes = new ConcurrentHashMap<>();
    }

    /**
     * Registers a client's known server list and returns the adjusted pool configuration.
     *
     * @param clientUUID The UUID of the connecting client
     * @param serverEndpoints List of server endpoints known to the client
     * @param connHash The connection hash for the datasource
     * @param originalMaxPoolSize The original maximum pool size requested
     * @return Adjusted pool configuration based on server count
     */
    public PoolConfiguration registerClientServers(String clientUUID,
                                                  List<String> serverEndpoints,
                                                  String connHash,
                                                  int originalMaxPoolSize) {

        // Store the original pool size if not already stored
        originalPoolSizes.putIfAbsent(connHash, originalMaxPoolSize);

        // Update client's server list
        List<String> previousServers = clientServerMap.put(clientUUID, List.copyOf(serverEndpoints));

        boolean serversChanged = previousServers == null || !previousServers.equals(serverEndpoints);

        if (serversChanged) {
            log.info("Client {} updated server list from {} to {} servers",
                    clientUUID,
                    previousServers != null ? previousServers.size() : 0,
                    serverEndpoints.size());
        }

        // Calculate the effective server count (minimum 1)
        int serverCount = Math.max(1, serverEndpoints.size());

        // Get the stored original pool size
        int storedOriginalSize = originalPoolSizes.get(connHash);

        // Calculate adjusted pool sizes
        PoolConfiguration poolConfig = calculatePoolConfiguration(storedOriginalSize, serverCount);

        log.debug("Pool configuration for connHash {}: original max={}, servers={}, adjusted max={}, adjusted min={}",
                connHash, storedOriginalSize, serverCount, poolConfig.getMaxPoolSize(), poolConfig.getMinPoolSize());

        return poolConfig;
    }

    /**
     * Calculates the adjusted pool configuration based on server count.
     *
     * @param originalMaxPoolSize Original maximum pool size
     * @param serverCount Number of servers in the cluster
     * @return Adjusted pool configuration
     */
    private PoolConfiguration calculatePoolConfiguration(int originalMaxPoolSize, int serverCount) {
        // Always round up on division to ensure total capacity is maintained
        int adjustedMaxPoolSize = (int) Math.ceil((double) originalMaxPoolSize / serverCount);

        // Calculate adjusted minimum pool size
        // Use default minimum idle as base, then adjust proportionally
        int baseMinIdle = CommonConstants.DEFAULT_MINIMUM_IDLE;
        int adjustedMinPoolSize = Math.max(1, (int) Math.ceil((double) baseMinIdle / serverCount));

        // Ensure minimum doesn't exceed maximum
        adjustedMinPoolSize = Math.min(adjustedMinPoolSize, adjustedMaxPoolSize);

        return PoolConfiguration.builder()
                .maxPoolSize(adjustedMaxPoolSize)
                .minPoolSize(adjustedMinPoolSize)
                .serverCount(serverCount)
                .build();
    }

    /**
     * Removes a client from tracking when they disconnect.
     *
     * @param clientUUID The UUID of the disconnecting client
     */
    public void unregisterClient(String clientUUID) {
        List<String> removedServers = clientServerMap.remove(clientUUID);
        if (removedServers != null) {
            log.debug("Unregistered client {} with {} servers", clientUUID, removedServers.size());
        }
    }

    /**
     * Gets the current effective server count for a connection.
     * Returns the maximum number of servers reported by any client for this connection.
     *
     * @param connHash The connection hash
     * @return Effective server count
     */
    public int getEffectiveServerCount(String connHash) {
        return clientServerMap.values().stream()
                .mapToInt(List::size)
                .max()
                .orElse(1);
    }

    /**
     * Checks if any client has reported a server list change that would affect pool configuration.
     *
     * @param connHash The connection hash
     * @param currentServerCount The current server count used for pool configuration
     * @return true if pool should be reconfigured
     */
    public boolean shouldReconfigurePool(String connHash, int currentServerCount) {
        int effectiveServerCount = getEffectiveServerCount(connHash);
        return effectiveServerCount != currentServerCount;
    }

    /**
     * Pool configuration data class.
     */
    @lombok.Data
    @lombok.Builder
    public static class PoolConfiguration {
        private final int maxPoolSize;
        private final int minPoolSize;
        private final int serverCount;
    }
}
