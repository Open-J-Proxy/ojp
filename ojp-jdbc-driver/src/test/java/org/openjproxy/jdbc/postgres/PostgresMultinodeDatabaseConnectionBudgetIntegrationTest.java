package org.openjproxy.jdbc.postgres;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PostgresMultinodeDatabaseConnectionBudgetIntegrationTest {
    private static final int GROUP_LIMIT = 24;
    private static final int REQUESTED_POOL_SIZE = 24;
    private static final long WAIT_TIMEOUT_MILLIS = 30_000L;
    private static final long STABLE_COUNT_MILLIS = 1_000L;
    private static final long POLL_INTERVAL_MILLIS = 100L;

    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_multinode_database_connection_budget.csv")
    void shouldKeepBackendConnectionsAtClusterBudgetAcrossHealthyServers(
            String driverClass, String url, String user, String password, String backendUrl)
            throws SQLException, InterruptedException, ClassNotFoundException {
        assumeTrue(Boolean.parseBoolean(System.getProperty("enablePostgresTests", "false")),
                "PostgreSQL integration tests are disabled");
        assumeTrue(Boolean.parseBoolean(System.getProperty("enablePostgresMultinodeBudgetTests", "false")),
                "PostgreSQL multinode budget integration tests are disabled");
        Class.forName(driverClass);

        try (Connection application = DriverManager.getConnection(url, poolProperties("budget-application", user, password));
             Connection reporting = DriverManager.getConnection(url, poolProperties("budget-reporting", user, password));
             Connection observer = new org.postgresql.Driver().connect(backendUrl, observerProperties(user, password))) {
            executeSimpleQuery(application);
            executeSimpleQuery(reporting);
            assertEquals(GROUP_LIMIT, awaitBackendSessionCount(observer),
                    "Both pools must share the 24-connection budget across all healthy OJP servers");
        }
    }

    private Properties poolProperties(String dataSourceName, String user, String password) {
        Properties properties = new Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", password == null ? "" : password);
        properties.setProperty("ojp.datasource.name", dataSourceName);
        properties.setProperty("ojp.connection.pool.maximumPoolSize", String.valueOf(REQUESTED_POOL_SIZE));
        properties.setProperty("ojp.connection.pool.minimumIdle", String.valueOf(REQUESTED_POOL_SIZE));
        return properties;
    }

    private Properties observerProperties(String user, String password) {
        Properties properties = new Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", password == null ? "" : password);
        return properties;
    }

    private void executeSimpleQuery(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT 1")) {
            resultSet.next();
        }
    }

    private int awaitBackendSessionCount(Connection observer) throws SQLException, InterruptedException {
        long deadline = System.nanoTime() + WAIT_TIMEOUT_MILLIS * 1_000_000L;
        long stableSince = 0;
        int sessionCount = 0;
        while (System.nanoTime() < deadline) {
            try (Statement statement = observer.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "SELECT COUNT(*) FROM pg_stat_activity "
                                 + "WHERE datname = current_database() AND usename = current_user "
                                 + "AND pid <> pg_backend_pid()")) {
                resultSet.next();
                sessionCount = resultSet.getInt(1);
            }
            if (sessionCount == GROUP_LIMIT) {
                if (stableSince == 0) {
                    stableSince = System.nanoTime();
                } else if (System.nanoTime() - stableSince >= STABLE_COUNT_MILLIS * 1_000_000L) {
                    return sessionCount;
                }
            } else {
                stableSince = 0;
            }
            Thread.sleep(POLL_INTERVAL_MILLIS);
        }
        return sessionCount;
    }
}
