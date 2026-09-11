package org.openjproxy.grpc.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for ServerConfiguration class.
 */
class ServerConfigurationTest {

    @AfterEach
    void cleanup() {
        // Clear any system properties set during tests
        System.clearProperty("ojp.server.port");
        System.clearProperty("ojp.prometheus.port");
        System.clearProperty("ojp.telemetry.enabled");
        System.clearProperty("ojp.opentelemetry.endpoint");
        System.clearProperty("ojp.server.threadPoolSize");
        System.clearProperty("ojp.server.virtualThreads.enabled");
        System.clearProperty("ojp.server.maxRequestSize");
        System.clearProperty("ojp.server.logLevel");
        System.clearProperty("ojp.server.allowedIps");
        System.clearProperty("ojp.server.connectionIdleTimeout");
        System.clearProperty("ojp.prometheus.allowedIps");
        System.clearProperty("ojp.telemetry.circuitbreaker.enabled");
        System.clearProperty("ojp.server.circuitBreakerTimeout");
        System.clearProperty("ojp.server.maxConcurrentRequests");
        System.clearProperty("ojp.server.admissionControl.maxQueueDepth");
        System.clearProperty("ojp.server.slowQuerySegregation.maxQueueDepth");
        System.clearProperty("ojp.server.slowQuerySegregation.classificationMode");
        System.clearProperty("ojp.server.slowQuerySegregation.slowQueryThresholdMs");
        System.clearProperty("ojp.server.slowQuerySegregation.minimumSlowQueryMs");
        System.clearProperty("ojp.server.slowQuerySegregation.slowMultiplier");
        System.clearProperty("ojp.server.slowQuerySegregation.recoveryMultiplier");
        System.clearProperty("ojp.server.slowQuerySegregation.minSamples");
        System.clearProperty("ojp.server.slowQuerySegregation.baselinePercentile");
        System.clearProperty("ojp.server.slowQuerySegregation.baselineRefreshIntervalSeconds");
        System.clearProperty("ojp.libs.path");
        System.clearProperty("ojp.resultset.rowsPerBlock");
        TestPropertyCleanupUtils.clearStatementCacheProperties();
    }

