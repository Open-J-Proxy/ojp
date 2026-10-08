package org.openjproxy.grpc.server;

import io.grpc.Server;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.netty.NettyServerBuilder;
import io.opentelemetry.instrumentation.grpc.v1_6.GrpcTelemetry;
import org.openjproxy.config.TlsConfigurationException;
import org.openjproxy.grpc.server.utils.DriverLoader;
import org.openjproxy.grpc.server.utils.DriverUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class GrpcServer {
    private static final Logger logger = LoggerFactory.getLogger(GrpcServer.class);
    private static final long EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS = 30;

    public static void main(String[] args) throws IOException, InterruptedException {
        // Initialize health status manager
        OjpHealthManager.initialize();

        // Load configuration
        ServerConfiguration config = new ServerConfiguration();

        // Load external JDBC drivers from configured directory
        logger.info("Loading external JDBC drivers...");
        boolean driversLoaded = DriverLoader.loadDriversFromPath(config.getDriversPath());
        if (!driversLoaded) {
            logger.warn("Failed to load external libraries from path: {}", config.getDriversPath());
            logger.warn("Server will continue, but proprietary drivers may not be available");
        }

        // Register JDBC drivers (both built-in and externally loaded)
        DriverUtils.registerDrivers(config.getDriversPath());

        // Validate IP whitelist for server
        if (!IpWhitelistValidator.validateWhitelistRules(config.getAllowedIps())) {
            logger.error("Invalid IP whitelist configuration for server. Exiting.");
            System.exit(1);
        }

        // Initialize telemetry based on configuration
        OjpServerTelemetry ojpServerTelemetry = new OjpServerTelemetry();
        GrpcTelemetry grpcTelemetry;
        CircuitBreakerMetrics circuitBreakerMetrics;

        if (config.isOpenTelemetryEnabled()) {
            grpcTelemetry = ojpServerTelemetry.createGrpcTelemetry(
                config.getPrometheusPort(),
                config.getPrometheusAllowedIps(),
                config.isTracingEnabled(),
                config.getTracingExporter(),
                config.getTracingEndpoint(),
                config.getTracingServiceName(),
                config.getTracingSampleRate(),
                config.isTelemetryGrpcMetricsEnabled(),
                config.isTelemetryPoolMetricsEnabled()
            );
            if (config.isTelemetryCircuitBreakerMetricsEnabled()) {
                circuitBreakerMetrics = ojpServerTelemetry.createCircuitBreakerMetrics();
            } else {
                circuitBreakerMetrics = CircuitBreakerMetrics.noop();
                logger.info("Circuit breaker metrics disabled");
            }

            OjpHealthManager.setServiceStatus(OjpHealthManager.Services.OPENTELEMETRY_SERVICE,
                    HealthCheckResponse.ServingStatus.SERVING);

            // Initialize cache metrics with OpenTelemetry
            io.opentelemetry.api.OpenTelemetry openTelemetry = ojpServerTelemetry.getOpenTelemetry();
            if (openTelemetry != null && config.isTelemetryCacheMetricsEnabled()) {
                org.openjproxy.grpc.server.cache.QueryCacheMetrics cacheMetrics =
                    new org.openjproxy.grpc.server.cache.OpenTelemetryQueryCacheMetrics(openTelemetry);
                org.openjproxy.grpc.server.cache.QueryResultCacheRegistry.getInstance().setMetrics(cacheMetrics);
                logger.info("Cache metrics initialized with OpenTelemetry");
            }
        } else {
            grpcTelemetry = ojpServerTelemetry.createNoOpGrpcTelemetry();
            circuitBreakerMetrics = CircuitBreakerMetrics.noop();
        }

        CircuitBreakerRegistry circuitBreakerRegistry = new CircuitBreakerRegistry(
                config.getCircuitBreakerTimeout(),
                config.getCircuitBreakerThreshold(),
                circuitBreakerMetrics
        );

        // Build server with configuration
        // Create shared cache configuration map for session-level caching
        java.util.Map<String, org.openjproxy.grpc.server.cache.CacheConfiguration> cacheConfigurationMap =
            new java.util.concurrent.ConcurrentHashMap<>();

        SessionManagerImpl sessionManager = new SessionManagerImpl(cacheConfigurationMap);
        final StatementServiceImpl statementService = new StatementServiceImpl(
                sessionManager,
                circuitBreakerRegistry,
                config,
                cacheConfigurationMap
        );

        ExecutorService grpcExecutor = createGrpcExecutor(config);

        NettyServerBuilder serverBuilder = NettyServerBuilder
                .forPort(config.getServerPort())
                .executor(grpcExecutor)
                .maxInboundMessageSize(config.getMaxRequestSize())
                .keepAliveTime(config.getConnectionIdleTimeout(), TimeUnit.MILLISECONDS)
                .addService(statementService)
                .addService(OjpHealthManager.getHealthStatusManager().getHealthService())
                .intercept(new IpWhitelistingInterceptor(config.getAllowedIps()))
                .intercept(new ConcurrencyThrottleInterceptor(config.getMaxConcurrentRequests()))
                .intercept(grpcTelemetry.newServerInterceptor());

        // Configure TLS if enabled
        if (config.isTlsEnabled()) {
            try {
                configureTls(serverBuilder, config);
                logger.info("TLS/mTLS enabled for gRPC server");
            } catch (Exception e) {
                logger.error("Failed to configure TLS for gRPC server: {}", e.getMessage(), e);
                throw new RuntimeException("Failed to configure TLS for gRPC server. " +
                        "Please check your TLS configuration properties and certificate files.", e);
            }
        } else {
            logger.info("TLS not enabled - using plaintext communication");
        }

        Server server = serverBuilder.build();

        logger.info("Starting OJP gRPC Server on port {}", config.getServerPort());
        logger.info("Server configuration applied successfully");

        // Initialize session cleanup task if enabled
        ScheduledExecutorService sessionCleanupExecutor = null;
        if (config.isSessionCleanupEnabled()) {
            logger.info("Initializing session cleanup task: timeout={}min, interval={}min",
                    config.getSessionTimeoutMinutes(), config.getSessionCleanupIntervalMinutes());

            sessionCleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "ojp-session-cleanup");
                thread.setDaemon(true);
                return thread;
            });

            long timeoutMillis = config.getSessionTimeoutMinutes() * 60 * 1000;
            long intervalMillis = config.getSessionCleanupIntervalMinutes() * 60 * 1000;

            SessionCleanupTask cleanupTask = new SessionCleanupTask(sessionManager, timeoutMillis);
            sessionCleanupExecutor.scheduleAtFixedRate(
                    cleanupTask,
                    intervalMillis, // Initial delay
                    intervalMillis, // Period
                    TimeUnit.MILLISECONDS
            );

            logger.info("Session cleanup task started successfully");
        } else {
            logger.info("Session cleanup is disabled");
        }

        server.start();
        OjpHealthManager.setServiceStatus(OjpHealthManager.Services.OJP_SERVER,
                HealthCheckResponse.ServingStatus.SERVING);

        // Add shutdown hook
        ScheduledExecutorService finalSessionCleanupExecutor = sessionCleanupExecutor;
        ExecutorService finalGrpcExecutor = grpcExecutor;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down OJP gRPC Server...");

            // Shutdown SQL enhancer engine first
            logger.info("Shutting down SQL enhancer engine...");
            statementService.shutdown();

            // Shutdown session cleanup task
            if (finalSessionCleanupExecutor != null) {
                logger.info("Shutting down session cleanup executor...");
                finalSessionCleanupExecutor.shutdown();
                try {
                    if (!finalSessionCleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                        logger.warn("Session cleanup executor did not terminate gracefully");
                        finalSessionCleanupExecutor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    logger.warn("Interrupted while waiting for session cleanup shutdown");
                    finalSessionCleanupExecutor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }

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

            shutdownExecutor(finalGrpcExecutor, "gRPC server executor");
            logger.info("OJP gRPC Server shutdown complete");
        }));

        logger.info("OJP gRPC Server started successfully and awaiting termination");
        server.awaitTermination();
    }

    private static ExecutorService createGrpcExecutor(ServerConfiguration config) {
        if (config.isVirtualThreadsEnabled()) {
            logger.info("Using virtual threads for gRPC request handling");
            return Executors.newVirtualThreadPerTaskExecutor();
        }

        logger.info("Using platform threads with fixed thread pool for gRPC request handling");
        return Executors.newFixedThreadPool(config.getThreadPoolSize());
    }

    private static void shutdownExecutor(ExecutorService executor, String executorName) {
        if (executor == null) {
            return;
        }

        executor.shutdown();
        try {
            if (!executor.awaitTermination(EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                logger.warn("{} did not terminate gracefully, forcing shutdown", executorName);
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            logger.warn("Interrupted while shutting down {}", executorName);
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Configures TLS/mTLS for the gRPC server.
     *
     * @param serverBuilder The NettyServerBuilder to configure
     * @param config Server configuration containing TLS settings
     * @throws TlsConfigurationException if TLS configuration fails
     */
    private static void configureTls(NettyServerBuilder serverBuilder, ServerConfiguration config)
            throws TlsConfigurationException {
        try {
            // Load keystore and extract certificate and key
            java.security.KeyStore keyStore = loadKeyStore(config);

            // Build SSL context using Netty's builder
            io.netty.handler.ssl.SslContextBuilder sslContextBuilder =
                io.netty.handler.ssl.SslContextBuilder.forServer(
                    extractKeyManagerFactory(keyStore, config.getTlsKeystorePassword())
                );

            // Apply gRPC-specific SSL settings
            sslContextBuilder = io.grpc.netty.GrpcSslContexts.configure(sslContextBuilder);

            // Configure client authentication (mTLS) if required
            if (config.isTlsClientAuthRequired()) {
                logger.info("mTLS (mutual TLS) enabled - client certificates required");
                javax.net.ssl.TrustManagerFactory trustManagerFactory = loadTrustManager(config);
                sslContextBuilder.trustManager(trustManagerFactory);
                sslContextBuilder.clientAuth(io.netty.handler.ssl.ClientAuth.REQUIRE);
            } else {
                logger.info("Server TLS enabled - client certificates not required");
                // Optionally configure truststore for client certificate verification
                if (config.getTlsTruststorePath() != null && !config.getTlsTruststorePath().trim().isEmpty()) {
                    logger.info("Truststore configured for optional client certificate verification");
                    javax.net.ssl.TrustManagerFactory trustManagerFactory = loadTrustManager(config);
                    sslContextBuilder.trustManager(trustManagerFactory);
                    sslContextBuilder.clientAuth(io.netty.handler.ssl.ClientAuth.OPTIONAL);
                }
            }

            io.netty.handler.ssl.SslContext sslContext = sslContextBuilder.build();
            serverBuilder.sslContext(sslContext);
        } catch (TlsConfigurationException e) {
            throw e;
        } catch (Exception e) {
            throw new TlsConfigurationException("Failed to configure TLS for gRPC server", e);
        }
    }

    /**
     * Loads the keystore containing server certificate and private key.
     *
     * @param config Server configuration
     * @return KeyStore instance
     * @throws TlsConfigurationException if keystore cannot be loaded
     */
    private static java.security.KeyStore loadKeyStore(ServerConfiguration config) throws TlsConfigurationException {
        if (config.getTlsKeystorePath() == null || config.getTlsKeystorePath().trim().isEmpty()) {
            throw new TlsConfigurationException("TLS is enabled but keystore path is not configured. " +
                    "Set ojp.server.tls.keystore.path property.");
        }

        try {
            java.security.KeyStore keyStore = java.security.KeyStore.getInstance(config.getTlsKeystoreType());
            try (java.io.FileInputStream fis = new java.io.FileInputStream(new java.io.File(config.getTlsKeystorePath()))) {
                keyStore.load(fis, config.getTlsKeystorePassword() != null ?
                    config.getTlsKeystorePassword().toCharArray() : null);
            }
            return keyStore;
        } catch (Exception e) {
            throw new TlsConfigurationException("Failed to load keystore from: " + config.getTlsKeystorePath(), e);
        }
    }

    /**
     * Creates a KeyManagerFactory from the keystore.
     *
     * @param keyStore The loaded keystore
     * @param password The keystore password
     * @return KeyManagerFactory for server certificate
     * @throws TlsConfigurationException if KeyManagerFactory cannot be created
     */
    private static javax.net.ssl.KeyManagerFactory extractKeyManagerFactory(
            java.security.KeyStore keyStore, String password) throws TlsConfigurationException {
        try {
            javax.net.ssl.KeyManagerFactory keyManagerFactory =
                javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
            keyManagerFactory.init(keyStore, password != null ? password.toCharArray() : null);
            return keyManagerFactory;
        } catch (Exception e) {
            throw new TlsConfigurationException("Failed to create KeyManagerFactory from keystore", e);
        }
    }

    /**
     * Loads the trust manager for client certificate verification.
     *
     * @param config Server configuration
     * @return TrustManagerFactory for client certificate verification
     * @throws TlsConfigurationException if truststore cannot be loaded
     */
    private static javax.net.ssl.TrustManagerFactory loadTrustManager(ServerConfiguration config)
            throws TlsConfigurationException {
        try {
            if (config.getTlsTruststorePath() == null || config.getTlsTruststorePath().trim().isEmpty()) {
                logger.info("No truststore path configured, using JVM default truststore");
                // Return default trust manager factory
                javax.net.ssl.TrustManagerFactory trustManagerFactory =
                    javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
                trustManagerFactory.init((java.security.KeyStore) null);
                return trustManagerFactory;
            }

            java.security.KeyStore trustStore = java.security.KeyStore.getInstance(config.getTlsTruststoreType());
            try (java.io.FileInputStream fis = new java.io.FileInputStream(new java.io.File(config.getTlsTruststorePath()))) {
                trustStore.load(fis, config.getTlsTruststorePassword() != null ?
                    config.getTlsTruststorePassword().toCharArray() : null);
            }

            javax.net.ssl.TrustManagerFactory trustManagerFactory =
                javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(trustStore);

            return trustManagerFactory;
        } catch (Exception e) {
            throw new TlsConfigurationException("Failed to load trust manager from: " + config.getTlsTruststorePath(), e);
        }
    }
}
