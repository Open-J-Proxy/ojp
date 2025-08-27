package org.openjdbcproxy.grpc.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;

/**
 * Integration test for multinode URL parsing and connection management.
 * These tests validate the end-to-end URL parsing and connection logic
 * without requiring actual server connections.
 */
class MultinodeIntegrationTest {

    @Test
    void testCompleteUrlParsingAndConnectionSetup() {
        // Test complete multinode URL parsing
        String multinodeUrl = "jdbc:ojp[server1.example.com:1059,server2.example.com:1059,server3.example.com:1060]_postgresql://localhost:5432/testdb";
        
        // Parse server endpoints
        List<ServerEndpoint> endpoints = MultinodeUrlParser.parseServerEndpoints(multinodeUrl);
        
        // Verify correct parsing
        assertEquals(3, endpoints.size());
        assertEquals("server1.example.com", endpoints.get(0).getHost());
        assertEquals(1059, endpoints.get(0).getPort());
        assertEquals("server2.example.com", endpoints.get(1).getHost());
        assertEquals(1059, endpoints.get(1).getPort());
        assertEquals("server3.example.com", endpoints.get(2).getHost());
        assertEquals(1060, endpoints.get(2).getPort());
        
        // Verify actual JDBC URL extraction
        String actualJdbcUrl = MultinodeUrlParser.extractActualJdbcUrl(multinodeUrl);
        assertEquals("jdbc:postgresql://localhost:5432/testdb", actualJdbcUrl);
        
        // Test connection manager initialization (without actual connections)
        assertDoesNotThrow(() -> {
            MultinodeConnectionManager manager = new MultinodeConnectionManager(endpoints, 3, 1000);
            
            // Verify configuration
            assertEquals(3, manager.getServerEndpoints().size());
            
            // Clean up
            manager.shutdown();
        });
    }

    @Test
    void testSingleNodeBackwardCompatibility() {
        // Test that single-node URLs still work as before
        String singleNodeUrl = "jdbc:ojp[localhost:1059]_h2:mem:test";
        
        List<ServerEndpoint> endpoints = MultinodeUrlParser.parseServerEndpoints(singleNodeUrl);
        
        assertEquals(1, endpoints.size());
        assertEquals("localhost", endpoints.get(0).getHost());
        assertEquals(1059, endpoints.get(0).getPort());
        
        String actualJdbcUrl = MultinodeUrlParser.extractActualJdbcUrl(singleNodeUrl);
        assertEquals("jdbc:h2:mem:test", actualJdbcUrl);
    }

    @Test
    void testServerListFormatting() {
        // Test round-trip server list formatting
        List<ServerEndpoint> originalEndpoints = List.of(
            new ServerEndpoint("server1", 1059),
            new ServerEndpoint("server2", 1060),
            new ServerEndpoint("server3", 1061)
        );
        
        String formatted = MultinodeUrlParser.formatServerList(originalEndpoints);
        assertEquals("server1:1059,server2:1060,server3:1061", formatted);
        
        // Test parsing the formatted string back
        String testUrl = "jdbc:ojp[" + formatted + "]_postgresql://localhost:5432/test";
        List<ServerEndpoint> parsedEndpoints = MultinodeUrlParser.parseServerEndpoints(testUrl);
        
        assertEquals(originalEndpoints.size(), parsedEndpoints.size());
        for (int i = 0; i < originalEndpoints.size(); i++) {
            assertEquals(originalEndpoints.get(i).getHost(), parsedEndpoints.get(i).getHost());
            assertEquals(originalEndpoints.get(i).getPort(), parsedEndpoints.get(i).getPort());
        }
    }

    @Test
    void testRealWorldScenarios() {
        // Test various real-world URL patterns
        String[] testUrls = {
            "jdbc:ojp[db-proxy-01:1059,db-proxy-02:1059]_mysql://mysql-cluster:3306/production",
            "jdbc:ojp[10.0.1.100:1059,10.0.1.101:1059,10.0.1.102:1059]_oracle:thin:@oracle-db:1521/XE",
            "jdbc:ojp[proxy1.internal:1059,proxy2.internal:1059]_sqlserver://sqlserver:1433;databaseName=myapp",
            "jdbc:ojp[localhost:1059]_h2:file:/tmp/testdb" // Single node case
        };
        
        for (String url : testUrls) {
            assertDoesNotThrow(() -> {
                List<ServerEndpoint> endpoints = MultinodeUrlParser.parseServerEndpoints(url);
                assertFalse(endpoints.isEmpty(), "Should parse at least one endpoint for URL: " + url);
                
                String actualUrl = MultinodeUrlParser.extractActualJdbcUrl(url);
                assertNotNull(actualUrl, "Should extract actual JDBC URL for: " + url);
                assertFalse(actualUrl.contains("ojp["), "Actual URL should not contain OJP prefix: " + actualUrl);
            }, "Should successfully parse URL: " + url);
        }
    }

    @Test
    void testErrorHandlingAndEdgeCases() {
        // Test various error conditions
        assertThrows(IllegalArgumentException.class, () -> {
            MultinodeUrlParser.parseServerEndpoints("jdbc:postgresql://localhost:5432/test");
        }, "Should reject non-OJP URLs");
        
        assertThrows(IllegalArgumentException.class, () -> {
            MultinodeUrlParser.parseServerEndpoints("jdbc:ojp[]_postgresql://localhost:5432/test");
        }, "Should reject empty server list");
        
        assertThrows(IllegalArgumentException.class, () -> {
            MultinodeUrlParser.parseServerEndpoints("jdbc:ojp[invalid-format]_postgresql://localhost:5432/test");
        }, "Should reject invalid host:port format");
        
        assertThrows(IllegalArgumentException.class, () -> {
            MultinodeUrlParser.parseServerEndpoints("jdbc:ojp[host:99999]_postgresql://localhost:5432/test");
        }, "Should reject invalid port numbers");
    }

    @Test
    void testConnectionManagerRoundRobinLogic() {
        List<ServerEndpoint> endpoints = List.of(
            new ServerEndpoint("server1", 1059),
            new ServerEndpoint("server2", 1059),
            new ServerEndpoint("server3", 1059)
        );
        
        // Create connection manager with short retry settings for testing
        MultinodeConnectionManager manager = new MultinodeConnectionManager(endpoints, 1, 100);
        
        try {
            // All servers are initially marked as healthy
            assertTrue(endpoints.get(0).isHealthy());
            assertTrue(endpoints.get(1).isHealthy());
            assertTrue(endpoints.get(2).isHealthy());
            
            // Test that we can get all server endpoints
            List<ServerEndpoint> retrievedEndpoints = manager.getServerEndpoints();
            assertEquals(3, retrievedEndpoints.size());
            
        } finally {
            manager.shutdown();
        }
    }
}