    @Test
    void testDefaultConfiguration() {
        ServerConfiguration config = new ServerConfiguration();

        assertEquals(ServerConfiguration.DEFAULT_SERVER_PORT, config.getServerPort());
        assertEquals(ServerConfiguration.DEFAULT_PROMETHEUS_PORT, config.getPrometheusPort());
        assertEquals(ServerConfiguration.DEFAULT_OPENTELEMETRY_ENABLED, config.isOpenTelemetryEnabled());
        assertEquals(ServerConfiguration.DEFAULT_OPENTELEMETRY_ENDPOINT, config.getOpenTelemetryEndpoint());
        assertEquals(ServerConfiguration.DEFAULT_THREAD_POOL_SIZE, config.getThreadPoolSize());
        assertEquals(ServerConfiguration.DEFAULT_VIRTUAL_THREADS_ENABLED, config.isVirtualThreadsEnabled());
        assertEquals(ServerConfiguration.DEFAULT_MAX_REQUEST_SIZE, config.getMaxRequestSize());
        assertEquals(ServerConfiguration.DEFAULT_LOG_LEVEL, config.getLogLevel());
        assertEquals(ServerConfiguration.DEFAULT_ALLOWED_IPS, config.getAllowedIps());
        assertEquals(ServerConfiguration.DEFAULT_CONNECTION_IDLE_TIMEOUT, config.getConnectionIdleTimeout());
        assertEquals(ServerConfiguration.DEFAULT_PROMETHEUS_ALLOWED_IPS, config.getPrometheusAllowedIps());
        assertEquals(ServerConfiguration.DEFAULT_CIRCUIT_BREAKER_TIMEOUT, config.getCircuitBreakerTimeout());
        assertEquals(ServerConfiguration.DEFAULT_TELEMETRY_CIRCUIT_BREAKER_ENABLED,
                config.isTelemetryCircuitBreakerMetricsEnabled());
        assertEquals(ServerConfiguration.DEFAULT_MAX_CONCURRENT_REQUESTS, config.getMaxConcurrentRequests());
        assertEquals(ServerConfiguration.DEFAULT_ADMISSION_CONTROL_MAX_QUEUE_DEPTH, config.getAdmissionControlMaxQueueDepth());
        assertEquals(ServerConfiguration.DEFAULT_SLOW_QUERY_CLASSIFICATION_MODE, config.getSlowQueryClassificationMode());
        assertEquals(ServerConfiguration.DEFAULT_SLOW_QUERY_THRESHOLD_MS, config.getSlowQueryThresholdMs());
        assertEquals(ServerConfiguration.DEFAULT_SLOW_QUERY_MINIMUM_SLOW_QUERY_MS, config.getSlowQueryMinimumSlowQueryMs());
        assertEquals(ServerConfiguration.DEFAULT_SLOW_QUERY_SLOW_MULTIPLIER, config.getSlowQuerySlowMultiplier());
        assertEquals(ServerConfiguration.DEFAULT_SLOW_QUERY_RECOVERY_MULTIPLIER, config.getSlowQueryRecoveryMultiplier());
        assertEquals(ServerConfiguration.DEFAULT_SLOW_QUERY_MIN_SAMPLES, config.getSlowQueryMinSamples());
        assertEquals(ServerConfiguration.DEFAULT_SLOW_QUERY_BASELINE_PERCENTILE, config.getSlowQueryBaselinePercentile());
        assertEquals(ServerConfiguration.DEFAULT_SLOW_QUERY_BASELINE_REFRESH_INTERVAL_SECONDS,
                config.getSlowQueryBaselineRefreshIntervalSeconds());
        assertEquals(ServerConfiguration.DEFAULT_CIRCUIT_BREAKER_THRESHOLD, config.getCircuitBreakerThreshold());
        assertTrue(config.isStatementCacheEnabled());
        assertEquals(250, config.getStatementCacheMaxSize());
        assertEquals(2048, config.getStatementCacheSqlLimit());
        assertTrue(config.isStatementCacheServerPrepare());
        assertEquals(5, config.getStatementCachePrepareThreshold());
        assertTrue(config.isXaStatementCacheEnabled());
        assertEquals(250, config.getXaStatementCacheMaxSize());
        assertEquals(2048, config.getXaStatementCacheSqlLimit());
        assertTrue(config.isXaStatementCacheServerPrepare());
        assertEquals(5, config.getXaStatementCachePrepareThreshold());
    }

    @Test
    void testJvmSystemPropertiesOverride() {
        // Set JVM system properties
        System.setProperty("ojp.server.port", "8080");
        System.setProperty("ojp.prometheus.port", "9091");
        System.setProperty("ojp.telemetry.enabled", "false");
        System.setProperty("ojp.opentelemetry.endpoint", "http://localhost:4317");
        System.setProperty("ojp.server.threadPoolSize", "100");
        System.setProperty("ojp.server.virtualThreads.enabled", "false");
        System.setProperty("ojp.server.maxRequestSize", "8388608"); // 8MB
        System.setProperty("ojp.server.logLevel", "DEBUG");
        System.setProperty("ojp.server.allowedIps", "192.168.1.0/24,10.0.0.1");
        System.setProperty("ojp.server.connectionIdleTimeout", "60000");
        System.setProperty("ojp.prometheus.allowedIps", "127.0.0.1,192.168.1.0/24");
        System.setProperty("ojp.server.circuitBreakerTimeout", "120000");
        System.setProperty("ojp.server.maxConcurrentRequests", "123");
        System.setProperty("ojp.server.admissionControl.maxQueueDepth", "77");

        ServerConfiguration config = new ServerConfiguration();

        assertEquals(8080, config.getServerPort());
        assertEquals(9091, config.getPrometheusPort());
        assertFalse(config.isOpenTelemetryEnabled());
        assertEquals("http://localhost:4317", config.getOpenTelemetryEndpoint());
        assertEquals(100, config.getThreadPoolSize());
        assertFalse(config.isVirtualThreadsEnabled());
        assertEquals(8388608, config.getMaxRequestSize());
        assertEquals("DEBUG", config.getLogLevel());
        assertEquals(List.of("192.168.1.0/24", "10.0.0.1"), config.getAllowedIps());
        assertEquals(60000, config.getConnectionIdleTimeout());
        assertEquals(List.of("127.0.0.1", "192.168.1.0/24"), config.getPrometheusAllowedIps());
        assertEquals(120000, config.getCircuitBreakerTimeout());
        assertEquals(123, config.getMaxConcurrentRequests());
        assertEquals(77, config.getAdmissionControlMaxQueueDepth());
    }

