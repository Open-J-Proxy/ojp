package org.openjdbcproxy.grpc.server.pool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DataSourcePropertiesParser.
 */
public class DataSourcePropertiesParserTest {

    @Test
    @DisplayName("Test parsing properties with multiple data sources")
    public void testParseMultipleDataSources() {
        Properties props = new Properties();
        
        // Default data source
        props.setProperty("ojp.connection.pool.maximumPoolSize", "25");
        props.setProperty("ojp.connection.pool.minimumIdle", "5");
        
        // Fast data source
        props.setProperty("fast.ojp.connection.pool.maximumPoolSize", "50");
        props.setProperty("fast.ojp.connection.pool.minimumIdle", "10");
        
        // Slow data source
        props.setProperty("slow.ojp.connection.pool.maximumPoolSize", "10");
        props.setProperty("slow.ojp.connection.pool.connectionTimeout", "15000");
        
        // Non-OJP property (should be ignored)
        props.setProperty("other.property", "value");
        
        Map<String, Properties> result = DataSourcePropertiesParser.parseDataSourceProperties(props);
        
        // Should have 3 data sources: default, fast, slow
        assertEquals(3, result.size());
        assertTrue(result.containsKey("default"));
        assertTrue(result.containsKey("fast"));
        assertTrue(result.containsKey("slow"));
        
        // Check default data source properties
        Properties defaultProps = result.get("default");
        assertEquals("25", defaultProps.getProperty("ojp.connection.pool.maximumPoolSize"));
        assertEquals("5", defaultProps.getProperty("ojp.connection.pool.minimumIdle"));
        
        // Check fast data source properties
        Properties fastProps = result.get("fast");
        assertEquals("50", fastProps.getProperty("ojp.connection.pool.maximumPoolSize"));
        assertEquals("10", fastProps.getProperty("ojp.connection.pool.minimumIdle"));
        
        // Check slow data source properties
        Properties slowProps = result.get("slow");
        assertEquals("10", slowProps.getProperty("ojp.connection.pool.maximumPoolSize"));
        assertEquals("15000", slowProps.getProperty("ojp.connection.pool.connectionTimeout"));
        assertNull(slowProps.getProperty("ojp.connection.pool.minimumIdle"));
    }

    @Test
    @DisplayName("Test parsing with only default properties")
    public void testParseOnlyDefaultProperties() {
        Properties props = new Properties();
        props.setProperty("ojp.connection.pool.maximumPoolSize", "20");
        props.setProperty("ojp.connection.pool.minimumIdle", "3");
        
        Map<String, Properties> result = DataSourcePropertiesParser.parseDataSourceProperties(props);
        
        assertEquals(1, result.size());
        assertTrue(result.containsKey("default"));
        
        Properties defaultProps = result.get("default");
        assertEquals("20", defaultProps.getProperty("ojp.connection.pool.maximumPoolSize"));
        assertEquals("3", defaultProps.getProperty("ojp.connection.pool.minimumIdle"));
    }

    @Test
    @DisplayName("Test parsing with only named data sources")
    public void testParseOnlyNamedDataSources() {
        Properties props = new Properties();
        props.setProperty("myapp.ojp.connection.pool.maximumPoolSize", "30");
        props.setProperty("batch.ojp.connection.pool.minimumIdle", "2");
        
        Map<String, Properties> result = DataSourcePropertiesParser.parseDataSourceProperties(props);
        
        assertEquals(2, result.size());
        assertTrue(result.containsKey("myapp"));
        assertTrue(result.containsKey("batch"));
        assertFalse(result.containsKey("default"));
        
        Properties myappProps = result.get("myapp");
        assertEquals("30", myappProps.getProperty("ojp.connection.pool.maximumPoolSize"));
        
        Properties batchProps = result.get("batch");
        assertEquals("2", batchProps.getProperty("ojp.connection.pool.minimumIdle"));
    }

