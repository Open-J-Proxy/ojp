package org.openjproxy.grpc.server;

import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Tracks cluster health status reported by clients and detects changes.
 *
 * <p>Every report a client sends is a set of votes, one per OJP server in that client's URL list. Votes from all
 * clients are combined per target server, regardless of which server list or pool the client uses. A server is
 * treated as DOWN only after it has been reported DOWN for a full vote window without any client reporting it UP
 * in the meantime. A single UP report resets that window, so any client that can still reach the server vetoes
 * the DOWN decision. UP reports take effect immediately, which means pools shrink back without delay when a
 * server recovers.</p>
 */
@Slf4j
public class ClusterHealthTracker {

    public static final long DEFAULT_VOTE_WINDOW_MILLIS = 10_000L;

    // Maps connHash to the last known cluster health string
    private final Map<String, String> lastKnownHealth = new ConcurrentHashMap<>();

    // Maps connHash to the healthy server count last applied to its pools
    private final Map<String, Integer> lastAppliedHealthyServers = new ConcurrentHashMap<>();

    // Maps normalized server address to the votes received about it
    private final Map<String, ServerVotes> votesByServer = new ConcurrentHashMap<>();

    private final long voteWindowMillis;
    private final LongSupplier clock;
    private volatile long scheduledConfirmationAt = Long.MIN_VALUE;

    private static final class ServerVotes {
        private long downSince = -1;
    }

    public ClusterHealthTracker() {
        this(DEFAULT_VOTE_WINDOW_MILLIS);
    }

    public ClusterHealthTracker(long voteWindowMillis) {
        this(voteWindowMillis, System::currentTimeMillis);
    }

    public ClusterHealthTracker(long voteWindowMillis, LongSupplier clock) {
        this.voteWindowMillis = Math.max(0, voteWindowMillis);
        this.clock = clock;
    }

    public long getVoteWindowMillis() {
        return voteWindowMillis;
    }

    /**
     * Parses a cluster health string into a map of server -> health status.
     * Format: "host1:port1(UP);host2:port2(DOWN);host3:port3(UP)"
     *
     * @param clusterHealth The cluster health string
     * @return Map of server address to health status (UP/DOWN)
     */
    public Map<String, String> parseClusterHealth(String clusterHealth) {
        Map<String, String> healthMap = new HashMap<>();

        if (clusterHealth == null || clusterHealth.isEmpty()) {
            return healthMap;
        }

        String[] serverEntries = clusterHealth.split(";");
        for (String entry : serverEntries) {
            entry = entry.trim();
            if (entry.isEmpty()) {
                continue;
            }

            // Parse format: "host:port(STATUS)"
            int statusStart = entry.lastIndexOf('(');
            int statusEnd = entry.lastIndexOf(')');

            if (statusStart > 0 && statusEnd > statusStart) {
                String serverAddress = entry.substring(0, statusStart);
                String status = entry.substring(statusStart + 1, statusEnd);
                healthMap.put(serverAddress, status);
            } else {
                log.warn("Invalid cluster health entry format: {}", entry);
            }
        }

        return healthMap;
    }

    /**
     * Counts the number of healthy servers (UP status) in the cluster health.
     *
     * @param clusterHealth The cluster health string
     * @return Number of servers with UP status
     */
    public int countHealthyServers(String clusterHealth) {
        Map<String, String> healthMap = parseClusterHealth(clusterHealth);
        return (int) healthMap.values().stream()
                .filter(status -> "UP".equalsIgnoreCase(status))
                .count();
    }

    /**
     * Checks if the cluster health has changed for a connection hash.
     * Returns true if this is a new connHash or if the health status has changed.
     * Updates the last known health if a change is detected.
     *
     * Thread-safe: Uses ConcurrentHashMap.compute() for atomic check-and-update
     * to prevent race conditions where multiple threads detect the same health change.
     *
     * @param connHash Connection hash
     * @param currentClusterHealth Current cluster health string
     * @return true if cluster health has changed, false otherwise
     */
    public boolean hasHealthChanged(String connHash, String currentClusterHealth) {
        if (connHash == null || connHash.isEmpty()) {
            return false;
        }

        // Normalize empty/null cluster health to empty string for comparison
        final String normalizedCurrent = currentClusterHealth == null ? "" : currentClusterHealth;

        // Use a holder to return the result from the compute function
        final boolean[] hasChanged = new boolean[1];

        // Atomic check-and-update using compute()
        lastKnownHealth.compute(connHash, (key, lastHealth) -> {
            if (lastHealth == null) {
                // First time seeing this connHash, store the health and ALWAYS trigger rebalancing
                // This ensures pools are properly configured even when servers restart (new tracker state)
                log.debug("First cluster health report for connHash {}: {} - triggering pool size verification",
                         connHash, normalizedCurrent);
                hasChanged[0] = true;
                return normalizedCurrent;
            }

            if (!lastHealth.equals(normalizedCurrent)) {
                // Health has changed
                log.info("Cluster health changed for connHash {}: {} -> {}",
                        connHash, lastHealth, normalizedCurrent);
                hasChanged[0] = true;
                return normalizedCurrent;
            }

            // No change
            hasChanged[0] = false;
            return lastHealth;
        });

        return hasChanged[0];
    }

