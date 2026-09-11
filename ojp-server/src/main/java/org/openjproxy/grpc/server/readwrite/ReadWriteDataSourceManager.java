package org.openjproxy.grpc.server.readwrite;

import com.openjproxy.grpc.ConnectionDetails;
import com.openjproxy.grpc.PropertyEntry;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.datasource.ConnectionPoolProviderRegistry;
import org.openjproxy.datasource.PoolConfig;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

/**
 * Manages creation and registration of primary and replica datasources for read/write splitting.
 * This class handles parsing read/write configuration and creating appropriate datasource pools.
 */
@Slf4j
public class ReadWriteDataSourceManager {

    private final ReadWriteDataSourceRegistry registry;

    public ReadWriteDataSourceManager(ReadWriteDataSourceRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry cannot be null");
    }

    /**
     * Checks if read/write splitting is configured for the given connection details.
     *
     * @param connectionDetails connection details with properties
     * @param datasourceName    name of the datasource
     * @return true if read/write splitting is configured and enabled
     */
    public boolean isReadWriteSplittingEnabled(ConnectionDetails connectionDetails, String datasourceName) {
        if (connectionDetails.getPropertiesCount() == 0) {
            return false;
        }

        ReadWriteConfiguration config = parseReadWriteConfig(connectionDetails, datasourceName).config;

        return config != null && config.isEnabled() && !config.getReplicaNames().isEmpty();
    }

    /**
     * Creates primary and replica datasources based on read/write configuration.
     * Registers them in the ReadWriteDataSourceRegistry.
     *
     * @param connectionDetails primary connection details
     * @param primaryConnHash   connection hash for the primary
     * @param primaryDs         the primary datasource (already created)
     * @param datasourceName    name of the datasource
     * @return ReadWriteConfiguration if read/write splitting was configured, null otherwise
     */
    public ReadWriteConfiguration setupReadWriteSplitting(
            ConnectionDetails connectionDetails,
            String primaryConnHash,
            DataSource primaryDs,
            String datasourceName) {

        // Idempotency: if the primary is already mapped and has replicas registered, nothing to do.
        if (registry.getPrimaryName(primaryConnHash) != null && registry.hasReplicas(datasourceName)) {
            log.debug("Read/write splitting already active for primary '{}' (connHash={}), skipping re-setup",
                    datasourceName, primaryConnHash);
            return null;
        }

        if (connectionDetails.getPropertiesCount() == 0) {
            log.debug("No properties provided, read/write splitting not configured");
            return null;
        }

        // Parse configuration (converts gRPC properties to Java Properties once)
        ParsedConfig parsed = parseReadWriteConfig(connectionDetails, datasourceName);
        ReadWriteConfiguration config = parsed.config;
        Properties props = parsed.props;

        if (config == null || !config.isEnabled()) {
            log.debug("Read/write splitting not enabled for datasource '{}'", datasourceName);
            return null;
        }

        if (config.getReplicaNames().isEmpty()) {
            log.warn("Read/write splitting enabled for '{}' but no replicas configured", datasourceName);
            return null;
        }

        log.info("Setting up read/write splitting for primary '{}' with {} replicas",
                datasourceName, config.getReplicaNames().size());

        // Register primary datasource
        registry.registerPrimaryMapping(primaryConnHash, datasourceName);

        // Register sticky session timeout
        registry.registerStickyTimeout(datasourceName, config.getStickySessionSeconds());

        // Register replica selection strategy
        registry.registerStrategy(datasourceName, config.getStrategy());

        // Create and register replica datasources
        List<String> successfulReplicas = new ArrayList<>();
        for (String replicaName : config.getReplicaNames()) {
            try {
                DataSource replicaDs = createReplicaDataSource(replicaName, props);
                if (replicaDs != null) {
                    registry.registerReplica(datasourceName, replicaDs);
                    successfulReplicas.add(replicaName);
                    log.info("Successfully created and registered replica datasource '{}'", replicaName);
                }
            } catch (Exception e) {
                log.error("Failed to create replica datasource '{}': {}", replicaName, e.getMessage(), e);
                // Continue with other replicas
            }
        }

        if (successfulReplicas.isEmpty()) {
            log.warn("No replicas successfully created for primary '{}', read/write splitting will not be active",
                    datasourceName);
            return null;
        }

        log.info("Read/write splitting configured for primary '{}' with {} active replicas: {}",
                datasourceName, successfulReplicas.size(), successfulReplicas);

        return config;
    }

