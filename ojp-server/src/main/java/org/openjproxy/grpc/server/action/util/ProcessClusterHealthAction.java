package org.openjproxy.grpc.server.action.util;

import com.openjproxy.grpc.SessionInfo;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.grpc.server.AdmissionControlManager;
import org.openjproxy.grpc.server.MultinodePoolCoordinator;
import org.openjproxy.grpc.server.action.ActionContext;
import org.openjproxy.grpc.server.pool.ConnectionPoolConfigurer;
import org.openjproxy.grpc.server.pool.DatabaseConnectionBudgetManager;
import org.openjproxy.xa.pool.XATransactionRegistry;

import javax.sql.DataSource;

/**
 * Action for processing cluster health changes and triggering pool rebalancing.
 * This is extracted from processClusterHealth method.
 * This action is implemented as a singleton for thread-safety and memory efficiency.
 */
@Slf4j
public class ProcessClusterHealthAction {

    private static final ProcessClusterHealthAction INSTANCE = new ProcessClusterHealthAction();

    private ProcessClusterHealthAction() {
        // Private constructor prevents external instantiation
    }

    public static ProcessClusterHealthAction getInstance() {
        return INSTANCE;
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

        log.debug("[XA-REBALANCE] processClusterHealth called: connHash={}, clusterHealth='{}', isXA={}, hasXARegistry={}",
                connHash, clusterHealth, sessionInfo.getIsXA(), context.getXaRegistries().containsKey(connHash));

        if (clusterHealth.isEmpty() || connHash.isEmpty()) {
            log.debug("[XA-REBALANCE-DEBUG] Skipping cluster health processing: clusterHealth={}, connHash={}",
                    clusterHealth.isEmpty() ? "empty" : "present",
                    connHash.isEmpty() ? "empty" : "present");
            return;
        }

        // Check if cluster health has changed
        boolean healthChanged = context.getClusterHealthTracker().hasHealthChanged(connHash, clusterHealth);

        log.debug("[XA-REBALANCE] Cluster health check for {}: changed={}, current health='{}', isXA={}",
                connHash, healthChanged, clusterHealth, sessionInfo.getIsXA());

        if (!healthChanged) {
            log.debug("[XA-REBALANCE-DEBUG] Cluster health unchanged for {}", connHash);
            return;
        }

        int healthyServerCount = context.getClusterHealthTracker().countHealthyServers(clusterHealth);
        log.info("[XA-REBALANCE] Cluster health changed for {}, healthy servers: {}, triggering pool rebalancing, isXA={}",
                connHash, healthyServerCount, sessionInfo.getIsXA());

        try {
            applyHealthChange(context, sessionInfo, connHash, healthyServerCount);
        } catch (RuntimeException e) {
            // Resizing pools is a side effect of a health report and must never fail the request that carried it
            // (this runs ahead of xaPrepare/xaCommit/xaRollback). Forget the stored health so the next request
            // evaluates the change again instead of treating it as already applied.
            log.error("[XA-REBALANCE] Failed to apply cluster health change for {}; will retry on the next request",
                    connHash, e);
            context.getClusterHealthTracker().removeTracking(connHash);
        }
    }

    private void applyHealthChange(ActionContext context, SessionInfo sessionInfo, String connHash,
                                   int healthyServerCount) {
        DatabaseConnectionBudgetManager budgetManager = DatabaseConnectionBudgetManager.getInstance();
        updateBudgetServerCount(budgetManager, connHash, healthyServerCount);

        // Update the pool coordinator with new healthy server count.
        // IMPORTANT: Use the returned allocation reference directly (not a second getPoolAllocation() call)
        // to prevent a race condition where a concurrent calculatePoolSizes() call can overwrite
        // the map entry between updateHealthyServers() and getPoolAllocation(), causing the wrong
        // (freshly-reset, un-updated) pool sizes to be applied during resize.
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
        } else if (sessionInfo.getIsXA()) {
            // Only log missing XA registry for actual XA connections
            log.debug("[XA-REBALANCE-DEBUG] No XA registry found for XA connection {}", connHash);
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
