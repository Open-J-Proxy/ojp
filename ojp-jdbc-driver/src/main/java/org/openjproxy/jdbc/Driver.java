package org.openjproxy.jdbc;

import com.google.protobuf.ByteString;
import com.openjproxy.grpc.ConnectionDetails;
import com.openjproxy.grpc.SessionInfo;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.database.DatabaseUtils;
import org.openjproxy.grpc.SerializationHandler;
import org.openjproxy.grpc.client.StatementService;
import org.openjproxy.grpc.client.StatementServiceGrpcClient;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.openjproxy.jdbc.Constants.PASSWORD;
import static org.openjproxy.jdbc.Constants.USER;

@Slf4j
public class Driver implements java.sql.Driver {

    static {
        try {
            log.debug("Registering OpenJProxy Driver");
            DriverManager.registerDriver(new Driver());
        } catch (SQLException var1) {
            log.error("Can't register OJP driver!", var1);
        }
    }

    private static StatementService statementService;

    public Driver() {
        if (statementService == null) {
            synchronized (Driver.class) {
                if (statementService == null) {
                    log.debug("Initializing StatementServiceGrpcClient");
                    statementService = new StatementServiceGrpcClient();
                }
            }
        }
    }

    @Override
    public java.sql.Connection connect(String url, Properties info) throws SQLException {
        log.debug("connect: url={}, info={}", url, info);
        
        // Parse URL to extract dataSource name and clean URL
        UrlParseResult urlParseResult = parseUrlWithDataSource(url);
        String cleanUrl = urlParseResult.cleanUrl;
        String dataSourceName = urlParseResult.dataSourceName;
        
        log.debug("Parsed URL - clean: {}, dataSource: {}", cleanUrl, dataSourceName);
        
        // Load ojp.properties file and extract datasource-specific configuration
        Properties ojpProperties = loadOjpPropertiesForDataSource(dataSourceName);
        ByteString propertiesBytes = ByteString.EMPTY;
        if (ojpProperties != null && !ojpProperties.isEmpty()) {
            propertiesBytes = ByteString.copyFrom(SerializationHandler.serialize(ojpProperties));
            log.debug("Loaded ojp.properties with {} properties for dataSource: {}", ojpProperties.size(), dataSourceName);
        }
        
        SessionInfo sessionInfo = statementService
                .connect(ConnectionDetails.newBuilder()
                        .setUrl(cleanUrl)
                        .setUser((String) ((info.get(USER) != null)? info.get(USER) : ""))
                        .setPassword((String) ((info.get(PASSWORD) != null) ? info.get(PASSWORD) : ""))
                        .setClientUUID(ClientUUID.getUUID())
                        .setProperties(propertiesBytes)
                        .build()
                );
        log.debug("Returning new Connection with sessionInfo: {}", sessionInfo);
        return new Connection(sessionInfo, statementService, DatabaseUtils.resolveDbName(cleanUrl));
    }
    
    /**
     * Parses the URL to extract dataSource parameter and return clean URL.
     */
    private UrlParseResult parseUrlWithDataSource(String url) {
        if (url == null) {
            return new UrlParseResult(url, "default");
        }
        
        // Look for query parameters after ?
        int queryStart = url.indexOf('?');
        if (queryStart == -1) {
            // No query parameters, use default dataSource
            return new UrlParseResult(url, "default");
        }
        
        String cleanUrl = url.substring(0, queryStart);
        String queryString = url.substring(queryStart + 1);
        
        // Parse query parameters
        Map<String, String> params = parseQueryString(queryString);
        String dataSourceName = params.getOrDefault("dataSource", "default");
        
        return new UrlParseResult(cleanUrl, dataSourceName);
    }
    
    /**
     * Parse query string into key-value pairs.
     */
    private Map<String, String> parseQueryString(String queryString) {
        Map<String, String> params = new HashMap<>();
        if (queryString == null || queryString.trim().isEmpty()) {
            return params;
        }
        
        String[] pairs = queryString.split("&");
        for (String pair : pairs) {
            String[] keyValue = pair.split("=", 2);
            if (keyValue.length == 2) {
                try {
                    String key = URLDecoder.decode(keyValue[0], StandardCharsets.UTF_8);
                    String value = URLDecoder.decode(keyValue[1], StandardCharsets.UTF_8);
                    params.put(key, value);
                } catch (Exception e) {
                    log.warn("Failed to parse URL parameter: {}", pair, e);
                }
            }
        }
        return params;
    }
    
