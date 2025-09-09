package org.openjdbcproxy.utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class for parsing dataSource names from OJP URLs.
 * Supports the new URL format: jdbc:ojp[host:port>dataSourceName]_database://...
 */
public class DataSourceUrlParser {
    
    // Pattern to match ojp[host:port>dataSourceName] or ojp[host:port]
    private static final Pattern URL_PATTERN = Pattern.compile("ojp\\[([^>\\]]+)(?:>([^\\]]+))?\\]");
    
    /**
     * Extracts the dataSource name from a JDBC URL.
     * 
     * @param url the JDBC URL (e.g., "jdbc:ojp[localhost:1059>fast]_h2:mem:testdb")
     * @return the dataSource name, or "default" if no dataSource name is specified
     */
    public static String extractDataSourceName(String url) {
        if (url == null || url.trim().isEmpty()) {
            return "default";
        }
        
        Matcher matcher = URL_PATTERN.matcher(url);
        if (matcher.find()) {
            String dataSourceName = matcher.group(2); // Group 2 is the dataSourceName after '>'
            if (dataSourceName != null && !dataSourceName.trim().isEmpty()) {
                return dataSourceName.trim();
            }
        }
        
        return "default";
    }
    
    /**
     * Removes the dataSource name from the URL for database connection purposes.
     * Converts "jdbc:ojp[localhost:1059>fast]_h2:mem:testdb" to "jdbc:ojp[localhost:1059]_h2:mem:testdb"
     * 
     * @param url the original URL
     * @return the URL with dataSource name removed
     */
    public static String removeDataSourceFromUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return url;
        }
        
        Matcher matcher = URL_PATTERN.matcher(url);
        if (matcher.find()) {
            String hostPort = matcher.group(1); // Group 1 is the host:port
            String replacement = "ojp[" + hostPort + "]";
            return matcher.replaceFirst(replacement);
        }
        
        return url;
    }
    
    /**
     * Extracts the host:port portion from the URL.
     * 
     * @param url the JDBC URL
     * @return the host:port portion, or empty string if not found
     */
    public static String extractHostPort(String url) {
        if (url == null || url.trim().isEmpty()) {
            return "";
        }
        
        Matcher matcher = URL_PATTERN.matcher(url);
        if (matcher.find()) {
            return matcher.group(1); // Group 1 is the host:port
        }
        
        return "";
    }
}