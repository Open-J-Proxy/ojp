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
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;
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

        // Parse server endpoints from URL
        List<ServerEndpoint> serverEndpoints;
        try {
            serverEndpoints = MultinodeUrlParser.parseServerEndpoints(url);
        } catch (IllegalArgumentException e) {
            throw new SQLException("Invalid OJP URL format: " + e.getMessage(), e);
        }

        // Initialize appropriate StatementService based on number of servers
        StatementService service = getOrCreateStatementService(serverEndpoints);

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
                        .build()
                );
        log.debug("Returning new Connection with sessionInfo: {}", sessionInfo);
        return new Connection(sessionInfo, statementService, DatabaseUtils.resolveDbName(url));
    }

    private synchronized StatementService getOrCreateStatementService(List<ServerEndpoint> serverEndpoints) {
        // For single server, use existing implementation for backward compatibility
        if (serverEndpoints.size() == 1) {
            if (statementService == null || !(statementService instanceof StatementServiceGrpcClient)) {
                log.debug("Initializing StatementServiceGrpcClient for single server");
                statementService = new StatementServiceGrpcClient();
            }
        } else {
            // For multiple servers, always create new multinode service
            log.debug("Initializing MultinodeStatementService for {} servers", serverEndpoints.size());
            statementService = new MultinodeStatementService(serverEndpoints);
        }

        return statementService;
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
