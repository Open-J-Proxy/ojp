package org.openjproxy.grpc.server.pool;

import lombok.extern.slf4j.Slf4j;
import org.openjproxy.constants.CommonConstants;

import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Manages multi-datasource configuration extracted from client properties.
 * This class handles the parsing of datasource-specific configuration and provides
 * unified access to pool settings.
 */
@Slf4j
public class DataSourceConfigurationManager {
    
    // Cache for parsed datasource configurations
    private static final ConcurrentMap<String, DataSourceConfiguration> configCache = new ConcurrentHashMap<>();
    
    /**
     * Configuration for a specific datasource
     */
    public static class DataSourceConfiguration {
        private final String dataSourceName;
        private final int maximumPoolSize;
        private final int minimumIdle;
        private final long idleTimeout;
        private final long maxLifetime;
        private final long connectionTimeout;
        
        public DataSourceConfiguration(String dataSourceName, Properties properties) {
            this.dataSourceName = dataSourceName;
            this.maximumPoolSize = getIntProperty(properties, CommonConstants.MAXIMUM_POOL_SIZE_PROPERTY, CommonConstants.DEFAULT_MAXIMUM_POOL_SIZE);
            this.minimumIdle = getIntProperty(properties, CommonConstants.MINIMUM_IDLE_PROPERTY, CommonConstants.DEFAULT_MINIMUM_IDLE);
            this.idleTimeout = getLongProperty(properties, CommonConstants.IDLE_TIMEOUT_PROPERTY, CommonConstants.DEFAULT_IDLE_TIMEOUT);
            this.maxLifetime = getLongProperty(properties, CommonConstants.MAX_LIFETIME_PROPERTY, CommonConstants.DEFAULT_MAX_LIFETIME);
            this.connectionTimeout = getLongProperty(properties, CommonConstants.CONNECTION_TIMEOUT_PROPERTY, CommonConstants.DEFAULT_CONNECTION_TIMEOUT);
        }
        
        // Getters
        public String getDataSourceName() { return dataSourceName; }
        public int getMaximumPoolSize() { return maximumPoolSize; }
        public int getMinimumIdle() { return minimumIdle; }
        public long getIdleTimeout() { return idleTimeout; }
        public long getMaxLifetime() { return maxLifetime; }
        public long getConnectionTimeout() { return connectionTimeout; }
        
        @Override
        public String toString() {
            return String.format("DataSourceConfiguration[%s: maxPool=%d, minIdle=%d, timeout=%d]", 
                    dataSourceName, maximumPoolSize, minimumIdle, connectionTimeout);
        }
    }
    
    /**
     * Gets or creates a DataSourceConfiguration from client properties.
     * 
     * @param clientProperties Properties sent from client, may include datasource name
     * @return DataSourceConfiguration with parsed settings
     */
    public static DataSourceConfiguration getConfiguration(Properties clientProperties) {
        // Extract datasource name from properties (set by Driver)
        String dataSourceName = clientProperties != null ? 
                clientProperties.getProperty(CommonConstants.DATASOURCE_NAME_PROPERTY, "default") : "default";
        
        // Create cache key that includes the properties hash to handle configuration changes
        String cacheKey = createCacheKey(dataSourceName, clientProperties);
        
        // Cache lookup with lazy initialization - creates new configuration only if not already cached
        return configCache.computeIfAbsent(cacheKey, k -> {
            DataSourceConfiguration config = new DataSourceConfiguration(dataSourceName, clientProperties);
            log.info("Created new DataSourceConfiguration: {}", config);
            return config;
        });
    }
    
    /**
     * Creates a cache key that includes datasource name and a hash of relevant properties.
     * The cache key is used to determine if a configuration has already been created and cached,
     * allowing for efficient reuse of configurations while detecting when property changes
     * require a new configuration to be created.
     */
    private static String createCacheKey(String dataSourceName, Properties properties) {
        if (properties == null) {
            return dataSourceName + ":defaults";
        }
        
        // Create a simple hash of the relevant properties to detect changes
        StringBuilder sb = new StringBuilder(dataSourceName).append(":");
        
        String[] keys = {
                CommonConstants.MAXIMUM_POOL_SIZE_PROPERTY,
                CommonConstants.MINIMUM_IDLE_PROPERTY, 
                CommonConstants.IDLE_TIMEOUT_PROPERTY,
                CommonConstants.MAX_LIFETIME_PROPERTY,
                CommonConstants.CONNECTION_TIMEOUT_PROPERTY
        };
        
        for (String key : keys) {
            String value = properties.getProperty(key, "");
            sb.append(key).append("=").append(value).append(";");
        }
        
        return sb.toString();
    }
    
    /**
     * Gets an integer property with a default value.
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
    
    /**
     * Clears the configuration cache. Useful for testing.
     */
    public static void clearCache() {
        configCache.clear();
        log.debug("Cleared DataSourceConfiguration cache");
    }
    
    /**
     * Gets the number of cached configurations. Useful for monitoring.
     */
    public static int getCacheSize() {
        return configCache.size();
    }
}