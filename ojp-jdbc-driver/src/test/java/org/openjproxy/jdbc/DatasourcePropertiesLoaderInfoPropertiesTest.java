package org.openjproxy.jdbc;

import org.junit.jupiter.api.Test;
import org.openjproxy.constants.CommonConstants;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for {@link DatasourcePropertiesLoader#applyInfoProperties}.
 *
 * <p>Verifies that OJP pool properties passed via the JDBC {@code info} argument to
 * {@code DriverManager.getConnection(url, info)} are merged on top of file/system/env settings
 * with the highest priority.
 */
class DatasourcePropertiesLoaderInfoPropertiesTest {

    // -------------------------------------------------------------------------
    // Null / empty guard cases
    // -------------------------------------------------------------------------

    @Test
    void shouldReturnNullWhenBothBaseAndInfoAreNull() {
        Properties result = DatasourcePropertiesLoader.applyInfoProperties(null, null, "default");

        assertNull(result, "Should return null when both base and info are null");
    }

    @Test
    void shouldReturnNullWhenBothBaseAndInfoAreEmpty() {
        Properties result = DatasourcePropertiesLoader.applyInfoProperties(
                new Properties(), new Properties(), "default");

        assertNull(result, "Should return null when both base and info are empty");
    }

    @Test
    void shouldReturnBaseUnchangedWhenInfoIsNull() {
        Properties base = new Properties();
        base.setProperty("ojp.connection.pool.maximumPoolSize", "10");

        Properties result = DatasourcePropertiesLoader.applyInfoProperties(base, null, "default");

        assertNotNull(result);
        assertEquals("10", result.getProperty("ojp.connection.pool.maximumPoolSize"));
    }

    @Test
    void shouldReturnBaseUnchangedWhenInfoIsEmpty() {
        Properties base = new Properties();
        base.setProperty("ojp.connection.pool.maximumPoolSize", "10");

        Properties result = DatasourcePropertiesLoader.applyInfoProperties(
                base, new Properties(), "default");

        assertNotNull(result);
        assertEquals("10", result.getProperty("ojp.connection.pool.maximumPoolSize"));
    }

    // -------------------------------------------------------------------------
    // Info-only cases (no base)
    // -------------------------------------------------------------------------

    @Test
    void shouldCreatePropertiesFromInfoWhenBaseIsNull() {
        Properties info = new Properties();
        info.setProperty("ojp.connection.pool.maximumPoolSize", "25");
        info.setProperty("ojp.connection.pool.minimumIdle", "5");

        Properties result = DatasourcePropertiesLoader.applyInfoProperties(null, info, "default");

        assertNotNull(result);
        assertEquals("25", result.getProperty("ojp.connection.pool.maximumPoolSize"));
        assertEquals("5", result.getProperty("ojp.connection.pool.minimumIdle"));
    }

    @Test
    void shouldSetDatasourceNameWhenOnlyInfoProperties() {
        Properties info = new Properties();
        info.setProperty("ojp.connection.pool.maximumPoolSize", "15");

        Properties result = DatasourcePropertiesLoader.applyInfoProperties(null, info, "myApp");

        assertNotNull(result);
        assertEquals("myApp", result.getProperty(CommonConstants.DATASOURCE_NAME_PROPERTY));
    }

    @Test
    void shouldNotOverrideDatasourceNameAlreadyPresentInBase() {
        Properties base = new Properties();
        base.setProperty(CommonConstants.DATASOURCE_NAME_PROPERTY, "originalDs");
        base.setProperty("ojp.connection.pool.maximumPoolSize", "10");

        Properties info = new Properties();
        info.setProperty("ojp.connection.pool.maximumPoolSize", "99");

        Properties result = DatasourcePropertiesLoader.applyInfoProperties(base, info, "anotherDs");

        assertNotNull(result);
        // Datasource name from base must be preserved
        assertEquals("originalDs", result.getProperty(CommonConstants.DATASOURCE_NAME_PROPERTY));
        assertEquals("99", result.getProperty("ojp.connection.pool.maximumPoolSize"));
    }

    // -------------------------------------------------------------------------
    // Priority: info overrides base
    // -------------------------------------------------------------------------

    @Test
    void shouldOverrideFilePropertyWithInfoProperty() {
        Properties base = new Properties();
        base.setProperty("ojp.connection.pool.maximumPoolSize", "10");
        base.setProperty("ojp.connection.pool.minimumIdle", "2");

        Properties info = new Properties();
        info.setProperty("ojp.connection.pool.maximumPoolSize", "50");

        Properties result = DatasourcePropertiesLoader.applyInfoProperties(base, info, "default");

        assertNotNull(result);
        assertEquals("50", result.getProperty("ojp.connection.pool.maximumPoolSize"),
                "Info property should override base (file) property");
        // Property not present in info should be kept from base
        assertEquals("2", result.getProperty("ojp.connection.pool.minimumIdle"),
                "Base property not overridden by info should be preserved");
    }

    @Test
    void shouldOverrideAllSuppliedPoolPropertiesWithInfoValues() {
        Properties base = new Properties();
        base.setProperty("ojp.connection.pool.maximumPoolSize", "10");
        base.setProperty("ojp.connection.pool.minimumIdle", "2");
        base.setProperty("ojp.connection.pool.connectionTimeout", "10000");

        Properties info = new Properties();
        info.setProperty("ojp.connection.pool.maximumPoolSize", "20");
        info.setProperty("ojp.connection.pool.minimumIdle", "5");
        info.setProperty("ojp.connection.pool.connectionTimeout", "30000");

        Properties result = DatasourcePropertiesLoader.applyInfoProperties(base, info, "default");

        assertNotNull(result);
        assertEquals("20", result.getProperty("ojp.connection.pool.maximumPoolSize"));
        assertEquals("5", result.getProperty("ojp.connection.pool.minimumIdle"));
        assertEquals("30000", result.getProperty("ojp.connection.pool.connectionTimeout"));
    }

    // -------------------------------------------------------------------------
    // XA properties
    // -------------------------------------------------------------------------

    @Test
    void shouldApplyXaPropertiesFromInfo() {
        Properties info = new Properties();
        info.setProperty("ojp.xa.connection.pool.maxTotal", "40");
        info.setProperty("ojp.xa.connection.pool.minIdle", "8");

        Properties result = DatasourcePropertiesLoader.applyInfoProperties(null, info, "default");

        assertNotNull(result);
        assertEquals("40", result.getProperty("ojp.xa.connection.pool.maxTotal"));
        assertEquals("8", result.getProperty("ojp.xa.connection.pool.minIdle"));
    }

    // -------------------------------------------------------------------------
    // Key filtering: non-OJP keys are ignored
    // -------------------------------------------------------------------------

    @Test
    void shouldIgnoreNonOjpKeysFromInfo() {
        Properties info = new Properties();
        info.setProperty("user", "alice");
        info.setProperty("password", "secret");
        info.setProperty("ssl", "true");
        info.setProperty("ojp.connection.pool.maximumPoolSize", "15");

        Properties result = DatasourcePropertiesLoader.applyInfoProperties(null, info, "default");

        assertNotNull(result);
        assertNull(result.getProperty("user"), "user should not be copied from info");
        assertNull(result.getProperty("password"), "password should not be copied from info");
        assertNull(result.getProperty("ssl"), "database-specific keys should not be copied from info");
        assertEquals("15", result.getProperty("ojp.connection.pool.maximumPoolSize"),
                "OJP pool key should be copied from info");
    }

    @Test
    void shouldReturnNullWhenInfoContainsOnlyNonOjpKeys() {
        Properties base = null;
        Properties info = new Properties();
        info.setProperty("user", "alice");
        info.setProperty("password", "secret");

        Properties result = DatasourcePropertiesLoader.applyInfoProperties(base, info, "default");

        assertNull(result, "Should return null when info has no OJP-relevant keys");
    }

    // -------------------------------------------------------------------------
    // Mixed scenarios
    // -------------------------------------------------------------------------

    @Test
    void shouldMergePoolAndXaPropertiesFromInfo() {
        Properties base = new Properties();
        base.setProperty("ojp.connection.pool.maximumPoolSize", "10");
        base.setProperty(CommonConstants.DATASOURCE_NAME_PROPERTY, "myApp");

        Properties info = new Properties();
        info.setProperty("ojp.connection.pool.maximumPoolSize", "20");
        info.setProperty("ojp.xa.connection.pool.maxTotal", "40");
        info.setProperty("user", "alice");   // should be ignored

        Properties result = DatasourcePropertiesLoader.applyInfoProperties(base, info, "myApp");

        assertNotNull(result);
        assertEquals("20", result.getProperty("ojp.connection.pool.maximumPoolSize"),
                "Pool size should be overridden by info");
        assertEquals("40", result.getProperty("ojp.xa.connection.pool.maxTotal"),
                "XA property from info should be added");
        assertNull(result.getProperty("user"), "user key should not be in result");
        assertEquals("myApp", result.getProperty(CommonConstants.DATASOURCE_NAME_PROPERTY),
                "Datasource name from base should be preserved");
    }

    @Test
    void shouldApplyJdbcClosePropertyFromInfo() {
        Properties info = new Properties();
        info.setProperty(CommonConstants.JDBC_CLOSE_SYNC_PROPERTY, "true");

        Properties result = DatasourcePropertiesLoader.applyInfoProperties(null, info, "default");

        assertNotNull(result);
        assertEquals("true", result.getProperty(CommonConstants.JDBC_CLOSE_SYNC_PROPERTY));
    }
}