    @Test
    void testCanDisableCircuitBreakerMetricsIndependently() {
        System.setProperty("ojp.telemetry.enabled", "true");
        System.setProperty("ojp.telemetry.circuitbreaker.enabled", "false");

        ServerConfiguration config = new ServerConfiguration();

        assertTrue(config.isOpenTelemetryEnabled());
        assertFalse(config.isTelemetryCircuitBreakerMetricsEnabled());
    }

    @Test
    void testLegacySlowQueryQueueDepthPropertyFallback() {
        System.setProperty("ojp.server.slowQuerySegregation.maxQueueDepth", "88");

        ServerConfiguration config = new ServerConfiguration();

        assertEquals(88, config.getAdmissionControlMaxQueueDepth());
    }

    @Test
    void testSlowQueryClassificationProperties() {
        System.setProperty("ojp.server.slowQuerySegregation.classificationMode", "ABSOLUTE_THRESHOLD");
        System.setProperty("ojp.server.slowQuerySegregation.slowQueryThresholdMs", "2500");
        System.setProperty("ojp.server.slowQuerySegregation.minimumSlowQueryMs", "120");
        System.setProperty("ojp.server.slowQuerySegregation.slowMultiplier", "6.0");
        System.setProperty("ojp.server.slowQuerySegregation.recoveryMultiplier", "2.5");
        System.setProperty("ojp.server.slowQuerySegregation.minSamples", "30");
        System.setProperty("ojp.server.slowQuerySegregation.baselinePercentile", "60");
        System.setProperty("ojp.server.slowQuerySegregation.baselineRefreshIntervalSeconds", "15");

        ServerConfiguration config = new ServerConfiguration();

        assertEquals(SlowQueryClassificationMode.ABSOLUTE_THRESHOLD, config.getSlowQueryClassificationMode());
        assertEquals(2500L, config.getSlowQueryThresholdMs());
        assertEquals(120L, config.getSlowQueryMinimumSlowQueryMs());
        assertEquals(6.0, config.getSlowQuerySlowMultiplier(), 0.001);
        assertEquals(2.5, config.getSlowQueryRecoveryMultiplier(), 0.001);
        assertEquals(30, config.getSlowQueryMinSamples());
        assertEquals(60, config.getSlowQueryBaselinePercentile());
        assertEquals(15L, config.getSlowQueryBaselineRefreshIntervalSeconds());
    }

