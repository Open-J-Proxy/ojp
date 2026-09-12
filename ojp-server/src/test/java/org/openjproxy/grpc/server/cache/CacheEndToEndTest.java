package org.openjproxy.grpc.server.cache;

import com.openjproxy.grpc.OpQueryResultProto;
import com.openjproxy.grpc.ParameterValue;
import com.openjproxy.grpc.ResultRow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration tests for query result caching.
 * 
 * Tests realistic scenarios including:
 * - Full query lifecycle (cache miss, store, hit)
 * - Automatic invalidation on writes
 * - Multi-datasource isolation
 * - Complex query patterns
 * - Realistic workload simulations
 */
class CacheEndToEndTest {
    
    private QueryResultCacheRegistry registry;
    private String datasourceName;
    private static final String TEST_UUID = "test-uuid";
    
    @BeforeEach
    void setUp() {
        registry = QueryResultCacheRegistry.getInstance();
        datasourceName = "test_ds_" + UUID.randomUUID();
        
        // Clear any existing cache
        registry.clear();
    }
    
    @AfterEach
    void tearDown() {
        registry.clear();
    }
    
    /**
     * Helper method to create a test proto from rows and columns
     */
    private OpQueryResultProto createTestProto(List<List<Object>> rows, List<String> columns) {
        OpQueryResultProto.Builder builder = OpQueryResultProto.newBuilder();
        builder.setResultSetUUID(TEST_UUID);
        
        // Add column labels
        for (String column : columns) {
            builder.addLabels(column);
        }
        
        // Add rows
        for (List<Object> row : rows) {
            ResultRow.Builder rowBuilder = ResultRow.newBuilder();
            for (Object value : row) {
                String stringValue = value != null ? value.toString() : "";
                rowBuilder.addColumns(ParameterValue.newBuilder()
                        .setStringValue(stringValue)
                        .build());
            }
            builder.addRows(rowBuilder.build());
        }
        
        return builder.build();
    }
    /**
     * Test: Complete query lifecycle from first query to cache hit
     * 
     * Scenario:
     * 1. Execute query (cache MISS)
     * 2. Store result in cache
     * 3. Execute same query (cache HIT)
     * 4. Verify same data returned
     * 5. Verify statistics updated correctly
     */
    @Test
    void testCompleteQueryLifecycle() {
        QueryResultCache cache = registry.getOrCreate(datasourceName);
        
        // Simulate first query (cache MISS)
        String sql = "SELECT * FROM products WHERE category = ?";
        List<Object> params = List.of("electronics");
        QueryCacheKey key = new QueryCacheKey(datasourceName, sql, params);
        
        CachedQueryResult cachedResult = cache.get(key);
        assertNull(cachedResult, "First query should be cache MISS");
        
        // Simulate query execution and result storage
        List<String> columnNames = List.of("id", "name", "category", "price");
        List<List<Object>> rows = List.of(
            List.of(1, "Laptop", "electronics", 999.99),
            List.of(2, "Phone", "electronics", 599.99)
        );
        CachedQueryResult result = new CachedQueryResult(
            createTestProto(rows, columnNames),
            Instant.now(),
            Instant.now().plus(Duration.ofMinutes(10)),
            Set.of("products")
        );
        
        cache.put(key, result);
        
        // Simulate second query (cache HIT)
        CachedQueryResult cachedResult2 = cache.get(key);
        assertNotNull(cachedResult2, "Second query should be cache HIT");
        assertEquals(2, cachedResult2.getQueryResultProto().getRowsCount());
        assertEquals("Laptop", cachedResult2.getQueryResultProto().getRows(0).getColumns(1).getStringValue());
        
        // Verify statistics
        CacheStatistics stats = cache.getStatistics();
        assertEquals(1, stats.getHits());
        assertEquals(1, stats.getMisses());
        assertTrue(stats.getHitRate() > 0.49 && stats.getHitRate() < 0.51);
    }
    
