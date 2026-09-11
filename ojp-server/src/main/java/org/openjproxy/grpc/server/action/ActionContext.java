package org.openjproxy.grpc.server.action;

import com.openjproxy.grpc.DbName;
import org.openjproxy.grpc.server.MultinodeXaCoordinator;
import org.openjproxy.grpc.server.ClusterHealthTracker;
import org.openjproxy.grpc.server.SessionManager;
import org.openjproxy.grpc.server.CircuitBreakerRegistry;
import org.openjproxy.grpc.server.ServerConfiguration;
import org.openjproxy.grpc.server.AdmissionControlManager;
import org.openjproxy.grpc.server.UnpooledConnectionDetails;
import org.openjproxy.grpc.server.metrics.SqlStatementMetrics;
import org.openjproxy.grpc.server.sql.SqlEnhancerEngine;
import org.openjproxy.xa.pool.XATransactionRegistry;
import org.openjproxy.xa.pool.spi.XAConnectionPoolProvider;

import javax.sql.DataSource;
import javax.sql.XADataSource;
import java.util.Map;

/**
 * ActionContext holds all shared state and dependencies needed by Action classes.
 * This context is created once in StatementServiceImpl and passed to all actions.
 * Thread Safety: This class is thread-safe. All maps are ConcurrentHashMap.
 * Actions should not modify the context itself, only the data within the maps.
 */
public class ActionContext {

    // ========== Data Source Management ==========

    /**
     * Map of connection hash to regular DataSource (HikariCP by default).
     * Key: connection hash (from ConnectionHashGenerator)
     * Value: pooled DataSource for regular (non-XA) connections
     */
    private final Map<String, DataSource> datasourceMap;

    /**
     * Map of connection hash to unpooled XADataSource.
     * Used when XA pooling is disabled (ojp.xa.connection.pool.enabled=false).
     * Key: connection hash
     * Value: native database XADataSource (not pooled)
     */
    private final Map<String, XADataSource> xaDataSourceMap;

    /**
     * Map of connection hash to XATransactionRegistry.
     * Used when XA pooling is enabled (default).
     * Key: connection hash
     * Value: registry managing pooled XA connections and transactions
     */
    private final Map<String, XATransactionRegistry> xaRegistries;

    /**
     * Map of connection hash to unpooled connection details.
     * Used when regular pooling is disabled (ojp.connection.pool.enabled=false).
     * Key: connection hash
     * Value: connection details for creating direct JDBC connections
     */
    private final Map<String, UnpooledConnectionDetails> unpooledConnectionDetailsMap;

    /**
     * Map of connection hash to database type.
     * Used for database-specific behavior (e.g., DB2 LOB handling).
     * Key: connection hash
     * Value: DbName enum (POSTGRES, ORACLE, MYSQL, etc.)
     */
    private final Map<String, DbName> dbNameMap;

    // ========== Query Management ==========

    /**
     * Map of connection hash to AdmissionControlManager.
     * Each datasource gets its own manager for admission control and optional slow/fast segregation.
     * Key: connection hash
     * Value: manager for this datasource
     */
    private final Map<String, AdmissionControlManager> admissionControlManagers;

    /**
     * Map of connection hash to CacheConfiguration.
     * Stores cache configuration for each datasource connection.
     * Key: connection hash
     * Value: cache configuration for query result caching
     */
    private final Map<String, org.openjproxy.grpc.server.cache.CacheConfiguration> cacheConfigurationMap;

    // ========== Read/Write Splitting ==========

    /**
     * Registry for managing primary and replica datasources for read/write splitting.
     * Thread-safe, shared across all actions.
     */
    private final org.openjproxy.grpc.server.readwrite.ReadWriteDataSourceRegistry readWriteDataSourceRegistry;


    // ========== XA Pool Provider ==========

    /**
     * XA Connection Pool Provider loaded via SPI.
     * Used for creating and managing pooled XA connections.
     * Mutable because it's initialized after construction.
     */
    private XAConnectionPoolProvider xaPoolProvider;

    // ========== Coordinators & Trackers ==========

    /**
     * Multinode XA coordinator for distributing transaction limits across nodes.
     * Static in original class, shared across all instances.
     */
    private final MultinodeXaCoordinator xaCoordinator;

    /**
     * Cluster health tracker for monitoring health changes and triggering rebalancing.
     */
    private final ClusterHealthTracker clusterHealthTracker;

    // ========== Service Dependencies ==========

    /**
     * Session manager for managing JDBC sessions, connections, and resources.
     * Thread-safe, shared across all actions.
     */
    private final SessionManager sessionManager;

