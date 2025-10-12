package org.openjproxy.grpc.server;

import org.openjproxy.grpc.GrpcChannelFactory;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.openjproxy.grpc.server.OjpHealthManager.*;

/**
 * Integration test for the complete GrpcServer health status functionality.
 * This test verifies that the health endpoints work correctly with the actual
 * server implementation.
 */
@Slf4j
class GrpcServerHealthTest {

    private static ExecutorService virtualThreadExecutor;
    private ManagedChannel channel;
    private HealthGrpc.HealthBlockingStub healthStub;

    @BeforeEach
    void setUp() {
        int testPort = findAvailablePort();
        int testPrometheusPort = testPort;
        while (testPrometheusPort == testPort) {
            testPrometheusPort = findAvailablePort();
        }

        System.setProperty("ojp.server.port", String.valueOf(testPort));
        System.setProperty("ojp.prometheus.port", String.valueOf(testPrometheusPort));

        // Create client channel
        channel = GrpcChannelFactory.createChannel("localhost", testPort);

        // Create health check stub
        healthStub = HealthGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (channel != null) {
            channel.shutdown();
            channel.awaitTermination(1, TimeUnit.SECONDS);
        }

        System.clearProperty("ojp.server.port");
        System.clearProperty("ojp.opentelemetry.enabled");
    }

    @AfterAll
    static void cleanUp() {
        if (virtualThreadExecutor != null) {
            virtualThreadExecutor.shutdownNow();
        }
    }

    @Test
    void testHealthEndpointServing() {
        startServer();

        assertHealthStatus(Services.OJP_SERVER, HealthCheckResponse.ServingStatus.SERVING);
        assertHealthStatus(Services.OPENTELEMETRY_SERVICE, HealthCheckResponse.ServingStatus.SERVING);
    }

    @Test
    void testHealthEndpointOtelServiceNotServing() {
        System.setProperty("ojp.opentelemetry.enabled", "false");
        startServer();

        assertHealthStatus(Services.OJP_SERVER, HealthCheckResponse.ServingStatus.SERVING);
        assertHealthStatus(Services.OPENTELEMETRY_SERVICE, HealthCheckResponse.ServingStatus.NOT_SERVING);
    }

    @Test
    void testServerHealthEndpointNotServing() {
        HealthCheckRequest request = HealthCheckRequest.newBuilder()
                .setService(Services.OJP_SERVER.getServiceName())
                .build();

        assertThrows(io.grpc.StatusRuntimeException.class, () -> healthStub.check(request),
                "Expected UNAVAILABLE status when server is not running");
    }

    // Helpers

    private void assertHealthStatus(Services service, HealthCheckResponse.ServingStatus expectedStatus) {
        HealthCheckRequest request = HealthCheckRequest.newBuilder()
                .setService(service.getServiceName())
                .build();

        HealthCheckResponse response = healthStub.check(request);

        assertNotNull(response);
        assertEquals(expectedStatus, response.getStatus());
    }

    private void startServer() {
        virtualThreadExecutor = Executors.newFixedThreadPool(10);
        virtualThreadExecutor.submit(() -> {
            try {
                String[] args = {};
                GrpcServer.main(args);
            } catch (Exception ignored) {
                log.error("Server interrupted");
            }
        });

        try {
            Thread.sleep(1000); // Wait for server to start
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private int findAvailablePort() {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (Exception e) {
            return 0;
        }
    }

}
