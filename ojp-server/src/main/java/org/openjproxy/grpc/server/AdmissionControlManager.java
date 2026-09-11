package org.openjproxy.grpc.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages per-datasource admission control and optional slow-query segregation.
 *
 * <p>This class coordinates between {@link QueryPerformanceMonitor} (classification signal)
 * and {@link SlotManager} (concurrency gate). It supports two execution modes:
 * </p>
 * <ul>
 *     <li><b>Admission-control-only</b>: fixed-capacity gate, no slow/fast split.</li>
 *     <li><b>Segregated</b>: slow/fast slot classification with slot borrowing.</li>
 * </ul>
 *
 * <p>SQL execution time metrics are recorded by the caller
 * ({@code StatementServiceImpl.executeWithResilience}). This class handles classification
 * and slot admission only.</p>
 */
public class AdmissionControlManager {

    // Workaround for Lombok compilation issue
    private static final Logger logger = LoggerFactory.getLogger(AdmissionControlManager.class);

    private final QueryPerformanceMonitor performanceMonitor;
    private final SlotManager slotManager;
    private final boolean enabled;
    private final boolean admissionControlOnly;
    private final long slowSlotTimeoutMs;
    private final long fastSlotTimeoutMs;
    private final ThreadLocal<HeldSlot> threadHeldSlot = new ThreadLocal<>();

    /**
     * Creates a new AdmissionControlManager.
     *
     * @param totalSlots The maximum total number of concurrent operations (from HikariCP max pool size)
     * @param slowSlotPercentage The percentage of slots allocated to slow operations (0-100)
     * @param idleTimeoutMs The time in milliseconds before a slot is considered idle and eligible for borrowing
     * @param slowSlotTimeoutMs The timeout in milliseconds for acquiring slow operation slots
     * @param fastSlotTimeoutMs The timeout in milliseconds for acquiring fast operation slots
     * @param updateGlobalAvgIntervalSeconds The interval in seconds for updating global average (0 = update every query)
     * @param classificationMode SQS classification mode
     * @param slowQueryThresholdMs Slow-query threshold in milliseconds for ABSOLUTE_THRESHOLD mode
     * @param minimumSlowQueryMs Minimum query average in milliseconds required before entering slow classification
     * @param slowMultiplier Multiplier against fast baseline for entering slow classification
     * @param recoveryMultiplier Multiplier against fast baseline for recovering back to fast classification
     * @param minSamples Minimum number of operation samples required before classification
     * @param baselinePercentile Percentile used to compute fast baseline from fast operations
     * @param baselineRefreshIntervalSeconds Fast baseline refresh interval in seconds (0 = refresh every check)
     * @param maxWaitQueueDepth Maximum waiting thread queue depth per semaphore (0 = auto)
     * @param enabled Whether admission control is enabled
     */
    public AdmissionControlManager(int totalSlots, int slowSlotPercentage, long idleTimeoutMs,
                                      long slowSlotTimeoutMs, long fastSlotTimeoutMs, long updateGlobalAvgIntervalSeconds,
                                     SlowQueryClassificationMode classificationMode, long slowQueryThresholdMs,
                                     long minimumSlowQueryMs, double slowMultiplier, double recoveryMultiplier,
                                     int minSamples, int baselinePercentile, long baselineRefreshIntervalSeconds,
                                     int maxWaitQueueDepth, boolean enabled) {
        this.enabled = enabled;
        this.admissionControlOnly = enabled && slowSlotPercentage == 0;
        this.slowSlotTimeoutMs = slowSlotTimeoutMs;
        this.fastSlotTimeoutMs = fastSlotTimeoutMs;
        this.performanceMonitor = new QueryPerformanceMonitor(
                updateGlobalAvgIntervalSeconds, classificationMode, slowQueryThresholdMs,
                minimumSlowQueryMs, slowMultiplier, recoveryMultiplier,
                minSamples, baselinePercentile, baselineRefreshIntervalSeconds);

        if (enabled) {
            this.slotManager = new SlotManager(totalSlots, slowSlotPercentage, idleTimeoutMs, maxWaitQueueDepth);
            logger.info("AdmissionControlManager initialized: enabled={}, admissionControlOnly={}, totalSlots={}, slowSlotPercentage={}%, idleTimeout={}ms, slowSlotTimeout={}ms, fastSlotTimeout={}ms, updateGlobalAvgInterval={}s, classificationMode={}, minimumSlowQueryMs={}, slowMultiplier={}, recoveryMultiplier={}, minSamples={}, baselinePercentile={}, baselineRefreshIntervalSeconds={}, slowQueryThreshold={}ms, maxWaitQueueDepth={}",
                    enabled, admissionControlOnly, totalSlots, slowSlotPercentage, idleTimeoutMs, slowSlotTimeoutMs,
                    fastSlotTimeoutMs, updateGlobalAvgIntervalSeconds, performanceMonitor.getClassificationMode(),
                    performanceMonitor.getMinimumSlowQueryMs(), performanceMonitor.getSlowMultiplier(),
                    performanceMonitor.getRecoveryMultiplier(), performanceMonitor.getMinSamples(),
                    performanceMonitor.getBaselinePercentile(), performanceMonitor.getBaselineRefreshIntervalSeconds(),
                    performanceMonitor.getSlowQueryThresholdMs(), slotManager.getMaxWaitQueueDepth());
        } else {
            this.slotManager = null;
            logger.info("AdmissionControlManager initialized: enabled={}, admissionControlOnly={}, updateGlobalAvgInterval={}s, classificationMode={}, minimumSlowQueryMs={}, slowMultiplier={}, recoveryMultiplier={}, minSamples={}, baselinePercentile={}, baselineRefreshIntervalSeconds={}, slowQueryThreshold={}ms",
                    enabled, admissionControlOnly, updateGlobalAvgIntervalSeconds, performanceMonitor.getClassificationMode(),
                    performanceMonitor.getMinimumSlowQueryMs(), performanceMonitor.getSlowMultiplier(),
                    performanceMonitor.getRecoveryMultiplier(), performanceMonitor.getMinSamples(),
                    performanceMonitor.getBaselinePercentile(), performanceMonitor.getBaselineRefreshIntervalSeconds(),
                    performanceMonitor.getSlowQueryThresholdMs());
        }
    }

