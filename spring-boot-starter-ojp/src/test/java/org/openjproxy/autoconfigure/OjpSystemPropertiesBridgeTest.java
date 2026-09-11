package org.openjproxy.autoconfigure;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class OjpSystemPropertiesBridgeTest {

    private static final String PROP_MAX_POOL_SIZE = "ojp.connection.pool.maximumPoolSize";
    private static final String PROP_MIN_IDLE = "ojp.connection.pool.minimumIdle";
    private static final String PROP_CONN_TIMEOUT = "ojp.connection.pool.connectionTimeout";
    private static final String PROP_IDLE_TIMEOUT = "ojp.connection.pool.idleTimeout";
    private static final String PROP_MAX_LIFETIME = "ojp.connection.pool.maxLifetime";
    private static final String PROP_MAX_INBOUND = "ojp.grpc.maxInboundMessageSize";

    @BeforeEach
    void clearProperties() {
        clearTestProperties();
    }

    @AfterEach
    void restoreProperties() {
        clearTestProperties();
    }

    private void clearTestProperties() {
        System.clearProperty(PROP_MAX_POOL_SIZE);
        System.clearProperty(PROP_MIN_IDLE);
        System.clearProperty(PROP_CONN_TIMEOUT);
        System.clearProperty(PROP_IDLE_TIMEOUT);
        System.clearProperty(PROP_MAX_LIFETIME);
        System.clearProperty(PROP_MAX_INBOUND);
    }

    @Test
    void shouldSetAllPoolPropertiesFromEnvironment() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("ojp.connection.pool.maximum-pool-size", "20");
        env.setProperty("ojp.connection.pool.minimum-idle", "5");
        env.setProperty("ojp.connection.pool.connection-timeout", "30000");
        env.setProperty("ojp.connection.pool.idle-timeout", "600000");
        env.setProperty("ojp.connection.pool.max-lifetime", "1800000");
        env.setProperty("ojp.grpc.max-inbound-message-size", "16777216");

        new OjpSystemPropertiesBridge(env).applySystemProperties();

        assertEquals("20", System.getProperty(PROP_MAX_POOL_SIZE));
        assertEquals("5", System.getProperty(PROP_MIN_IDLE));
        assertEquals("30000", System.getProperty(PROP_CONN_TIMEOUT));
        assertEquals("600000", System.getProperty(PROP_IDLE_TIMEOUT));
        assertEquals("1800000", System.getProperty(PROP_MAX_LIFETIME));
        assertEquals("16777216", System.getProperty(PROP_MAX_INBOUND));
    }

    @Test
    void shouldForwardAlreadyCamelCaseKeys() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("ojp.connection.pool.maximumPoolSize", "25");

        new OjpSystemPropertiesBridge(env).applySystemProperties();

        assertEquals("25", System.getProperty(PROP_MAX_POOL_SIZE));
    }

    @Test
    void shouldNotSetPropertiesWhenNoOjpPropertiesPresent() {
        MockEnvironment env = new MockEnvironment();
        // No ojp.* properties set

        new OjpSystemPropertiesBridge(env).applySystemProperties();

        assertNull(System.getProperty(PROP_MAX_POOL_SIZE));
        assertNull(System.getProperty(PROP_MAX_INBOUND));
    }

    @Test
    void shouldNotOverrideExistingSystemProperties() {
        System.setProperty(PROP_MAX_POOL_SIZE, "50");

        MockEnvironment env = new MockEnvironment();
        env.setProperty("ojp.connection.pool.maximum-pool-size", "10");

        new OjpSystemPropertiesBridge(env).applySystemProperties();

        // Existing system property should not be overwritten
        assertEquals("50", System.getProperty(PROP_MAX_POOL_SIZE));
    }

    @Test
    void shouldOnlySetExplicitlyConfiguredProperties() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("ojp.connection.pool.maximum-pool-size", "25");
        // minimumIdle intentionally not set

        new OjpSystemPropertiesBridge(env).applySystemProperties();

        assertEquals("25", System.getProperty(PROP_MAX_POOL_SIZE));
        assertNull(System.getProperty(PROP_MIN_IDLE));
    }

    @Test
    void kebabToCamelCaseShouldConvertCorrectly() {
        assertEquals("ojp.connection.pool.maximumPoolSize",
                OjpSystemPropertiesBridge.kebabToCamelCase("ojp.connection.pool.maximum-pool-size"));
        assertEquals("ojp.grpc.maxInboundMessageSize",
                OjpSystemPropertiesBridge.kebabToCamelCase("ojp.grpc.max-inbound-message-size"));
        assertEquals("ojp.connection.pool.minimumIdle",
                OjpSystemPropertiesBridge.kebabToCamelCase("ojp.connection.pool.minimum-idle"));
    }

    @Test
    void kebabToCamelCaseShouldLeaveAlreadyCamelCaseUnchanged() {
        assertEquals("ojp.connection.pool.maximumPoolSize",
                OjpSystemPropertiesBridge.kebabToCamelCase("ojp.connection.pool.maximumPoolSize"));
    }

    // ---- toSystemPropertyKey ------------------------------------------------

    @Test
    void toSystemPropertyKeyShouldHandleDefaultPoolKeys() {
        assertEquals("ojp.connection.pool.maximumPoolSize",
                OjpSystemPropertiesBridge.toSystemPropertyKey("ojp.connection.pool.maximum-pool-size"));
        assertEquals("ojp.grpc.maxInboundMessageSize",
                OjpSystemPropertiesBridge.toSystemPropertyKey("ojp.grpc.max-inbound-message-size"));
    }

    @Test
    void toSystemPropertyKeyShouldHandleNamedPoolKebabKeys() {
        assertEquals("high-performance.ojp.connection.pool.maximumPoolSize",
                OjpSystemPropertiesBridge.toSystemPropertyKey(
                        "high-performance.ojp.connection.pool.maximum-pool-size"));
        assertEquals("postgres.ojp.connection.pool.minimumIdle",
                OjpSystemPropertiesBridge.toSystemPropertyKey(
                        "postgres.ojp.connection.pool.minimum-idle"));
        assertEquals("postgres2.ojp.grpc.maxInboundMessageSize",
                OjpSystemPropertiesBridge.toSystemPropertyKey(
                        "postgres2.ojp.grpc.max-inbound-message-size"));
    }

    @Test
    void toSystemPropertyKeyShouldPreserveHyphensInPoolName() {
        // Pool name contains hyphens – must NOT be camelCased
        assertEquals("high-performance.ojp.connection.pool.maximumPoolSize",
                OjpSystemPropertiesBridge.toSystemPropertyKey(
                        "high-performance.ojp.connection.pool.maximumPoolSize"));
    }

    @Test
    void toSystemPropertyKeyShouldReturnNullForUnrelatedKeys() {
        assertNull(OjpSystemPropertiesBridge.toSystemPropertyKey("spring.datasource.url"));
        assertNull(OjpSystemPropertiesBridge.toSystemPropertyKey("server.port"));
        assertNull(OjpSystemPropertiesBridge.toSystemPropertyKey(""));
    }

    // ---- named-pool end-to-end forwarding -----------------------------------

    @Test
    void shouldSetNamedPoolPropertiesFromEnvironment() {
        String propMaxPool = "high-performance.ojp.connection.pool.maximumPoolSize";
        String propMinIdle  = "postgres.ojp.connection.pool.minimumIdle";
        String propMaxInbound = "postgres2.ojp.grpc.maxInboundMessageSize";
        System.clearProperty(propMaxPool);
        System.clearProperty(propMinIdle);
        System.clearProperty(propMaxInbound);
        try {
            MockEnvironment env = new MockEnvironment();
            env.setProperty("high-performance.ojp.connection.pool.maximum-pool-size", "50");
            env.setProperty("postgres.ojp.connection.pool.minimum-idle", "3");
            env.setProperty("postgres2.ojp.grpc.max-inbound-message-size", "8388608");

            new OjpSystemPropertiesBridge(env).applySystemProperties();

            assertEquals("50",      System.getProperty(propMaxPool));
            assertEquals("3",       System.getProperty(propMinIdle));
            assertEquals("8388608", System.getProperty(propMaxInbound));
        } finally {
            System.clearProperty(propMaxPool);
            System.clearProperty(propMinIdle);
            System.clearProperty(propMaxInbound);
        }
    }

    @Test
    void shouldNotOverrideExistingSystemPropertiesForNamedPool() {
        String prop = "postgres.ojp.connection.pool.maximumPoolSize";
        System.setProperty(prop, "99");
        try {
            MockEnvironment env = new MockEnvironment();
            env.setProperty("postgres.ojp.connection.pool.maximum-pool-size", "10");

            new OjpSystemPropertiesBridge(env).applySystemProperties();

            assertEquals("99", System.getProperty(prop));
        } finally {
            System.clearProperty(prop);
        }
    }

    @Test
    void shouldSetBothDefaultAndNamedPoolPropertiesFromSameEnvironment() {
        String defaultProp = "ojp.connection.pool.maximumPoolSize";
        String namedProp   = "postgres.ojp.connection.pool.maximumPoolSize";
        System.clearProperty(defaultProp);
        System.clearProperty(namedProp);
        try {
            MockEnvironment env = new MockEnvironment();
            env.setProperty("ojp.connection.pool.maximum-pool-size", "20");
            env.setProperty("postgres.ojp.connection.pool.maximum-pool-size", "30");

            new OjpSystemPropertiesBridge(env).applySystemProperties();

            assertEquals("20", System.getProperty(defaultProp));
            assertEquals("30", System.getProperty(namedProp));
        } finally {
            System.clearProperty(defaultProp);
            System.clearProperty(namedProp);
        }
    }

    // ---- read/write splitting properties ------------------------------------

    @Test
    void toSystemPropertyKeyShouldHandleReadWriteSplittingKeys() {
        // camelCase property names pass through unchanged
        assertEquals("primary.ojp.readwrite.enabled",
                OjpSystemPropertiesBridge.toSystemPropertyKey("primary.ojp.readwrite.enabled"));
        assertEquals("primary.ojp.readwrite.role",
                OjpSystemPropertiesBridge.toSystemPropertyKey("primary.ojp.readwrite.role"));
        assertEquals("replica1.ojp.readwrite.role",
                OjpSystemPropertiesBridge.toSystemPropertyKey("replica1.ojp.readwrite.role"));
        assertEquals("replica1.ojp.readwrite.primary",
                OjpSystemPropertiesBridge.toSystemPropertyKey("replica1.ojp.readwrite.primary"));
        assertEquals("replica1.ojp.connection.url",
                OjpSystemPropertiesBridge.toSystemPropertyKey("replica1.ojp.connection.url"));
    }

    @Test
    void toSystemPropertyKeyShouldConvertKebabCaseReadWriteSplittingKeys() {
        // kebab-case variants (as written in application.properties) are converted to camelCase
        assertEquals("primary.ojp.readwrite.replicaSelectionStrategy",
                OjpSystemPropertiesBridge.toSystemPropertyKey(
                        "primary.ojp.readwrite.replica-selection-strategy"));
        assertEquals("primary.ojp.readwrite.stickySessionSeconds",
                OjpSystemPropertiesBridge.toSystemPropertyKey(
                        "primary.ojp.readwrite.sticky-session-seconds"));
        assertEquals("primary.ojp.readwrite.replicaFailoverToPrimary",
                OjpSystemPropertiesBridge.toSystemPropertyKey(
                        "primary.ojp.readwrite.replica-failover-to-primary"));
    }

    @Test
    void shouldForwardReadWriteSplittingPropertiesAsSystemProperties() {
        String propEnabled   = "primary.ojp.readwrite.enabled";
        String propRole      = "primary.ojp.readwrite.role";
        String propStrategy  = "primary.ojp.readwrite.replicaSelectionStrategy";
        String propSticky    = "primary.ojp.readwrite.stickySessionSeconds";
        String propR1Role    = "replica1.ojp.readwrite.role";
        String propR1Primary = "replica1.ojp.readwrite.primary";
        String propR1Url     = "replica1.ojp.connection.url";
        System.clearProperty(propEnabled);
        System.clearProperty(propRole);
        System.clearProperty(propStrategy);
        System.clearProperty(propSticky);
        System.clearProperty(propR1Role);
        System.clearProperty(propR1Primary);
        System.clearProperty(propR1Url);
        try {
            MockEnvironment env = new MockEnvironment();
            env.setProperty("primary.ojp.readwrite.enabled", "true");
            env.setProperty("primary.ojp.readwrite.role", "primary");
            env.setProperty("primary.ojp.readwrite.replica-selection-strategy", "ROUND_ROBIN");
            env.setProperty("primary.ojp.readwrite.sticky-session-seconds", "5");
            env.setProperty("replica1.ojp.readwrite.role", "replica");
            env.setProperty("replica1.ojp.readwrite.primary", "primary");
            env.setProperty("replica1.ojp.connection.url", "jdbc:postgresql://replica.host/db");

            new OjpSystemPropertiesBridge(env).applySystemProperties();

            assertEquals("true",    System.getProperty(propEnabled));
            assertEquals("primary", System.getProperty(propRole));
            assertEquals("ROUND_ROBIN", System.getProperty(propStrategy));
            assertEquals("5",       System.getProperty(propSticky));
            assertEquals("replica", System.getProperty(propR1Role));
            assertEquals("primary", System.getProperty(propR1Primary));
            assertEquals("jdbc:postgresql://replica.host/db", System.getProperty(propR1Url));
        } finally {
            System.clearProperty(propEnabled);
            System.clearProperty(propRole);
            System.clearProperty(propStrategy);
            System.clearProperty(propSticky);
            System.clearProperty(propR1Role);
            System.clearProperty(propR1Primary);
            System.clearProperty(propR1Url);
        }
    }
}
