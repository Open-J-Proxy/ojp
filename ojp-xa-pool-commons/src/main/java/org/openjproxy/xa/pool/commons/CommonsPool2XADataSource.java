package org.openjproxy.xa.pool.commons;

import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.openjproxy.xa.pool.XABackendSession;
import org.openjproxy.xa.pool.commons.housekeeping.BorrowInfo;
import org.openjproxy.xa.pool.commons.housekeeping.DiagnosticsTask;
import org.openjproxy.xa.pool.commons.housekeeping.HousekeepingConfig;
import org.openjproxy.xa.pool.commons.housekeeping.HousekeepingListener;
import org.openjproxy.xa.pool.commons.housekeeping.LeakDetectionTask;
import org.openjproxy.xa.pool.commons.housekeeping.LoggingHousekeepingListener;
import org.openjproxy.xa.pool.commons.metrics.NoOpPoolMetrics;
import org.openjproxy.xa.pool.commons.metrics.PoolMetrics;
import org.openjproxy.xa.pool.commons.metrics.PoolMetricsFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.XAConnection;
import javax.sql.XADataSource;
import java.io.PrintWriter;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * XADataSource wrapper that pools {@link XABackendSession} instances using Apache Commons Pool 2.
 * <p>
 * This class wraps a vendor XADataSource and provides connection pooling with configurable
 * size, timeout, validation, and eviction policies optimized for XA transaction workloads.
 * </p>
 *
 * <h3>Configuration:</h3>
 * <p>Configuration is provided via a map with the following keys:</p>
 * <ul>
 *   <li>{@code xa.maxPoolSize} - Maximum pool size (default: 20)</li>
 *   <li>{@code xa.minIdle} - Minimum idle sessions (default: 5)</li>
 *   <li>{@code xa.connectionTimeoutMs} - Borrow timeout in ms (default: 30000)</li>
 *   <li>{@code xa.idleTimeoutMs} - Idle eviction timeout in ms (default: 600000)</li>
 *   <li>{@code xa.maxLifetimeMs} - Maximum session lifetime in ms (default: 1800000)</li>
 *   <li>{@code xa.timeBetweenEvictionRunsMs} - Evictor run interval in ms (default: 30000)</li>
 *   <li>{@code xa.numTestsPerEvictionRun} - Number of idle connections to check per run (default: 10)</li>
 *   <li>{@code xa.softMinEvictableIdleTimeMs} - Soft min evictable idle time in ms, respects minIdle (default: 60000)</li>
 * </ul>
 *
 * <h3>Pool Behavior:</h3>
 * <ul>
 *   <li>Blocks when pool exhausted (up to connectionTimeoutMs)</li>
 *   <li>Validates sessions on borrow (testOnBorrow=true)</li>
 *   <li>Validates idle sessions periodically (testWhileIdle=true)</li>
 *   <li>Evicts excess idle sessions above minIdle after softMinEvictableIdleTimeMs</li>
 *   <li>Evictor runs every timeBetweenEvictionRunsMs to clean up excess connections</li>
 *   <li>Hard eviction disabled (minEvictableIdleDuration=-1) to prevent premature eviction</li>
 * </ul>
 *
 * <p><strong>Note:</strong> This wrapper implements XADataSource for compatibility but
 * should NOT be used directly for getting XAConnections. Use the provider's
 * {@code borrowSession()} method instead.</p>
 */
