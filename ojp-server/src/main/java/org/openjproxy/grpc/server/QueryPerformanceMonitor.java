package org.openjproxy.grpc.server;

import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Monitors SQL operation performance and classifies operations as fast/slow.
 */
@Slf4j
public class QueryPerformanceMonitor {
    public static final long DEFAULT_SLOW_QUERY_THRESHOLD_MS = 1000L;
    public static final SlowQueryClassificationMode DEFAULT_CLASSIFICATION_MODE = SlowQueryClassificationMode.RELATIVE_FAST_BASELINE;
    public static final long DEFAULT_MINIMUM_SLOW_QUERY_MS = 100L;
    public static final double DEFAULT_SLOW_MULTIPLIER = 5.0;
    public static final double DEFAULT_RECOVERY_MULTIPLIER = 3.0;
    public static final int DEFAULT_MIN_SAMPLES = 20;
    public static final int DEFAULT_BASELINE_PERCENTILE = 50;
    public static final long DEFAULT_BASELINE_REFRESH_INTERVAL_SECONDS = 10L;

    /**
     * Record for tracking operation performance metrics.
     */
    private static class PerformanceRecord {
        private volatile double averageExecutionTime;
        private final AtomicLong sampleCount;
        private volatile long lastSeenTimeMillis;
        private volatile boolean currentlyClassifiedAsSlow;
        private final ReentrantLock lock = new ReentrantLock();

        PerformanceRecord(double initialTime, long nowMillis) {
            this.averageExecutionTime = initialTime;
            this.sampleCount = new AtomicLong(1);
            this.lastSeenTimeMillis = nowMillis;
            this.currentlyClassifiedAsSlow = false;
        }

        /**
         * Updates the average execution time using EWMA (α = 0.2).
         * new_average = α × new_measurement + (1 - α) × stored_average
         */
        public void updateAverage(double newMeasurement, long nowMillis) {
            lock.lock();
            try {
                this.averageExecutionTime = ((this.averageExecutionTime * 4) + newMeasurement) / 5;
                this.sampleCount.incrementAndGet();
                this.lastSeenTimeMillis = nowMillis;
            } finally {
                lock.unlock();
            }
        }

        public double getAverageExecutionTime() {
            return averageExecutionTime;
        }

        public long getSampleCount() {
            return sampleCount.get();
        }

        public long getLastSeenTimeMillis() {
            return lastSeenTimeMillis;
        }

        public boolean isCurrentlyClassifiedAsSlow() {
            return currentlyClassifiedAsSlow;
        }

        public boolean updateSlowClassification(boolean slow) {
            lock.lock();
            try {
                if (this.currentlyClassifiedAsSlow == slow) {
                    return false;
                }
                this.currentlyClassifiedAsSlow = slow;
                return true;
            } finally {
                lock.unlock();
            }
        }
    }

    private final ConcurrentHashMap<String, PerformanceRecord> operationRecords = new ConcurrentHashMap<>();
    private volatile double overallAverageExecutionTime = 0.0;
    private final AtomicLong totalOperations = new AtomicLong(0);

    private final long updateGlobalAvgIntervalSeconds;
    private final SlowQueryClassificationMode classificationMode;
    private final long slowQueryThresholdMs;
    private final long minimumSlowQueryMs;
    private final double slowMultiplier;
    private final double recoveryMultiplier;
    private final int minSamples;
    private final int baselinePercentile;
    private final long baselineRefreshIntervalSeconds;
    private final TimeProvider timeProvider;
    private volatile long lastGlobalAvgUpdateTime = 0L;

    private volatile double fastBaselineMs = 0.0;
    private volatile long lastBaselineRefreshMillis = 0L;

    /**
     * Creates a QueryPerformanceMonitor with default settings.
     */
    public QueryPerformanceMonitor() {
        this(0L, TimeProvider.SYSTEM, DEFAULT_CLASSIFICATION_MODE, DEFAULT_SLOW_QUERY_THRESHOLD_MS,
                DEFAULT_MINIMUM_SLOW_QUERY_MS, DEFAULT_SLOW_MULTIPLIER, DEFAULT_RECOVERY_MULTIPLIER,
                DEFAULT_MIN_SAMPLES, DEFAULT_BASELINE_PERCENTILE, DEFAULT_BASELINE_REFRESH_INTERVAL_SECONDS);
    }

