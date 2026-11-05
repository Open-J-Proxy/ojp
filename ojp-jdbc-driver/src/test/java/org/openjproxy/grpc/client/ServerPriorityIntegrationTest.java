package org.openjproxy.grpc.client;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.openjproxy.jdbc.UrlParser;
import org.openjproxy.jdbc.xa.OjpXADataSource;
import org.openjproxy.testcontainers.OjpContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import javax.sql.XAConnection;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.io.IOException;
import java.net.ServerSocket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "enableServerPriorityTests", matches = "true")
class ServerPriorityIntegrationTest {
    private static final List<OjpContainer> SERVERS = new ArrayList<>();
    private static final Set<Integer> STOPPED_SERVERS = new HashSet<>();
    private final Map<String, String> originalProperties = new HashMap<>();
    private final Set<MultinodeConnectionManager> managers = new HashSet<>();
    private String datasource;

    @BeforeAll
    static void startServers() throws IOException {
        String image = System.getenv("OJP_IMAGE");
        for (int i = 0; i < 4; i++) {
            OjpContainer server = image == null || image.isBlank()
                    ? new OjpContainer()
                    : new OjpContainer(DockerImageName.parse(image)
                            .asCompatibleSubstituteFor("rrobetti/ojp:0.4.2-beta"));
            server.withEnv("JAVA_TOOL_OPTIONS", "-Xmx256m");
            try (ServerSocket socket = new ServerSocket(0)) {
                int port = socket.getLocalPort();
                // Docker otherwise assigns a different random host port after stop/start.
                server.withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
                        new PortBinding(Ports.Binding.bindPort(port), new ExposedPort(1059))));
            }
            SERVERS.add(server);
            server.start();
        }
    }

    @BeforeEach
    void configureClient() {
        datasource = "priority_" + UUID.randomUUID().toString().replace("-", "");
        setProperty("ojp.health.check.interval", "100ms");
        setProperty("ojp.health.check.threshold", "100ms");
        setProperty("ojp.health.check.timeout", "500ms");
        setProperty("ojp.multinode.retryAttempts", "4");
        setProperty("ojp.multinode.retryDelayMs", "100");
        setProperty("ojp.redistribution.enabled", "true");
    }

    @AfterEach
    void restoreClientAndServers() {
        try {
            managers.forEach(MultinodeConnectionManager::shutdown);
            for (int index : new ArrayList<>(STOPPED_SERVERS)) {
                restart(index);
            }
        } finally {
            originalProperties.forEach((key, value) -> {
                if (value == null) {
                    System.clearProperty(key);
                } else {
                    System.setProperty(key, value);
                }
            });
        }
    }

    @AfterAll
    static void stopServers() {
        SERVERS.forEach(OjpContainer::stop);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/server_priority_connection.csv")
    void shouldPreferLocalServerAndKeepDifferentPoliciesIsolated(
            String driverClass, String jdbcUrl, String user, String password, boolean loadAware) throws Exception {
        prepare(driverClass, loadAware);
        String url = url(jdbcUrl, new int[]{1, 2, 2, 3});
        List<Connection> busy = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) {
                Connection connection = open(url, user, password);
                busy.add(connection);
                assertEquals(address(0), queryServer(connection, manager(url)));
            }
            String reversed = url(jdbcUrl, new int[]{3, 2, 2, 1});
            assertEquals(address(3), openAndQuery(reversed, user, password));
            assertEquals(address(0), openAndQuery(url, user, password));
            try (Statement statement = busy.get(0).createStatement()) {
                assertThrows(SQLException.class, () -> statement.executeQuery("SELECT * FROM missing_priority_table"));
            }
            assertEquals(address(0), queryServer(busy.get(0), manager(url)),
                    "Database errors must not demote a healthy preferred server");
        } finally {
            for (Connection connection : busy) {
                connection.close();
            }
        }
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/server_priority_connection.csv")
    void shouldExhaustEveryTierBeforeUsingTheNext(
            String driverClass, String jdbcUrl, String user, String password, boolean loadAware) throws Exception {
        prepare(driverClass, loadAware);
        String url = url(jdbcUrl, new int[]{1, 2, 2, 3});
        assertEquals(address(0), openAndQuery(url, user, password));
        stop(0);
        Set<String> selected = new HashSet<>();
        for (int i = 0; i < 8; i++) {
            selected.add(openAndQuery(url, user, password));
        }
        assertEquals(Set.of(address(1), address(2)), selected);
        stop(1);
        assertEquals(address(2), openAndQuery(url, user, password));
        stop(2);
        assertEquals(address(3), openAndQuery(url, user, password));
        stop(3);
        assertThrows(SQLException.class, () -> openAndQuery(url, user, password));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/server_priority_connection.csv")
    void shouldUseFallbackOnColdStartAndInitializeRecoveredPreferredPool(
            String driverClass, String jdbcUrl, String user, String password, boolean loadAware) throws Exception {
        prepare(driverClass, loadAware);
        String url = url(jdbcUrl, new int[]{1, 2, 2, 3});
        stop(0);
        assertTrue(Set.of(address(1), address(2)).contains(openAndQuery(url, user, password)));
        restart(0);
        await(() -> manager(url).getServerEndpoints().get(0).isHealthy());
        assertEquals(address(0), openAndQuery(url, user, password));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/server_priority_connection.csv")
    void shouldReturnNewWorkToRecoveredLocalServerWithoutMovingTransactions(
            String driverClass, String jdbcUrl, String user, String password, boolean loadAware) throws Exception {
        prepare(driverClass, loadAware);
        String url = url(jdbcUrl, new int[]{1, 2, 2, 3});
        openAndQuery(url, user, password);
        stop(0);
        try (Connection transaction = open(url, user, password)) {
            transaction.setAutoCommit(false);
            String bound = queryServer(transaction, manager(url));
            assertTrue(Set.of(address(1), address(2)).contains(bound));
            restart(0);
            await(() -> manager(url).getServerEndpoints().get(0).isHealthy());
            assertEquals(address(0), openAndQuery(url, user, password));
            assertEquals(bound, queryServer(transaction, manager(url)));
            assertFalse(((org.openjproxy.jdbc.Connection) transaction).isForceInvalid());
            transaction.commit();
        }
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/server_priority_connection.csv")
    void shouldRespectPrioritiesForXaAndNotRebalanceToRecoveredLowerTier(
            String driverClass, String jdbcUrl, String user, String password, boolean loadAware) throws Exception {
        prepare(driverClass, loadAware);
        String url = url(jdbcUrl, new int[]{1, 2, 2, 3});
        OjpXADataSource datasource = new OjpXADataSource();
        datasource.setUrl(url);
        datasource.setUser(user);
        datasource.setPassword(password == null ? "" : password);
        XAConnection xa = datasource.getXAConnection();
        try (Connection connection = xa.getConnection()) {
            XAResource resource = xa.getXAResource();
            Xid xid = xid();
            resource.start(xid, XAResource.TMNOFLAGS);
            assertEquals(address(0), queryServer(connection, manager(url)));
            stop(3);
            await(() -> !manager(url).getServerEndpoints().get(3).isHealthy());
            restart(3);
            await(() -> manager(url).getServerEndpoints().get(3).isHealthy());
            assertFalse(((org.openjproxy.jdbc.Connection) connection).isForceInvalid(),
                    "Lower-tier recovery must not invalidate preferred-tier connections");
            assertEquals(address(0), queryServer(connection, manager(url)));
            resource.end(xid, XAResource.TMSUCCESS);
            resource.commit(xid, true);
        } finally {
            xa.close();
        }
        stop(0);
        assertXaTier(datasource, url, 2);
        stop(1);
        assertXaTier(datasource, url, 2);
        stop(2);
        assertXaTier(datasource, url, 3);
        stop(3);
        XAConnection unavailable = datasource.getXAConnection();
        try {
            assertThrows(SQLException.class, unavailable::getConnection);
        } finally {
            unavailable.close();
        }
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/server_priority_connection.csv")
    void shouldKeepUnannotatedAndEqualPriorityUrlsBalanced(
            String driverClass, String jdbcUrl, String user, String password, boolean loadAware) throws Exception {
        prepare(driverClass, loadAware);
        for (int[] priorities : new int[][]{null, {7, 7, 7, 7}}) {
            String url = url(jdbcUrl, priorities);
            Set<String> selected = new HashSet<>();
            for (int i = 0; i < 8; i++) {
                selected.add(openAndQuery(url, user, password));
            }
            assertEquals(SERVERS.stream().map(OjpContainer::getOjpConnectionString).collect(Collectors.toSet()), selected);
        }
    }

    private void prepare(String driverClass, boolean loadAware) throws ClassNotFoundException {
        Class.forName(driverClass);
        setProperty("ojp.loadaware.selection.enabled", Boolean.toString(loadAware));
    }

    private void assertXaTier(OjpXADataSource datasource, String url, int priority) throws SQLException {
        XAConnection xa = datasource.getXAConnection();
        try (Connection connection = xa.getConnection()) {
            assertEquals(priority, manager(url).affinityServer(
                    ((org.openjproxy.jdbc.Connection) connection).getSession().getSessionUUID()).getPriority());
        } finally {
            xa.close();
        }
    }

    private String url(String jdbcUrl, int[] priorities) {
        return "jdbc:ojp[" + IntStream.range(0, SERVERS.size())
                .mapToObj(i -> address(i) + "(" + datasource + ")"
                        + (priorities == null ? "" : "{" + priorities[i] + "}"))
                .collect(Collectors.joining(",")) + "]_" + jdbcUrl;
    }

    private MultinodeConnectionManager manager(String url) {
        UrlParser.UrlParseResult parsed = UrlParser.parseUrlWithDataSource(url);
        MultinodeConnectionManager manager = ((MultinodeStatementService) MultinodeUrlParser
                .getOrCreateStatementService(parsed.cleanUrl, parsed.dataSourceNames).getService()).getConnectionManager();
        managers.add(manager);
        return manager;
    }

    private Connection open(String url, String user, String password) throws SQLException {
        manager(url);
        Properties properties = new Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", password == null ? "" : password);
        properties.setProperty("ojp.connection.pool.maximumPoolSize", "64");
        properties.setProperty("ojp.connection.pool.minimumIdle", "0");
        return DriverManager.getConnection(url, properties);
    }

    private String openAndQuery(String url, String user, String password) throws SQLException {
        try (Connection connection = open(url, user, password)) {
            return queryServer(connection, manager(url));
        }
    }

    private String queryServer(Connection connection, MultinodeConnectionManager manager) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT 1")) {
            assertTrue(resultSet.next());
            assertEquals(1, resultSet.getInt(1));
            return manager.affinityServer(
                    ((org.openjproxy.jdbc.Connection) connection).getSession().getSessionUUID()).getAddress();
        }
    }

    private static String address(int index) {
        return SERVERS.get(index).getOjpConnectionString();
    }

    private static void stop(int index) {
        OjpContainer server = SERVERS.get(index);
        server.getDockerClient().stopContainerCmd(server.getContainerId()).withTimeout(1).exec();
        STOPPED_SERVERS.add(index);
    }

    private static void restart(int index) {
        OjpContainer server = SERVERS.get(index);
        if (!server.isRunning()) {
            server.getDockerClient().startContainerCmd(server.getContainerId()).exec();
        }
        Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(60)).waitUntilReady(server);
        STOPPED_SERVERS.remove(index);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        assertTrue(condition.getAsBoolean(), "Server health did not converge within 30 seconds");
    }

    private void setProperty(String key, String value) {
        if (!originalProperties.containsKey(key)) {
            originalProperties.put(key, System.getProperty(key));
        }
        System.setProperty(key, value);
    }

    private static Xid xid() {
        return new Xid() {
            public int getFormatId() {
                return 1;
            }

            public byte[] getGlobalTransactionId() {
                return new byte[]{1, 2, 3};
            }

            public byte[] getBranchQualifier() {
                return new byte[]{4, 5, 6};
            }
        };
    }
}
