package org.openjproxy.grpc.server.pool;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DatabaseConnectionBudgetIntegrationTest {
    private static final int GROUP_LIMIT = 10;
    private static final long WAIT_TIMEOUT_MILLIS = 10_000L;
    private static final long POLL_INTERVAL_MILLIS = 100L;

    @Test
    void shouldOpenExactlyTheConfiguredGroupLimitAcrossWeightedPools() throws Exception {
        String databaseName = "budget_" + UUID.randomUUID().toString().replace("-", "");
        String jdbcUrl = "jdbc:h2:mem:" + databaseName + ";DB_CLOSE_DELAY=-1";
        createUsers(jdbcUrl);

        DatabaseConnectionBudgetManager manager = new DatabaseConnectionBudgetManager(budgetProperties(jdbcUrl));
        DatabaseConnectionBudgetManager.Registration application = manager.registerPool(
                "application", jdbcUrl, "app_rw", 8, 8, true);

        try (HikariDataSource applicationPool = createPool(
                jdbcUrl, "app_rw", "app_secret", application.getMaximumPoolSize())) {
            manager.attachPool(application, (maximum, minimum) -> resizePool(applicationPool, maximum, minimum),
                    applicationPool.getMaximumPoolSize(), applicationPool.getMinimumIdle());

            DatabaseConnectionBudgetManager.Registration reporting = manager.registerPool(
                    "reporting", jdbcUrl, "reporting_ro", 8, 8, true);
            assertEquals(7, application.getMaximumPoolSize());
            assertEquals(3, reporting.getMaximumPoolSize());

            try (HikariDataSource reportingPool = createPool(
                    jdbcUrl, "reporting_ro", "reporting_secret", reporting.getMaximumPoolSize())) {
                manager.attachPool(reporting,
                        (maximum, minimum) -> resizePool(reportingPool, maximum, minimum),
                        reportingPool.getMaximumPoolSize(), reportingPool.getMinimumIdle());

                assertEquals(GROUP_LIMIT, applicationPool.getMaximumPoolSize() + reportingPool.getMaximumPoolSize());
                assertEquals(GROUP_LIMIT, applicationPool.getMinimumIdle() + reportingPool.getMinimumIdle());
                assertEquals(GROUP_LIMIT, awaitBackendSessionCount(applicationPool),
                        "H2 should report exactly the group limit as open backend sessions");
            }
        }
    }

    private void createUsers(String jdbcUrl) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE USER app_rw PASSWORD 'app_secret' ADMIN");
            statement.execute("CREATE USER reporting_ro PASSWORD 'reporting_secret' ADMIN");
        }
    }

    private Properties budgetProperties(String jdbcUrl) {
        Properties properties = new Properties();
        String prefix = "ojp.server.databaseBudgets.orders.";
        properties.setProperty(prefix + "match.jdbcUrlPattern", jdbcUrl + "*");
        properties.setProperty(prefix + "maxTotalConnections", String.valueOf(GROUP_LIMIT));
        properties.setProperty(prefix + "priorities.username.app_rw.weight", "2");
        properties.setProperty(prefix + "priorities.username.reporting_ro.weight", "1");
        return properties;
    }

    private HikariDataSource createPool(String jdbcUrl, String username, String password, int poolSize) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(poolSize);
        config.setMinimumIdle(poolSize);
        config.setPoolName("budget-" + username);
        return new HikariDataSource(config);
    }

    private void resizePool(HikariDataSource pool, int maximum, int minimum) {
        if (maximum < pool.getMaximumPoolSize()) {
            pool.setMinimumIdle(minimum);
            pool.setMaximumPoolSize(maximum);
        } else {
            pool.setMaximumPoolSize(maximum);
            pool.setMinimumIdle(minimum);
        }
    }

    private int awaitBackendSessionCount(HikariDataSource pool) throws SQLException, InterruptedException {
        long deadline = System.nanoTime() + WAIT_TIMEOUT_MILLIS * 1_000_000L;
        int sessionCount = 0;
        while (System.nanoTime() < deadline && sessionCount != GROUP_LIMIT) {
            try (Connection connection = pool.getConnection();
                 Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS "
                                 + "WHERE USER_NAME IN ('APP_RW', 'REPORTING_RO')")) {
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
