package org.openjproxy.jdbc;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Utility class for parsing OJP JDBC URLs.
 * Shared by Driver and XA components.
 */
@Slf4j
public class UrlParser {

    /**
     * Result class for URL parsing.
     */
    public static class UrlParseResult {
        public final String cleanUrl;
        public final String dataSourceName;
        public final List<String> dataSourceNames;

        public UrlParseResult(String cleanUrl, String dataSourceName) {
            this.cleanUrl = cleanUrl;
            this.dataSourceName = dataSourceName;
            this.dataSourceNames = Collections.singletonList(dataSourceName);
        }

        public UrlParseResult(String cleanUrl, List<String> dataSourceNames) {
            this.cleanUrl = cleanUrl;
            this.dataSourceNames = dataSourceNames != null && !dataSourceNames.isEmpty()
                ? Collections.unmodifiableList(dataSourceNames)
                : Collections.singletonList("default");
            // For backward compatibility, use the first datasource name
            this.dataSourceName = this.dataSourceNames.get(0);
        }
    }

    /**
     * Parses the URL to extract dataSource parameter from the OJP section and return clean URL.
     * Supports both single-node and multinode URLs with per-endpoint datasource names.
     *
     * <p>Example transformations:
     * <ul>
     *   <li>Input: {@code jdbc:ojp[localhost:1059(webApp)]_postgresql://localhost/mydb}</li>
     *   <li>Output: {@code jdbc:ojp[localhost:1059]_postgresql://localhost/mydb}, dataSource: "webApp"</li>
     * </ul>
     * <ul>
     *   <li>Input: {@code jdbc:ojp[localhost:1059]_h2:mem:test}</li>
     *   <li>Output: {@code jdbc:ojp[localhost:1059]_h2:mem:test}, dataSource: "default"</li>
     * </ul>
     * <ul>
     *   <li>Input: {@code jdbc:ojp[localhost:10591(default),localhost:10592(multinode)]_h2:~/test}</li>
     *   <li>Output: {@code jdbc:ojp[localhost:10591,localhost:10592]_h2:~/test}, dataSources: ["default", "multinode"]</li>
     * </ul>
     *
     * @param url the original JDBC URL
     * @return UrlParseResult containing the cleaned URL (with dataSource removed) and the extracted dataSource name(s)
     */
    public static UrlParseResult parseUrlWithDataSource(String url) {
        if (url == null) {
            return new UrlParseResult(url, "default");
        }

        // Look for the OJP section: jdbc:ojp[host:port(dataSource)]_
        if (!url.startsWith("jdbc:ojp[")) {
            return new UrlParseResult(url, "default");
        }

        int bracketStart = url.indexOf('[');
        int bracketEnd = url.indexOf(']');

        if (bracketStart == -1 || bracketEnd == -1) {
            return new UrlParseResult(url, "default");
        }

        String ojpSection = url.substring(bracketStart + 1, bracketEnd);

        // Split by comma to handle multiple endpoints
        String[] endpoints = ojpSection.split(",");
        List<String> dataSourceNames = new ArrayList<>();
        StringBuilder cleanOjpSectionBuilder = new StringBuilder();

        for (int i = 0; i < endpoints.length; i++) {
            String endpoint = endpoints[i].trim();

            // Look for dataSource in parentheses: host:port(dataSource)
            int parenStart = endpoint.indexOf('(');
            int parenEnd = endpoint.lastIndexOf(')');

            String dataSourceName = "default";
            String cleanEndpoint = endpoint;

            if (parenStart != -1 && parenEnd != -1 && parenEnd > parenStart) {
                // Extract dataSource name from parentheses and trim whitespace
                dataSourceName = endpoint.substring(parenStart + 1, parenEnd).trim();
                // Remove the dataSource part from endpoint
                cleanEndpoint = endpoint.substring(0, parenStart).trim()
                        + endpoint.substring(parenEnd + 1).trim();
            }

            dataSourceNames.add(dataSourceName);

            if (i > 0) {
                cleanOjpSectionBuilder.append(",");
            }
            cleanOjpSectionBuilder.append(cleanEndpoint);
        }

        // Reconstruct the URL without the dataSource parts
        String cleanUrl = "jdbc:ojp[" + cleanOjpSectionBuilder.toString() + "]" + url.substring(bracketEnd + 1);

        log.debug("Parsed URL - input: {}, clean: {}, dataSources: {}", url, cleanUrl, dataSourceNames);

        return new UrlParseResult(cleanUrl, dataSourceNames);
    }
}