    /**
     * Creates a new AdmissionControlManager with relative-average classification defaults.
     */
    public AdmissionControlManager(int totalSlots, int slowSlotPercentage, long idleTimeoutMs,
                                   long slowSlotTimeoutMs, long fastSlotTimeoutMs, long updateGlobalAvgIntervalSeconds,
                                    int maxWaitQueueDepth, boolean enabled) {
        this(totalSlots, slowSlotPercentage, idleTimeoutMs, slowSlotTimeoutMs, fastSlotTimeoutMs,
                updateGlobalAvgIntervalSeconds, QueryPerformanceMonitor.DEFAULT_CLASSIFICATION_MODE,
                QueryPerformanceMonitor.DEFAULT_SLOW_QUERY_THRESHOLD_MS,
                QueryPerformanceMonitor.DEFAULT_MINIMUM_SLOW_QUERY_MS,
                QueryPerformanceMonitor.DEFAULT_SLOW_MULTIPLIER,
                QueryPerformanceMonitor.DEFAULT_RECOVERY_MULTIPLIER,
                QueryPerformanceMonitor.DEFAULT_MIN_SAMPLES,
                QueryPerformanceMonitor.DEFAULT_BASELINE_PERCENTILE,
                QueryPerformanceMonitor.DEFAULT_BASELINE_REFRESH_INTERVAL_SECONDS,
                maxWaitQueueDepth, enabled);
    }

