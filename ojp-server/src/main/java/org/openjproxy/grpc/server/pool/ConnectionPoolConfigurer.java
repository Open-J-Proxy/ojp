package org.openjproxy.grpc.server.pool;

import com.openjproxy.grpc.ConnectionDetails;
import com.zaxxer.hikari.HikariConfig;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.constants.CommonConstants;

import java.util.Properties;

import static org.openjproxy.grpc.SerializationHandler.deserialize;

/**
 * Utility class responsible for configuring HikariCP connection pools.
 * Extracted from StatementServiceImpl to reduce its responsibilities.
 * Updated to support multi-datasource configuration.
 */
@Slf4j
public class ConnectionPoolConfigurer {

    /**
     * Configures a HikariCP connection pool with connection details and client properties.
     * Now supports multi-datasource configuration through DataSourceConfigurationManager.
     *
     * @param config            The HikariConfig to configure
     * @param connectionDetails The connection details containing properties
     * @return The datasource configuration used for this pool
     */
    public static DataSourceConfigurationManager.DataSourceConfiguration configureHikariPool(HikariConfig config, ConnectionDetails connectionDetails) {
        Properties clientProperties = extractClientProperties(connectionDetails);
        
        // Get datasource-specific configuration
        DataSourceConfigurationManager.DataSourceConfiguration dsConfig = 
                DataSourceConfigurationManager.getConfiguration(clientProperties);

        // Configure basic connection pool settings first
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

        // Configure HikariCP pool settings using datasource-specific configuration
        config.setMaximumPoolSize(dsConfig.getMaximumPoolSize());
        config.setMinimumIdle(dsConfig.getMinimumIdle());
        config.setIdleTimeout(dsConfig.getIdleTimeout());
        config.setMaxLifetime(dsConfig.getMaxLifetime());
        config.setConnectionTimeout(dsConfig.getConnectionTimeout());
        
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
     * Extracts client properties from connection details.
     *
     * @param connectionDetails The connection details
     * @return Properties object or null if not available
     */
    private static Properties extractClientProperties(ConnectionDetails connectionDetails) {
        if (connectionDetails.getProperties().isEmpty()) {
            return null;
        }

        try {
            Properties clientProperties = deserialize(connectionDetails.getProperties().toByteArray(), Properties.class);
            log.debug("Received {} properties from client for connection pool configuration", clientProperties.size());
            return clientProperties;
        } catch (Exception e) {
            log.warn("Failed to deserialize client properties, using defaults: {}", e.getMessage());
            return null;
        }
    }
}