package org.openjproxy.grpc.server.cache;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Registry for managing QueryResultCache instances per datasource.
 * Singleton pattern with lazy initialization and thread-safe access.
 */
public class QueryResultCacheRegistry {
    private static final QueryResultCacheRegistry INSTANCE = new QueryResultCacheRegistry();

    // Default cache settings
    private static final int DEFAULT_MAX_ENTRIES = 10_000;
    private static final Duration DEFAULT_MAX_AGE = Duration.ofMinutes(10);
    private static final long DEFAULT_MAX_SIZE_BYTES = 100L * 1024 * 1024; // 100MB

    private final ConcurrentMap<String, QueryResultCache> caches = new ConcurrentHashMap<>();
    private QueryCacheMetrics metrics = NoOpQueryCacheMetrics.getInstance();

    private QueryResultCacheRegistry() {
        // Private constructor for singleton
    }

    /**
     * Get the singleton instance.
     *
     * @return The registry instance
     */
    public static QueryResultCacheRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * Set the metrics collector for all caches.
     * Must be called before any caches are created.
     *
     * @param metrics The metrics collector
     */
    public void setMetrics(QueryCacheMetrics metrics) {
        if (metrics != null) {
            this.metrics = metrics;
        }
    }

    /**
     * Get or create a cache for the specified datasource with default settings.
     *
     * @param datasourceName The datasource name
     * @return The cache instance
     */
    public QueryResultCache getOrCreate(String datasourceName) {
        return caches.computeIfAbsent(datasourceName,
                k -> new QueryResultCache(datasourceName, DEFAULT_MAX_ENTRIES, DEFAULT_MAX_AGE, DEFAULT_MAX_SIZE_BYTES,
                        metrics));
    }

    /**
     * Get or create a cache for the specified datasource with custom settings.
     *
     * @param datasourceName The datasource name
     * @param maxEntries     Maximum number of entries
     * @param maxAge         Maximum age for entries
     * @param maxSizeBytes   Maximum total size in bytes
     * @return The cache instance
     */
    public QueryResultCache getOrCreate(String datasourceName, int maxEntries, Duration maxAge, long maxSizeBytes) {
        return caches.computeIfAbsent(datasourceName,
                k -> new QueryResultCache(datasourceName, maxEntries, maxAge, maxSizeBytes, metrics));
    }

    /**
     * Get an existing cache for the datasource, or null if none exists.
     *
     * @param datasourceName The datasource name
     * @return The cache instance, or null
     */
    public QueryResultCache get(String datasourceName) {
        return caches.get(datasourceName);
    }

    /**
     * Check if a cache exists for the datasource.
     *
     * @param datasourceName The datasource name
     * @return True if cache exists
     */
    public boolean exists(String datasourceName) {
        return caches.containsKey(datasourceName);
    }

    /**
     * Remove and invalidate all entries for a datasource.
     *
     * @param datasourceName The datasource name
     * @return The removed cache, or null if none existed
     */
    public QueryResultCache remove(String datasourceName) {
        return caches.remove(datasourceName);
    }

    /**
     * Clear all caches.
     */
    public void clear() {
        caches.values().forEach(QueryResultCache::invalidateAll);
        caches.clear();
    }

    /**
     * Get the number of registered caches.
     *
     * @return Cache count
     */
    public int size() {
        return caches.size();
    }

    /**
     * Get statistics for all caches.
     *
     * @return Combined statistics string
     */
    public String getAllStatistics() {
        StringBuilder sb = new StringBuilder();
        sb.append("Cache Statistics:\n");
        caches.forEach((datasourceName, cache) ->
            sb.append(String.format("  %s: %s (entries=%d, size=%d bytes)%n",
                    datasourceName,
                    cache.getStatistics(),
                    cache.getEntryCount(),
                    cache.getCurrentSizeBytes()))
        );
        return sb.toString();
    }
}
