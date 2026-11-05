package org.openjproxy.grpc.client;

import lombok.Getter;
import org.openjproxy.constants.CommonConstants;
import org.openjproxy.jdbc.DatasourcePropertiesLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Utility class for parsing OJP URLs with multinode support.
 * Always uses {@link MultinodeStatementService} regardless of the number of endpoints,
 * so a single-endpoint URL is treated the same as a multi-endpoint URL.
 * <p>
 * Examples:
 * - Single: jdbc:ojp[localhost:1059]_postgresql://localhost:5432/mydb
 * - Multi:  jdbc:ojp[server1:1059,server2:1059,server3:1059]_postgresql://localhost:5432/mydb
 */
public class MultinodeUrlParser {

    private static final Logger log = LoggerFactory.getLogger(MultinodeUrlParser.class);
    private static final Pattern OJP_PATTERN = Pattern.compile(CommonConstants.OJP_REGEX_PATTERN);
    private static final Pattern PRIORITY_PATTERN = Pattern.compile("^([^{}]+)\\{([0-9]+)\\}$");

    // Cache of statement services keyed by server configuration
    private static final Map<String, StatementService> STATEMENT_SERVICE_CACHE = new ConcurrentHashMap<>();

    /**
     * Helper class to return the service, connection URL, server endpoints, and datasource names.
     */
    @Getter
    public static class ServiceAndUrl {
        final StatementService service;
        final String connectionUrl;
        final List<String> serverEndpoints;
        final List<ServerEndpoint> serverEndpointsWithDatasources;

        ServiceAndUrl(StatementService service, String connectionUrl, List<String> serverEndpoints, List<ServerEndpoint> serverEndpointsWithDatasources) {
            this.service = service;
            this.connectionUrl = connectionUrl;
            this.serverEndpoints = serverEndpoints;
            this.serverEndpointsWithDatasources = serverEndpointsWithDatasources;
        }
    }

    /**
     * Gets or creates a StatementService implementation based on the URL.
     * Returns both the service and the connection URL (which may be modified for the server).
     * Always creates a {@link MultinodeStatementService} backed by a
     * {@link MultinodeConnectionManager}, regardless of how many endpoints the URL contains.
     * A single-endpoint URL is handled identically to a multi-endpoint URL.
     *
     * @param url             the JDBC URL (should be already cleaned of datasource names)
     * @param dataSourceNames optional list of datasource names corresponding to each endpoint
     */
    // No synchronization needed: computeIfAbsent on ConcurrentHashMap provides the required atomicity.
    public static ServiceAndUrl getOrCreateStatementService(String url, List<String> dataSourceNames) {

        List<ServerEndpoint> endpoints = MultinodeUrlParser.parseServerEndpoints(url, dataSourceNames);

        log.info("Configuring StatementService with {} endpoint(s): {}",
                endpoints.size(), MultinodeUrlParser.formatServerList(endpoints));

        // Include datasource and priority so different routing policies never share health/selection state.
        String cacheKey = endpoints.stream()
                .map(ep -> ep.getAddress() + "(" + ep.getDataSourceName() + "){" + ep.getPriority() + "}")
                .collect(Collectors.joining(","));
        StatementService service = STATEMENT_SERVICE_CACHE.computeIfAbsent(cacheKey, k -> {
            log.debug("Creating MultinodeStatementService for endpoints: {}",
                    MultinodeUrlParser.formatServerList(endpoints));
            Properties rawProperties = DatasourcePropertiesLoader.loadOjpProperties();
            HealthCheckConfig healthConfig = HealthCheckConfig.loadFromProperties(rawProperties);
            int retryAttempts = readIntProperty(rawProperties,
                    CommonConstants.MULTINODE_RETRY_ATTEMPTS_PROPERTY,
                    CommonConstants.DEFAULT_MULTINODE_RETRY_ATTEMPTS);
            long retryDelayMs = readLongProperty(rawProperties,
                    CommonConstants.MULTINODE_RETRY_DELAY_PROPERTY,
                    CommonConstants.DEFAULT_MULTINODE_RETRY_DELAY_MS);
            MultinodeConnectionManager connectionManager = new MultinodeConnectionManager(endpoints,
                    retryAttempts,
                    retryDelayMs,
                    healthConfig);
            XAConnectionRedistributor redistributor = new XAConnectionRedistributor(connectionManager, healthConfig);
            connectionManager.setXaConnectionRedistributor(redistributor);
            log.info("XAConnectionRedistributor wired into MultinodeConnectionManager ({} endpoints)", endpoints.size());

            return new MultinodeStatementService(connectionManager, url);
        });

        // Pass a URL that the server can parse: replace the endpoint list with just the first endpoint.
        String connectionUrl = MultinodeUrlParser.replaceBracketsWithSingleEndpoint(url, endpoints.get(0));

        // Convert ServerEndpoint list to string list (host:port format)
        List<String> serverEndpointStrings = endpoints.stream()
                .map(ep -> ep.getHost() + ":" + ep.getPort())
                .collect(java.util.stream.Collectors.toList());

        return new ServiceAndUrl(service, connectionUrl, serverEndpointStrings, endpoints);
    }

