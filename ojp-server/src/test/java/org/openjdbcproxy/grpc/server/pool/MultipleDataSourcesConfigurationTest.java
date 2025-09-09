package org.openjdbcproxy.grpc.server.pool;

import com.openjdbcproxy.grpc.ConnectionDetails;
import com.zaxxer.hikari.HikariConfig;
import org.junit.jupiter.api.Test;
import org.openjdbcproxy.constants.CommonConstants;
import org.openjdbcproxy.grpc.SerializationHandler;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for the multiple data sources configuration in ConnectionPoolConfigurer.
 */
public class MultipleDataSourcesConfigurationTest {

    @Test
    public void testDefaultConfiguration() {
        // Test that default configuration still works
        Properties props = new Properties();
        props.setProperty("ojp.connection.pool.maximumPoolSize", "15");
        props.setProperty("ojp.connection.pool.minimumIdle", "3");

        ConnectionDetails connectionDetails = createConnectionDetails(
                "jdbc:postgresql://localhost:5432/testdb", "testuser", props);

        HikariConfig config = new HikariConfig();
        ConnectionPoolConfigurer.configureHikariPool(config, connectionDetails);

        assertEquals(15, config.getMaximumPoolSize());
        assertEquals(3, config.getMinimumIdle());
        assertEquals(CommonConstants.DEFAULT_IDLE_TIMEOUT, config.getIdleTimeout());
    }

    @Test
    public void testDatabaseSpecificConfiguration() {
        // Test database-specific configuration takes precedence over default
        Properties props = new Properties();
        props.setProperty("ojp.connection.pool.maximumPoolSize", "10"); // default
        props.setProperty("testdb.ojp.connection.pool.maximumPoolSize", "25"); // database-specific
        props.setProperty("testdb.ojp.connection.pool.minimumIdle", "5"); // database-specific

        ConnectionDetails connectionDetails = createConnectionDetails(
                "jdbc:postgresql://localhost:5432/testdb", "testuser", props);

        HikariConfig config = new HikariConfig();
        ConnectionPoolConfigurer.configureHikariPool(config, connectionDetails);

        assertEquals(25, config.getMaximumPoolSize()); // database-specific value
        assertEquals(5, config.getMinimumIdle()); // database-specific value
        assertEquals(CommonConstants.DEFAULT_IDLE_TIMEOUT, config.getIdleTimeout()); // falls back to default
    }

    @Test
    public void testDatabaseUserSpecificConfiguration() {
        // Test database+user specific configuration has highest precedence
        Properties props = new Properties();
        props.setProperty("ojp.connection.pool.maximumPoolSize", "10"); // default
        props.setProperty("testdb.ojp.connection.pool.maximumPoolSize", "20"); // database-specific
        props.setProperty("testdb.testuser.ojp.connection.pool.maximumPoolSize", "30"); // database+user specific
        props.setProperty("testdb.testuser.ojp.connection.pool.connectionTimeout", "15000"); // database+user specific

        ConnectionDetails connectionDetails = createConnectionDetails(
                "jdbc:postgresql://localhost:5432/testdb", "testuser", props);

        HikariConfig config = new HikariConfig();
        ConnectionPoolConfigurer.configureHikariPool(config, connectionDetails);

        assertEquals(30, config.getMaximumPoolSize()); // database+user specific value
        assertEquals(15000, config.getConnectionTimeout()); // database+user specific value
        assertEquals(CommonConstants.DEFAULT_MINIMUM_IDLE, config.getMinimumIdle()); // falls back to default
    }

    @Test
    public void testHierarchicalFallback() {
        // Test that fallback works properly: db+user > db > global
        Properties props = new Properties();
        props.setProperty("ojp.connection.pool.maximumPoolSize", "10"); // global default
        props.setProperty("ojp.connection.pool.minimumIdle", "2"); // global default
        props.setProperty("testdb.ojp.connection.pool.maximumPoolSize", "20"); // database-specific
        props.setProperty("testdb.testuser.ojp.connection.pool.idleTimeout", "300000"); // database+user specific

        ConnectionDetails connectionDetails = createConnectionDetails(
                "jdbc:postgresql://localhost:5432/testdb", "testuser", props);

        HikariConfig config = new HikariConfig();
        ConnectionPoolConfigurer.configureHikariPool(config, connectionDetails);

        assertEquals(20, config.getMaximumPoolSize()); // database-specific (no db+user override)
        assertEquals(2, config.getMinimumIdle()); // global default (no db or db+user override)
        assertEquals(300000, config.getIdleTimeout()); // database+user specific
    }

