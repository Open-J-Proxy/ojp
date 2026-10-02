package org.openjproxy.jdbc.h2;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class H2DatabaseConnectionBudgetIntegrationTest {
    private static final int GROUP_LIMIT = 10;
    private static final long WAIT_TIMEOUT_MILLIS = 10_000L;
    private static final long POLL_INTERVAL_MILLIS = 100L;

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_database_connection_budget.csv")
    void shouldOpenExactlyTheConfiguredGroupLimitThroughOjpJdbcDriver(
            String driverClass, String url, String user, String password) throws SQLException, InterruptedException,
            ClassNotFoundException {
        assumeTrue(Boolean.parseBoolean(System.getProperty("enableH2Tests", "false")),
                "H2 integration tests are disabled");
        Class.forName(driverClass);

        try (Connection application = DriverManager.getConnection(url, poolProperties("application", user, password));
             Connection reporting = DriverManager.getConnection(url, poolProperties("reporting", user, password))) {
            assertEquals(GROUP_LIMIT, awaitBackendSessionCount(application),
                    "H2 should report exactly the group limit of backend sessions opened through OJP");
        }
    }

    private Properties poolProperties(String dataSourceName, String user, String password) {
        Properties properties = new Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", password == null ? "" : password);
        properties.setProperty("ojp.datasource.name", dataSourceName + "-" + UUID.randomUUID());
        properties.setProperty("ojp.connection.pool.maximumPoolSize", "8");
        properties.setProperty("ojp.connection.pool.minimumIdle", "8");
        return properties;
    }

    private int awaitBackendSessionCount(Connection connection) throws SQLException, InterruptedException {
        long deadline = System.nanoTime() + WAIT_TIMEOUT_MILLIS * 1_000_000L;
        int sessionCount = 0;
        while (System.nanoTime() < deadline && sessionCount != GROUP_LIMIT) {
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS "
                                 + "WHERE USER_NAME = 'SA'")) {
                resultSet.next();
                sessionCount = resultSet.getInt(1);
            }
            if (sessionCount != GROUP_LIMIT) {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            }
        }
        return sessionCount;
    }
}
