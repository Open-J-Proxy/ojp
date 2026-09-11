package org.openjproxy.grpc.server.readwrite;

import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for managing primary and replica datasource mappings in read/write splitting.
 *
 * <p>This class maintains the mapping between primary datasources and their associated
 * read replicas. It provides thread-safe operations for registering datasources and
 * retrieving replica lists for routing decisions.
 *
 * <p>Thread Safety: All operations are thread-safe using ConcurrentHashMap.
 */
@Slf4j
public class ReadWriteDataSourceRegistry {

    // Map of primary datasource name → replica list
    private final Map<String, List<DataSource>> replicaMap = new ConcurrentHashMap<>();

    // Map of connection hash → primary datasource name (for session affinity)
    private final Map<String, String> primaryMappings = new ConcurrentHashMap<>();

    // Map of primary datasource name → sticky session timeout in seconds (0 = disabled)
    private final Map<String, Integer> stickyTimeoutMap = new ConcurrentHashMap<>();

    // Map of primary datasource name → replica selection strategy
    private final Map<String, ReadWriteConfiguration.ReplicaSelectionStrategy> strategyMap = new ConcurrentHashMap<>();

    // Map of primary datasource name → timestamp (epoch ms) of the last write (for sticky sessions)
    private final Map<String, Long> lastWriteTimestamps = new ConcurrentHashMap<>();

    /**
     * Registers a mapping from connection hash to primary datasource name.
     * This is used to associate client connections with their primary datasource.
     *
     * @param connectionHash the unique connection identifier
     * @param primaryName    the primary datasource name
     */
    public void registerPrimaryMapping(String connectionHash, String primaryName) {
        if (connectionHash == null || primaryName == null) {
            throw new IllegalArgumentException("connectionHash and primaryName must not be null");
        }
        primaryMappings.put(connectionHash, primaryName);
        log.debug("Registered primary mapping: {} -> {}", connectionHash, primaryName);
    }

    /**
     * Registers a replica datasource for a given primary.
     * Multiple replicas can be registered for the same primary.
     *
     * @param primaryName       the primary datasource name
     * @param replicaDataSource the replica datasource to register
     */
    public void registerReplica(String primaryName, DataSource replicaDataSource) {
        if (primaryName == null || replicaDataSource == null) {
            throw new IllegalArgumentException("primaryName and replicaDataSource must not be null");
        }
        replicaMap.computeIfAbsent(primaryName, k -> new ArrayList<>())
                  .add(replicaDataSource);
        log.debug("Registered replica for primary: {}", primaryName);
    }

    /**
     * Gets the list of replica datasources for a given primary.
     *
     * @param primaryName the primary datasource name
     * @return unmodifiable list of replica datasources, empty list if no replicas registered
     */
    public List<DataSource> getReplicas(String primaryName) {
        List<DataSource> replicas = replicaMap.get(primaryName);
        if (replicas == null) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(replicas);
    }

    /**
     * Gets the primary datasource name for a given connection hash.
     *
     * @param connectionHash the connection hash
     * @return the primary datasource name, or null if not found
     */
    public String getPrimaryName(String connectionHash) {
        return primaryMappings.get(connectionHash);
    }

    /**
     * Checks if a primary datasource has any replicas registered.
     *
     * @param primaryName the primary datasource name
     * @return true if at least one replica is registered, false otherwise
     */
    public boolean hasReplicas(String primaryName) {
        List<DataSource> replicas = replicaMap.get(primaryName);
        return replicas != null && !replicas.isEmpty();
    }

    /**
     * Gets the count of replicas for a given primary.
     *
     * @param primaryName the primary datasource name
     * @return the number of replicas, 0 if none registered
     */
    public int getReplicaCount(String primaryName) {
        List<DataSource> replicas = replicaMap.get(primaryName);
        return replicas != null ? replicas.size() : 0;
    }

    /**
     * Removes all registered replicas for a given primary.
     *
     * @param primaryName the primary datasource name
     */
    public void clearReplicas(String primaryName) {
        replicaMap.remove(primaryName);
        log.debug("Cleared all replicas for primary: {}", primaryName);
    }

    /**
     * Removes a primary mapping for a connection hash.
     *
     * @param connectionHash the connection hash
     */
    public void removePrimaryMapping(String connectionHash) {
        primaryMappings.remove(connectionHash);
        log.debug("Removed primary mapping for connection: {}", connectionHash);
    }

