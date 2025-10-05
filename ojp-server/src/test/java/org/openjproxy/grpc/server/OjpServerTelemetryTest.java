package org.openjproxy.grpc.server;

import io.opentelemetry.instrumentation.grpc.v1_6.GrpcTelemetry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class OjpServerTelemetryTest {

	private static GrpcTelemetry grpcTelemetry;

	@BeforeAll
	static void setUp() {
		OjpServerTelemetry instrument = new OjpServerTelemetry();
		grpcTelemetry = instrument.createGrpcTelemetry(9159);
	}

	@Test
	void shouldCreateGrpcTelemetrySuccessfully() {
		assertNotNull(grpcTelemetry);
		assertNotNull(grpcTelemetry.newServerInterceptor());
		assertNotNull(grpcTelemetry.newClientInterceptor());
	}

	@Test
	void shouldExposePrometheusMetricsEndpoint() throws IOException {
		HttpURLConnection connection = (HttpURLConnection) URI.create("http://localhost:9191/metrics").toURL().openConnection();
		connection.setRequestMethod("GET");
		connection.setConnectTimeout(5000);
		connection.setReadTimeout(5000);

		assertEquals(200, connection.getResponseCode());
		assertEquals("text/plain; version=0.0.4; charset=utf-8", connection.getContentType());
	}

}
