package org.openjproxy.xa.pool.spi;

import org.openjproxy.xa.pool.XABackendSession;

import javax.sql.XADataSource;
import java.sql.SQLException;
import java.util.Map;

/**
 * Service Provider Interface (SPI) for XA connection pool implementations.
 *
 * <p>This interface defines the contract for pluggable XA connection pool providers
 * in OJP. Unlike {@code ConnectionPoolProvider} which manages standard {@code DataSource}
 * pooling, this SPI manages {@code XADataSource} instances for distributed transactions.</p>
 *
 * <p>Implementations should be registered via the standard Java
 * {@link java.util.ServiceLoader} mechanism by creating a file named
 * {@code META-INF/services/org.openjproxy.xa.pool.spi.XAConnectionPoolProvider}
 * containing the fully qualified class name of the implementation.</p>
 *
 * <h2>Design Principles</h2>
 * <ul>
 *   <li><strong>Zero Vendor Dependencies:</strong> Default implementation uses reflection
 *       to configure any XADataSource without compile-time dependencies</li>
 *   <li><strong>Single Universal Provider:</strong> One implementation (Commons Pool 2)
 *       works with all databases (PostgreSQL, SQL Server, DB2, MySQL, MariaDB)</li>
 *   <li><strong>Optional Optimizations:</strong> Vendor-specific providers (Oracle UCP)
 *       can provide enhanced features for specific databases</li>
 * </ul>
 *
 * <h2>Configuration</h2>
 * <p>XA pool configuration is passed via a Map with the following canonical keys:</p>
 * <ul>
 *   <li>{@code xa.datasource.className} - Fully qualified XADataSource class name (required)</li>
 *   <li>{@code xa.url} - JDBC URL</li>
 *   <li>{@code xa.username} - Database username</li>
 *   <li>{@code xa.password} - Database password</li>
 *   <li>{@code xa.maxPoolSize} - Maximum pool size (default: 20)</li>
 *   <li>{@code xa.minIdle} - Minimum idle connections (default: 2)</li>
 *   <li>{@code xa.connectionTimeoutMs} - Connection timeout in ms (default: 30000)</li>
 *   <li>{@code xa.idleTimeoutMs} - Idle timeout in ms (default: 600000)</li>
 *   <li>{@code xa.maxLifetimeMs} - Maximum connection lifetime in ms (default: 1800000)</li>
 *   <li>{@code xa.validationQuery} - SQL validation query (optional)</li>
 * </ul>
 *
 * <p>Example implementation:</p>
 * <pre>{@code
 * public class CommonsPool2XAProvider implements XAConnectionPoolProvider {
 *     @Override
 *     public String id() {
 *         return "commons-pool2";
 *     }
 *
 *     @Override
 *     public XADataSource createXADataSource(Map<String, String> config) throws SQLException {
 *         String className = config.get("xa.datasource.className");
 *         Class<?> clazz = Class.forName(className);
 *         XADataSource xaDS = (XADataSource) clazz.getDeclaredConstructor().newInstance();
 *
 *         // Configure via reflection
 *         setProperty(xaDS, "URL", config.get("xa.url"));
 *         setProperty(xaDS, "user", config.get("xa.username"));
 *         setProperty(xaDS, "password", config.get("xa.password"));
 *
 *         return new CommonsPool2XADataSource(xaDS, config);
 *     }
 * }
 * }</pre>
 */
public interface XAConnectionPoolProvider {

    /**
     * Returns the unique identifier for this XA connection pool provider.
     *
     * <p>This ID is used to select the appropriate provider when multiple
     * implementations are available. Common conventions:</p>
     * <ul>
     *   <li>{@code "commons-pool2"} - Default universal provider</li>
     *   <li>{@code "oracle-ucp"} - Oracle Universal Connection Pool</li>
     * </ul>
     *
     * @return the unique provider identifier, never null or empty
     */
    String id();

    /**
     * Creates a new pooled XADataSource configured according to the provided settings.
     *
     * <p>The implementation should:</p>
     * <ul>
     *   <li>Instantiate the XADataSource class specified in {@code xa.datasource.className}</li>
     *   <li>Configure connection properties (URL, username, password)</li>
     *   <li>Create and configure the connection pool</li>
     *   <li>Set up validation, timeouts, and sizing parameters</li>
     *   <li>Return a pooled XADataSource wrapper</li>
     * </ul>
     *
     * <p>The returned XADataSource may wrap the actual vendor XADataSource with
     * pooling logic. Callers should use {@link #closeXADataSource} to properly
     * release resources.</p>
     *
     * @param config the XA pool configuration settings (see class javadoc for keys)
     * @return a configured pooled XADataSource, never null
     * @throws SQLException if the XADataSource cannot be created
     * @throws IllegalArgumentException if config is null or missing required keys
     * @throws ClassNotFoundException if XADataSource class cannot be found
     * @throws ReflectiveOperationException if XADataSource cannot be instantiated/configured
     */
    XADataSource createXADataSource(Map<String, String> config)
            throws SQLException, ReflectiveOperationException;

    /**
     * Closes and releases all resources associated with the pooled XADataSource.
     *
     * <p>This method should:</p>
     * <ul>
     *   <li>Close all XA connections in the pool</li>
     *   <li>Release any associated resources (threads, timers, etc.)</li>
     *   <li>Be idempotent (safe to call multiple times)</li>
     * </ul>
     *
     * <p><strong>Warning:</strong> Closing an XADataSource while transactions are
     * in PREPARED state will cause those transactions to be abandoned. Ensure all
     * transactions are completed before calling this method.</p>
     *
     * @param xaDataSource the XADataSource to close
     * @throws Exception if an error occurs during shutdown
     */
    void closeXADataSource(XADataSource xaDataSource) throws Exception;

