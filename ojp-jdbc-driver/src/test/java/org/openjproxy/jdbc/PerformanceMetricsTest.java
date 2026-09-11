package org.openjproxy.jdbc;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PerformanceMetrics utility class.
 */
class PerformanceMetricsTest {

    @Test
    void testCalculatePercentile() {
        List<Long> values = List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);

        // Test p50 (median)
        double p50 = PerformanceMetrics.calculatePercentile(values, 50);
        assertEquals(5.0, p50, 0.01);

        // Test p95
        double p95 = PerformanceMetrics.calculatePercentile(values, 95);
        assertEquals(10.0, p95, 0.01);

        // Test p99
        double p99 = PerformanceMetrics.calculatePercentile(values, 99);
        assertEquals(10.0, p99, 0.01);
    }

    @Test
    void testCalculatePercentileEmptyList() {
        List<Long> values = List.of();

        double p50 = PerformanceMetrics.calculatePercentile(values, 50);
        assertEquals(0.0, p50, 0.01);
    }

    @Test
    void testCalculatePercentileSingleValue() {
        List<Long> values = List.of(42L);

        double p50 = PerformanceMetrics.calculatePercentile(values, 50);
        assertEquals(42.0, p50, 0.01);
    }

    @Test
    void testCalculateMax() {
        List<Long> values = List.of(1L, 5L, 3L, 9L, 2L);

        long max = PerformanceMetrics.calculateMax(values);
        assertEquals(9L, max);
    }

    @Test
    void testCalculateMaxEmptyList() {
        List<Long> values = List.of();

        long max = PerformanceMetrics.calculateMax(values);
        assertEquals(0L, max);
    }

    @Test
    void testCalculateThroughput() {
        int totalOperations = 1000;
        long totalTimeMs = 10000; // 10 seconds

        double throughput = PerformanceMetrics.calculateThroughput(totalOperations, totalTimeMs);
        assertEquals(100.0, throughput, 0.01);
    }

    @Test
    void testCalculateThroughputZeroTime() {
        int totalOperations = 1000;
        long totalTimeMs = 0;

        double throughput = PerformanceMetrics.calculateThroughput(totalOperations, totalTimeMs);
        assertEquals(0.0, throughput, 0.01);
    }

    @Test
    void testCollectJvmStatistics() {
        PerformanceMetrics.JvmStatistics stats = PerformanceMetrics.collectJvmStatistics();

        // Just verify that we can collect stats without errors
        assertNotNull(stats);
        assertTrue(stats.getGcCount() >= 0);
        assertTrue(stats.getGcPauseTotalMs() >= 0);
        assertTrue(stats.getHeapUsedMb() >= 0);
        assertTrue(stats.getHeapMaxMb() >= 0);
        assertTrue(stats.getHeapCommittedMb() >= 0);

        // CPU loads may be -1 if not available
        assertTrue(stats.getProcessCpuLoad() >= -1);
        assertTrue(stats.getSystemCpuLoad() >= -1);
    }

    @Test
    void testGeneratePerformanceReport() {
        List<Long> durations = List.of(
                1000000L, 2000000L, 3000000L, 4000000L, 5000000L, // 1-5ms in nanoseconds
                6000000L, 7000000L, 8000000L, 9000000L, 10000000L // 6-10ms in nanoseconds
        );

        String report = PerformanceMetrics.generatePerformanceReport(durations, 10, 1000);

        assertNotNull(report);
        assertTrue(report.contains("LATENCY METRICS"));
        assertTrue(report.contains("Average latency:"));
        assertTrue(report.contains("P50 latency:"));
        assertTrue(report.contains("P95 latency:"));
        assertTrue(report.contains("P99 latency:"));
        assertTrue(report.contains("Max latency:"));
        assertTrue(report.contains("THROUGHPUT"));
        assertTrue(report.contains("Throughput:"));
    }

    @Test
    void testJvmStatisticsToString() {
        PerformanceMetrics.JvmStatistics stats = PerformanceMetrics.collectJvmStatistics();

        String output = stats.toString();
        assertNotNull(output);
        assertTrue(output.contains("GC Pause Total:"));
        assertTrue(output.contains("GC Count:"));
        assertTrue(output.contains("Heap Usage:"));
    }
}
