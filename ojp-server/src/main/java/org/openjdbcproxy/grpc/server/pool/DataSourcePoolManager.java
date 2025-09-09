package org.openjdbcproxy.grpc.server.pool;

import com.openjdbcproxy.grpc.ConnectionDetails;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.openjdbcproxy.grpc.server.utils.UrlParser;

import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages multiple named HikariCP data source pools.
 * Supports initialization of multiple pools at startup and runtime connection acquisition by data source name.
 */
@Slf4j
public class DataSourcePoolManager {
    
    private final Map<String, HikariDataSource> dataSources = new ConcurrentHashMap<>();
    private final Map<String, Properties> dataSourceProperties = new ConcurrentHashMap<>();
    
    /**
     * Initializes all data source pools based on provided client properties.
     * This should be called at startup to create all required pools.
     *
     * @param connectionDetails The connection details containing all properties
     */
    public void initializeDataSources(ConnectionDetails connectionDetails) {
        Properties clientProperties = ConnectionPoolConfigurer.extractClientProperties(connectionDetails);
        if (clientProperties == null || clientProperties.isEmpty()) {
            log.info("No client properties provided, no data sources to initialize");
            return;
        }
        
        Map<String, Properties> parsedProperties = DataSourcePropertiesParser.parseDataSourceProperties(clientProperties);
        
        for (Map.Entry<String, Properties> entry : parsedProperties.entrySet()) {
            String dataSourceName = entry.getKey();
            Properties dsProperties = entry.getValue();
            
            try {
                createDataSource(dataSourceName, connectionDetails, dsProperties);
                log.info("Successfully initialized data source: {}", dataSourceName);
            } catch (Exception e) {
                log.error("Failed to initialize data source '{}': {}", dataSourceName, e.getMessage(), e);
                throw new RuntimeException("Failed to initialize data source: " + dataSourceName, e);
            }
        }
        
        log.info("Initialized {} data sources: {}", dataSources.size(), dataSources.keySet());
    }
    
    /**
     * Creates a HikariCP data source with the specified configuration.
     */
    private void createDataSource(String dataSourceName, ConnectionDetails connectionDetails, Properties dsProperties) {
        HikariConfig config = new HikariConfig();
        
        // Set basic connection parameters
        config.setJdbcUrl(UrlParser.parseUrl(connectionDetails.getUrl()));
        config.setUsername(connectionDetails.getUser());
        config.setPassword(connectionDetails.getPassword());
        
        // Configure pool settings with data source specific properties
        ConnectionPoolConfigurer.configureHikariPoolWithProperties(config, dsProperties, dataSourceName);
        
        // Create the data source
        HikariDataSource dataSource = new HikariDataSource(config);
        
        // Store the data source and its properties
        dataSources.put(dataSourceName, dataSource);
        dataSourceProperties.put(dataSourceName, dsProperties);
    }
    
    /**
     * Gets a data source by name.
     * 
     * @param dataSourceName The name of the data source
     * @return The HikariDataSource
     * @throws IllegalArgumentException if data source is not found
     */
    public HikariDataSource getDataSource(String dataSourceName) {
        if (dataSourceName == null || dataSourceName.trim().isEmpty()) {
            dataSourceName = DataSourcePropertiesParser.getDefaultDataSourceName();
        }
        
        HikariDataSource dataSource = dataSources.get(dataSourceName);
        if (dataSource == null) {
            String errorMessage = String.format(
                "Data source '%s' not found. Available data sources: %s. " +
                "Please ensure the data source is configured in your ojp.properties file " +
                "using the format: %s.ojp.connection.pool.* or ojp.connection.pool.* for the default data source.",
                dataSourceName, dataSources.keySet(), dataSourceName
            );
            log.error(errorMessage);
            throw new IllegalArgumentException(errorMessage);
        }
        
        return dataSource;
    }
    
    /**
     * Checks if a data source exists.
     */
    public boolean hasDataSource(String dataSourceName) {
        if (dataSourceName == null || dataSourceName.trim().isEmpty()) {
            dataSourceName = DataSourcePropertiesParser.getDefaultDataSourceName();
        }
        return dataSources.containsKey(dataSourceName);
    }
    
    /**
     * Gets all configured data source names.
     */
    public java.util.Set<String> getDataSourceNames() {
        return dataSources.keySet();
    }
    
    /**
     * Closes all data sources and releases resources.
     */
    public void shutdown() {
        log.info("Shutting down {} data sources", dataSources.size());
        
        for (Map.Entry<String, HikariDataSource> entry : dataSources.entrySet()) {
            try {
                String dataSourceName = entry.getKey();
                HikariDataSource dataSource = entry.getValue();
                
                log.info("Closing data source: {}", dataSourceName);
                dataSource.close();
            } catch (Exception e) {
                log.error("Error closing data source: {}", e.getMessage(), e);
            }
        }
        
        dataSources.clear();
        dataSourceProperties.clear();
        log.info("All data sources shut down");
    }
    
    /**
     * Gets a connection from the specified data source.
     */
    public java.sql.Connection getConnection(String dataSourceName) throws SQLException {
        HikariDataSource dataSource = getDataSource(dataSourceName);
        return dataSource.getConnection();
    }
}