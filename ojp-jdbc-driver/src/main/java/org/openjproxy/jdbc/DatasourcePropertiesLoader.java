package org.openjproxy.jdbc;

import lombok.extern.slf4j.Slf4j;

import org.openjproxy.constants.CommonConstants;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Properties;

/**
 * Utility class for loading datasource-specific properties from ojp.properties file.
 * Shared by Driver, OjpXADataSource, and MultinodeConnectionManager to avoid code duplication.
 *
 * <p>Each named datasource is identified by the prefix it uses in ojp.properties.
 * For example, properties prefixed with {@code mainApp.ojp.connection.pool.*} belong to
 * the datasource named {@code mainApp}. Unprefixed {@code ojp.connection.pool.*} properties
 * belong to the implicit {@code "default"} datasource.
 *
 * <p>All {@code *.ojp.*} properties (e.g. read/write splitting, replica connection URLs) are
 * forwarded to the server with their full keys intact so that server-side parsers such as
 * {@code ReadWriteConfigurationParser} can find them. Pool and XA properties for the primary
 * datasource are additionally forwarded with the datasource prefix stripped for backward
 * compatibility with existing server-side pool configuration readers.
 */
@Slf4j
public class DatasourcePropertiesLoader {

    private static final String DEFAULT_DATASOURCE_NAME = "default";
    private static final String OJP_POOL_PREFIX = "ojp.connection.pool.";
    private static final String OJP_XA_PREFIX = "ojp.xa.";
    private static final String OJP_JDBC_PREFIX = "ojp.jdbc.";

    /**
     * Apply OJP-relevant properties from the JDBC {@code info} {@link Properties} argument
     * (passed to {@link java.sql.Driver#connect}) on top of the existing {@code base} properties.
     *
     * <p>Properties from {@code info} have the <em>highest</em> precedence and override any value
     * already present in {@code base} (which was loaded from the properties file, system properties,
     * or environment variables). Only keys matching the {@code ojp.connection.pool.*},
     * {@code ojp.xa.*}, or {@code ojp.jdbc.*} pattern are copied; JDBC-standard keys such as {@code user} and
     * {@code password} are intentionally ignored.
     *
     * <p>Full property precedence after merging (highest to lowest):
     * <ol>
     *   <li>{@code info} properties passed to {@code DriverManager.getConnection()}</li>
     *   <li>Environment variables (e.g. {@code OJP_CONNECTION_POOL_MAXIMUMPOOLSIZE=20})</li>
     *   <li>System properties (e.g. {@code -Dojp.connection.pool.maximumPoolSize=20})</li>
     *   <li>Properties file ({@code ojp.properties} or environment-specific variant)</li>
     * </ol>
     *
     * @param base           properties already resolved from file / system-props / env-vars,
     *                       or {@code null} if none were found
     * @param info           the raw {@link Properties} passed by the caller to {@code connect()},
     *                       or {@code null}
     * @param datasourceName the datasource name, used to stamp
     *                       {@link CommonConstants#DATASOURCE_NAME_PROPERTY} when the result is
     *                       built solely from {@code info}
     * @return merged properties, or {@code null} if both {@code base} and the OJP-relevant subset
     *         of {@code info} are empty
     */
    public static Properties applyInfoProperties(Properties base, Properties info, String datasourceName) {
        if (info == null || info.isEmpty()) {
            return (base == null || base.isEmpty()) ? null : base;
        }
        Properties result = base != null ? base : new Properties();
        boolean added = false;
        for (String key : info.stringPropertyNames()) {
            if (isUnprefixedOjpKey(key)) {
                result.setProperty(key, info.getProperty(key));
                log.debug("Overriding property from connect() info argument: {} = {}", key, info.getProperty(key));
                added = true;
            }
        }
        if (added && result.getProperty(CommonConstants.DATASOURCE_NAME_PROPERTY) == null) {
            result.setProperty(CommonConstants.DATASOURCE_NAME_PROPERTY, datasourceName);
        }
        return result.isEmpty() ? null : result;
    }

