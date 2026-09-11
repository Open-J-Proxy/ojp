package org.openjproxy.grpc.server.readwrite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Tests for ReadWriteDataSourceRegistry — sticky session and strategy features (Phase 2).
 */
class ReadWriteDataSourceRegistryTest {

    private ReadWriteDataSourceRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ReadWriteDataSourceRegistry();
    }

    // -----------------------------------------------------------------------
    // Strategy
    // -----------------------------------------------------------------------

    @Test
    void shouldDefaultToRoundRobinStrategyWhenNoneRegistered() {
        assertEquals(ReadWriteConfiguration.ReplicaSelectionStrategy.ROUND_ROBIN,
                registry.getStrategy("primary"));
    }

    @Test
    void shouldReturnRegisteredStrategy() {
        registry.registerStrategy("primary", ReadWriteConfiguration.ReplicaSelectionStrategy.RANDOM);
        assertEquals(ReadWriteConfiguration.ReplicaSelectionStrategy.RANDOM, registry.getStrategy("primary"));
    }

    @Test
    void shouldThrowWhenRegisteringNullStrategy() {
        assertThrows(IllegalArgumentException.class, () -> registry.registerStrategy("primary", null));
    }

    @Test
    void shouldThrowWhenRegisteringStrategyForNullPrimary() {
        assertThrows(IllegalArgumentException.class,
                () -> registry.registerStrategy(null, ReadWriteConfiguration.ReplicaSelectionStrategy.RANDOM));
    }

    // -----------------------------------------------------------------------
    // Sticky session — isStickyActive
    // -----------------------------------------------------------------------

    @Test
    void shouldNotBeActiveWhenNoWriteHasOccurred() {
        registry.registerStickyTimeout("primary", 5);
        assertFalse(registry.isStickyActive("primary"));
    }

    @Test
    void shouldNotBeActiveWhenStickyTimeoutIsZero() {
        registry.registerStickyTimeout("primary", 0);
        registry.markWrite("primary");
        assertFalse(registry.isStickyActive("primary"));
    }

    @Test
    void shouldBeActiveImmediatelyAfterWrite() {
        registry.registerStickyTimeout("primary", 5);
        registry.markWrite("primary");
        assertTrue(registry.isStickyActive("primary"));
    }

    @Test
    void shouldNotBeActiveForNullPrimary() {
        assertFalse(registry.isStickyActive(null));
    }

    @Test
    void shouldExpireAfterTimeoutElapses() {
        registry.registerStickyTimeout("primary", 0); // 0 = immediately inactive
        registry.markWrite("primary");
        assertFalse(registry.isStickyActive("primary"),
                "Sticky session with timeout=0 should not be active");
    }

    // -----------------------------------------------------------------------
    // clear()
    // -----------------------------------------------------------------------

    @Test
    void shouldClearAllDataIncludingPhase2State() {
        DataSource ds = mock(DataSource.class);
        registry.registerPrimaryMapping("hash1", "primary");
        registry.registerReplica("primary", ds);
        registry.registerStickyTimeout("primary", 10);
        registry.registerStrategy("primary", ReadWriteConfiguration.ReplicaSelectionStrategy.RANDOM);
        registry.markWrite("primary");

        registry.clear();

        assertNull(registry.getPrimaryName("hash1"));
        assertTrue(registry.getReplicas("primary").isEmpty());
        assertFalse(registry.isStickyActive("primary"));
        assertEquals(ReadWriteConfiguration.ReplicaSelectionStrategy.ROUND_ROBIN,
                registry.getStrategy("primary"));
    }
}
