package org.openjproxy.grpc.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for load-aware server selection in MultinodeConnectionManager.
 * Verifies that the least-loaded server is selected when load-aware mode is enabled.
 */
class LoadAwareServerSelectionTest {

    private List<ServerEndpoint> endpoints;

    private HealthCheckConfig loadAwareConfig;
    private HealthCheckConfig roundRobinConfig;

    @BeforeEach
    void setUp() {
        endpoints = Arrays.asList(
                new ServerEndpoint("server1", 1059),
                new ServerEndpoint("server2", 1059),
                new ServerEndpoint("server3", 1059)
        );


        // Create config with load-aware selection enabled
        loadAwareConfig = HealthCheckConfig.createDefault();

        // Create config with load-aware selection disabled (round-robin)
        roundRobinConfig = createRoundRobinConfig();
    }

    /**
     * Helper method to create a configuration with round-robin selection.
     */
    private HealthCheckConfig createRoundRobinConfig() {
        // We need to use reflection or a test-friendly constructor to create this
        // For now, we'll use system properties
        System.setProperty("ojp.loadaware.selection.enabled", "false");
        HealthCheckConfig config = HealthCheckConfig.loadFromProperties(System.getProperties());
        System.clearProperty("ojp.loadaware.selection.enabled");
        return config;
    }

    @Test
    void testLoadAwareSelectionPicksLeastLoadedServer() throws SQLException {
        // Create manager with load-aware selection enabled
        MultinodeConnectionManager manager = new MultinodeConnectionManager(
                endpoints, 3, 1000, loadAwareConfig, null
        );

        // Get the manager's SessionTracker
        SessionTracker tracker = manager.getSessionTracker();

        // Simulate sessions tracked on different servers
        // Server1: 5 sessions, Server2: 2 sessions, Server3: 8 sessions
        simulateSessions(tracker, endpoints.get(0), 5);
        simulateSessions(tracker, endpoints.get(1), 2);
        simulateSessions(tracker, endpoints.get(2), 8);

        // Select a server - should pick server2 (least loaded with 2 sessions)
        ServerEndpoint selected = manager.affinityServer(null);

        assertNotNull(selected);
        assertEquals("server2:1059", selected.getAddress());
    }

    @Test
    void testLoadAwareSelectionWithEqualLoadUsesRoundRobin() throws SQLException {
        // Create manager with load-aware selection enabled
        MultinodeConnectionManager manager = new MultinodeConnectionManager(
                endpoints, 3, 1000, loadAwareConfig, null
        );

        SessionTracker tracker = manager.getSessionTracker();

        // Simulate equal load on all servers (3 sessions each)
        simulateSessions(tracker, endpoints.get(0), 3);
        simulateSessions(tracker, endpoints.get(1), 3);
        simulateSessions(tracker, endpoints.get(2), 3);

        // With equal load, selection should be fair (round-robin as tie-breaker)
        ServerEndpoint selected1 = manager.affinityServer(null);
        assertNotNull(selected1);

        // All servers have equal load, so any selection is valid
        assertTrue(endpoints.contains(selected1));
    }

    @Test
    void testLoadAwareSelectionWithNoTrackedConnections() throws SQLException {
        // Create manager with load-aware selection enabled
        MultinodeConnectionManager manager = new MultinodeConnectionManager(
                endpoints, 3, 1000, loadAwareConfig, null
        );

        // No sessions tracked - all servers have 0 sessions
        // Should select the first healthy server
        ServerEndpoint selected = manager.affinityServer(null);

        assertNotNull(selected);
        assertTrue(endpoints.contains(selected));
    }

    @Test
    void testLoadAwareSelectionRebalancesOverTime() throws SQLException {
        // Create manager with load-aware selection enabled
        MultinodeConnectionManager manager = new MultinodeConnectionManager(
                endpoints, 3, 1000, loadAwareConfig, null
        );

        SessionTracker tracker = manager.getSessionTracker();

        // Initially, server1 has more load
        simulateSessions(tracker, endpoints.get(0), 10);
        simulateSessions(tracker, endpoints.get(1), 2);
        simulateSessions(tracker, endpoints.get(2), 2);

        // Multiple selections should favor the less loaded servers
        Map<String, Integer> selectionCounts = new HashMap<>();
        for (int i = 0; i < 10; i++) {
            ServerEndpoint selected = manager.affinityServer(null);
            assertNotNull(selected);
            selectionCounts.merge(selected.getAddress(), 1, Integer::sum);

            // Simulate this new session being tracked
            simulateSession(tracker, selected);
        }

        // Server1 (initially 10 sessions) should be selected less frequently
        // than server2 and server3 (initially 2 sessions each)
        int server1Selections = selectionCounts.getOrDefault("server1:1059", 0);
        int server2Selections = selectionCounts.getOrDefault("server2:1059", 0);
        int server3Selections = selectionCounts.getOrDefault("server3:1059", 0);

        // Server2 and server3 should have more selections than server1
        assertTrue(server2Selections >= server1Selections,
                String.format("Server2 selections (%d) should be >= server1 (%d)",
                        server2Selections, server1Selections));
        assertTrue(server3Selections >= server1Selections,
                String.format("Server3 selections (%d) should be >= server1 (%d)",
                        server3Selections, server1Selections));
    }

