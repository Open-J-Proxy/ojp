package org.openjproxy.datasource.hikari;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.MetricsTrackerFactory;
import com.zaxxer.hikari.metrics.PoolStats;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.Meter;
import org.openjproxy.datasource.ConnectionPoolProvider;
import org.openjproxy.datasource.PoolConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/**
 * HikariCP implementation of {@link ConnectionPoolProvider}.
 * This is the default connection pool provider for OJP.
 *
 * <p>This provider creates and manages connection pools using HikariCP,
 * the high-performance JDBC connection pool. It maps the generic
 * {@link PoolConfig} settings to HikariCP-specific configuration.</p>
 *
 * <p>The provider is registered via ServiceLoader and is selected by default
 * (highest priority) when no specific provider is requested.</p>
 *
 * <h2>Configuration Mapping</h2>
 * <ul>
 *   <li>{@code url} → {@code jdbcUrl}</li>
 *   <li>{@code username} → {@code username}</li>
 *   <li>{@code password} → {@code password}</li>
 *   <li>{@code driverClassName} → {@code driverClassName}</li>
 *   <li>{@code maxPoolSize} → {@code maximumPoolSize}</li>
 *   <li>{@code minIdle} → {@code minimumIdle}</li>
 *   <li>{@code connectionTimeoutMs} → {@code connectionTimeout}</li>
 *   <li>{@code idleTimeoutMs} → {@code idleTimeout}</li>
 *   <li>{@code maxLifetimeMs} → {@code maxLifetime}</li>
 *   <li>{@code validationQuery} → {@code connectionTestQuery}</li>
 *   <li>{@code autoCommit} → {@code autoCommit}</li>
 * </ul>
 */
public class HikariConnectionPoolProvider implements ConnectionPoolProvider {

    private static final Logger log = LoggerFactory.getLogger(HikariConnectionPoolProvider.class);

    public static final String PROVIDER_ID = "hikari";
    private static final int PRIORITY = 100; // Highest priority - default provider
    private static final String METRICS_ENABLED_KEY = "ojp.telemetry.pool.metrics.enabled";
    private static final long HIKARI_MIN_CONNECTION_TIMEOUT_MS = 250L;

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    @Override
    public DataSource createDataSource(PoolConfig config) throws SQLException {
        if (config == null) {
            throw new IllegalArgumentException("PoolConfig cannot be null");
        }

        HikariConfig hikariConfig = new HikariConfig();

        // Connection settings
        if (config.getUrl() != null) {
            hikariConfig.setJdbcUrl(config.getUrl());
        }
        if (config.getUsername() != null) {
            hikariConfig.setUsername(config.getUsername());
        }
        String password = config.getPasswordAsString();
        if (password != null) {
            hikariConfig.setPassword(password);
        }
        if (config.getDriverClassName() != null) {
            hikariConfig.setDriverClassName(config.getDriverClassName());
        }

        // Pool sizing
        hikariConfig.setMaximumPoolSize(config.getMaxPoolSize());
        hikariConfig.setMinimumIdle(config.getMinIdle());

        // Timeouts
        long configuredConnectionTimeoutMs = config.getConnectionTimeoutMs();
        long hikariConnectionTimeoutMs = Math.max(configuredConnectionTimeoutMs, HIKARI_MIN_CONNECTION_TIMEOUT_MS);
        hikariConfig.setConnectionTimeout(hikariConnectionTimeoutMs);
        hikariConfig.setIdleTimeout(config.getIdleTimeoutMs());
        hikariConfig.setMaxLifetime(config.getMaxLifetimeMs());

        if (configuredConnectionTimeoutMs < HIKARI_MIN_CONNECTION_TIMEOUT_MS) {
            log.debug("Clamped HikariCP connection timeout from {}ms to minimum {}ms",
                    configuredConnectionTimeoutMs, HIKARI_MIN_CONNECTION_TIMEOUT_MS);
        }

        // Validation
        if (config.getValidationQuery() != null && !config.getValidationQuery().isEmpty()) {
            hikariConfig.setConnectionTestQuery(config.getValidationQuery());
        }

        // Auto-commit
        hikariConfig.setAutoCommit(config.isAutoCommit());

        // Transaction isolation - configure default level for connection reset
        if (config.getDefaultTransactionIsolation() != null) {
            String isolationLevel = mapTransactionIsolationToString(config.getDefaultTransactionIsolation());
            hikariConfig.setTransactionIsolation(isolationLevel);
            log.info("Configured default transaction isolation: {} ({})",
                    isolationLevel, config.getDefaultTransactionIsolation());
        }

        // Pool name for monitoring
        String poolName = config.getMetricsPrefix() != null
                ? config.getMetricsPrefix() + "-hikari"
                : "ojp-hikari-" + System.currentTimeMillis();
        hikariConfig.setPoolName(poolName);

        // Additional HikariCP-specific settings for production use
        hikariConfig.setLeakDetectionThreshold(config.getLeakDetectionThresholdMs()); // 0 = disabled (default)
        hikariConfig.setValidationTimeout(5000);       // 5 seconds
        hikariConfig.setInitializationFailTimeout(10000); // 10 seconds
        hikariConfig.setRegisterMbeans(true);

        // Configure OpenTelemetry metrics if enabled
        String metricsEnabled = config.getProperties().get(METRICS_ENABLED_KEY);
        if (metricsEnabled == null) {
            metricsEnabled = System.getProperty(METRICS_ENABLED_KEY);
        }
        if (metricsEnabled == null || Boolean.parseBoolean(metricsEnabled)) {
            try {
                OpenTelemetry openTelemetry = getOpenTelemetryInstance();
                if (openTelemetry != null) {
                    hikariConfig.setMetricsTrackerFactory(new HikariOpenTelemetryMetricsTrackerFactory(openTelemetry, poolName));
                    log.info("OpenTelemetry metrics enabled for HikariCP pool '{}'", poolName);
                }
            } catch (Exception e) {
                log.warn("Failed to enable OpenTelemetry metrics for HikariCP: {}", e.getMessage());
            }
        }

        // Connection properties
        for (Map.Entry<String, String> entry : config.getProperties().entrySet()) {
            hikariConfig.addDataSourceProperty(entry.getKey(), entry.getValue());
        }

        log.info("Creating HikariCP DataSource '{}': url={}, maxPoolSize={}, minIdle={}, connectionTimeout={}ms",
                poolName, config.getUrl(), hikariConfig.getMaximumPoolSize(),
                hikariConfig.getMinimumIdle(), hikariConfig.getConnectionTimeout());

        try {
            return new HikariDataSource(hikariConfig);
        } catch (Exception e) {
            log.error("Failed to create HikariCP DataSource: {}", e.getMessage(), e);
            throw new SQLException("Failed to create HikariCP DataSource: " + e.getMessage(), e);
        }
    }