    /**
     * Records the votes contained in one client health report.
     *
     * @param clusterHealth health report in the format {@code host1:port1(UP);host2:port2(DOWN)}
     */
    public void recordVotes(String clusterHealth) {
        long now = clock.getAsLong();
        parseClusterHealth(clusterHealth).forEach((server, status) -> {
            boolean up = "UP".equalsIgnoreCase(status);
            boolean down = "DOWN".equalsIgnoreCase(status);
            if (!up && !down) {
                return;
            }
            ServerVotes votes = votesByServer.computeIfAbsent(normalize(server), key -> new ServerVotes());
            synchronized (votes) {
                if (up && votes.downSince >= 0) {
                    log.info("Server {} reported UP; clearing DOWN votes recorded since {}", server, votes.downSince);
                    votes.downSince = -1;
                } else if (down && votes.downSince < 0) {
                    log.info("Server {} reported DOWN; it will be treated as down if no client reports it UP "
                            + "within {} ms", server, voteWindowMillis);
                    votes.downSince = now;
                }
            }
        });
    }

    /**
     * Returns true when the votes received so far confirm the server as DOWN.
     */
    public boolean isVotedDown(String server) {
        ServerVotes votes = votesByServer.get(normalize(server));
        if (votes == null) {
            return false;
        }
        synchronized (votes) {
            return votes.downSince >= 0 && clock.getAsLong() - votes.downSince >= voteWindowMillis;
        }
    }

    /**
     * Counts the servers of an endpoint list that are not confirmed DOWN by the votes. Servers without any vote
     * count as healthy, so a missing report never enlarges the share of the remaining servers.
     *
     * @return number of healthy servers, at least 1
     */
    public int countVotedHealthyServers(Collection<String> serverEndpoints) {
        if (serverEndpoints == null || serverEndpoints.isEmpty()) {
            return 1;
        }
        long healthy = serverEndpoints.stream().map(this::normalize).distinct()
                .filter(server -> !isVotedDown(server))
                .count();
        return (int) Math.max(1, healthy);
    }

    /**
     * Milliseconds until the earliest pending DOWN vote completes its window, or -1 when none is pending.
     */
    public long millisUntilNextDownConfirmation() {
        long now = clock.getAsLong();
        long next = -1;
        for (ServerVotes votes : votesByServer.values()) {
            synchronized (votes) {
                if (votes.downSince >= 0) {
                    long remaining = votes.downSince + voteWindowMillis - now;
                    if (remaining > 0 && (next < 0 || remaining < next)) {
                        next = remaining;
                    }
                }
            }
        }
        return next;
    }

    /**
     * Claims the right to schedule a re-evaluation at the given time, so each confirmation is scheduled once.
     */
    public synchronized boolean claimConfirmationCheck(long dueAtMillis) {
        if (scheduledConfirmationAt == dueAtMillis) {
            return false;
        }
        scheduledConfirmationAt = dueAtMillis;
        return true;
    }

    public long currentTimeMillis() {
        return clock.getAsLong();
    }

    /**
     * Returns true if the healthy server count differs from the one last applied for the connection hash and
     * records the new count. The first call for a connection hash always returns true.
     */
    public boolean hasHealthyServerCountChanged(String connHash, int healthyServerCount) {
        if (connHash == null || connHash.isEmpty()) {
            return false;
        }
        Integer previous = lastAppliedHealthyServers.put(connHash, healthyServerCount);
        if (previous == null || previous != healthyServerCount) {
            log.info("Voted healthy server count for {}: {} -> {}", connHash, previous, healthyServerCount);
            return true;
        }
        return false;
    }

    /**
     * Returns true if the given count is the one last applied for the connection hash.
     */
    public boolean isHealthyServerCountApplied(String connHash, int healthyServerCount) {
        Integer applied = lastAppliedHealthyServers.get(connHash);
        return applied != null && applied == healthyServerCount;
    }

    private String normalize(String server) {
        return server == null ? "" : server.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Removes tracking for a connection hash (e.g., when connection is closed).
     *
     * @param connHash Connection hash to remove
     */
    public void removeTracking(String connHash) {
        if (connHash != null) {
            lastKnownHealth.remove(connHash);
            lastAppliedHealthyServers.remove(connHash);
            log.debug("Removed cluster health tracking for connHash {}", connHash);
        }
    }

    /**
     * Gets the last known cluster health for a connection hash.
     *
     * @param connHash Connection hash
     * @return Last known cluster health string, or null if not tracked
     */
    public String getLastKnownHealth(String connHash) {
        return lastKnownHealth.get(connHash);
    }
}