    @Test
    void testInvalidSlowQueryClassificationPropertiesFallbackToDefault() {
        System.setProperty("ojp.server.slowQuerySegregation.classificationMode", "NOT_A_MODE");
        System.setProperty("ojp.server.slowQuerySegregation.slowQueryThresholdMs", "-1");
        System.setProperty("ojp.server.slowQuerySegregation.minimumSlowQueryMs", "-1");
        System.setProperty("ojp.server.slowQuerySegregation.slowMultiplier", "1.0");
        System.setProperty("ojp.server.slowQuerySegregation.recoveryMultiplier", "10.0");
        System.setProperty("ojp.server.slowQuerySegregation.minSamples", "0");
        System.setProperty("ojp.server.slowQuerySegregation.baselinePercentile", "100");
        System.setProperty("ojp.server.slowQuerySegregation.baselineRefreshIntervalSeconds", "-1");

        ServerConfiguration config = new ServerConfiguration();

        assertEquals(ServerConfiguration.DEFAULT_SLOW_QUERY_CLASSIFICATION_MODE, config.getSlowQueryClassificationMode());
        assertEquals(ServerConfiguration.DEFAULT_SLOW_QUERY_THRESHOLD_MS, config.getSlowQueryThresholdMs());
        assertEquals(ServerConfiguration.DEFAULT_SLOW_QUERY_MINIMUM_SLOW_QUERY_MS, config.getSlowQueryMinimumSlowQueryMs());
        assertEquals(ServerConfiguration.DEFAULT_SLOW_QUERY_SLOW_MULTIPLIER, config.getSlowQuerySlowMultiplier());
        assertEquals(ServerConfiguration.DEFAULT_SLOW_QUERY_RECOVERY_MULTIPLIER, config.getSlowQueryRecoveryMultiplier());
        assertEquals(ServerConfiguration.DEFAULT_SLOW_QUERY_MIN_SAMPLES, config.getSlowQueryMinSamples());
        assertEquals(ServerConfiguration.DEFAULT_SLOW_QUERY_BASELINE_PERCENTILE, config.getSlowQueryBaselinePercentile());
        assertEquals(ServerConfiguration.DEFAULT_SLOW_QUERY_BASELINE_REFRESH_INTERVAL_SECONDS,
                config.getSlowQueryBaselineRefreshIntervalSeconds());
    }

    @Test
    void testRecoveryMultiplierEqualToSlowMultiplierFallsBack() {
        System.setProperty("ojp.server.slowQuerySegregation.slowMultiplier", "5.0");
        System.setProperty("ojp.server.slowQuerySegregation.recoveryMultiplier", "5.0");

        ServerConfiguration config = new ServerConfiguration();

        assertEquals(ServerConfiguration.DEFAULT_SLOW_QUERY_RECOVERY_MULTIPLIER,
                config.getSlowQueryRecoveryMultiplier(), 0.001);
    }

    @Test
    void testInvalidIntegerValues() {
        System.setProperty("ojp.server.port", "invalid");
        System.setProperty("ojp.prometheus.port", "not-a-number");
        System.setProperty("ojp.server.threadPoolSize", "abc");
        System.setProperty("ojp.server.maxConcurrentRequests", "-2");
        System.setProperty("ojp.server.admissionControl.maxQueueDepth", "-3");
        System.setProperty("ojp.server.circuitBreakerThreshold", "xyz");
        System.setProperty("ojp.connection.pool.statementCache.maxSize", "-1");
        System.setProperty("ojp.connection.pool.statementCache.sqlLimit", "invalid");
        System.setProperty("ojp.connection.pool.statementCache.prepareThreshold", "-2");

        ServerConfiguration config = new ServerConfiguration();

        // Should fall back to defaults for invalid values
        assertEquals(ServerConfiguration.DEFAULT_SERVER_PORT, config.getServerPort());
        assertEquals(ServerConfiguration.DEFAULT_PROMETHEUS_PORT, config.getPrometheusPort());
        assertEquals(ServerConfiguration.DEFAULT_THREAD_POOL_SIZE, config.getThreadPoolSize());
        assertEquals(ServerConfiguration.DEFAULT_MAX_CONCURRENT_REQUESTS, config.getMaxConcurrentRequests());
        assertEquals(ServerConfiguration.DEFAULT_ADMISSION_CONTROL_MAX_QUEUE_DEPTH, config.getAdmissionControlMaxQueueDepth());
        assertEquals(ServerConfiguration.DEFAULT_CIRCUIT_BREAKER_THRESHOLD, config.getCircuitBreakerThreshold());
        assertEquals(ServerConfiguration.DEFAULT_STATEMENT_CACHE_MAX_SIZE, config.getStatementCacheMaxSize());
        assertEquals(ServerConfiguration.DEFAULT_STATEMENT_CACHE_SQL_LIMIT, config.getStatementCacheSqlLimit());
        assertEquals(ServerConfiguration.DEFAULT_STATEMENT_CACHE_PREPARE_THRESHOLD, config.getStatementCachePrepareThreshold());
    }

