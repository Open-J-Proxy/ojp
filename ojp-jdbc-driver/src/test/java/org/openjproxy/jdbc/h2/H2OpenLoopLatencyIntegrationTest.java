package org.openjproxy.jdbc.h2;
import org.openjproxy.jdbc.PerformanceMetrics;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.openjproxy.constants.CommonConstants;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Open-loop latency integration test for H2 through OJP.
 *
 * <p>This test intentionally acquires and closes a pooled connection for each measured operation
 * to expose potential latency hotspots across connect/execute/close flow steps.
 *
 * <p>It also intentionally enables app-side HikariCP (100/100) as a diagnostics-only exception to
 * the normal "no client-side pool with OJP" guidance, because this test specifically profiles
 * checkout/checkin overhead at fixed concurrency.
 */
@Slf4j
class H2OpenLoopLatencyIntegrationTest {

    private static final String TABLE_NAME = "h2_open_loop_latency_test";
    private static final String LOOP_MODE_PROPERTY = "h2LatencyLoopMode";
    private static final int INITIAL_ROWS = 1000;
    private static final int SELECT_QUERY_COUNT = 1000;
    private static final int WRITE_OPERATION_COUNT = 1000;
    private static final int WRITE_OPERATION_TYPE_COUNT = 3;
    private static final int HIKARI_POOL_SIZE = 100;
    private static final int SELECT_OPEN_LOOP_RATE_PER_SECOND = 250;
    private static final int WRITE_OPEN_LOOP_RATE_PER_SECOND = 250;
    private static final int OPEN_LOOP_WORKER_THREADS = 32;
    private static final long EXECUTOR_SHUTDOWN_TIMEOUT_MINUTES = 1L;
    private static final long MAX_WAIT_PARK_NANOS = TimeUnit.MILLISECONDS.toNanos(10);
    private static final long MIN_INTERVAL_NANOS = 1L;

    private static boolean isH2TestEnabled;
    private HikariDataSource dataSource;

    private enum SqlType {
        SELECT,
        INSERT,
        UPDATE,
        DELETE
    }

    private enum StepType {
        CONNECT,
        EXECUTE_QUERY,
        EXECUTE_UPDATE,
        CLOSE
    }

    private enum LoopMode {
        OPEN_LOOP,
        CLOSED_LOOP,
        BOTH
    }

