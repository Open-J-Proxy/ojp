package org.openjproxy.grpc.server;

import lombok.extern.slf4j.Slf4j;
import org.openjproxy.constants.CommonConstants;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Coordinates connection pool sizes across multiple OJP server instances in a multinode setup.
 *
 * When multiple servers are configured:
 * - Pool sizes are divided among servers (e.g., maxPoolSize=20 with 2 servers = 10 each)
 * - When a server becomes unhealthy, remaining servers increase their pools to maintain capacity
 * - When an unhealthy server recovers, all servers rebalance back to divided pool sizes
 *
 * This ensures global pool limits are respected while maintaining high availability.
 */
@Slf4j
public class MultinodePoolCoordinator {

    private final Map<String, PoolAllocation> poolAllocations = new ConcurrentHashMap<>();

    /**
     * Stores the most-recent healthy-server count received via {@link #updateHealthyServers}
     * for a connHash that does not yet have a {@link PoolAllocation} (i.e. the pool has not
     * been created yet).  {@link #calculatePoolSizes} consumes this entry so that the pool is
     * born at the correct (expanded) size even when cluster-health pushes arrive before the
     * first {@code ConnectAction} for that connHash.
     *
     * <p>This race is most visible after a server restart: the driver reconnects and pushes
     * cluster-health information via {@code StartTransactionAction} before the new
     * {@code ConnectAction} that actually creates the pool has been processed.</p>
     */
    private final Map<String, Integer> pendingHealthyServers = new ConcurrentHashMap<>();

    /**
     * Represents the pool allocation for a connection hash (dataSource).
     */
    public static class PoolAllocation {
        private final int originalMaxPoolSize;
        private final int originalMinIdle;
        private final int totalServers;
        private final List<String> serverEndpoints;
        private int healthyServers;

        public PoolAllocation(int originalMaxPoolSize, int originalMinIdle, int totalServers) {
            this(originalMaxPoolSize, originalMinIdle, totalServers, List.of());
        }

        public PoolAllocation(int originalMaxPoolSize, int originalMinIdle, int totalServers,
                              List<String> serverEndpoints) {
            this.originalMaxPoolSize = originalMaxPoolSize;
            this.originalMinIdle = originalMinIdle;
            this.totalServers = totalServers;
            this.healthyServers = totalServers;
            this.serverEndpoints = serverEndpoints == null ? List.of() : List.copyOf(serverEndpoints);
        }

        public int getCurrentMaxPoolSize() {
            if (healthyServers <= 0) {
                return originalMaxPoolSize; // Fallback to original if no healthy servers
            }
            // Divide the total pool among healthy servers, rounding up
            return (int) Math.ceil((double) originalMaxPoolSize / healthyServers);
        }

        public int getCurrentMinIdle() {
            if (healthyServers <= 0) {
                return originalMinIdle; // Fallback to original if no healthy servers
            }
            // Divide the total min idle among healthy servers, rounding up
            return (int) Math.ceil((double) originalMinIdle / healthyServers);
        }

        public void updateHealthyServerCount(int count) {
            this.healthyServers = Math.max(1, Math.min(count, totalServers));
        }

        public int getOriginalMaxPoolSize() {
            return originalMaxPoolSize;
        }

        public int getOriginalMinIdle() {
            return originalMinIdle;
        }

        public int getTotalServers() {
            return totalServers;
        }

        public int getHealthyServers() {
            return healthyServers;
        }

        public List<String> getServerEndpoints() {
            return serverEndpoints;
        }
    }

    /**
     * Calculates pool sizes for a multinode configuration.
     *
     * @param connHash Connection hash identifying the datasource
     * @param requestedMaxPoolSize Maximum pool size from configuration
     * @param requestedMinIdle Minimum idle connections from configuration
     * @param serverEndpoints List of server endpoints in the cluster
     * @return PoolAllocation with calculated sizes
     */
    public PoolAllocation calculatePoolSizes(String connHash, int requestedMaxPoolSize,
                                            int requestedMinIdle, List<String> serverEndpoints) {

        if (serverEndpoints == null || serverEndpoints.isEmpty()) {
            // Single node - no coordination needed, return original values
            log.debug("Single node configuration for {}, using original pool sizes: max={}, min={}",
                    connHash, requestedMaxPoolSize, requestedMinIdle);
            return new PoolAllocation(requestedMaxPoolSize, requestedMinIdle, 1);
        }

        int serverCount = serverEndpoints.size();

        // Create allocation with divided pool sizes.
        // Health counts may have been received before the allocation existed (e.g. a cluster-health
        // push arrives before the ConnectAction that creates the pool, or the server was restarted
        // and the driver pushes health state via StartTransactionAction before reconnecting via
        // ConnectAction).  We handle two complementary cases:
        //
        //   1. An existing allocation already has healthyServers < totalServers — preserve that
        //      count so a subsequent calculatePoolSizes() call (e.g. from a new ConnectAction)
        //      does not reset it back to totalServers.
        //
        //   2. No allocation exists yet but updateHealthyServers() was called while the map was
        //      empty — a "pending" count is stored in pendingHealthyServers.  We consume it here
        //      so the pool is created at the correct (expanded) size immediately.
        PoolAllocation allocation = new PoolAllocation(requestedMaxPoolSize, requestedMinIdle, serverCount,
                serverEndpoints);
        PoolAllocation existingAllocation = poolAllocations.get(connHash);
        if (existingAllocation != null && existingAllocation.getHealthyServers() < serverCount) {
            allocation.updateHealthyServerCount(existingAllocation.getHealthyServers());
            log.info("Preserved healthyServers={} from prior allocation for {} when creating new pool",
                    existingAllocation.getHealthyServers(), connHash);
        } else {
            // Consume any pending health count that arrived before the allocation was created.
            Integer pending = pendingHealthyServers.remove(connHash);
            if (pending != null) {
                int effective = Math.max(1, Math.min(pending, serverCount));
                if (effective < serverCount) {
                    allocation.updateHealthyServerCount(effective);
                    log.info("Applied pending healthyServers={} (raw={}) from pre-pool health update for {} when creating new pool",
                            effective, pending, connHash);
                } else {
                    log.debug("Pending healthyServers={} (raw={}) for {} equals totalServers={}, using divided pool sizes",
                            effective, pending, connHash, serverCount);
                }
            }
        }

        poolAllocations.put(connHash, allocation);

        log.info("Multinode configuration for {}: {} servers, original max={}, min={}, divided max={}, min={}",
                connHash, serverCount, requestedMaxPoolSize, requestedMinIdle,
                allocation.getCurrentMaxPoolSize(), allocation.getCurrentMinIdle());

        return allocation;
    }

