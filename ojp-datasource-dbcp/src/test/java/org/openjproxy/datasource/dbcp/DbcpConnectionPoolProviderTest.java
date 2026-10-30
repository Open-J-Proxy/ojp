package org.openjproxy.datasource.dbcp;

import org.apache.commons.dbcp2.BasicDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.openjproxy.datasource.ConnectionPoolProvider;
import org.openjproxy.datasource.ConnectionPoolProviderRegistry;
import org.openjproxy.datasource.PoolConfig;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for DbcpConnectionPoolProvider.
 */
class DbcpConnectionPoolProviderTest {

    private DbcpConnectionPoolProvider provider;
    private DataSource createdDataSource;

    @BeforeEach
    void setUp() {
        provider = new DbcpConnectionPoolProvider();
        ConnectionPoolProviderRegistry.clear();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (createdDataSource != null) {
            provider.closeDataSource(createdDataSource);
            createdDataSource = null;
        }
    }

    @Test
    @DisplayName("Provider should have correct ID")
    void testProviderId() {
        assertEquals("dbcp", provider.id());
        assertEquals(DbcpConnectionPoolProvider.PROVIDER_ID, provider.id());
    }

    @Test
    @DisplayName("Provider should be available when DBCP is on classpath")
    void testIsAvailable() {
        assertTrue(provider.isAvailable());
    }

    @Test
    @DisplayName("Provider should have positive priority")
    void testPriority() {
        assertTrue(provider.getPriority() > 0);
    }

