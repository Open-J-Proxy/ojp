package org.openjproxy.grpc.server.readwrite;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ReadWriteConfiguration class
 */
class ReadWriteConfigurationTest {
    
    @Test
    void testBasicConfiguration() {
        ReadWriteConfiguration config = new ReadWriteConfiguration.Builder()
                .primaryName("primary")
                .enabled(true)
                .strategy(ReadWriteConfiguration.ReplicaSelectionStrategy.ROUND_ROBIN)
                .stickySessionSeconds(5)
                .failoverToPrimary(true)
                .addReplica("replica1")
                .addReplica("replica2")
                .build();
        
        assertEquals("primary", config.getPrimaryName());
        assertTrue(config.isEnabled());
        assertEquals(ReadWriteConfiguration.ReplicaSelectionStrategy.ROUND_ROBIN, config.getStrategy());
        assertEquals(5, config.getStickySessionSeconds());
        assertTrue(config.isFailoverToPrimary());
        assertEquals(2, config.getReplicaCount());
        assertEquals(Arrays.asList("replica1", "replica2"), config.getReplicaNames());
        assertTrue(config.hasReplicas());
    }
    
    @Test
    void testDisabledConfiguration() {
        ReadWriteConfiguration config = new ReadWriteConfiguration.Builder()
                .primaryName("primary")
                .enabled(false)
                .build();
        
        assertFalse(config.isEnabled());
        assertFalse(config.hasReplicas());
        assertEquals(0, config.getReplicaCount());
    }
    
    @Test
    void testDifferentStrategies() {
        ReadWriteConfiguration roundRobin = new ReadWriteConfiguration.Builder()
                .primaryName("primary")
                .strategy(ReadWriteConfiguration.ReplicaSelectionStrategy.ROUND_ROBIN)
                .build();
        
        ReadWriteConfiguration random = new ReadWriteConfiguration.Builder()
                .primaryName("primary")
                .strategy(ReadWriteConfiguration.ReplicaSelectionStrategy.RANDOM)
                .build();
        
        ReadWriteConfiguration leastConn = new ReadWriteConfiguration.Builder()
                .primaryName("primary")
                .strategy(ReadWriteConfiguration.ReplicaSelectionStrategy.LEAST_CONNECTIONS)
                .build();
        
        assertEquals(ReadWriteConfiguration.ReplicaSelectionStrategy.ROUND_ROBIN, roundRobin.getStrategy());
        assertEquals(ReadWriteConfiguration.ReplicaSelectionStrategy.RANDOM, random.getStrategy());
        assertEquals(ReadWriteConfiguration.ReplicaSelectionStrategy.LEAST_CONNECTIONS, leastConn.getStrategy());
    }
    
    @Test
    void testReplicaListImmutability() {
        List<String> replicas = Arrays.asList("replica1", "replica2");
        ReadWriteConfiguration config = new ReadWriteConfiguration.Builder()
                .primaryName("primary")
                .replicas(replicas)
                .build();
        
        // Returned list should be immutable
        List<String> replicaNames = config.getReplicaNames();
        assertThrows(UnsupportedOperationException.class, () -> replicaNames.add("replica3"));
    }
    
    @Test
    void testValidation_NullPrimaryName() {
        ReadWriteConfiguration.Builder builder = new ReadWriteConfiguration.Builder().primaryName(null);
        assertThrows(NullPointerException.class, builder::build);
    }
    
    @Test
    void testValidation_EmptyPrimaryName() {
        ReadWriteConfiguration.Builder builder = new ReadWriteConfiguration.Builder().primaryName("");
        assertThrows(IllegalArgumentException.class, builder::build);
    }
    
    @Test
    void testValidation_EnabledWithoutReplicas() {
        ReadWriteConfiguration.Builder builder = new ReadWriteConfiguration.Builder()
                .primaryName("primary").enabled(true);
        assertThrows(IllegalArgumentException.class, builder::build);
    }
    
    @Test
    void testValidation_NegativeStickySessionSeconds() {
        ReadWriteConfiguration.Builder builder = new ReadWriteConfiguration.Builder()
                .primaryName("primary").stickySessionSeconds(-1).addReplica("replica1");
        assertThrows(IllegalArgumentException.class, builder::build);
    }
    
    @Test
    void testValidation_DuplicateReplicas() {
        ReadWriteConfiguration.Builder builder = new ReadWriteConfiguration.Builder()
                .primaryName("primary").enabled(true).addReplica("replica1").addReplica("replica1");
        assertThrows(IllegalArgumentException.class, builder::build);
    }
    
    @Test
    void testValidation_ZeroStickySessionSeconds() {
        // Zero is valid (instant expiration)
        ReadWriteConfiguration config = new ReadWriteConfiguration.Builder()
                .primaryName("primary")
                .stickySessionSeconds(0)
                .addReplica("replica1")
                .build();
        
        assertEquals(0, config.getStickySessionSeconds());
    }
    
    @Test
    void testDefaultValues() {
        ReadWriteConfiguration config = new ReadWriteConfiguration.Builder()
                .primaryName("primary")
                .addReplica("replica1")
                .build();
        
        assertFalse(config.isEnabled()); // Default: disabled
        assertEquals(ReadWriteConfiguration.ReplicaSelectionStrategy.ROUND_ROBIN, config.getStrategy());
        assertEquals(5, config.getStickySessionSeconds());
        assertTrue(config.isFailoverToPrimary());
    }
    
    @Test
    void testToString() {
        ReadWriteConfiguration config = new ReadWriteConfiguration.Builder()
                .primaryName("myPrimary")
                .enabled(true)
                .addReplica("replica1")
                .build();
        
        String str = config.toString();
        assertTrue(str.contains("myPrimary"));
        assertTrue(str.contains("enabled=true"));
    }
    
    @Test
    void testMultipleReplicas() {
        ReadWriteConfiguration config = new ReadWriteConfiguration.Builder()
                .primaryName("primary")
                .addReplica("replica1")
                .addReplica("replica2")
                .addReplica("replica3")
                .build();
        
        assertEquals(3, config.getReplicaCount());
        assertEquals(Arrays.asList("replica1", "replica2", "replica3"), config.getReplicaNames());
    }
    
    @Test
    void testFailoverToPrimaryDisabled() {
        ReadWriteConfiguration config = new ReadWriteConfiguration.Builder()
                .primaryName("primary")
                .failoverToPrimary(false)
                .addReplica("replica1")
                .build();
        
        assertFalse(config.isFailoverToPrimary());
    }
    
    @Test
    void testCustomStickySessionSeconds() {
        ReadWriteConfiguration config = new ReadWriteConfiguration.Builder()
                .primaryName("primary")
                .stickySessionSeconds(10)
                .addReplica("replica1")
                .build();
        
        assertEquals(10, config.getStickySessionSeconds());
    }
}
