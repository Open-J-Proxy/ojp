package org.openjproxy.grpc.server;

import com.zaxxer.hikari.HikariDataSource;
import io.grpc.Context;
import io.grpc.Deadline;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.datasource.ConnectionPoolProviderRegistry;
import org.openjproxy.xa.pool.commons.metrics.PoolMetrics;
import org.openjproxy.xa.pool.commons.metrics.NoOpPoolMetrics;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Manages connection acquisition with bounded retries for recognized pool-capacity timeouts.
 * This class preserves pool-owned creation and maximum limits while reporting terminal failures.
 *
 * ISSUE #29 FIX: This class was created to resolve the problem where OJP would
 * block indefinitely under high concurrent load (200+ threads) when the connection
 * pool was exhausted. The solution relies on pool implementation's built-in timeout
 * mechanisms while providing enhanced error reporting with pool statistics.
 *
 * Retries require provider statistics that include idle, total, and maximum pool sizes.
 *
 * @see <a href="https://github.com/Open-J-Proxy/ojp/issues/29">Issue #29</a>
 */
@Slf4j
public class ConnectionAcquisitionManager {
    private static final String SQLSTATE_CONNECTION_CLASS = "08";
    private static final String SQLSTATE_CONNECTION_FAILURE = "08001";
    private static final long RETRY_DELAY_MIN_MS = 20L;
    private static final long RETRY_DELAY_MAX_MS = 50L;
    private static final ThreadLocal<Long> ACQUISITION_DEADLINE_NANOS = new ThreadLocal<>();

