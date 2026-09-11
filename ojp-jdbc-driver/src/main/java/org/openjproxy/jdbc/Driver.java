package org.openjproxy.jdbc;

import com.openjproxy.grpc.ConnectionDetails;
import com.openjproxy.grpc.SessionInfo;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.constants.CommonConstants;
import org.openjproxy.database.DatabaseUtils;
import org.openjproxy.grpc.ProtoConverter;
import org.openjproxy.grpc.client.MultinodeUrlParser;
import org.openjproxy.grpc.client.ServerEndpoint;
import org.openjproxy.grpc.client.StatementService;

import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.HashMap;
import java.util.List;
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

    public Driver() {
        // Services are created per-URL configuration in connect()
    }

    @Override
    public java.sql.Connection connect(String url, Properties info) throws SQLException {
        log.debug("connect: url={}, info={}", url, info);

        // Parse URL to extract dataSource name(s) and clean URL
        UrlParser.UrlParseResult urlParseResult = UrlParser.parseUrlWithDataSource(url);
        String cleanUrl = urlParseResult.cleanUrl;
        String dataSourceName = urlParseResult.dataSourceName;
        List<String> dataSourceNames = urlParseResult.dataSourceNames;

        log.debug("Parsed URL - clean: {}, dataSource: {}, dataSources: {}", cleanUrl, dataSourceName, dataSourceNames);

        // Get or create the StatementService for these endpoints
        MultinodeUrlParser.ServiceAndUrl serviceAndUrl = MultinodeUrlParser.getOrCreateStatementService(cleanUrl, dataSourceNames);
        StatementService statementService = serviceAndUrl.getService();
        String connectionUrl = serviceAndUrl.getConnectionUrl();
        List<String> serverEndpoints = serviceAndUrl.getServerEndpoints();
        List<ServerEndpoint> serverEndpointsWithDatasources = serviceAndUrl.getServerEndpointsWithDatasources();

        // Warn when multiple endpoints carry distinct datasource names so that operators
        // are aware of the per-server routing that will be applied.
        if (serverEndpointsWithDatasources.size() > 1) {
            boolean hasMultipleDatasources = serverEndpointsWithDatasources.stream()
                .map(ServerEndpoint::getDataSourceName)
                .distinct()
                .count() > 1;

            if (hasMultipleDatasources) {
                log.warn("Per-endpoint datasources detected. Currently using first datasource '{}' for connection properties. " +
                        "Per-server configuration will be applied based on server endpoint datasource names: {}",
                        dataSourceName,
                        serverEndpointsWithDatasources.stream()
                            .map(ep -> ep.getAddress() + "=" + ep.getDataSourceName())
                            .collect(java.util.stream.Collectors.joining(", ")));
            }
        }

        // Load ojp.properties file and extract datasource-specific configuration.
        // Then merge any ojp.connection.pool.* / ojp.xa.* / ojp.jdbc.* keys from the caller-supplied info
        // on top (info properties take the highest priority).
        Properties ojpProperties = DatasourcePropertiesLoader.loadOjpPropertiesForDataSource(dataSourceName);
        ojpProperties = DatasourcePropertiesLoader.applyInfoProperties(ojpProperties, info, dataSourceName);

        ConnectionDetails.Builder connBuilder = ConnectionDetails.newBuilder()
                .setUrl(connectionUrl)
                .setUser((String) ((info.get(USER) != null) ? info.get(USER) : ""))
                .setPassword((String) ((info.get(PASSWORD) != null) ? info.get(PASSWORD) : ""))
                .setClientUUID(ClientUUID.getUUID());

        // Always add all server endpoints for cluster coordination
        connBuilder.addAllServerEndpoints(serverEndpoints);
        log.info("Adding {} server endpoint(s) to ConnectionDetails", serverEndpoints.size());

        // Build combined properties map: file (ojp.properties) takes lowest priority;
        // inline ojp.* properties from info override the file so callers can supply
        // read/write splitting and other configuration directly via
        // DriverManager.getConnection(url, info) without a server-side properties file.
        Map<String, Object> propertiesMap = new HashMap<>();
        if (ojpProperties != null && !ojpProperties.isEmpty()) {
            for (String key : ojpProperties.stringPropertyNames()) {
                propertiesMap.put(key, ojpProperties.getProperty(key));
            }
        }
        if (info != null) {
            for (String key : info.stringPropertyNames()) {
                // Forward any *.ojp.* properties (e.g. read/write splitting, replica config)
                // and the top-level ojp.* properties (e.g. ojp.datasource.name).
                if (key.contains(".ojp.") || key.startsWith("ojp.")) {
                    propertiesMap.put(key, info.getProperty(key));
                }
            }
        }
        if (!propertiesMap.isEmpty()) {
            // Add cache configuration properties to the map
            try {
                CacheConfigurationBuilder.addCachePropertiesToMap(propertiesMap, dataSourceName);
            } catch (Exception e) {
                log.error("Failed to add cache configuration for datasource '{}': {}", dataSourceName, e.getMessage());
                // Continue without cache configuration - caching will be disabled
            }

            connBuilder.addAllProperties(ProtoConverter.propertiesToProto(propertiesMap));
            log.debug("Loaded {} properties for dataSource: {}", propertiesMap.size(), dataSourceName);
        }

        log.info("Calling connect() on statement service with URL: {}", connectionUrl);
        SessionInfo sessionInfo;
        try {
            sessionInfo = statementService.connect(connBuilder.build());
            log.info("Connection established - sessionUUID: {}, connHash: {}",
                    sessionInfo.getSessionUUID(), sessionInfo.getConnHash());
        } catch (Exception e) {
            log.error("Failed to establish connection", e);
            throw e;
        }
        boolean closeSynchronously = Boolean.parseBoolean(
                ojpProperties != null
                        ? ojpProperties.getProperty(
                                CommonConstants.JDBC_CLOSE_SYNC_PROPERTY,
                                String.valueOf(CommonConstants.DEFAULT_JDBC_CLOSE_SYNCHRONOUS))
                        : String.valueOf(CommonConstants.DEFAULT_JDBC_CLOSE_SYNCHRONOUS));
        ClientThrottleMode throttleMode = ClientThrottleMode.fromString(
                ojpProperties != null
                        ? ojpProperties.getProperty(CommonConstants.JDBC_CLIENT_THROTTLE_MODE_PROPERTY,
                                CommonConstants.DEFAULT_JDBC_CLIENT_THROTTLE_MODE)
                        : CommonConstants.DEFAULT_JDBC_CLIENT_THROTTLE_MODE);
        log.debug("Returning new Connection with sessionInfo: {}", sessionInfo);
        return new Connection(sessionInfo, statementService, DatabaseUtils.resolveDbName(cleanUrl),
                closeSynchronously, throttleMode);
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
        return DriverVersion.getMajorVersion();
    }

    @Override
    public int getMinorVersion() {
        log.debug("getMinorVersion called");
        return DriverVersion.getMinorVersion();
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