    /**
     * Creates a new AdmissionControlManager with default global average update interval.
     * This constructor maintains backward compatibility.
     *
     * @param totalSlots The maximum total number of concurrent operations (from HikariCP max pool size)
     * @param slowSlotPercentage The percentage of slots allocated to slow operations (0-100)
     * @param idleTimeoutMs The time in milliseconds before a slot is considered idle and eligible for borrowing
     * @param slowSlotTimeoutMs The timeout in milliseconds for acquiring slow operation slots
     * @param fastSlotTimeoutMs The timeout in milliseconds for acquiring fast operation slots
     * @param enabled Whether admission control is enabled
     */
    public AdmissionControlManager(int totalSlots, int slowSlotPercentage, long idleTimeoutMs,
                                       long slowSlotTimeoutMs, long fastSlotTimeoutMs, boolean enabled) {
        this(totalSlots, slowSlotPercentage, idleTimeoutMs, slowSlotTimeoutMs, fastSlotTimeoutMs, 0L, 0, enabled);
    }

    /**
     * Creates a new AdmissionControlManager with configured queue depth cap.
     */
    public AdmissionControlManager(int totalSlots, int slowSlotPercentage, long idleTimeoutMs,
                                       long slowSlotTimeoutMs, long fastSlotTimeoutMs, long updateGlobalAvgIntervalSeconds, boolean enabled) {
        this(totalSlots, slowSlotPercentage, idleTimeoutMs, slowSlotTimeoutMs, fastSlotTimeoutMs,
                updateGlobalAvgIntervalSeconds, 0, enabled);
    }

    /**
     * Executes an operation with admission control.
     * This method handles slot acquisition, performance monitoring, and slot release.
     * The {@code operationHash} is used for performance monitoring (slot classification);
     * the actual SQL text is passed separately for metric labelling.
     *
     * @param operationHash The hash of the SQL operation
     * @param sql           The actual SQL statement text (used as metric label)
     * @param operation     The operation to execute
     * @param <T>           The return type of the operation
     * @return The result of the operation
     * @throws Exception if the operation fails or slot acquisition times out
     */
    public <T> T executeWithSegregation(String operationHash, String sql, SegregatedOperation<T> operation) throws Exception {
        if (!enabled) {
            // If admission control is disabled, just execute and monitor performance
            return executeAndMonitor(operationHash, sql, operation);
        }

        if (admissionControlOnly) {
            boolean slotAcquired = false;
            try {
                slotAcquired = slotManager.acquireFastSlot(fastSlotTimeoutMs);
                if (!slotAcquired) {
                    throw new ServerOverloadException(
                            "Timeout waiting for admission control slot for operation: " + operationHash,
                            ServerOverloadException.Lane.FAST);
                }
                logger.debug("Acquired admission control slot for operation: {}", operationHash);
                threadHeldSlot.set(new HeldSlot(false));
                return executeAndMonitor(operationHash, sql, operation);
            } finally {
                HeldSlot heldSlot = threadHeldSlot.get();
                if (slotAcquired && (heldSlot == null || !heldSlot.claimed)) {
                    slotManager.releaseFastSlot();
                    logger.debug("Released admission control slot for operation: {}", operationHash);
                }
                threadHeldSlot.remove();
            }
        }

        // Determine if this is a slow or fast operation
        boolean isSlowOperation = performanceMonitor.isSlowOperation(operationHash);

        // Acquire appropriate slot
        boolean slotAcquired = false;

        try {
            if (isSlowOperation) {
                slotAcquired = slotManager.acquireSlowSlot(slowSlotTimeoutMs);
                if (!slotAcquired) {
                    throw new ServerOverloadException(
                            "Timeout waiting for slow operation slot for operation: " + operationHash,
                            ServerOverloadException.Lane.SLOW);
                }
                logger.debug("Acquired slow slot for operation: {}", operationHash);
                threadHeldSlot.set(new HeldSlot(true));
            } else {
                slotAcquired = slotManager.acquireFastSlot(fastSlotTimeoutMs);
                if (!slotAcquired) {
                    throw new ServerOverloadException(
                            "Timeout waiting for fast operation slot for operation: " + operationHash,
                            ServerOverloadException.Lane.FAST);
                }
                logger.debug("Acquired fast slot for operation: {}", operationHash);
                threadHeldSlot.set(new HeldSlot(false));
            }

            // Execute the operation and monitor its performance
            return executeAndMonitor(operationHash, sql, operation);

        } finally {
            // Always release the slot
            HeldSlot heldSlot = threadHeldSlot.get();
            if (slotAcquired && (heldSlot == null || !heldSlot.claimed)) {
                if (isSlowOperation) {
                    slotManager.releaseSlowSlot();
                    logger.debug("Released slow slot for operation: {}", operationHash);
                } else {
                    slotManager.releaseFastSlot();
                    logger.debug("Released fast slot for operation: {}", operationHash);
                }
            }
            threadHeldSlot.remove();
        }
    }

