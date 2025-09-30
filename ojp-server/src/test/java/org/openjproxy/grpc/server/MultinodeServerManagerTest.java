package org.openjproxy.grpc.server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;

class MultinodeServerManagerTest {

    private MultinodeServerManager manager;

    @BeforeEach
    void setUp() {
        manager = new MultinodeServerManager();
    }

    @Test
    void testSingleServerConfiguration() {
        List<String> serverEndpoints = List.of("server1:1059");
        String clientUUID = "client1";
        String connHash = "conn1";
        int originalMaxPoolSize = 20;

        MultinodeServerManager.PoolConfiguration config = manager.registerClientServers(
                clientUUID, serverEndpoints, connHash, originalMaxPoolSize);

        // Single server should maintain original pool size
        assertEquals(20, config.getMaxPoolSize());
        assertEquals(5, config.getMinPoolSize()); // Based on DEFAULT_MINIMUM_IDLE
        assertEquals(1, config.getServerCount());
    }

    @Test
    void testMultiServerConfiguration() {
        List<String> serverEndpoints = List.of("server1:1059", "server2:1059", "server3:1059");
        String clientUUID = "client1";
        String connHash = "conn1";
        int originalMaxPoolSize = 21; // Chosen to test ceiling division

        MultinodeServerManager.PoolConfiguration config = manager.registerClientServers(
                clientUUID, serverEndpoints, connHash, originalMaxPoolSize);

        // Pool size should be divided among servers (21/3 = 7)
        assertEquals(7, config.getMaxPoolSize());
        assertEquals(2, config.getMinPoolSize()); // 5/3 = 1.67, ceil = 2
        assertEquals(3, config.getServerCount());
    }

    @Test
    void testPoolConfigurationWithRounding() {
        List<String> serverEndpoints = List.of("server1:1059", "server2:1059", "server3:1059");
        String clientUUID = "client1";
        String connHash = "conn1";
        int originalMaxPoolSize = 10;

        MultinodeServerManager.PoolConfiguration config = manager.registerClientServers(
                clientUUID, serverEndpoints, connHash, originalMaxPoolSize);

        // 10/3 = 3.33, ceil = 4
        assertEquals(4, config.getMaxPoolSize());
        assertEquals(2, config.getMinPoolSize()); // 5/3 = 1.67, ceil = 2
        assertEquals(3, config.getServerCount());
    }

    @Test
    void testMinimumPoolSizeConstraints() {
        List<String> serverEndpoints = List.of("server1:1059", "server2:1059", "server3:1059", "server4:1059");
        String clientUUID = "client1";
        String connHash = "conn1";
        int originalMaxPoolSize = 4; // Very small pool

        MultinodeServerManager.PoolConfiguration config = manager.registerClientServers(
                clientUUID, serverEndpoints, connHash, originalMaxPoolSize);

        // 4/4 = 1 for max, min should not exceed max
        assertEquals(1, config.getMaxPoolSize());
        assertEquals(1, config.getMinPoolSize());
        assertEquals(4, config.getServerCount());
    }

    @Test
    void testEmptyServerList() {
        List<String> serverEndpoints = List.of();
        String clientUUID = "client1";
        String connHash = "conn1";
        int originalMaxPoolSize = 20;

        MultinodeServerManager.PoolConfiguration config = manager.registerClientServers(
                clientUUID, serverEndpoints, connHash, originalMaxPoolSize);

        // Empty list should default to 1 server
        assertEquals(20, config.getMaxPoolSize());
        assertEquals(5, config.getMinPoolSize());
        assertEquals(1, config.getServerCount());
    }

    @Test
    void testMultipleClientsWithSameConnHash() {
        String connHash = "conn1";
        int originalMaxPoolSize = 20;

        // First client with 2 servers
        List<String> serverEndpoints1 = List.of("server1:1059", "server2:1059");
        MultinodeServerManager.PoolConfiguration config1 = manager.registerClientServers(
                "client1", serverEndpoints1, connHash, originalMaxPoolSize);

        assertEquals(10, config1.getMaxPoolSize()); // 20/2
        assertEquals(3, config1.getMinPoolSize()); // 5/2 = 2.5, ceil = 3

        // Second client with 3 servers - should use the higher server count
        List<String> serverEndpoints2 = List.of("server1:1059", "server2:1059", "server3:1059");
        MultinodeServerManager.PoolConfiguration config2 = manager.registerClientServers(
                "client2", serverEndpoints2, connHash, originalMaxPoolSize);

        // Should still use original pool size since it's the same connHash
        assertEquals(7, config2.getMaxPoolSize()); // 20/3 = 6.67, ceil = 7
        assertEquals(2, config2.getMinPoolSize()); // 5/3 = 1.67, ceil = 2
    }

    @Test
    void testClientUnregistration() {
        String clientUUID = "client1";
        List<String> serverEndpoints = List.of("server1:1059", "server2:1059");

        manager.registerClientServers(clientUUID, serverEndpoints, "conn1", 20);

        // Verify effective server count before unregistration
        assertEquals(2, manager.getEffectiveServerCount("conn1"));

        // Unregister client
        manager.unregisterClient(clientUUID);

        // Effective server count should fall back to 1 (default)
        assertEquals(1, manager.getEffectiveServerCount("conn1"));
    }

    @Test
    void testEffectiveServerCount() {
        // Register multiple clients with different server counts
        manager.registerClientServers("client1", List.of("server1:1059"), "conn1", 20);
        manager.registerClientServers("client2", List.of("server1:1059", "server2:1059"), "conn1", 20);
        manager.registerClientServers("client3", List.of("server1:1059", "server2:1059", "server3:1059"), "conn1", 20);

        // Should use the maximum server count reported
        assertEquals(3, manager.getEffectiveServerCount("conn1"));
    }

    @Test
    void testShouldReconfigurePool() {
        manager.registerClientServers("client1", List.of("server1:1059", "server2:1059"), "conn1", 20);

        // Current server count matches effective server count
        assertFalse(manager.shouldReconfigurePool("conn1", 2));

        // Different server count should trigger reconfiguration
        assertTrue(manager.shouldReconfigurePool("conn1", 1));
        assertTrue(manager.shouldReconfigurePool("conn1", 3));
    }

    @Test
    void testOriginalPoolSizePreservation() {
        String connHash = "conn1";

        // Register client with initial pool size
        manager.registerClientServers("client1", List.of("server1:1059"), connHash, 20);

        // Register another client with different pool size - should use original
        MultinodeServerManager.PoolConfiguration config = manager.registerClientServers(
                "client2", List.of("server1:1059", "server2:1059"), connHash, 30);

        // Should still base calculation on original pool size (20)
        assertEquals(10, config.getMaxPoolSize()); // 20/2, not 30/2
    }
}
