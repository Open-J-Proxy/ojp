package org.openjproxy.grpc.server.readwrite;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ReadWriteConfigurationParser class
 */
class ReadWriteConfigurationParserTest {
    
    @BeforeEach
    void setUp() {
        ReadWriteConfigurationParser.clearCache();
    }
    
    @AfterEach
    void tearDown() {
        ReadWriteConfigurationParser.clearCache();
    }
    
    @Test
    void testParseBasicConfiguration() {
        Properties props = new Properties();
        props.setProperty("primary.ojp.readwrite.enabled", "true");
        props.setProperty("primary.ojp.readwrite.role", "primary");
        props.setProperty("primary.ojp.readwrite.replicaSelectionStrategy", "ROUND_ROBIN");
        props.setProperty("primary.ojp.readwrite.stickySessionSeconds", "5");
        props.setProperty("primary.ojp.readwrite.replicaFailoverToPrimary", "true");
        
        props.setProperty("replica1.ojp.readwrite.role", "replica");
        props.setProperty("replica1.ojp.readwrite.primary", "primary");
        
        Map<String, ReadWriteConfiguration> configs = ReadWriteConfigurationParser.parseAll(props);
        
        assertEquals(1, configs.size());
        assertTrue(configs.containsKey("primary"));
        
        ReadWriteConfiguration config = configs.get("primary");
        assertEquals("primary", config.getPrimaryName());
        assertTrue(config.isEnabled());
        assertEquals(ReadWriteConfiguration.ReplicaSelectionStrategy.ROUND_ROBIN, config.getStrategy());
        assertEquals(5, config.getStickySessionSeconds());
        assertTrue(config.isFailoverToPrimary());
        assertEquals(1, config.getReplicaCount());
        assertTrue(config.getReplicaNames().contains("replica1"));
    }
    
    @Test
    void testParseMultipleReplicas() {
        Properties props = new Properties();
        props.setProperty("primary.ojp.readwrite.enabled", "true");
        props.setProperty("primary.ojp.readwrite.role", "primary");
        
        props.setProperty("replica1.ojp.readwrite.role", "replica");
        props.setProperty("replica1.ojp.readwrite.primary", "primary");
        
        props.setProperty("replica2.ojp.readwrite.role", "replica");
        props.setProperty("replica2.ojp.readwrite.primary", "primary");
        
        props.setProperty("replica3.ojp.readwrite.role", "replica");
        props.setProperty("replica3.ojp.readwrite.primary", "primary");
        
        ReadWriteConfiguration config = ReadWriteConfigurationParser.parseForPrimary("primary", props);
        
        assertEquals(3, config.getReplicaCount());
        assertTrue(config.getReplicaNames().contains("replica1"));
        assertTrue(config.getReplicaNames().contains("replica2"));
        assertTrue(config.getReplicaNames().contains("replica3"));
    }
    
    @Test
    void testParseMultiplePrimaries() {
        Properties props = new Properties();
        
        // Primary 1
        props.setProperty("db1.ojp.readwrite.enabled", "true");
        props.setProperty("db1.ojp.readwrite.role", "primary");
        props.setProperty("db1_replica.ojp.readwrite.role", "replica");
        props.setProperty("db1_replica.ojp.readwrite.primary", "db1");
        
        // Primary 2
        props.setProperty("db2.ojp.readwrite.enabled", "true");
        props.setProperty("db2.ojp.readwrite.role", "primary");
        props.setProperty("db2_replica.ojp.readwrite.role", "replica");
        props.setProperty("db2_replica.ojp.readwrite.primary", "db2");
        
        Map<String, ReadWriteConfiguration> configs = ReadWriteConfigurationParser.parseAll(props);
        
        assertEquals(2, configs.size());
        assertTrue(configs.containsKey("db1"));
        assertTrue(configs.containsKey("db2"));
        
        assertEquals(1, configs.get("db1").getReplicaCount());
        assertEquals(1, configs.get("db2").getReplicaCount());
    }
    
