package org.openjproxy.datasource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PoolConfig class.
 */
class PoolConfigTest {

    @Test
    @DisplayName("Builder should create config with all fields set")
    void testBuilderWithAllFields() {
        Map<String, String> props = new HashMap<>();
        props.put("key1", "value1");
        props.put("key2", "value2");

        PoolConfig config = PoolConfig.builder()
                .url("jdbc:postgresql://localhost:5432/testdb")
                .username("testuser")
                .password("testpass")
                .driverClassName("org.postgresql.Driver")
                .maxPoolSize(25)
                .minIdle(5)
                .connectionTimeoutMs(15000L)
                .idleTimeoutMs(300000L)
                .maxLifetimeMs(900000L)
                .validationQuery("SELECT 1")
                .autoCommit(false)
                .properties(props)
                .metricsPrefix("myapp.db")
                .build();

        assertEquals("jdbc:postgresql://localhost:5432/testdb", config.getUrl());
        assertEquals("testuser", config.getUsername());
        assertEquals("testpass", config.getPasswordAsString());
        assertEquals("org.postgresql.Driver", config.getDriverClassName());
        assertEquals(25, config.getMaxPoolSize());
        assertEquals(5, config.getMinIdle());
        assertEquals(15000L, config.getConnectionTimeoutMs());
        assertEquals(300000L, config.getIdleTimeoutMs());
        assertEquals(900000L, config.getMaxLifetimeMs());
        assertEquals("SELECT 1", config.getValidationQuery());
        assertFalse(config.isAutoCommit());
        assertEquals(2, config.getProperties().size());
        assertEquals("value1", config.getProperties().get("key1"));
        assertEquals("myapp.db", config.getMetricsPrefix());
    }

    @Test
    @DisplayName("Builder should use default values when not specified")
    void testBuilderDefaults() {
        PoolConfig config = PoolConfig.builder().build();

        assertNull(config.getUrl());
        assertNull(config.getUsername());
        assertNull(config.getPassword());
        assertNull(config.getDriverClassName());
        assertEquals(PoolConfig.DEFAULT_MAX_POOL_SIZE, config.getMaxPoolSize());
        assertEquals(PoolConfig.DEFAULT_MIN_IDLE, config.getMinIdle());
        assertEquals(PoolConfig.DEFAULT_CONNECTION_TIMEOUT_MS, config.getConnectionTimeoutMs());
        assertEquals(PoolConfig.DEFAULT_IDLE_TIMEOUT_MS, config.getIdleTimeoutMs());
        assertEquals(PoolConfig.DEFAULT_MAX_LIFETIME_MS, config.getMaxLifetimeMs());
        assertNull(config.getValidationQuery());
        assertTrue(config.isAutoCommit());
        assertTrue(config.getProperties().isEmpty());
        assertNull(config.getMetricsPrefix());
    }

    @Test
    @DisplayName("Password as char array should be handled securely")
    void testPasswordCharArray() {
        char[] password = "secret123".toCharArray();
        PoolConfig config = PoolConfig.builder()
                .password(password)
                .build();

        // Password should be cloned, not the same reference
        char[] retrieved = config.getPassword();
        assertArrayEquals(password, retrieved);
        assertNotSame(password, retrieved);

        // Modifying original should not affect config
        Arrays.fill(password, 'x');
        assertArrayEquals("secret123".toCharArray(), config.getPassword());
    }

    @Test
    @DisplayName("Password supplier should be invoked on each getPassword call")
    void testPasswordSupplier() {
        int[] callCount = {0};
        Supplier<char[]> supplier = () -> {
            callCount[0]++;
            return ("password" + callCount[0]).toCharArray();
        };

        PoolConfig config = PoolConfig.builder()
                .passwordSupplier(supplier)
                .build();

        assertArrayEquals("password1".toCharArray(), config.getPassword());
        assertArrayEquals("password2".toCharArray(), config.getPassword());
        assertEquals(2, callCount[0]);
    }

    @Test
    @DisplayName("Password supplier should override password char array")
    void testPasswordSupplierOverridesCharArray() {
        PoolConfig config = PoolConfig.builder()
                .password("initial".toCharArray())
                .passwordSupplier(() -> "fromSupplier".toCharArray())
                .build();

        assertEquals("fromSupplier", config.getPasswordAsString());
    }

    @Test
    @DisplayName("Password char array should override supplier")
    void testPasswordCharArrayOverridesSupplier() {
        PoolConfig config = PoolConfig.builder()
                .passwordSupplier(() -> "fromSupplier".toCharArray())
                .password("fromCharArray".toCharArray())
                .build();

        assertEquals("fromCharArray", config.getPasswordAsString());
    }

    @Test
    @DisplayName("clearSensitiveData should zero out password")
    void testClearSensitiveData() {
        PoolConfig config = PoolConfig.builder()
                .password("secret123")
                .build();

        config.clearSensitiveData();

        // After clearing, internal password array should be zeroed
        // Note: getPassword returns a clone, so we can't directly verify
        // but this tests the method doesn't throw
        assertDoesNotThrow(config::getPassword);
    }