    /**
     * Result class for URL parsing.
     */
    private static class UrlParseResult {
        final String cleanUrl;
        final String dataSourceName;
        
        UrlParseResult(String cleanUrl, String dataSourceName) {
            this.cleanUrl = cleanUrl;
            this.dataSourceName = dataSourceName;
        }
    }
    
    /**
     * Load ojp.properties and extract configuration specific to the given dataSource.
     */
    private Properties loadOjpPropertiesForDataSource(String dataSourceName) {
        Properties allProperties = loadOjpProperties();
        if (allProperties == null || allProperties.isEmpty()) {
            return null;
        }
        
        Properties dataSourceProperties = new Properties();
        
        // Look for dataSource-prefixed properties first: {dataSourceName}.ojp.connection.pool.*
        String prefix = dataSourceName + ".ojp.connection.pool.";
        boolean foundDataSourceSpecific = false;
        
        for (String key : allProperties.stringPropertyNames()) {
            if (key.startsWith(prefix)) {
                // Remove the dataSource prefix and keep the standard property name
                String standardKey = key.substring(dataSourceName.length() + 1); // Remove "{dataSourceName}."
                dataSourceProperties.setProperty(standardKey, allProperties.getProperty(key));
                foundDataSourceSpecific = true;
            }
        }
        
        // If no dataSource-specific properties found, and this is the "default" dataSource,
        // look for unprefixed properties: ojp.connection.pool.*
        if (!foundDataSourceSpecific && "default".equals(dataSourceName)) {
            for (String key : allProperties.stringPropertyNames()) {
                if (key.startsWith("ojp.connection.pool.")) {
                    dataSourceProperties.setProperty(key, allProperties.getProperty(key));
                }
            }
        }
        
        // If we found any properties, also include the dataSource name for server-side use
        if (!dataSourceProperties.isEmpty()) {
            dataSourceProperties.setProperty("ojp.datasource.name", dataSourceName);
        }
        
        log.debug("Loaded {} properties for dataSource '{}': {}", 
                dataSourceProperties.size(), dataSourceName, dataSourceProperties);
        
        return dataSourceProperties.isEmpty() ? null : dataSourceProperties;
    }

    /**
     * Load the raw ojp.properties file from classpath.
     */
    protected Properties loadOjpProperties() {
        Properties properties = new Properties();
        
        // Only try to load from resources/ojp.properties in the classpath
        try (InputStream is = Driver.class.getClassLoader().getResourceAsStream("ojp.properties")) {
            if (is != null) {
                properties.load(is);
                log.debug("Loaded ojp.properties from resources folder");
                return properties;
            }
        } catch (IOException e) {
            log.debug("Could not load ojp.properties from resources folder: {}", e.getMessage());
        }
        
        log.debug("No ojp.properties file found, using server defaults");
        return null;
    }

    @Override
    public boolean acceptsURL(String url) throws SQLException {
        log.debug("acceptsURL: {}", url);
        if (url == null) {
            log.error("URL is null");
            throw new SQLException("URL is null");
        } else {
            boolean accepts = url.startsWith("jdbc:ojp");
            log.debug("acceptsURL returns: {}", accepts);
            return accepts;
        }
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
        log.debug("getPropertyInfo: url={}, info={}", url, info);
        return new DriverPropertyInfo[0];
    }

    @Override
    public int getMajorVersion() {
        log.debug("getMajorVersion called");
        return 0;
    }

    @Override
    public int getMinorVersion() {
        log.debug("getMinorVersion called");
        return 0;
    }

    @Override
    public boolean jdbcCompliant() {
        log.debug("jdbcCompliant called");
        return false;
    }

    @Override
    public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
        log.debug("getParentLogger called");
        return null;
    }
}