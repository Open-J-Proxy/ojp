package org.openjproxy.grpc.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openjproxy.jdbc.UrlParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ServerPriorityTest {
    private final List<MultinodeConnectionManager> managers = new ArrayList<>();

    @AfterEach
    void tearDown() {
        managers.forEach(MultinodeConnectionManager::shutdown);
    }

    @Test
    void shouldParsePrioritiesWithoutChangingTransportAddresses() {
        List<ServerEndpoint> endpoints = MultinodeUrlParser.parseServerEndpoints(
                "jdbc:ojp[remote:1059{20},local:1060{1},peer:1059{20}]_h2:mem:priority");
        assertEquals(List.of(20, 1, 20),
                List.of(endpoints.get(0).getPriority(), endpoints.get(1).getPriority(), endpoints.get(2).getPriority()));
        assertEquals("local:1060", endpoints.get(1).getAddress());
        assertEquals("remote:1059{20},local:1060{1},peer:1059{20}",
                MultinodeUrlParser.formatServerList(endpoints));
        assertEquals("jdbc:ojp[local:1060]_h2:mem:priority",
                MultinodeUrlParser.replaceBracketsWithSingleEndpoint(
                        "jdbc:ojp[remote:1059{20},local:1060{1}]_h2:mem:priority", endpoints.get(1)));
        assertEquals(Integer.MAX_VALUE, MultinodeUrlParser.parseServerEndpoints(
                "jdbc:ojp[local:1059{2147483647}]_h2:mem:priority").get(0).getPriority());
        assertEquals(1, new ServerEndpoint("legacy", 1059).getPriority());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "+1", "", "abc", "1.5", "2147483648", "1}{2", "1}junk", " 1 "})
    void shouldRejectInvalidPriorities(String priority) {
        assertThrows(IllegalArgumentException.class, () -> MultinodeUrlParser.parseServerEndpoints(
                "jdbc:ojp[local:1059{" + priority + "}]_h2:mem:priority"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"local:1059{1},remote:1059", "local:1059,remote:1059{2}",
            "local:1059{1", "local:1059}1", "local{1}:1059"})
    void shouldRejectMixedOrMalformedAnnotations(String servers) {
        assertThrows(IllegalArgumentException.class, () -> MultinodeUrlParser.parseServerEndpoints(
                "jdbc:ojp[" + servers + "]_h2:mem:priority"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"local:1059(app){1},remote:1059(batch){2}",
            "local:1059{1}(app),remote:1059{2}(batch)"})
    void shouldPreserveDatasourceNamesAndPriorities(String servers) {
        UrlParser.UrlParseResult parsed = UrlParser.parseUrlWithDataSource(
                "jdbc:ojp[" + servers + "]_h2:mem:priority");
        List<ServerEndpoint> endpoints = MultinodeUrlParser.parseServerEndpoints(parsed.cleanUrl, parsed.dataSourceNames);
        assertEquals("app", endpoints.get(0).getDataSourceName());
        assertEquals("batch", endpoints.get(1).getDataSourceName());
        assertEquals(1, endpoints.get(0).getPriority());
        assertEquals(2, endpoints.get(1).getPriority());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldExhaustTiersAndReturnToRecoveredPreferredServer(boolean loadAware) throws Exception {
        List<ServerEndpoint> endpoints = MultinodeUrlParser.parseServerEndpoints(
                "jdbc:ojp[remote:1059{3},peer1:1059{2},local:1059{1},peer2:1059{2}]_h2:mem:priority");
        MultinodeConnectionManager manager = manager(endpoints, loadAware);
        ServerEndpoint local = endpoints.get(2);
        for (int i = 0; i < 10; i++) {
            manager.getSessionTracker().registerSession("local-" + i, local);
            assertSame(local, manager.affinityServer(null), "Priority must override load");
        }
        local.markUnhealthy();
        ServerEndpoint first = manager.affinityServer(null);
        ServerEndpoint second = manager.affinityServer(null);
        assertEquals(2, first.getPriority());
        assertEquals(2, second.getPriority());
        assertNotSame(first, second, "Equal-load peers must retain round-robin balancing");
        endpoints.get(1).markUnhealthy();
        endpoints.get(3).markUnhealthy();
        assertSame(endpoints.get(0), manager.affinityServer(null));
        local.markHealthy();
        assertSame(local, manager.affinityServer(null));
    }

    @Test
    void shouldRetainLoadAwareSelectionInsidePreferredTier() throws Exception {
        List<ServerEndpoint> endpoints = MultinodeUrlParser.parseServerEndpoints(
                "jdbc:ojp[peer1:1059{2},peer2:1059{2},fallback:1059{3}]_h2:mem:priority");
        MultinodeConnectionManager manager = manager(endpoints, true);
        manager.getSessionTracker().registerSession("busy-peer", endpoints.get(0));
        assertSame(endpoints.get(1), manager.affinityServer(null));
    }

    @Test
    void shouldKeepExistingSessionAffinityAcrossPriorityRecovery() throws Exception {
        List<ServerEndpoint> endpoints = MultinodeUrlParser.parseServerEndpoints(
                "jdbc:ojp[local:1059{1},fallback:1059{2}]_h2:mem:priority");
        MultinodeConnectionManager manager = manager(endpoints, true);
        manager.bindSession("existing", endpoints.get(1).getAddress());
        assertSame(endpoints.get(1), manager.affinityServer("existing"));
        assertSame(endpoints.get(0), manager.affinityServer(null));
    }

    @Test
    void shouldSeparateCachedServicesWithDifferentPriorityPolicies() {
        MultinodeUrlParser.ServiceAndUrl first = MultinodeUrlParser.getOrCreateStatementService(
                "jdbc:ojp[cache-local:1059{1},cache-remote:1059{2}]_h2:mem:priority");
        MultinodeUrlParser.ServiceAndUrl second = MultinodeUrlParser.getOrCreateStatementService(
                "jdbc:ojp[cache-local:1059{2},cache-remote:1059{1}]_h2:mem:priority");
        managers.add(((MultinodeStatementService) first.getService()).getConnectionManager());
        managers.add(((MultinodeStatementService) second.getService()).getConnectionManager());
        assertNotSame(first.getService(), second.getService());
        assertSame(first.getService(), MultinodeUrlParser.getOrCreateStatementService(
                "jdbc:ojp[cache-local:1059{1},cache-remote:1059{2}]_h2:mem:other").getService());
    }

    private MultinodeConnectionManager manager(List<ServerEndpoint> endpoints, boolean loadAware) {
        Properties properties = new Properties();
        properties.setProperty("ojp.redistribution.enabled", "false");
        properties.setProperty("ojp.loadaware.selection.enabled", Boolean.toString(loadAware));
        MultinodeConnectionManager manager = new MultinodeConnectionManager(
                endpoints, 1, 60_000, HealthCheckConfig.loadFromProperties(properties));
        managers.add(manager);
        return manager;
    }
}