    /**
     * Returns current statistics about the XA connection pool.
     *
     * <p>The returned map may include statistics such as:</p>
     * <ul>
     *   <li>{@code activeConnections} - number of currently active XA connections</li>
     *   <li>{@code idleConnections} - number of idle XA connections in the pool</li>
     *   <li>{@code totalConnections} - total XA connections (active + idle)</li>
     *   <li>{@code pendingThreads} - threads waiting for an XA connection</li>
     *   <li>{@code maxPoolSize} - configured maximum pool size</li>
     *   <li>{@code preparedTransactions} - count of transactions in PREPARED state (if tracked)</li>
     * </ul>
     *
     * <p>Implementations should return an empty map if statistics are not
     * available or the XADataSource is not recognized.</p>
     *
     * @param xaDataSource the XADataSource to get statistics for
     * @return a map of statistic names to values, never null
     */
    Map<String, Object> getStatistics(XADataSource xaDataSource);

    /**
     * Returns the priority of this provider for auto-selection.
     *
     * <p>When multiple providers are available and no specific provider is
     * requested, the one with the highest priority will be selected.
     * Higher values indicate higher priority.</p>
     *
     * <p>Recommended priorities:</p>
     * <ul>
     *   <li>{@code 0} - Default/universal providers (Commons Pool 2)</li>
     *   <li>{@code 50} - Database-specific optimized providers (Oracle UCP)</li>
     *   <li>{@code 100} - Reserved for future use</li>
     * </ul>
     *
     * @return the provider priority (default: 0)
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
     * <p>Example:</p>
     * <pre>{@code
     * @Override
     * public boolean isAvailable() {
     *     try {
     *         Class.forName("org.apache.commons.pool2.ObjectPool");
     *         return true;
     *     } catch (ClassNotFoundException e) {
     *         return false;
     *     }
     * }
     * }</pre>
     *
     * @return true if the provider can be used, false otherwise
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * Checks if this provider supports the specified database.
     *
     * <p>Providers can use the JDBC URL or driver class name to determine
     * if they support a particular database. This allows database-specific
     * providers to be selected automatically.</p>
     *
     * <p>Example (Oracle UCP provider):</p>
     * <pre>{@code
     * @Override
     * public boolean supportsDatabase(String jdbcUrl, String driverClassName) {
     *     return jdbcUrl != null && jdbcUrl.startsWith("jdbc:oracle:");
     * }
     * }</pre>
     *
     * <p>Universal providers should return true for all databases:</p>
     * <pre>{@code
     * @Override
     * public boolean supportsDatabase(String jdbcUrl, String driverClassName) {
     *     return true; // Commons Pool 2 works with all databases
     * }
     * }</pre>
     *
     * @param jdbcUrl the JDBC URL (may be null)
     * @param driverClassName the driver class name (may be null)
     * @return true if this provider supports the database
     */
    default boolean supportsDatabase(String jdbcUrl, String driverClassName) {
        return true; // Default: support all databases
    }

    /**
     * Borrows a backend session from the pool.
     *
     * <p>This method is called by {@link org.openjproxy.xa.pool.XATransactionRegistry}
     * when starting a new XA transaction branch (TMNOFLAGS). The returned session
     * will be bound to the transaction and held until commit or rollback.</p>
     *
     * <p>The implementation should:</p>
     * <ul>
     *   <li>Obtain an XAConnection from the pool</li>
     *   <li>Wrap it in a XABackendSession implementation</li>
     *   <li>Validate the connection if configured to do so</li>
     *   <li>Block if pool is exhausted (up to configured timeout)</li>
     * </ul>
     *
     * @param xaDataSource the XADataSource (pool) to borrow from
     * @return a backend session wrapping a pooled XAConnection, never null
     * @throws Exception if session cannot be borrowed (timeout, pool exhausted, etc.)
     */
    XABackendSession borrowSession(Object xaDataSource) throws Exception;

    /**
     * Returns a backend session to the pool after transaction completion.
     *
     * <p>This method is called after successful commit or rollback. The session
     * should be reset to a clean state before being returned to the pool.</p>
     *
     * <p>The implementation should:</p>
     * <ul>
     *   <li>Call {@link XABackendSession#reset()} to clean session state</li>
     *   <li>Return the XAConnection to the pool</li>
     *   <li>If reset() fails, invalidate the session instead</li>
     * </ul>
     *
     * <p><strong>Invariant:</strong> This method is ONLY called after the session
     * is no longer in PREPARED state (post-commit or post-rollback).</p>
     *
     * @param xaDataSource the XADataSource (pool) to return to
     * @param session the backend session to return
     * @throws Exception if session cannot be returned
     */
    void returnSession(Object xaDataSource, XABackendSession session) throws Exception;

    /**
     * Invalidates a backend session, removing it from the pool.
     *
     * <p>This method is called when a session encounters an unrecoverable error
     * (connection failure, protocol error, reset() failure, etc.).</p>
     *
     * <p>The implementation should:</p>
     * <ul>
     *   <li>Close the XAConnection</li>
     *   <li>Remove it from the pool (do not reuse)</li>
     *   <li>Update pool statistics</li>
     * </ul>
     *
     * @param xaDataSource the XADataSource (pool) containing the session
     * @param session the backend session to invalidate
     * @throws Exception if session cannot be invalidated
     */
    void invalidateSession(Object xaDataSource, XABackendSession session) throws Exception;
}