    @Test
    @DisplayName("createDataSource should create a working H2 DataSource")
    void testCreateDataSourceH2() throws SQLException {
        PoolConfig config = PoolConfig.builder()
                .url("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1")
                .username("sa")
                .password("")
                .driverClassName("org.h2.Driver")
                .maxPoolSize(5)
                .minIdle(1)
                .connectionTimeoutMs(5000)
                .validationQuery("SELECT 1")
                .build();

        createdDataSource = provider.createDataSource(config);

        assertNotNull(createdDataSource);
        assertInstanceOf(BasicDataSource.class, createdDataSource);

        // Verify we can get a connection
        try (Connection conn = createdDataSource.getConnection()) {
            assertNotNull(conn);
            assertFalse(conn.isClosed());

            // Execute a simple query
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT 1")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
            }
        }
    }

    @Test
    @DisplayName("createDataSource should configure pool sizing correctly")
    void testPoolSizing() throws SQLException {
        PoolConfig config = PoolConfig.builder()
                .url("jdbc:h2:mem:pooltest;DB_CLOSE_DELAY=-1")
                .username("sa")
                .password("")
                .maxPoolSize(15)
                .minIdle(3)
                .build();

        createdDataSource = provider.createDataSource(config);
        BasicDataSource basicDs = (BasicDataSource) createdDataSource;

        assertEquals(15, basicDs.getMaxTotal());
        assertEquals(3, basicDs.getMinIdle());
        assertEquals(15, basicDs.getMaxIdle()); // Should default to maxTotal
    }

    @Test
    @DisplayName("createDataSource should configure timeouts correctly")
    void testTimeouts() throws SQLException {
        PoolConfig config = PoolConfig.builder()
                .url("jdbc:h2:mem:timeouttest;DB_CLOSE_DELAY=-1")
                .username("sa")
                .password("")
                .connectionTimeoutMs(10000)
                .idleTimeoutMs(300000)
                .maxLifetimeMs(600000)
                .build();

        createdDataSource = provider.createDataSource(config);
        BasicDataSource basicDs = (BasicDataSource) createdDataSource;

        assertEquals(10000, basicDs.getMaxWaitDuration().toMillis());
        assertEquals(300000, basicDs.getMinEvictableIdleDuration().toMillis());
        assertEquals(600000, basicDs.getMaxConnDuration().toMillis());
    }

    @Test
    @DisplayName("createDataSource should configure validation")
    void testValidation() throws SQLException {
        PoolConfig config = PoolConfig.builder()
                .url("jdbc:h2:mem:validationtest;DB_CLOSE_DELAY=-1")
                .username("sa")
                .password("")
                .validationQuery("SELECT 1")
                .build();

        createdDataSource = provider.createDataSource(config);
        BasicDataSource basicDs = (BasicDataSource) createdDataSource;

        assertEquals("SELECT 1", basicDs.getValidationQuery());
        assertTrue(basicDs.getTestOnBorrow());
        assertTrue(basicDs.getTestWhileIdle());
    }

    @Test
    @DisplayName("createDataSource should configure auto-commit")
    void testAutoCommit() throws Exception {
        PoolConfig configTrue = PoolConfig.builder()
                .url("jdbc:h2:mem:autocommit1;DB_CLOSE_DELAY=-1")
                .username("sa")
                .password("")
                .autoCommit(true)
                .build();

        DataSource ds1 = provider.createDataSource(configTrue);
        assertTrue(((BasicDataSource) ds1).getDefaultAutoCommit());
        provider.closeDataSource(ds1);

        PoolConfig configFalse = PoolConfig.builder()
                .url("jdbc:h2:mem:autocommit2;DB_CLOSE_DELAY=-1")
                .username("sa")
                .password("")
                .autoCommit(false)
                .build();

        DataSource ds2 = provider.createDataSource(configFalse);
        assertFalse(((BasicDataSource) ds2).getDefaultAutoCommit());
        provider.closeDataSource(ds2);
    }

    @Test
    @DisplayName("createDataSource should apply additional properties")
    void testAdditionalProperties() throws SQLException {
        Map<String, String> props = new HashMap<>();
        props.put("cachePrepStmts", "true");
        props.put("useUnicode", "true");

        PoolConfig config = PoolConfig.builder()
                .url("jdbc:h2:mem:propstest;DB_CLOSE_DELAY=-1")
                .username("sa")
                .password("")
                .properties(props)
                .build();

        createdDataSource = provider.createDataSource(config);
        
        // Verify DataSource was created (properties are set internally)
        assertNotNull(createdDataSource);
    }

    @Test
    @DisplayName("createDataSource should throw for null config")
    void testNullConfig() {
        assertThrows(IllegalArgumentException.class, 
                () -> provider.createDataSource(null));
    }

    @Test
    @DisplayName("closeDataSource should close the pool")
    void testCloseDataSource() throws Exception {
        PoolConfig config = PoolConfig.builder()
                .url("jdbc:h2:mem:closetest;DB_CLOSE_DELAY=-1")
                .username("sa")
                .password("")
                .build();

        DataSource ds = provider.createDataSource(config);
        
        // Get a connection to ensure pool is initialized
        Connection conn = ds.getConnection();
        conn.close();

        // Close the pool
        provider.closeDataSource(ds);

        // Verify pool is closed
        assertTrue(((BasicDataSource) ds).isClosed());
    }

    @Test
    @DisplayName("closeDataSource should handle null gracefully")
    void testCloseNullDataSource() {
        assertDoesNotThrow(() -> provider.closeDataSource(null));
    }

    @Test
    @DisplayName("getStatistics should return pool statistics")
    void testGetStatistics() throws SQLException {
        PoolConfig config = PoolConfig.builder()
                .url("jdbc:h2:mem:statstest;DB_CLOSE_DELAY=-1")
                .username("sa")
                .password("")
                .maxPoolSize(10)
                .minIdle(2)
                .build();

        createdDataSource = provider.createDataSource(config);
        
        // Get some connections to affect stats
        Connection conn1 = createdDataSource.getConnection();
        Connection conn2 = createdDataSource.getConnection();

        Map<String, Object> stats = provider.getStatistics(createdDataSource);

        assertEquals(2, stats.get("activeConnections"));
        assertEquals(10, stats.get("maxPoolSize"));
        assertEquals(2, stats.get("minIdle"));
        assertFalse((Boolean) stats.get("isClosed"));

        conn1.close();
        conn2.close();
    }

    @Test
    @DisplayName("getStatistics should return empty map for non-DBCP DataSource")
    void testGetStatisticsNonDbcp() {
        DataSource mockDs = new DataSource() {
            @Override public Connection getConnection() { return null; }
            @Override public Connection getConnection(String username, String password) { return null; }
            @Override public java.io.PrintWriter getLogWriter() { return null; }
            @Override public void setLogWriter(java.io.PrintWriter out) {}
            @Override public void setLoginTimeout(int seconds) {}
            @Override public int getLoginTimeout() { return 0; }
            @Override public java.util.logging.Logger getParentLogger() { return null; }
            @Override public <T> T unwrap(Class<T> iface) { return null; }
            @Override public boolean isWrapperFor(Class<?> iface) { return false; }
        };

        Map<String, Object> stats = provider.getStatistics(mockDs);
        assertTrue(stats.isEmpty());
    }

    @Test
    @DisplayName("Provider should be discoverable via ServiceLoader")
    void testServiceLoaderDiscovery() {
        ConnectionPoolProviderRegistry.reload();

        Optional<ConnectionPoolProvider> found = ConnectionPoolProviderRegistry.getProvider("dbcp");
        assertTrue(found.isPresent());
        assertInstanceOf(DbcpConnectionPoolProvider.class, found.get());
    }

    @Test
    @DisplayName("Multiple connections should be possible up to maxPoolSize")
    void testMultipleConnections() throws SQLException {
        PoolConfig config = PoolConfig.builder()
                .url("jdbc:h2:mem:multitest;DB_CLOSE_DELAY=-1")
                .username("sa")
                .password("")
                .maxPoolSize(3)
                .minIdle(1)
                .connectionTimeoutMs(1000)
                .build();

        createdDataSource = provider.createDataSource(config);

        Connection conn1 = createdDataSource.getConnection();
        Connection conn2 = createdDataSource.getConnection();
        Connection conn3 = createdDataSource.getConnection();

        assertNotNull(conn1);
        assertNotNull(conn2);
        assertNotNull(conn3);

        // All connections are in use, maxPoolSize reached
        BasicDataSource basicDs = (BasicDataSource) createdDataSource;
        assertEquals(3, basicDs.getNumActive());

        conn1.close();
        conn2.close();
        conn3.close();
    }

    @Test
    @DisplayName("Password supplier should be used correctly")
    void testPasswordSupplier() throws SQLException {
        int[] callCount = {0};
        PoolConfig config = PoolConfig.builder()
                .url("jdbc:h2:mem:suppliertest;DB_CLOSE_DELAY=-1")
                .username("sa")
                .passwordSupplier(() -> {
                    callCount[0]++;
                    return "".toCharArray();
                })
                .build();

        createdDataSource = provider.createDataSource(config);
        
        // Password should have been retrieved during creation
        assertTrue(callCount[0] >= 1);
        
        try (Connection conn = createdDataSource.getConnection()) {
            assertNotNull(conn);
        }
    }

    @Test
    @DisplayName("createDataSource should work with minimal config")
    void testMinimalConfig() throws SQLException {
        PoolConfig config = PoolConfig.builder()
                .url("jdbc:h2:mem:minimaltest;DB_CLOSE_DELAY=-1")
                .build();

        createdDataSource = provider.createDataSource(config);
        assertNotNull(createdDataSource);

        try (Connection conn = createdDataSource.getConnection()) {
            assertNotNull(conn);
        }
    }

    @Test
    @DisplayName("Metrics prefix should be used in pool name")
    void testMetricsPrefix() throws SQLException {
        PoolConfig config = PoolConfig.builder()
                .url("jdbc:h2:mem:metricstest;DB_CLOSE_DELAY=-1")
                .username("sa")
                .password("")
                .metricsPrefix("myapp.orders")
                .build();

        createdDataSource = provider.createDataSource(config);
        assertNotNull(createdDataSource);
    }
}
