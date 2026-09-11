package org.openjproxy.grpc.server.pool;

import com.openjproxy.grpc.ConnectionDetails;
import com.zaxxer.hikari.HikariConfig;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.constants.CommonConstants;
import org.openjproxy.grpc.ProtoConverter;
import org.openjproxy.grpc.server.MultinodePoolCoordinator;

import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Utility class responsible for configuring HikariCP connection pools.
 * Extracted from StatementServiceImpl to reduce its responsibilities.
 * Updated to support multi-datasource configuration and multinode pool coordination.
 */
@Slf4j
public class ConnectionPoolConfigurer {

    private static final MultinodePoolCoordinator POOL_COORDINATOR = new MultinodePoolCoordinator();

    /**
     * Configures a HikariCP connection pool with connection details and client properties.
     * Now supports multi-datasource configuration and multinode pool coordination.
     *
     * @param config            The HikariConfig to configure
     * @param connectionDetails The connection details containing properties
     * @param connHash          Connection hash for tracking pool allocations
     * @return The datasource configuration used for this pool
     */
    public static DataSourceConfigurationManager.DataSourceConfiguration configureHikariPool(
            HikariConfig config, ConnectionDetails connectionDetails, String connHash) {
        Properties clientProperties = extractClientProperties(connectionDetails);

        // Get datasource-specific configuration
        DataSourceConfigurationManager.DataSourceConfiguration dsConfig =
                DataSourceConfigurationManager.getConfiguration(clientProperties);

        // Configure basic connection pool settings first
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

        // Check if multinode configuration is present
        List<String> serverEndpoints = connectionDetails.getServerEndpointsList();
        int maxPoolSize = dsConfig.getMaximumPoolSize();
        int minIdle = dsConfig.getMinimumIdle();

        if (serverEndpoints != null && !serverEndpoints.isEmpty()) {
            // Multinode: calculate divided pool sizes
            MultinodePoolCoordinator.PoolAllocation allocation =
                    POOL_COORDINATOR.calculatePoolSizes(connHash, maxPoolSize, minIdle, serverEndpoints);

            maxPoolSize = allocation.getCurrentMaxPoolSize();
            minIdle = allocation.getCurrentMinIdle();

            log.info("Multinode pool coordination enabled for {}: {} servers, divided pool sizes: max={}, min={}",
                    connHash, serverEndpoints.size(), maxPoolSize, minIdle);
        }

        // Configure HikariCP pool settings
        config.setMaximumPoolSize(maxPoolSize);
        config.setMinimumIdle(minIdle);
        config.setIdleTimeout(dsConfig.getIdleTimeout());
        config.setMaxLifetime(dsConfig.getMaxLifetime());
        config.setConnectionTimeout(CommonConstants.FAIL_FAST_POOL_CONNECTION_TIMEOUT_MS);

        // Additional settings for high concurrency scenarios
        config.setLeakDetectionThreshold(60000); // 60 seconds - detect connection leaks
        config.setValidationTimeout(5000);       // 5 seconds - faster validation timeout
        config.setInitializationFailTimeout(10000); // 10 seconds - fail fast on initialization issues

        // Set pool name for better monitoring - include dataSource name
        String poolName = "OJP-Pool-" + dsConfig.getDataSourceName() + "-" + System.currentTimeMillis();
        config.setPoolName(poolName);

        // Enable JMX for monitoring if not explicitly disabled
        config.setRegisterMbeans(true);

        log.info("HikariCP configured for dataSource '{}' with maximumPoolSize={}, minimumIdle={}, connectionTimeout={}ms, poolName={}",
                dsConfig.getDataSourceName(), config.getMaximumPoolSize(), config.getMinimumIdle(),
                config.getConnectionTimeout(), poolName);

        return dsConfig;
    }

    /**
     * Legacy method for backward compatibility - calls new method with null connHash.
     */
    public static DataSourceConfigurationManager.DataSourceConfiguration configureHikariPool(
            HikariConfig config, ConnectionDetails connectionDetails) {
        return configureHikariPool(config, connectionDetails, null);
    }

    /**
     * Gets the multinode pool coordinator instance.
     */
    public static MultinodePoolCoordinator getPoolCoordinator() {
        return POOL_COORDINATOR;
    }

