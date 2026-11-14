package org.openjproxy.grpc.server.pool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openjproxy.constants.CommonConstants;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Test for DataSourceConfigurationManager functionality.
 */
class DataSourceConfigurationManagerTest {

    @BeforeEach
    void setUp() {
        // Clear cache before each test
        DataSourceConfigurationManager.clearCache();
    }

    @Test
    void testDefaultDataSourceConfiguration() {
        // Test with null properties (default configuration)
        DataSourceConfigurationManager.DataSourceConfiguration config =
                DataSourceConfigurationManager.getConfiguration(null);

        assertEquals("default", config.getDataSourceName());
        assertEquals(CommonConstants.DEFAULT_MAXIMUM_POOL_SIZE, config.getMaximumPoolSize());
        assertEquals(CommonConstants.DEFAULT_MINIMUM_IDLE, config.getMinimumIdle());
        assertEquals(CommonConstants.DEFAULT_IDLE_TIMEOUT, config.getIdleTimeout());
        assertEquals(CommonConstants.DEFAULT_MAX_LIFETIME, config.getMaxLifetime());
        assertEquals(CommonConstants.DEFAULT_CONNECTION_TIMEOUT, config.getConnectionTimeout());
    }

    @Test
    void testCustomDataSourceConfiguration() {
        Properties props = new Properties();
        props.setProperty(CommonConstants.DATASOURCE_NAME_PROPERTY, "myApp");
        props.setProperty(CommonConstants.MAXIMUM_POOL_SIZE_PROPERTY, "50");
        props.setProperty(CommonConstants.MINIMUM_IDLE_PROPERTY, "10");
        props.setProperty(CommonConstants.CONNECTION_TIMEOUT_PROPERTY, "15000");
        props.setProperty(CommonConstants.CONNECTION_CREATION_GRACE_PERIOD_PROPERTY, "7500");

        DataSourceConfigurationManager.DataSourceConfiguration config =
                DataSourceConfigurationManager.getConfiguration(props);

        assertEquals("myApp", config.getDataSourceName());
        assertEquals(50, config.getMaximumPoolSize());
        assertEquals(10, config.getMinimumIdle());
        assertEquals(15000, config.getConnectionTimeout());
        assertEquals(7500, config.getConnectionCreationGracePeriod());

        // Should use defaults for properties not specified
        assertEquals(CommonConstants.DEFAULT_IDLE_TIMEOUT, config.getIdleTimeout());
        assertEquals(CommonConstants.DEFAULT_MAX_LIFETIME, config.getMaxLifetime());
    }

    @Test
    void shouldCacheCreationGraceSeparatelyAndAllowDisablingIt() {
        Properties props = new Properties();
        props.setProperty(CommonConstants.DATASOURCE_NAME_PROPERTY, "mainApp");
        var defaults = DataSourceConfigurationManager.getConfiguration(props);
        assertEquals(5000, defaults.getConnectionCreationGracePeriod());

        props.setProperty(CommonConstants.CONNECTION_CREATION_GRACE_PERIOD_PROPERTY, "0");
        var disabled = DataSourceConfigurationManager.getConfiguration(props);
        assertNotSame(defaults, disabled);
        assertEquals(0, disabled.getConnectionCreationGracePeriod());

        props.setProperty(CommonConstants.CONNECTION_CREATION_GRACE_PERIOD_PROPERTY, "-1");
        assertThrows(IllegalArgumentException.class,
                () -> DataSourceConfigurationManager.getConfiguration(props));
    }

    @Test
    void testReadOnlyDataSourceConfiguration() {
        Properties props = new Properties();
        props.setProperty(CommonConstants.DATASOURCE_NAME_PROPERTY, "readOnly");
        props.setProperty(CommonConstants.MAXIMUM_POOL_SIZE_PROPERTY, "5");
        props.setProperty(CommonConstants.MINIMUM_IDLE_PROPERTY, "1");
        props.setProperty(CommonConstants.IDLE_TIMEOUT_PROPERTY, "30000");

        DataSourceConfigurationManager.DataSourceConfiguration config =
                DataSourceConfigurationManager.getConfiguration(props);

        assertEquals("readOnly", config.getDataSourceName());
        assertEquals(5, config.getMaximumPoolSize());
        assertEquals(1, config.getMinimumIdle());
        assertEquals(30000, config.getIdleTimeout());
        assertEquals(CommonConstants.DEFAULT_MAX_LIFETIME, config.getMaxLifetime());
        assertEquals(CommonConstants.DEFAULT_CONNECTION_TIMEOUT, config.getConnectionTimeout());
    }

    @Test
    void testConfigurationCaching() {
        Properties props = new Properties();
        props.setProperty(CommonConstants.DATASOURCE_NAME_PROPERTY, "cached");
        props.setProperty(CommonConstants.MAXIMUM_POOL_SIZE_PROPERTY, "25");

        // First call should create and cache the configuration
        DataSourceConfigurationManager.DataSourceConfiguration config1 =
                DataSourceConfigurationManager.getConfiguration(props);

        // Second call with same properties should return the cached instance
        DataSourceConfigurationManager.DataSourceConfiguration config2 =
                DataSourceConfigurationManager.getConfiguration(props);

        assertSame(config1, config2);
        assertEquals(1, DataSourceConfigurationManager.getCacheSize());
    }

