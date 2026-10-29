package org.openjproxy.datasource.dbcp;

import org.apache.commons.dbcp2.BasicDataSource;
import org.openjproxy.datasource.ConnectionPoolProvider;
import org.openjproxy.datasource.PoolConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Apache Commons DBCP2 implementation of {@link ConnectionPoolProvider}.
 *
 * <p>This provider creates and manages connection pools using Apache Commons DBCP2.
 * It maps the generic {@link PoolConfig} settings to DBCP2-specific configuration.</p>
 *
 * <p>The provider is registered via ServiceLoader and can be used by including this
 * module on the classpath. The provider ID is "dbcp".</p>
 *
 * <h2>Configuration Mapping</h2>
 * <ul>
 *   <li>{@code maxPoolSize} → {@code maxTotal}</li>
 *   <li>{@code minIdle} → {@code minIdle}</li>
 *   <li>{@code connectionTimeoutMs} → {@code maxWait}</li>
 *   <li>{@code idleTimeoutMs} → {@code minEvictableIdleTimeMillis}</li>
 *   <li>{@code maxLifetimeMs} → {@code maxConnLifetimeMillis}</li>
 *   <li>{@code validationQuery} → {@code validationQuery}</li>
 *   <li>{@code autoCommit} → {@code defaultAutoCommit}</li>
 * </ul>
 */
public class DbcpConnectionPoolProvider implements ConnectionPoolProvider {

    private static final Logger log = LoggerFactory.getLogger(DbcpConnectionPoolProvider.class);

    public static final String PROVIDER_ID = "dbcp";
    private static final int PRIORITY = 10;

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    @Override
    public DataSource createDataSource(PoolConfig config) throws SQLException {
        if (config == null) {
            throw new IllegalArgumentException("PoolConfig cannot be null");
        }

        BasicDataSource dataSource = new BasicDataSource();

        // Connection settings
        if (config.getUrl() != null) {
            dataSource.setUrl(config.getUrl());
        }
        if (config.getUsername() != null) {
            dataSource.setUsername(config.getUsername());
        }
        String password = config.getPasswordAsString();
        if (password != null) {
            dataSource.setPassword(password);
        }
        if (config.getDriverClassName() != null) {
            dataSource.setDriverClassName(config.getDriverClassName());
        }

        // Pool sizing
        dataSource.setMaxTotal(config.getMaxPoolSize());
        dataSource.setMinIdle(config.getMinIdle());
        dataSource.setMaxIdle(config.getMaxPoolSize()); // Default maxIdle to maxPoolSize

        // Timeouts
        dataSource.setMaxWait(Duration.ofMillis(config.getConnectionTimeoutMs()));
        dataSource.setMinEvictableIdle(Duration.ofMillis(config.getIdleTimeoutMs()));
        dataSource.setMaxConn(Duration.ofMillis(config.getMaxLifetimeMs()));

        // Validation
        if (config.getValidationQuery() != null && !config.getValidationQuery().isEmpty()) {
            dataSource.setValidationQuery(config.getValidationQuery());
            dataSource.setTestOnBorrow(true);
            dataSource.setTestWhileIdle(true);
        }

        // Auto-commit
        dataSource.setDefaultAutoCommit(config.isAutoCommit());

        // Transaction isolation - configure default level for connection reset
        if (config.getDefaultTransactionIsolation() != null) {
            dataSource.setDefaultTransactionIsolation(config.getDefaultTransactionIsolation());
            log.info("Configured default transaction isolation: {}", config.getDefaultTransactionIsolation());
        }

        // Connection properties
        for (Map.Entry<String, String> entry : config.getProperties().entrySet()) {
            dataSource.addConnectionProperty(entry.getKey(), entry.getValue());
        }

        // Enable eviction
        dataSource.setDurationBetweenEvictionRuns(Duration.ofSeconds(30));
        dataSource.setNumTestsPerEvictionRun(3);

        // Pool name for logging
        String poolName = config.getMetricsPrefix() != null
                ? config.getMetricsPrefix() + "-dbcp"
                : "ojp-dbcp-" + System.currentTimeMillis();

        log.info("Created DBCP DataSource '{}': url={}, maxTotal={}, minIdle={}, maxWait={}ms",
                poolName, config.getUrl(), dataSource.getMaxTotal(),
                dataSource.getMinIdle(), config.getConnectionTimeoutMs());

        return dataSource;
    }

    @Override
    public void closeDataSource(DataSource dataSource) throws Exception {
        if (dataSource instanceof BasicDataSource) {
            BasicDataSource basicDataSource = (BasicDataSource) dataSource;
            log.info("Closing DBCP DataSource: active={}, idle={}",
                    basicDataSource.getNumActive(), basicDataSource.getNumIdle());
            basicDataSource.close();
        } else if (dataSource != null) {
            log.warn("Cannot close DataSource: not a BasicDataSource instance ({})",
                    dataSource.getClass().getName());
        }
    }

    @Override
    public Map<String, Object> getStatistics(DataSource dataSource) {
        Map<String, Object> stats = new HashMap<>();

        if (dataSource instanceof BasicDataSource) {
            BasicDataSource basicDataSource = (BasicDataSource) dataSource;

            stats.put("activeConnections", basicDataSource.getNumActive());
            stats.put("idleConnections", basicDataSource.getNumIdle());
            stats.put("totalConnections", basicDataSource.getNumActive() + basicDataSource.getNumIdle());
            stats.put("maxPoolSize", basicDataSource.getMaxTotal());
            stats.put("minIdle", basicDataSource.getMinIdle());
            stats.put("maxIdle", basicDataSource.getMaxIdle());
            stats.put("maxWaitMs", basicDataSource.getMaxWaitDuration().toMillis());
            stats.put("isClosed", basicDataSource.isClosed());
        }

        return stats;
    }

    @Override
    public int getPriority() {
        return PRIORITY;
    }

    @Override
    public boolean isAvailable() {
        try {
            Class.forName("org.apache.commons.dbcp2.BasicDataSource");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
