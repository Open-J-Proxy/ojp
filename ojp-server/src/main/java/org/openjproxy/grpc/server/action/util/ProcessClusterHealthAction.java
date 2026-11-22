package org.openjproxy.grpc.server.action.util;

import com.openjproxy.grpc.SessionInfo;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.grpc.server.AdmissionControlManager;
import org.openjproxy.grpc.server.ClusterHealthTracker;
import org.openjproxy.grpc.server.MultinodePoolCoordinator;
import org.openjproxy.grpc.server.ServerConfiguration;
import org.openjproxy.grpc.server.action.ActionContext;
import org.openjproxy.grpc.server.pool.ConnectionPoolConfigurer;
import org.openjproxy.grpc.server.pool.DatabaseConnectionBudgetManager;
import org.openjproxy.xa.pool.XATransactionRegistry;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Records client health reports as votes and rebalances multinode pools when the voted health changes.
 *
 * <p>Each report counts as one vote per server in the reporting client's URL list. Votes from every client are
 * combined per server (see {@link ClusterHealthTracker}), so clients with different server lists contribute to the
 * decision about the servers they share. Every multinode pool is then sized from the voted health of the servers in
 * its own endpoint list. A server is treated as DOWN only after a full vote window without any UP report about it;
 * a scheduled re-evaluation applies that decision even when no further requests arrive.</p>
 *
 * <p>Rebalancing can be disabled with {@code ojp.server.multinode.poolRebalancing.enabled=false}; pools then keep
 * the share calculated from the configured number of servers.</p>
 */
@Slf4j
public class ProcessClusterHealthAction {

    private static final ProcessClusterHealthAction INSTANCE = new ProcessClusterHealthAction();
    private static final long CONFIRMATION_CHECK_MARGIN_MILLIS = 50L;

    private final Object rebalanceLock = new Object();
    private volatile ScheduledExecutorService confirmationScheduler;

    private ProcessClusterHealthAction() {
        // Private constructor prevents external instantiation
    }

    public static ProcessClusterHealthAction getInstance() {
        return INSTANCE;
    }

    /**
     * Returns whether health reports may resize multinode pools on this server.
     */
    public static boolean isRebalancingEnabled(ActionContext context) {
        ServerConfiguration configuration = context.getServerConfiguration();
        return configuration == null || configuration.isMultinodePoolRebalancingEnabled();
    }

    /**
     * Processes cluster health from the client request and triggers pool rebalancing if needed.
     * This should be called for every request that includes SessionInfo with cluster health.
     */
    public void execute(ActionContext context, SessionInfo sessionInfo) {
        if (sessionInfo == null) {
            log.debug("[XA-REBALANCE-DEBUG] processClusterHealth: sessionInfo is null");
            return;
        }

        String clusterHealth = sessionInfo.getClusterHealth();
        String connHash = sessionInfo.getConnHash();

        if (clusterHealth.isEmpty() || connHash.isEmpty()) {
            log.debug("[XA-REBALANCE-DEBUG] Skipping cluster health processing: clusterHealth={}, connHash={}",
                    clusterHealth.isEmpty() ? "empty" : "present",
                    connHash.isEmpty() ? "empty" : "present");
            return;
        }

        if (!isRebalancingEnabled(context)) {
            log.debug("[XA-REBALANCE] Health-driven pool rebalancing is disabled; ignoring cluster health for {}",
                    connHash);
            return;
        }

        ClusterHealthTracker tracker = context.getClusterHealthTracker();
        tracker.recordVotes(clusterHealth);
        List<String> reportedServers = new ArrayList<>(tracker.parseClusterHealth(clusterHealth).keySet());
        rebalance(context, connHash, reportedServers);
        scheduleConfirmationCheck(context);
    }

