package org.openjdbcproxy.grpc.server.pool;

import com.openjdbcproxy.grpc.ConnectionDetails;
import com.google.protobuf.ByteString;
import org.openjdbcproxy.grpc.SerializationHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Demonstration tests for the new data source pool management functionality.
 * These tests show the complete flow from properties to data source configuration.
 */
public class DataSourcePoolManagerDemonstrationTest {

    @Test
    @DisplayName("Demonstrate end-to-end data source configuration flow")
    public void testEndToEndDataSourceConfiguration() {
        // 1. Create client properties with multiple data sources (simulating ojp.properties)
        Properties clientProperties = new Properties();
        
        // Default data source
        clientProperties.setProperty("ojp.connection.pool.maximumPoolSize", "25");
        clientProperties.setProperty("ojp.connection.pool.minimumIdle", "5");
        
        // Fast data source for real-time operations
        clientProperties.setProperty("fast.ojp.connection.pool.maximumPoolSize", "50");
        clientProperties.setProperty("fast.ojp.connection.pool.minimumIdle", "10");
        clientProperties.setProperty("fast.ojp.connection.pool.connectionTimeout", "5000");
        
        // Batch data source for background processing
        clientProperties.setProperty("batch.ojp.connection.pool.maximumPoolSize", "15");
        clientProperties.setProperty("batch.ojp.connection.pool.minimumIdle", "2");
        clientProperties.setProperty("batch.ojp.connection.pool.idleTimeout", "600000");
        
        // 2. Serialize properties (as the JDBC driver would do)
        byte[] serializedProperties = SerializationHandler.serialize(clientProperties);
        
        // 3. Create ConnectionDetails with serialized properties (as gRPC would do)
        ConnectionDetails connectionDetails = ConnectionDetails.newBuilder()
                .setUrl("jdbc:h2:mem:testdb")
                .setUser("sa")
                .setPassword("")
                .setClientUUID("test-client-uuid")
                .setDataSourceName("fast")  // Request specific data source
                .setProperties(ByteString.copyFrom(serializedProperties))
                .build();
        
        // 4. Parse the properties on the server side
        Properties extractedProperties = ConnectionPoolConfigurer.extractClientProperties(connectionDetails);
        assertNotNull(extractedProperties);
        assertEquals(8, extractedProperties.size()); // Should have all 8 properties
        
        // 5. Parse into per-data source configurations
        var dataSourceProperties = DataSourcePropertiesParser.parseDataSourceProperties(extractedProperties);
        assertEquals(3, dataSourceProperties.size()); // default, fast, batch
        
        // 6. Get properties for the requested data source
        String requestedDataSource = connectionDetails.getDataSourceName();
        Properties fastProperties = DataSourcePropertiesParser.getPropertiesForDataSource(
                dataSourceProperties, requestedDataSource);
        
        // 7. Verify the fast data source has correct properties
        assertEquals("50", fastProperties.getProperty("ojp.connection.pool.maximumPoolSize"));
        assertEquals("10", fastProperties.getProperty("ojp.connection.pool.minimumIdle"));
        assertEquals("5000", fastProperties.getProperty("ojp.connection.pool.connectionTimeout"));
        
        // 8. Verify fallback to default works
        Properties defaultProperties = DataSourcePropertiesParser.getPropertiesForDataSource(
                dataSourceProperties, "default");
        assertEquals("25", defaultProperties.getProperty("ojp.connection.pool.maximumPoolSize"));
        assertEquals("5", defaultProperties.getProperty("ojp.connection.pool.minimumIdle"));
        
        // 9. Verify non-existent data source falls back to default
        Properties fallbackProperties = DataSourcePropertiesParser.getPropertiesForDataSource(
                dataSourceProperties, "nonexistent");
        assertEquals("25", fallbackProperties.getProperty("ojp.connection.pool.maximumPoolSize"));
        
        System.out.println("✅ End-to-end data source configuration flow validated successfully!");
        System.out.println("📊 Parsed " + dataSourceProperties.size() + " data sources: " + dataSourceProperties.keySet());
        System.out.println("🎯 Successfully configured 'fast' data source with maxPoolSize=" + 
                fastProperties.getProperty("ojp.connection.pool.maximumPoolSize"));
    }

