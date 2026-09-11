package org.openjproxy.grpc.server;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.exporter.prometheus.PrometheusHttpServer;
import io.opentelemetry.exporter.zipkin.ZipkinSpanExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.instrumentation.grpc.v1_6.GrpcTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.OpenTelemetrySdkBuilder;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * OJP Server Telemetry Configuration for OpenTelemetry with Prometheus Exporter.
 * This class provides methods to create a GrpcTelemetry instance with Prometheus metrics
 * and optional distributed tracing via Zipkin or OTLP exporters.
 */
public class OjpServerTelemetry {
	private static final Logger logger = LoggerFactory.getLogger(OjpServerTelemetry.class);
	private static final int DEFAULT_PROMETHEUS_PORT = 9159;

	// Store the OpenTelemetry instance for cache and circuit breaker metrics initialization.
	private OpenTelemetry openTelemetry = OpenTelemetry.noop();

	/**
	 * Get the OpenTelemetry instance (for cache metrics initialization).
	 *
	 * @return The OpenTelemetry instance, or null if not initialized
	 */
	public OpenTelemetry getOpenTelemetry() {
		return openTelemetry;
	}

	/**
	 * Creates GrpcTelemetry with default configuration.
	 */
	public GrpcTelemetry createGrpcTelemetry() {
		return createGrpcTelemetry(DEFAULT_PROMETHEUS_PORT, List.of(IpWhitelistValidator.ALLOW_ALL_IPS), true, true);
	}

	/**
	 * Creates GrpcTelemetry with specified Prometheus port.
	 */
	public GrpcTelemetry createGrpcTelemetry(int prometheusPort) {
		return createGrpcTelemetry(prometheusPort, List.of(IpWhitelistValidator.ALLOW_ALL_IPS), true, true);
	}

	/**
	 * Validates the IP whitelist, returning the original list if valid or the allow-all default.
	 */
	private List<String> validateAllowedIps(List<String> allowedIps) {
		if (!IpWhitelistValidator.validateWhitelistRules(allowedIps)) {
			logger.warn("Invalid IP whitelist rules detected, falling back to allow all");
			return List.of(IpWhitelistValidator.ALLOW_ALL_IPS);
		}
		return allowedIps;
	}

	/**
	 * Creates GrpcTelemetry with specified Prometheus port and IP whitelist.
	 *
	 * @param prometheusPort Port to expose Prometheus metrics on
	 * @param allowedIps IP whitelist for Prometheus endpoint
	 * @param grpcMetricsEnabled Whether to enable gRPC metrics
	 * @param poolMetricsEnabled Whether to enable pool metrics
	 */
	public GrpcTelemetry createGrpcTelemetry(int prometheusPort, List<String> allowedIps,
			boolean grpcMetricsEnabled, boolean poolMetricsEnabled) {
		allowedIps = validateAllowedIps(allowedIps);

		logger.info("Initializing OpenTelemetry with Prometheus on port {} with IP whitelist: {}",
					prometheusPort, allowedIps);
		logger.info("gRPC metrics enabled: {}, Pool metrics enabled: {}",
					grpcMetricsEnabled, poolMetricsEnabled);

		PrometheusHttpServer prometheusServer = PrometheusHttpServer.builder()
				.setPort(prometheusPort)
				.build();

		this.openTelemetry = OpenTelemetrySdk.builder()
				.setMeterProvider(
						SdkMeterProvider.builder()
								.registerMetricReader(prometheusServer)
								.build())
				.build();

		// Register OpenTelemetry instance for pool metrics if enabled
		if (poolMetricsEnabled) {
			org.openjproxy.xa.pool.commons.metrics.OpenTelemetryHolder.setInstance(this.openTelemetry);
			logger.info("OpenTelemetry instance registered for pool metrics");
		}

		// Create GrpcTelemetry with or without metrics based on configuration
		// Note: When gRPC metrics are disabled, we use noop() to prevent gRPC instrumentation
		// from collecting and exporting metrics. The OpenTelemetry SDK is still initialized
		// for pool metrics and tracing if enabled.
		if (grpcMetricsEnabled) {
			logger.info("gRPC metrics enabled, creating instrumented GrpcTelemetry");
			return GrpcTelemetry.create(this.openTelemetry);
		} else {
			logger.info("gRPC metrics disabled, creating no-op GrpcTelemetry");
			return GrpcTelemetry.create(OpenTelemetry.noop());
		}
	}

