package org.openjproxy.grpc.server.readwrite;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Selects a replica {@link DataSource} from the list of registered replicas
 * using the configured {@link ReadWriteConfiguration.ReplicaSelectionStrategy}.
 *
 * <p>Supported strategies:
 * <ul>
 *   <li>{@link ReadWriteConfiguration.ReplicaSelectionStrategy#ROUND_ROBIN} —
 *       cycles through replicas in order (default)</li>
 *   <li>{@link ReadWriteConfiguration.ReplicaSelectionStrategy#RANDOM} —
 *       picks a replica at random</li>
 *   <li>{@link ReadWriteConfiguration.ReplicaSelectionStrategy#LEAST_CONNECTIONS} —
 *       falls back to ROUND_ROBIN in Phase 2; metrics-based selection is planned
 *       for a future phase</li>
 * </ul>
 *
 * <p>Thread Safety: this class is thread-safe.  Round-robin counters use
 * {@link AtomicInteger} and are stored in a {@link ConcurrentHashMap}.
 */
public class ReadReplicaSelector {

    private final Map<String, AtomicInteger> roundRobinCounters = new ConcurrentHashMap<>();

    /**
     * Selects a replica {@link DataSource} using the given strategy.
     *
     * @param primaryName the name of the primary datasource (used as a key for
     *                    per-primary counters)
     * @param replicas    the list of available replica datasources; must not be
     *                    {@code null}
     * @param strategy    the replica selection strategy
     * @return the selected {@link DataSource}, or {@code null} if
     *         {@code replicas} is empty
     */
    public DataSource select(String primaryName, List<DataSource> replicas,
                             ReadWriteConfiguration.ReplicaSelectionStrategy strategy) {
        if (replicas.isEmpty()) {
            return null;
        }
        return switch (strategy) {
            case RANDOM -> replicas.get(ThreadLocalRandom.current().nextInt(replicas.size()));
            // LEAST_CONNECTIONS falls back to ROUND_ROBIN until pool metrics are available
            default -> roundRobin(primaryName, replicas);
        };
    }

    private DataSource roundRobin(String primaryName, List<DataSource> replicas) {
        AtomicInteger counter = roundRobinCounters
                .computeIfAbsent(primaryName, k -> new AtomicInteger(0));
        // Use bitwise AND to avoid negative index when counter wraps past Integer.MAX_VALUE
        int idx = (counter.getAndIncrement() & Integer.MAX_VALUE) % replicas.size();
        return replicas.get(idx);
    }
}