    @Test
    void testConfigurationCacheWithDifferentProperties() {
        Properties props1 = new Properties();
        props1.setProperty(CommonConstants.DATASOURCE_NAME_PROPERTY, "app1");
        props1.setProperty(CommonConstants.MAXIMUM_POOL_SIZE_PROPERTY, "25");

        Properties props2 = new Properties();
        props2.setProperty(CommonConstants.DATASOURCE_NAME_PROPERTY, "app2");
        props2.setProperty(CommonConstants.MAXIMUM_POOL_SIZE_PROPERTY, "30");

        // Different datasource names should create separate cache entries
        DataSourceConfigurationManager.DataSourceConfiguration config1 =
                DataSourceConfigurationManager.getConfiguration(props1);
        DataSourceConfigurationManager.DataSourceConfiguration config2 =
                DataSourceConfigurationManager.getConfiguration(props2);

        assertNotSame(config1, config2);
        assertEquals("app1", config1.getDataSourceName());
        assertEquals("app2", config2.getDataSourceName());
        assertEquals(25, config1.getMaximumPoolSize());
        assertEquals(30, config2.getMaximumPoolSize());
        assertEquals(2, DataSourceConfigurationManager.getCacheSize());
    }

    @Test
    void testInvalidPropertyValues() {
        Properties props = new Properties();
        props.setProperty(CommonConstants.DATASOURCE_NAME_PROPERTY, "testInvalid");
        props.setProperty(CommonConstants.MAXIMUM_POOL_SIZE_PROPERTY, "not-a-number");
        props.setProperty(CommonConstants.CONNECTION_TIMEOUT_PROPERTY, "invalid-long");

        DataSourceConfigurationManager.DataSourceConfiguration config =
                DataSourceConfigurationManager.getConfiguration(props);

        assertEquals("testInvalid", config.getDataSourceName());

        // Invalid values should fall back to defaults
        assertEquals(CommonConstants.DEFAULT_MAXIMUM_POOL_SIZE, config.getMaximumPoolSize());
        assertEquals(CommonConstants.DEFAULT_CONNECTION_TIMEOUT, config.getConnectionTimeout());
    }

    @Test
    void testEmptyDataSourceName() {
        Properties props = new Properties();
        props.setProperty(CommonConstants.DATASOURCE_NAME_PROPERTY, "");
        props.setProperty(CommonConstants.MAXIMUM_POOL_SIZE_PROPERTY, "35");

        DataSourceConfigurationManager.DataSourceConfiguration config =
                DataSourceConfigurationManager.getConfiguration(props);

        // Empty datasource name should be preserved (though not recommended)
        assertEquals("", config.getDataSourceName());
        assertEquals(35, config.getMaximumPoolSize());
    }

    @Test
    void testClearCache() {
        Properties props = new Properties();
        props.setProperty(CommonConstants.DATASOURCE_NAME_PROPERTY, "clearTest");
        props.setProperty(CommonConstants.MAXIMUM_POOL_SIZE_PROPERTY, "15");

        // Create a configuration
        DataSourceConfigurationManager.getConfiguration(props);
        assertEquals(1, DataSourceConfigurationManager.getCacheSize());

        // Clear the cache
        DataSourceConfigurationManager.clearCache();
        assertEquals(0, DataSourceConfigurationManager.getCacheSize());
    }

    @Test
    void testToString() {
        Properties props = new Properties();
        props.setProperty(CommonConstants.DATASOURCE_NAME_PROPERTY, "testString");
        props.setProperty(CommonConstants.MAXIMUM_POOL_SIZE_PROPERTY, "40");
        props.setProperty(CommonConstants.MINIMUM_IDLE_PROPERTY, "8");
        props.setProperty(CommonConstants.CONNECTION_TIMEOUT_PROPERTY, "12000");

        DataSourceConfigurationManager.DataSourceConfiguration config =
                DataSourceConfigurationManager.getConfiguration(props);

        String str = config.toString();
        assertNotNull(str);
        assertTrue(str.contains("testString"));
        assertTrue(str.contains("40"));
        assertTrue(str.contains("8"));
        assertTrue(str.contains("12000"));
    }

    @Test
    void testPoolEnabledProperty() {
        // Test pool enabled (default)
        Properties props1 = new Properties();
        props1.setProperty(CommonConstants.DATASOURCE_NAME_PROPERTY, "pooledDS");

        DataSourceConfigurationManager.DataSourceConfiguration config1 =
                DataSourceConfigurationManager.getConfiguration(props1);

        assertTrue(config1.isPoolEnabled(), "Pool should be enabled by default");

        DataSourceConfigurationManager.clearCache();

        // Test pool explicitly disabled
        Properties props2 = new Properties();
        props2.setProperty(CommonConstants.DATASOURCE_NAME_PROPERTY, "unpooledDS");
        props2.setProperty(CommonConstants.POOL_ENABLED_PROPERTY, "false");

        DataSourceConfigurationManager.DataSourceConfiguration config2 =
                DataSourceConfigurationManager.getConfiguration(props2);

        assertFalse(config2.isPoolEnabled(), "Pool should be disabled when property is false");
    }
}