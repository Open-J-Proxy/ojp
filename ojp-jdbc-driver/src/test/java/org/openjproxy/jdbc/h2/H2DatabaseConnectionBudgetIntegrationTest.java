package org.openjproxy.jdbc.h2;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class H2DatabaseConnectionBudgetIntegrationTest {
    private static final int GROUP_LIMIT = 10;
    private static final String OJP_URL = "jdbc:ojp[localhost:1059]_h2:mem:ojp_budget_test;DB_CLOSE_DELAY=-1";
    private static final long WAIT_TIMEOUT_MILLIS = 10_000L;
    private static final long POLL_INTERVAL_MILLIS = 100L;

    @Test
    void shouldOpenExactlyTheConfiguredGroupLimitThroughOjpJdbcDriver() throws SQLException, InterruptedException {
        assumeTrue(Boolean.parseBoolean(System.getProperty("enableH2Tests", "false")),
                "H2 integration tests are disabled");

        try (Connection application = DriverManager.getConnection(OJP_URL, poolProperties("application"));
             Connection reporting = DriverManager.getConnection(OJP_URL, poolProperties("reporting"))) {
            assertEquals(GROUP_LIMIT, awaitBackendSessionCount(application),
                    "H2 should report exactly the group limit of backend sessions opened through OJP");
        }
    }

    private Properties poolProperties(String dataSourceName) {
        Properties properties = new Properties();
        properties.setProperty("user", "sa");
        properties.setProperty("password", "");
        properties.setProperty("ojp.datasource.name", dataSourceName);
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