    /**
     * Creates a replica datasource based on configuration properties.
     *
     * @param replicaName name of the replica datasource
     * @param props       configuration properties
     * @return DataSource for the replica, or null if configuration is invalid
     */
    private DataSource createReplicaDataSource(String replicaName, Properties props) {
        // Extract replica-specific properties with ojp. prefix
        String replicaPrefix = replicaName + ".ojp.";

        // Get replica URL (required)
        String replicaUrl = props.getProperty(replicaPrefix + "connection.url");
        if (replicaUrl == null || replicaUrl.trim().isEmpty()) {
            log.error("No connection URL configured for replica '{}' (looked for {}connection.url), skipping",
                    replicaName, replicaPrefix);
            return null;
        }

        // Get replica credentials (optional)
        String replicaUser = props.getProperty(replicaPrefix + "connection.user", "");
        String replicaPassword = props.getProperty(replicaPrefix + "connection.password", "");

        // Get pool configuration (with sensible defaults)
        int maxPoolSize = getIntProperty(props, replicaPrefix + "pool.maxPoolSize", 10);
        int minIdle = getIntProperty(props, replicaPrefix + "pool.minIdle", 2);
        long connectionTimeout = getLongProperty(props, replicaPrefix + "pool.connectionTimeout", 30000);
        long idleTimeout = getLongProperty(props, replicaPrefix + "pool.idleTimeout", 600000);
        long maxLifetime = getLongProperty(props, replicaPrefix + "pool.maxLifetime", 1800000);

        try {
            PoolConfig poolConfig = PoolConfig.builder()
                    .url(replicaUrl)
                    .username(replicaUser)
                    .password(replicaPassword)
                    .maxPoolSize(maxPoolSize)
                    .minIdle(minIdle)
                    .connectionTimeoutMs(connectionTimeout)
                    .idleTimeoutMs(idleTimeout)
                    .maxLifetimeMs(maxLifetime)
                    .defaultTransactionIsolation(java.sql.Connection.TRANSACTION_READ_COMMITTED)
                    .metricsPrefix("OJP-Replica-" + replicaName)
                    .build();

            DataSource ds = ConnectionPoolProviderRegistry.createDataSource(poolConfig);
            log.info("Created replica datasource '{}' with URL: {}, maxPoolSize: {}, minIdle: {}",
                    replicaName, replicaUrl, maxPoolSize, minIdle);

            return ds;
        } catch (Exception e) {
            log.error("Failed to create datasource for replica '{}': {}", replicaName, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Converts gRPC PropertyEntry list to Java Properties object and parses the read/write configuration.
     * Centralizes the conversion so it is performed exactly once per public API call.
     */
    private ParsedConfig parseReadWriteConfig(ConnectionDetails connectionDetails, String datasourceName) {
        Properties props = convertPropertiesToJava(connectionDetails.getPropertiesList());
        ReadWriteConfiguration config = ReadWriteConfigurationParser.parseForPrimary(datasourceName, props);
        return new ParsedConfig(config, props);
    }

    /** Holder for a parsed {@link ReadWriteConfiguration} together with the already-converted properties. */
    private static final class ParsedConfig {
        private final ReadWriteConfiguration config;
        private final Properties props;

        ParsedConfig(ReadWriteConfiguration config, Properties props) {
            this.config = config;
            this.props = props;
        }
    }

    /**
     * Converts gRPC PropertyEntry list to Java Properties object.
     */
    private Properties convertPropertiesToJava(List<PropertyEntry> propertyEntries) {
        Properties props = new Properties();
        for (PropertyEntry entry : propertyEntries) {
            props.setProperty(entry.getKey(), entry.getStringValue());
        }
        return props;
    }

    private int getIntProperty(Properties props, String key, int defaultValue) {
        String value = props.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            log.warn("Invalid integer value for property '{}': {}, using default: {}", key, value, defaultValue);
            return defaultValue;
        }
    }

    private long getLongProperty(Properties props, String key, long defaultValue) {
        String value = props.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            log.warn("Invalid long value for property '{}': {}, using default: {}", key, value, defaultValue);
            return defaultValue;
        }
    }
}