    /**
     * Creates a QueryPerformanceMonitor with specified global average update interval.
     */
    public QueryPerformanceMonitor(long updateGlobalAvgIntervalSeconds) {
        this(updateGlobalAvgIntervalSeconds, TimeProvider.SYSTEM, DEFAULT_CLASSIFICATION_MODE,
                DEFAULT_SLOW_QUERY_THRESHOLD_MS, DEFAULT_MINIMUM_SLOW_QUERY_MS, DEFAULT_SLOW_MULTIPLIER,
                DEFAULT_RECOVERY_MULTIPLIER, DEFAULT_MIN_SAMPLES, DEFAULT_BASELINE_PERCENTILE,
                DEFAULT_BASELINE_REFRESH_INTERVAL_SECONDS);
    }

    /**
     * Creates a QueryPerformanceMonitor with update interval and classification settings.
     */
    public QueryPerformanceMonitor(long updateGlobalAvgIntervalSeconds,
                                   SlowQueryClassificationMode classificationMode,
                                   long slowQueryThresholdMs) {
        this(updateGlobalAvgIntervalSeconds, TimeProvider.SYSTEM, classificationMode, slowQueryThresholdMs,
                DEFAULT_MINIMUM_SLOW_QUERY_MS, DEFAULT_SLOW_MULTIPLIER, DEFAULT_RECOVERY_MULTIPLIER,
                DEFAULT_MIN_SAMPLES, DEFAULT_BASELINE_PERCENTILE, DEFAULT_BASELINE_REFRESH_INTERVAL_SECONDS);
    }

    /**
     * Creates a QueryPerformanceMonitor with specified update interval and time provider.
     */
    public QueryPerformanceMonitor(long updateGlobalAvgIntervalSeconds, TimeProvider timeProvider) {
        this(updateGlobalAvgIntervalSeconds, timeProvider, DEFAULT_CLASSIFICATION_MODE,
                DEFAULT_SLOW_QUERY_THRESHOLD_MS, DEFAULT_MINIMUM_SLOW_QUERY_MS, DEFAULT_SLOW_MULTIPLIER,
                DEFAULT_RECOVERY_MULTIPLIER, DEFAULT_MIN_SAMPLES, DEFAULT_BASELINE_PERCENTILE,
                DEFAULT_BASELINE_REFRESH_INTERVAL_SECONDS);
    }

    /**
     * Creates a QueryPerformanceMonitor with specified settings and time provider.
     */
    public QueryPerformanceMonitor(long updateGlobalAvgIntervalSeconds, TimeProvider timeProvider,
                                   SlowQueryClassificationMode classificationMode, long slowQueryThresholdMs) {
        this(updateGlobalAvgIntervalSeconds, timeProvider, classificationMode, slowQueryThresholdMs,
                DEFAULT_MINIMUM_SLOW_QUERY_MS, DEFAULT_SLOW_MULTIPLIER, DEFAULT_RECOVERY_MULTIPLIER,
                DEFAULT_MIN_SAMPLES, DEFAULT_BASELINE_PERCENTILE, DEFAULT_BASELINE_REFRESH_INTERVAL_SECONDS);
    }

    /**
     * Creates a QueryPerformanceMonitor with full SQS classification controls.
     */
    public QueryPerformanceMonitor(long updateGlobalAvgIntervalSeconds,
                                   SlowQueryClassificationMode classificationMode,
                                   long slowQueryThresholdMs,
                                   long minimumSlowQueryMs,
                                   double slowMultiplier,
                                   double recoveryMultiplier,
                                   int minSamples,
                                   int baselinePercentile,
                                   long baselineRefreshIntervalSeconds) { //NOSONAR
        this(updateGlobalAvgIntervalSeconds, TimeProvider.SYSTEM, classificationMode, slowQueryThresholdMs,
                minimumSlowQueryMs, slowMultiplier, recoveryMultiplier, minSamples,
                baselinePercentile, baselineRefreshIntervalSeconds);
    }