    @Test
    public void testInvalidPrefixFailsFast() {
        // Test that invalid prefixes cause immediate failure
        Properties props = new Properties();
        props.setProperty("ojp.connection.pool.maximumPoolSize", "10");
        props.setProperty("wrongdb.ojp.connection.pool.maximumPoolSize", "20"); // wrong database name

        ConnectionDetails connectionDetails = createConnectionDetails(
                "jdbc:postgresql://localhost:5432/testdb", "testuser", props);

        HikariConfig config = new HikariConfig();
        
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            ConnectionPoolConfigurer.configureHikariPool(config, connectionDetails);
        });
        
        assertTrue(exception.getMessage().contains("Invalid configuration prefix"));
        assertTrue(exception.getMessage().contains("wrongdb"));
    }

    @Test
    public void testUsernameOnlyPrefixFailsFast() {
        // Test that username-only prefixes are rejected
        Properties props = new Properties();
        props.setProperty("ojp.connection.pool.maximumPoolSize", "10");
        props.setProperty("testuser.ojp.connection.pool.maximumPoolSize", "20"); // username-only prefix

        ConnectionDetails connectionDetails = createConnectionDetails(
                "jdbc:postgresql://localhost:5432/testdb", "testuser", props);

        HikariConfig config = new HikariConfig();
        
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            ConnectionPoolConfigurer.configureHikariPool(config, connectionDetails);
        });
        
        assertTrue(exception.getMessage().contains("Username-only prefix"));
        assertTrue(exception.getMessage().contains("testuser"));
    }

    @Test
    public void testMultipleDatabaseConfigurations() {
        // Test that only matching database configurations are used
        Properties props = new Properties();
        props.setProperty("ojp.connection.pool.maximumPoolSize", "10"); // global default
        props.setProperty("testdb.ojp.connection.pool.maximumPoolSize", "30"); // for testdb - should be used
        props.setProperty("testdb.testuser.ojp.connection.pool.minimumIdle", "7"); // for testdb+testuser - should be used

        ConnectionDetails connectionDetails = createConnectionDetails(
                "jdbc:postgresql://localhost:5432/testdb", "testuser", props);

        HikariConfig config = new HikariConfig();
        ConnectionPoolConfigurer.configureHikariPool(config, connectionDetails);

        assertEquals(30, config.getMaximumPoolSize()); // testdb-specific value
        assertEquals(7, config.getMinimumIdle()); // testdb+testuser specific value
    }

    @Test
    public void testMultipleInvalidPrefixesFailsFast() {
        // Test that multiple invalid prefixes cause immediate failure
        Properties props = new Properties();
        props.setProperty("ojp.connection.pool.maximumPoolSize", "10");
        props.setProperty("db1.ojp.connection.pool.maximumPoolSize", "15"); // invalid for testdb
        props.setProperty("db2.ojp.connection.pool.maximumPoolSize", "25"); // invalid for testdb
        props.setProperty("testdb.ojp.connection.pool.maximumPoolSize", "30"); // valid for testdb

        ConnectionDetails connectionDetails = createConnectionDetails(
                "jdbc:postgresql://localhost:5432/testdb", "testuser", props);

        HikariConfig config = new HikariConfig();
        
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            ConnectionPoolConfigurer.configureHikariPool(config, connectionDetails);
        });
        
        assertTrue(exception.getMessage().contains("Invalid configuration prefix"));
        assertTrue(exception.getMessage().contains("db1"));
        assertTrue(exception.getMessage().contains("db2"));
    }

    @Test
    public void testDifferentDatabaseTypes() {
        // Test with MySQL database
        Properties props = new Properties();
        props.setProperty("myapp.ojp.connection.pool.maximumPoolSize", "40");
        props.setProperty("myapp.admin.ojp.connection.pool.minimumIdle", "8");

        ConnectionDetails connectionDetails = createConnectionDetails(
                "jdbc:mysql://localhost:3306/myapp", "admin", props);

        HikariConfig config = new HikariConfig();
        ConnectionPoolConfigurer.configureHikariPool(config, connectionDetails);

        assertEquals(40, config.getMaximumPoolSize()); // database-specific
        assertEquals(8, config.getMinimumIdle()); // database+user specific
    }

    @Test
    public void testOracleDatabase() {
        // Test with Oracle database (SID format)
        Properties props = new Properties();
        props.setProperty("XE.ojp.connection.pool.maximumPoolSize", "35");
        props.setProperty("XE.oracle.ojp.connection.pool.connectionTimeout", "20000");

        ConnectionDetails connectionDetails = createConnectionDetails(
                "jdbc:oracle:thin:@localhost:1521:XE", "oracle", props);

        HikariConfig config = new HikariConfig();
        ConnectionPoolConfigurer.configureHikariPool(config, connectionDetails);

        assertEquals(35, config.getMaximumPoolSize()); // database-specific
        assertEquals(20000, config.getConnectionTimeout()); // database+user specific
    }

    @Test
    public void testUnextractableDatabaseName() {
        // Test behavior when database name cannot be extracted
        Properties props = new Properties();
        props.setProperty("ojp.connection.pool.maximumPoolSize", "12");

        ConnectionDetails connectionDetails = createConnectionDetails(
                "jdbc:unsupported://localhost/somedb", "testuser", props);

        HikariConfig config = new HikariConfig();
        ConnectionPoolConfigurer.configureHikariPool(config, connectionDetails);

        assertEquals(12, config.getMaximumPoolSize()); // falls back to global default
        assertTrue(config.getPoolName().contains("testuser")); // pool name includes user even when db name is unknown
    }

    @Test
    public void testInvalidPropertyValues() {
        // Test that invalid values fall back appropriately
        Properties props = new Properties();
        props.setProperty("ojp.connection.pool.maximumPoolSize", "10"); // valid default
        props.setProperty("testdb.ojp.connection.pool.maximumPoolSize", "not-a-number"); // invalid db-specific
        props.setProperty("testdb.testuser.ojp.connection.pool.minimumIdle", "also-not-a-number"); // invalid db+user

        ConnectionDetails connectionDetails = createConnectionDetails(
                "jdbc:postgresql://localhost:5432/testdb", "testuser", props);

        HikariConfig config = new HikariConfig();
        ConnectionPoolConfigurer.configureHikariPool(config, connectionDetails);

        assertEquals(10, config.getMaximumPoolSize()); // falls back to valid global default
        assertEquals(CommonConstants.DEFAULT_MINIMUM_IDLE, config.getMinimumIdle()); // falls back to constant default
    }

    @Test
    public void testPoolNameGeneration() {
        // Test that pool names include database and user context
        Properties props = new Properties();
        
        ConnectionDetails connectionDetails = createConnectionDetails(
                "jdbc:postgresql://localhost:5432/mydb", "myuser", props);

        HikariConfig config = new HikariConfig();
        ConnectionPoolConfigurer.configureHikariPool(config, connectionDetails);

        String poolName = config.getPoolName();
        assertTrue(poolName.contains("mydb"));
        assertTrue(poolName.contains("myuser"));
        assertTrue(poolName.startsWith("OJP-Pool"));
    }

    @Test
    public void testNoPropertiesProvided() {
        // Test that configuration works even with no properties (null case)
        ConnectionDetails connectionDetails = createConnectionDetails(
                "jdbc:postgresql://localhost:5432/testdb", "testuser", null);

        HikariConfig config = new HikariConfig();
        ConnectionPoolConfigurer.configureHikariPool(config, connectionDetails);

        assertEquals(CommonConstants.DEFAULT_MAXIMUM_POOL_SIZE, config.getMaximumPoolSize());
        assertEquals(CommonConstants.DEFAULT_MINIMUM_IDLE, config.getMinimumIdle());
        assertEquals(CommonConstants.DEFAULT_IDLE_TIMEOUT, config.getIdleTimeout());
    }

    /**
     * Helper method to create ConnectionDetails for testing
     */
    private ConnectionDetails createConnectionDetails(String url, String user, Properties properties) {
        ConnectionDetails.Builder builder = ConnectionDetails.newBuilder()
                .setUrl(url)
                .setUser(user)
                .setPassword("password")
                .setClientUUID("test-uuid");
        
        if (properties != null) {
            byte[] serializedProperties = SerializationHandler.serialize(properties);
            builder.setProperties(com.google.protobuf.ByteString.copyFrom(serializedProperties));
        }
        
        return builder.build();
    }
}