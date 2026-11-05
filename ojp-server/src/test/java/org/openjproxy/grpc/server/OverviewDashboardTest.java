package org.openjproxy.grpc.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.metrics.IMetricsTracker;
import com.zaxxer.hikari.metrics.MetricsTrackerFactory;
import com.zaxxer.hikari.metrics.PoolStats;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.protobuf.services.HealthStatusManager;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthGrpc;
import io.opentelemetry.exporter.prometheus.PrometheusHttpServer;
import io.opentelemetry.instrumentation.grpc.v1_6.GrpcTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import org.junit.jupiter.api.Test;
import org.openjproxy.grpc.server.cache.OpenTelemetryQueryCacheMetrics;
import org.openjproxy.grpc.server.metrics.OpenTelemetrySqlStatementMetrics;
import org.openjproxy.xa.pool.commons.metrics.OpenTelemetryPoolMetrics;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class OverviewDashboardTest {
    private static final Pattern SELECTOR = Pattern.compile("([a-zA-Z_:][a-zA-Z0-9_:]*)\\{([^}]*)}");
    private static final String DATASOURCE = "${DS_PROMETHEUS}";

    private JsonNode dashboard() throws Exception {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null && !Files.isRegularFile(
                directory.resolve("documents/monitoring/ojp-overview-dashboard.json"))) {
            directory = directory.getParent();
        }
        assertNotNull(directory, "Dashboard must be available in the repository");
        return new ObjectMapper().readTree(directory.resolve(
                "documents/monitoring/ojp-overview-dashboard.json").toFile());
    }

    private List<JsonNode> panels(JsonNode dashboard) {
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode panel : dashboard.path("panels")) {
            result.add(panel);
            result.addAll(panels(panel));
        }
        return result;
    }

    @Test
    void shouldUseModernStandalonePanelsAndValidLayout() throws Exception {
        JsonNode dashboard = dashboard();
        assertEquals("ojp-overview", dashboard.path("uid").asText());
        assertTrue(dashboard.path("schemaVersion").asInt() >= 38);
        assertFalse(dashboard.has("__inputs"), "Datasource variable works without import-time rewriting");
        Set<Integer> ids = new HashSet<>();
        for (JsonNode panel : panels(dashboard)) {
            assertTrue(ids.add(panel.path("id").asInt()), "Unique panel ID: " + panel);
            assertTrue(Set.of("row", "stat", "timeseries", "table", "state-timeline", "text")
                    .contains(panel.path("type").asText()));
            JsonNode grid = panel.path("gridPos");
            assertTrue(grid.path("x").asInt() >= 0 && grid.path("y").asInt() >= 0);
            assertTrue(grid.path("w").asInt() > 0 && grid.path("h").asInt() > 0);
            assertTrue(grid.path("x").asInt() + grid.path("w").asInt() <= 24);
            Set<String> refs = new HashSet<>();
            for (JsonNode target : panel.path("targets")) {
                assertTrue(refs.add(target.path("refId").asText()));
                assertEquals(DATASOURCE, panel.path("datasource").path("uid").asText());
                assertEquals(DATASOURCE, target.path("datasource").path("uid").asText());
            }
        }
        assertTrue(ids.size() >= 30, "Operational and drill-down coverage");
        assertNoOverlap(dashboard.path("panels"));
        for (JsonNode panel : panels(dashboard)) {
            if (panel.path("collapsed").asBoolean()) {
                assertNoOverlap(panel.path("panels"));
            }
        }
    }

    private void assertNoOverlap(JsonNode siblings) {
        for (int i = 0; i < siblings.size(); i++) {
            for (int j = i + 1; j < siblings.size(); j++) {
                JsonNode a = siblings.get(i).path("gridPos");
                JsonNode b = siblings.get(j).path("gridPos");
                boolean overlap = a.path("x").asInt() < b.path("x").asInt() + b.path("w").asInt()
                        && b.path("x").asInt() < a.path("x").asInt() + a.path("w").asInt()
                        && a.path("y").asInt() < b.path("y").asInt() + b.path("h").asInt()
                        && b.path("y").asInt() < a.path("y").asInt() + a.path("h").asInt();
                assertFalse(overlap, "Overlapping sibling panels " + i + " and " + j);
            }
        }
    }

    @Test
    void shouldKeepRequiredVariablesIndependentOfOptionalMetrics() throws Exception {
        JsonNode variables = dashboard().path("templating").path("list");
        assertEquals(5, variables.size());
        assertEquals("DS_PROMETHEUS", variables.get(0).path("name").asText());
        assertEquals("datasource", variables.get(0).path("type").asText());
        assertEquals("prometheus", variables.get(0).path("query").asText());
        for (int i = 1; i < variables.size(); i++) {
            JsonNode variable = variables.get(i);
            assertTrue(variable.path("multi").asBoolean());
            assertTrue(variable.path("includeAll").asBoolean());
            assertEquals(".*", variable.path("allValue").asText());
            assertEquals(DATASOURCE, variable.path("datasource").path("uid").asText());
            String query = variable.path("query").path("query").asText();
            if (i < 3) {
                assertTrue(query.startsWith("label_values(up"));
                assertFalse(query.contains("pool") || query.contains("datasource"));
            } else {
                assertTrue(query.contains("ojp_"), "Optional variable must be metric-derived");
            }
        }
    }

    @Test
    void shouldScopeEverySelectorAndAvoidMisleadingAggregations() throws Exception {
        for (JsonNode panel : panels(dashboard())) {
            for (JsonNode target : panel.path("targets")) {
                String query = target.path("expr").asText();
                assertFalse(query.contains("vector(0)") || query.contains("clamp_min("));
                Matcher selectors = SELECTOR.matcher(query);
                assertTrue(selectors.find(), "Expected explicit selector: " + query);
                selectors.reset();
                while (selectors.find()) {
                    String metric = selectors.group(1);
                    String labels = selectors.group(2);
                    assertTrue(labels.contains("job=~\"${job:regex}\""), query);
                    assertTrue(labels.contains("instance=~\"${instance:regex}\""), query);
                    assertEquals(metric.contains("_pool_"), labels.contains("pool_name=~\"${pool:regex}\""), query);
                    assertEquals(metric.startsWith("ojp_cache_") || metric.startsWith("ojp_query_")
                            || metric.startsWith("ojp_circuit_breaker_"),
                            labels.contains("datasource=~\"${datasource:regex}\""), query);
                }
                if (query.contains("_bucket")) {
                    assertTrue(query.contains("histogram_quantile(") && query.contains("le"), query);
                    assertEquals("ms", panel.path("fieldConfig").path("defaults").path("unit").asText()
                            .equals("s") ? "ms" : panel.path("fieldConfig").path("defaults").path("unit").asText());
                }
                if (query.contains("_pool_")) {
                    assertTrue(query.contains("instance") && query.contains("pool_name"));
                    assertFalse(query.contains("ojp_hikari_") && query.contains("ojp_xa_"), query);
                }
                if (query.contains(" / ")) {
                    assertTrue(query.contains(" > 0"), "Filter zero denominator: " + query);
                }
                if (panel.path("type").asText().equals("table") && query.contains("sql_statement")) {
                    assertTrue(query.startsWith("topk(10, "), query);
                    assertTrue(target.path("instant").asBoolean());
                    assertTrue(query.contains("[$__range]"), query);
                }
            }
        }
    }

    @Test
    void shouldMatchActualExporterNamesAndLabelsIncludingRpc() throws Exception {
        try (PrometheusHttpServer exporter = PrometheusHttpServer.builder().setHost("127.0.0.1").setPort(0).build();
             SdkMeterProvider provider = SdkMeterProvider.builder().registerMetricReader(exporter).build();
             OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setMeterProvider(provider).build();
             OpenTelemetrySqlStatementMetrics sql = new OpenTelemetrySqlStatementMetrics(sdk);
             OpenTelemetryQueryCacheMetrics cache = new OpenTelemetryQueryCacheMetrics(sdk);
             OpenTelemetryPoolMetrics xa = new OpenTelemetryPoolMetrics(sdk, "fixture-xa")) {
            sql.recordSqlExecution("SELECT 1", 25, true);
            cache.recordCacheHit("fixture", "SELECT 1");
            cache.recordCacheMiss("fixture", "SELECT 1");
            cache.recordCacheEviction("fixture", "size");
            cache.recordCacheInvalidation("fixture", "fixture_table");
            cache.recordCacheRejection("fixture", 128);
            cache.updateCacheSize("fixture", 1, 64);
            cache.recordQueryExecutionTime("fixture", "cache", 1);
            cache.recordQueryExecutionTime("fixture", "database", 25);
            xa.recordPoolState("fixture-xa", 2, 3, 1, 10, 1, 5, 0, 2, 0);
            xa.recordConnectionAcquisitionTime("fixture-xa", 5);
            CircuitBreakerMetrics circuits = new CircuitBreakerMetrics(sdk);
            circuits.updateState("fixture", "fixture-query", CircuitBreakerMetrics.State.OPEN,
                    CircuitBreakerMetrics.TripReason.FAILURE_THRESHOLD);
            circuits.recordBlockedCall("fixture", "fixture-query");
            circuits.updateState("fixture", "fixture-query", CircuitBreakerMetrics.State.HALF_OPEN);
            recordHikari(sdk);
            recordRpc(sdk);
            HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + exporter.getAddress().getPort() + "/metrics"))
                    .header("Accept", "text/plain; version=0.0.4").build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            String scrape = response.body();
            for (JsonNode panel : panels(dashboard())) {
                for (JsonNode target : panel.path("targets")) {
                    Matcher selectors = SELECTOR.matcher(target.path("expr").asText());
                    while (selectors.find()) {
                        String metric = selectors.group(1);
                        if (metric.equals("up")) {
                            continue; // Prometheus supplies scrape health, not OJP's exporter.
                        }
                        Matcher sample = Pattern.compile("(?m)^" + metric + "\\{([^}]*)} [^\\n]+$").matcher(scrape);
                        assertTrue(sample.find(), "Not exported by real recorders: " + metric + "\n" + scrape);
                        String labels = sample.group(1);
                        if (metric.contains("_pool_")) {
                            assertTrue(labels.contains("pool_name=\"fixture-"));
                        } else if (metric.startsWith("ojp_sql_")) {
                            assertTrue(labels.contains("sql_statement=\"SELECT 1\""));
                            assertFalse(labels.contains("datasource="));
                        } else if (metric.startsWith("ojp_")) {
                            assertTrue(labels.contains("datasource=\"fixture\""));
                        } else if (metric.startsWith("rpc_")) {
                            assertTrue(labels.contains("rpc_system=\"grpc\""));
                        }
                    }
                }
            }
            Files.createDirectories(Path.of("target/dashboard-validation"));
            Files.writeString(Path.of("target/dashboard-validation/actual-exporter.prom"), scrape);
        }
    }

    private void recordHikari(OpenTelemetrySdk sdk) throws Exception {
        Class<?> factoryClass = Class.forName(
                "org.openjproxy.datasource.hikari.HikariConnectionPoolProvider$HikariOpenTelemetryMetricsTrackerFactory");
        var constructor = factoryClass.getDeclaredConstructor(io.opentelemetry.api.OpenTelemetry.class, String.class);
        constructor.setAccessible(true);
        MetricsTrackerFactory factory = (MetricsTrackerFactory) constructor.newInstance(sdk, "fixture-hikari");
        PoolStats stats = new PoolStats(0) {
            @Override
            protected void update() {
                activeConnections = 2;
                idleConnections = 3;
                totalConnections = 5;
                pendingThreads = 1;
                maxConnections = 10;
                minConnections = 1;
            }
        };
        try (IMetricsTracker tracker = factory.create("fixture-hikari", stats)) {
            tracker.recordConnectionAcquiredNanos(5_000_000);
        }
    }

    private void recordRpc(OpenTelemetrySdk sdk) throws Exception {
        HealthStatusManager health = new HealthStatusManager();
        Server server = NettyServerBuilder.forPort(0).addService(health.getHealthService())
                .intercept(GrpcTelemetry.create(sdk).newServerInterceptor()).build().start();
        ManagedChannel channel = NettyChannelBuilder.forAddress("127.0.0.1", server.getPort()).usePlaintext().build();
        try {
            HealthGrpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS)
                    .check(HealthCheckRequest.getDefaultInstance());
        } finally {
            channel.shutdownNow().awaitTermination(10, TimeUnit.SECONDS);
            server.shutdownNow().awaitTermination(10, TimeUnit.SECONDS);
        }
    }
}
