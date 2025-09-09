package org.openjdbcproxy.grpc.server.pool;

import com.openjdbcproxy.grpc.ConnectionDetails;
import com.zaxxer.hikari.HikariConfig;
import lombok.extern.slf4j.Slf4j;
import org.openjdbcproxy.constants.CommonConstants;
import org.openjdbcproxy.grpc.server.utils.DatabaseNameExtractor;

import java.util.Properties;
import java.util.Set;
import java.util.HashSet;

import static org.openjdbcproxy.grpc.SerializationHandler.deserialize;

/**
 * Utility class responsible for configuring HikariCP connection pools.
 * Extracted from StatementServiceImpl to reduce its responsibilities.
 */
@Slf4j
public class ConnectionPoolConfigurer {

    /**
     * Configures a HikariCP connection pool with connection details and client properties.
     * Supports hierarchical property resolution: dbName.userName > dbName.* > global default.
     *
     * @param config            The HikariConfig to configure
     * @param connectionDetails The connection details containing properties
     */
    public static void configureHikariPool(HikariConfig config, ConnectionDetails connectionDetails) {
        Properties clientProperties = extractClientProperties(connectionDetails);
        
        // Extract database name and username for hierarchical lookup
        String databaseName = DatabaseNameExtractor.extractDatabaseName(connectionDetails.getUrl());
        String userName = connectionDetails.getUser();
        
        // Validate prefixed properties exist and match expected database/user combinations
        validatePrefixedProperties(clientProperties, databaseName, userName);

        // Configure basic connection pool settings first
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

        // Configure HikariCP pool settings using hierarchical property resolution
        config.setMaximumPoolSize(getHierarchicalIntProperty(clientProperties, "ojp.connection.pool.maximumPoolSize", 
                databaseName, userName, CommonConstants.DEFAULT_MAXIMUM_POOL_SIZE));
        config.setMinimumIdle(getHierarchicalIntProperty(clientProperties, "ojp.connection.pool.minimumIdle", 
                databaseName, userName, CommonConstants.DEFAULT_MINIMUM_IDLE));
        config.setIdleTimeout(getHierarchicalLongProperty(clientProperties, "ojp.connection.pool.idleTimeout", 
                databaseName, userName, CommonConstants.DEFAULT_IDLE_TIMEOUT));
        config.setMaxLifetime(getHierarchicalLongProperty(clientProperties, "ojp.connection.pool.maxLifetime", 
                databaseName, userName, CommonConstants.DEFAULT_MAX_LIFETIME));
        config.setConnectionTimeout(getHierarchicalLongProperty(clientProperties, "ojp.connection.pool.connectionTimeout", 
                databaseName, userName, CommonConstants.DEFAULT_CONNECTION_TIMEOUT));
        
        // Additional settings for high concurrency scenarios
        config.setLeakDetectionThreshold(60000); // 60 seconds - detect connection leaks
        config.setValidationTimeout(5000);       // 5 seconds - faster validation timeout
        config.setInitializationFailTimeout(10000); // 10 seconds - fail fast on initialization issues
        
        // Set pool name for better monitoring - include database and user context
        String poolName = buildPoolName(databaseName, userName);
        config.setPoolName(poolName);
        
        // Enable JMX for monitoring if not explicitly disabled
        config.setRegisterMbeans(true);

        log.info("HikariCP configured for {}@{} with maximumPoolSize={}, minimumIdle={}, connectionTimeout={}ms, poolName={}",
                userName != null ? userName : "unknown", 
                databaseName != null ? databaseName : "unknown",
                config.getMaximumPoolSize(), config.getMinimumIdle(), config.getConnectionTimeout(), poolName);
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

    /**
     * Gets an integer property using hierarchical lookup: dbName.userName > dbName.* > global default.
     * 
     * @param properties    The properties object
     * @param basePropKey   The base property key (e.g., "ojp.connection.pool.maximumPoolSize")
     * @param databaseName  The database name for specific configuration
     * @param userName      The username for specific configuration
     * @param defaultValue  The default value
     * @return The property value using hierarchical resolution
     */
    private static int getHierarchicalIntProperty(Properties properties, String basePropKey, 
            String databaseName, String userName, int defaultValue) {
        if (properties == null) {
            return defaultValue;
        }

        // Try most specific: dbName.userName.propKey
        if (databaseName != null && userName != null) {
            String specificKey = databaseName + "." + userName + "." + basePropKey;
            if (properties.containsKey(specificKey)) {
                try {
                    int value = Integer.parseInt(properties.getProperty(specificKey));
                    log.debug("Using database+user specific property '{}' = {}", specificKey, value);
                    return value;
                } catch (NumberFormatException e) {
                    log.warn("Invalid integer value for property '{}': {}, falling back to less specific", 
                            specificKey, properties.getProperty(specificKey));
                }
            }
        }

        // Try database specific: dbName.propKey
        if (databaseName != null) {
            String dbKey = databaseName + "." + basePropKey;
            if (properties.containsKey(dbKey)) {
                try {
                    int value = Integer.parseInt(properties.getProperty(dbKey));
                    log.debug("Using database specific property '{}' = {}", dbKey, value);
                    return value;
                } catch (NumberFormatException e) {
                    log.warn("Invalid integer value for property '{}': {}, falling back to default", 
                            dbKey, properties.getProperty(dbKey));
                }
            }
        }

        // Fall back to global default
        return getIntProperty(properties, basePropKey, defaultValue);
    }

    /**
     * Gets a long property using hierarchical lookup: dbName.userName > dbName.* > global default.
     * 
     * @param properties    The properties object
     * @param basePropKey   The base property key (e.g., "ojp.connection.pool.idleTimeout")
     * @param databaseName  The database name for specific configuration
     * @param userName      The username for specific configuration
     * @param defaultValue  The default value
     * @return The property value using hierarchical resolution
     */
    private static long getHierarchicalLongProperty(Properties properties, String basePropKey, 
            String databaseName, String userName, long defaultValue) {
        if (properties == null) {
            return defaultValue;
        }

        // Try most specific: dbName.userName.propKey
        if (databaseName != null && userName != null) {
            String specificKey = databaseName + "." + userName + "." + basePropKey;
            if (properties.containsKey(specificKey)) {
                try {
                    long value = Long.parseLong(properties.getProperty(specificKey));
                    log.debug("Using database+user specific property '{}' = {}", specificKey, value);
                    return value;
                } catch (NumberFormatException e) {
                    log.warn("Invalid long value for property '{}': {}, falling back to less specific", 
                            specificKey, properties.getProperty(specificKey));
                }
            }
        }

        // Try database specific: dbName.propKey
        if (databaseName != null) {
            String dbKey = databaseName + "." + basePropKey;
            if (properties.containsKey(dbKey)) {
                try {
                    long value = Long.parseLong(properties.getProperty(dbKey));
                    log.debug("Using database specific property '{}' = {}", dbKey, value);
                    return value;
                } catch (NumberFormatException e) {
                    log.warn("Invalid long value for property '{}': {}, falling back to default", 
                            dbKey, properties.getProperty(dbKey));
                }
            }
        }

        // Fall back to global default
        return getLongProperty(properties, basePropKey, defaultValue);
    }

    /**
     * Validates that any prefixed properties match expected database/user combinations.
     * Fails fast with clear exception if invalid prefixes are found.
     * 
     * @param properties    The properties object to validate
     * @param databaseName  The actual database name
     * @param userName      The actual username
     * @throws IllegalArgumentException if invalid prefixes are found
     */
    private static void validatePrefixedProperties(Properties properties, String databaseName, String userName) {
        if (properties == null) {
            return;
        }

        Set<String> invalidPrefixes = new HashSet<>();
        
        // Check all property keys for prefixes
        for (String key : properties.stringPropertyNames()) {
            if (key.startsWith("ojp.connection.pool.")) {
                // This is a global property - valid
                continue;
            }
            
            // Check if this looks like a prefixed property
            if (key.contains(".ojp.connection.pool.")) {
                String prefix = key.substring(0, key.indexOf(".ojp.connection.pool."));
                
                // Check if prefix matches our database name
                if (databaseName != null && prefix.equals(databaseName)) {
                    // Valid database-specific property
                    continue;
                }
                
                // Check if prefix matches database.username pattern
                if (databaseName != null && userName != null) {
                    String dbUserPrefix = databaseName + "." + userName;
                    if (prefix.equals(dbUserPrefix)) {
                        // Valid database+user specific property
                        continue;
                    }
                }
                
                // Check for invalid username-only prefix (userName.ojp.connection.pool.*)
                if (userName != null && prefix.equals(userName)) {
                    throw new IllegalArgumentException(
                        String.format("Username-only prefix '%s' is not supported. " +
                                "Use database-specific ('%s.ojp.connection.pool.*') or " + 
                                "database+user-specific ('%s.%s.ojp.connection.pool.*') configuration instead.",
                                userName, databaseName != null ? databaseName : "dbName", 
                                databaseName != null ? databaseName : "dbName", userName));
                }
                
                // Invalid prefix - doesn't match our database or database+user
                invalidPrefixes.add(prefix);
            }
        }
        
        // Report all invalid prefixes
        if (!invalidPrefixes.isEmpty()) {
            throw new IllegalArgumentException(
                String.format("Invalid configuration prefix(es) found: %s. " +
                        "Expected prefixes for this connection: '%s' (database-specific) or '%s.%s' (database+user-specific). " +
                        "Current database: '%s', user: '%s'",
                        invalidPrefixes,
                        databaseName != null ? databaseName : "null",
                        databaseName != null ? databaseName : "null",
                        userName != null ? userName : "null",
                        databaseName != null ? databaseName : "null",
                        userName != null ? userName : "null"));
        }
    }

    /**
     * Builds a pool name that includes database and user context for monitoring.
     * 
     * @param databaseName The database name
     * @param userName     The username
     * @return A descriptive pool name
     */
    private static String buildPoolName(String databaseName, String userName) {
        StringBuilder poolName = new StringBuilder("OJP-Pool");
        
        if (databaseName != null) {
            poolName.append("-").append(databaseName);
        }
        
        if (userName != null) {
            poolName.append("-").append(userName);
        }
        
        poolName.append("-").append(System.currentTimeMillis());
        
        return poolName.toString();
    }
}