package org.openjproxy.xa.pool.commons;

import org.openjproxy.xa.pool.XABackendSession;
import org.openjproxy.xa.pool.spi.XAConnectionPoolProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.XADataSource;
import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/**
 * Universal XA connection pool provider using Apache Commons Pool 2.
 * <p>
 * This is the **default implementation** of {@link XAConnectionPoolProvider} and works
 * with **all databases** that support XA transactions (PostgreSQL, SQL Server, DB2,
 * MySQL, MariaDB, Oracle, etc.).
 * </p>
 *
 * <h2>Zero Vendor Dependencies</h2>
 * <p>
 * This provider uses **reflection** to instantiate and configure vendor-specific
 * XADataSource implementations, eliminating compile-time dependencies on vendor JDBC
 * drivers. The only requirement is that the appropriate driver JAR is present on the
 * classpath at runtime.
 * </p>
 *
 * <h3>Configuration Example (PostgreSQL):</h3>
 * <pre>{@code
 * Map<String, String> config = new HashMap<>();
 * config.put("xa.datasource.className", "org.postgresql.xa.PGXADataSource");
 * config.put("xa.url", "jdbc:postgresql://localhost:5432/mydb");
 * config.put("xa.username", "postgres");
 * config.put("xa.password", "secret");
 * config.put("xa.maxPoolSize", "20");
 *
 * XADataSource xaDS = provider.createXADataSource(config);
 * }</pre>
 *
 * <h3>Configuration Example (SQL Server):</h3>
 * <pre>{@code
 * config.put("xa.datasource.className", "com.microsoft.sqlserver.jdbc.SQLServerXADataSource");
 * config.put("xa.url", "jdbc:sqlserver://localhost:1433;database=mydb");
 * // ... (same configuration keys, different class name)
 * }</pre>
 *
 * <h2>Pool Characteristics</h2>
 * <ul>
 *   <li>Configurable pool sizing (maxTotal, minIdle)</li>
 *   <li>Validation on borrow and while idle</li>
 *   <li>Idle session eviction</li>
 *   <li>Maximum session lifetime enforcement</li>
 *   <li>Fair queuing when pool exhausted</li>
 *   <li>Automatic session reset on return</li>
 * </ul>
 *
 * <h2>Priority and Selection</h2>
 * <p>
 * This provider has priority 0 (default) and supports all databases. When a
 * database-specific provider is available (e.g., Oracle UCP with priority 50),
 * that provider will be selected instead for optimal performance.
 * </p>
 */
public class CommonsPool2XAProvider implements XAConnectionPoolProvider {
    private static final Logger log = LoggerFactory.getLogger(CommonsPool2XAProvider.class);

    private static final String ID = "commons-pool2";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public XADataSource createXADataSource(Map<String, String> config)
            throws SQLException, ReflectiveOperationException {

        if (config == null) {
            throw new IllegalArgumentException("config cannot be null");
        }

        String className = config.get("xa.datasource.className");
        if (className == null || className.trim().isEmpty()) {
            throw new IllegalArgumentException("xa.datasource.className is required");
        }

        log.info("Creating XADataSource using Commons Pool 2: {}", className);

        try {
            // Instantiate vendor XADataSource via reflection
            // Use the context class loader to find classes loaded from external JARs
            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
            Class<?> xaDataSourceClass = Class.forName(className, true, classLoader);
            XADataSource vendorXADataSource = (XADataSource) xaDataSourceClass
                    .getDeclaredConstructor()
                    .newInstance();

            // Configure vendor XADataSource via reflection
            configureXADataSource(vendorXADataSource, config);

            // Wrap in pooling XADataSource
            CommonsPool2XADataSource pooledXADataSource =
                    new CommonsPool2XADataSource(vendorXADataSource, config);

            log.info("XADataSource created successfully: {}", className);

            return pooledXADataSource;

        } catch (ClassNotFoundException e) {
            log.error("XADataSource class not found: {}", className);
            throw new SQLException("XADataSource class not found: " + className, e);

        } catch (ReflectiveOperationException e) {
            log.error("Failed to instantiate XADataSource: {}", className, e);
            throw e;

        } catch (Exception e) {
            log.error("Failed to create XADataSource: {}", className, e);
            throw new SQLException("Failed to create XADataSource: " + className, e);
        }
    }

    @Override
    public void closeXADataSource(XADataSource xaDataSource) throws Exception {
        if (xaDataSource == null) {
            return;
        }

        if (xaDataSource instanceof CommonsPool2XADataSource) {
            CommonsPool2XADataSource pooled = (CommonsPool2XADataSource) xaDataSource;
            pooled.close();
        } else {
            log.warn("XADataSource is not a CommonsPool2XADataSource, cannot close pool");
        }
    }

    @Override
    public Map<String, Object> getStatistics(XADataSource xaDataSource) {
        Map<String, Object> stats = new HashMap<>();

        if (xaDataSource instanceof CommonsPool2XADataSource) {
            CommonsPool2XADataSource pooled = (CommonsPool2XADataSource) xaDataSource;

            stats.put("activeConnections", pooled.getNumActive());
            stats.put("idleConnections", pooled.getNumIdle());
            stats.put("totalConnections", pooled.getNumActive() + pooled.getNumIdle());
            stats.put("pendingThreads", pooled.getNumWaiters());
            stats.put("maxPoolSize", pooled.getMaxTotal());
        }

        return stats;
    }

    @Override
    public int getPriority() {
        return 100; // Highest priority - default universal provider
    }

    @Override
    public boolean isAvailable() {
        try {
            // Check if Apache Commons Pool 2 is on classpath
            Class.forName("org.apache.commons.pool2.ObjectPool");
            return true;
        } catch (ClassNotFoundException e) {
            log.warn("Apache Commons Pool 2 not available on classpath");
            return false;
        }
    }