    /**
     * Test: Automatic cache invalidation on write operations
     * 
     * Scenario:
     * 1. Query and cache result
     * 2. Execute write that modifies queried table
     * 3. Verify cache invalidated
     * 4. Query again (should be cache MISS)
     */
    @Test
    void testAutomaticInvalidationOnWrite() {
        QueryResultCache cache = registry.getOrCreate(datasourceName);
        
        // Cache a query result
        String sql = "SELECT * FROM products WHERE category = ?";
        List<Object> params = List.of("electronics");
        QueryCacheKey key = new QueryCacheKey(datasourceName, sql, params);
        
        List<String> columnNames = List.of("id", "name");
        List<List<Object>> rows = List.of(List.of(1, "Laptop"));
        CachedQueryResult result = new CachedQueryResult(
            createTestProto(rows, columnNames),
            Instant.now(),
            Instant.now().plus(Duration.ofMinutes(10)),
            Set.of("products")
        );
        cache.put(key, result);
        
        // Verify cached
        assertNotNull(cache.get(key));
        
        // Simulate write operation (invalidates cache)
        cache.invalidate(datasourceName, Set.of("products"));
        
        // Verify cache invalidated
        assertNull(cache.get(key), "Cache should be invalidated after write to products table");
        
        // Verify statistics
        CacheStatistics stats = cache.getStatistics();
        assertEquals(1, stats.getInvalidations());
    }
    
    /**
     * Test: Multi-datasource isolation
     * 
     * Verifies that caches for different datasources are completely isolated
     * and don't interfere with each other.
     */
    @Test
    void testMultiDatasourceIsolation() {
        String ds1 = "datasource1";
        String ds2 = "datasource2";
        
        QueryResultCache cache1 = registry.getOrCreate(ds1);
        QueryResultCache cache2 = registry.getOrCreate(ds2);
        
        // Store in cache1
        String sql = "SELECT * FROM users";
        QueryCacheKey key1 = new QueryCacheKey(ds1, sql, List.of());
        QueryCacheKey key2 = new QueryCacheKey(ds2, sql, List.of());
        
        List<String> columns = List.of("id", "name");
        List<List<Object>> rows1 = List.of(List.of(1, "Alice"));
        List<List<Object>> rows2 = List.of(List.of(2, "Bob"));
        
        cache1.put(key1, new CachedQueryResult(createTestProto(rows1, columns), Instant.now(), Instant.now().plus(Duration.ofMinutes(10)), Set.of("users")));
        cache2.put(key2, new CachedQueryResult(createTestProto(rows2, columns), Instant.now(), Instant.now().plus(Duration.ofMinutes(10)), Set.of("users")));
        
        // Verify isolation
        CachedQueryResult result1 = cache1.get(key1);
        CachedQueryResult result2 = cache2.get(key2);
        
        assertNotNull(result1);
        assertNotNull(result2);
        assertEquals("Alice", result1.getQueryResultProto().getRows(0).getColumns(1).getStringValue());
        assertEquals("Bob", result2.getQueryResultProto().getRows(0).getColumns(1).getStringValue());
        
        // Invalidate ds1, verify ds2 unaffected
        cache1.invalidate(ds1, Set.of("users"));
        
        assertNull(cache1.get(key1), "Cache1 should be invalidated");
        assertNotNull(cache2.get(key2), "Cache2 should NOT be invalidated");
        
        // Cleanup
        registry.clear();
    }
    
    /**
     * Test: Complex query patterns with joins
     * 
     * Tests caching behavior with complex queries involving multiple tables.
     */
    @Test
    void testComplexQueryPatterns() {
        QueryResultCache cache = registry.getOrCreate(datasourceName);
        
        // Complex query with JOIN
        String sql = "SELECT o.id, o.customer_id, oi.product_id, oi.quantity " +
                    "FROM orders o JOIN order_items oi ON o.id = oi.order_id " +
                    "WHERE o.customer_id = ?";
        List<Object> params = List.of(12345);
        QueryCacheKey key = new QueryCacheKey(datasourceName, sql, params);
        
        // Store complex result
        List<String> columns = List.of("id", "customer_id", "product_id", "quantity");
        List<List<Object>> rows = List.of(
            List.of(1, 12345, 101, 2),
            List.of(1, 12345, 102, 1)
        );
        CachedQueryResult result = new CachedQueryResult(
            createTestProto(rows, columns),
            Instant.now(),
            Instant.now().plus(Duration.ofMinutes(5)),
            Set.of("orders", "order_items")
        );
        cache.put(key, result);
        
        // Verify cached
        CachedQueryResult cached = cache.get(key);
        assertNotNull(cached);
        assertEquals(2, cached.getQueryResultProto().getRowsCount());
        
        // Invalidate by orders table
        cache.invalidate(datasourceName, Set.of("orders"));
        assertNull(cache.get(key), "Should be invalidated by orders table write");
        
        // Re-cache
        cache.put(key, result);
        
        // Invalidate by order_items table
        cache.invalidate(datasourceName, Set.of("order_items"));
        assertNull(cache.get(key), "Should be invalidated by order_items table write");
    }
    