    /**
     * Claims the slot currently held by this thread (acquired inside executeWithSegregation)
     * and converts it into a session-scoped permit that must be released on session termination.
     *
     * @return a claimed session permit, or null if current thread does not hold a slot.
     */
    public SessionPermit claimCurrentThreadPermitForSession() {
        if (!enabled || slotManager == null) {
            return null;
        }
        HeldSlot heldSlot = threadHeldSlot.get();
        if (heldSlot == null || heldSlot.claimed) {
            return null;
        }
        heldSlot.claimed = true;
        return new SessionPermit(slotManager, heldSlot.slow);
    }

    /**
     * Acquires a session-scoped admission permit using the existing fast-slot semaphore.
     * Used when no statement-scoped slot is currently held by this thread.
     */
    public SessionPermit acquireSessionPermit(String connHash) throws java.sql.SQLException {
        if (!enabled || slotManager == null) {
            return null;
        }
        try {
            boolean acquired = slotManager.acquireFastSlot(fastSlotTimeoutMs);
            if (!acquired) {
                throw new java.sql.SQLTransientConnectionException(String.format(
                        "Connection admission timeout for hash: %s after %dms (phase=admission)",
                        connHash, fastSlotTimeoutMs));
            }
            return new SessionPermit(slotManager, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new java.sql.SQLException("Interrupted while waiting for admission slot", e);
        }
    }

    /**
     * Executes an operation with admission control.
     * This method handles slot acquisition, performance monitoring, and slot release.
     *
     * @param operationHash The hash of the SQL operation
     * @param operation The operation to execute
     * @param <T> The return type of the operation
     * @return The result of the operation
     * @throws Exception if the operation fails or slot acquisition times out
     */
    public <T> T executeWithSegregation(String operationHash, SegregatedOperation<T> operation) throws Exception {
        return executeWithSegregation(operationHash, operationHash, operation);
    }

    /**
     * Executes an operation without acquiring any admission slot, only running the
     * performance monitor. Intended for requests whose session already holds a
     * session-scoped {@link SessionPermit} acquired at session creation time —
     * such sessions should not be double-counted by acquiring a per-statement slot.
     *
     * @param operationHash The hash of the SQL operation
     * @param sql           The actual SQL statement text (used as metric label)
     * @param operation     The operation to execute
     * @param <T>           The return type of the operation
     * @return The result of the operation
     * @throws Exception if the operation fails
     */
    public <T> T executeWithMonitoringOnly(String operationHash, String sql, SegregatedOperation<T> operation) throws Exception {
        return executeAndMonitor(operationHash, sql, operation);
    }

    /**
     * Executes an operation and monitors its performance without slot management.
     * Returns the execution time in milliseconds so the caller can record metrics.
     */
    private <T> T executeAndMonitor(String operationHash, String sql, SegregatedOperation<T> operation) throws Exception {
        long startTime = System.nanoTime();

        try {
            T result = operation.execute();

            // Record execution time for performance classification only
            long executionTime = (System.nanoTime() - startTime) / 1_000_000L;
            performanceMonitor.recordExecutionTime(operationHash, executionTime);

            return result;
        } catch (Exception e) {
            // Still record execution time even for failed operations for monitoring purposes
            long executionTime = (System.nanoTime() - startTime) / 1_000_000L;
            performanceMonitor.recordExecutionTime(operationHash, executionTime);
            throw e;
        }
    }

    /**
     * Gets the current status of both the performance monitor and slot manager.
     */
    public String getStatus() {
        if (!enabled) {
            return "AdmissionControlManager[enabled=false]";
        }

        return String.format(
            "AdmissionControlManager[enabled=true, admissionControlOnly=%s, classificationMode=%s, fastBaselineMs=%.2f, minimumSlowQueryMs=%d, slowMultiplier=%.2f, recoveryMultiplier=%.2f, minSamples=%d, baselinePercentile=%d, slowQueryThreshold=%dms, trackedOps=%d, classifiedSlowOps=%d, totalExecs=%d, overallAvg=%.2fms, %s]",
            admissionControlOnly,
            performanceMonitor.getClassificationMode(),
            performanceMonitor.getFastBaselineMs(),
            performanceMonitor.getMinimumSlowQueryMs(),
            performanceMonitor.getSlowMultiplier(),
            performanceMonitor.getRecoveryMultiplier(),
            performanceMonitor.getMinSamples(),
            performanceMonitor.getBaselinePercentile(),
            performanceMonitor.getSlowQueryThresholdMs(),
            performanceMonitor.getTrackedOperationCount(),
            performanceMonitor.getClassifiedSlowOperationCount(),
            performanceMonitor.getTotalExecutionCount(),
            performanceMonitor.getOverallAverageExecutionTime(),
            slotManager.getStatus()
        );
    }

    /**
     * Checks if an operation is currently classified as slow.
     */
    public boolean isSlowOperation(String operationHash) {
        return performanceMonitor.isSlowOperation(operationHash);
    }

    /**
     * Gets the average execution time for a specific operation.
     */
    public double getOperationAverageTime(String operationHash) {
        return performanceMonitor.getOperationAverageTime(operationHash);
    }

    /**
     * Gets the overall average execution time across all operations.
     */
    public double getOverallAverageExecutionTime() {
        return performanceMonitor.getOverallAverageExecutionTime();
    }

    /**
     * Checks if admission control is enabled.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Checks if manager is running in admission-control-only mode (no slow/fast segregation).
     */
    public boolean isAdmissionControlOnly() {
        return admissionControlOnly;
    }

    /**
     * Gets the performance monitor (for testing purposes).
     */
    public QueryPerformanceMonitor getPerformanceMonitor() {
        return performanceMonitor;
    }

    /**
     * Gets the slot manager (for testing purposes).
     */
    public SlotManager getSlotManager() {
        return slotManager;
    }

    /**
     * Gets configured fast-slot timeout (for testing/diagnostics).
     */
    long getFastSlotTimeoutMs() {
        return fastSlotTimeoutMs;
    }

    /**
     * Gets configured slow-slot timeout (for testing/diagnostics).
     */
    long getSlowSlotTimeoutMs() {
        return slowSlotTimeoutMs;
    }

    /**
     * Functional interface for operations that can be executed with segregation.
     */
    @FunctionalInterface
    public interface SegregatedOperation<T> {
        T execute() throws Exception;
    }

    /**
     * Tracks slot ownership for the current thread while executing admission-controlled work.
     * The {@code claimed} flag indicates the slot was transferred to session lifecycle ownership
     * and must not be auto-released by executeWithSegregation.
     */
    private static final class HeldSlot {
        private final boolean slow;
        private boolean claimed;

        private HeldSlot(boolean slow) {
            this.slow = slow;
            this.claimed = false;
        }
    }

    /**
     * Represents a claimed admission slot tied to session lifecycle.
     * This permit must be released when the session terminates. Release is one-shot and
     * idempotent, guarded by AtomicBoolean to prevent double-release across concurrent paths.
     */
    public static final class SessionPermit {
        private final SlotManager slotManager;
        private final boolean slow;
        private final java.util.concurrent.atomic.AtomicBoolean released = new java.util.concurrent.atomic.AtomicBoolean(false);

        private SessionPermit(SlotManager slotManager, boolean slow) {
            this.slotManager = slotManager;
            this.slow = slow;
        }

        public void release() {
            if (released.compareAndSet(false, true)) {
                if (slow) {
                    slotManager.releaseSlowSlot();
                } else {
                    slotManager.releaseFastSlot();
                }
            }
        }
    }
}