    /**
     * Creates a QueryPerformanceMonitor with full SQS classification controls and time provider.
     */
    public QueryPerformanceMonitor(long updateGlobalAvgIntervalSeconds, TimeProvider timeProvider,
                                   SlowQueryClassificationMode classificationMode,
                                   long slowQueryThresholdMs,
                                   long minimumSlowQueryMs,
                                   double slowMultiplier,
                                   double recoveryMultiplier,
                                   int minSamples,
                                   int baselinePercentile,
                                   long baselineRefreshIntervalSeconds) { //NOSONAR
        this.updateGlobalAvgIntervalSeconds = updateGlobalAvgIntervalSeconds;
        this.timeProvider = timeProvider;
        this.classificationMode = classificationMode != null ? classificationMode : DEFAULT_CLASSIFICATION_MODE;
        this.slowQueryThresholdMs = normalizeSlowQueryThresholdMs(slowQueryThresholdMs);
        this.minimumSlowQueryMs = normalizeMinimumSlowQueryMs(minimumSlowQueryMs);
        this.slowMultiplier = normalizeSlowMultiplier(slowMultiplier);
        this.recoveryMultiplier = normalizeRecoveryMultiplier(recoveryMultiplier, this.slowMultiplier);
        this.minSamples = normalizeMinSamples(minSamples);
        this.baselinePercentile = normalizeBaselinePercentile(baselinePercentile);
        this.baselineRefreshIntervalSeconds = normalizeBaselineRefreshIntervalSeconds(baselineRefreshIntervalSeconds);
        this.lastGlobalAvgUpdateTime = timeProvider.currentTimeSeconds();
        this.lastBaselineRefreshMillis = 0L;
    }

    /**
     * Records execution time for an operation in milliseconds.
     */
    public void recordExecutionTime(String operationHash, double executionTimeMs) {
        if (operationHash == null || executionTimeMs < 0) {
            log.warn("Invalid operation hash or execution time: hash={}, time={}", operationHash, executionTimeMs);
            return;
        }

        long nowMillis = currentTimeMillis();
        PerformanceRecord newRecord = new PerformanceRecord(executionTimeMs, nowMillis);
        PerformanceRecord existingRecord = operationRecords.putIfAbsent(operationHash, newRecord);
        boolean isNewOperation = existingRecord == null;
        PerformanceRecord operationPerformanceRecord = isNewOperation ? newRecord : existingRecord;
        if (!isNewOperation) {
            operationPerformanceRecord.updateAverage(executionTimeMs, nowMillis);
        }

        totalOperations.incrementAndGet();

        if (shouldUpdateGlobalAverage(isNewOperation)) {
            updateOverallAverage();
        }

        log.debug("Updated operation {} with execution time {}ms, average now {}ms",
                operationHash, executionTimeMs, operationPerformanceRecord.getAverageExecutionTime());
    }

    /**
     * Gets average execution time for a specific operation.
     */
    public double getOperationAverageTime(String operationHash) {
        PerformanceRecord operationPerformanceRecord = operationRecords.get(operationHash);
        return operationPerformanceRecord != null ? operationPerformanceRecord.getAverageExecutionTime() : 0.0;
    }

    /**
     * Gets overall average execution time across all tracked operations.
     */
    public double getOverallAverageExecutionTime() {
        return overallAverageExecutionTime;
    }

    /**
     * Determines if an operation is classified as slow.
     */
    public boolean isSlowOperation(String operationHash) {
        PerformanceRecord operationPerformanceRecord = operationRecords.get(operationHash);
        if (operationPerformanceRecord == null || operationPerformanceRecord.getSampleCount() < minSamples) {
            return false;
        }

        double operationAverage = operationPerformanceRecord.getAverageExecutionTime();
        if (classificationMode == SlowQueryClassificationMode.ABSOLUTE_THRESHOLD) {
            boolean isSlow = operationAverage >= slowQueryThresholdMs;
            log.debug("Operation {} classification: mode={}, average={}ms, threshold={}ms, slow={}",
                    operationHash, classificationMode, operationAverage, slowQueryThresholdMs, isSlow);
            return isSlow;
        }

        return classifyRelativeFastBaseline(operationHash, operationPerformanceRecord, operationAverage);
    }

