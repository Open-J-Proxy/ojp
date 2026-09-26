package org.openjproxy.jdbc.postgres;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.openjproxy.jdbc.PerformanceMetrics;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class PostgresEagerCloseConcurrencyComparisonIntegrationTest {

    private static final String TABLE_NAME = "ojp_eager_close_benchmark";
    private static final int CONCURRENT_THREADS = 100;
    private static final int WARMUP_OPERATIONS = 100;
    private static final int MEASURED_OPERATIONS = 1000;
    private static final int POOL_SIZE = 20;
    private static final int SEED_ROWS = 2000;

    private static boolean isTestEnabled;

    @BeforeAll
    static void checkTestConfiguration() {
        isTestEnabled = Boolean.parseBoolean(System.getProperty("enablePostgresTests", "false"));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    void shouldShowBetterP95LatencyWithEagerCloseForConcurrentMixedDml(
            String driverClass, String url, String user, String password) throws Exception {
        assumeFalse(!isTestEnabled, "Postgres tests are disabled");

        ScenarioResult eagerCloseDisabled = runScenario(url, user, password, false);
        ScenarioResult eagerCloseEnabled = runScenario(url, user, password, true);

        logScenario("disabled", eagerCloseDisabled);
        logScenario("enabled", eagerCloseEnabled);

        assertEquals(0, eagerCloseDisabled.failures(), "Baseline run should not fail operations");
        assertEquals(0, eagerCloseEnabled.failures(), "Eager-close run should not fail operations");
        assertTrue(
                eagerCloseEnabled.p95LatencyNanos() < eagerCloseDisabled.p95LatencyNanos(),
                "Expected eager-close p95 latency to be better. disabled="
                        + toMillis(eagerCloseDisabled.p95LatencyNanos())
                        + " ms, enabled="
                        + toMillis(eagerCloseEnabled.p95LatencyNanos())
                        + " ms"
        );
    }

    /**
     * Note for reliability runs: restarting the PostgreSQL container between scenarios is acceptable.
     * Each scenario re-creates and re-seeds the table, then runs warmup and measured operations independently.
     */
    private ScenarioResult runScenario(String url, String user, String password, boolean eagerCloseEnabled) throws Exception {
        Properties connectionProperties = createConnectionProperties(user, password, eagerCloseEnabled);
        prepareBenchmarkTable(url, connectionProperties);

        executeConcurrentMixedDml(url, connectionProperties, WARMUP_OPERATIONS);
        return executeConcurrentMixedDml(url, connectionProperties, MEASURED_OPERATIONS);
    }

    private Properties createConnectionProperties(String user, String password, boolean eagerCloseEnabled) {
        Properties properties = new Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", password);
        properties.setProperty("ojp.statement.eagerClose.enabled", String.valueOf(eagerCloseEnabled));
        properties.setProperty("ojp.connection.pool.maximumPoolSize", String.valueOf(POOL_SIZE));
        properties.setProperty("ojp.connection.pool.minimumIdle", "2");
        return properties;
    }

    private void prepareBenchmarkTable(String url, Properties properties) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, properties);
                Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + TABLE_NAME);
            statement.execute(
                    "CREATE TABLE " + TABLE_NAME + " ("
                            + "id BIGINT PRIMARY KEY, "
                            + "payload VARCHAR(120), "
                            + "updated_at TIMESTAMP DEFAULT NOW()"
                            + ")"
            );
        }

        try (Connection connection = DriverManager.getConnection(url, properties);
                PreparedStatement insertStatement = connection.prepareStatement(
                        "INSERT INTO " + TABLE_NAME + " (id, payload) VALUES (?, ?)")) {
            for (int id = 1; id <= SEED_ROWS; id++) {
                insertStatement.setLong(1, id);
                insertStatement.setString(2, "seed-" + id);
                insertStatement.addBatch();
            }
            insertStatement.executeBatch();
        }
    }

    private ScenarioResult executeConcurrentMixedDml(String url, Properties properties, int operationCount) throws Exception {
        ExecutorService executorService = Executors.newFixedThreadPool(CONCURRENT_THREADS);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<Long> latencies = Collections.synchronizedList(new ArrayList<>(operationCount));
        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger failures = new AtomicInteger(0);
        AtomicInteger insertIds = new AtomicInteger(SEED_ROWS + 1);

        for (int operationIndex = 0; operationIndex < operationCount; operationIndex++) {
            final int currentIndex = operationIndex;
            executorService.submit(() -> {
                startLatch.await();
                long start = System.nanoTime();
                try {
                    executeSingleOperation(url, properties, currentIndex, insertIds);
                    successes.incrementAndGet();
                } catch (SQLException e) {
                    failures.incrementAndGet();
                } finally {
                    latencies.add(System.nanoTime() - start);
                }
                return null;
            });
        }

        startLatch.countDown();
        executorService.shutdown();
        assertTrue(executorService.awaitTermination(5, TimeUnit.MINUTES), "Timed out waiting operations to finish");

        List<Long> sortedLatencies = new ArrayList<>(latencies);
        Collections.sort(sortedLatencies);
        long p95Latency = (long) PerformanceMetrics.calculatePercentile(sortedLatencies, 95);

        return new ScenarioResult(successes.get(), failures.get(), p95Latency);
    }

    private void executeSingleOperation(String url, Properties properties, int operationIndex, AtomicInteger insertIds)
            throws SQLException {
        int operationType = operationIndex % 3;
        try (Connection connection = DriverManager.getConnection(url, properties)) {
            if (operationType == 0) {
                executeInsert(connection, insertIds.incrementAndGet());
            } else if (operationType == 1) {
                executeUpdate(connection, (operationIndex % SEED_ROWS) + 1);
            } else {
                executeDelete(connection, (operationIndex % SEED_ROWS) + 1);
            }
        }
    }

    private void executeInsert(Connection connection, int id) throws SQLException {
        try (PreparedStatement preparedStatement = connection.prepareStatement(
                "INSERT INTO " + TABLE_NAME + " (id, payload) VALUES (?, ?)")) {
            preparedStatement.setInt(1, id);
            preparedStatement.setString(2, "payload-" + id);
            preparedStatement.executeUpdate();
        }
    }

    private void executeUpdate(Connection connection, int id) throws SQLException {
        try (PreparedStatement preparedStatement = connection.prepareStatement(
                "UPDATE " + TABLE_NAME + " SET payload = ?, updated_at = NOW() WHERE id = ?")) {
            preparedStatement.setString(1, "updated-" + id);
            preparedStatement.setInt(2, id);
            preparedStatement.executeUpdate();
        }
    }

    private void executeDelete(Connection connection, int id) throws SQLException {
        try (PreparedStatement preparedStatement = connection.prepareStatement(
                "DELETE FROM " + TABLE_NAME + " WHERE id = ?")) {
            preparedStatement.setInt(1, id);
            preparedStatement.executeUpdate();
        }
    }

    private void logScenario(String mode, ScenarioResult result) {
        System.out.println(
                "eagerClose=" + mode
                        + ", p95Ms=" + toMillis(result.p95LatencyNanos())
                        + ", successes=" + result.successes()
                        + ", failures=" + result.failures());
    }

    private double toMillis(long nanos) {
        return nanos / 1_000_000.0;
    }

    private static final class ScenarioResult {
        private final int successes;
        private final int failures;
        private final long p95LatencyNanos;

        private ScenarioResult(int successes, int failures, long p95LatencyNanos) {
            this.successes = successes;
            this.failures = failures;
            this.p95LatencyNanos = p95LatencyNanos;
        }

        private int successes() {
            return successes;
        }

        private int failures() {
            return failures;
        }

        private long p95LatencyNanos() {
            return p95LatencyNanos;
        }
    }
}