    @Test
    void testParseDisabledConfiguration() {
        Properties props = new Properties();
        props.setProperty("primary.ojp.readwrite.enabled", "false");
        props.setProperty("primary.ojp.readwrite.role", "primary");
        
        ReadWriteConfiguration config = ReadWriteConfigurationParser.parseForPrimary("primary", props);
        
        assertFalse(config.isEnabled());
    }
    
    @Test
    void testParseDefaultValues() {
        Properties props = new Properties();
        props.setProperty("primary.ojp.readwrite.role", "primary");
        
        ReadWriteConfiguration config = ReadWriteConfigurationParser.parseForPrimary("primary", props);
        
        assertFalse(config.isEnabled()); // Default: disabled
        assertEquals(ReadWriteConfiguration.ReplicaSelectionStrategy.ROUND_ROBIN, config.getStrategy());
        assertEquals(0, config.getStickySessionSeconds());
        assertTrue(config.isFailoverToPrimary());
    }
    
    @Test
    void testParseCustomStrategy() {
        Properties props = new Properties();
        props.setProperty("primary.ojp.readwrite.role", "primary");
        props.setProperty("primary.ojp.readwrite.replicaSelectionStrategy", "RANDOM");
        
        ReadWriteConfiguration config = ReadWriteConfigurationParser.parseForPrimary("primary", props);
        
        assertEquals(ReadWriteConfiguration.ReplicaSelectionStrategy.RANDOM, config.getStrategy());
    }
    
    @Test
    void testParseInvalidStrategy() {
        Properties props = new Properties();
        props.setProperty("primary.ojp.readwrite.role", "primary");
        props.setProperty("primary.ojp.readwrite.replicaSelectionStrategy", "INVALID");
        
        ReadWriteConfiguration config = ReadWriteConfigurationParser.parseForPrimary("primary", props);
        
        // Should fall back to default
        assertEquals(ReadWriteConfiguration.ReplicaSelectionStrategy.ROUND_ROBIN, config.getStrategy());
    }
    
    @Test
    void testParseCaseInsensitiveStrategy() {
        Properties props = new Properties();
        props.setProperty("primary.ojp.readwrite.role", "primary");
        props.setProperty("primary.ojp.readwrite.replicaSelectionStrategy", "round_robin");
        
        ReadWriteConfiguration config = ReadWriteConfigurationParser.parseForPrimary("primary", props);
        
        assertEquals(ReadWriteConfiguration.ReplicaSelectionStrategy.ROUND_ROBIN, config.getStrategy());
    }
    
    @Test
    void testParseCustomStickySessionSeconds() {
        Properties props = new Properties();
        props.setProperty("primary.ojp.readwrite.role", "primary");
        props.setProperty("primary.ojp.readwrite.stickySessionSeconds", "10");
        
        ReadWriteConfiguration config = ReadWriteConfigurationParser.parseForPrimary("primary", props);
        
        assertEquals(10, config.getStickySessionSeconds());
    }
    
    @Test
    void testParseInvalidStickySessionSeconds() {
        Properties props = new Properties();
        props.setProperty("primary.ojp.readwrite.role", "primary");
        props.setProperty("primary.ojp.readwrite.stickySessionSeconds", "invalid");
        
        ReadWriteConfiguration config = ReadWriteConfigurationParser.parseForPrimary("primary", props);
        
        // Should use default (0 — disabled)
        assertEquals(0, config.getStickySessionSeconds());
    }
    
    @Test
    void testParseFailoverToPrimaryDisabled() {
        Properties props = new Properties();
        props.setProperty("primary.ojp.readwrite.role", "primary");
        props.setProperty("primary.ojp.readwrite.replicaFailoverToPrimary", "false");
        
        ReadWriteConfiguration config = ReadWriteConfigurationParser.parseForPrimary("primary", props);
        
        assertFalse(config.isFailoverToPrimary());
    }
    