    @Test
    void testLoadAwareSelectionIgnoresUnhealthyServers() throws SQLException {
        // Create manager with load-aware selection enabled
        MultinodeConnectionManager manager = new MultinodeConnectionManager(
                endpoints, 3, 1000, loadAwareConfig, null
        );

        SessionTracker tracker = manager.getSessionTracker();

        // Server2 has least sessions but is unhealthy
        simulateSessions(tracker, endpoints.get(0), 10);
        simulateSessions(tracker, endpoints.get(1), 1);
        simulateSessions(tracker, endpoints.get(2), 8);
        endpoints.get(1).setHealthy(false);

        // Should select server3 (8 sessions) instead of unhealthy server2 (1 session)
        // or heavily loaded server1 (10 sessions)
        ServerEndpoint selected = manager.affinityServer(null);

        assertNotNull(selected);
        assertEquals("server3:1059", selected.getAddress());
    }

    @Test
    void shouldThrowSQLExceptionWhenAffinityServerFindsNoHealthyServers() {
        MultinodeConnectionManager manager = new MultinodeConnectionManager(
                endpoints, 3, 1000, loadAwareConfig, null
        );

        long now = System.nanoTime();
        endpoints.forEach(endpoint -> {
            endpoint.setHealthy(false);
            endpoint.setLastFailureTime(now);
        });

        SQLException exception = assertThrows(SQLException.class, () -> manager.affinityServer(null));
        assertTrue(exception.getMessage().contains("No healthy servers available"));
        assertTrue(exception.getMessage().contains("checked " + endpoints.size() + " servers"));
    }

    @Test
    void testRoundRobinModeIgnoresLoad() throws SQLException {
        // Create manager with round-robin mode (load-aware disabled)
        MultinodeConnectionManager manager = new MultinodeConnectionManager(
                endpoints, 3, 1000, roundRobinConfig, null
        );

        SessionTracker tracker = manager.getSessionTracker();

        // Simulate very unbalanced load
        simulateSessions(tracker, endpoints.get(0), 100);
        simulateSessions(tracker, endpoints.get(1), 1);
        simulateSessions(tracker, endpoints.get(2), 50);

        // In round-robin mode, each server should be selected regardless of load
        // Make multiple selections and verify all servers are selected
        Map<String, Integer> selectionCounts = new HashMap<>();
        for (int i = 0; i < 30; i++) {
            ServerEndpoint selected = manager.affinityServer(null);
            assertNotNull(selected);
            selectionCounts.merge(selected.getAddress(), 1, Integer::sum);
        }

        // All servers should be selected approximately equal times (±3 for variance)
        int server1Selections = selectionCounts.getOrDefault("server1:1059", 0);
        int server2Selections = selectionCounts.getOrDefault("server2:1059", 0);
        int server3Selections = selectionCounts.getOrDefault("server3:1059", 0);

        // Each should be selected approximately 10 times (30 / 3 servers)
        assertTrue(Math.abs(server1Selections - 10) <= 3,
                "Server1 selections should be ~10, got " + server1Selections);
        assertTrue(Math.abs(server2Selections - 10) <= 3,
                "Server2 selections should be ~10, got " + server2Selections);
        assertTrue(Math.abs(server3Selections - 10) <= 3,
                "Server3 selections should be ~10, got " + server3Selections);
    }

    @Test
    void testLoadAwareConfigurationDefault() {
        HealthCheckConfig config = HealthCheckConfig.createDefault();
        assertTrue(config.isLoadAwareSelectionEnabled(),
                "Load-aware selection should be enabled by default");
    }

    @Test
    void testLoadAwareFallsBackToRoundRobinWhenAllCountsEqual() throws SQLException {
        // Create manager with load-aware selection enabled
        MultinodeConnectionManager manager = new MultinodeConnectionManager(
                endpoints, 3, 1000, loadAwareConfig, null
        );

        // Don't simulate any sessions - SessionTracker is empty
        // All servers have count = 0

        // Make multiple selections - should use round-robin since all are equal
        Map<String, Integer> selectionCounts = new HashMap<>();
        for (int i = 0; i < 30; i++) {
            ServerEndpoint selected = manager.affinityServer(null);
            assertNotNull(selected);
            selectionCounts.merge(selected.getAddress(), 1, Integer::sum);
        }

        // With round-robin fallback, all servers should be selected approximately equally
        int server1Selections = selectionCounts.getOrDefault("server1:1059", 0);
        int server2Selections = selectionCounts.getOrDefault("server2:1059", 0);
        int server3Selections = selectionCounts.getOrDefault("server3:1059", 0);

        // Each should be selected approximately 10 times (30 / 3 servers)
        assertTrue(Math.abs(server1Selections - 10) <= 3,
                "Server1 selections should be ~10 when using round-robin fallback, got " + server1Selections);
        assertTrue(Math.abs(server2Selections - 10) <= 3,
                "Server2 selections should be ~10 when using round-robin fallback, got " + server2Selections);
        assertTrue(Math.abs(server3Selections - 10) <= 3,
                "Server3 selections should be ~10 when using round-robin fallback, got " + server3Selections);

        // Verify all servers were actually selected (not just one)
        assertTrue(server1Selections > 0, "Server1 should be selected at least once");
        assertTrue(server2Selections > 0, "Server2 should be selected at least once");
        assertTrue(server3Selections > 0, "Server3 should be selected at least once");
    }

    /**
     * Helper method to simulate multiple sessions on a server.
     */
    private void simulateSessions(SessionTracker tracker, ServerEndpoint server, int count) {
        for (int i = 0; i < count; i++) {
            String sessionUUID = "session-" + server.getAddress() + "-" + i;
            tracker.registerSession(sessionUUID, server);
        }
    }

    /**
     * Helper method to simulate a single session on a server.
     */
    private void simulateSession(SessionTracker tracker, ServerEndpoint server) {
        simulateSessions(tracker, server, 1);
    }

}