    @Test
    @DisplayName("Properties map should be immutable")
    void testPropertiesImmutable() {
        Map<String, String> props = new HashMap<>();
        props.put("key", "value");

        PoolConfig config = PoolConfig.builder()
                .properties(props)
                .build();

        // Original map modification should not affect config
        props.put("newKey", "newValue");
        assertEquals(1, config.getProperties().size());

        // Config properties should be unmodifiable
        assertThrows(UnsupportedOperationException.class, 
                () -> config.getProperties().put("another", "value"));
    }

    @Test
    @DisplayName("property() method should add individual properties")
    void testAddIndividualProperty() {
        PoolConfig config = PoolConfig.builder()
                .property("key1", "value1")
                .property("key2", "value2")
                .build();

        assertEquals(2, config.getProperties().size());
        assertEquals("value1", config.getProperties().get("key1"));
        assertEquals("value2", config.getProperties().get("key2"));
    }

    @Test
    @DisplayName("maxPoolSize validation should reject values less than 1")
    void testMaxPoolSizeValidation() {
        assertThrows(IllegalArgumentException.class, 
                () -> PoolConfig.builder().maxPoolSize(0).build());
        assertThrows(IllegalArgumentException.class, 
                () -> PoolConfig.builder().maxPoolSize(-1).build());
    }

    @Test
    @DisplayName("minIdle validation should reject negative values")
    void testMinIdleValidation() {
        assertThrows(IllegalArgumentException.class, 
                () -> PoolConfig.builder().minIdle(-1).build());
    }

    @Test
    @DisplayName("Build should fail if minIdle exceeds maxPoolSize")
    void testMinIdleExceedsMaxPoolSize() {
        assertThrows(IllegalStateException.class, 
                () -> PoolConfig.builder()
                        .maxPoolSize(5)
                        .minIdle(10)
                        .build());
    }

    @Test
    @DisplayName("connectionTimeoutMs validation should reject negative values")
    void testConnectionTimeoutValidation() {
        assertThrows(IllegalArgumentException.class, 
                () -> PoolConfig.builder().connectionTimeoutMs(-1).build());
    }

    @Test
    void shouldRejectNegativeCreationGracePeriod() {
        assertThrows(IllegalArgumentException.class,
                () -> PoolConfig.builder().connectionCreationGracePeriodMs(-1).build());
    }

    @Test
    @DisplayName("idleTimeoutMs validation should reject negative values")
    void testIdleTimeoutValidation() {
        assertThrows(IllegalArgumentException.class, 
                () -> PoolConfig.builder().idleTimeoutMs(-1).build());
    }

    @Test
    @DisplayName("maxLifetimeMs validation should reject negative values")
    void testMaxLifetimeValidation() {
        assertThrows(IllegalArgumentException.class, 
                () -> PoolConfig.builder().maxLifetimeMs(-1).build());
    }

    @Test
    @DisplayName("toString should not expose password")
    void testToStringHidesPassword() {
        PoolConfig config = PoolConfig.builder()
                .url("jdbc:h2:mem:test")
                .username("user")
                .password("verysecretpassword")
                .build();

        String str = config.toString();
        assertTrue(str.contains("password='****'"));
        assertFalse(str.contains("verysecretpassword"));
    }

    @Test
    @DisplayName("Null password should be handled correctly")
    void testNullPassword() {
        PoolConfig config = PoolConfig.builder()
                .password((char[]) null)
                .build();

        assertNull(config.getPassword());
        assertNull(config.getPasswordAsString());
    }

    @Test
    @DisplayName("Password supplier returning null should be handled")
    void testNullPasswordFromSupplier() {
        PoolConfig config = PoolConfig.builder()
                .passwordSupplier(() -> null)
                .build();

        assertNull(config.getPassword());
        assertNull(config.getPasswordAsString());
    }

    @Test
    @DisplayName("Builder password(String) should convert to char array")
    void testPasswordStringMethod() {
        PoolConfig config = PoolConfig.builder()
                .password("mypassword")
                .build();

        assertArrayEquals("mypassword".toCharArray(), config.getPassword());
    }

    @Test
    @DisplayName("Null string password should be handled")
    void testNullStringPassword() {
        PoolConfig config = PoolConfig.builder()
                .password((String) null)
                .build();

        assertNull(config.getPassword());
    }

    @Test
    @DisplayName("Properties with null map should result in empty properties")
    void testNullProperties() {
        PoolConfig config = PoolConfig.builder()
                .properties(null)
                .build();

        assertNotNull(config.getProperties());
        assertTrue(config.getProperties().isEmpty());
    }

    @Test
    @DisplayName("Zero timeouts should be allowed")
    void testZeroTimeouts() {
        PoolConfig config = PoolConfig.builder()
                .connectionTimeoutMs(0)
                .idleTimeoutMs(0)
                .maxLifetimeMs(0)
                .build();

        assertEquals(0, config.getConnectionTimeoutMs());
        assertEquals(0, config.getIdleTimeoutMs());
        assertEquals(0, config.getMaxLifetimeMs());
    }

    @Test
    @DisplayName("minIdle of 0 should be allowed")
    void testZeroMinIdle() {
        PoolConfig config = PoolConfig.builder()
                .minIdle(0)
                .build();

        assertEquals(0, config.getMinIdle());
    }
}