    private boolean classifyRelativeFastBaseline(String operationHash, PerformanceRecord operationPerformanceRecord, double operationAverageMs) {
        double baseline = getFastBaselineMs();
        if (baseline <= 0) {
            return false;
        }

        if (operationPerformanceRecord.isCurrentlyClassifiedAsSlow()) {
            boolean shouldRecover = operationAverageMs < minimumSlowQueryMs
                    || operationAverageMs <= baseline * recoveryMultiplier;
            if (shouldRecover) {
                boolean changed = operationPerformanceRecord.updateSlowClassification(false);
                if (changed) {
                    log.debug("Query recovered to fast: hash={}, avgMs={}, baselineMs={}, recoveryMultiplier={}",
                            operationHash, operationAverageMs, baseline, recoveryMultiplier);
                }
                return false;
            }
            return true;
        }

        boolean shouldEnterSlow = operationAverageMs >= minimumSlowQueryMs
                && operationAverageMs >= baseline * slowMultiplier;
        if (shouldEnterSlow) {
            boolean changed = operationPerformanceRecord.updateSlowClassification(true);
            if (changed) {
                log.debug("Query classified as slow: hash={}, avgMs={}, baselineMs={}, multiplier={}",
                        operationHash, operationAverageMs, baseline, slowMultiplier);
            }
            return true;
        }

        return false;
    }

    /**
     * Gets the cached fast baseline in milliseconds, refreshing when needed.
     */
    public double getFastBaselineMs() {
        long nowMillis = currentTimeMillis();
        if (shouldRefreshBaseline(nowMillis)) {
            synchronized (this) {
                if (shouldRefreshBaseline(nowMillis)) {
                    refreshFastBaseline(nowMillis);
                }
            }
        }
        return fastBaselineMs;
    }

    private boolean shouldRefreshBaseline(long nowMillis) {
        return baselineRefreshIntervalSeconds == 0
                || (nowMillis - lastBaselineRefreshMillis) >= baselineRefreshIntervalSeconds * 1000L;
    }

    private void refreshFastBaseline(long nowMillis) {
        double[] eligibleFastAverages = operationRecords.values().stream()
                .filter(performanceRecord -> performanceRecord.getSampleCount() >= minSamples)
                .filter(performanceRecord -> performanceRecord.getAverageExecutionTime() > 0)
                .filter(performanceRecord -> !performanceRecord.isCurrentlyClassifiedAsSlow())
                .mapToDouble(PerformanceRecord::getAverageExecutionTime)
                .toArray();

        if (eligibleFastAverages.length == 0) {
            fastBaselineMs = 0.0;
            lastBaselineRefreshMillis = nowMillis;
            return;
        }

        Arrays.sort(eligibleFastAverages);
        fastBaselineMs = calculatePercentile(eligibleFastAverages, baselinePercentile);
        lastBaselineRefreshMillis = nowMillis;
    }

    private double calculatePercentile(double[] sortedValues, int percentile) {
        if (sortedValues.length == 0) {
            return 0.0;
        }
        // Nearest-rank percentile selection (1-based rank mapped to 0-based array index).
        // Ceil() is used for rank so p=50 on 2 values picks rank 1 (index 0 after -1),
        // keeping the baseline anchored to observed fast-shape values without interpolation.
        int rank = (int) Math.ceil((percentile / 100.0) * sortedValues.length) - 1;
        int index = Math.max(0, Math.min(rank, sortedValues.length - 1));
        return sortedValues[index];
    }

    /**
     * Updates overall average execution time.
     */
    private void updateOverallAverage() {
        if (operationRecords.isEmpty()) {
            overallAverageExecutionTime = 0.0;
            return;
        }

        double sum = operationRecords.values().stream()
                .mapToDouble(PerformanceRecord::getAverageExecutionTime)
                .sum();

        overallAverageExecutionTime = sum / operationRecords.size();
        lastGlobalAvgUpdateTime = timeProvider.currentTimeSeconds();
        log.trace("Updated overall average execution time to {}ms across {} operations",
                overallAverageExecutionTime, operationRecords.size());
    }

