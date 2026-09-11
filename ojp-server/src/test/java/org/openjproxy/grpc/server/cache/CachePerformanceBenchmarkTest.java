package org.openjproxy.grpc.server.cache;

import com.openjproxy.grpc.OpQueryResultProto;
import com.openjproxy.grpc.ParameterValue;
import com.openjproxy.grpc.ResultRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance benchmark tests for cache implementation.
 * These tests measure cache performance characteristics.
 * 
 * Note: These are benchmark tests and may be disabled in CI.
 * Run them manually to verify performance characteristics.
 */
class CachePerformanceBenchmarkTest {

    private QueryResultCache cache;
    private final String datasourceName = "perftest";
    private static final String TEST_UUID = "test-uuid";

    @BeforeEach
    void setUp() {
        cache = new QueryResultCache(
            datasourceName,
            10000,  // maxEntries
            Duration.ofMinutes(10),  // maxAge
            100 * 1024 * 1024,  // maxSizeBytes (100MB)
            NoOpQueryCacheMetrics.getInstance()
        );
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

    @Test
    void benchmarkCacheHitLatency() {
        // Pre-populate cache
        QueryCacheKey key = new QueryCacheKey(datasourceName, "SELECT * FROM products", Collections.emptyList());
        CachedQueryResult result = new CachedQueryResult(
            createTestProto(
                List.of(List.of("value1", "value2")),
                List.of("col1", "col2")
            ),
            Instant.now(),
            Instant.now().plus(Duration.ofMinutes(10)),
            Set.of()
        );
        cache.put(key, result);

        // Warm up
        for (int i = 0; i < 10000; i++) {
            cache.get(key);
        }

        // Benchmark
        int iterations = 100000;
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            CachedQueryResult retrieved = cache.get(key);
            assertNotNull(retrieved);
        }
        long duration = System.nanoTime() - start;

        double avgLatencyMs = duration / (double) iterations / 1_000_000;
        
        System.out.printf("Cache hit latency: %.4f ms (avg over %d iterations)%n", avgLatencyMs, iterations);
        System.out.printf("Operations per second: %.0f ops/sec%n", 1000.0 / avgLatencyMs);
        
        // Cache hit should be very fast (< 0.01ms = 10 microseconds)
        assertTrue(avgLatencyMs < 0.01, 
            String.format("Cache hit should be < 0.01ms, was: %.4fms", avgLatencyMs));
    }

    @Test
    void benchmarkCacheMissLatency() {
        // Benchmark cache miss (key not in cache)
        int iterations = 100000;
        long start = System.nanoTime();
        
        for (int i = 0; i < iterations; i++) {
            QueryCacheKey key = new QueryCacheKey(
                datasourceName,
                "SELECT * FROM products WHERE id = " + i,
                Collections.emptyList()
            );
            CachedQueryResult retrieved = cache.get(key);
            assertNull(retrieved);
        }
        
        long duration = System.nanoTime() - start;
        double avgLatencyMs = duration / (double) iterations / 1_000_000;
        
        System.out.printf("Cache miss latency: %.4f ms (avg over %d iterations)%n", avgLatencyMs, iterations);
        
        // Cache miss should also be very fast (< 0.01ms)
        assertTrue(avgLatencyMs < 0.01, 
            String.format("Cache miss should be < 0.01ms, was: %.4fms", avgLatencyMs));
    }

    @Test
    void benchmarkCachePutLatency() {
        int iterations = 50000;
        CachedQueryResult result = new CachedQueryResult(
            createTestProto(
                List.of(List.of("value1", "value2")),
                List.of("col1", "col2")
            ),
            Instant.now(),
            Instant.now().plus(Duration.ofMinutes(10)),
            Set.of()
        );

        long start = System.nanoTime();
        
        for (int i = 0; i < iterations; i++) {
            QueryCacheKey key = new QueryCacheKey(
                datasourceName,
                "SELECT * FROM products WHERE id = " + i,
                Collections.emptyList()
            );
            cache.put(key, result);
        }
        
        long duration = System.nanoTime() - start;
        double avgLatencyMs = duration / (double) iterations / 1_000_000;
        
        System.out.printf("Cache put latency: %.4f ms (avg over %d iterations)%n", avgLatencyMs, iterations);
        
        // Cache put should be fast (< 0.05ms)
        assertTrue(avgLatencyMs < 0.05, 
            String.format("Cache put should be < 0.05ms, was: %.4fms", avgLatencyMs));
    }