    /**
     * Gets or creates a StatementService implementation based on the URL.
     * Backward compatibility method without datasource names.
     */
    public static ServiceAndUrl getOrCreateStatementService(String url) {
        return getOrCreateStatementService(url, null);
    }

    /**
     * Parses an OJP URL and extracts server endpoints.
     *
     * @param url The OJP JDBC URL to parse
     * @param dataSourceNames Optional list of datasource names corresponding to each endpoint
     * @return List of server endpoints
     * @throws IllegalArgumentException if URL format is invalid
     */
    public static List<ServerEndpoint> parseServerEndpoints(String url, List<String> dataSourceNames) {
        if (url == null) {
            throw new IllegalArgumentException("URL cannot be null");
        }

        Matcher matcher = OJP_PATTERN.matcher(url);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Invalid OJP URL format. Expected: jdbc:ojp[host:port]_actual_jdbc_url");
        }

        String serverListString = matcher.group(1);
        List<ServerEndpoint> endpoints = new ArrayList<>();

        // Split by comma to support multinode
        String[] serverAddresses = serverListString.split(",");
        int annotatedEndpoints = 0;

        for (int i = 0; i < serverAddresses.length; i++) {
            String address = serverAddresses[i].trim();
            if (address.isEmpty()) {
                continue;
            }

            int priority = 1;
            if (address.contains("{") || address.contains("}")) {
                Matcher priorityMatcher = PRIORITY_PATTERN.matcher(address);
                if (!priorityMatcher.matches()) {
                    throw new IllegalArgumentException("Invalid server priority in address: " + address
                            + ". Expected format: host:port{positiveInteger}");
                }
                try {
                    priority = Integer.parseInt(priorityMatcher.group(2));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Server priority must be between 1 and "
                            + Integer.MAX_VALUE + ": " + address, e);
                }
                address = priorityMatcher.group(1).trim();
                annotatedEndpoints++;
            }

            String[] hostPort = address.split(":");
            if (hostPort.length != 2) {
                throw new IllegalArgumentException("Invalid server address format: " + address + ". Expected format: host:port");
            }

            String host = hostPort[0].trim();
            int port;
            try {
                port = Integer.parseInt(hostPort[1].trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid port number in address: " + address, e);
            }

            if (port <= 0 || port > 65535) {
                throw new IllegalArgumentException("Port number must be between 1 and 65535. Got: " + port);
            }

            // Get datasource name for this endpoint if provided
            String dataSourceName = (dataSourceNames != null && i < dataSourceNames.size())
                ? dataSourceNames.get(i)
                : "default";

            endpoints.add(new ServerEndpoint(host, port, dataSourceName, priority));
        }

