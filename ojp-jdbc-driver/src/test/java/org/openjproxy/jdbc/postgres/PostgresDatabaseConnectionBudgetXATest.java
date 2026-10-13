package org.openjproxy.jdbc.postgres;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.openjproxy.jdbc.xa.OjpXADataSource;

import javax.sql.XAConnection;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PostgresDatabaseConnectionBudgetXATest {
    private static final int GROUP_LIMIT = 10;
    private static final int REQUESTED_POOL_SIZE = 8;
    private static final long WAIT_TIMEOUT_MILLIS = 15_000L;
    private static final long STABLE_COUNT_MILLIS = 1_000L;
    private static final long POLL_INTERVAL_MILLIS = 100L;

    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_xa_database_connection_budget.csv")
    void shouldCapTwoOjpXaPoolsAtTheConfiguredGroupLimit(
            String driverClass, String url, String user, String password, String backendUrl)
            throws SQLException, InterruptedException, ClassNotFoundException {
        assumeTrue(Boolean.parseBoolean(System.getProperty("enablePostgresTests", "false")),
                "PostgreSQL integration tests are disabled");
        Class.forName(driverClass);

        XAConnection application = null;
        XAConnection reporting = null;
        try {
            application = createXAConnection(url, "application", user, password);
            reporting = createXAConnection(url, "reporting", user, password);

            Properties observerProperties = new Properties();
            observerProperties.setProperty("user", user);
            observerProperties.setProperty("password", password);
            try (Connection observer = new org.postgresql.Driver().connect(backendUrl, observerProperties)) {
                assertEquals(GROUP_LIMIT, awaitBackendSessionCount(observer),
                        "Two XA pools requesting 16 backend sessions must stabilize at the group limit of 10");
            }
        } finally {
            try {
                if (reporting != null) {
                    reporting.close();
                }
            } finally {
                if (application != null) {
                    application.close();
                }
            }
        }
    }

    private XAConnection createXAConnection(String url, String dataSourceName, String user, String password)
            throws SQLException {
        OjpXADataSource xaDataSource = new OjpXADataSource();
        xaDataSource.setUrl(url.replace("(pool)", "(" + dataSourceName + ")"));
        xaDataSource.setUser(user);
        xaDataSource.setPassword(password);
        xaDataSource.setProperty("ojp.xa.connection.pool.maximumPoolSize", String.valueOf(REQUESTED_POOL_SIZE));
        xaDataSource.setProperty("ojp.xa.connection.pool.minimumIdle", String.valueOf(REQUESTED_POOL_SIZE));
        return xaDataSource.getXAConnection(user, password);
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