    @Test
    void testInvalidLongValues() {
        System.setProperty("ojp.server.connectionIdleTimeout", "invalid-long");
        System.setProperty("ojp.server.circuitBreakerTimeout", "not-a-number");

        ServerConfiguration config = new ServerConfiguration();

        // Should fall back to default for invalid values
        assertEquals(ServerConfiguration.DEFAULT_CONNECTION_IDLE_TIMEOUT, config.getConnectionIdleTimeout());
    }

    @Test
    void testBooleanValues() {
        System.setProperty("ojp.telemetry.enabled", "true");

        ServerConfiguration config = new ServerConfiguration();

        assertTrue(config.isOpenTelemetryEnabled());
    }

    @Test
    void testListProperties() {
        System.setProperty("ojp.server.allowedIps", "192.168.1.1, 10.0.0.0/8 , 172.16.0.1");
        System.setProperty("ojp.prometheus.allowedIps", "127.0.0.1,192.168.0.0/16");

        ServerConfiguration config = new ServerConfiguration();

        assertEquals(List.of("192.168.1.1", "10.0.0.0/8", "172.16.0.1"), config.getAllowedIps());
        assertEquals(List.of("127.0.0.1", "192.168.0.0/16"), config.getPrometheusAllowedIps());
    }

    @Test
    void testEmptyListProperties() {
        System.setProperty("ojp.server.allowedIps", "");
        System.setProperty("ojp.prometheus.allowedIps", " ");

        ServerConfiguration config = new ServerConfiguration();

        assertEquals(ServerConfiguration.DEFAULT_ALLOWED_IPS, config.getAllowedIps());
        assertEquals(ServerConfiguration.DEFAULT_PROMETHEUS_ALLOWED_IPS, config.getPrometheusAllowedIps());
    }

    @Test
    void testGettersReturnDefensiveCopies() {
        ServerConfiguration config = new ServerConfiguration();

        List<String> allowedIps = config.getAllowedIps();
        List<String> prometheusAllowedIps = config.getPrometheusAllowedIps();

        // Modifying returned lists should not affect the configuration
        allowedIps.clear();
        prometheusAllowedIps.clear();

        assertFalse(config.getAllowedIps().isEmpty());
        assertFalse(config.getPrometheusAllowedIps().isEmpty());
    }

    @Test
    void testDriversPathConfiguration() {
        // Test default value
        ServerConfiguration config = new ServerConfiguration();
        assertEquals(ServerConfiguration.DEFAULT_DRIVERS_PATH, config.getDriversPath());

        // Test custom value via system property
        System.setProperty("ojp.libs.path", "/custom/path/to/libs");
        config = new ServerConfiguration();
        assertEquals("/custom/path/to/libs", config.getDriversPath());
    }

    @Test
    void testDefaultSessionCleanupConfiguration() {
        ServerConfiguration config = new ServerConfiguration();

        assertEquals(ServerConfiguration.DEFAULT_SESSION_CLEANUP_ENABLED, config.isSessionCleanupEnabled());
        assertEquals(ServerConfiguration.DEFAULT_SESSION_TIMEOUT_MINUTES, config.getSessionTimeoutMinutes());
        assertEquals(ServerConfiguration.DEFAULT_SESSION_CLEANUP_INTERVAL_MINUTES, config.getSessionCleanupIntervalMinutes());
    }

    @Test
    void testCustomSessionCleanupConfiguration() {
        // Set custom properties
        System.setProperty("ojp.server.sessionCleanup.enabled", "false");
        System.setProperty("ojp.server.sessionCleanup.timeoutMinutes", "60");
        System.setProperty("ojp.server.sessionCleanup.intervalMinutes", "10");

        ServerConfiguration config = new ServerConfiguration();

        assertFalse(config.isSessionCleanupEnabled());
        assertEquals(60, config.getSessionTimeoutMinutes());
        assertEquals(10, config.getSessionCleanupIntervalMinutes());

        // Cleanup
        System.clearProperty("ojp.server.sessionCleanup.enabled");
        System.clearProperty("ojp.server.sessionCleanup.timeoutMinutes");
        System.clearProperty("ojp.server.sessionCleanup.intervalMinutes");
    }

