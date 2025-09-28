package org.openjproxy.jdbc;

import org.junit.jupiter.api.Test;
import org.openjproxy.grpc.SerializationHandler;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for multi-datasource configuration functionality.
 */
public class MultiDataSourceConfigurationTest {

    @Test
    public void testUrlParsingWithDataSourceParameter() throws Exception {
        Driver driver = new Driver();
        Method parseUrlMethod = Driver.class.getDeclaredMethod("parseUrlWithDataSource", String.class);
        parseUrlMethod.setAccessible(true);
        
        // Test URL without dataSource parameter - should default to "default"
        Object result1 = parseUrlMethod.invoke(driver, "jdbc:ojp[localhost:1059]_h2:~/test");
        String cleanUrl1 = (String) result1.getClass().getDeclaredField("cleanUrl").get(result1);
        String dataSourceName1 = (String) result1.getClass().getDeclaredField("dataSourceName").get(result1);
        
        assertEquals("jdbc:ojp[localhost:1059]_h2:~/test", cleanUrl1);
        assertEquals("default", dataSourceName1);
        
        // Test URL with dataSource parameter in parentheses
        Object result2 = parseUrlMethod.invoke(driver, "jdbc:ojp[localhost:1059(myApp)]_h2:~/test");
        String cleanUrl2 = (String) result2.getClass().getDeclaredField("cleanUrl").get(result2);
        String dataSourceName2 = (String) result2.getClass().getDeclaredField("dataSourceName").get(result2);
        
        assertEquals("jdbc:ojp[localhost:1059]_h2:~/test", cleanUrl2);
        assertEquals("myApp", dataSourceName2);
        
        // Test URL with port and datasource
        Object result3 = parseUrlMethod.invoke(driver, "jdbc:ojp[localhost:1059(readOnly)]_h2:~/test");
        String cleanUrl3 = (String) result3.getClass().getDeclaredField("cleanUrl").get(result3);
        String dataSourceName3 = (String) result3.getClass().getDeclaredField("dataSourceName").get(result3);
        
        assertEquals("jdbc:ojp[localhost:1059]_h2:~/test", cleanUrl3);
        assertEquals("readOnly", dataSourceName3);
    }

    
    @Test
    public void testLoadOjpPropertiesForDataSource() throws Exception {
        // Create test properties with multiple datasources
        String testProperties = 
            "# Default datasource\n" +
            "ojp.connection.pool.maximumPoolSize=20\n" +
            "ojp.connection.pool.minimumIdle=5\n" +
            "\n" +
            "# MyApp datasource\n" +
            "myApp.ojp.connection.pool.maximumPoolSize=50\n" +
            "myApp.ojp.connection.pool.minimumIdle=10\n" +
            "myApp.ojp.connection.pool.connectionTimeout=15000\n" +
            "\n" +
            "# ReadOnly datasource\n" +
            "readOnly.ojp.connection.pool.maximumPoolSize=5\n" +
            "readOnly.ojp.connection.pool.minimumIdle=1\n";
        
        // Mock the loadOjpProperties method to return our test properties
        Properties allProperties = new Properties();
        try (InputStream is = new ByteArrayInputStream(testProperties.getBytes())) {
            allProperties.load(is);
        }
        
        Driver driver = new Driver() {
            @Override
            protected Properties loadOjpProperties() {
                return allProperties;
            }
        };
        
        // Make the method accessible for testing
        Method loadForDataSourceMethod = Driver.class.getDeclaredMethod("loadOjpPropertiesForDataSource", String.class);
        loadForDataSourceMethod.setAccessible(true);
        
        // Test default datasource
        Properties defaultProps = (Properties) loadForDataSourceMethod.invoke(driver, "default");
        assertNotNull(defaultProps);
        assertEquals("20", defaultProps.getProperty("ojp.connection.pool.maximumPoolSize"));
        assertEquals("5", defaultProps.getProperty("ojp.connection.pool.minimumIdle"));
        assertEquals("default", defaultProps.getProperty("ojp.datasource.name"));
        
        // Test myApp datasource
        Properties myAppProps = (Properties) loadForDataSourceMethod.invoke(driver, "myApp");
        assertNotNull(myAppProps);
        assertEquals("50", myAppProps.getProperty("ojp.connection.pool.maximumPoolSize"));
        assertEquals("10", myAppProps.getProperty("ojp.connection.pool.minimumIdle"));
        assertEquals("15000", myAppProps.getProperty("ojp.connection.pool.connectionTimeout"));
        assertEquals("myApp", myAppProps.getProperty("ojp.datasource.name"));
        
        // Test readOnly datasource
        Properties readOnlyProps = (Properties) loadForDataSourceMethod.invoke(driver, "readOnly");
        assertNotNull(readOnlyProps);
        assertEquals("5", readOnlyProps.getProperty("ojp.connection.pool.maximumPoolSize"));
        assertEquals("1", readOnlyProps.getProperty("ojp.connection.pool.minimumIdle"));
        assertEquals("readOnly", readOnlyProps.getProperty("ojp.datasource.name"));
        
        // Test non-existent datasource - should return null
        Properties nonExistentProps = (Properties) loadForDataSourceMethod.invoke(driver, "nonExistent");
        assertNull(nonExistentProps);
    }
    
    @Test
    public void testDataSourceConfigurationWithNoProperties() throws Exception {
        // Create a driver that returns null for ojp.properties (simulating no file found)
        Driver driver = new Driver() {
            @Override
            protected Properties loadOjpProperties() {
                return null; // Simulate no properties file found
            }
        };
        
        Method loadForDataSourceMethod = Driver.class.getDeclaredMethod("loadOjpPropertiesForDataSource", String.class);
        loadForDataSourceMethod.setAccessible(true);
        
        // Test when no properties file exists
        Properties result = (Properties) loadForDataSourceMethod.invoke(driver, "default");
        assertNull(result);
        
        // Test with non-default datasource when no properties exist
        Properties result2 = (Properties) loadForDataSourceMethod.invoke(driver, "myApp");
        assertNull(result2);
    }
}