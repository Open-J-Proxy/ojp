package org.openjproxy.grpc.server.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Security validation for cache operations to prevent cache poisoning
 * and other security issues.
 */
public class CacheSecurityValidator {

    private static final Logger log = LoggerFactory.getLogger(CacheSecurityValidator.class);

    // Maximum size for cached results (200KB - same as rejection limit)
    private static final long MAX_CACHE_SIZE = 200L * 1024;

    /**
     * Validates that a cache key doesn't contain suspicious SQL patterns
     * that might indicate SQL injection attempts or cache poisoning.
     *
     * @param key the cache key to validate
     * @return true if the key is safe to use
     */
    public static boolean isSafeCacheKey(QueryCacheKey key) {
        if (key == null) {
            return false;
        }

        String sql = key.getNormalizedSql();

        // Check for SQL injection patterns
        if (containsSqlInjectionPatterns(sql)) {
            log.warn("Suspicious SQL pattern detected in cache key - potential SQL injection: datasource={}, sql={}",
                    key.getDatasourceName(), truncateSql(sql, 100));
            return false;
        }

        // Check parameters for suspicious content
        if (key.getParameters() != null) {
            for (Object param : key.getParameters()) {
                if (param != null && containsSuspiciousParameter(param.toString())) {
                    log.warn("Suspicious parameter detected in cache key: datasource={}, param={}",
                            key.getDatasourceName(), truncate(param.toString(), 50));
                    return false;
                }
            }
        }

        return true;
    }

    /**
     * Validates that a cached result size is within acceptable limits
     * to prevent memory exhaustion attacks.
     *
     * @param result the cached query result
     * @return true if the result size is acceptable
     */
    public static boolean isSafeCacheSize(CachedQueryResult result) {
        return isSafeCacheSize(result, MAX_CACHE_SIZE);
    }

    /**
     * Validates that a cached result size is within specified limit.
     *
     * @param result  the cached query result
     * @param maxSize maximum allowed size in bytes
     * @return true if the result size is acceptable
     */
    public static boolean isSafeCacheSize(CachedQueryResult result, long maxSize) {
        if (result == null) {
            return false;
        }

        long size = result.getEstimatedSizeBytes();

        if (size > maxSize) {
            log.warn("Cache result too large: size={} bytes, max={} bytes", size, maxSize);
            return false;
        }

        return true;
    }

    /**
     * Checks if SQL contains patterns commonly associated with SQL injection.
     */
    private static boolean containsSqlInjectionPatterns(String sql) {
        if (sql == null) {
            return false;
        }

        String lowerSql = sql.toLowerCase();

        // Check for comment markers (used to bypass filters)
        if (lowerSql.contains("--") || lowerSql.contains("/*") || lowerSql.contains("*/")) {
            return true;
        }

        // Check for string concatenation with quotes (common in injection)
        if (lowerSql.contains("';") || lowerSql.contains("\";")) {
            return true;
        }

        // Check for stacked queries (semicolon in middle of query)
        int semicolonIndex = lowerSql.indexOf(';');
        return semicolonIndex > 0 && semicolonIndex < lowerSql.length() - 1;
    }

    /**
     * Checks if a parameter value contains suspicious content.
     */
    private static boolean containsSuspiciousParameter(String param) {
        if (param == null) {
            return false;
        }

        // Check for SQL keywords in parameters (might be injection attempts)
        String lowerParam = param.toLowerCase();

        if (lowerParam.contains(" or ") || lowerParam.contains(" and ")) {
            return true;
        }

        return lowerParam.contains("drop ") || lowerParam.contains("delete ") ||
                lowerParam.contains("update ") || lowerParam.contains("insert ");
    }

    /**
     * Truncates SQL for logging purposes.
     */
    private static String truncateSql(String sql, int maxLength) {
        if (sql == null || sql.length() <= maxLength) {
            return sql;
        }
        return sql.substring(0, maxLength) + "...";
    }

    /**
     * Truncates string for logging purposes.
     */
    private static String truncate(String str, int maxLength) {
        if (str == null || str.length() <= maxLength) {
            return str;
        }
        return str.substring(0, maxLength) + "...";
    }
}