    @Test
    void testDefaultTracingConfiguration() {
        ServerConfiguration config = new ServerConfiguration();

        assertFalse(config.isTracingEnabled());
        assertEquals(ServerConfiguration.DEFAULT_TRACING_ENDPOINT, config.getTracingEndpoint());
        assertEquals(ServerConfiguration.DEFAULT_TRACING_EXPORTER, config.getTracingExporter());
        assertEquals(ServerConfiguration.DEFAULT_TRACING_SERVICE_NAME, config.getTracingServiceName());
        assertEquals(ServerConfiguration.DEFAULT_TRACING_SAMPLE_RATE, config.getTracingSampleRate(), 0.001);
    }

    @Test
    void testCustomTracingConfiguration() {
        System.setProperty("ojp.tracing.enabled", "true");
        System.setProperty("ojp.tracing.endpoint", "http://zipkin:9411/api/v2/spans");
        System.setProperty("ojp.tracing.exporter", "zipkin");
        System.setProperty("ojp.tracing.serviceName", "my-ojp");
        System.setProperty("ojp.tracing.sampleRate", "0.5");

        ServerConfiguration config = new ServerConfiguration();

        assertTrue(config.isTracingEnabled());
        assertEquals("http://zipkin:9411/api/v2/spans", config.getTracingEndpoint());
        assertEquals("zipkin", config.getTracingExporter());
        assertEquals("my-ojp", config.getTracingServiceName());
        assertEquals(0.5, config.getTracingSampleRate(), 0.001);

        // Cleanup
        System.clearProperty("ojp.tracing.enabled");
        System.clearProperty("ojp.tracing.endpoint");
        System.clearProperty("ojp.tracing.exporter");
        System.clearProperty("ojp.tracing.serviceName");
        System.clearProperty("ojp.tracing.sampleRate");
    }

    @Test
    void testOtlpTracingExporterConfiguration() {
        System.setProperty("ojp.tracing.enabled", "true");
        System.setProperty("ojp.tracing.exporter", "otlp");
        System.setProperty("ojp.tracing.endpoint", "http://jaeger:4317");

        ServerConfiguration config = new ServerConfiguration();

        assertTrue(config.isTracingEnabled());
        assertEquals("otlp", config.getTracingExporter());
        assertEquals("http://jaeger:4317", config.getTracingEndpoint());

        // Cleanup
        System.clearProperty("ojp.tracing.enabled");
        System.clearProperty("ojp.tracing.exporter");
        System.clearProperty("ojp.tracing.endpoint");
    }

    @Test
    void testInvalidTracingSampleRate() {
        System.setProperty("ojp.tracing.sampleRate", "not-a-number");

        ServerConfiguration config = new ServerConfiguration();

        assertEquals(ServerConfiguration.DEFAULT_TRACING_SAMPLE_RATE, config.getTracingSampleRate(), 0.001);

        System.clearProperty("ojp.tracing.sampleRate");
    }

    @Test
    void testResultsetRowsPerBlockDefaultValue() {
        ServerConfiguration config = new ServerConfiguration();

        assertEquals(100, config.getResultsetRowsPerBlock());
        assertEquals(ServerConfiguration.DEFAULT_RESULTSET_ROWS_PER_BLOCK, config.getResultsetRowsPerBlock());
    }

    @ParameterizedTest
    @CsvSource({
            "250, 250",
            "1, 1",
            "10000, 10000"
    })
    void testResultsetRowsPerBlockValidValues(String propertyValue, int expectedRowsPerBlock) {
        System.setProperty("ojp.resultset.rowsPerBlock", propertyValue);

        ServerConfiguration config = new ServerConfiguration();

        assertEquals(expectedRowsPerBlock, config.getResultsetRowsPerBlock());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "10001", "-1", "not-a-number"})
    void testResultsetRowsPerBlockInvalidValuesFallBackToDefault(String propertyValue) {
        System.setProperty("ojp.resultset.rowsPerBlock", propertyValue);

        ServerConfiguration config = new ServerConfiguration();

        assertEquals(ServerConfiguration.DEFAULT_RESULTSET_ROWS_PER_BLOCK, config.getResultsetRowsPerBlock());
    }
}
