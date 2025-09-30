package openjproxy.grpc.client;

import org.junit.jupiter.api.Test;
import org.openjproxy.grpc.client.MultinodeUrlParser;
import org.openjproxy.grpc.client.ServerEndpoint;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultinodeUrlParserTest {

    @Test
    void testParseSingleServerEndpoint() {
        String url = "jdbc:ojp[localhost:1059]_postgresql://localhost:5432/test";
        List<ServerEndpoint> endpoints = MultinodeUrlParser.parseServerEndpoints(url);

        assertEquals(1, endpoints.size());
        ServerEndpoint endpoint = endpoints.get(0);
        assertEquals("localhost", endpoint.getHost());
        assertEquals(1059, endpoint.getPort());
        assertTrue(endpoint.isHealthy());
    }

    @Test
    void testParseMultipleServerEndpoints() {
        String url = "jdbc:ojp[192.168.1.10:1059,192.168.1.11:1059,192.168.1.12:1060]_postgresql://localhost:5432/test";
        List<ServerEndpoint> endpoints = MultinodeUrlParser.parseServerEndpoints(url);

        assertEquals(3, endpoints.size());

        assertEquals("192.168.1.10", endpoints.get(0).getHost());
        assertEquals(1059, endpoints.get(0).getPort());

        assertEquals("192.168.1.11", endpoints.get(1).getHost());
        assertEquals(1059, endpoints.get(1).getPort());

        assertEquals("192.168.1.12", endpoints.get(2).getHost());
        assertEquals(1060, endpoints.get(2).getPort());
    }

    @Test
    void testParseWithSpaces() {
        String url = "jdbc:ojp[ host1:1059 , host2:1060 ]_postgresql://localhost:5432/test";
        List<ServerEndpoint> endpoints = MultinodeUrlParser.parseServerEndpoints(url);

        assertEquals(2, endpoints.size());
        assertEquals("host1", endpoints.get(0).getHost());
        assertEquals(1059, endpoints.get(0).getPort());
        assertEquals("host2", endpoints.get(1).getHost());
        assertEquals(1060, endpoints.get(1).getPort());
    }

    @Test
    void testParseInvalidUrlFormat() {
        assertThrows(IllegalArgumentException.class, () -> {
            MultinodeUrlParser.parseServerEndpoints("jdbc:postgresql://localhost:5432/test");
        });
    }

    @Test
    void testParseNullUrl() {
        assertThrows(IllegalArgumentException.class, () -> {
            MultinodeUrlParser.parseServerEndpoints(null);
        });
    }

    @Test
    void testParseInvalidHostPortFormat() {
        assertThrows(IllegalArgumentException.class, () -> {
            MultinodeUrlParser.parseServerEndpoints("jdbc:ojp[localhost]_postgresql://localhost:5432/test");
        });
    }

    @Test
    void testParseInvalidPortNumber() {
        assertThrows(IllegalArgumentException.class, () -> {
            MultinodeUrlParser.parseServerEndpoints("jdbc:ojp[localhost:abc]_postgresql://localhost:5432/test");
        });
    }

    @Test
    void testParsePortOutOfRange() {
        assertThrows(IllegalArgumentException.class, () -> {
            MultinodeUrlParser.parseServerEndpoints("jdbc:ojp[localhost:70000]_postgresql://localhost:5432/test");
        });

        assertThrows(IllegalArgumentException.class, () -> {
            MultinodeUrlParser.parseServerEndpoints("jdbc:ojp[localhost:0]_postgresql://localhost:5432/test");
        });
    }

    @Test
    void testParseEmptyServerList() {
        assertThrows(IllegalArgumentException.class, () -> {
            MultinodeUrlParser.parseServerEndpoints("jdbc:ojp[]_postgresql://localhost:5432/test");
        });
    }

    @Test
    void testParseWithEmptyServerInList() {
        String url = "jdbc:ojp[host1:1059,,host2:1060]_postgresql://localhost:5432/test";
        List<ServerEndpoint> endpoints = MultinodeUrlParser.parseServerEndpoints(url);

        assertEquals(2, endpoints.size());
        assertEquals("host1", endpoints.get(0).getHost());
        assertEquals("host2", endpoints.get(1).getHost());
    }

    @Test
    void testExtractActualJdbcUrl() {
        String url = "jdbc:ojp[localhost:1059]_postgresql://localhost:5432/test";
        String actual = MultinodeUrlParser.extractActualJdbcUrl(url);
        assertEquals("jdbc:postgresql://localhost:5432/test", actual);
    }

    @Test
    void testExtractActualJdbcUrlMultinode() {
        String url = "jdbc:ojp[host1:1059,host2:1060]_postgresql://localhost:5432/test";
        String actual = MultinodeUrlParser.extractActualJdbcUrl(url);
        assertEquals("jdbc:postgresql://localhost:5432/test", actual);
    }

    @Test
    void testExtractActualJdbcUrlNull() {
        assertNull(MultinodeUrlParser.extractActualJdbcUrl(null));
    }

    @Test
    void testFormatServerList() {
        List<ServerEndpoint> endpoints = List.of(
            new ServerEndpoint("host1", 1059),
            new ServerEndpoint("host2", 1060),
            new ServerEndpoint("host3", 1061)
        );

        String formatted = MultinodeUrlParser.formatServerList(endpoints);
        assertEquals("host1:1059,host2:1060,host3:1061", formatted);
    }

    @Test
    void testFormatEmptyServerList() {
        assertEquals("", MultinodeUrlParser.formatServerList(List.of()));
        assertEquals("", MultinodeUrlParser.formatServerList(null));
    }

    @Test
    void testServerEndpointAddress() {
        ServerEndpoint endpoint = new ServerEndpoint("example.com", 1059);
        assertEquals("example.com:1059", endpoint.getAddress());
        assertEquals("example.com:1059", endpoint.toString());
    }

    @Test
    void testServerEndpointHealth() {
        ServerEndpoint endpoint = new ServerEndpoint("localhost", 1059);
        assertTrue(endpoint.isHealthy());
        assertEquals(0, endpoint.getLastFailureTime());

        endpoint.setHealthy(false);
        endpoint.setLastFailureTime(System.currentTimeMillis());

        assertFalse(endpoint.isHealthy());
        assertTrue(endpoint.getLastFailureTime() > 0);
    }
}