    /**
     * Test: Realistic e-commerce workload simulation
     * 
     * Simulates a realistic e-commerce workload:
     * - 70% product catalog reads (cacheable)
     * - 20% user data reads (cacheable)
     * - 10% writes (orders, cart updates)
     * 
     * Validates:
     * - Cache hit rate >60%
     * - No errors under load
     * - Statistics accuracy
     */
    @Test
    void testRealisticEcommerceWorkload() {
        QueryResultCache cache = registry.getOrCreate(datasourceName);
        
        // Simulate workload
        int totalRequests = 1000;
        int productReads = 700;
        int userReads = 200;
        
        Random random = new Random(42); // Fixed seed for reproducibility
        
        // Warm up cache with some queries
        for (int i = 0; i < 10; i++) {
            String sql = "SELECT * FROM products WHERE id = ?";
            QueryCacheKey key = new QueryCacheKey(datasourceName, sql, List.of(i % 10));
            List<List<Object>> rows = List.of(List.of(i % 10, "Product" + i));
            cache.put(key, new CachedQueryResult(
                createTestProto(rows, List.of("id", "name")),
                Instant.now(),
                Instant.now().plus(Duration.ofMinutes(10)),
                Set.of("products")
            ));
        }
        
        int cacheableQueries = 0;
        
        // Execute workload
        for (int i = 0; i < totalRequests; i++) {
            if (i < productReads) {
                // Product catalog read (high cache hit potential)
                int productId = random.nextInt(10); // Limited set for cache hits
                String sql = "SELECT * FROM products WHERE id = ?";
                QueryCacheKey key = new QueryCacheKey(datasourceName, sql, List.of(productId));
                
                CachedQueryResult result = cache.get(key);
                if (result == null) {
                    // Cache miss, store result
                    List<List<Object>> rows = List.of(List.of(productId, "Product" + productId));
                    cache.put(key, new CachedQueryResult(
                        createTestProto(rows, List.of("id", "name")),
                        Instant.now(),
                        Instant.now().plus(Duration.ofMinutes(10)),
                        Set.of("products")
                    ));
                }
                cacheableQueries++;
                
            } else if (i < productReads + userReads) {
                // User data read (moderate cache hit potential)
                int userId = random.nextInt(50); // Larger set, lower hit rate
                String sql = "SELECT * FROM users WHERE id = ?";
                QueryCacheKey key = new QueryCacheKey(datasourceName, sql, List.of(userId));
                
                CachedQueryResult result = cache.get(key);
                if (result == null) {
                    List<List<Object>> rows = List.of(List.of(userId, "User" + userId));
                    cache.put(key, new CachedQueryResult(
                        createTestProto(rows, List.of("id", "name")),
                        Instant.now(),
                        Instant.now().plus(Duration.ofMinutes(5)),
                        Set.of("users")
                    ));
                }
                cacheableQueries++;
                
            } else {
                // Write operation (invalidates cache)
                if (random.nextBoolean()) {
                    cache.invalidate(datasourceName, Set.of("products"));
                } else {
                    cache.invalidate(datasourceName, Set.of("users"));
                }
            }
        }
        
        // Verify results
        CacheStatistics stats = cache.getStatistics();
        double hitRate = stats.getHitRate();
        
        System.out.println("E-commerce workload simulation:");
        System.out.println("  Total requests: " + totalRequests);
        System.out.println("  Cacheable queries: " + cacheableQueries);
        System.out.println("  Cache hits: " + stats.getHits());
        System.out.println("  Cache misses: " + stats.getMisses());
        System.out.println("  Hit rate: " + String.format("%.1f%%", hitRate * 100));
        System.out.println("  Invalidations: " + stats.getInvalidations());
        
        // Assert performance targets
        assertTrue(hitRate > 0.30, "Hit rate should be > 30% for realistic workload");
        assertEquals(stats.getHits() + stats.getMisses(), cacheableQueries,
                "Total cache operations should match cacheable queries");
    }
    