public class CommonsPool2XADataSource implements XADataSource, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(CommonsPool2XADataSource.class);

    // Counter used to assign a unique, non-sensitive ordinal to each pool instance,
    // so OTel metric labels remain distinct without exposing connection hashes.
    private static final AtomicInteger POOL_COUNTER = new AtomicInteger(0);

    private final XADataSource vendorXADataSource;
    private final GenericObjectPool<XABackendSession> pool;
    private final Map<String, String> config;
    private final HousekeepingConfig housekeepingConfig;
    private final HousekeepingListener housekeepingListener;
    private final PoolMetrics poolMetrics;
    private final String poolName;

    // Leak detection state
    private final ConcurrentHashMap<XABackendSession, BorrowInfo> borrowedSessions;
    private ScheduledExecutorService housekeepingExecutor;

    /**
     * Creates a new pooled XADataSource.
     *
     * @param vendorXADataSource the underlying vendor XADataSource
     * @param config the pool configuration
     */
    public CommonsPool2XADataSource(XADataSource vendorXADataSource, Map<String, String> config) {
        if (vendorXADataSource == null) {
            throw new IllegalArgumentException("vendorXADataSource cannot be null");
        }
        if (config == null) {
            throw new IllegalArgumentException("config cannot be null");
        }

        this.vendorXADataSource = vendorXADataSource;
        this.config = config;
        this.poolName = config.getOrDefault("xa.poolName", "ojp-xa-pool");

        // Assign a unique, non-sensitive name for OTel metrics so that multiple simultaneous
        // pools never share the same label and cause DuplicateLabelsException on scrape.
        // The ordinal is used only for metric labelling; it does not expose connection details.
        Map<String, String> metricsConfig = new HashMap<>(config);
        metricsConfig.putIfAbsent("ojp.xa.poolName",
                "ojp-xa-pool-" + POOL_COUNTER.incrementAndGet());

        // Initialize metrics (will be no-op if OpenTelemetry not available)
        this.poolMetrics = PoolMetricsFactory.create(metricsConfig, null);

        // Parse housekeeping configuration
        this.housekeepingConfig = HousekeepingConfig.parseFromProperties(config);

        // Create housekeeping listener with metrics support only if metrics are enabled
        HousekeepingListener baseListener = new LoggingHousekeepingListener();
        if (poolMetrics instanceof NoOpPoolMetrics) {
            // Metrics disabled, use base listener directly
            this.housekeepingListener = baseListener;
        } else {
            // Metrics enabled, wrap with metrics-aware listener
            this.housekeepingListener = new org.openjproxy.xa.pool.commons.housekeeping.MetricsAwareHousekeepingListener(
                baseListener, poolMetrics, poolName);
        }

        // Initialize leak detection tracking
        this.borrowedSessions = new ConcurrentHashMap<>();

        // Get default transaction isolation from config
        Integer defaultTransactionIsolation = getTransactionIsolationFromConfig(config);

        // Create the session factory with transaction isolation and housekeeping support
        BackendSessionFactory factory = new BackendSessionFactory(
            vendorXADataSource,
            defaultTransactionIsolation,
            housekeepingConfig,
            housekeepingListener
        );

        // Configure the pool
        GenericObjectPoolConfig<XABackendSession> poolConfig = createPoolConfig(config);

        // Create the pool
        this.pool = new GenericObjectPool<>(factory, poolConfig);

        // Initialize housekeeping features
        initializeHousekeeping();

        log.info("CommonsPool2XADataSource created with maxTotal={}, minIdle={}, maxWaitMs={}, defaultTransactionIsolation={}, housekeeping=enabled(leak={}, maxLifetime={}ms)",
                poolConfig.getMaxTotal(), poolConfig.getMinIdle(), poolConfig.getMaxWaitDuration().toMillis(),
                defaultTransactionIsolation, housekeepingConfig.isLeakDetectionEnabled(), housekeepingConfig.getMaxLifetimeMs());
    }

    /**
     * Borrows a backend session from the pool.
     * <p>
     * This method is called by the XA transaction registry when starting a new
     * XA transaction branch. It will block if the pool is exhausted, up to the
     * configured timeout.
     * </p>
     *
     * @return a backend session from the pool
     * @throws SQLException if pool is exhausted and timeout expires
     * @throws Exception if session cannot be borrowed for other reasons
     */
    public XABackendSession borrowSession() throws Exception {
        log.debug("[XA-POOL-BORROW] Attempting to borrow session (state BEFORE: active={}, idle={}, maxTotal={}, maxIdle={}, minIdle={})",
                pool.getNumActive(), pool.getNumIdle(), pool.getMaxTotal(), pool.getMaxIdle(), pool.getMinIdle());

        long startTime = System.nanoTime();

        try {
            XABackendSession session = pool.borrowObject();

            // Track borrow for leak detection
            if (housekeepingConfig.isLeakDetectionEnabled()) {
                boolean captureStackTrace = housekeepingConfig.isEnhancedLeakReport();
                if (session instanceof BackendSessionImpl) {
                    ((BackendSessionImpl) session).onBorrow(captureStackTrace);
                }
                borrowedSessions.put(session, new BorrowInfo(
                    System.nanoTime(),
                    Thread.currentThread(),
                    captureStackTrace ? Thread.currentThread().getStackTrace() : null
                ));
            }

            // Record acquisition time
            long duration = (System.nanoTime() - startTime) / 1_000_000L;
            poolMetrics.recordConnectionAcquisitionTime(poolName, duration);

            // Update pool state metrics
            updatePoolMetrics();

            log.debug("[XA-POOL-BORROW] Session borrowed successfully (state AFTER: active={}, idle={}, maxTotal={})",
                    pool.getNumActive(), pool.getNumIdle(), pool.getMaxTotal());

            return session;

        } catch (java.util.NoSuchElementException e) {
            // Pool exhausted and maxWait timeout expired
            long maxWaitMs = getLongConfig(config, "xa.connectionTimeoutMs", 30000L);
            String errorMsg = String.format(
                "[XA-POOL-BORROW] POOL EXHAUSTED: maxTotal=%d, active=%d, idle=%d, timeout=%dms. " +
                "Increase pool size or reduce concurrent XA transactions.",
                pool.getMaxTotal(), pool.getNumActive(), pool.getNumIdle(), maxWaitMs);

            // Record pool exhaustion event
            poolMetrics.recordPoolExhaustion(poolName);
            updatePoolMetrics();

            log.error(errorMsg);
            throw new SQLException(errorMsg, "08001", e);

        } catch (Exception e) {
            log.error("[XA-POOL-BORROW] Failed to borrow session from pool (active={}, idle={}, maxTotal={})",
                    pool.getNumActive(), pool.getNumIdle(), pool.getMaxTotal(), e);
            throw e;
        }
    }

    /**
     * Returns a backend session to the pool.
     * <p>
     * The session will be reset to a clean state before being made available
     * for reuse. If reset fails, the session is invalidated instead.
     * </p>
     *
     * @param session the session to return
     */
    public void returnSession(XABackendSession session) {
        if (session == null) {
            log.debug("[XA-POOL-RETURN] Skipping return of null session");
            return;
        }

        // Remove from leak tracking
        if (housekeepingConfig.isLeakDetectionEnabled()) {
            borrowedSessions.remove(session);
            if (session instanceof BackendSessionImpl) {
                ((BackendSessionImpl) session).onReturn();
            }
        }

        log.debug("[XA-POOL-RETURN] Attempting to return session to pool (state BEFORE: active={}, idle={}, maxTotal={}, minIdle={})",
                pool.getNumActive(), pool.getNumIdle(), pool.getMaxTotal(), pool.getMinIdle());

        try {
            pool.returnObject(session);

            log.debug("[XA-POOL-RETURN] Session returned successfully (state AFTER: active={}, idle={}, maxTotal={})",
                    pool.getNumActive(), pool.getNumIdle(), pool.getMaxTotal());

            // Update pool metrics after return
            updatePoolMetrics();

        } catch (Exception e) {
            log.error("[XA-POOL-RETURN] Failed to return session to pool (active={}, idle={}, maxTotal={})",
                    pool.getNumActive(), pool.getNumIdle(), pool.getMaxTotal(), e);
            // Session will be destroyed by pool
        }
    }

    /**
     * Invalidates a backend session, removing it from the pool.
     * <p>
     * This is called when a session encounters an unrecoverable error.
     * The session will be destroyed and a new one may be created to maintain
     * pool size.
     * </p>
     *
     * @param session the session to invalidate
     */
    public void invalidateSession(XABackendSession session) {
        if (session == null) {
            log.debug("[XA-POOL-INVALIDATE] Skipping invalidation of null session");
            return;
        }

        log.warn("[XA-POOL-INVALIDATE] Invalidating session (before: active={}, idle={}, maxTotal={})",
                pool.getNumActive(), pool.getNumIdle(), pool.getMaxTotal());

        try {
            pool.invalidateObject(session);

            // Record validation failure metric
            poolMetrics.recordValidationFailure(poolName);
            updatePoolMetrics();

            log.debug("[XA-POOL-INVALIDATE] Session invalidated (after: active={}, idle={}, maxTotal={})",
                    pool.getNumActive(), pool.getNumIdle(), pool.getMaxTotal());

        } catch (Exception e) {
            log.error("[XA-POOL-INVALIDATE] Failed to invalidate session (active={}, idle={}, maxTotal={})",
                    pool.getNumActive(), pool.getNumIdle(), pool.getMaxTotal(), e);
        }
    }

    /**
     * Gets the number of active sessions in the pool.
     *
     * @return the number of currently borrowed sessions
     */
    public int getNumActive() {
        return pool.getNumActive();
    }

    /**
     * Gets the number of idle sessions in the pool.
     *
     * @return the number of sessions available for borrowing
     */
    public int getNumIdle() {
        return pool.getNumIdle();
    }

    /**
     * Gets the number of threads waiting to borrow a session.
     *
     * @return the number of waiting threads
     */
    public int getNumWaiters() {
        return pool.getNumWaiters();
    }

    /**
     * Gets the maximum pool size.
     *
     * @return the configured maximum number of sessions
     */
    public int getMaxTotal() {
        return pool.getMaxTotal();
    }

    /**
     * Sets the maximum number of sessions that can be allocated in the pool.
     * Allows dynamic pool resizing at runtime for cluster rebalancing.
     *
     * @param maxTotal the new maximum pool size
     */
    public void setMaxTotal(int maxTotal) {
        int oldMaxTotal = pool.getMaxTotal();
        int oldMaxIdle = pool.getMaxIdle();
        int currentActive = pool.getNumActive();
        int currentIdle = pool.getNumIdle();

        log.debug("[XA-POOL-RESIZE] setMaxTotal: old={}, new={}, oldMaxIdle={}, currentState=(active={}, idle={})",
                oldMaxTotal, maxTotal, oldMaxIdle, currentActive, currentIdle);

        pool.setMaxTotal(maxTotal);
        // CRITICAL: Also update maxIdle to allow idle connections up to maxTotal
        // Without this, addObject() becomes a no-op once numIdle >= old maxIdle
        pool.setMaxIdle(maxTotal);

        log.debug("[XA-POOL-RESIZE] After setMaxTotal: maxTotal={}, maxIdle={}, minIdle={}, state=(active={}, idle={})",
                pool.getMaxTotal(), pool.getMaxIdle(), pool.getMinIdle(), pool.getNumActive(), pool.getNumIdle());

        // If we're downsizing (new maxTotal < old maxTotal), actively destroy excess connections
        if (maxTotal < oldMaxTotal) {
            destroyExcessConnections();
        }
    }

    /**
     * Destroys excess idle connections after a pool downsize operation.
     * <p>
     * Apache Commons Pool 2 does NOT automatically destroy excess connections when maxTotal is reduced.
     * If there are active connections that exceed the new maxTotal when they were borrowed (before downsize),
     * they remain valid and get added to the idle pool when returned, potentially exceeding maxTotal.
     * <p>
     * This method actively clears excess idle connections to bring total connections (active + idle)
     * back within the maxTotal limit.
     * <p>
     * Called after setMaxTotal() completes to enforce the new pool size immediately.
     */
    private void destroyExcessConnections() {
        int maxTotal = pool.getMaxTotal();
        int currentActive = pool.getNumActive();
        int currentIdle = pool.getNumIdle();
        int totalConnections = currentActive + currentIdle;

        if (totalConnections <= maxTotal) {
            log.debug("[XA-POOL-CLEANUP] No excess connections to destroy: total={}, maxTotal={}",
                    totalConnections, maxTotal);
            return;
        }

        int excessCount = totalConnections - maxTotal;
        log.debug("[XA-POOL-CLEANUP] Destroying {} excess connections: total={} (active={}, idle={}), maxTotal={}",
                excessCount, totalConnections, currentActive, currentIdle, maxTotal);

        // Destroy idle connections to reduce total
        // We can only destroy idle connections immediately; active connections will be destroyed when returned
        int idleToDestroy = Math.min(excessCount, currentIdle);
        int destroyed = 0;
        int failures = 0;

        for (int i = 0; i < idleToDestroy; i++) {
            try {
                // Borrow an idle connection and immediately invalidate it
                XABackendSession session = pool.borrowObject(Duration.ofMillis(100));
                pool.invalidateObject(session);
                destroyed++;

                log.debug("[XA-POOL-CLEANUP] Destroyed excess idle connection {}/{}", destroyed, idleToDestroy);

            } catch (NoSuchElementException e) {
                // No more idle connections available (all borrowed or pool empty)
                log.debug("[XA-POOL-CLEANUP] No more idle connections to destroy (destroyed {} of {})",
                        destroyed, idleToDestroy);
                break;

            } catch (Exception e) {
                failures++;
                log.warn("[XA-POOL-CLEANUP] Failed to destroy excess connection {}/{}: {}",
                        i + 1, idleToDestroy, e.getMessage());
            }
        }

        int remainingActive = pool.getNumActive();
        int remainingIdle = pool.getNumIdle();
        int remainingTotal = remainingActive + remainingIdle;

        log.debug("[XA-POOL-CLEANUP] Cleanup complete: destroyed={}, failures={}, " +
                "remaining total={} (active={}, idle={}), maxTotal={}, " +
                "excess remaining={}",
                destroyed, failures, remainingTotal, remainingActive, remainingIdle, maxTotal,
                Math.max(0, remainingTotal - maxTotal));

        // If we still have excess, it's because all excess connections are active
        // They will be destroyed when returned (Apache Commons Pool enforces maxIdle on return)
        if (remainingTotal > maxTotal) {
            log.warn("[XA-POOL-CLEANUP] {} connections still exceed maxTotal (all active). " +
                    "They will be destroyed when returned to pool.",
                    remainingTotal - maxTotal);
        }
    }

    /**
     * Gets the minimum number of idle sessions maintained in the pool.
     *
     * @return the minimum idle sessions
     */
    public int getMinIdle() {
        return pool.getMinIdle();
    }

    /**
     * Sets the minimum number of idle sessions to maintain in the pool.
     * Allows dynamic pool resizing at runtime for cluster rebalancing.
     *
     * @param minIdle the new minimum idle sessions
     */
    public void setMinIdle(int minIdle) {
        int oldMinIdle = pool.getMinIdle();
        log.info("Resizing XA pool: setMinIdle from {} to {}", oldMinIdle, minIdle);

        // CRITICAL: Update maxIdle to allow idle connections up to maxTotal
        // Without this, preparePool() cannot create idle connections beyond old maxIdle
        int currentMaxTotal = pool.getMaxTotal();
        pool.setMaxIdle(currentMaxTotal);
        log.debug("[XA-POOL-RESIZE] Updated maxIdle to {} (same as maxTotal)", currentMaxTotal);

        // Set the new minIdle target
        pool.setMinIdle(minIdle);

        int currentIdle = pool.getNumIdle();
        int currentActive = pool.getNumActive();
        int needed = minIdle - currentIdle;

        if (needed > 0) {
            log.debug("[XA-POOL-RESIZE] Preparing pool to reach minIdle={} (current: idle={}, active={}, needed={})",
                    minIdle, currentIdle, currentActive, needed);

            // Use a robust approach: attempt to create connections with retry logic
            // preparePool() alone may not be sufficient for exhausted pools under load
            int successCount = 0;
            int failureCount = 0;
            Exception lastException = null;

            // First, try preparePool() which is the standard approach
            log.debug("[XA-POOL-RESIZE] Calling preparePool() to create {} idle connections...", needed);
            try {
                pool.preparePool();
                int afterPrepare = pool.getNumIdle();
                successCount = afterPrepare - currentIdle;
                log.debug("[XA-POOL-RESIZE] preparePool() created {} idle connections (idle: {} -> {}), " +
                        "pool state: maxTotal={}, maxIdle={}, minIdle={}, active={}",
                        successCount, currentIdle, afterPrepare,
                        pool.getMaxTotal(), pool.getMaxIdle(), pool.getMinIdle(), pool.getNumActive());
                currentIdle = afterPrepare;
            } catch (Exception e) {
                log.warn("[XA-POOL-RESIZE] preparePool() failed, will try manual creation", e);
                lastException = e;
            }

            // If preparePool() didn't reach target, manually create additional connections
            // This handles the case where pool is under load and preparePool() exits early
            int stillNeeded = minIdle - pool.getNumIdle();
            if (stillNeeded > 0) {
                log.debug("[XA-POOL-RESIZE] Still need {} idle connections after preparePool(), creating manually",
                        stillNeeded);

                for (int i = 0; i < stillNeeded; i++) {
                    try {
                        // Check if we've already reached minIdle (another thread may have added)
                        if (pool.getNumIdle() >= minIdle) {
                            log.debug("[XA-POOL-RESIZE] Reached minIdle target, stopping early");
                            break;
                        }

                        // Check if we're at maxTotal capacity
                        int currentTotal = pool.getNumActive() + pool.getNumIdle();
                        if (currentTotal >= currentMaxTotal) {
                            log.warn("[XA-POOL-RESIZE] Cannot create more connections: at maxTotal capacity " +
                                    "(active={}, idle={}, maxTotal={})",
                                    pool.getNumActive(), pool.getNumIdle(), currentMaxTotal);
                            break;
                        }

                        pool.addObject();
                        successCount++;
                        log.debug("[XA-POOL-RESIZE] Created idle connection {}/{} (idle={}, active={})",
                                i + 1, stillNeeded, pool.getNumIdle(), pool.getNumActive());

                    } catch (Exception e) {
                        failureCount++;
                        lastException = e;
                        log.warn("[XA-POOL-RESIZE] Failed to create idle connection {}/{}: {} - {}",
                                i + 1, stillNeeded, e.getClass().getSimpleName(), e.getMessage());
                    }
                }
            }

            int finalIdle = pool.getNumIdle();
            int finalActive = pool.getNumActive();

            log.debug("[XA-POOL-RESIZE] Pool expansion complete: success={}, failures={}, " +
                    "pool state: idle={}, active={}, maxTotal={}",
                    successCount, failureCount, finalIdle, finalActive, currentMaxTotal);

            // Warn if we didn't reach the target
            if (finalIdle < minIdle) {
                log.warn("[XA-POOL-RESIZE] WARNING: Pool has {} idle connections but minIdle target is {}. " +
                        "Created={}, Failed={}. Pool may be under load or backend unavailable.",
                        finalIdle, minIdle, successCount, failureCount);
            }

            // Only throw if we created NO connections at all
            if (successCount == 0 && failureCount > 0) {
                log.error("[XA-POOL-RESIZE] CRITICAL: Failed to create ANY idle connections during pool resize");
                throw new RuntimeException("Failed to create idle connections during pool resize. " +
                        "All " + failureCount + " attempts failed.", lastException);
            }

        } else {
            log.debug("[XA-POOL-RESIZE] No idle connections needed (current idle={} >= minIdle={})", currentIdle, minIdle);
        }
    }

    /**
     * Logs detailed diagnostic information about the current pool state.
     * Useful for debugging pool sizing and connection tracking issues.
     */
    public void logPoolDiagnostics(String context) {
        log.debug("[XA-POOL-DIAGNOSTICS] {} - Pool state: active={}, idle={}, waiters={}, " +
                "maxTotal={}, maxIdle={}, minIdle={}, createdCount={}, destroyedCount={}, borrowedCount={}, returnedCount={}",
                context,
                pool.getNumActive(),
                pool.getNumIdle(),
                pool.getNumWaiters(),
                pool.getMaxTotal(),
                pool.getMaxIdle(),
                pool.getMinIdle(),
                pool.getCreatedCount(),
                pool.getDestroyedCount(),
                pool.getBorrowedCount(),
                pool.getReturnedCount());
    }

    /**
     * Initializes housekeeping features (leak detection, diagnostics).
     * <p>
     * This method sets up scheduled tasks for leak detection if enabled.
     * </p>
     */
    /**
     * Updates the pool metrics with current pool state.
     * This method collects and records pool statistics to OpenTelemetry.
     */
    private void updatePoolMetrics() {
        try {
            poolMetrics.recordPoolState(
                poolName,
                pool.getNumActive(),
                pool.getNumIdle(),
                pool.getNumWaiters(),
                pool.getMaxTotal(),
                pool.getMinIdle(),
                pool.getCreatedCount(),
                pool.getDestroyedCount(),
                pool.getBorrowedCount(),
                pool.getReturnedCount()
            );
        } catch (Exception e) {
            log.warn("Failed to update pool metrics: {}", e.getMessage());
        }
    }

    /**
     * Creates a ThreadFactory that uses virtual threads.
     * <p>
     * This module runs only on ojp-server which is guaranteed to be running on Java 25+,
     * so we can directly use virtual threads without reflection.
     * </p>
     *
     * @return a ThreadFactory for housekeeping tasks using virtual threads
     */
    private java.util.concurrent.ThreadFactory createThreadFactory() {
        return Thread.ofVirtual().name("ojp-xa-housekeeping-", 0).factory();
    }

    private void initializeHousekeeping() {
        boolean needsExecutor = housekeepingConfig.isLeakDetectionEnabled() || housekeepingConfig.isDiagnosticsEnabled();

        if (needsExecutor) {
            // Create executor for housekeeping tasks using virtual threads (Java 25+)
            housekeepingExecutor = Executors.newSingleThreadScheduledExecutor(createThreadFactory());
        }

        // Initialize leak detection if enabled
        if (housekeepingConfig.isLeakDetectionEnabled()) {
            log.info("Initializing leak detection with timeout={}ms, interval={}ms, enhanced={}",
                housekeepingConfig.getLeakTimeoutMs(),
                housekeepingConfig.getLeakCheckIntervalMs(),
                housekeepingConfig.isEnhancedLeakReport());

            // Schedule leak detection task
            LeakDetectionTask leakTask = new LeakDetectionTask(
                borrowedSessions,
                housekeepingConfig.getLeakTimeoutMs() * 1_000_000L,  // Convert ms to nanos
                housekeepingListener
            );

            housekeepingExecutor.scheduleAtFixedRate(
                leakTask,
                housekeepingConfig.getLeakCheckIntervalMs(),
                housekeepingConfig.getLeakCheckIntervalMs(),
                TimeUnit.MILLISECONDS
            );

            log.info("Leak detection initialized and scheduled");
        } else {
            log.info("Leak detection is disabled");
        }

        // Initialize diagnostics if enabled
        if (housekeepingConfig.isDiagnosticsEnabled()) {
            log.info("Initializing pool diagnostics with interval={}ms",
                housekeepingConfig.getDiagnosticsIntervalMs());

            // Schedule diagnostics task
            DiagnosticsTask diagnosticsTask = new DiagnosticsTask(pool, housekeepingListener);

            housekeepingExecutor.scheduleAtFixedRate(
                diagnosticsTask,
                housekeepingConfig.getDiagnosticsIntervalMs(),
                housekeepingConfig.getDiagnosticsIntervalMs(),
                TimeUnit.MILLISECONDS
            );

            log.info("Pool diagnostics initialized and scheduled");
        } else {
            log.info("Pool diagnostics is disabled");
        }
    }

    /**
     * Closes the pool and releases all resources.
     * <p>
     * This will close all sessions (active and idle) and shut down the pool.
     * </p>
     */
    public void close() {
        log.info("Closing CommonsPool2XADataSource (active={}, idle={})",
                pool.getNumActive(), pool.getNumIdle());

        // Shutdown housekeeping executor
        if (housekeepingExecutor != null) {
            log.info("Shutting down housekeeping executor");
            housekeepingExecutor.shutdown();
            try {
                if (!housekeepingExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                    log.warn("Housekeeping executor did not terminate in time, forcing shutdown");
                    housekeepingExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                log.warn("Interrupted while waiting for housekeeping executor shutdown");
                housekeepingExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        pool.close();

        // Close metrics
        try {
            poolMetrics.close();
        } catch (Exception e) {
            log.warn("Failed to close pool metrics: {}", e.getMessage());
        }

        log.info("CommonsPool2XADataSource closed");
    }

    // XADataSource interface methods (not directly used)

    @Override
    public XAConnection getXAConnection() throws SQLException {
        throw new UnsupportedOperationException(
                "Do not use getXAConnection() directly. Use provider.borrowSession() instead.");
    }

    @Override
    public XAConnection getXAConnection(String user, String password) throws SQLException {
        throw new UnsupportedOperationException(
                "Do not use getXAConnection() directly. Use provider.borrowSession() instead.");
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return vendorXADataSource.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        vendorXADataSource.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        vendorXADataSource.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return vendorXADataSource.getLoginTimeout();
    }

    @Override
    public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return vendorXADataSource.getParentLogger();
    }

    // Private helper methods

    private static GenericObjectPoolConfig<XABackendSession> createPoolConfig(Map<String, String> config) {
        GenericObjectPoolConfig<XABackendSession> poolConfig = new GenericObjectPoolConfig<>();

        // Pool sizing
        int maxTotal = getIntConfig(config, "xa.maxPoolSize", 20);
        int minIdle = getIntConfig(config, "xa.minIdle", 5);
        poolConfig.setMaxTotal(maxTotal);
        poolConfig.setMinIdle(minIdle);
        poolConfig.setMaxIdle(maxTotal); // maxIdle same as maxTotal

        // Borrow behavior
        poolConfig.setBlockWhenExhausted(true);
        long maxWaitMs = getLongConfig(config, "xa.connectionTimeoutMs", 30000L);
        poolConfig.setMaxWait(Duration.ofMillis(maxWaitMs));

        // Validation
        poolConfig.setTestOnBorrow(true);
        poolConfig.setTestOnReturn(false);
        poolConfig.setTestWhileIdle(true);

        // Eviction configuration
        long timeBetweenEvictionRunsMs = getLongConfig(config, "xa.timeBetweenEvictionRunsMs", 30000L);
        poolConfig.setTimeBetweenEvictionRuns(Duration.ofMillis(timeBetweenEvictionRunsMs));

        // Note: minEvictableIdleDuration is NOT configurable (hard eviction is forbidden)
        // We use -1 to disable hard eviction and rely on softMinEvictableIdleDuration instead
        poolConfig.setMinEvictableIdleDuration(Duration.ofMillis(-1));

        long softMinEvictableIdleTimeMs = getLongConfig(config, "xa.softMinEvictableIdleTimeMs", 60000L);
        poolConfig.setSoftMinEvictableIdleDuration(Duration.ofMillis(softMinEvictableIdleTimeMs));

        int numTestsPerEvictionRun = getIntConfig(config, "xa.numTestsPerEvictionRun", 10);
        poolConfig.setNumTestsPerEvictionRun(numTestsPerEvictionRun);

        // Lifetime enforcement via eviction
        // Note: Commons Pool 2 doesn't have a direct "maxLifetime" setting.
        // Objects are evicted based on idle time and validation failures.
        // The maxLifetimeMs config is preserved for future use but not applied here
        // to avoid overwriting the maxWait timeout setting above.

        // Fairness
        poolConfig.setFairness(true);

        return poolConfig;
    }

    private static int getIntConfig(Map<String, String> config, String key, int defaultValue) {
        String value = config.get(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            log.warn("Invalid integer config for {}: {}, using default {}", key, value, defaultValue);
            return defaultValue;
        }
    }

    private static long getLongConfig(Map<String, String> config, String key, long defaultValue) {
        String value = config.get(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            log.warn("Invalid long config for {}: {}, using default {}", key, value, defaultValue);
            return defaultValue;
        }
    }

    /**
     * Gets transaction isolation level from config.
     * Accepts string names (READ_COMMITTED, SERIALIZABLE, etc.).
     * Returns READ_COMMITTED as the hardcoded default if not specified or invalid.
     */
    private static Integer getTransactionIsolationFromConfig(Map<String, String> config) {
        String value = config.get("xa.defaultTransactionIsolation");
        if (value == null || value.trim().isEmpty()) {
            // Default to READ_COMMITTED for safety
            log.info("No transaction isolation configured, using default: READ_COMMITTED");
            return java.sql.Connection.TRANSACTION_READ_COMMITTED;
        }

        value = value.trim();

        // Parse string names (case-insensitive)
        switch (value.toUpperCase()) {
            case "TRANSACTION_NONE":
            case "NONE":
                return java.sql.Connection.TRANSACTION_NONE;
            case "TRANSACTION_READ_UNCOMMITTED":
            case "READ_UNCOMMITTED":
                return java.sql.Connection.TRANSACTION_READ_UNCOMMITTED;
            case "TRANSACTION_READ_COMMITTED":
            case "READ_COMMITTED":
                return java.sql.Connection.TRANSACTION_READ_COMMITTED;
            case "TRANSACTION_REPEATABLE_READ":
            case "REPEATABLE_READ":
                return java.sql.Connection.TRANSACTION_REPEATABLE_READ;
            case "TRANSACTION_SERIALIZABLE":
            case "SERIALIZABLE":
                return java.sql.Connection.TRANSACTION_SERIALIZABLE;
            default:
                log.warn("Invalid transaction isolation value: {}. Valid values are: " +
                        "NONE, READ_UNCOMMITTED, READ_COMMITTED, REPEATABLE_READ, SERIALIZABLE. " +
                        "Using default: READ_COMMITTED", value);
                return java.sql.Connection.TRANSACTION_READ_COMMITTED;
        }
    }

    /**
     * Gets the housekeeping configuration.
     *
     * @return the housekeeping configuration
     */
    public HousekeepingConfig getHousekeepingConfig() {
        return housekeepingConfig;
    }
}