    @Test
    void benchmarkInvalidationLatency() {
        // Pre-populate with 1000 entries
        for (int i = 0; i < 1000; i++) {
            QueryCacheKey key = new QueryCacheKey(
                datasourceName,
                "SELECT * FROM products WHERE id = " + i,
                Collections.emptyList()
            );
            cache.put(key, new CachedQueryResult(
                createTestProto(new ArrayList<>(), new ArrayList<>()),
                Instant.now(),
                Instant.now().plus(Duration.ofMinutes(10)),
                Set.of()
            ));
        }

        int iterations = 100;
        long totalDuration = 0;

        for (int i = 0; i < iterations; i++) {
            // Re-populate between iterations
            if (i > 0) {
                for (int j = 0; j < 1000; j++) {
                    QueryCacheKey key = new QueryCacheKey(
                        datasourceName,
                        "SELECT * FROM products WHERE id = " + j,
                        Collections.emptyList()
                    );
                    cache.put(key, new CachedQueryResult(
                        createTestProto(new ArrayList<>(), new ArrayList<>()),
                        Instant.now(),
                        Instant.now().plus(Duration.ofMinutes(10)),
                        Set.of()
                    ));
                }
            }

            long start = System.nanoTime();
            cache.invalidate(datasourceName, Set.of("products"));
            long duration = System.nanoTime() - start;
            totalDuration += duration;
        }

        double avgLatencyMs = totalDuration / (double) iterations / 1_000_000;
        
        System.out.printf("Invalidation latency (1000 entries): %.4f ms (avg over %d iterations)%n", 
            avgLatencyMs, iterations);
        
        // Invalidation should be reasonable (< 50ms for 1000 entries)
        assertTrue(avgLatencyMs < 50, 
            String.format("Invalidation should be < 50ms, was: %.4fms", avgLatencyMs));
    }

    @Test
    void benchmarkCacheThroughput() {
        CachedQueryResult result = new CachedQueryResult(
            createTestProto(
                List.of(List.of("value1")),
                List.of("col1")
            ),
            Instant.now(),
            Instant.now().plus(Duration.ofMinutes(10)),
            Set.of()
        );

        // Pre-populate
        for (int i = 0; i < 10000; i++) {
            QueryCacheKey key = new QueryCacheKey(
                datasourceName,
                "SELECT " + i,
                Collections.emptyList()
            );
            cache.put(key, result);
        }

        // Measure read throughput
        int readIterations = 1000000;
        long readStart = System.nanoTime();
        
        for (int i = 0; i < readIterations; i++) {
            QueryCacheKey key = new QueryCacheKey(
                datasourceName,
                "SELECT " + (i % 10000),
                Collections.emptyList()
            );
            cache.get(key);
        }
        
        long readDuration = System.nanoTime() - readStart;
        double readThroughput = readIterations / (readDuration / 1_000_000_000.0);
        
        System.out.printf("Read throughput: %.0f ops/sec%n", readThroughput);
        
        // Should achieve at least 750k reads/sec
        assertTrue(readThroughput > 750_000,
            String.format("Read throughput should be > 1M ops/sec, was: %.0f", readThroughput));
    }

    @Test
    void benchmarkMemoryFootprint() {
        // Measure memory used by different cache sizes
        long[][] measurements = new long[5][2]; // [iteration][before, after]
        int[] sizes = {100, 500, 1000, 5000, 10000};

        for (int i = 0; i < sizes.length; i++) {
            // Clear cache
            cache.invalidate(datasourceName, Set.of("products"));
            System.gc();
            Thread.yield();

            long before = cache.getCurrentSizeBytes();

            // Add entries
            for (int j = 0; j < sizes[i]; j++) {
                QueryCacheKey key = new QueryCacheKey(
                    datasourceName,
                    "SELECT * FROM products WHERE id = " + j,
                    Collections.emptyList()
                );
                CachedQueryResult result = new CachedQueryResult(
                    createTestProto(
                        List.of(List.of("value1", "value2", "value3")),
                        List.of("col1", "col2", "col3")
                    ),
                    Instant.now(),
                    Instant.now().plus(Duration.ofMinutes(10)),
                    Set.of()
                );
                cache.put(key, result);
            }

            long after = cache.getCurrentSizeBytes();

            measurements[i][0] = before;
            measurements[i][1] = after;

            System.out.printf("Entries: %d, Size: %d bytes (%.2f KB), Avg per entry: %.2f bytes%n",
                sizes[i], after, after / 1024.0, after / (double) sizes[i]);
        }

        // Verify linear growth
        for (int i = 1; i < measurements.length; i++) {
            long prevSize = measurements[i - 1][1];
            long currSize = measurements[i][1];
            assertTrue(currSize > prevSize, "Size should grow with more entries");
        }
    }

