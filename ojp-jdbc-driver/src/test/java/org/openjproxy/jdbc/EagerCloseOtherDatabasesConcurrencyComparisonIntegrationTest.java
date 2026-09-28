package org.openjproxy.jdbc;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.openjproxy.jdbc.testutil.ToxiproxyOjpUrlBridge;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class EagerCloseOtherDatabasesConcurrencyComparisonIntegrationTest {

    private static final int CONCURRENT_THREADS = 100;
    private static final int WARMUP_OPERATIONS = 100;
    private static final int MEASURED_OPERATIONS = 1000;
    private static final int POOL_SIZE = 20;
    private static final int POOL_CONNECTION_TIMEOUT_MS = 5_000;
    private static final int SEED_ROWS = 2000;
    private static final int TOXIPROXY_REQUEST_LATENCY_MS = 20;
    private static final String TABLE_PREFIX = "OJP_EC_";

    private static boolean isH2TestEnabled;
    private static boolean isMySQLTestEnabled;
    private static boolean isMariaDBTestEnabled;
    private static boolean isOracleTestEnabled;
    private static boolean isSqlServerTestEnabled;
    private static boolean isDb2TestEnabled;

    @BeforeAll
    static void checkTestConfiguration() {
        isH2TestEnabled = Boolean.parseBoolean(System.getProperty("enableH2Tests", "false"));
        isMySQLTestEnabled = Boolean.parseBoolean(System.getProperty("enableMySQLTests", "false"));
        isMariaDBTestEnabled = Boolean.parseBoolean(System.getProperty("enableMariaDBTests", "false"));
        isOracleTestEnabled = Boolean.parseBoolean(System.getProperty("enableOracleTests", "false"));
        isSqlServerTestEnabled = Boolean.parseBoolean(System.getProperty("enableSqlServerTests", "false"));
        isDb2TestEnabled = Boolean.parseBoolean(System.getProperty("enableDb2Tests", "false"));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/eager_close_other_databases_connection.csv")
    void shouldShowBetterP95LatencyWithEagerCloseForConcurrentMixedDmlOnOtherDatabases(
            String driverClass, String url, String user, String password) throws Exception {
        assumeFalse(!isScenarioEnabled(url), "Database tests are disabled for this URL");

        try (ToxiproxyOjpUrlBridge toxiproxy = ToxiproxyOjpUrlBridge.withRequestLatency(url, TOXIPROXY_REQUEST_LATENCY_MS)) {
            String proxiedUrl = toxiproxy.proxiedJdbcUrl();
            String uniqueBase = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            String tableNameOff = TABLE_PREFIX + uniqueBase + "_O";
            String tableNameOn = TABLE_PREFIX + uniqueBase + "_N";
            String dataSourceNameOff = "ojp_ec_" + uniqueBase + "_off";
            String dataSourceNameOn = "ojp_ec_" + uniqueBase + "_on";

            ScenarioResult eagerCloseDisabled = runScenario(
                    proxiedUrl, user, password, false, tableNameOff, dataSourceNameOff);
            ScenarioResult eagerCloseEnabled = runScenario(
                    proxiedUrl, user, password, true, tableNameOn, dataSourceNameOn);

            assertScenarioOperationAccounting(eagerCloseDisabled, "Baseline run");
            assertScenarioOperationAccounting(eagerCloseEnabled, "Eager-close run");
            logScenario("disabled", eagerCloseDisabled);
            logScenario("enabled", eagerCloseEnabled);

            assertTrue(
                    eagerCloseEnabled.p95LatencyNanos() < eagerCloseDisabled.p95LatencyNanos(),
                    "Expected eager-close p95 latency to be better. baseline="
                            + toMillis(eagerCloseDisabled.p95LatencyNanos())
                            + " ms, enabled="
                            + toMillis(eagerCloseEnabled.p95LatencyNanos())
                            + " ms"
            );
        }
    }

    private boolean isScenarioEnabled(String url) {
        if (url.contains("_h2:")) {
            return isH2TestEnabled;
        }
        if (url.contains("_mysql:")) {
            return isMySQLTestEnabled;
        }
        if (url.contains("_mariadb:")) {
            return isMariaDBTestEnabled;
        }
        if (url.contains("_oracle:")) {
            return isOracleTestEnabled;
        }
        if (url.contains("_sqlserver:")) {
            return isSqlServerTestEnabled;
        }
        if (url.contains("_db2:")) {
            return isDb2TestEnabled;
        }
        return false;
    }

    private ScenarioResult runScenario(
            String url, String user, String password, boolean eagerCloseEnabled, String tableName, String dataSourceName)
            throws Exception {
        Properties connectionProperties = createConnectionProperties(user, password, eagerCloseEnabled, dataSourceName);
        prepareBenchmarkTable(url, connectionProperties, tableName);
        AtomicInteger insertIds = new AtomicInteger(SEED_ROWS + 1);

        executeConcurrentMixedDml(url, connectionProperties, WARMUP_OPERATIONS, tableName, insertIds);
        return executeConcurrentMixedDml(url, connectionProperties, MEASURED_OPERATIONS, tableName, insertIds);
    }

    private Properties createConnectionProperties(
            String user, String password, boolean eagerCloseEnabled, String dataSourceName) {
        Properties properties = new Properties();
        properties.setProperty("user", user == null ? "" : user);
        properties.setProperty("password", password == null ? "" : password);
        properties.setProperty("ojp.statement.eagerClose.enabled", String.valueOf(eagerCloseEnabled));
        properties.setProperty("ojp.connection.pool.maximumPoolSize", String.valueOf(POOL_SIZE));
        properties.setProperty("ojp.connection.pool.minimumIdle", "2");
        properties.setProperty("ojp.connection.pool.connectionTimeout", String.valueOf(POOL_CONNECTION_TIMEOUT_MS));
        properties.setProperty("ojp.datasource.name", dataSourceName);
        return properties;
    }

    private void prepareBenchmarkTable(String url, Properties properties, String tableName) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, properties);
                Statement statement = connection.createStatement()) {
            dropTableIfPresent(statement, tableName);
            statement.execute(
                    "CREATE TABLE " + tableName + " ("
                            + "id INTEGER, "
                            + "payload VARCHAR(120)"
                            + ")"
            );

            try (PreparedStatement preparedStatement = connection.prepareStatement(
                    "INSERT INTO " + tableName + " (id, payload) VALUES (?, ?)")) {
                for (int id = 1; id <= SEED_ROWS; id++) {
                    preparedStatement.setInt(1, id);
                    preparedStatement.setString(2, "seed-" + id);
                    preparedStatement.executeUpdate();
                }
            }
        }
    }

    private void dropTableIfPresent(Statement statement, String tableName) {
        try {
            statement.execute("DROP TABLE " + tableName);
        } catch (SQLException ignored) {
            // Table may not exist in this database yet.
        }
    }

    private ScenarioResult executeConcurrentMixedDml(
            String url, Properties properties, int operationCount, String tableName, AtomicInteger insertIds)
            throws Exception {
        ExecutorService executorService = Executors.newFixedThreadPool(CONCURRENT_THREADS);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<Long> latencies = Collections.synchronizedList(new ArrayList<>(operationCount));
        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger failures = new AtomicInteger(0);

        for (int operationIndex = 0; operationIndex < operationCount; operationIndex++) {
            final int currentIndex = operationIndex;
            executorService.submit(() -> {
                startLatch.await();
                long start = System.nanoTime();
                try {
                    executeSingleOperation(url, properties, currentIndex, insertIds, tableName);
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

    private void executeSingleOperation(
            String url, Properties properties, int operationIndex, AtomicInteger insertIds, String tableName)
            throws SQLException {
        int operationType = operationIndex % 3;
        try (Connection connection = DriverManager.getConnection(url, properties)) {
            if (operationType == 0) {
                executeInsert(connection, insertIds.incrementAndGet(), tableName);
            } else if (operationType == 1) {
                executeUpdate(connection, (operationIndex % SEED_ROWS) + 1, tableName);
            } else {
                executeDelete(connection, (operationIndex % SEED_ROWS) + 1, tableName);
            }
        }
    }

    private void executeInsert(Connection connection, int id, String tableName) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "INSERT INTO " + tableName + " (id, payload) VALUES (" + id + ", 'payload-" + id + "')"
            );
        }
    }

    private void executeUpdate(Connection connection, int id, String tableName) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "UPDATE " + tableName + " SET payload = 'updated-" + id + "' WHERE id = " + id
            );
        }
    }

    private void executeDelete(Connection connection, int id, String tableName) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM " + tableName + " WHERE id = " + id);
        }
    }

    private void logScenario(String mode, ScenarioResult result) {
        double failureRate = failureRate(result);
        double successRate = 1.0D - failureRate;
        System.out.println(
                "eagerClose=" + mode
                        + ", p95Ms=" + toMillis(result.p95LatencyNanos())
                        + ", successes=" + result.successes()
                        + ", failures=" + result.failures()
                        + ", successRate=" + successRate
                        + ", failureRate=" + failureRate);
    }

    private double toMillis(long nanos) {
        return nanos / 1_000_000.0;
    }

    private double failureRate(ScenarioResult result) {
        return (double) result.failures() / MEASURED_OPERATIONS;
    }

    private void assertScenarioOperationAccounting(ScenarioResult result, String scenarioLabel) {
        assertEquals(
                MEASURED_OPERATIONS,
                result.successes() + result.failures(),
                scenarioLabel + " should report all measured operations"
        );
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