    /**
     * Gets OpenTelemetry instance using reflection to avoid compile-time dependency.
     */
    private OpenTelemetry getOpenTelemetryInstance() {
        try {
            Class<?> holderClass = Class.forName("org.openjproxy.xa.pool.commons.metrics.OpenTelemetryHolder");
            java.lang.reflect.Method getInstanceMethod = holderClass.getMethod("getInstance");
            return (OpenTelemetry) getInstanceMethod.invoke(null);
        } catch (Exception e) {
            log.debug("OpenTelemetry not available: {}", e.getMessage());
            return null;
        }
    }

    /**
     * OpenTelemetry metrics tracker factory for HikariCP.
     */
    private static class HikariOpenTelemetryMetricsTrackerFactory implements MetricsTrackerFactory {
        private final Meter meter;
        private final String poolName;
        private final Attributes attributes;
        private volatile PoolStats poolStats;
        private final DoubleHistogram acquisitionTimeHistogram;

        public HikariOpenTelemetryMetricsTrackerFactory(OpenTelemetry openTelemetry, String poolName) {
            this.meter = openTelemetry.getMeter("ojp.hikari.pool");
            this.poolName = poolName;
            this.attributes = Attributes.of(AttributeKey.stringKey("pool.name"), poolName);

            log.info("Registering OpenTelemetry gauges for HikariCP pool '{}'", poolName);

            // Histogram for connection acquisition time
            this.acquisitionTimeHistogram = meter.histogramBuilder("ojp.hikari.pool.connections.acquisition.time")
                    .setDescription("Time in milliseconds to acquire a connection from the HikariCP pool")
                    .setUnit("ms")
                    .build();

            // Register standardized pool metrics (aligned suffix naming with XA pools)
            meter.gaugeBuilder("ojp.hikari.pool.connections.active")
                    .setDescription("Number of active (borrowed) connections")
                    .setUnit("connections")
                    .buildWithCallback(measurement -> {
                        PoolStats stats = poolStats;
                        if (stats != null) {
                            measurement.record(stats.getActiveConnections(), attributes);
                        }
                    });

            meter.gaugeBuilder("ojp.hikari.pool.connections.idle")
                    .setDescription("Number of idle connections in pool")
                    .setUnit("connections")
                    .buildWithCallback(measurement -> {
                        PoolStats stats = poolStats;
                        if (stats != null) {
                            measurement.record(stats.getIdleConnections(), attributes);
                        }
                    });

            meter.gaugeBuilder("ojp.hikari.pool.connections.total")
                    .setDescription("Total connections (active + idle)")
                    .setUnit("connections")
                    .buildWithCallback(measurement -> {
                        PoolStats stats = poolStats;
                        if (stats != null) {
                            measurement.record(stats.getTotalConnections(), attributes);
                        }
                    });

            meter.gaugeBuilder("ojp.hikari.pool.connections.pending")
                    .setDescription("Number of threads waiting for connections")
                    .setUnit("threads")
                    .buildWithCallback(measurement -> {
                        PoolStats stats = poolStats;
                        if (stats != null) {
                            measurement.record(stats.getPendingThreads(), attributes);
                        }
                    });

            meter.gaugeBuilder("ojp.hikari.pool.connections.max")
                    .setDescription("Maximum pool size")
                    .setUnit("connections")
                    .buildWithCallback(measurement -> {
                        PoolStats stats = poolStats;
                        if (stats != null) {
                            measurement.record(stats.getMaxConnections(), attributes);
                        }
                    });

            meter.gaugeBuilder("ojp.hikari.pool.connections.min")
                    .setDescription("Minimum idle connections")
                    .setUnit("connections")
                    .buildWithCallback(measurement -> {
                        PoolStats stats = poolStats;
                        if (stats != null) {
                            measurement.record(stats.getMinConnections(), attributes);
                        }
                    });
        }

