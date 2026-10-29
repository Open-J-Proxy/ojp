package org.openjproxy.datasource;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Map;

/**
 * Service Provider Interface (SPI) for connection pool implementations.
 *
 * <p>This interface defines the contract for pluggable connection pool providers
 * in OJP. Implementations should be registered via the standard Java
 * {@link java.util.ServiceLoader} mechanism by creating a file named
 * {@code META-INF/services/org.openjproxy.datasource.ConnectionPoolProvider}
 * containing the fully qualified class name of the implementation.</p>
 *
 * <p>Example implementation:</p>
 * <pre>{@code
 * public class MyPoolProvider implements ConnectionPoolProvider {
 *     @Override
 *     public String id() {
 *         return "my-pool";
 *     }
 *
 *     @Override
 *     public DataSource createDataSource(PoolConfig config) throws SQLException {
 *         // Create and configure the DataSource
 *         return new MyDataSource(config);
 *     }
 *
 *     @Override
 *     public void closeDataSource(DataSource ds) throws Exception {
 *         if (ds instanceof MyDataSource) {
 *             ((MyDataSource) ds).close();
 *         }
 *     }
 *
 *     @Override
 *     public Map<String, Object> getStatistics(DataSource ds) {
 *         // Return pool statistics
 *         return Map.of("activeConnections", 5);
 *     }
 * }
 * }</pre>
 */
public interface ConnectionPoolProvider {

    /**
     * Returns the unique identifier for this connection pool provider.
     *
     * <p>This ID is used to select the appropriate provider when multiple
     * implementations are available. Common conventions include lowercase
     * names like "hikari", "dbcp", "c3p0", etc.</p>
     *
     * @return the unique provider identifier, never null or empty
     */
    String id();

    /**
     * Creates a new DataSource configured according to the provided settings.
     *
     * <p>Implementations should:</p>
     * <ul>
     *   <li>Create and configure a connection pool based on the PoolConfig</li>
     *   <li>Set up connection validation if a validation query is provided</li>
     *   <li>Configure timeouts and pool sizing parameters</li>
     *   <li>Apply any additional properties from the config</li>
     * </ul>
     *
     * @param config the pool configuration settings
     * @return a configured DataSource, never null
     * @throws SQLException if the DataSource cannot be created
     * @throws IllegalArgumentException if config is null or invalid
     */
    DataSource createDataSource(PoolConfig config) throws SQLException;

    /**
     * Closes and releases all resources associated with the DataSource.
     *
     * <p>This method should:</p>
     * <ul>
     *   <li>Close all connections in the pool</li>
     *   <li>Release any associated resources (threads, timers, etc.)</li>
     *   <li>Be idempotent (safe to call multiple times)</li>
     * </ul>
     *
     * @param dataSource the DataSource to close
     * @throws Exception if an error occurs during shutdown
     */
    void closeDataSource(DataSource dataSource) throws Exception;

    /**
     * Returns current statistics about the connection pool.
     *
     * <p>The returned map may include statistics such as:</p>
     * <ul>
     *   <li>{@code activeConnections} - number of currently active connections</li>
     *   <li>{@code idleConnections} - number of idle connections in the pool</li>
     *   <li>{@code totalConnections} - total connections (active + idle)</li>
     *   <li>{@code pendingThreads} - threads waiting for a connection</li>
     *   <li>{@code maxPoolSize} - configured maximum pool size</li>
     * </ul>
     *
     * <p>Implementations should return an empty map if statistics are not
     * available or the DataSource is not recognized.</p>
     *
     * @param dataSource the DataSource to get statistics for
     * @return a map of statistic names to values, never null
     */
    Map<String, Object> getStatistics(DataSource dataSource);

    /**
     * Returns the priority of this provider for auto-selection.
     *
     * <p>When multiple providers are available and no specific provider is
     * requested, the one with the highest priority will be selected.
     * Higher values indicate higher priority.</p>
     *
     * <p>Default priority is 0. Built-in providers typically use values
     * between -100 and 100.</p>
     *
     * @return the provider priority
     */
    default int getPriority() {
        return 0;
    }

    /**
     * Checks if this provider is available (has all required dependencies).
     *
     * <p>Implementations should verify that the underlying connection pool
     * library is present on the classpath.</p>
     *
     * @return true if the provider can be used, false otherwise
     */
    default boolean isAvailable() {
        return true;
    }
}
