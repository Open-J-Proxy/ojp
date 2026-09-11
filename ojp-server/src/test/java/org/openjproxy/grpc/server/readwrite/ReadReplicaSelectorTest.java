package org.openjproxy.grpc.server.readwrite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Tests for ReadReplicaSelector
 */
class ReadReplicaSelectorTest {

    private ReadReplicaSelector selector;
    private DataSource ds1;
    private DataSource ds2;
    private DataSource ds3;

    @BeforeEach
    void setUp() {
        selector = new ReadReplicaSelector();
        ds1 = mock(DataSource.class);
        ds2 = mock(DataSource.class);
        ds3 = mock(DataSource.class);
    }

    @Test
    void shouldReturnNullWhenNoReplicasAvailable() {
        DataSource result = selector.select("primary", List.of(),
                ReadWriteConfiguration.ReplicaSelectionStrategy.ROUND_ROBIN);
        assertNull(result);
    }

    @Test
    void shouldReturnOnlyReplicaForRoundRobin() {
        DataSource result = selector.select("primary", List.of(ds1),
                ReadWriteConfiguration.ReplicaSelectionStrategy.ROUND_ROBIN);
        assertEquals(ds1, result);
    }

    @Test
    void shouldCycleThroughReplicasWithRoundRobin() {
        List<DataSource> replicas = List.of(ds1, ds2, ds3);

        DataSource first = selector.select("primary", replicas,
                ReadWriteConfiguration.ReplicaSelectionStrategy.ROUND_ROBIN);
        DataSource second = selector.select("primary", replicas,
                ReadWriteConfiguration.ReplicaSelectionStrategy.ROUND_ROBIN);
        DataSource third = selector.select("primary", replicas,
                ReadWriteConfiguration.ReplicaSelectionStrategy.ROUND_ROBIN);
        DataSource fourth = selector.select("primary", replicas,
                ReadWriteConfiguration.ReplicaSelectionStrategy.ROUND_ROBIN);

        // Should cycle: ds1 → ds2 → ds3 → ds1
        assertEquals(ds1, first);
        assertEquals(ds2, second);
        assertEquals(ds3, third);
        assertEquals(ds1, fourth);
    }

    @Test
    void shouldReturnReplicaForRandomStrategy() {
        List<DataSource> replicas = List.of(ds1, ds2);
        DataSource result = selector.select("primary", replicas,
                ReadWriteConfiguration.ReplicaSelectionStrategy.RANDOM);
        assertTrue(replicas.contains(result), "Random selection must return one of the registered replicas");
    }

    @Test
    void shouldUseRoundRobinFallbackForLeastConnections() {
        List<DataSource> replicas = List.of(ds1, ds2);

        DataSource first = selector.select("primary", replicas,
                ReadWriteConfiguration.ReplicaSelectionStrategy.LEAST_CONNECTIONS);
        DataSource second = selector.select("primary", replicas,
                ReadWriteConfiguration.ReplicaSelectionStrategy.LEAST_CONNECTIONS);

        // LEAST_CONNECTIONS falls back to round-robin in Phase 2
        assertNotNull(first);
        assertNotNull(second);
        assertTrue(replicas.contains(first));
        assertTrue(replicas.contains(second));
    }

    @Test
    void shouldMaintainSeparateCountersPerPrimary() {
        List<DataSource> replicas = List.of(ds1, ds2);

        DataSource primary1First = selector.select("primary1", replicas,
                ReadWriteConfiguration.ReplicaSelectionStrategy.ROUND_ROBIN);
        DataSource primary2First = selector.select("primary2", replicas,
                ReadWriteConfiguration.ReplicaSelectionStrategy.ROUND_ROBIN);

        // Each primary has its own counter starting at 0 → both pick ds1
        assertEquals(ds1, primary1First);
        assertEquals(ds1, primary2First);
    }
}