    @Test
    @DisplayName("Demonstrate security benefits - no database name in config keys")
    public void testSecurityBenefits() {
        Properties clientProperties = new Properties();
        
        // Old approach would have exposed database/user info in keys
        // e.g., "mydb.myuser.ojp.connection.pool.maximumPoolSize" 
        
        // New approach uses abstract data source names
        clientProperties.setProperty("prod-api.ojp.connection.pool.maximumPoolSize", "100");
        clientProperties.setProperty("prod-batch.ojp.connection.pool.maximumPoolSize", "20");
        clientProperties.setProperty("dev-testing.ojp.connection.pool.maximumPoolSize", "5");
        
        var dataSourceProperties = DataSourcePropertiesParser.parseDataSourceProperties(clientProperties);
        
        // Verify all data sources are parsed correctly without exposing sensitive info
        assertEquals(3, dataSourceProperties.size());
        assertTrue(dataSourceProperties.containsKey("prod-api"));
        assertTrue(dataSourceProperties.containsKey("prod-batch"));
        assertTrue(dataSourceProperties.containsKey("dev-testing"));
        
        // The data source names are meaningful to the application but don't expose DB details
        Properties prodApiProps = dataSourceProperties.get("prod-api");
        assertEquals("100", prodApiProps.getProperty("ojp.connection.pool.maximumPoolSize"));
        
        System.out.println("🔒 Security demonstration completed:");
        System.out.println("   - Data source names are application-defined: " + dataSourceProperties.keySet());
        System.out.println("   - No database names or usernames exposed in configuration keys");
        System.out.println("   - Multiple pools can use same DB connection parameters");
    }

    @Test
    @DisplayName("Demonstrate flexibility - multiple data sources for same database")
    public void testFlexibilityBenefits() {
        Properties clientProperties = new Properties();
        
        // Multiple data sources pointing to the same database but with different pool settings
        clientProperties.setProperty("user-facing.ojp.connection.pool.maximumPoolSize", "50");
        clientProperties.setProperty("user-facing.ojp.connection.pool.connectionTimeout", "3000");
        
        clientProperties.setProperty("admin-panel.ojp.connection.pool.maximumPoolSize", "20");
        clientProperties.setProperty("admin-panel.ojp.connection.pool.connectionTimeout", "10000");
        
        clientProperties.setProperty("reports.ojp.connection.pool.maximumPoolSize", "10");
        clientProperties.setProperty("reports.ojp.connection.pool.idleTimeout", "900000");
        
        var dataSourceProperties = DataSourcePropertiesParser.parseDataSourceProperties(clientProperties);
        
        // All three can connect to the same database with different pool characteristics
        assertEquals(3, dataSourceProperties.size());
        
        // User-facing: fast response, larger pool
        Properties userFacing = dataSourceProperties.get("user-facing");
        assertEquals("50", userFacing.getProperty("ojp.connection.pool.maximumPoolSize"));
        assertEquals("3000", userFacing.getProperty("ojp.connection.pool.connectionTimeout"));
        
        // Admin panel: moderate size, longer timeout
        Properties adminPanel = dataSourceProperties.get("admin-panel");
        assertEquals("20", adminPanel.getProperty("ojp.connection.pool.maximumPoolSize"));
        assertEquals("10000", adminPanel.getProperty("ojp.connection.pool.connectionTimeout"));
        
        // Reports: small pool, long idle timeout for heavy queries
        Properties reports = dataSourceProperties.get("reports");
        assertEquals("10", reports.getProperty("ojp.connection.pool.maximumPoolSize"));
        assertEquals("900000", reports.getProperty("ojp.connection.pool.idleTimeout"));
        
        System.out.println("🔧 Flexibility demonstration completed:");
        System.out.println("   - Same database can have multiple optimized connection pools");
        System.out.println("   - Each pool can be tuned for its specific use case");
        System.out.println("   - Clear separation of concerns between application components");
    }
}