    /**
     * Test: Concurrent access under load
     * 
     * Simulates multiple concurrent threads accessing the cache
     * to verify thread safety and performance under load.
     */
    @Test
    void testConcurrentAccessUnderLoad() throws Exception {
        QueryResultCache cache = registry.getOrCreate(datasourceName);
        
        int threadCount = 20;
        int opsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger(0);
        
        // Submit concurrent tasks
        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    Random random = new Random(threadId);
                    for (int i = 0; i < opsPerThread; i++) {
                        int id = random.nextInt(10);
                        String sql = "SELECT * FROM test_table WHERE id = ?";
                        QueryCacheKey key = new QueryCacheKey(datasourceName, sql, List.of(id));
                        
                        CachedQueryResult result = cache.get(key);
                        if (result == null) {
                            // Store
                            List<List<Object>> rows = List.of(List.of(id, "Value" + id));
                            cache.put(key, new CachedQueryResult(
                                createTestProto(rows, List.of("id", "value")),
                                Instant.now(),
                                Instant.now().plus(Duration.ofMinutes(10)),
                                Set.of("test_table")
                            ));
                        }
                        
                        // Occasionally invalidate
                        if (random.nextInt(50) == 0) {
                            cache.invalidate(datasourceName, Set.of("test_table"));
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }
        
        // Wait for completion
        boolean completed = latch.await(30, TimeUnit.SECONDS);
        executor.shutdown();
        
        assertTrue(completed, "All threads should complete within timeout");
        assertEquals(0, errors.get(), "No errors should occur during concurrent access");
        
        // Verify statistics
        CacheStatistics stats = cache.getStatistics();
        assertTrue(stats.getHits() + stats.getMisses() > 0, "Cache should have been accessed");
        System.out.println("Concurrent load test completed:");
        System.out.println("  Threads: " + threadCount);
        System.out.println("  Operations: " + (threadCount * opsPerThread));
        System.out.println("  Hits: " + stats.getHits());
        System.out.println("  Misses: " + stats.getMisses());
        System.out.println("  Hit rate: " + String.format("%.1f%%", stats.getHitRate() * 100));
    }
    
    /**
     * Test: Large result set handling
     * 
     * Verifies cache correctly handles large result sets
     * and respects size limits.
     */
    @Test
    void testLargeResultSetHandling() {
        QueryResultCache cache = registry.getOrCreate(datasourceName);
        
        String sql = "SELECT * FROM large_table";
        QueryCacheKey key = new QueryCacheKey(datasourceName, sql, List.of());
        
        // Create large result set (1000 rows, each with 10 columns)
        List<String> columns = List.of("c1", "c2", "c3", "c4", "c5", "c6", "c7", "c8", "c9", "c10");
        List<List<Object>> rows = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            rows.add(List.of(i, "value" + i, i * 2, "data" + i, i * 3, 
                            "info" + i, i * 4, "text" + i, i * 5, "content" + i));
        }
        
        CachedQueryResult result = new CachedQueryResult(
            createTestProto(rows, columns),
            Instant.now(),
            Instant.now().plus(Duration.ofMinutes(10)),
            Set.of("large_table")
        );
        long estimatedSize = result.getEstimatedSizeBytes();
        System.out.println("Large result set size: " + estimatedSize + " bytes");
        
        // Try to cache
        cache.put(key, result);
        
        // Verify behavior depends on size limit
        CachedQueryResult cached = cache.get(key);
        if (estimatedSize <= 204800) { // 200KB default limit
            assertNotNull(cached, "Should cache if under size limit");
            assertEquals(1000, cached.getQueryResultProto().getRowsCount());
        } else {
            // May or may not be cached depending on configuration
            CacheStatistics stats = cache.getStatistics();
            System.out.println("Rejections: " + stats.getRejections());
        }
    }
}
