package org.openjdbcproxy.grpc.server.pool;

import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Utility class for parsing data source properties with support for prefixed data source names.
 * 
 * Supports both:
 * - Prefixed properties: someDataSourceName.ojp.connection.pool.maximumPoolSize
 * - Default properties: ojp.connection.pool.maximumPoolSize (treated as "default" data source)
 */
@Slf4j
public class DataSourcePropertiesParser {
    
    private static final String DEFAULT_DATASOURCE_NAME = "default";
    private static final String OJP_PREFIX = "ojp.";
    
    /**
     * Parses properties into per-data source configurations.
     * 
     * @param properties The properties to parse
     * @return Map of data source name to its properties
     */
    public static Map<String, Properties> parseDataSourceProperties(Properties properties) {
        Map<String, Properties> dataSourceProperties = new HashMap<>();
        
        if (properties == null || properties.isEmpty()) {
            log.debug("No properties provided");
            return dataSourceProperties;
        }
        
        for (String key : properties.stringPropertyNames()) {
            String value = properties.getProperty(key);
            
            if (key.startsWith(OJP_PREFIX)) {
                // Default data source property (no prefix)
                addPropertyToDataSource(dataSourceProperties, DEFAULT_DATASOURCE_NAME, key, value);
                log.debug("Added default property: {} = {}", key, value);
            } else if (key.contains("." + OJP_PREFIX)) {
                // Prefixed data source property
                int ojpIndex = key.indexOf("." + OJP_PREFIX);
                String dataSourceName = key.substring(0, ojpIndex);
                String propertyKey = key.substring(ojpIndex + 1); // Remove the leading dot
                
                addPropertyToDataSource(dataSourceProperties, dataSourceName, propertyKey, value);
                log.debug("Added property for data source '{}': {} = {}", dataSourceName, propertyKey, value);
            } else {
                log.debug("Ignoring non-OJP property: {}", key);
            }
        }
        
        log.info("Parsed properties for {} data sources: {}", 
                dataSourceProperties.size(), 
                dataSourceProperties.keySet());
        
        return dataSourceProperties;
    }
    
    /**
     * Adds a property to a specific data source configuration.
     */
    private static void addPropertyToDataSource(Map<String, Properties> dataSourceProperties, 
                                               String dataSourceName, 
                                               String key, 
                                               String value) {
        Properties dsProps = dataSourceProperties.computeIfAbsent(dataSourceName, k -> new Properties());
        dsProps.setProperty(key, value);
    }
    
    /**
     * Gets the properties for a specific data source, falling back to default if not found.
     */
    public static Properties getPropertiesForDataSource(Map<String, Properties> dataSourceProperties, 
                                                       String dataSourceName) {
        if (dataSourceName == null || dataSourceName.trim().isEmpty()) {
            dataSourceName = DEFAULT_DATASOURCE_NAME;
        }
        
        Properties props = dataSourceProperties.get(dataSourceName);
        if (props == null) {
            log.debug("No specific properties found for data source '{}', checking for default", dataSourceName);
            props = dataSourceProperties.get(DEFAULT_DATASOURCE_NAME);
        }
        
        return props != null ? props : new Properties();
    }
    
    /**
     * Returns the default data source name.
     */
    public static String getDefaultDataSourceName() {
        return DEFAULT_DATASOURCE_NAME;
    }
}