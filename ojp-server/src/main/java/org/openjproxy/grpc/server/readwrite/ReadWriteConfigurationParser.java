package org.openjproxy.grpc.server.readwrite;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Parses read/write splitting configuration from Properties.
 *
 * <p>Configuration format:
 * <pre>
 * # Primary configuration
 * primary.ojp.readwrite.enabled=true
 * primary.ojp.readwrite.role=primary
 * primary.ojp.readwrite.replicaSelectionStrategy=ROUND_ROBIN
 * primary.ojp.readwrite.stickySessionSeconds=5
 * primary.ojp.readwrite.replicaFailoverToPrimary=true
 *
 * # Replica configuration
 * replica1.ojp.readwrite.role=replica
 * replica1.ojp.readwrite.primary=primary
 * </pre>
 *
 * <p>All read/write configuration is supplied by the JDBC client via {@link java.util.Properties}
 * passed to {@link java.sql.DriverManager#getConnection(String, Properties)} and forwarded to
 * the OJP server as gRPC metadata.  No server-side properties file is required.
 */
@Slf4j
public class ReadWriteConfigurationParser {

    // Private constructor to prevent instantiation of utility class
    private ReadWriteConfigurationParser() {
        throw new UnsupportedOperationException("Utility class - do not instantiate");
    }

    private static final String READWRITE_PREFIX = ".ojp.readwrite.";
    private static final String ENABLED_SUFFIX = "enabled";
    private static final String ROLE_SUFFIX = "role";
    private static final String PRIMARY_SUFFIX = "primary";
    private static final String STRATEGY_SUFFIX = "replicaSelectionStrategy";
    private static final String STICKY_SESSION_SUFFIX = "stickySessionSeconds";
    private static final String FAILOVER_SUFFIX = "replicaFailoverToPrimary";

    private static final String ROLE_PRIMARY = "primary";
    private static final String ROLE_REPLICA = "replica";

    // Cache for parsed configurations
    private static final ConcurrentMap<String, ReadWriteConfiguration> CONFIG_CACHE = new ConcurrentHashMap<>();

    /**
     * Parses read/write configuration for all datasources from properties.
     *
     * @param properties configuration properties
     * @return map of primary datasource name to ReadWriteConfiguration
     * @throws IllegalArgumentException if configuration is invalid
     */
    public static Map<String, ReadWriteConfiguration> parseAll(Properties properties) {
        Map<String, ReadWriteConfiguration> configs = new HashMap<>();

        // First pass: find all primaries
        Set<String> primaries = findPrimaries(properties);

        // Second pass: build configuration for each primary
        for (String primaryName : primaries) {
            ReadWriteConfiguration config = parseForPrimary(primaryName, properties);
            configs.put(primaryName, config);

            log.info("Parsed read/write configuration for primary '{}': {}", primaryName, config);
        }

        return configs;
    }

    /**
     * Parses read/write configuration for a specific primary datasource.
     * Uses cache to avoid reparsing.
     *
     * @param primaryName name of the primary datasource
     * @param properties  configuration properties
     * @return ReadWriteConfiguration for this primary, or null if not configured
     */
    public static ReadWriteConfiguration parseForPrimary(String primaryName, Properties properties) {
        // Check cache first
        ReadWriteConfiguration cached = CONFIG_CACHE.get(primaryName);
        if (cached != null) {
            return cached;
        }

        // Parse configuration
        String prefix = primaryName + READWRITE_PREFIX;

        // Check if read/write splitting is enabled for this primary
        boolean enabled = getBooleanProperty(properties, prefix + ENABLED_SUFFIX, false);

        // Verify role is explicitly set to "primary"
        String role = getStringProperty(properties, prefix + ROLE_SUFFIX, "");
        if (!role.isEmpty() && !ROLE_PRIMARY.equals(role)) {
            log.warn("Datasource '{}' has readwrite.role='{}' but is not 'primary', skipping read/write config",
                    primaryName, role);
            return null;
        }

        // Parse strategy
        String strategyStr = getStringProperty(properties, prefix + STRATEGY_SUFFIX, "ROUND_ROBIN");
        ReadWriteConfiguration.ReplicaSelectionStrategy strategy;
        try {
            strategy = ReadWriteConfiguration.ReplicaSelectionStrategy.valueOf(strategyStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid replicaSelectionStrategy '{}' for primary '{}', using ROUND_ROBIN",
                    strategyStr, primaryName);
            strategy = ReadWriteConfiguration.ReplicaSelectionStrategy.ROUND_ROBIN;
        }

        // Parse other settings — default is 0 (disabled); sticky sessions are opt-in
        int stickySessionSeconds = getIntProperty(properties, prefix + STICKY_SESSION_SUFFIX, 0);
        boolean failoverToPrimary = getBooleanProperty(properties, prefix + FAILOVER_SUFFIX, true);

        // Find all replicas for this primary
        List<String> replicaNames = findReplicasForPrimary(primaryName, properties);

        // Build configuration
        ReadWriteConfiguration config = new ReadWriteConfiguration.Builder()
                .primaryName(primaryName)
                .enabled(enabled)
                .strategy(strategy)
                .stickySessionSeconds(stickySessionSeconds)
                .failoverToPrimary(failoverToPrimary)
                .replicas(replicaNames)
                .build();

        // Only cache when read/write splitting is actually active (enabled with at least one replica).
        // A disabled or replica-less config is NOT cached so that the next connection attempt that
        // carries the full properties can re-evaluate and set up splitting correctly.
        if (config.isEnabled() && !config.getReplicaNames().isEmpty()) {
            CONFIG_CACHE.put(primaryName, config);
        }

        return config;
    }

    /**
     * Finds all datasources configured as primaries
     */
    private static Set<String> findPrimaries(Properties properties) {
        Set<String> primaries = new HashSet<>();

        for (String propertyName : properties.stringPropertyNames()) {
            if (propertyName.contains(READWRITE_PREFIX + ROLE_SUFFIX)) {
                String role = properties.getProperty(propertyName);
                if (ROLE_PRIMARY.equals(role)) {
                    // Extract datasource name (everything before .ojp.readwrite.role)
                    String datasourceName = propertyName.substring(0, propertyName.indexOf(READWRITE_PREFIX));
                    primaries.add(datasourceName);
                }
            }
        }

        return primaries;
    }

    /**
     * Finds all replicas configured for a specific primary
     */
    private static List<String> findReplicasForPrimary(String primaryName, Properties properties) {
        List<String> replicas = new ArrayList<>();

        for (String propertyName : properties.stringPropertyNames()) {
            if (propertyName.contains(READWRITE_PREFIX + ROLE_SUFFIX)) {
                String role = properties.getProperty(propertyName);
                if (ROLE_REPLICA.equals(role)) {
                    // Extract datasource name
                    String datasourceName = propertyName.substring(0, propertyName.indexOf(READWRITE_PREFIX));

                    // Check if this replica references our primary
                    String referencedPrimary = getStringProperty(properties,
                            datasourceName + READWRITE_PREFIX + PRIMARY_SUFFIX, "");

                    if (primaryName.equals(referencedPrimary)) {
                        replicas.add(datasourceName);
                    }
                }
            }
        }

        return replicas;
    }

    /**
     * Validates that all replica references point to valid primaries
     */
    public static void validateReplicaReferences(Properties properties) {
        Set<String> primaries = findPrimaries(properties);

        for (String propertyName : properties.stringPropertyNames()) {
            if (propertyName.contains(READWRITE_PREFIX + ROLE_SUFFIX)) {
                String role = properties.getProperty(propertyName);
                if (ROLE_REPLICA.equals(role)) {
                    String datasourceName = propertyName.substring(0, propertyName.indexOf(READWRITE_PREFIX));
                    String referencedPrimary = getStringProperty(properties,
                            datasourceName + READWRITE_PREFIX + PRIMARY_SUFFIX, "");

                    if (referencedPrimary.isEmpty()) {
                        throw new IllegalArgumentException(
                                "Replica '" + datasourceName + "' does not specify a primary datasource");
                    }

                    if (!primaries.contains(referencedPrimary)) {
                        throw new IllegalArgumentException(
                                "Replica '" + datasourceName + "' references unknown primary '" + referencedPrimary + "'");
                    }
                }
            }
        }
    }

    /**
     * Clears the configuration cache. Useful for testing.
     */
    public static void clearCache() {
        CONFIG_CACHE.clear();
    }

    // Property parsing helpers

    private static String getStringProperty(Properties properties, String key, String defaultValue) {
        return properties.getProperty(key, defaultValue);
    }

    private static boolean getBooleanProperty(Properties properties, String key, boolean defaultValue) {
        String value = properties.getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value);
    }

    private static int getIntProperty(Properties properties, String key, int defaultValue) {
        String value = properties.getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            log.warn("Invalid integer value '{}' for property '{}', using default {}", value, key, defaultValue);
            return defaultValue;
        }
    }
}