    /**
     * Starts an acquisition deadline, bounded by the current gRPC deadline when present.
     * A nested scope retains the earlier deadline.
     *
     * @param timeoutMs the maximum time for admission and acquisition in milliseconds
     * @return a scope that restores the prior deadline when closed
     */
    public static DeadlineScope beginAcquisitionDeadline(long timeoutMs) {
        long start = System.nanoTime();
        Long previousDeadline = ACQUISITION_DEADLINE_NANOS.get();
        long deadlineNanos = previousDeadline == null
                ? start + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMs))
                : previousDeadline;

        if (previousDeadline == null) {
            Deadline rpcDeadline = Context.current().getDeadline();
            if (rpcDeadline != null) {
                long rpcRemainingNanos = Math.max(0L, rpcDeadline.timeRemaining(TimeUnit.NANOSECONDS));
                if (rpcRemainingNanos < deadlineNanos - start) {
                    deadlineNanos = start + rpcRemainingNanos;
                }
            }
        }
        ACQUISITION_DEADLINE_NANOS.set(deadlineNanos);
        return new DeadlineScope(previousDeadline);
    }

    static long remainingAcquisitionNanos() {
        Long deadlineNanos = ACQUISITION_DEADLINE_NANOS.get();
        return deadlineNanos == null ? Long.MAX_VALUE : deadlineNanos - System.nanoTime();
    }

    /**
     * Returns the rounded-up remaining acquisition budget.
     *
     * @return remaining milliseconds, or zero when expired
     */
    public static long remainingAcquisitionTimeoutMs() {
        long remainingNanos = remainingAcquisitionNanos();
        return remainingNanos <= 0L ? 0L
                : TimeUnit.NANOSECONDS.toMillis(remainingNanos) + 1L;
    }

    /**
     * Checks whether the request context was cancelled or the active deadline expired.
     *
     * @return true when acquisition must stop
     */
    public static boolean isAcquisitionCancelledOrExpired() {
        return Context.current().isCancelled()
                || (hasAcquisitionDeadline() && remainingAcquisitionNanos() <= 0L);
    }

    private static boolean hasAcquisitionDeadline() {
        return ACQUISITION_DEADLINE_NANOS.get() != null;
    }

    public static final class DeadlineScope implements AutoCloseable {
        private final Long previousDeadline;

        private DeadlineScope(Long previousDeadline) {
            this.previousDeadline = previousDeadline;
        }

        @Override
        public void close() {
            if (previousDeadline == null) {
                ACQUISITION_DEADLINE_NANOS.remove();
            } else {
                ACQUISITION_DEADLINE_NANOS.set(previousDeadline);
            }
        }
    }

    /**
     * Acquires a connection from the given datasource with enhanced error reporting.
     * This method relies on the pool implementation's built-in connection timeout mechanism
     * to prevent indefinite blocking, while providing detailed error messages with pool statistics.
     *
     * @param dataSource the datasource (supports HikariCP for enhanced statistics)
     * @param connectionHash the connection hash for logging purposes
     * @return a database connection
     * @throws SQLException if connection acquisition fails or times out
     */
    public static Connection acquireConnection(DataSource dataSource, String connectionHash) throws SQLException {
        return acquireConnection(dataSource, connectionHash, NoOpPoolMetrics.INSTANCE, connectionHash);
    }

    /**
     * Acquires a connection from the given datasource with enhanced error reporting and metrics.
     * This overload additionally records:
     * <ul>
     *   <li>The queue depth (threads waiting for a connection) immediately before acquiring</li>
     *   <li>The time spent waiting to acquire the connection</li>
     * </ul>
     *
     * @param dataSource     the datasource (supports HikariCP for enhanced statistics)
     * @param connectionHash the connection hash for logging purposes
     * @param poolMetrics    the metrics collector to record acquisition telemetry
     * @param poolName       the pool name label used when recording metrics
     * @return a database connection
     * @throws SQLException if connection acquisition fails or times out
     */
    public static Connection acquireConnection(DataSource dataSource, String connectionHash,
            PoolMetrics poolMetrics, String poolName) throws SQLException {
        if (dataSource == null) {
            throw new SQLException("DataSource is null for connection hash: " + connectionHash);
        }

        // Capture pool state and queue depth before attempting acquisition (HikariCP-specific).
        // JMX bean calls acquire a brief lock on the pool; only invoke them when debug logging
        // is enabled or a real metrics collector is configured to avoid contention on every
        // connection acquisition under load.
        if (dataSource instanceof HikariDataSource) {
            HikariDataSource hikariDataSource = (HikariDataSource) dataSource;
            if (log.isDebugEnabled() || !(poolMetrics instanceof NoOpPoolMetrics)) {
                try {
                    int activeConnections = hikariDataSource.getHikariPoolMXBean().getActiveConnections();
                    int idleConnections = hikariDataSource.getHikariPoolMXBean().getIdleConnections();
                    int totalConnections = hikariDataSource.getHikariPoolMXBean().getTotalConnections();
                    int threadsWaiting = hikariDataSource.getHikariPoolMXBean().getThreadsAwaitingConnection();
                    int maxPoolSize = hikariDataSource.getMaximumPoolSize();
                    int minIdle = hikariDataSource.getMinimumIdle();

                    log.debug("Connection acquisition attempt for hash: {} - Active: {}, Idle: {}, Total: {}, Waiting: {}",
                        connectionHash, activeConnections, idleConnections, totalConnections, threadsWaiting);

                    // Emit current pool state metrics (includes queue depth via numWaiters)
                    poolMetrics.recordPoolState(poolName,
                            activeConnections, idleConnections, threadsWaiting,
                            maxPoolSize, minIdle, 0L, 0L, 0L, 0L);
                } catch (Exception e) {
                    log.debug("Could not retrieve pool statistics for hash: {}", connectionHash);
                }
            }
        } else {
            log.debug("Connection acquisition attempt for hash: {} using {}",
                connectionHash, dataSource.getClass().getSimpleName());
        }

        if (dataSource instanceof HikariDataSource) {
            HikariDataSource hikariDataSource = (HikariDataSource) dataSource;
            try {
                int activeConnections = hikariDataSource.getHikariPoolMXBean().getActiveConnections();
                int idleConnections = hikariDataSource.getHikariPoolMXBean().getIdleConnections();
                int totalConnections = hikariDataSource.getHikariPoolMXBean().getTotalConnections();
                int maxPoolSize = hikariDataSource.getMaximumPoolSize();
                int waitingThreads = hikariDataSource.getHikariPoolMXBean().getThreadsAwaitingConnection();
                long configuredTimeoutMs = hikariDataSource.getConnectionTimeout();

                if (idleConnections == 0 && totalConnections >= maxPoolSize && activeConnections > 0) {
                    String message = String.format(
                            "Connection acquisition pre-check failed for hash: %s. Pool exhausted (idle=0, total=%d, max=%d, active=%d, waiting=%d, poolTimeoutMs=%d). Request will not wait at pool level.",
                            connectionHash, totalConnections, maxPoolSize, activeConnections, waitingThreads, configuredTimeoutMs);
                    poolMetrics.recordPoolExhaustion(poolName + "|phase=admission_gate");
                    log.error(message);
                    throw new SQLTransientConnectionException(message, SQLSTATE_CONNECTION_FAILURE);
                }
            } catch (SQLException e) {
                throw e;
            } catch (Exception e) {
                String message = String.format(
                        "Cannot evaluate pool state for hash: %s (phase=pool_precheck). Refusing borrow attempt due to pre-check failure to avoid hidden blocking path.",
                        connectionHash);
                log.error(message, e);
                throw new SQLException(message, e);
            }
        }

        long acquisitionStart = System.nanoTime();
        int attempts = 0;
        while (true) {
            attempts++;
            try {
                if (isAcquisitionCancelledOrExpired()) {
                    throw new SQLTransientConnectionException(
                            "Connection acquisition deadline expired or request cancelled for hash: "
                                    + connectionHash,
                            SQLSTATE_CONNECTION_FAILURE);
                }
                Connection connection = dataSource.getConnection();
                if (isAcquisitionCancelledOrExpired()) {
                    try {
                        connection.close();
                    } catch (SQLException closeException) {
                        log.warn("Could not close connection acquired after deadline for hash: {}",
                                connectionHash, closeException);
                    }
                    throw new SQLTransientConnectionException(
                            "Connection acquired after deadline or request cancellation for hash: "
                                    + connectionHash,
                            SQLSTATE_CONNECTION_FAILURE);
                }

                long acquisitionTimeMs = (System.nanoTime() - acquisitionStart) / 1_000_000L;
                log.debug("Successfully acquired connection for hash: {} in thread: {} (waited {}ms, attempts={})",
                        connectionHash, Thread.currentThread().getName(), acquisitionTimeMs, attempts);
                poolMetrics.recordConnectionAcquisitionTime(poolName, acquisitionTimeMs);
                return connection;
            } catch (SQLException e) {
                if (shouldRetry(dataSource, e) && waitBeforeRetry()) {
                    log.debug("Retrying capacity-limited connection acquisition for hash: {} (attempt={})",
                            connectionHash, attempts + 1);
                    continue;
                }

            // Enhanced error message with pool statistics (HikariCP-specific)
            String enhancedMessage;
            if (dataSource instanceof HikariDataSource) {
                HikariDataSource hikariDataSource = (HikariDataSource) dataSource;
                try {
                    enhancedMessage = String.format(
                        "Connection acquisition failed for hash: %s (phase=pool_borrow, poolTimeoutMs=%d). Pool state - Active: %d, Max: %d, Waiting threads: %d. Original error: %s",
                        connectionHash,
                        hikariDataSource.getConnectionTimeout(),
                        hikariDataSource.getHikariPoolMXBean().getActiveConnections(),
                        hikariDataSource.getMaximumPoolSize(),
                        hikariDataSource.getHikariPoolMXBean().getThreadsAwaitingConnection(),
                        e.getMessage()
                    );
                } catch (Exception poolStatsException) {
                    enhancedMessage = String.format(
                        "Connection acquisition failed for hash: %s. Could not retrieve pool statistics. Original error: %s",
                        connectionHash, e.getMessage()
                    );
                }
            } else {
                enhancedMessage = String.format(
                    "Connection acquisition failed for hash: %s. Original error: %s",
                    connectionHash, e.getMessage()
                );
            }

            // Record exhaustion event when acquisition fails
            poolMetrics.recordPoolExhaustion(poolName + "|phase=pool_borrow");

            log.error(enhancedMessage);
            String sqlState = e.getSQLState();
            if (sqlState == null || sqlState.length() != 5 || !sqlState.startsWith(SQLSTATE_CONNECTION_CLASS)) {
                sqlState = SQLSTATE_CONNECTION_FAILURE;
            }
            throw new SQLTransientConnectionException(enhancedMessage, sqlState, 0, e);
            }
        }
    }

    private static boolean shouldRetry(DataSource dataSource, SQLException exception) {
        long remainingNanos = remainingAcquisitionNanos();
        if (!hasAcquisitionDeadline() || remainingNanos <= 0L || Context.current().isCancelled()
                || !isCapacityTimeout(dataSource, exception)) {
            return false;
        }
        if (dataSource instanceof HikariDataSource hikariDataSource) {
            try {
                if (remainingNanos < TimeUnit.MILLISECONDS.toNanos(hikariDataSource.getConnectionTimeout())) {
                    return false;
                }
            } catch (RuntimeException e) {
                return false;
            }
        }

        try {
            for (var provider : ConnectionPoolProviderRegistry.getProviders().values()) {
                Map<String, Object> statistics = provider.getStatistics(dataSource);
                Object idleValue = statistics.get("idleConnections");
                Object totalValue = statistics.get("totalConnections");
                Object maximumValue = statistics.get("maxPoolSize");
                if (!(idleValue instanceof Number) || !(totalValue instanceof Number)
                        || !(maximumValue instanceof Number)
                        || Boolean.TRUE.equals(statistics.get("isClosed"))) {
                    continue;
                }
                int idle = ((Number) idleValue).intValue();
                int total = ((Number) totalValue).intValue();
                int maximum = ((Number) maximumValue).intValue();
                return idle > 0 || (total >= 0 && maximum > 0 && total < maximum);
            }
            return false;
        } catch (RuntimeException e) {
            log.debug("Could not retrieve pool statistics while deciding whether to retry", e);
            return false;
        }
    }

    private static boolean isCapacityTimeout(DataSource dataSource, SQLException exception) {
        if (dataSource instanceof HikariDataSource) {
            String message = exception.getMessage();
            return exception instanceof SQLTransientConnectionException
                    && message != null && message.toLowerCase(Locale.ROOT).contains("timed out");
        }

        Throwable cause = exception;
        while (cause != null) {
            if ("java.util.NoSuchElementException".equals(cause.getClass().getName())) {
                String message = cause.getMessage();
                return message != null
                        && message.toLowerCase(Locale.ROOT).contains("timeout waiting for idle object");
            }
            cause = cause.getCause();
        }
        return false;
    }

    private static boolean waitBeforeRetry() throws SQLException {
        long remainingNanos = remainingAcquisitionNanos();
        if (remainingNanos <= 0L) {
            return false;
        }
        long delayMs = ThreadLocalRandom.current().nextLong(RETRY_DELAY_MIN_MS, RETRY_DELAY_MAX_MS + 1L);
        long delayNanos = TimeUnit.MILLISECONDS.toNanos(delayMs);
        long sleepNanos = Math.min(delayNanos, remainingNanos);
        try {
            TimeUnit.NANOSECONDS.sleep(sleepNanos);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted while waiting to retry connection acquisition", e);
        }
        return remainingAcquisitionNanos() > 0L;
    }
}