    /**
     * Processes cluster health from client and triggers pool rebalancing if health has changed.
     * This should be called on each request that includes cluster health information.
     *
     * @param connHash Connection hash
     * @param clusterHealth Cluster health string from client
     * @param clusterHealthTracker Tracker to detect health changes
     * @param dataSource HikariDataSource to apply pool size changes to (can be null)
     */
    public static void processClusterHealth(String connHash, String clusterHealth,
                                           org.openjproxy.grpc.server.ClusterHealthTracker clusterHealthTracker,
                                           com.zaxxer.hikari.HikariDataSource dataSource) {
        if (connHash == null || connHash.isEmpty() || clusterHealth == null || clusterHealth.isEmpty()) {
            return;
        }

        // Check if cluster health has changed
        boolean healthChanged = clusterHealthTracker.hasHealthChanged(connHash, clusterHealth);

        if (healthChanged) {
            // Count healthy servers
            int healthyServerCount = clusterHealthTracker.countHealthyServers(clusterHealth);

            log.info("Cluster health changed for {}, healthy servers: {}, triggering pool rebalancing",
                    connHash, healthyServerCount);

            // Update the pool coordinator with new healthy server count.
            // Use the returned allocation to avoid a race condition where a concurrent
            // calculatePoolSizes() call could overwrite the map entry before applyPoolSizeChanges
            // reads it back via getPoolAllocation().
            POOL_COORDINATOR.updateHealthyServers(connHash, healthyServerCount);

            // Apply new pool sizes to existing HikariDataSource if provided
            if (dataSource != null) {
                applyPoolSizeChanges(connHash, dataSource);
            }
        }
    }

    /**
     * Applies current pool allocation sizes to an existing HikariDataSource.
     * HikariCP supports dynamic resizing of pool sizes at runtime.
     *
     * Important notes on HikariCP pool reduction:
     * - When reducing pool size, setMinimumIdle() must be called BEFORE setMaximumPoolSize()
     *   to avoid validation errors (minIdle must be <= maxPoolSize)
     * - HikariCP's softEvictConnections() helps release idle connections above the new minimumIdle
     * - Connections are evicted gradually as they become idle, not immediately
     *
     * @param connHash Connection hash to look up pool allocation
     * @param dataSource HikariDataSource to update
     */
    public static void applyPoolSizeChanges(String connHash, com.zaxxer.hikari.HikariDataSource dataSource) {
        MultinodePoolCoordinator.PoolAllocation allocation = POOL_COORDINATOR.getPoolAllocation(connHash);

        if (allocation == null) {
            log.debug("No pool allocation found for {}, skipping pool resize", connHash);
            return;
        }

        int newMaxPoolSize = allocation.getCurrentMaxPoolSize();
        int newMinIdle = allocation.getCurrentMinIdle();

        // Get current sizes for logging
        int currentMaxPoolSize = dataSource.getMaximumPoolSize();
        int currentMinIdle = dataSource.getMinimumIdle();

        if (currentMaxPoolSize != newMaxPoolSize || currentMinIdle != newMinIdle) {
            log.info("Resizing HikariCP pool for {}: maxPoolSize {} -> {}, minIdle {} -> {}",
                    connHash, currentMaxPoolSize, newMaxPoolSize, currentMinIdle, newMinIdle);

            // Determine if we're increasing or decreasing pool sizes
            boolean isIncreasing = (newMaxPoolSize > currentMaxPoolSize) || (newMinIdle > currentMinIdle);
            boolean isDecreasing = (newMaxPoolSize < currentMaxPoolSize) || (newMinIdle < currentMinIdle);

            if (isDecreasing) {
                // When reducing: set minIdle first, then maxPoolSize
                // This avoids HikariCP validation errors (minIdle <= maxPoolSize)
                dataSource.setMinimumIdle(newMinIdle);
                dataSource.setMaximumPoolSize(newMaxPoolSize);

                // Trigger soft eviction to release idle connections above the new minimum
                // This helps reduce the pool size more quickly by marking excess connections for closure
                dataSource.getHikariPoolMXBean().softEvictConnections();

                log.info("Successfully resized (decreased) HikariCP pool for {}. Idle connections above {} will be evicted.",
                        connHash, newMinIdle);
            } else if (isIncreasing) {
                // When increasing: set maxPoolSize first, then minIdle
                // This allows the pool to grow before setting the new minimum
                dataSource.setMaximumPoolSize(newMaxPoolSize);
                dataSource.setMinimumIdle(newMinIdle);

                log.info("Successfully resized (increased) HikariCP pool for {}", connHash);
            } else {
                // Mixed case (one increasing, one decreasing) - be conservative
                // Set minIdle first to avoid violations
                dataSource.setMinimumIdle(newMinIdle);
                dataSource.setMaximumPoolSize(newMaxPoolSize);

                log.info("Successfully resized HikariCP pool for {}", connHash);
            }
        } else {
            log.debug("Pool sizes unchanged for {}, no resize needed", connHash);
        }
    }

    /**
     * Extracts client properties from connection details.
     *
     * @param connectionDetails The connection details
     * @return Properties object or null if not available
     */
    public static Properties extractClientProperties(ConnectionDetails connectionDetails) {
        if (connectionDetails.getPropertiesList().isEmpty()) {
            return null;
        }

        try {
            Map<String, Object> propertiesMap = ProtoConverter.propertiesFromProto(connectionDetails.getPropertiesList());
            Properties clientProperties = new Properties();
            clientProperties.putAll(propertiesMap);
            log.debug("Received {} properties from client for connection pool configuration", clientProperties.size());
            return clientProperties;
        } catch (Exception e) {
            log.warn("Failed to deserialize client properties, using defaults: {}", e.getMessage());
            return null;
        }
    }
}