    @Test
    void testValidateReplicaReferences_Valid() {
        Properties props = new Properties();
        props.setProperty("primary.ojp.readwrite.role", "primary");
        props.setProperty("replica1.ojp.readwrite.role", "replica");
        props.setProperty("replica1.ojp.readwrite.primary", "primary");
        
        // Should not throw
        assertDoesNotThrow(() -> ReadWriteConfigurationParser.validateReplicaReferences(props));
    }
    
    @Test
    void testValidateReplicaReferences_MissingPrimary() {
        Properties props = new Properties();
        props.setProperty("replica1.ojp.readwrite.role", "replica");
        props.setProperty("replica1.ojp.readwrite.primary", "nonexistent");
        
        assertThrows(IllegalArgumentException.class, () ->
                ReadWriteConfigurationParser.validateReplicaReferences(props));
    }
    
    @Test
    void testValidateReplicaReferences_NoPrimarySpecified() {
        Properties props = new Properties();
        props.setProperty("replica1.ojp.readwrite.role", "replica");
        // Missing: replica1.ojp.readwrite.primary
        
        assertThrows(IllegalArgumentException.class, () ->
                ReadWriteConfigurationParser.validateReplicaReferences(props));
    }
    
    @Test
    void testNoConfiguration() {
        Properties props = new Properties();
        
        Map<String, ReadWriteConfiguration> configs = ReadWriteConfigurationParser.parseAll(props);
        
        assertTrue(configs.isEmpty());
    }
    
    @Test
    void testReplicaWithoutMatchingPrimary() {
        Properties props = new Properties();
        props.setProperty("primary.ojp.readwrite.role", "primary");
        props.setProperty("replica1.ojp.readwrite.role", "replica");
        props.setProperty("replica1.ojp.readwrite.primary", "other_primary");
        
        ReadWriteConfiguration config = ReadWriteConfigurationParser.parseForPrimary("primary", props);
        
        // Replica doesn't reference this primary
        assertEquals(0, config.getReplicaCount());
    }
    
    @Test
    void testCaching() {
        Properties props = new Properties();
        props.setProperty("primary.ojp.readwrite.enabled", "true");
        props.setProperty("primary.ojp.readwrite.role", "primary");
        props.setProperty("replica1.ojp.readwrite.role", "replica");
        props.setProperty("replica1.ojp.readwrite.primary", "primary");
        
        ReadWriteConfiguration config1 = ReadWriteConfigurationParser.parseForPrimary("primary", props);
        ReadWriteConfiguration config2 = ReadWriteConfigurationParser.parseForPrimary("primary", props);
        
        // Should return same instance from cache (only cached when enabled with replicas)
        assertSame(config1, config2);
    }
    
    @Test
    void testClearCache() {
        Properties props = new Properties();
        props.setProperty("primary.ojp.readwrite.role", "primary");
        
        ReadWriteConfiguration config1 = ReadWriteConfigurationParser.parseForPrimary("primary", props);
        
        ReadWriteConfigurationParser.clearCache();
        
        ReadWriteConfiguration config2 = ReadWriteConfigurationParser.parseForPrimary("primary", props);
        
        // Should be different instances after cache clear
        assertNotSame(config1, config2);
    }
    
    @Test
    void testDatasourceNameWithUnderscore() {
        Properties props = new Properties();
        props.setProperty("my_primary_db.ojp.readwrite.role", "primary");
        props.setProperty("my_replica_db.ojp.readwrite.role", "replica");
        props.setProperty("my_replica_db.ojp.readwrite.primary", "my_primary_db");
        
        ReadWriteConfiguration config = ReadWriteConfigurationParser.parseForPrimary("my_primary_db", props);
        
        assertEquals("my_primary_db", config.getPrimaryName());
        assertEquals(1, config.getReplicaCount());
        assertTrue(config.getReplicaNames().contains("my_replica_db"));
    }
}