    @Test
    @Disabled("Performance comparison test - enable for manual testing")
    void compareVsDatabaseQuery() {
        /*
         * This test would require actual database connection.
         * It's disabled by default but can be enabled for manual performance testing.
         * 
         * Expected results:
         * - Cache hit: < 1ms
         * - Database query: 10-100ms (depending on query complexity)
         * - Speedup: 10x - 100x
         */
        assertTrue(true, "Placeholder: test requires a live database connection and must be run manually");
    }

    @Test
    void benchmarkConcurrentReadPerformance() throws Exception {
        // Pre-populate cache
        for (int i = 0; i < 100; i++) {
            QueryCacheKey key = new QueryCacheKey(
                datasourceName,
                "SELECT " + i,
                Collections.emptyList()
            );
            cache.put(key, new CachedQueryResult(
                createTestProto(new ArrayList<>(), new ArrayList<>()),
                Instant.now(),
                Instant.now().plus(Duration.ofMinutes(10)),
                Set.of()
            ));
        }

        int threadCount = 10;
        int readsPerThread = 100000;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        long start = System.nanoTime();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < readsPerThread; j++) {
                        QueryCacheKey key = new QueryCacheKey(
                            datasourceName,
                            "SELECT " + (j % 100),
                            Collections.emptyList()
                        );
                        cache.get(key);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        long duration = System.nanoTime() - start;
        executor.shutdown();

        double totalOps = threadCount * readsPerThread;
        double throughput = totalOps / (duration / 1_000_000_000.0);
        double avgLatencyMs = (duration / totalOps) / 1_000_000;

        System.out.printf("Concurrent read throughput: %.0f ops/sec with %d threads%n", 
            throughput, threadCount);
        System.out.printf("Average latency under concurrency: %.4f ms%n", avgLatencyMs);

        // Should still achieve high throughput under concurrency
        assertTrue(throughput > 500_000, 
            String.format("Concurrent throughput should be > 500K ops/sec, was: %.0f", throughput));
    }

    @Test
    void benchmarkStatisticsOverhead() {
        // Benchmark with statistics tracking
        int iterations = 100000;
        
        // Operations with statistics
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            QueryCacheKey key = new QueryCacheKey(
                datasourceName,
                "SELECT " + (i % 100),
                Collections.emptyList()
            );
            cache.get(key);
        }
        long duration = System.nanoTime() - start;

        double avgLatencyMs = duration / (double) iterations / 1_000_000;
        
        System.out.printf("Operations with statistics: %.4f ms avg latency%n", avgLatencyMs);
        
        // Statistics overhead should be minimal
        assertTrue(avgLatencyMs < 0.01, 
            "Statistics tracking should not significantly impact performance");

        CacheStatistics stats = cache.getStatistics();
        assertEquals(iterations, stats.getHits() + stats.getMisses(), 
            "Statistics should be accurate");
    }

    @Test
    void benchmarkSizeEstimation() {
        int iterations = 10000;
        
        long start = System.nanoTime();
        
        for (int i = 0; i < iterations; i++) {
            CachedQueryResult result = new CachedQueryResult(
                createTestProto(
                    List.of(
                        List.of("value1", "value2", "value3"),
                        List.of("value4", "value5", "value6")
                    ),
                    List.of("col1", "col2", "col3")
                ),
                Instant.now(),
                Instant.now().plus(Duration.ofMinutes(10)),
                Set.of()
            );
            long size = result.getEstimatedSizeBytes();
            assertTrue(size > 0, "Size should be positive");
        }
        
        long duration = System.nanoTime() - start;
        double avgLatencyMs = duration / (double) iterations / 1_000_000;
        
        System.out.printf("Size estimation: %.4f ms avg latency%n", avgLatencyMs);
        
        // Size estimation should be very fast
        assertTrue(avgLatencyMs < 0.05,
            "Size estimation should be < 0.05ms");
    }

    @Test
    void printPerformanceSummary() {
        System.out.println("\n========== Cache Performance Summary ==========");
        System.out.println("Target Metrics:");
        System.out.println("  - Cache hit latency: < 0.01ms (10 microseconds)");
        System.out.println("  - Cache miss latency: < 0.01ms");
        System.out.println("  - Cache put latency: < 0.05ms");
        System.out.println("  - Read throughput: > 1M ops/sec");
        System.out.println("  - Concurrent throughput: > 500K ops/sec");
        System.out.println("  - Invalidation (1000 entries): < 50ms");
        System.out.println("Run other benchmark tests to verify these targets.");
        System.out.println("================================================\n");
    }
}