        if (endpoints.isEmpty()) {
            throw new IllegalArgumentException("No valid server endpoints found in URL: " + url);
        }
        if (annotatedEndpoints > 0 && annotatedEndpoints != endpoints.size()) {
            throw new IllegalArgumentException("Every server must specify {priority} when any server has a priority");
        }

        log.debug("Parsed {} server endpoints from URL: {}", endpoints.size(),
                endpoints.stream().map(ep -> ep.getAddress() + "(" + ep.getDataSourceName() + ")").collect(Collectors.toList()));

        return endpoints;
    }

    /**
     * Parses an OJP URL and extracts server endpoints.
     * Backward compatibility method without datasource names.
     *
     * @param url The OJP JDBC URL to parse
     * @return List of server endpoints
     * @throws IllegalArgumentException if URL format is invalid
     */
    public static List<ServerEndpoint> parseServerEndpoints(String url) {
        return parseServerEndpoints(url, null);
    }

    /**
     * Extracts the actual JDBC URL by removing the OJP prefix.
     *
     * @param url The OJP JDBC URL
     * @return The actual JDBC URL without OJP prefix
     */
    public static String extractActualJdbcUrl(String url) {
        if (url == null) {
            return null;
        }
        return url.replaceAll(CommonConstants.OJP_REGEX_PATTERN + "_", "");
    }

    /**
     * Formats a list of server endpoints back into the OJP URL format, retaining priority tiers.
     *
     * @param endpoints List of server endpoints
     * @return Comma-separated string representation for URL
     */
    public static String formatServerList(List<ServerEndpoint> endpoints) {
        if (endpoints == null || endpoints.isEmpty()) {
            return "";
        }

        boolean includePriorities = endpoints.stream().anyMatch(endpoint -> endpoint.getPriority() != 1);
        return endpoints.stream()
                .map(endpoint -> endpoint.getAddress()
                        + (includePriorities ? "{" + endpoint.getPriority() + "}" : ""))
                .collect(Collectors.joining(","));
    }

    /**
     * Replaces the server list in a multinode URL with a single endpoint.
     * This is useful for converting multinode URLs to single-node format.
     *
     * @param url The original OJP URL (possibly with multiple endpoints)
     * @param endpoint The single endpoint to use
     * @return The URL with the server list replaced by a single endpoint
     */
    public static String replaceBracketsWithSingleEndpoint(String url, ServerEndpoint endpoint) {
        if (url == null || endpoint == null) {
            throw new IllegalArgumentException("URL and endpoint cannot be null");
        }

        // Replace the entire [server1:port1,server2:port2,...] section with [singleHost:singlePort]
        // The pattern includes "ojp" so we need to keep it in the replacement
        return url.replaceAll(CommonConstants.OJP_REGEX_PATTERN, "ojp[" + endpoint.getAddress() + "]");
    }

    /**
     * Reads an integer property, checking system properties first (highest priority),
     * then the supplied properties file, then falling back to the default.
     */
    private static int readIntProperty(Properties props, String key, int defaultValue) {
        String sysProp = System.getProperty(key);
        String raw = (sysProp != null && !sysProp.trim().isEmpty())
                ? sysProp.trim()
                : (props != null ? props.getProperty(key) : null);
        if (raw == null || raw.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("Invalid value for {}: {}, using default: {}", key, raw, defaultValue);
            return defaultValue;
        }
    }

    /**
     * Reads a long property, checking system properties first (highest priority),
     * then the supplied properties file, then falling back to the default.
     */
    private static long readLongProperty(Properties props, String key, long defaultValue) {
        String sysProp = System.getProperty(key);
        String raw = (sysProp != null && !sysProp.trim().isEmpty())
                ? sysProp.trim()
                : (props != null ? props.getProperty(key) : null);
        if (raw == null || raw.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("Invalid value for {}: {}, using default: {}", key, raw, defaultValue);
            return defaultValue;
        }
    }
}
