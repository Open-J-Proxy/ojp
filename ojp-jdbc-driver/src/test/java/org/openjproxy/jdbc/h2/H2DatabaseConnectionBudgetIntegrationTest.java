package org.openjproxy.jdbc.h2;

import io.grpc.Server;
import io.grpc.netty.NettyServerBuilder;
import org.h2.Driver;
import org.junit.jupiter.api.Test;
import org.openjproxy.grpc.server.CircuitBreakerRegistry;
import org.openjproxy.grpc.server.ServerConfiguration;
import org.openjproxy.grpc.server.SessionManagerImpl;
import org.openjproxy.grpc.server.StatementServiceImpl;
import org.openjproxy.grpc.server.pool.DatabaseConnectionBudgetManager;
import org.openjproxy.grpc.server.cache.CacheConfiguration;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

class H2DatabaseConnectionBudgetIntegrationTest {
    private static final int GROUP_LIMIT = 10;
    private static final long WAIT_TIMEOUT_MILLIS = 10_000L;
    private static final long POLL_INTERVAL_MILLIS = 100L;

    @Test
    void shouldOpenExactlyTheConfiguredGroupLimitThroughOjpJdbcDriver() throws Exception {
        String databaseName = "budget_" + UUID.randomUUID().toString().replace("-", "");
        String jdbcUrl = "jdbc:h2:mem:" + databaseName + ";DB_CLOSE_DELAY=-1";
        Properties originalProperties = setBudgetSystemProperties(jdbcUrl);
        DatabaseConnectionBudgetManager previousManager = replaceBudgetManager();
        Server server = null;
        StatementServiceImpl statementService = null;
        try {
            createUsers(jdbcUrl);
            ServerConfiguration configuration = new ServerConfiguration();
            Map<String, CacheConfiguration> cacheConfigurations = new ConcurrentHashMap<>();
            statementService = new StatementServiceImpl(new SessionManagerImpl(cacheConfigurations),
                    new CircuitBreakerRegistry(configuration.getCircuitBreakerTimeout(),
                            configuration.getCircuitBreakerThreshold()),
                    configuration, cacheConfigurations);
            server = NettyServerBuilder.forPort(0).addService(statementService).build().start();
            DriverManager.registerDriver(new org.openjproxy.jdbc.Driver());

            String ojpUrl = "jdbc:ojp[localhost:" + server.getPort() + "]_"
                    + jdbcUrl.substring("jdbc:".length());
            try (Connection application = DriverManager.getConnection(
                    ojpUrl, poolProperties("app_rw", "app_secret", "application"));
                 Connection reporting = DriverManager.getConnection(
                         ojpUrl, poolProperties("reporting_ro", "reporting_secret", "reporting"))) {
                assertEquals(GROUP_LIMIT, awaitBackendSessionCount(jdbcUrl),
                        "H2 should report exactly the group limit of backend sessions opened through OJP");
            }
        } finally {
            if (server != null) {
                server.shutdownNow();
                server.awaitTermination();
            }
            if (statementService != null) {
                statementService.shutdown();
            }
            restoreBudgetManager(previousManager);
            restoreBudgetSystemProperties(originalProperties);
        }
    }

    private Properties setBudgetSystemProperties(String jdbcUrl) {
        Properties originals = new Properties();
        String prefix = "ojp.server.databaseBudgets.orders.";
        setSystemProperty(originals, prefix + "match.jdbcUrlPattern", jdbcUrl + "*");
        setSystemProperty(originals, prefix + "maxTotalConnections", String.valueOf(GROUP_LIMIT));
        setSystemProperty(originals, prefix + "priorities.username.app_rw.weight", "2");
        setSystemProperty(originals, prefix + "priorities.username.reporting_ro.weight", "1");
        return originals;
    }

    private void setSystemProperty(Properties originals, String key, String value) {
        String original = System.getProperty(key);
        if (original != null) {
            originals.setProperty(key, original);
        }
        System.setProperty(key, value);
    }

    private void restoreBudgetSystemProperties(Properties originals) {
        String prefix = "ojp.server.databaseBudgets.orders.";
        System.clearProperty(prefix + "match.jdbcUrlPattern");
        System.clearProperty(prefix + "maxTotalConnections");
        System.clearProperty(prefix + "priorities.username.app_rw.weight");
        System.clearProperty(prefix + "priorities.username.reporting_ro.weight");
        originals.forEach((key, value) -> System.setProperty((String) key, (String) value));
    }

    private DatabaseConnectionBudgetManager replaceBudgetManager() throws ReflectiveOperationException {
        Field instanceField = DatabaseConnectionBudgetManager.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        DatabaseConnectionBudgetManager previousManager = (DatabaseConnectionBudgetManager) instanceField.get(null);
        instanceField.set(null, null);
        return previousManager;
    }

    private void restoreBudgetManager(DatabaseConnectionBudgetManager previousManager)
            throws ReflectiveOperationException {
        Field instanceField = DatabaseConnectionBudgetManager.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        instanceField.set(null, previousManager);
    }

    private void createUsers(String jdbcUrl) throws SQLException {
        try (Connection connection = getDirectH2Connection(jdbcUrl);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE USER app_rw PASSWORD 'app_secret' ADMIN");
            statement.execute("CREATE USER reporting_ro PASSWORD 'reporting_secret' ADMIN");
        }
    }

    private Properties poolProperties(String username, String password, String dataSourceName) {
        Properties properties = new Properties();
        properties.setProperty("user", username);
        properties.setProperty("password", password);
        properties.setProperty("ojp.datasource.name", dataSourceName);
        properties.setProperty("ojp.connection.pool.maximumPoolSize", "8");
        properties.setProperty("ojp.connection.pool.minimumIdle", "8");
        return properties;
    }

    private int awaitBackendSessionCount(String jdbcUrl) throws SQLException, InterruptedException {
        long deadline = System.nanoTime() + WAIT_TIMEOUT_MILLIS * 1_000_000L;
        int sessionCount = 0;
        while (System.nanoTime() < deadline && sessionCount != GROUP_LIMIT) {
            try (Connection connection = getDirectH2Connection(jdbcUrl);
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

    private Connection getDirectH2Connection(String jdbcUrl) throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", "sa");
        properties.setProperty("password", "");
        return new Driver().connect(jdbcUrl, properties);
    }
}
