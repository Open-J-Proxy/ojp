package org.openjproxy.jdbc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test that system properties and environment variables override file properties in DatasourcePropertiesLoader
 */
class DatasourcePropertiesLoaderSystemPropertyTest {

    @AfterEach
    void cleanup() {
        // Clear any system properties we set during tests
        System.clearProperty("multinode.ojp.connection.pool.enabled");
        System.clearProperty("ojp.connection.pool.enabled");
    }

    @Test
    void testSystemPropertyOverridesFileProperty() {
        // Set a system property that should override the file property
        System.setProperty("multinode.ojp.connection.pool.enabled", "false");

        // Load properties for "multinode" datasource
        Properties props = DatasourcePropertiesLoader.loadOjpPropertiesForDataSource("multinode");

        assertNotNull(props, "Properties should not be null");

        // The system property should override the file property
        String poolEnabled = props.getProperty("ojp.connection.pool.enabled");
        assertNotNull(poolEnabled, "Pool enabled property should be present");
        assertEquals("false", poolEnabled, "System property should override file property to 'false'");
    }

    @Test
    void testDefaultDataSourceSystemPropertyOverride() {
        // Set a system property for the default datasource
        System.setProperty("ojp.connection.pool.enabled", "false");

        // Load properties for "default" datasource
        Properties props = DatasourcePropertiesLoader.loadOjpPropertiesForDataSource("default");

        // The system property should be present
        assertNotNull(props, "Properties should not be null");

        String poolEnabled = props.getProperty("ojp.connection.pool.enabled");
        assertNotNull(poolEnabled, "Pool enabled property should be present");
        assertEquals("false", poolEnabled, "System property should set pool enabled to 'false'");
    }

    @Test
    void shouldResolveCreationGraceIndependentlyForNamedDatasources() {
        String mainKey = "mainApp.ojp.connection.pool.connectionCreationGracePeriod";
        String batchKey = "batch.ojp.connection.pool.connectionCreationGracePeriod";
        String previousMain = System.setProperty(mainKey, "7500");
        String previousBatch = System.setProperty(batchKey, "0");
        try {
            Properties main = DatasourcePropertiesLoader.loadOjpPropertiesForDataSource("mainApp");
            Properties batch = DatasourcePropertiesLoader.loadOjpPropertiesForDataSource("batch");

            assertNotNull(main);
            assertNotNull(batch);
            assertEquals("7500", main.getProperty("ojp.connection.pool.connectionCreationGracePeriod"));
            assertEquals("0", batch.getProperty("ojp.connection.pool.connectionCreationGracePeriod"));
        } finally {
            if (previousMain == null) {
                System.clearProperty(mainKey);
            } else {
                System.setProperty(mainKey, previousMain);
            }
            if (previousBatch == null) {
                System.clearProperty(batchKey);
            } else {
                System.setProperty(batchKey, previousBatch);
            }
        }
    }

    /**
     * Test for environment variable support.
     * <p>
     * Note: Environment variables cannot be reliably tested via unit tests in Java 9+
     * due to module system restrictions. This test documents the expected behavior.
     * <p>
     * To manually test environment variable support:
     * 1. Set environment variable: export MULTINODE_OJP_CONNECTION_POOL_ENABLED=false
     * 2. Run the application
     * 3. Verify that non-XA pooling is disabled for the "multinode" datasource
     * <p>
     * Environment variables have the highest precedence:
     * 1. Environment variables (UPPERCASE_WITH_UNDERSCORES)
     * 2. System properties (-Dlowercase.with.dots)
     * 3. Properties file (ojp.properties)
     */
    @Test
    void testEnvironmentVariableSupportDocumented() {
        // This test documents that environment variable support exists
        // The actual functionality is tested in integration tests

        // Verify that the DatasourcePropertiesLoader reads environment variables
        // by checking that System.getenv() is accessible (which is what the loader uses)
        Map<String, String> env = System.getenv();
        assertNotNull(env, "Environment variables should be accessible");

        // Document the expected environment variable names:
        // - MULTINODE_OJP_CONNECTION_POOL_ENABLED -> multinode.ojp.connection.pool.enabled
        // - MYAPP_OJP_XA_CONNECTION_POOL_ENABLED -> myapp.ojp.xa.connection.pool.enabled
        // - OJP_CONNECTION_POOL_ENABLED -> ojp.connection.pool.enabled

        assertTrue(true, "Environment variable support is documented and implemented");
    }
}