    /**
     * Updates the number of healthy servers for a connection hash.
     * This triggers pool size recalculation.
     *
     * Returns the updated {@link PoolAllocation} so callers can use it directly
     * without a second map lookup. A second lookup would be subject to a race
     * condition where a concurrent {@code calculatePoolSizes()} call can overwrite
     * the allocation between the update and the read, causing stale (un-updated)
     * pool sizes to be used for resizing.
     *
     * @param connHash Connection hash identifying the datasource
     * @param healthyServerCount Number of currently healthy servers
     * @return the updated PoolAllocation, or {@code null} if none exists for the given connHash
     */
    public PoolAllocation updateHealthyServers(String connHash, int healthyServerCount) {
        PoolAllocation allocation = poolAllocations.get(connHash);
        if (allocation != null) {
            int oldCount = allocation.getHealthyServers();
            allocation.updateHealthyServerCount(healthyServerCount);

            log.info("Updated healthy server count for {}: {} -> {}, pool sizes: max={}, min={}",
                    connHash, oldCount, healthyServerCount,
                    allocation.getCurrentMaxPoolSize(), allocation.getCurrentMinIdle());
        } else {
            // No allocation yet (pool has not been created for this connHash — e.g. the server
            // was just restarted).  Store the raw count so calculatePoolSizes() can apply it
            // when the pool is eventually created by a ConnectAction.
            pendingHealthyServers.put(connHash, healthyServerCount);
            log.info("No pool allocation found for {} - storing pending healthyServers={} for later pool creation",
                    connHash, healthyServerCount);
        }
        return allocation;
    }

    /**
     * Gets the pool allocation for a connection hash.
     *
     * @param connHash Connection hash
     * @return PoolAllocation or null if not found
     */
    public PoolAllocation getPoolAllocation(String connHash) {
        return poolAllocations.get(connHash);
    }

    /**
     * Returns pools whose configured endpoint set matches the given pool.
     * Health reports describe one endpoint set and must not be applied to a different cluster.
     *
     * @param connHash connection hash identifying the source pool
     * @return matching pool hashes, including the source when it has an allocation
     */
    public List<String> getConnHashesWithSameServerEndpoints(String connHash) {
        PoolAllocation source = poolAllocations.get(connHash);
        if (source == null || source.getServerEndpoints().isEmpty()) {
            return List.of(connHash);
        }

        Set<String> sourceEndpoints = new HashSet<>(source.getServerEndpoints());
        List<String> matchingConnHashes = new ArrayList<>();
        poolAllocations.forEach((poolConnHash, allocation) -> {
            if (sourceEndpoints.equals(new HashSet<>(allocation.getServerEndpoints()))) {
                matchingConnHashes.add(poolConnHash);
            }
        });
        matchingConnHashes.sort(String::compareTo);
        return matchingConnHashes;
    }

    /**
     * Removes pool allocation tracking for a connection hash.
     *
     * @param connHash Connection hash
     */
    public void removeAllocation(String connHash) {
        poolAllocations.remove(connHash);
        pendingHealthyServers.remove(connHash);
        log.debug("Removed pool allocation for {}", connHash);
    }

    /**
     * Gets the default pool sizes with fallback to CommonConstants.
     */
    public static class DefaultPoolSizes {
        public final int maxPoolSize;
        public final int minIdle;

        public DefaultPoolSizes(int maxPoolSize, int minIdle) {
            this.maxPoolSize = maxPoolSize <= 0 ? CommonConstants.DEFAULT_MAXIMUM_POOL_SIZE : maxPoolSize;
            this.minIdle = minIdle < 0 ? CommonConstants.DEFAULT_MINIMUM_IDLE : minIdle;
        }
    }
}
