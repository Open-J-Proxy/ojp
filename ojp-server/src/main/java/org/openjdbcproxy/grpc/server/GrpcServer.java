package org.openjdbcproxy.grpc.server;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.netty.NettyServerBuilder;
import io.opentelemetry.instrumentation.grpc.v1_6.GrpcTelemetry;
import org.openjdbcproxy.constants.CommonConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class GrpcServer {
    private static final Logger logger = LoggerFactory.getLogger(GrpcServer.class);

    public static void main(String[] args) throws IOException, InterruptedException {
        // Initialize health status manager
        OjpHealthManager.initialize();

        // Load configuration
        ServerConfiguration config = new ServerConfiguration();
        
        // Validate IP whitelist for server
        if (!IpWhitelistValidator.validateWhitelistRules(config.getAllowedIps())) {
            logger.error("Invalid IP whitelist configuration for server. Exiting.");
            System.exit(1);
        }

        // Initialize telemetry based on configuration
        OjpServerTelemetry ojpServerTelemetry = new OjpServerTelemetry();
        GrpcTelemetry grpcTelemetry;
        
        if (config.isOpenTelemetryEnabled()) {
            grpcTelemetry = ojpServerTelemetry.createGrpcTelemetry(
                config.getPrometheusPort(), 
                config.getPrometheusAllowedIps()
            );

            OjpHealthManager.setServiceStatus(OjpHealthManager.Services.OPENTELEMETRY_SERVICE,
                    HealthCheckResponse.ServingStatus.SERVING);
        } else {
            grpcTelemetry = ojpServerTelemetry.createNoOpGrpcTelemetry();
        }

        // Build server with configuration
        ServerBuilder<?> serverBuilder = NettyServerBuilder
                .forPort(config.getServerPort())
                .executor(Executors.newFixedThreadPool(config.getThreadPoolSize()))
                .maxInboundMessageSize(config.getMaxRequestSize())
                .keepAliveTime(config.getConnectionIdleTimeout(), TimeUnit.MILLISECONDS)
                .addService(new StatementServiceImpl(
                        new SessionManagerImpl(),
                        new CircuitBreaker(config.getCircuitBreakerTimeout(), config.getCircuitBreakerThreshold()),
                        config
                ))
                .addService(OjpHealthManager.getHealthStatusManager().getHealthService())
                .intercept(grpcTelemetry.newServerInterceptor());

        Server server = serverBuilder.build();

        logger.info("Starting OJP gRPC Server on port {}", config.getServerPort());
        logger.info("Server configuration applied successfully");
        
        server.start();
        OjpHealthManager.setServiceStatus(OjpHealthManager.Services.OJP_SERVER,
                HealthCheckResponse.ServingStatus.SERVING);
        
        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down OJP gRPC Server...");
            server.shutdown();

            try {
                if (!server.awaitTermination(30, TimeUnit.SECONDS)) {
                    logger.warn("Server did not terminate gracefully, forcing shutdown");
                    server.shutdownNow();
                }
            } catch (InterruptedException e) {
                logger.warn("Interrupted while waiting for server shutdown");
                server.shutdownNow();
                Thread.currentThread().interrupt();
            }
            logger.info("OJP gRPC Server shutdown complete");
        }));

        logger.info("OJP gRPC Server started successfully and awaiting termination");
        server.awaitTermination();
    }
}
