package org.openjproxy.jdbc;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openjproxy.testcontainers.OjpContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.ServerSocket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end test for OJP server graceful shutdown with two OJP servers.
 *
 * <p>Scenario: a long transaction is open on server A while a background thread sends steady
 * auto-commit traffic. Server A receives SIGTERM. The test checks that:</p>
 * <ul>
 *   <li>the open transaction on A can still run statements and commit while A drains,</li>
 *   <li>new traffic moves to server B without errors,</li>
 *   <li>after A is restarted (same host port), the driver's health check brings it back and
 *       traffic is served by it again.</li>
 * </ul>
 *
 * <p>This test needs Docker and an OJP image that contains the graceful shutdown feature
 * (build it locally with {@code ojp-server/docker-build.sh}). Run with:</p>
 * <pre>
 * mvn test -pl ojp-jdbc-driver -Dtest=GracefulShutdownMultinodeIntegrationTest \
 *     -DenableGracefulShutdownTests=true -Dojp.image.version=&lt;local image tag&gt;
 * </pre>
 */
class GracefulShutdownMultinodeIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(GracefulShutdownMultinodeIntegrationTest.class);

    private static final int OJP_PORT = 1059;
    private static final String DRAIN_TIMEOUT_SECONDS = "20";
    private static final String SERVED_BY_SQL = "SELECT FILE_READ('/etc/hostname', NULL)";
    private static final long RECOVERY_TIMEOUT_MS = 90_000L;

    private final List<OjpContainer> containers = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (OjpContainer container : containers) {
            try {
                container.stop();
            } catch (Exception e) {
                log.warn("Failed to stop container: {}", e.getMessage());
            }
        }
    }

    @Test
    void shouldCommitOpenTransactionAndMoveTrafficWhenServerReceivesSigterm() throws Exception {
        assumeTrue(Boolean.parseBoolean(System.getProperty("enableGracefulShutdownTests", "false")),
                "Graceful shutdown tests disabled (use -DenableGracefulShutdownTests=true)");
        String imageVersion = System.getProperty("ojp.image.version");
        assumeTrue(imageVersion != null && !imageVersion.isBlank(),
                "Set -Dojp.image.version to a locally built OJP image tag");

        int portA = freePort();
        int portB = freePort();
        OjpContainer serverA = startServer(imageVersion, portA);
        OjpContainer serverB = startServer(imageVersion, portB);
        String hostnameA = hostname(serverA);
        String hostnameB = hostname(serverB);

        String url = "jdbc:ojp[localhost:" + portA + ",localhost:" + portB + "]_h2:mem:gracefulshutdown;DB_CLOSE_DELAY=-1";

        // Open one long transaction on each server. The driver balances new sessions by
        // session count, so keeping one open session on each server keeps the counts equal and
        // lets the steady traffic below be spread over both servers (round-robin).
        Connection longTx = openTransaction(url);
        String txServer = servedByInTransaction(longTx);
        try (Statement st = longTx.createStatement()) {
            st.executeUpdate("INSERT INTO gs_test (id, val) VALUES (1, 'before-sigterm')");
        }
        Connection otherTx = openTransaction(url);
        assertFalse(txServer.equals(servedByInTransaction(otherTx)),
                "The second session should be placed on the other server");
        OjpContainer drainingServer = txServer.equals(hostnameA) ? serverA : serverB;
        String drainingHostname = txServer;
        String otherHostname = txServer.equals(hostnameA) ? hostnameB : hostnameA;
        int drainingPort = txServer.equals(hostnameA) ? portA : portB;
        log.info("Long transaction bound to {}, other server is {}", drainingHostname, otherHostname);

        TrafficGenerator traffic = new TrafficGenerator(url);
        Thread trafficThread = new Thread(traffic, "gs-traffic");
        trafficThread.start();
        Thread.sleep(2_000L);
        assertTrue(traffic.servedBy().contains(drainingHostname) && traffic.servedBy().contains(otherHostname),
                "Traffic should be flowing before SIGTERM, served by: " + traffic.servedBy()
                        + " (servers " + hostnameA + ", " + hostnameB + "), failures: " + traffic.failures());

        // SIGTERM the server holding the open transaction.
        DockerClient docker = DockerClientFactory.instance().client();
        docker.killContainerCmd(drainingServer.getContainerId()).withSignal("SIGTERM").exec();
        Thread.sleep(2_000L);
        int servedBeforeCheck = traffic.servedBy().size();

        // The open transaction must still work and commit on the draining server.
        try (Statement st = longTx.createStatement()) {
            st.executeUpdate("INSERT INTO gs_test (id, val) VALUES (2, 'after-sigterm')");
            assertEquals(drainingHostname, servedBy(st), "Open transaction must stay on its server while draining");
        }
        longTx.commit();
        try (Statement st = longTx.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM gs_test WHERE id IN (1, 2)")) {
            assertTrue(rs.next());
            assertEquals(2, rs.getInt(1), "Both rows of the committed transaction must be visible");
        }
        longTx.close();
        otherTx.commit();
        otherTx.close();

        // Once the last session is closed the server finishes draining and exits.
        waitUntilStopped(docker, drainingServer.getContainerId(), 60_000L);

        List<String> afterSigterm = traffic.servedBy().subList(servedBeforeCheck, traffic.servedBy().size());
        assertFalse(afterSigterm.isEmpty(), "Traffic should continue during the drain");
        assertFalse(afterSigterm.contains(drainingHostname),
                "No new work should be routed to the draining server");
        assertTrue(traffic.failures().isEmpty(), "Traffic must not fail during the drain: " + traffic.failures());

        // Restart the server on the same host port and wait until the driver uses it again.
        OjpContainer restarted = startServer(imageVersion, drainingPort);
        String restartedHostname = hostname(restarted);
        int servedBeforeRestart = traffic.servedBy().size();
        long deadline = System.currentTimeMillis() + RECOVERY_TIMEOUT_MS;
        boolean recovered = false;
        while (System.currentTimeMillis() < deadline && !recovered) {
            Thread.sleep(1_000L);
            List<String> sinceRestart = traffic.servedBy().subList(servedBeforeRestart, traffic.servedBy().size());
            recovered = sinceRestart.contains(restartedHostname);
        }

        traffic.stop();
        trafficThread.join(10_000L);

        assertTrue(recovered, "Restarted server should be brought back by the driver health check");
        log.info("Traffic summary: {} requests, {} failures", traffic.servedBy().size(), traffic.failures().size());
    }

    private OjpContainer startServer(String imageVersion, int hostPort) {
        DockerImageName image = DockerImageName.parse("rrobetti/ojp:" + imageVersion)
                .asCompatibleSubstituteFor("rrobetti/ojp:0.4.2-beta");
        OjpContainer container = new OjpContainer(image)
                .withEnv("OJP_SERVER_GRACEFULSHUTDOWN_DRAINTIMEOUTSECONDS", DRAIN_TIMEOUT_SECONDS)
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
                        new PortBinding(Ports.Binding.bindPort(hostPort), new ExposedPort(OJP_PORT))));
        container.start();
        containers.add(container);
        return container;
    }

    private static Connection openTransaction(String url) throws SQLException {
        Connection conn = DriverManager.getConnection(url, "sa", "");
        conn.setAutoCommit(false);
        try (Statement st = conn.createStatement()) {
            // Each server has its own in-memory H2, so create the table on the server the session is bound to.
            st.execute("CREATE TABLE IF NOT EXISTS gs_test (id INT PRIMARY KEY, val VARCHAR(50))");
        }
        return conn;
    }

    private static String servedByInTransaction(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            return servedBy(st);
        }
    }

    private static String hostname(OjpContainer container) {
        return container.getContainerInfo().getConfig().getHostName();
    }

    private static String servedBy(Statement st) throws SQLException {
        try (ResultSet rs = st.executeQuery(SERVED_BY_SQL)) {
            assertTrue(rs.next());
            return rs.getString(1).trim();
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void waitUntilStopped(DockerClient docker, String containerId, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Boolean running = docker.inspectContainerCmd(containerId).exec().getState().getRunning();
            if (running == null || !running) {
                return;
            }
            Thread.sleep(500L);
        }
        fail("Server did not stop within " + timeoutMs + "ms after SIGTERM");
    }

    /**
     * Sends auto-commit queries in a loop. Each request starts without a session, so it is
     * routed independently and may land on any healthy server.
     */
    private static final class TrafficGenerator implements Runnable {
        private final String url;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final List<String> servedBy = Collections.synchronizedList(new ArrayList<>());
        private final List<String> failures = Collections.synchronizedList(new ArrayList<>());

        TrafficGenerator(String url) {
            this.url = url;
        }

        @Override
        public void run() {
            while (running.get()) {
                // A new connection per request: queries may bind a session to their connection,
                // so reusing one connection would pin all traffic to a single server.
                try (Connection conn = DriverManager.getConnection(url, "sa", "");
                     Statement st = conn.createStatement()) {
                    servedBy.add(GracefulShutdownMultinodeIntegrationTest.servedBy(st));
                } catch (SQLException e) {
                    failures.add(e.getMessage());
                }
                try {
                    Thread.sleep(50L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        List<String> servedBy() {
            synchronized (servedBy) {
                return new ArrayList<>(servedBy);
            }
        }

        List<String> failures() {
            synchronized (failures) {
                return new ArrayList<>(failures);
            }
        }

        void stop() {
            running.set(false);
        }
    }
}
