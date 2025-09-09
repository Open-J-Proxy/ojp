package org.openjdbcproxy.grpc.server.utils;

import org.openjdbcproxy.constants.CommonConstants;
import org.openjdbcproxy.utils.DataSourceUrlParser;

import static org.openjdbcproxy.grpc.server.Constants.EMPTY_STRING;

/**
 * Utility class for parsing and manipulating URLs.
 * Extracted from StatementServiceImpl to improve modularity.
 */
public class UrlParser {

    /**
     * Parses a URL by removing OJP-specific patterns and dataSource names.
     * Converts "jdbc:ojp[localhost:1059>fast]_h2:mem:testdb" to "h2:mem:testdb"
     *
     * @param url The URL to parse
     * @return The parsed URL with OJP patterns and dataSource names removed
     */
    public static String parseUrl(String url) {
        if (url == null) {
            return url;
        }
        
        // First remove the dataSource name from the URL if present
        String urlWithoutDataSource = DataSourceUrlParser.removeDataSourceFromUrl(url);
        
        // Then remove the OJP pattern as before
        return urlWithoutDataSource.replaceAll(CommonConstants.OJP_REGEX_PATTERN + "_", EMPTY_STRING);
    }
}