    /**
     * Load ojp.properties and extract configuration for the datasource identified by
     * {@code datasourceName}. The name is the prefix used for that datasource's properties
     * (e.g. {@code "mainApp"} loads all {@code mainApp.ojp.connection.pool.*} entries,
     * stripping the prefix before returning them).
     *
     * <p>Pass {@code "default"} to load unprefixed {@code ojp.connection.pool.*} properties.
     *
     * <p>Property precedence (highest to lowest):
     * <ol>
     *   <li>Environment variables (e.g. {@code MAINAPP_OJP_CONNECTION_POOL_ENABLED=false})</li>
     *   <li>System properties (e.g. {@code -Dmainapp.ojp.connection.pool.enabled=false})</li>
     *   <li>Properties file (ojp.properties)</li>
     * </ol>
     *
     * @param datasourceName the prefix/name of the datasource to load
     * @return properties for the datasource, or {@code null} if none found
     */
    public static Properties loadOjpPropertiesForDataSource(String datasourceName) {
        Properties allProperties = loadOjpProperties();
        if (allProperties == null || allProperties.isEmpty()) {
            return null;
        }
        boolean isDefault = DEFAULT_DATASOURCE_NAME.equals(datasourceName);
        String prefixDot = datasourceName + ".";
        Properties result = new Properties();
        applyFileProperties(result, allProperties, prefixDot, isDefault);
        applySystemProperties(result, prefixDot, isDefault);
        applyEnvProperties(result, prefixDot, isDefault);
        if (!result.isEmpty()) {
            result.setProperty(CommonConstants.DATASOURCE_NAME_PROPERTY, datasourceName);
        }
        log.debug("Loaded {} properties for dataSource '{}': {}", result.size(), datasourceName, result);
        return result.isEmpty() ? null : result;
    }

    private static void applyFileProperties(Properties result, Properties source,
                                             String prefixDot, boolean isDefault) {
        boolean found = false;
        for (String key : source.stringPropertyNames()) {
            String value = source.getProperty(key);
            if (hasPrefixedOjpKey(key, prefixDot)) {
                // Pool and XA properties for this datasource: strip prefix for backward compat
                // Example: "myapp.ojp.connection.pool.maxPoolSize=10" → "ojp.connection.pool.maxPoolSize=10"
                result.setProperty(key.substring(prefixDot.length()), value);
                found = true;
            } else if (isPrefixedOjpKey(key)) {
                // Keep full key for read/write splitting and replica configs so the server can find them
                // Example: "replica1.ojp.readwrite.primary=myapp" stays as-is
                result.setProperty(key, value);
            }
        }
        if (!found && isDefault) {
            copyUnprefixedOjpProperties(result, source);
        }
    }

    private static void applySystemProperties(Properties result, String prefixDot, boolean isDefault) {
        applyNormalizedProperties(result, System.getProperties(), prefixDot, isDefault, "system property");
    }

