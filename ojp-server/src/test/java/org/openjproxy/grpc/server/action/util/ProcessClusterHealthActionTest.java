package org.openjproxy.grpc.server.action.util;

import com.openjproxy.grpc.ConnectionDetails;
import com.openjproxy.grpc.SessionInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openjproxy.grpc.server.ClusterHealthTracker;
import org.openjproxy.grpc.server.MultinodePoolCoordinator;
import org.openjproxy.grpc.server.ServerConfiguration;
import org.openjproxy.grpc.server.action.ActionContext;
import org.openjproxy.grpc.server.pool.ConnectionPoolConfigurer;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProcessClusterHealthActionTest {
    private static final String WIDE_POOL = "votes-wide-pool";
    private static final String NARROW_POOL = "votes-narrow-pool";
    private static final List<String> WIDE_ENDPOINTS = List.of("a:1059", "b:1059", "c:1059");
    private static final List<String> NARROW_ENDPOINTS = List.of("a:1059", "b:1059");
    private static final String REBALANCING_KEY = "ojp.server.multinode.poolRebalancing.enabled";

    private final MultinodePoolCoordinator coordinator = ConnectionPoolConfigurer.getPoolCoordinator();

    @AfterEach
    void tearDown() {
        coordinator.removeAllocation(WIDE_POOL);
        coordinator.removeAllocation(NARROW_POOL);
        System.clearProperty(REBALANCING_KEY);
    }

    @Test
    void shouldCombineVotesFromClientsWithDifferentServerLists() {
        AtomicLong now = new AtomicLong(1_000);
        ActionContext context = context(new ClusterHealthTracker(10_000, now::get), new ServerConfiguration());
        coordinator.calculatePoolSizes(WIDE_POOL, 30, 6, WIDE_ENDPOINTS);
        coordinator.calculatePoolSizes(NARROW_POOL, 30, 6, NARROW_ENDPOINTS);

        report(context, NARROW_POOL, "a:1059(UP);b:1059(DOWN)");
        now.addAndGet(5_000);
        report(context, WIDE_POOL, "a:1059(UP);b:1059(UP);c:1059(UP)");
        now.addAndGet(10_000);
        report(context, NARROW_POOL, "a:1059(UP);b:1059(DOWN)");

        // Client 1 still reached B during the window, so neither pool expands.
        assertEquals(3, coordinator.getPoolAllocation(WIDE_POOL).getHealthyServers());
        assertEquals(2, coordinator.getPoolAllocation(NARROW_POOL).getHealthyServers());

        now.addAndGet(10_000);
        report(context, NARROW_POOL, "a:1059(UP);b:1059(DOWN)");

        // No client reported B up for a full window: both pools that list B rebalance.
        assertEquals(2, coordinator.getPoolAllocation(WIDE_POOL).getHealthyServers());
        assertEquals(1, coordinator.getPoolAllocation(NARROW_POOL).getHealthyServers());

        report(context, WIDE_POOL, "a:1059(UP);b:1059(UP);c:1059(UP)");

        assertEquals(3, coordinator.getPoolAllocation(WIDE_POOL).getHealthyServers());
        assertEquals(2, coordinator.getPoolAllocation(NARROW_POOL).getHealthyServers());
    }

    @Test
    void shouldApplyAConfirmedDownVoteWithoutFurtherRequests() throws InterruptedException {
        ActionContext context = context(new ClusterHealthTracker(100), new ServerConfiguration());
        coordinator.calculatePoolSizes(NARROW_POOL, 30, 6, NARROW_ENDPOINTS);

        report(context, NARROW_POOL, "a:1059(UP);b:1059(DOWN)");
        assertEquals(2, coordinator.getPoolAllocation(NARROW_POOL).getHealthyServers());

        long deadline = System.currentTimeMillis() + 5_000;
        while (coordinator.getPoolAllocation(NARROW_POOL).getHealthyServers() != 1
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(1, coordinator.getPoolAllocation(NARROW_POOL).getHealthyServers());
    }

    @Test
    void shouldKeepTheConfiguredShareWhenRebalancingIsDisabled() {
        System.setProperty(REBALANCING_KEY, "false");
        ActionContext context = context(new ClusterHealthTracker(0), new ServerConfiguration());
        coordinator.calculatePoolSizes(NARROW_POOL, 30, 6, NARROW_ENDPOINTS);

        report(context, NARROW_POOL, "a:1059(UP);b:1059(DOWN)");

        assertEquals(2, coordinator.getPoolAllocation(NARROW_POOL).getHealthyServers());
        assertEquals(2, ConnectionPoolConfigurer.getHealthyServerCount(context, ConnectionDetails.newBuilder()
                .addAllServerEndpoints(NARROW_ENDPOINTS).setClusterHealth("a:1059(UP);b:1059(DOWN)").build()));
    }

    private void report(ActionContext context, String connHash, String clusterHealth) {
        ProcessClusterHealthAction.getInstance().execute(context,
                SessionInfo.newBuilder().setConnHash(connHash).setClusterHealth(clusterHealth).build());
    }

    private ActionContext context(ClusterHealthTracker tracker, ServerConfiguration configuration) {
        return new ActionContext(
                new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), new ConcurrentHashMap<>(),
                new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), new ConcurrentHashMap<>(),
                new ConcurrentHashMap<>(), null, null, null, tracker, null, null, configuration, null, null);
    }
}