    /**
     * Registers the sticky session timeout (in seconds) for a given primary datasource.
     * A value of 0 disables sticky session behaviour.
     *
     * @param primaryName          the primary datasource name
     * @param stickyTimeoutSeconds the duration in seconds to route reads to primary after a write
     */
    public void registerStickyTimeout(String primaryName, int stickyTimeoutSeconds) {
        if (primaryName == null) {
            throw new IllegalArgumentException("primaryName must not be null");
        }
        stickyTimeoutMap.put(primaryName, stickyTimeoutSeconds);
        log.debug("Registered sticky session timeout for primary '{}': {}s", primaryName, stickyTimeoutSeconds);
    }

    /**
     * Returns the sticky session timeout in seconds for the given primary datasource.
     * Returns 0 if no timeout has been registered (sticky sessions disabled).
     *
     * @param primaryName the primary datasource name
     * @return the sticky session timeout in seconds, or 0 if not configured
     */
    public int getStickySessionSeconds(String primaryName) {
        return stickyTimeoutMap.getOrDefault(primaryName, 0);
    }

    /**
     * Registers the replica selection strategy for a given primary datasource.
     *
     * @param primaryName the primary datasource name
     * @param strategy    the strategy to use when selecting a replica
     */
    public void registerStrategy(String primaryName,
                                  ReadWriteConfiguration.ReplicaSelectionStrategy strategy) {
        if (primaryName == null || strategy == null) {
            throw new IllegalArgumentException("primaryName and strategy must not be null");
        }
        strategyMap.put(primaryName, strategy);
        log.debug("Registered replica selection strategy for primary '{}': {}", primaryName, strategy);
    }

    /**
     * Returns the replica selection strategy for the given primary datasource.
     * Defaults to {@link ReadWriteConfiguration.ReplicaSelectionStrategy#ROUND_ROBIN}
     * if none has been registered.
     *
     * @param primaryName the primary datasource name
     * @return the configured strategy, or {@code ROUND_ROBIN} if not set
     */
    public ReadWriteConfiguration.ReplicaSelectionStrategy getStrategy(String primaryName) {
        return strategyMap.getOrDefault(primaryName,
                ReadWriteConfiguration.ReplicaSelectionStrategy.ROUND_ROBIN);
    }

    /**
     * Records that a write operation has just been performed on the given primary.
     * This timestamp is used to determine whether the sticky-session window is still active.
     *
     * @param primaryName the primary datasource name
     */
    public void markWrite(String primaryName) {
        if (primaryName == null) {
            return;
        }
        lastWriteTimestamps.put(primaryName, System.currentTimeMillis());
        log.debug("[RW-SPLIT] markWrite: sticky session started for primary='{}', timeout={}s",
                primaryName, stickyTimeoutMap.getOrDefault(primaryName, 0));
    }

    /**
     * Returns {@code true} when a sticky-session window is currently active for the
     * given primary.  A sticky window is active when all of the following hold:
     * <ol>
     *   <li>A sticky timeout greater than zero has been registered.</li>
     *   <li>A write has occurred within the last {@code stickyTimeoutSeconds} seconds.</li>
     * </ol>
     *
     * @param primaryName the primary datasource name
     * @return {@code true} if reads should be routed to the primary (sticky), {@code false}
     *         if they may be routed to a replica
     */
    public boolean isStickyActive(String primaryName) {
        if (primaryName == null) {
            return false;
        }
        int timeoutSeconds = stickyTimeoutMap.getOrDefault(primaryName, 0);
        if (timeoutSeconds <= 0) {
            log.debug("[RW-SPLIT] isStickyActive: primary='{}', timeoutSeconds={} (disabled), sticky=false",
                    primaryName, timeoutSeconds);
            return false;
        }
        Long lastWrite = lastWriteTimestamps.get(primaryName);
        if (lastWrite == null) {
            log.debug("[RW-SPLIT] isStickyActive: primary='{}', no write timestamp recorded, sticky=false", primaryName);
            return false;
        }
        long elapsed = System.currentTimeMillis() - lastWrite;
        boolean active = elapsed < (long) timeoutSeconds * 1000;
        log.debug("[RW-SPLIT] isStickyActive: primary='{}', elapsedMs={}, timeoutMs={}, sticky={}",
                primaryName, elapsed, (long) timeoutSeconds * 1000, active);
        return active;
    }

    /**
     * Clears all registered datasources and mappings.
     * This is primarily for testing and cleanup purposes.
     */
    public void clear() {
        replicaMap.clear();
        primaryMappings.clear();
        stickyTimeoutMap.clear();
        strategyMap.clear();
        lastWriteTimestamps.clear();
        log.debug("Cleared all registry data");
    }
}