    private static void applyEnvProperties(Properties result, String prefixDot, boolean isDefault) {
        Properties normalized = new Properties();
        for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
            normalized.setProperty(entry.getKey().toLowerCase().replace('_', '.'), entry.getValue());
        }
        applyNormalizedProperties(result, normalized, prefixDot, isDefault, "environment variable");
    }

    /**
     * Applies properties from a source (system properties or environment variables) to the result.
     */
    private static void applyNormalizedProperties(Properties result, Properties source,
                                                   String prefixDot, boolean isDefault, String sourceName) {
        for (String key : source.stringPropertyNames()) {
            String value = source.getProperty(key);
            if (hasPrefixedOjpKey(key, prefixDot)) {
                String std = key.substring(prefixDot.length());
                result.setProperty(std, value);
                log.debug("Overriding property from {}: {} = {}", sourceName, std, value);
            } else if (isPrefixedOjpKey(key)) {
                result.setProperty(key, value);
                log.debug("Setting property from {} (full key): {} = {}", sourceName, key, value);
            } else if (isDefault && isUnprefixedOjpKey(key)) {
                result.setProperty(key, value);
                log.debug("Overriding property from {}: {} = {}", sourceName, key, value);
            }
        }
    }

    private static boolean hasPrefixedOjpKey(String key, String prefixDot) {
        return key.startsWith(prefixDot + OJP_POOL_PREFIX)
                || key.startsWith(prefixDot + OJP_XA_PREFIX)
                || key.startsWith(prefixDot + OJP_JDBC_PREFIX);
    }

    /**
     * Returns true for any property that contains {@code .ojp.} in its key, i.e. any prefixed
     * OJP property regardless of the datasource name prefix. These are forwarded with their
     * full key so that server-side parsers (e.g. read/write splitting) can find them.
     */
    private static boolean isPrefixedOjpKey(String key) {
        return key.contains(".ojp.");
    }

    private static boolean isUnprefixedOjpKey(String key) {
        return key.startsWith(OJP_POOL_PREFIX)
                || key.startsWith(OJP_XA_PREFIX)
                || key.startsWith(OJP_JDBC_PREFIX);
    }

    private static void copyUnprefixedOjpProperties(Properties target, Properties source) {
        for (String key : source.stringPropertyNames()) {
            if (isUnprefixedOjpKey(key)) {
                target.setProperty(key, source.getProperty(key));
            }
        }
    }

    /**
     * Load the raw ojp.properties file from classpath.
     *
     * Supports environment-specific properties files using the naming pattern:
     * ojp-{environment}.properties (e.g., ojp-dev.properties, ojp-prod.properties)
     *
     * The environment is determined by (in order of precedence):
     * 1. System property: -Dojp.environment=dev
     * 2. Environment variable: OJP_ENVIRONMENT=dev
     *
     * If environment is specified, attempts to load ojp-{environment}.properties first.
     * Falls back to ojp.properties if environment-specific file not found.
     *
     * @return All properties from ojp.properties file, or null if not found
     */
    public static Properties loadOjpProperties() {
        Properties properties = new Properties();

        // Determine environment from system property or environment variable
        String environment = getEnvironmentName();

        // Try to load environment-specific properties file first
        if (environment != null && !environment.isEmpty()) {
            String envPropertiesFile = "ojp-" + environment + ".properties";
            try (InputStream is = DatasourcePropertiesLoader.class.getClassLoader().getResourceAsStream(envPropertiesFile)) {
                if (is != null) {
                    properties.load(is);
                    log.info("Loaded environment-specific properties from {} for environment: {}", envPropertiesFile, environment);
                    return properties;
                }
            } catch (IOException e) {
                log.debug("Could not load {} from resources folder: {}", envPropertiesFile, e.getMessage());
            }

            // Log that we're falling back
            log.debug("Environment-specific file {} not found, falling back to ojp.properties", envPropertiesFile);
        }

        // Fall back to ojp.properties in the classpath
        try (InputStream is = DatasourcePropertiesLoader.class.getClassLoader().getResourceAsStream("ojp.properties")) {
            if (is != null) {
                properties.load(is);
                log.debug("Loaded ojp.properties from resources folder");
                return properties;
            }
        } catch (IOException e) {
            log.debug("Could not load ojp.properties from resources folder: {}", e.getMessage());
        }

        log.debug("No ojp.properties file found, using server defaults");
        return null;
    }

    /**
     * Get the environment name from system property or environment variable.
     *
     * Precedence:
     * 1. System property: -Dojp.environment
     * 2. Environment variable: OJP_ENVIRONMENT
     *
     * @return Environment name (trimmed), or null if not specified
     */
    private static String getEnvironmentName() {
        // Check system property first
        String environment = System.getProperty("ojp.environment");
        if (environment != null && !environment.trim().isEmpty()) {
            return environment.trim();
        }

        // Fallback to environment variable
        String envVar = System.getenv("OJP_ENVIRONMENT");
        if (envVar != null && !envVar.trim().isEmpty()) {
            return envVar.trim();
        }

        return null;
    }
}
