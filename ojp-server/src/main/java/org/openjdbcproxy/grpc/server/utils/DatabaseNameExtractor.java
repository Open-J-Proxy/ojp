package org.openjdbcproxy.grpc.server.utils;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class for extracting the actual database name from JDBC URLs.
 * This extracts the specific database name (e.g., "mydatabase") rather than 
 * just the database type (e.g., "postgresql").
 */
@Slf4j
@UtilityClass
public class DatabaseNameExtractor {

    // Pattern for PostgreSQL: jdbc:postgresql://host:port/databaseName?params (case insensitive)
    private static final Pattern POSTGRESQL_PATTERN = Pattern.compile("jdbc:postgresql://[^/]+/([^?;&]+)", Pattern.CASE_INSENSITIVE);
    
    // Pattern for MySQL/MariaDB: jdbc:mysql://host:port/databaseName?params (case insensitive)
    private static final Pattern MYSQL_PATTERN = Pattern.compile("jdbc:mysql://[^/]+/([^?;&]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MARIADB_PATTERN = Pattern.compile("jdbc:mariadb://[^/]+/([^?;&]+)", Pattern.CASE_INSENSITIVE);
    
    // Pattern for Oracle: jdbc:oracle:thin:@host:port/serviceName or jdbc:oracle:thin:@host:port:sid (case insensitive)
    private static final Pattern ORACLE_SERVICE_PATTERN = Pattern.compile("jdbc:oracle:thin:@[^/]+/([^?;&]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ORACLE_SID_PATTERN = Pattern.compile("jdbc:oracle:thin:@[^:]+:[^:]+:([^?;&]+)", Pattern.CASE_INSENSITIVE);
    
    // Pattern for SQL Server: jdbc:sqlserver://host:port;databaseName=dbName (case insensitive)
    private static final Pattern SQLSERVER_PATTERN = Pattern.compile("jdbc:sqlserver://[^;]+;.*databaseName=([^;&]+)", Pattern.CASE_INSENSITIVE);
    
    // Pattern for DB2: jdbc:db2://host:port/databaseName (case insensitive)
    private static final Pattern DB2_PATTERN = Pattern.compile("jdbc:db2://[^/]+/([^?;&]+)", Pattern.CASE_INSENSITIVE);
    
    // Pattern for H2: jdbc:h2:mem:databaseName or jdbc:h2:file:./path/databaseName (case insensitive)
    private static final Pattern H2_MEM_PATTERN = Pattern.compile("jdbc:h2:mem:([^?;&]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern H2_FILE_PATTERN = Pattern.compile("jdbc:h2:file:.*[/\\\\]([^/\\\\?;&]+)", Pattern.CASE_INSENSITIVE);

    /**
     * Extracts the actual database name from a JDBC URL.
     * 
     * @param jdbcUrl The JDBC URL to parse
     * @return The database name if found, or null if not extractable
     */
    public static String extractDatabaseName(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.trim().isEmpty()) {
            return null;
        }

        String url = jdbcUrl.trim().toLowerCase();
        
        try {
            // PostgreSQL
            if (url.contains("postgresql")) {
                return extractWithPattern(jdbcUrl, POSTGRESQL_PATTERN);
            }
            
            // MySQL
            if (url.contains("mysql") && !url.contains("mariadb")) {
                return extractWithPattern(jdbcUrl, MYSQL_PATTERN);
            }
            
            // MariaDB
            if (url.contains("mariadb")) {
                return extractWithPattern(jdbcUrl, MARIADB_PATTERN);
            }
            
            // Oracle - try service name first, then SID
            if (url.contains("oracle")) {
                String serviceName = extractWithPattern(jdbcUrl, ORACLE_SERVICE_PATTERN);
                if (serviceName != null) {
                    return serviceName;
                }
                return extractWithPattern(jdbcUrl, ORACLE_SID_PATTERN);
            }
            
            // SQL Server
            if (url.contains("sqlserver")) {
                return extractWithPattern(jdbcUrl, SQLSERVER_PATTERN);
            }
            
            // DB2
            if (url.contains("db2")) {
                return extractWithPattern(jdbcUrl, DB2_PATTERN);
            }
            
            // H2 - try memory first, then file
            if (url.contains("h2")) {
                String memDbName = extractWithPattern(jdbcUrl, H2_MEM_PATTERN);
                if (memDbName != null) {
                    return memDbName;
                }
                return extractWithPattern(jdbcUrl, H2_FILE_PATTERN);
            }
            
            log.warn("Could not extract database name from unsupported JDBC URL format: {}", jdbcUrl);
            return null;
            
        } catch (Exception e) {
            log.warn("Error extracting database name from URL {}: {}", jdbcUrl, e.getMessage());
            return null;
        }
    }

    /**
     * Helper method to extract database name using a regex pattern.
     */
    private static String extractWithPattern(String jdbcUrl, Pattern pattern) {
        Matcher matcher = pattern.matcher(jdbcUrl);
        if (matcher.find() && matcher.groupCount() > 0) {
            String dbName = matcher.group(1);
            // Clean up the database name - remove any trailing parameters
            if (dbName.contains("?")) {
                dbName = dbName.substring(0, dbName.indexOf("?"));
            }
            if (dbName.contains(";")) {
                dbName = dbName.substring(0, dbName.indexOf(";"));
            }
            return dbName.trim();
        }
        return null;
    }
}