    /**
     * Registry of circuit breakers providing isolated protection against cascading failures
     * per datasource. Thread-safe and shared across all actions to ensure consistent
     * state management.
     */
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    /**
     * Server-wide configuration.
     * Immutable after construction.
     */
    private final ServerConfiguration serverConfiguration;


    /**
     * SQL statement metrics from the registered OpenTelemetry instance
     */
    private final SqlStatementMetrics sqlStatementMetrics;

    /**
     *  SQL enhancer engine for parsing and enhancing SQL statements.
     */
    private final SqlEnhancerEngine sqlEnhancerEngine;

    // ========== Constructors ==========

    public ActionContext(
            Map<String, DataSource> datasourceMap,
            Map<String, XADataSource> xaDataSourceMap,
            Map<String, XATransactionRegistry> xaRegistries,
            Map<String, UnpooledConnectionDetails> unpooledConnectionDetailsMap,
            Map<String, DbName> dbNameMap,
            Map<String, AdmissionControlManager> admissionControlManagers,
            Map<String, org.openjproxy.grpc.server.cache.CacheConfiguration> cacheConfigurationMap,
            org.openjproxy.grpc.server.readwrite.ReadWriteDataSourceRegistry readWriteDataSourceRegistry,
            XAConnectionPoolProvider xaPoolProvider,
            MultinodeXaCoordinator xaCoordinator,
            ClusterHealthTracker clusterHealthTracker,
            SessionManager sessionManager,
            CircuitBreakerRegistry circuitBreakerRegistry,
            ServerConfiguration serverConfiguration,
            SqlStatementMetrics sqlStatementMetrics, SqlEnhancerEngine sqlEnhancerEngine) {

        this.datasourceMap = datasourceMap;
        this.xaDataSourceMap = xaDataSourceMap;
        this.xaRegistries = xaRegistries;
        this.unpooledConnectionDetailsMap = unpooledConnectionDetailsMap;
        this.dbNameMap = dbNameMap;
        this.admissionControlManagers = admissionControlManagers;
        this.cacheConfigurationMap = cacheConfigurationMap;
        this.readWriteDataSourceRegistry = readWriteDataSourceRegistry;
        this.xaPoolProvider = xaPoolProvider;
        this.xaCoordinator = xaCoordinator;
        this.clusterHealthTracker = clusterHealthTracker;
        this.sessionManager = sessionManager;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.serverConfiguration = serverConfiguration;
        this.sqlStatementMetrics = sqlStatementMetrics;
        this.sqlEnhancerEngine = sqlEnhancerEngine;
    }

    // ========== Getters ==========

    public Map<String, DataSource> getDatasourceMap() {
        return datasourceMap;
    }

    public Map<String, XADataSource> getXaDataSourceMap() {
        return xaDataSourceMap;
    }

    public Map<String, XATransactionRegistry> getXaRegistries() {
        return xaRegistries;
    }

    public Map<String, UnpooledConnectionDetails> getUnpooledConnectionDetailsMap() {
        return unpooledConnectionDetailsMap;
    }

    public Map<String, DbName> getDbNameMap() {
        return dbNameMap;
    }

    public Map<String, AdmissionControlManager> getAdmissionControlManagers() {
        return admissionControlManagers;
    }

    public Map<String, org.openjproxy.grpc.server.cache.CacheConfiguration> getCacheConfigurationMap() {
        return cacheConfigurationMap;
    }
    public org.openjproxy.grpc.server.readwrite.ReadWriteDataSourceRegistry getReadWriteDataSourceRegistry() {
        return readWriteDataSourceRegistry;
    }


    public XAConnectionPoolProvider getXaPoolProvider() {
        return xaPoolProvider;
    }

    public void setXaPoolProvider(XAConnectionPoolProvider xaPoolProvider) {
        this.xaPoolProvider = xaPoolProvider;
    }

    public MultinodeXaCoordinator getXaCoordinator() {
        return xaCoordinator;
    }

    public ClusterHealthTracker getClusterHealthTracker() {
        return clusterHealthTracker;
    }

    public SessionManager getSessionManager() {
        return sessionManager;
    }


    public CircuitBreakerRegistry getCircuitBreakerRegistry() {
        return circuitBreakerRegistry;
    }

    public ServerConfiguration getServerConfiguration() {
        return serverConfiguration;
    }

    public SqlStatementMetrics getSqlStatementMetrics() {
        return sqlStatementMetrics;
    }

    public SqlEnhancerEngine getSqlEnhancerEngine() {
        return sqlEnhancerEngine;
    }
}