        @Override
        public com.zaxxer.hikari.metrics.IMetricsTracker create(String poolName, PoolStats poolStats) {
            // Store the PoolStats reference so gauges can read from it
            this.poolStats = poolStats;
            log.info("HikariCP pool '{}' metrics tracker created - metrics will be available", poolName);

            return new com.zaxxer.hikari.metrics.IMetricsTracker() {
                @Override
                public void recordConnectionAcquiredNanos(long elapsedAcquiredNanos) {
                    acquisitionTimeHistogram.record(elapsedAcquiredNanos / 1_000_000.0, attributes);
                }

                @Override
                public void recordConnectionUsageMillis(long elapsedBorrowedMillis) {
                    // Can add histogram/counter for usage time if needed
                }

                @Override
                public void recordConnectionTimeout() {
                    // Can add counter for timeouts if needed
                }

                @Override
                public void close() {
                    // Cleanup if needed
                }
            };
        }
    }

    @Override
    public void closeDataSource(DataSource dataSource) throws Exception {
        if (dataSource instanceof HikariDataSource) {
            HikariDataSource hikariDataSource = (HikariDataSource) dataSource;
            log.info("Closing HikariCP DataSource '{}': active={}, idle={}, total={}",
                    hikariDataSource.getPoolName(),
                    hikariDataSource.getHikariPoolMXBean() != null ? hikariDataSource.getHikariPoolMXBean().getActiveConnections() : 0,
                    hikariDataSource.getHikariPoolMXBean() != null ? hikariDataSource.getHikariPoolMXBean().getIdleConnections() : 0,
                    hikariDataSource.getHikariPoolMXBean() != null ? hikariDataSource.getHikariPoolMXBean().getTotalConnections() : 0);
            hikariDataSource.close();
        } else if (dataSource != null) {
            log.warn("Cannot close DataSource: not a HikariDataSource instance ({})",
                    dataSource.getClass().getName());
        }
    }

    @Override
    public Map<String, Object> getStatistics(DataSource dataSource) {
        Map<String, Object> stats = new HashMap<>();

        if (dataSource instanceof HikariDataSource) {
            HikariDataSource hikariDataSource = (HikariDataSource) dataSource;

            stats.put("poolName", hikariDataSource.getPoolName());
            stats.put("maxPoolSize", hikariDataSource.getMaximumPoolSize());
            stats.put("minIdle", hikariDataSource.getMinimumIdle());
            stats.put("connectionTimeout", hikariDataSource.getConnectionTimeout());
            stats.put("idleTimeout", hikariDataSource.getIdleTimeout());
            stats.put("maxLifetime", hikariDataSource.getMaxLifetime());
            stats.put("isClosed", hikariDataSource.isClosed());

            // Runtime statistics from MXBean
            if (hikariDataSource.getHikariPoolMXBean() != null) {
                stats.put("activeConnections", hikariDataSource.getHikariPoolMXBean().getActiveConnections());
                stats.put("idleConnections", hikariDataSource.getHikariPoolMXBean().getIdleConnections());
                stats.put("totalConnections", hikariDataSource.getHikariPoolMXBean().getTotalConnections());
                stats.put("threadsAwaitingConnection", hikariDataSource.getHikariPoolMXBean().getThreadsAwaitingConnection());
            }
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
            Class.forName("com.zaxxer.hikari.HikariDataSource");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * Maps JDBC transaction isolation level constant to HikariCP string format.
     * HikariCP expects transaction isolation levels as strings like "TRANSACTION_READ_COMMITTED".
     *
     * @param isolationLevel JDBC constant (e.g., Connection.TRANSACTION_READ_COMMITTED)
     * @return HikariCP string format (e.g., "TRANSACTION_READ_COMMITTED")
     */
    private static String mapTransactionIsolationToString(int isolationLevel) {
        switch (isolationLevel) {
            case java.sql.Connection.TRANSACTION_NONE:
                return "TRANSACTION_NONE";
            case java.sql.Connection.TRANSACTION_READ_UNCOMMITTED:
                return "TRANSACTION_READ_UNCOMMITTED";
            case java.sql.Connection.TRANSACTION_READ_COMMITTED:
                return "TRANSACTION_READ_COMMITTED";
            case java.sql.Connection.TRANSACTION_REPEATABLE_READ:
                return "TRANSACTION_REPEATABLE_READ";
            case java.sql.Connection.TRANSACTION_SERIALIZABLE:
                return "TRANSACTION_SERIALIZABLE";
            default:
                throw new IllegalArgumentException("Unknown transaction isolation level: " + isolationLevel);
        }
    }
}
