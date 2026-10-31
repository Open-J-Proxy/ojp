package org.openjproxy.grpc.server;

import io.opentelemetry.instrumentation.grpc.v1_6.GrpcTelemetry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class OjpServerTelemetryTest {
	private static final int PROMETHEUS_PORT = 9191;
	private static final int TIMEOUT = 5000;
	private static final int RESPONSE_CODE_OK = 200;

	private static OjpServerTelemetry instrument;
	private static GrpcTelemetry grpcTelemetry;

	@BeforeAll
	static void setUp() {
		instrument = new OjpServerTelemetry();
		grpcTelemetry = instrument.createGrpcTelemetry(PROMETHEUS_PORT);
	}

	@Test
	void shouldCreateGrpcTelemetrySuccessfully() {
		assertNotNull(grpcTelemetry);
		assertNotNull(grpcTelemetry.createServerInterceptor());
		assertNotNull(grpcTelemetry.createClientInterceptor());
	}

	@Test
	void shouldCreateCircuitBreakerMetricsWithServerTelemetry() {
		assertNotNull(instrument.createCircuitBreakerMetrics());
	}

	@Test
	void shouldExposePrometheusMetricsEndpoint() throws IOException {
		HttpURLConnection connection = (HttpURLConnection) URI.create("http://localhost:9191/metrics").toURL().openConnection();
		connection.setRequestMethod("GET");
		connection.setConnectTimeout(TIMEOUT);
		connection.setReadTimeout(TIMEOUT);

		assertEquals(RESPONSE_CODE_OK, connection.getResponseCode());
		assertEquals("text/plain; version=0.0.4; charset=utf-8", connection.getContentType());
	}

	@Test
	void shouldCreateNoOpGrpcTelemetryWhenDisabled() {
		OjpServerTelemetry noOpTelemetry = new OjpServerTelemetry();
		GrpcTelemetry noOp = noOpTelemetry.createNoOpGrpcTelemetry();

		assertNotNull(noOp);
		assertNotNull(noOp.createServerInterceptor());
		assertNotNull(noOp.createClientInterceptor());
	}

	@Test
	void shouldCreateGrpcTelemetryWithTracingDisabledByDefault() {
		OjpServerTelemetry telemetryFactory = new OjpServerTelemetry();
		// tracing disabled - no exporter is configured; must not throw
		GrpcTelemetry telemetry = telemetryFactory.createGrpcTelemetry(
				9192,
				List.of(IpWhitelistValidator.ALLOW_ALL_IPS),
				false, "zipkin", "http://localhost:9411/api/v2/spans", "ojp-server", 1.0,
				true, true); // grpcMetricsEnabled, poolMetricsEnabled

		assertNotNull(telemetry);
		assertNotNull(telemetry.createServerInterceptor());
	}

}
