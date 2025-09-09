package org.openjdbcproxy.grpc.server.pool;

import com.openjdbcproxy.grpc.ConnectionDetails;
import com.zaxxer.hikari.HikariConfig;
import lombok.extern.slf4j.Slf4j;
import org.openjdbcproxy.constants.CommonConstants;

import java.util.Properties;

import static org.openjdbcproxy.grpc.SerializationHandler.deserialize;

/**
 * Utility class responsible for configuring HikariCP connection pools.
 * Extracted from StatementServiceImpl to reduce its responsibilities.
 */
@Slf4j
public class ConnectionPoolConfigurer {

    /**
     * Configures a HikariCP connection pool with connection details and client properties.
     *
     * @param config            The HikariConfig to configure
     * @param connectionDetails The connection details containing properties
     */
    public static void configureHikariPool(HikariConfig config, ConnectionDetails connectionDetails) {
        Properties clientProperties = extractClientProperties(connectionDetails);
        configureHikariPoolWithProperties(config, clientProperties, "default");
    }
    
    /**
     * Configures a HikariCP connection pool with specific properties and data source name.
     *
     * @param config            The HikariConfig to configure
     * @param clientProperties  The client properties to use
     * @param dataSourceName    The name of the data source for logging
     */
    public static void configureHikariPoolWithProperties(HikariConfig config, Properties clientProperties, String dataSourceName) {
        // Configure basic connection pool settings first
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

        // Configure HikariCP pool settings using client properties or defaults
        config.setMaximumPoolSize(getIntProperty(clientProperties, "ojp.connection.pool.maximumPoolSize", CommonConstants.DEFAULT_MAXIMUM_POOL_SIZE));
        config.setMinimumIdle(getIntProperty(clientProperties, "ojp.connection.pool.minimumIdle", CommonConstants.DEFAULT_MINIMUM_IDLE));
        config.setIdleTimeout(getLongProperty(clientProperties, "ojp.connection.pool.idleTimeout", CommonConstants.DEFAULT_IDLE_TIMEOUT));
        config.setMaxLifetime(getLongProperty(clientProperties, "ojp.connection.pool.maxLifetime", CommonConstants.DEFAULT_MAX_LIFETIME));
        config.setConnectionTimeout(getLongProperty(clientProperties, "ojp.connection.pool.connectionTimeout", CommonConstants.DEFAULT_CONNECTION_TIMEOUT));
        
        // Additional settings for high concurrency scenarios
        config.setLeakDetectionThreshold(60000); // 60 seconds - detect connection leaks
        config.setValidationTimeout(5000);       // 5 seconds - faster validation timeout
        config.setInitializationFailTimeout(10000); // 10 seconds - fail fast on initialization issues
        
        // Set pool name for better monitoring
        String poolName = "OJP-Pool-" + dataSourceName + "-" + System.currentTimeMillis();
        config.setPoolName(poolName);
        
        // Enable JMX for monitoring if not explicitly disabled
        config.setRegisterMbeans(true);

        log.info("HikariCP configured for data source '{}' with maximumPoolSize={}, minimumIdle={}, connectionTimeout={}ms, poolName={}",
                dataSourceName, config.getMaximumPoolSize(), config.getMinimumIdle(), config.getConnectionTimeout(), poolName);
    }

    /**
     * Extracts client properties from connection details.
     *
     * @param connectionDetails The connection details
     * @return Properties object or null if not available
     */
    public static Properties extractClientProperties(ConnectionDetails connectionDetails) {
        if (connectionDetails.getProperties().isEmpty()) {
            return null;
        }

        try {
            Properties clientProperties = deserialize(connectionDetails.getProperties().toByteArray(), Properties.class);
            log.info("Received {} properties from client for connection pool configuration", clientProperties.size());
            return clientProperties;
        } catch (Exception e) {
            log.warn("Failed to deserialize client properties, using defaults: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Gets an integer property with a default value.
     *
     * @param properties   The properties object
     * @param key         The property key
     * @param defaultValue The default value
     * @return The property value or default
     */
    private static int getIntProperty(Properties properties, String key, int defaultValue) {
        if (properties == null || !properties.containsKey(key)) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(properties.getProperty(key));
        } catch (NumberFormatException e) {
            log.warn("Invalid integer value for property '{}': {}, using default: {}", key, properties.getProperty(key), defaultValue);
            return defaultValue;
        }
    }

    /**
     * Gets a long property with a default value.
     *
     * @param properties   The properties object
     * @param key         The property key
     * @param defaultValue The default value
     * @return The property value or default
     */
    private static long getLongProperty(Properties properties, String key, long defaultValue) {
        if (properties == null || !properties.containsKey(key)) {
            return defaultValue;
        }
        try {
            return Long.parseLong(properties.getProperty(key));
        } catch (NumberFormatException e) {
            log.warn("Invalid long value for property '{}': {}, using default: {}", key, properties.getProperty(key), defaultValue);
            return defaultValue;
        }
    }
}