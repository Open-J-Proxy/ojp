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
    private static final ConcurrentMap<String, DataSourceConfiguration> CONFIG_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, XADataSourceConfiguration> XA_CONFIG_CACHE = new ConcurrentHashMap<>();

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
        private final boolean poolEnabled;
        private final Integer defaultTransactionIsolation;
        private final long leakDetectionThreshold;

        public DataSourceConfiguration(String dataSourceName, Properties properties) {
            this.dataSourceName = dataSourceName;
            this.maximumPoolSize = getIntProperty(properties, CommonConstants.MAXIMUM_POOL_SIZE_PROPERTY, CommonConstants.DEFAULT_MAXIMUM_POOL_SIZE);
            this.minimumIdle = getIntProperty(properties, CommonConstants.MINIMUM_IDLE_PROPERTY, CommonConstants.DEFAULT_MINIMUM_IDLE);
            this.idleTimeout = getLongProperty(properties, CommonConstants.IDLE_TIMEOUT_PROPERTY, CommonConstants.DEFAULT_IDLE_TIMEOUT);
            this.maxLifetime = getLongProperty(properties, CommonConstants.MAX_LIFETIME_PROPERTY, CommonConstants.DEFAULT_MAX_LIFETIME);
            this.connectionTimeout = getLongProperty(properties, CommonConstants.CONNECTION_TIMEOUT_PROPERTY, CommonConstants.DEFAULT_CONNECTION_TIMEOUT);
            this.poolEnabled = getBooleanProperty(properties, CommonConstants.POOL_ENABLED_PROPERTY, true);
            this.defaultTransactionIsolation = getTransactionIsolationProperty(properties, CommonConstants.DEFAULT_TRANSACTION_ISOLATION_PROPERTY);
            this.leakDetectionThreshold = getLongProperty(properties, CommonConstants.LEAK_DETECTION_THRESHOLD_PROPERTY, CommonConstants.DEFAULT_LEAK_DETECTION_THRESHOLD);
        }

        // Getters
        public String getDataSourceName() { return dataSourceName; }
        public int getMaximumPoolSize() { return maximumPoolSize; }
        public int getMinimumIdle() { return minimumIdle; }
        public long getIdleTimeout() { return idleTimeout; }
        public long getMaxLifetime() { return maxLifetime; }
        public long getConnectionTimeout() { return connectionTimeout; }
        public boolean isPoolEnabled() { return poolEnabled; }
        public Integer getDefaultTransactionIsolation() { return defaultTransactionIsolation; }
        public long getLeakDetectionThreshold() { return leakDetectionThreshold; }

        @Override
        public String toString() {
            return String.format("DataSourceConfiguration[%s: maxPool=%d, minIdle=%d, timeout=%d, poolEnabled=%b, txIsolation=%s, leakDetectionThreshold=%d]",
                    dataSourceName, maximumPoolSize, minimumIdle, connectionTimeout, poolEnabled,
                    defaultTransactionIsolation != null ? defaultTransactionIsolation : "auto-detect", leakDetectionThreshold);
        }
    }

    /**
     * Configuration for XA-specific datasource (separate from non-XA)
     */
    public static class XADataSourceConfiguration {
        private final String dataSourceName;
        private final int maximumPoolSize;
        private final int minimumIdle;
        private final long idleTimeout;
        private final long maxLifetime;
        private final long connectionTimeout;
        private final boolean poolEnabled;
        private final long timeBetweenEvictionRuns;
        private final int numTestsPerEvictionRun;
        private final long softMinEvictableIdleTime;
        private final Integer defaultTransactionIsolation;

        public XADataSourceConfiguration(String dataSourceName, Properties properties) {
            this.dataSourceName = dataSourceName;
            // Use XA-specific properties with XA defaults (no fallback to non-XA properties)
            this.maximumPoolSize = getIntProperty(properties, CommonConstants.XA_MAXIMUM_POOL_SIZE_PROPERTY,
                    CommonConstants.DEFAULT_XA_MAXIMUM_POOL_SIZE);
            this.minimumIdle = getIntProperty(properties, CommonConstants.XA_MINIMUM_IDLE_PROPERTY,
                    CommonConstants.DEFAULT_XA_MINIMUM_IDLE);
            this.idleTimeout = getLongProperty(properties, CommonConstants.XA_IDLE_TIMEOUT_PROPERTY,
                    CommonConstants.DEFAULT_IDLE_TIMEOUT);
            this.maxLifetime = getLongProperty(properties, CommonConstants.XA_MAX_LIFETIME_PROPERTY,
                    CommonConstants.DEFAULT_MAX_LIFETIME);
            this.connectionTimeout = getLongProperty(properties, CommonConstants.XA_CONNECTION_TIMEOUT_PROPERTY,
                    CommonConstants.DEFAULT_CONNECTION_TIMEOUT);
            this.poolEnabled = getBooleanProperty(properties, CommonConstants.XA_POOL_ENABLED_PROPERTY, true);

            // Evictor configuration (XA-specific only, no fallback to non-XA)
            this.timeBetweenEvictionRuns = getLongProperty(properties, CommonConstants.XA_TIME_BETWEEN_EVICTION_RUNS_PROPERTY,
                    CommonConstants.DEFAULT_XA_TIME_BETWEEN_EVICTION_RUNS_MS);
            this.numTestsPerEvictionRun = getIntProperty(properties, CommonConstants.XA_NUM_TESTS_PER_EVICTION_RUN_PROPERTY,
                    CommonConstants.DEFAULT_XA_NUM_TESTS_PER_EVICTION_RUN);
            this.softMinEvictableIdleTime = getLongProperty(properties, CommonConstants.XA_SOFT_MIN_EVICTABLE_IDLE_TIME_PROPERTY,
                    CommonConstants.DEFAULT_XA_SOFT_MIN_EVICTABLE_IDLE_TIME_MS);

            this.defaultTransactionIsolation = getTransactionIsolationProperty(properties, CommonConstants.XA_DEFAULT_TRANSACTION_ISOLATION_PROPERTY);
        }

        // Getters
        public String getDataSourceName() { return dataSourceName; }
        public int getMaximumPoolSize() { return maximumPoolSize; }
        public int getMinimumIdle() { return minimumIdle; }
        public long getIdleTimeout() { return idleTimeout; }
        public long getMaxLifetime() { return maxLifetime; }
        public long getConnectionTimeout() { return connectionTimeout; }
        public boolean isPoolEnabled() { return poolEnabled; }
        public long getTimeBetweenEvictionRuns() { return timeBetweenEvictionRuns; }
        public int getNumTestsPerEvictionRun() { return numTestsPerEvictionRun; }
        public long getSoftMinEvictableIdleTime() { return softMinEvictableIdleTime; }
        public Integer getDefaultTransactionIsolation() { return defaultTransactionIsolation; }

        @Override
        public String toString() {
            return String.format("XADataSourceConfiguration[%s: maxPool=%d, minIdle=%d, timeout=%d, poolEnabled=%b, evictionRuns=%d, testsPerRun=%d, softMinEvictable=%d, txIsolation=%s]",
                    dataSourceName, maximumPoolSize, minimumIdle, connectionTimeout, poolEnabled,
                    timeBetweenEvictionRuns, numTestsPerEvictionRun, softMinEvictableIdleTime,
                    defaultTransactionIsolation != null ? defaultTransactionIsolation : "auto-detect");
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
        String cacheKey = createCacheKey(dataSourceName, clientProperties, false);

        // Cache lookup with lazy initialization - creates new configuration only if not already cached
        return CONFIG_CACHE.computeIfAbsent(cacheKey, k -> {
            DataSourceConfiguration config = new DataSourceConfiguration(dataSourceName, clientProperties);
            log.info("Created new DataSourceConfiguration: {}", config);
            return config;
        });
    }

    /**
     * Gets or creates an XADataSourceConfiguration from client properties.
     *
     * @param clientProperties Properties sent from client, may include datasource name
     * @return XADataSourceConfiguration with parsed settings (XA-specific or fallback to regular)
     */
    public static XADataSourceConfiguration getXAConfiguration(Properties clientProperties) {
        // Extract datasource name from properties (set by Driver)
        String dataSourceName = clientProperties != null ?
                clientProperties.getProperty(CommonConstants.DATASOURCE_NAME_PROPERTY, "default") : "default";

        // Create cache key that includes the properties hash to handle configuration changes
        String cacheKey = createCacheKey(dataSourceName, clientProperties, true);

        // Cache lookup with lazy initialization (using separate XA cache)
        return XA_CONFIG_CACHE.computeIfAbsent(cacheKey, k -> {
            XADataSourceConfiguration config = new XADataSourceConfiguration(dataSourceName, clientProperties);
            log.info("Created new XADataSourceConfiguration: {}", config);
            return config;
        });
    }

    /**
     * Creates a cache key that includes datasource name and a hash of relevant properties.
     * The cache key is used to determine if a configuration has already been created and cached,
     * allowing for efficient reuse of configurations while detecting when property changes
     * require a new configuration to be created.
     */
    private static String createCacheKey(String dataSourceName, Properties properties, boolean isXA) {
        if (properties == null) {
            return dataSourceName + ":" + (isXA ? "xa:" : "") + "defaults";
        }

        // Create a simple hash of the relevant properties to detect changes
        StringBuilder sb = new StringBuilder(dataSourceName).append(":").append(isXA ? "xa:" : "");

        String[] keys;
        if (isXA) {
            keys = new String[] {
                    CommonConstants.XA_MAXIMUM_POOL_SIZE_PROPERTY,
                    CommonConstants.XA_MINIMUM_IDLE_PROPERTY,
                    CommonConstants.XA_IDLE_TIMEOUT_PROPERTY,
                    CommonConstants.XA_MAX_LIFETIME_PROPERTY,
                    CommonConstants.XA_CONNECTION_TIMEOUT_PROPERTY,
                    CommonConstants.XA_POOL_ENABLED_PROPERTY,
                    CommonConstants.XA_TIME_BETWEEN_EVICTION_RUNS_PROPERTY,
                    CommonConstants.XA_NUM_TESTS_PER_EVICTION_RUN_PROPERTY,
                    CommonConstants.XA_SOFT_MIN_EVICTABLE_IDLE_TIME_PROPERTY,
                    CommonConstants.XA_DEFAULT_TRANSACTION_ISOLATION_PROPERTY
            };
        } else {
            keys = new String[] {
                    CommonConstants.MAXIMUM_POOL_SIZE_PROPERTY,
                    CommonConstants.MINIMUM_IDLE_PROPERTY,
                    CommonConstants.IDLE_TIMEOUT_PROPERTY,
                    CommonConstants.MAX_LIFETIME_PROPERTY,
                    CommonConstants.CONNECTION_TIMEOUT_PROPERTY,
                    CommonConstants.POOL_ENABLED_PROPERTY,
                    CommonConstants.DEFAULT_TRANSACTION_ISOLATION_PROPERTY,
                    CommonConstants.LEAK_DETECTION_THRESHOLD_PROPERTY
            };
        }

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
     * Gets a boolean property with a default value.
     */
    private static boolean getBooleanProperty(Properties properties, String key, boolean defaultValue) {
        if (properties == null || !properties.containsKey(key)) {
            return defaultValue;
        }
        String value = properties.getProperty(key);
        if (value != null) {
            return Boolean.parseBoolean(value);
        }
        return defaultValue;
    }

    /**
     * Gets a transaction isolation level property from string names.
     * Returns null if not specified, defaulting to READ_COMMITTED.
     *
     * <p>This method provides explicit validation of transaction isolation values,
     * accepting only well-known string names (case-insensitive). Invalid values
     * are rejected with a warning and null is returned, which causes the system
     * to fall back to the default READ_COMMITTED isolation level.</p>
     *
     * @param properties The properties object
     * @param key The property key
     * @return The transaction isolation level constant, or null if not specified or invalid
     */
    private static Integer getTransactionIsolationProperty(Properties properties, String key) {
        if (properties == null || !properties.containsKey(key)) {
            return null;
        }

        String value = properties.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return null;
        }

        value = value.trim();

        // Parse string names (case-insensitive) with explicit validation
        switch (value.toUpperCase()) {
            case "TRANSACTION_NONE":
            case "NONE":
                return java.sql.Connection.TRANSACTION_NONE;
            case "TRANSACTION_READ_UNCOMMITTED":
            case "READ_UNCOMMITTED":
                return java.sql.Connection.TRANSACTION_READ_UNCOMMITTED;
            case "TRANSACTION_READ_COMMITTED":
            case "READ_COMMITTED":
                return java.sql.Connection.TRANSACTION_READ_COMMITTED;
            case "TRANSACTION_REPEATABLE_READ":
            case "REPEATABLE_READ":
                return java.sql.Connection.TRANSACTION_REPEATABLE_READ;
            case "TRANSACTION_SERIALIZABLE":
            case "SERIALIZABLE":
                return java.sql.Connection.TRANSACTION_SERIALIZABLE;
            default:
                log.warn("Invalid transaction isolation value for property '{}': '{}'. " +
                        "Valid values are: NONE, READ_UNCOMMITTED, READ_COMMITTED, REPEATABLE_READ, SERIALIZABLE " +
                        "(or their TRANSACTION_* variants). " +
                        "READ_COMMITTED will be used as default.", key, value);
                return null;
        }
    }

    /**
     * Clears the configuration caches. Useful for testing.
     */
    public static void clearCache() {
        CONFIG_CACHE.clear();
        XA_CONFIG_CACHE.clear();
        log.debug("Cleared DataSourceConfiguration caches");
    }

    /**
     * Gets the number of cached configurations. Useful for monitoring.
     */
    public static int getCacheSize() {
        return CONFIG_CACHE.size() + XA_CONFIG_CACHE.size();
    }
}
