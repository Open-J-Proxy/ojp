package org.openjdbcproxy.jdbc;

import com.google.protobuf.ByteString;
import com.openjdbcproxy.grpc.ConnectionDetails;
import com.openjdbcproxy.grpc.SessionInfo;
import lombok.extern.slf4j.Slf4j;
import org.openjdbcproxy.database.DatabaseUtils;
import org.openjdbcproxy.grpc.SerializationHandler;
import org.openjdbcproxy.grpc.client.StatementService;
import org.openjdbcproxy.grpc.client.StatementServiceGrpcClient;

import java.io.IOException;
import java.io.InputStream;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;

import static org.openjdbcproxy.jdbc.Constants.PASSWORD;
import static org.openjdbcproxy.jdbc.Constants.USER;

@Slf4j
public class Driver implements java.sql.Driver {

    static {
        try {
            log.debug("Registering OpenJDBCProxy Driver");
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
        
        // Extract dataSource name from connection info or use default
        String dataSourceName = extractDataSourceName(info);
        log.debug("Using dataSource name: {}", dataSourceName);
        
        // Load ojp.properties file if it exists
        Properties ojpProperties = loadOjpProperties();
        ByteString propertiesBytes = ByteString.EMPTY;
        if (ojpProperties != null && !ojpProperties.isEmpty()) {
            propertiesBytes = ByteString.copyFrom(SerializationHandler.serialize(ojpProperties));
            log.debug("Loaded ojp.properties with {} properties", ojpProperties.size());
        }
        
        SessionInfo sessionInfo = statementService
                .connect(ConnectionDetails.newBuilder()
                        .setUrl(url)
                        .setUser((String) ((info.get(USER) != null)? info.get(USER) : ""))
                        .setPassword((String) ((info.get(PASSWORD) != null) ? info.get(PASSWORD) : ""))
                        .setClientUUID(ClientUUID.getUUID())
                        .setProperties(propertiesBytes)
                        .setDataSourceName(dataSourceName)
                        .build()
                );
        log.debug("Returning new Connection with sessionInfo: {}", sessionInfo);
        return new Connection(sessionInfo, statementService, DatabaseUtils.resolveDbName(url));
    }
    
    /**
     * Extracts the dataSource name from connection properties.
     * Supports both "dataSourceName" property and URL parameter.
     */
    private String extractDataSourceName(Properties info) {
        // First check for explicit dataSourceName property
        String dataSourceName = (String) info.get("dataSourceName");
        if (dataSourceName != null && !dataSourceName.trim().isEmpty()) {
            return dataSourceName.trim();
        }
        
        // Could also support URL parameter in future: jdbc:ojp:...?dataSourceName=myDS
        // For now, return empty string which will be treated as default
        return "";
    }
    
    private Properties loadOjpProperties() {
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