    @Override
    public boolean supportsDatabase(String jdbcUrl, String driverClassName) {
        // Universal provider supports all databases
        return true;
    }

    @Override
    public XABackendSession borrowSession(Object xaDataSource) throws Exception {
        if (!(xaDataSource instanceof CommonsPool2XADataSource)) {
            throw new IllegalArgumentException(
                    "xaDataSource must be CommonsPool2XADataSource, got: " +
                    (xaDataSource != null ? xaDataSource.getClass().getName() : "null"));
        }

        CommonsPool2XADataSource pooled = (CommonsPool2XADataSource) xaDataSource;
        return pooled.borrowSession();
    }

    @Override
    public void returnSession(Object xaDataSource, XABackendSession session) throws Exception {
        if (!(xaDataSource instanceof CommonsPool2XADataSource)) {
            throw new IllegalArgumentException(
                    "xaDataSource must be CommonsPool2XADataSource");
        }

        CommonsPool2XADataSource pooled = (CommonsPool2XADataSource) xaDataSource;
        pooled.returnSession(session);
    }

    @Override
    public void invalidateSession(Object xaDataSource, XABackendSession session) throws Exception {
        if (!(xaDataSource instanceof CommonsPool2XADataSource)) {
            throw new IllegalArgumentException(
                    "xaDataSource must be CommonsPool2XADataSource");
        }

        CommonsPool2XADataSource pooled = (CommonsPool2XADataSource) xaDataSource;
        pooled.invalidateSession(session);
    }

    // Private helper methods

    /**
     * Configures a vendor XADataSource using reflection.
     * <p>
     * This method attempts to set properties on the XADataSource by calling
     * setter methods via reflection. It handles common property names across
     * different vendors (URL/url, user/username, etc.).
     * </p>
     *
     * @param xaDataSource the XADataSource to configure
     * @param config the configuration map
     */
    private void configureXADataSource(XADataSource xaDataSource, Map<String, String> config) {
        // Map of property keys to try (in order)
        Map<String, String[]> propertyMappings = new HashMap<>();
        propertyMappings.put("xa.url", new String[]{"URL", "url", "Url"});
        propertyMappings.put("xa.username", new String[]{"user", "User", "username", "Username"});
        propertyMappings.put("xa.password", new String[]{"password", "Password"});
        propertyMappings.put("xa.databaseName", new String[]{"databaseName", "DatabaseName"});
        propertyMappings.put("xa.serverName", new String[]{"serverName", "ServerName"});
        propertyMappings.put("xa.portNumber", new String[]{"portNumber", "PortNumber"});

        for (Map.Entry<String, String[]> entry : propertyMappings.entrySet()) {
            String configKey = entry.getKey();
            String configValue = config.get(configKey);

            if (configValue != null && !configValue.trim().isEmpty()) {
                String[] propertyNames = entry.getValue();

                boolean set = false;
                for (String propertyName : propertyNames) {
                    if (setProperty(xaDataSource, propertyName, configValue)) {
                        log.debug("Set property {}={}", propertyName, maskPassword(configKey, configValue));
                        set = true;
                        break;
                    }
                }

                if (!set) {
                    log.warn("Could not set property for config key: {}", configKey);
                }
            }
        }

        // Set any additional properties that start with "xa.property."
        for (Map.Entry<String, String> entry : config.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("xa.property.")) {
                String propertyName = key.substring("xa.property.".length());
                String propertyValue = entry.getValue();

                if (setProperty(xaDataSource, propertyName, propertyValue)) {
                    log.debug("Set custom property {}={}", propertyName, propertyValue);
                } else {
                    log.warn("Could not set custom property: {}", propertyName);
                }
            }
        }
    }

    /**
     * Sets a property on an object via reflection.
     *
     * @param obj the object to set property on
     * @param propertyName the property name (will try setter method)
     * @param value the value to set
     * @return true if property was set successfully, false otherwise
     */
    private boolean setProperty(Object obj, String propertyName, String value) {
        try {
            // Try String setter
            Method setter = findSetter(obj.getClass(), propertyName, String.class);
            if (setter != null) {
                setter.invoke(obj, value);
                return true;
            }

            // Try int setter (for port numbers, etc.)
            setter = findSetter(obj.getClass(), propertyName, int.class);
            if (setter != null) {
                setter.invoke(obj, Integer.parseInt(value));
                return true;
            }

            // Try Integer setter
            setter = findSetter(obj.getClass(), propertyName, Integer.class);
            if (setter != null) {
                setter.invoke(obj, Integer.valueOf(value));
                return true;
            }

        } catch (Exception e) {
            log.debug("Failed to set property {} on {}: {}",
                    propertyName, obj.getClass().getName(), e.getMessage());
        }

        return false;
    }

    /**
     * Finds a setter method for a property.
     *
     * @param clazz the class to search
     * @param propertyName the property name
     * @param paramType the parameter type
     * @return the setter method, or null if not found
     */
    private Method findSetter(Class<?> clazz, String propertyName, Class<?> paramType) {
        String setterName = "set" + propertyName.substring(0, 1).toUpperCase() +
                            propertyName.substring(1);

        try {
            return clazz.getMethod(setterName, paramType);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    /**
     * Masks password values for logging.
     *
     * @param configKey the configuration key
     * @param value the configuration value
     * @return masked value if password, original value otherwise
     */
    private String maskPassword(String configKey, String value) {
        if (configKey != null && configKey.toLowerCase().contains("password")) {
            return "****";
        }
        return value;
    }
}