    @Test
    @DisplayName("Test parsing empty properties")
    public void testParseEmptyProperties() {
        Properties props = new Properties();
        
        Map<String, Properties> result = DataSourcePropertiesParser.parseDataSourceProperties(props);
        
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Test parsing null properties")
    public void testParseNullProperties() {
        Map<String, Properties> result = DataSourcePropertiesParser.parseDataSourceProperties(null);
        
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Test parsing with non-OJP properties only")
    public void testParseNonOjpPropertiesOnly() {
        Properties props = new Properties();
        props.setProperty("database.url", "jdbc:h2:mem:test");
        props.setProperty("app.name", "TestApp");
        props.setProperty("logging.level", "DEBUG");
        
        Map<String, Properties> result = DataSourcePropertiesParser.parseDataSourceProperties(props);
        
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Test getPropertiesForDataSource with existing data source")
    public void testGetPropertiesForExistingDataSource() {
        Properties props = new Properties();
        props.setProperty("ojp.connection.pool.maximumPoolSize", "20");
        props.setProperty("fast.ojp.connection.pool.maximumPoolSize", "50");
        
        Map<String, Properties> parsed = DataSourcePropertiesParser.parseDataSourceProperties(props);
        
        Properties fastProps = DataSourcePropertiesParser.getPropertiesForDataSource(parsed, "fast");
        assertEquals("50", fastProps.getProperty("ojp.connection.pool.maximumPoolSize"));
        
        Properties defaultProps = DataSourcePropertiesParser.getPropertiesForDataSource(parsed, "default");
        assertEquals("20", defaultProps.getProperty("ojp.connection.pool.maximumPoolSize"));
    }

    @Test
    @DisplayName("Test getPropertiesForDataSource fallback to default")
    public void testGetPropertiesForDataSourceFallbackToDefault() {
        Properties props = new Properties();
        props.setProperty("ojp.connection.pool.maximumPoolSize", "20");
        
        Map<String, Properties> parsed = DataSourcePropertiesParser.parseDataSourceProperties(props);
        
        // Request non-existent data source, should fall back to default
        Properties fallbackProps = DataSourcePropertiesParser.getPropertiesForDataSource(parsed, "nonexistent");
        assertEquals("20", fallbackProps.getProperty("ojp.connection.pool.maximumPoolSize"));
    }

    @Test
    @DisplayName("Test getPropertiesForDataSource with null/empty name")
    public void testGetPropertiesForDataSourceNullOrEmptyName() {
        Properties props = new Properties();
        props.setProperty("ojp.connection.pool.maximumPoolSize", "20");
        
        Map<String, Properties> parsed = DataSourcePropertiesParser.parseDataSourceProperties(props);
        
        Properties nullNameProps = DataSourcePropertiesParser.getPropertiesForDataSource(parsed, null);
        assertEquals("20", nullNameProps.getProperty("ojp.connection.pool.maximumPoolSize"));
        
        Properties emptyNameProps = DataSourcePropertiesParser.getPropertiesForDataSource(parsed, "");
        assertEquals("20", emptyNameProps.getProperty("ojp.connection.pool.maximumPoolSize"));
        
        Properties blankNameProps = DataSourcePropertiesParser.getPropertiesForDataSource(parsed, "  ");
        assertEquals("20", blankNameProps.getProperty("ojp.connection.pool.maximumPoolSize"));
    }

    @Test
    @DisplayName("Test getPropertiesForDataSource returns empty properties when nothing found")
    public void testGetPropertiesForDataSourceReturnsEmptyWhenNothingFound() {
        Properties props = new Properties();
        props.setProperty("fast.ojp.connection.pool.maximumPoolSize", "50");
        
        Map<String, Properties> parsed = DataSourcePropertiesParser.parseDataSourceProperties(props);
        
        // Request non-existent data source and no default exists
        Properties emptyProps = DataSourcePropertiesParser.getPropertiesForDataSource(parsed, "nonexistent");
        assertTrue(emptyProps.isEmpty());
    }

    @Test
    @DisplayName("Test complex data source names")
    public void testComplexDataSourceNames() {
        Properties props = new Properties();
        props.setProperty("my-app-prod.ojp.connection.pool.maximumPoolSize", "100");
        props.setProperty("analytics_v2.ojp.connection.pool.minimumIdle", "5");
        props.setProperty("user123.ojp.connection.pool.connectionTimeout", "5000");
        
        Map<String, Properties> result = DataSourcePropertiesParser.parseDataSourceProperties(props);
        
        assertEquals(3, result.size());
        assertTrue(result.containsKey("my-app-prod"));
        assertTrue(result.containsKey("analytics_v2"));
        assertTrue(result.containsKey("user123"));
        
        assertEquals("100", result.get("my-app-prod").getProperty("ojp.connection.pool.maximumPoolSize"));
        assertEquals("5", result.get("analytics_v2").getProperty("ojp.connection.pool.minimumIdle"));
        assertEquals("5000", result.get("user123").getProperty("ojp.connection.pool.connectionTimeout"));
    }

    @Test
    @DisplayName("Test getDefaultDataSourceName")
    public void testGetDefaultDataSourceName() {
        assertEquals("default", DataSourcePropertiesParser.getDefaultDataSourceName());
    }
}