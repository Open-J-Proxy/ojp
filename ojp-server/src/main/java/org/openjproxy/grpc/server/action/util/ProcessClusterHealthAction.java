package org.openjproxy.grpc.server.action.util;

import com.openjproxy.grpc.SessionInfo;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.grpc.server.MultinodePoolCoordinator;
import org.openjproxy.grpc.server.action.ActionContext;
import org.openjproxy.grpc.server.pool.ConnectionPoolConfigurer;
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
            ConnectionPoolConfigurer.applyPoolSizeChanges(connHash, hikariDataSource);
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

            log.debug("[XA-REBALANCE-DEBUG] Resizing XA backend pool for {}: maxPoolSize={}, minIdle={}",
                    connHash, newMaxPoolSize, newMinIdle);

            xaRegistry.resizeBackendPool(newMaxPoolSize, newMinIdle);
        } else if (sessionInfo.getIsXA()) {
            // Only log missing XA registry for actual XA connections
            log.debug("[XA-REBALANCE-DEBUG] No XA registry found for XA connection {}", connHash);
        }
    }
}