    private void rebalance(ActionContext context, String reportingConnHash, List<String> reportedServers) {
        ClusterHealthTracker tracker = context.getClusterHealthTracker();
        MultinodePoolCoordinator coordinator = ConnectionPoolConfigurer.getPoolCoordinator();
        Set<String> connHashes = new TreeSet<>(coordinator.getAllocatedConnHashes());
        if (reportingConnHash != null) {
            connHashes.add(reportingConnHash);
        }

        for (String connHash : connHashes) {
            List<String> endpoints = endpointsOf(coordinator, connHash, reportingConnHash, reportedServers);
            if (endpoints.isEmpty()
                    || tracker.isHealthyServerCountApplied(connHash, tracker.countVotedHealthyServers(endpoints))) {
                continue;
            }
            synchronized (rebalanceLock) {
                int healthyServerCount = tracker.countVotedHealthyServers(endpoints);
                if (!tracker.hasHealthyServerCountChanged(connHash, healthyServerCount)) {
                    continue;
                }
                log.info("[XA-REBALANCE] Voted cluster health changed for {}, healthy servers: {} of {}, "
                        + "triggering pool rebalancing", connHash, healthyServerCount, endpoints.size());
                try {
                    applyHealthChange(context, connHash, healthyServerCount);
                } catch (RuntimeException e) {
                    // Resizing pools is a side effect of a health report and must never fail the request that
                    // carried it (this runs ahead of xaPrepare/xaCommit/xaRollback). Forget the applied count so
                    // the next request evaluates the change again instead of treating it as already applied.
                    log.error("[XA-REBALANCE] Failed to apply cluster health change for {}; "
                            + "will retry on the next request", connHash, e);
                    tracker.removeTracking(connHash);
                }
            }
        }
    }

    private List<String> endpointsOf(MultinodePoolCoordinator coordinator, String connHash, String reportingConnHash,
                                     List<String> reportedServers) {
        MultinodePoolCoordinator.PoolAllocation allocation = coordinator.getPoolAllocation(connHash);
        if (allocation != null && !allocation.getServerEndpoints().isEmpty()) {
            return allocation.getServerEndpoints();
        }
        // The pool has not been created yet; size it from the servers the reporting client knows about.
        return connHash.equals(reportingConnHash) ? reportedServers : List.of();
    }

    private void scheduleConfirmationCheck(ActionContext context) {
        ClusterHealthTracker tracker = context.getClusterHealthTracker();
        long delay = tracker.millisUntilNextDownConfirmation();
        if (delay < 0 || !tracker.claimConfirmationCheck(tracker.currentTimeMillis() + delay)) {
            return;
        }
        confirmationScheduler().schedule(() -> {
            try {
                rebalance(context, null, List.of());
                scheduleConfirmationCheck(context);
            } catch (RuntimeException e) {
                log.error("[XA-REBALANCE] Scheduled cluster health re-evaluation failed", e);
            }
        }, delay + CONFIRMATION_CHECK_MARGIN_MILLIS, TimeUnit.MILLISECONDS);
    }