    @BeforeAll
    static void setupClass() {
        isH2TestEnabled = Boolean.parseBoolean(System.getProperty("enableH2Tests", "false"));
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void shouldLogLatencyReportForOpenLoopH2Workload(
            String driverClass, String url, String user, String password) throws SQLException {

        assumeTrue(isH2TestEnabled, "Skipping H2 tests - not enabled");
        try {
            Class.forName(driverClass);
        } catch (ClassNotFoundException e) {
            throw new SQLException("JDBC driver class not found: " + driverClass, e);
        }
        dataSource = createDataSource(driverClass, url, user, password);

        Map<LoopMode, LoopRunResult> loopResults = new EnumMap<>(LoopMode.class);
        List<LoopMode> executionModes = resolveExecutionModes();
        for (LoopMode loopMode : executionModes) {
            setupSchemaAndSeedRows(url, user, password);

            Map<SqlType, List<Long>> sqlLatencies = initializeLatencyMap(SqlType.class);
            Map<StepType, List<Long>> stepLatencies = initializeLatencyMap(StepType.class);
            FailureTracker failureTracker = new FailureTracker();

            List<Integer> activeIds = new ArrayList<>(INITIAL_ROWS);
            for (int i = 1; i <= INITIAL_ROWS; i++) {
                activeIds.add(i);
            }

            long fullTestStartNanos = System.nanoTime();
            runSelectQueries(loopMode, sqlLatencies, stepLatencies, activeIds, failureTracker);
            runWriteQueries(loopMode, sqlLatencies, stepLatencies, activeIds, failureTracker);
            long fullTestDurationNanos = System.nanoTime() - fullTestStartNanos;
            int totalOperations = SELECT_QUERY_COUNT + WRITE_OPERATION_COUNT;

            logLatencyReport(loopMode, sqlLatencies, stepLatencies, fullTestDurationNanos, totalOperations, failureTracker);
            assertEquals(0, failureTracker.getTotalExceptions(),
                    "Unexpected SQL workload exceptions. See FAILURE SUMMARY section in report.");
            assertExpectedCounts(sqlLatencies, stepLatencies);
            loopResults.put(loopMode, new LoopRunResult(sqlLatencies, stepLatencies,
                    fullTestDurationNanos, totalOperations));
        }

        logModeComparison(loopResults);
    }

    private HikariDataSource createDataSource(String driverClass, String url, String user, String password) {
        HikariConfig config = new HikariConfig();
        config.setDriverClassName(driverClass);
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        // Reviewer-requested fixed-size pool to stress checkout/checkin behavior under a large pool.
        config.setMaximumPoolSize(HIKARI_POOL_SIZE);
        config.setMinimumIdle(HIKARI_POOL_SIZE);
        config.setPoolName("H2OpenLoopLatencyPool");
        // OJP server-side connection pool defaults — set explicitly so behaviour is not
        // silently governed by the classpath ojp.properties or server fallbacks.
        config.addDataSourceProperty(CommonConstants.MAXIMUM_POOL_SIZE_PROPERTY,
                String.valueOf(CommonConstants.DEFAULT_MAXIMUM_POOL_SIZE));
        config.addDataSourceProperty(CommonConstants.MINIMUM_IDLE_PROPERTY,
                String.valueOf(CommonConstants.DEFAULT_MINIMUM_IDLE));
        config.addDataSourceProperty(CommonConstants.IDLE_TIMEOUT_PROPERTY,
                String.valueOf(CommonConstants.DEFAULT_IDLE_TIMEOUT));
        config.addDataSourceProperty(CommonConstants.MAX_LIFETIME_PROPERTY,
                String.valueOf(CommonConstants.DEFAULT_MAX_LIFETIME));
        config.addDataSourceProperty(CommonConstants.CONNECTION_TIMEOUT_PROPERTY,
                String.valueOf(CommonConstants.DEFAULT_CONNECTION_TIMEOUT));
        return new HikariDataSource(config);
    }

    private void setupSchemaAndSeedRows(String url, String user, String password) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + TABLE_NAME);
            statement.execute("CREATE TABLE " + TABLE_NAME + " (id INT PRIMARY KEY, name VARCHAR(255))");
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO " + TABLE_NAME + " (id, name) VALUES (?, ?)")) {
                for (int i = 1; i <= INITIAL_ROWS; i++) {
                    insert.setInt(1, i);
                    insert.setString(2, "seed-" + i);
                    insert.addBatch();
                }
                insert.executeBatch();
            }
        }
    }

    private void runSelectQueries(LoopMode loopMode,
                                  Map<SqlType, List<Long>> sqlLatencies,
                                  Map<StepType, List<Long>> stepLatencies,
                                  List<Integer> activeIds,
                                  FailureTracker failureTracker) throws SQLException {
        executeWorkload(loopMode, SELECT_QUERY_COUNT, SELECT_OPEN_LOOP_RATE_PER_SECOND, failureTracker, operationIndex -> {
            int id = activeIds.get(ThreadLocalRandom.current().nextInt(activeIds.size()));
            withInstrumentedConnection(stepLatencies, connection -> {
                try (PreparedStatement select = connection.prepareStatement(
                        "SELECT name FROM " + TABLE_NAME + " WHERE id = ?")) {
                    select.setInt(1, id);
                    long latency = measureStepLatency(stepLatencies, StepType.EXECUTE_QUERY, () -> {
                        try (ResultSet resultSet = select.executeQuery()) {
                            assertTrue(resultSet.next(), "Expected one row for id=" + id);
                        }
                    });
                    sqlLatencies.get(SqlType.SELECT).add(latency);
                }
            });
        });
    }

    private void runWriteQueries(LoopMode loopMode,
                                 Map<SqlType, List<Long>> sqlLatencies,
                                 Map<StepType, List<Long>> stepLatencies,
                                 List<Integer> activeIds,
                                 FailureTracker failureTracker) throws SQLException {
        AtomicInteger nextInsertId = new AtomicInteger(INITIAL_ROWS + 1);
        Object activeIdsLock = new Object();
        executeWorkload(loopMode, WRITE_OPERATION_COUNT, WRITE_OPEN_LOOP_RATE_PER_SECOND, failureTracker, operationIndex -> {
            int operationType = operationIndex % WRITE_OPERATION_TYPE_COUNT;
            if (operationType == 0) {
                int newId = nextInsertId.getAndIncrement();
                withInstrumentedConnection(stepLatencies, connection -> {
                    try (PreparedStatement insert = connection.prepareStatement(
                            "INSERT INTO " + TABLE_NAME + " (id, name) VALUES (?, ?)")) {
                        insert.setInt(1, newId);
                        insert.setString(2, "insert-" + newId);
                        long latency = measureStepLatency(stepLatencies, StepType.EXECUTE_UPDATE, () -> {
                            int rows = insert.executeUpdate();
                            assertEquals(1, rows);
                        });
                        sqlLatencies.get(SqlType.INSERT).add(latency);
                    }
                });
                synchronized (activeIdsLock) {
                    activeIds.add(newId);
                }
            } else if (operationType == 1) {
                int idToUpdate;
                synchronized (activeIdsLock) {
                    idToUpdate = activeIds.get(ThreadLocalRandom.current().nextInt(activeIds.size()));
                }
                withInstrumentedConnection(stepLatencies, connection -> {
                    try (PreparedStatement update = connection.prepareStatement(
                            "UPDATE " + TABLE_NAME + " SET name = ? WHERE id = ?")) {
                        update.setString(1, "updated-" + idToUpdate);
                        update.setInt(2, idToUpdate);
                        long latency = measureStepLatency(stepLatencies, StepType.EXECUTE_UPDATE, () -> {
                            int rows = update.executeUpdate();
                            assertEquals(1, rows);
                        });
                        sqlLatencies.get(SqlType.UPDATE).add(latency);
                    }
                });
            } else {
                int idToDelete;
                synchronized (activeIdsLock) {
                    int deleteIndex = ThreadLocalRandom.current().nextInt(activeIds.size());
                    idToDelete = activeIds.remove(deleteIndex);
                }
                withInstrumentedConnection(stepLatencies, connection -> {
                    try (PreparedStatement delete = connection.prepareStatement(
                            "DELETE FROM " + TABLE_NAME + " WHERE id = ?")) {
                        delete.setInt(1, idToDelete);
                        long latency = measureStepLatency(stepLatencies, StepType.EXECUTE_UPDATE, () -> {
                            int rows = delete.executeUpdate();
                            assertEquals(1, rows);
                        });
                        sqlLatencies.get(SqlType.DELETE).add(latency);
                    }
                });
            }
        });
    }

    private void logLatencyReport(LoopMode loopMode,
                                  Map<SqlType, List<Long>> sqlLatencies,
                                  Map<StepType, List<Long>> stepLatencies,
                                  long fullTestDurationNanos,
                                  int totalOperations,
                                  FailureTracker failureTracker) {
        StringBuilder report = new StringBuilder();
        double fullTestDurationMs = nanosToMillis(fullTestDurationNanos);
        double fullTestAvgPerOperationMs = calculateAverageMsPerOperation(fullTestDurationNanos, totalOperations);
        report.append(String.format("%n=== H2 LATENCY REPORT (%s) ===%n", loopMode));
        report.append(String.format("Full test wall-clock time (start->end): %.3f ms%n", fullTestDurationMs));
        report.append(String.format("Full test average time per operation (wall-clock/ops): %.3f ms%n",
                fullTestAvgPerOperationMs));
        report.append(String.format("Total operations: %d%n", totalOperations));
        report.append(String.format("SELECT operations: %d%n", sqlLatencies.get(SqlType.SELECT).size()));
        report.append(String.format("INSERT operations: %d%n", sqlLatencies.get(SqlType.INSERT).size()));
        report.append(String.format("UPDATE operations: %d%n", sqlLatencies.get(SqlType.UPDATE).size()));
        report.append(String.format("DELETE operations: %d%n", sqlLatencies.get(SqlType.DELETE).size()));
        report.append('\n');

        appendFailureSummary(report, failureTracker);
        report.append('\n');

        appendLatencyLine(report, "SELECT", sqlLatencies.get(SqlType.SELECT));
        appendLatencyLine(report, "INSERT", sqlLatencies.get(SqlType.INSERT));
        appendLatencyLine(report, "UPDATE", sqlLatencies.get(SqlType.UPDATE));
        appendLatencyLine(report, "DELETE", sqlLatencies.get(SqlType.DELETE));
        report.append('\n');

        report.append("=== STEP LATENCY MEDIANS ===\n");
        appendLatencyLine(report, "connect", stepLatencies.get(StepType.CONNECT));
        appendLatencyLine(report, "executeQuery", stepLatencies.get(StepType.EXECUTE_QUERY));
        appendLatencyLine(report, "executeUpdate", stepLatencies.get(StepType.EXECUTE_UPDATE));
        appendLatencyLine(report, "close", stepLatencies.get(StepType.CLOSE));
        report.append('\n');

        long totalJdbcStepCount = stepLatencies.get(StepType.CONNECT).size()
                + stepLatencies.get(StepType.EXECUTE_QUERY).size()
                + stepLatencies.get(StepType.EXECUTE_UPDATE).size()
                + stepLatencies.get(StepType.CLOSE).size();
        report.append("=== JDBC STEP COUNT (PROXY FOR gRPC INTERACTION POINTS) ===\n");
        report.append(String.format("Total JDBC step count: %d%n", totalJdbcStepCount));
        report.append("Proxy formula: connect + executeQuery + executeUpdate + close call counts\n");
        report.append("Note: this is not a direct gRPC decoder metric; it approximates the number of JDBC-layer\n");
        report.append("request/response touch points where parse work can happen, and serves as an upper-bound proxy.\n");

        log.info(report.toString());
    }

    private void appendFailureSummary(StringBuilder report, FailureTracker failureTracker) {
        report.append("=== FAILURE SUMMARY ===\n");
        report.append(String.format("Total exceptions: %d%n", failureTracker.getTotalExceptions()));
        Map<String, Integer> exceptionCounts = failureTracker.snapshotExceptionCounts();
        if (exceptionCounts.isEmpty()) {
            report.append("No exceptions recorded.\n");
            return;
        }
        for (Map.Entry<String, Integer> entry : exceptionCounts.entrySet()) {
            report.append(String.format("%s -> %d%n", entry.getKey(), entry.getValue()));
        }
    }

    private void logModeComparison(Map<LoopMode, LoopRunResult> loopResults) {
        LoopRunResult openLoopResult = loopResults.get(LoopMode.OPEN_LOOP);
        LoopRunResult closedLoopResult = loopResults.get(LoopMode.CLOSED_LOOP);
        if (openLoopResult == null || closedLoopResult == null) {
            return;
        }

        StringBuilder report = new StringBuilder();
        report.append(String.format("%n=== OPEN LOOP VS CLOSED LOOP COMPARISON (P50 MS) ===%n"));
        appendComparisonLine(report, "SELECT",
                calculateMedianMs(openLoopResult.getSqlLatencies().get(SqlType.SELECT)),
                calculateMedianMs(closedLoopResult.getSqlLatencies().get(SqlType.SELECT)));
        appendComparisonLine(report, "INSERT",
                calculateMedianMs(openLoopResult.getSqlLatencies().get(SqlType.INSERT)),
                calculateMedianMs(closedLoopResult.getSqlLatencies().get(SqlType.INSERT)));
        appendComparisonLine(report, "UPDATE",
                calculateMedianMs(openLoopResult.getSqlLatencies().get(SqlType.UPDATE)),
                calculateMedianMs(closedLoopResult.getSqlLatencies().get(SqlType.UPDATE)));
        appendComparisonLine(report, "DELETE",
                calculateMedianMs(openLoopResult.getSqlLatencies().get(SqlType.DELETE)),
                calculateMedianMs(closedLoopResult.getSqlLatencies().get(SqlType.DELETE)));
        appendComparisonLine(report, "connect",
                calculateMedianMs(openLoopResult.getStepLatencies().get(StepType.CONNECT)),
                calculateMedianMs(closedLoopResult.getStepLatencies().get(StepType.CONNECT)));
        appendComparisonLine(report, "executeQuery",
                calculateMedianMs(openLoopResult.getStepLatencies().get(StepType.EXECUTE_QUERY)),
                calculateMedianMs(closedLoopResult.getStepLatencies().get(StepType.EXECUTE_QUERY)));
        appendComparisonLine(report, "executeUpdate",
                calculateMedianMs(openLoopResult.getStepLatencies().get(StepType.EXECUTE_UPDATE)),
                calculateMedianMs(closedLoopResult.getStepLatencies().get(StepType.EXECUTE_UPDATE)));
        appendComparisonLine(report, "close",
                calculateMedianMs(openLoopResult.getStepLatencies().get(StepType.CLOSE)),
                calculateMedianMs(closedLoopResult.getStepLatencies().get(StepType.CLOSE)));
        report.append('\n');
        report.append("=== FULL TEST DURATION COMPARISON ===\n");
        appendDurationComparisonLine(report, "full-test-wall-clock-time(start-end)",
                openLoopResult.getFullTestDurationNanos(), closedLoopResult.getFullTestDurationNanos());
        appendDurationPerOperationComparisonLine(report, "full-test-average-per-operation",
                openLoopResult.getFullTestDurationNanos(), openLoopResult.getTotalOperations(),
                closedLoopResult.getFullTestDurationNanos(), closedLoopResult.getTotalOperations());
        log.info(report.toString());
    }

    private void appendComparisonLine(StringBuilder report, String metric, double openLoopMs, double closedLoopMs) {
        double deltaMs = openLoopMs - closedLoopMs;
        report.append(String.format("%s: open=%.3f, closed=%.3f, delta=%.3f ms%n",
                metric, openLoopMs, closedLoopMs, deltaMs));
    }

    private void appendLatencyLine(StringBuilder report, String type, List<Long> values) {
        double medianMs = calculateMedianMs(values);
        report.append(String.format("%s median latency: %.3f ms%n", type, medianMs));
    }

    private void appendDurationComparisonLine(StringBuilder report,
                                              String metric,
                                              long openLoopDurationNanos,
                                              long closedLoopDurationNanos) {
        double openLoopMs = openLoopDurationNanos / 1_000_000.0;
        double closedLoopMs = closedLoopDurationNanos / 1_000_000.0;
        double deltaMs = openLoopMs - closedLoopMs;
        report.append(String.format("%s: open=%.3f, closed=%.3f, delta=%.3f ms%n",
                metric, openLoopMs, closedLoopMs, deltaMs));
    }

    private void appendDurationPerOperationComparisonLine(StringBuilder report,
                                                          String metric,
                                                          long openLoopDurationNanos,
                                                          int openLoopOperations,
                                                          long closedLoopDurationNanos,
                                                          int closedLoopOperations) {
        double openLoopMs = calculateAverageMsPerOperation(openLoopDurationNanos, openLoopOperations);
        double closedLoopMs = calculateAverageMsPerOperation(closedLoopDurationNanos, closedLoopOperations);
        double deltaMs = openLoopMs - closedLoopMs;
        report.append(String.format("%s: open=%.3f, closed=%.3f, delta=%.3f ms%n",
                metric, openLoopMs, closedLoopMs, deltaMs));
    }

    private double calculateAverageMsPerOperation(long durationNanos, int operationCount) {
        if (operationCount <= 0) {
            return 0.0;
        }
        return nanosToMillis(durationNanos) / operationCount;
    }

    private double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0;
    }

    private double calculateMedianMs(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compareTo);
        double medianNs = PerformanceMetrics.calculatePercentile(sorted, 50);
        return medianNs / 1_000_000.0;
    }

    private <E extends Enum<E>> Map<E, List<Long>> initializeLatencyMap(Class<E> enumClass) {
        Map<E, List<Long>> map = new EnumMap<>(enumClass);
        for (E value : enumClass.getEnumConstants()) {
            map.put(value, Collections.synchronizedList(new ArrayList<>()));
        }
        return map;
    }

    @FunctionalInterface
    private interface SqlOperation {
        void run() throws SQLException;
    }

    @FunctionalInterface
    private interface ConnectionOperation {
        void run(Connection connection) throws SQLException;
    }

    @FunctionalInterface
    private interface IndexedSqlOperation {
        void run(int operationIndex) throws SQLException;
    }

    private static final class LoopRunResult {
        private final Map<SqlType, List<Long>> sqlLatencies;
        private final Map<StepType, List<Long>> stepLatencies;
        private final long fullTestDurationNanos;
        private final int totalOperations;

        LoopRunResult(Map<SqlType, List<Long>> sqlLatencies,
                      Map<StepType, List<Long>> stepLatencies,
                      long fullTestDurationNanos,
                      int totalOperations) {
            this.sqlLatencies = copyLatencyMap(sqlLatencies);
            this.stepLatencies = copyLatencyMap(stepLatencies);
            this.fullTestDurationNanos = fullTestDurationNanos;
            this.totalOperations = totalOperations;
        }

        Map<SqlType, List<Long>> getSqlLatencies() {
            return Collections.unmodifiableMap(sqlLatencies);
        }

        Map<StepType, List<Long>> getStepLatencies() {
            return Collections.unmodifiableMap(stepLatencies);
        }

        long getFullTestDurationNanos() {
            return fullTestDurationNanos;
        }

        int getTotalOperations() {
            return totalOperations;
        }

        private static <E extends Enum<E>> Map<E, List<Long>> copyLatencyMap(Map<E, List<Long>> source) {
            Class<E> enumClass = source.keySet().iterator().next().getDeclaringClass();
            Map<E, List<Long>> copy = new EnumMap<>(enumClass);
            for (Map.Entry<E, List<Long>> entry : source.entrySet()) {
                copy.put(entry.getKey(), new ArrayList<>(entry.getValue()));
            }
            return copy;
        }
    }

    private long measureStepLatency(Map<StepType, List<Long>> stepLatencies,
                                    StepType stepType,
                                    SqlOperation operation) throws SQLException {
        long start = System.nanoTime();
        operation.run();
        long latency = System.nanoTime() - start;
        stepLatencies.get(stepType).add(latency);
        return latency;
    }

    private void withInstrumentedConnection(Map<StepType, List<Long>> stepLatencies,
                                            ConnectionOperation operation) throws SQLException {
        Connection connection = null;
        try {
            long connectStart = System.nanoTime();
            connection = dataSource.getConnection();
            stepLatencies.get(StepType.CONNECT).add(System.nanoTime() - connectStart);
            operation.run(connection);
        } finally {
            if (connection != null) {
                long closeStart = System.nanoTime();
                try {
                    connection.close();
                } finally {
                    // Include both success-path and error-path close costs in diagnostics.
                    stepLatencies.get(StepType.CLOSE).add(System.nanoTime() - closeStart);
                }
            }
        }
    }

    private List<LoopMode> resolveExecutionModes() {
        String rawMode = System.getProperty(LOOP_MODE_PROPERTY, LoopMode.BOTH.name());
        LoopMode loopMode = parseLoopMode(rawMode);
        if (loopMode == LoopMode.BOTH) {
            List<LoopMode> modes = new ArrayList<>(2);
            modes.add(LoopMode.OPEN_LOOP);
            modes.add(LoopMode.CLOSED_LOOP);
            return modes;
        }
        List<LoopMode> singleMode = new ArrayList<>(1);
        singleMode.add(loopMode);
        return singleMode;
    }

    private LoopMode parseLoopMode(String rawMode) {
        String normalized = rawMode.trim().toUpperCase();
        switch (normalized) {
            case "OPEN":
            case "OPEN_LOOP":
                return LoopMode.OPEN_LOOP;
            case "CLOSED":
            case "CLOSED_LOOP":
                return LoopMode.CLOSED_LOOP;
            case "BOTH":
                return LoopMode.BOTH;
            default:
                throw new IllegalArgumentException("Invalid " + LOOP_MODE_PROPERTY + " value: " + rawMode
                        + ". Allowed values: OPEN, CLOSED, OPEN_LOOP, CLOSED_LOOP, BOTH");
        }
    }

    private void assertExpectedCounts(Map<SqlType, List<Long>> sqlLatencies,
                                      Map<StepType, List<Long>> stepLatencies) {
        assertEquals(SELECT_QUERY_COUNT, sqlLatencies.get(SqlType.SELECT).size());
        assertEquals(WRITE_OPERATION_COUNT, sqlLatencies.get(SqlType.INSERT).size()
                + sqlLatencies.get(SqlType.UPDATE).size()
                + sqlLatencies.get(SqlType.DELETE).size());
        assertFalse(sqlLatencies.get(SqlType.INSERT).isEmpty());
        assertFalse(sqlLatencies.get(SqlType.UPDATE).isEmpty());
        assertFalse(sqlLatencies.get(SqlType.DELETE).isEmpty());
        assertEquals(SELECT_QUERY_COUNT + WRITE_OPERATION_COUNT, stepLatencies.get(StepType.CONNECT).size());
        assertEquals(SELECT_QUERY_COUNT + WRITE_OPERATION_COUNT, stepLatencies.get(StepType.CLOSE).size());
        assertEquals(SELECT_QUERY_COUNT, stepLatencies.get(StepType.EXECUTE_QUERY).size());
        assertEquals(WRITE_OPERATION_COUNT, stepLatencies.get(StepType.EXECUTE_UPDATE).size());
    }

    private void executeWorkload(LoopMode loopMode,
                                 int operationCount,
                                 int operationsPerSecond,
                                 FailureTracker failureTracker,
                                 IndexedSqlOperation operation) throws SQLException {
        if (loopMode == LoopMode.OPEN_LOOP) {
            executeOpenLoopWorkload(operationCount, operationsPerSecond, failureTracker, operation);
            return;
        }
        executeClosedLoopWorkload(operationCount, failureTracker, operation);
    }

    private void executeClosedLoopWorkload(int operationCount,
                                           FailureTracker failureTracker,
                                           IndexedSqlOperation operation) throws SQLException {
        for (int i = 0; i < operationCount; i++) {
            try {
                operation.run(i);
            } catch (SQLException e) {
                failureTracker.record(e);
            }
        }
    }

    private void executeOpenLoopWorkload(int operationCount,
                                         int operationsPerSecond,
                                         FailureTracker failureTracker,
                                         IndexedSqlOperation operation) throws SQLException {
        if (operationsPerSecond <= 0) {
            throw new SQLException("operationsPerSecond must be greater than zero");
        }
        ExecutorService executorService = Executors.newFixedThreadPool(OPEN_LOOP_WORKER_THREADS);
        List<Future<?>> futures = new ArrayList<>(operationCount);
        long intervalNanos = Math.max(MIN_INTERVAL_NANOS, TimeUnit.SECONDS.toNanos(1) / operationsPerSecond);
        long startTimeNanos = System.nanoTime();

        for (int i = 0; i < operationCount; i++) {
            long targetTimeNanos = startTimeNanos + (long) i * intervalNanos;
            waitUntil(targetTimeNanos);
            final int operationIndex = i;
            futures.add(executorService.submit(() -> {
                try {
                    operation.run(operationIndex);
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            }));
        }

        executorService.shutdown();
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SQLException("Open-loop workload interrupted", e);
            } catch (ExecutionException e) {
                failureTracker.record(extractSqlException(e));
            }
        }

        try {
            if (!executorService.awaitTermination(EXECUTOR_SHUTDOWN_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted while terminating open-loop workload", e);
        }
    }

    private void waitUntil(long targetTimeNanos) {
        while (true) {
            long remainingNanos = targetTimeNanos - System.nanoTime();
            if (remainingNanos <= 0L) {
                return;
            }
            LockSupport.parkNanos(Math.min(remainingNanos, MAX_WAIT_PARK_NANOS));
        }
    }

    private SQLException extractSqlException(ExecutionException executionException) {
        Throwable cause = executionException.getCause();
        while (cause != null) {
            if (cause instanceof SQLException) {
                return (SQLException) cause;
            }
            cause = cause.getCause();
        }
        return new SQLException("Open-loop operation failed", executionException.getCause());
    }

    private static final class FailureTracker {
        private static final String NO_EXCEPTION_MESSAGE_MARKER = "<no-message>";
        private static final int MAX_CAUSE_UNWRAP_DEPTH = 100;
        private final AtomicInteger totalExceptions = new AtomicInteger();
        private final Map<String, AtomicInteger> exceptionCounts = new ConcurrentHashMap<>();

        void record(Throwable throwable) {
            Throwable rootCause = unwrapRootCause(throwable);
            String message = rootCause.getMessage() == null
                    ? NO_EXCEPTION_MESSAGE_MARKER
                    : rootCause.getMessage().replace("\n", "\\n").replace("\r", "\\r");
            String key = rootCause.getClass().getName() + " | message=\"" + message + "\"";
            exceptionCounts.computeIfAbsent(key, ignored -> new AtomicInteger()).incrementAndGet();
            totalExceptions.incrementAndGet();
        }

        int getTotalExceptions() {
            return totalExceptions.get();
        }

        Map<String, Integer> snapshotExceptionCounts() {
            Map<String, Integer> snapshot = new TreeMap<>();
            for (Map.Entry<String, AtomicInteger> entry : exceptionCounts.entrySet()) {
                snapshot.put(entry.getKey(), entry.getValue().get());
            }
            return snapshot;
        }

        private Throwable unwrapRootCause(Throwable throwable) {
            Throwable current = throwable;
            int depth = 0;
            while (current.getCause() != null && depth < MAX_CAUSE_UNWRAP_DEPTH) {
                current = current.getCause();
                depth++;
            }
            return current;
        }
    }
}