	/**
	 * Creates GrpcTelemetry with Prometheus metrics and optional distributed tracing.
	 *
	 * @param prometheusPort  Port to expose Prometheus metrics on
	 * @param allowedIps      IP whitelist for Prometheus endpoint
	 * @param tracingEnabled  Whether distributed tracing should be enabled
	 * @param tracingExporter Exporter type: "zipkin" or "otlp"
	 * @param tracingEndpoint Endpoint URL for the trace exporter
	 * @param serviceName     Service name to attach to all spans
	 * @param sampleRate      Fraction of requests to sample (0.0–1.0)
	 * @param grpcMetricsEnabled Whether to enable gRPC metrics
	 * @param poolMetricsEnabled Whether to enable pool metrics
	 */
	public GrpcTelemetry createGrpcTelemetry(int prometheusPort, List<String> allowedIps,
			boolean tracingEnabled, String tracingExporter, String tracingEndpoint,
			String serviceName, double sampleRate, boolean grpcMetricsEnabled, boolean poolMetricsEnabled) {

		allowedIps = validateAllowedIps(allowedIps);

		logger.info("Initializing OpenTelemetry with Prometheus on port {} with IP whitelist: {}",
				prometheusPort, allowedIps);
		logger.info("gRPC metrics enabled: {}, Pool metrics enabled: {}",
				grpcMetricsEnabled, poolMetricsEnabled);

		PrometheusHttpServer prometheusServer = PrometheusHttpServer.builder()
				.setPort(prometheusPort)
				.build();

		OpenTelemetrySdkBuilder sdkBuilder = OpenTelemetrySdk.builder()
				.setMeterProvider(
						SdkMeterProvider.builder()
								.registerMetricReader(prometheusServer)
								.build());

		if (tracingEnabled) {
			SdkTracerProvider tracerProvider = buildTracerProvider(
					tracingExporter, tracingEndpoint, serviceName, sampleRate);
			sdkBuilder.setTracerProvider(tracerProvider);
		}

		this.openTelemetry = sdkBuilder.build();

		// Register OpenTelemetry instance for pool metrics if enabled
		if (poolMetricsEnabled) {
			org.openjproxy.xa.pool.commons.metrics.OpenTelemetryHolder.setInstance(this.openTelemetry);
			logger.info("OpenTelemetry instance registered for pool metrics");
		}

		// Create GrpcTelemetry with or without metrics based on configuration
		// Note: When gRPC metrics are disabled, we use noop() to prevent gRPC instrumentation
		// from collecting and exporting metrics. The OpenTelemetry SDK is still initialized
		// for pool metrics and tracing if enabled.
		if (grpcMetricsEnabled) {
			logger.info("gRPC metrics enabled, creating instrumented GrpcTelemetry");
			return GrpcTelemetry.create(this.openTelemetry);
		} else {
			logger.info("gRPC metrics disabled, creating no-op GrpcTelemetry");
			return GrpcTelemetry.create(OpenTelemetry.noop());
		}
	}

	/**
	 * Builds a {@link SdkTracerProvider} configured with the requested span exporter.
	 */
	private SdkTracerProvider buildTracerProvider(String exporterType, String endpoint,
			String serviceName, double sampleRate) {

		Resource resource = Resource.getDefault().merge(
				Resource.create(io.opentelemetry.api.common.Attributes.of(
						AttributeKey.stringKey("service.name"), serviceName)));

		Sampler sampler = sampleRate >= 1.0
				? Sampler.alwaysOn()
				: Sampler.traceIdRatioBased(sampleRate);

		SdkTracerProviderBuilder tracerProviderBuilder = SdkTracerProvider.builder()
				.setResource(resource)
				.setSampler(sampler);

		if ("otlp".equalsIgnoreCase(exporterType)) {
			logger.info("Configuring OTLP trace exporter with endpoint: {}", endpoint);
			OtlpGrpcSpanExporter otlpExporter = OtlpGrpcSpanExporter.builder()
					.setEndpoint(endpoint)
					.build();
			tracerProviderBuilder.addSpanProcessor(BatchSpanProcessor.builder(otlpExporter).build());
		} else {
			if (!"zipkin".equalsIgnoreCase(exporterType)) {
				logger.warn("Unrecognized tracing exporter type '{}'. Valid values are 'zipkin' and 'otlp'. Falling back to Zipkin.", exporterType);
			}
			logger.info("Configuring Zipkin trace exporter with endpoint: {}", endpoint);
			ZipkinSpanExporter zipkinExporter = ZipkinSpanExporter.builder()
					.setEndpoint(endpoint)
					.build();
			tracerProviderBuilder.addSpanProcessor(BatchSpanProcessor.builder(zipkinExporter).build());
		}

		return tracerProviderBuilder.build();
	}

	/**
	 * Creates circuit breaker metrics using the same OpenTelemetry instance as the server metrics endpoint.
	 */
	public CircuitBreakerMetrics createCircuitBreakerMetrics() {
		return new CircuitBreakerMetrics(openTelemetry);
	}

	/**
	 * Creates a no-op GrpcTelemetry when OpenTelemetry is disabled.
	 */
	public GrpcTelemetry createNoOpGrpcTelemetry() {
		logger.info("OpenTelemetry disabled, using no-op implementation");
		openTelemetry = OpenTelemetry.noop();
		return GrpcTelemetry.create(openTelemetry);
	}
}