    private ScheduledExecutorService confirmationScheduler() {
        ScheduledExecutorService scheduler = confirmationScheduler;
        if (scheduler == null) {
            synchronized (this) {
                scheduler = confirmationScheduler;
                if (scheduler == null) {
                    scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                        Thread thread = new Thread(runnable, "ojp-cluster-health-votes");
                        thread.setDaemon(true);
                        return thread;
                    });
                    confirmationScheduler = scheduler;
                }
            }
        }
        return scheduler;
    }

    private void applyHealthChange(ActionContext context, String connHash, int healthyServerCount) {
        DatabaseConnectionBudgetManager budgetManager = DatabaseConnectionBudgetManager.getInstance();
        updateBudgetServerCount(budgetManager, connHash, healthyServerCount);
        applyPoolHealthChange(context, budgetManager, connHash, healthyServerCount);
    }

    private void applyPoolHealthChange(ActionContext context, DatabaseConnectionBudgetManager budgetManager,
                                       String connHash, int healthyServerCount) {
        // Use the returned allocation directly to avoid a second lookup racing with pool creation.
        MultinodePoolCoordinator.PoolAllocation allocation =
                ConnectionPoolConfigurer.getPoolCoordinator().updateHealthyServers(connHash, healthyServerCount);

        // Apply pool size changes to non-XA HikariDataSource if present
        DataSource ds = context.getDatasourceMap().get(connHash);
        if (ds instanceof HikariDataSource hikariDataSource) {
            log.debug("[XA-REBALANCE-DEBUG] Applying size changes to HikariDataSource for {}", connHash);
            ConnectionPoolConfigurer.applyPoolSizeChanges(connHash, hikariDataSource, allocation, context);
        } else if (ds != null && allocation != null) {
            DatabaseConnectionBudgetManager.getInstance().updatePoolRequest(
                    "regular:" + connHash, allocation.getCurrentMaxPoolSize(), allocation.getCurrentMinIdle());
        } else {
            log.debug("[XA-REBALANCE-DEBUG] No HikariDataSource found for {}", connHash);
        }

        // Apply pool size changes to XA registry if present
        XATransactionRegistry xaRegistry = context.getXaRegistries().get(connHash);
        if (xaRegistry != null) {
            log.debug("[XA-REBALANCE-DEBUG] Found XA registry for {}, resizing", connHash);

            if (allocation == null) {
                log.warn("[XA-REBALANCE-DEBUG] No pool allocation found for {}", connHash);
                return;
            }

            int newMaxPoolSize = allocation.getCurrentMaxPoolSize();
            int newMinIdle = allocation.getCurrentMinIdle();
            if (!budgetManager.updatePoolRequest("xa:" + connHash, newMaxPoolSize, newMinIdle)) {
                newMaxPoolSize = budgetManager.getMaximumPoolSize("xa:" + connHash, newMaxPoolSize);
                newMinIdle = Math.min(newMinIdle, budgetManager.getMinimumIdle("xa:" + connHash, newMinIdle));
                newMinIdle = Math.min(newMinIdle, newMaxPoolSize);

                log.debug("[XA-REBALANCE-DEBUG] Resizing XA backend pool for {}: maxPoolSize={}, minIdle={}",
                        connHash, newMaxPoolSize, newMinIdle);

                xaRegistry.resizeBackendPool(newMaxPoolSize, newMinIdle);
                AdmissionControlManager admissionControlManager =
                        context.getAdmissionControlManagers().get(connHash);
                if (admissionControlManager != null) {
                    admissionControlManager.resizeCapacity(newMaxPoolSize);
                }
            }
        }
    }

    private void updateBudgetServerCount(DatabaseConnectionBudgetManager budgetManager, String connHash,
                                         int healthyServerCount) {
        if (healthyServerCount < 1) {
            // Same rule as ConnectionPoolConfigurer.getHealthyServerCount: a report without a single healthy
            // server is not a usable cluster size, and treating it as one would hand this server the whole budget.
            log.warn("[XA-REBALANCE] Cluster health for {} reports no healthy servers; keeping the current "
                    + "database budget allocation", connHash);
            return;
        }
        try {
            budgetManager.updateHealthyServerCount("regular:" + connHash, healthyServerCount);
            budgetManager.updateHealthyServerCount("xa:" + connHash, healthyServerCount);
            budgetManager.updateHealthyServerCountForPoolPrefix("replica:" + connHash + ":", healthyServerCount);
        } catch (DatabaseConnectionBudgetManager.InfeasibleBudgetException e) {
            // Retrying cannot help until the server count, the pools or the budget change, so keep the last
            // allocation that worked and carry on with the rest of the rebalance.
            log.error("[XA-REBALANCE] Database budget cannot be divided across {} healthy servers for {}; "
                    + "keeping the previous allocation: {}", healthyServerCount, connHash, e.getMessage());
        }
    }
}