    private boolean shouldUpdateGlobalAverage(boolean isNewOperation) {
        if (updateGlobalAvgIntervalSeconds == 0) {
            return true;
        }

        if (isNewOperation) {
            return true;
        }

        long currentTime = timeProvider.currentTimeSeconds();
        return (currentTime - lastGlobalAvgUpdateTime) >= updateGlobalAvgIntervalSeconds;
    }

    private long normalizeSlowQueryThresholdMs(long value) {
        if (value < 0) {
            log.warn("Invalid negative slowQueryThresholdMs={}, using default {}ms",
                    value, DEFAULT_SLOW_QUERY_THRESHOLD_MS);
            return DEFAULT_SLOW_QUERY_THRESHOLD_MS;
        }
        return value;
    }

    private long normalizeMinimumSlowQueryMs(long value) {
        if (value < 0) {
            log.warn("Invalid minimumSlowQueryMs={}, using default {}ms",
                    value, DEFAULT_MINIMUM_SLOW_QUERY_MS);
            return DEFAULT_MINIMUM_SLOW_QUERY_MS;
        }
        return value;
    }

    private double normalizeSlowMultiplier(double value) {
        if (value <= 1.0) {
            log.warn("Invalid slowMultiplier={}, using default {}", value, DEFAULT_SLOW_MULTIPLIER);
            return DEFAULT_SLOW_MULTIPLIER;
        }
        return value;
    }

    private double normalizeRecoveryMultiplier(double value, double validatedSlowMultiplier) {
        if (value <= 1.0 || value >= validatedSlowMultiplier) {
            log.warn("Invalid recoveryMultiplier={} (must be > 1.0 and < slowMultiplier={}), using default {}",
                    value, validatedSlowMultiplier, DEFAULT_RECOVERY_MULTIPLIER);
            return DEFAULT_RECOVERY_MULTIPLIER;
        }
        return value;
    }

    private int normalizeMinSamples(int value) {
        if (value < 1) {
            log.warn("Invalid minSamples={}, using default {}", value, DEFAULT_MIN_SAMPLES);
            return DEFAULT_MIN_SAMPLES;
        }
        return value;
    }

    private int normalizeBaselinePercentile(int value) {
        if (value < 1 || value > 99) {
            log.warn("Invalid baselinePercentile={} (must be between 1 and 99), using default {}",
                    value, DEFAULT_BASELINE_PERCENTILE);
            return DEFAULT_BASELINE_PERCENTILE;
        }
        return value;
    }

    private long normalizeBaselineRefreshIntervalSeconds(long value) {
        if (value < 0) {
            log.warn("Invalid baselineRefreshIntervalSeconds={}, using default {}",
                    value, DEFAULT_BASELINE_REFRESH_INTERVAL_SECONDS);
            return DEFAULT_BASELINE_REFRESH_INTERVAL_SECONDS;
        }
        return value;
    }

    private long currentTimeMillis() {
        return timeProvider.currentTimeSeconds() * 1000L;
    }

    public int getTrackedOperationCount() {
        return operationRecords.size();
    }

    public long getTotalExecutionCount() {
        return totalOperations.get();
    }

    public long getClassifiedSlowOperationCount() {
        return operationRecords.values().stream().filter(PerformanceRecord::isCurrentlyClassifiedAsSlow).count();
    }

    public SlowQueryClassificationMode getClassificationMode() {
        return classificationMode;
    }

    public long getSlowQueryThresholdMs() {
        return slowQueryThresholdMs;
    }

    public long getMinimumSlowQueryMs() {
        return minimumSlowQueryMs;
    }

    public double getSlowMultiplier() {
        return slowMultiplier;
    }

    public double getRecoveryMultiplier() {
        return recoveryMultiplier;
    }

    public int getMinSamples() {
        return minSamples;
    }

    public int getBaselinePercentile() {
        return baselinePercentile;
    }

    public long getBaselineRefreshIntervalSeconds() {
        return baselineRefreshIntervalSeconds;
    }

    /**
     * Clears all performance records.
     */
    public void clear() {
        operationRecords.clear();
        overallAverageExecutionTime = 0.0;
        totalOperations.set(0);
        lastGlobalAvgUpdateTime = timeProvider.currentTimeSeconds();
        fastBaselineMs = 0.0;
        lastBaselineRefreshMillis = 0L;
        log.info("Performance monitor cleared");
    }
}
