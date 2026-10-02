package org.openjproxy.grpc.client;

import com.openjproxy.grpc.OpResult;
import com.openjproxy.grpc.SessionInfo;
import com.openjproxy.grpc.StatementRequest;
import com.openjproxy.grpc.StatementServiceGrpc;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openjproxy.constants.CommonConstants;

import java.io.IOException;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the driver side of OJP server graceful shutdown (draining).
 */
class ServerDrainingFailoverTest {

    private static final Metadata.Key<String> DRAINING_KEY =
            Metadata.Key.of(CommonConstants.SERVER_DRAINING_TRAILER_KEY, Metadata.ASCII_STRING_MARSHALLER);

    private final List<Server> servers = new ArrayList<>();
    private MultinodeConnectionManager manager;

    @AfterEach
    void tearDown() throws InterruptedException {
        if (manager != null) {
            manager.shutdown();
        }
        for (Server server : servers) {
            server.shutdownNow();
            server.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static StatusRuntimeException drainingError() {
        Metadata trailers = new Metadata();
        trailers.put(DRAINING_KEY, "true");
        return Status.UNAVAILABLE.withDescription(CommonConstants.SERVER_DRAINING_DESCRIPTION)
                .asRuntimeException(trailers);
    }

    // ---- draining error detection ----

    @Test
    void shouldDetectDrainingErrorWhenTrailerPresent() {
        assertTrue(GrpcExceptionHandler.isServerDrainingError(drainingError()));
    }

    @Test
    void shouldDetectDrainingErrorWhenWrappedInSqlException() {
        SQLException wrapped = new SQLTransientConnectionException("unavailable", "08001", 0, drainingError());
        assertTrue(GrpcExceptionHandler.isServerDrainingError(wrapped));
        assertTrue(GrpcExceptionHandler.isServerDrainingError(new RuntimeException(wrapped)));
    }

    @Test
    void shouldNotDetectDrainingErrorWhenUnavailableWithoutTrailer() {
        StatusRuntimeException plain = Status.UNAVAILABLE.withDescription("Connection refused").asRuntimeException();
        assertFalse(GrpcExceptionHandler.isServerDrainingError(plain));
        assertFalse(GrpcExceptionHandler.isServerDrainingError(null));
        assertFalse(GrpcExceptionHandler.isServerDrainingError(new SQLException("boom")));
    }

    @Test
    void shouldNotDetectDrainingErrorWhenStatusIsNotUnavailable() {
        Metadata trailers = new Metadata();
        trailers.put(DRAINING_KEY, "true");
        StatusRuntimeException internal = Status.INTERNAL.asRuntimeException(trailers);
        assertFalse(GrpcExceptionHandler.isServerDrainingError(internal));
    }

    // ---- endpoint state ----

    @Test
    void shouldExcludeFromNewSessionsButKeepBoundSessionsWhenEndpointDraining() {
        ServerEndpoint endpoint = new ServerEndpoint("server1", 1059);
        endpoint.markDraining();

        assertTrue(endpoint.isDraining());
        assertFalse(endpoint.isHealthy());
        assertTrue(endpoint.acceptsBoundSessions());
    }

    @Test
    void shouldClearDrainingWhenEndpointMarkedHealthy() {
        ServerEndpoint endpoint = new ServerEndpoint("server1", 1059);
        endpoint.markDraining();
        endpoint.markHealthy();

        assertTrue(endpoint.isHealthy());
        assertFalse(endpoint.isDraining());
    }

    @Test
    void shouldClearDrainingWhenEndpointMarkedUnhealthy() {
        ServerEndpoint endpoint = new ServerEndpoint("server1", 1059);
        endpoint.markDraining();
        endpoint.markUnhealthy();

        assertFalse(endpoint.isHealthy());
        assertFalse(endpoint.isDraining());
        assertFalse(endpoint.acceptsBoundSessions());
    }

    // ---- connection manager ----

    @Test
    void shouldKeepBoundSessionsRoutedToDrainingServer() throws SQLException {
        ServerEndpoint server1 = new ServerEndpoint("server1", 1059);
        ServerEndpoint server2 = new ServerEndpoint("server2", 1059);
        manager = new MultinodeConnectionManager(Arrays.asList(server1, server2));
        manager.bindSession("xa-session-1", "server1:1059");
        manager.bindSession("session-2", "server1:1059");

        manager.markDraining(server1);

        assertTrue(server1.isDraining());
        assertSame(server1, manager.affinityServer("xa-session-1"),
                "XA session must stay on the draining server so it can prepare/commit");
        assertSame(server1, manager.affinityServer("session-2"));
        assertSame(server2, manager.affinityServer(null), "New sessions must go to the other server");
        assertSame(server2, manager.affinityServer(null));
    }

    @Test
    void shouldNotInvalidateXaSessionsWhenServerDrains() throws SQLException {
        ServerEndpoint server1 = new ServerEndpoint("server1", 1059);
        ServerEndpoint server2 = new ServerEndpoint("server2", 1059);
        manager = new MultinodeConnectionManager(Arrays.asList(server1, server2), 3, 1000,
                HealthCheckConfig.createDefault(), new ConnectionTracker());
        manager.setXaConnectionRedistributor(new XAConnectionRedistributor(manager, HealthCheckConfig.createDefault()));
        manager.bindSession("xa-session-1", "server1:1059");

        manager.handleServerFailure(server1, drainingError());

        assertTrue(server1.isDraining());
        assertTrue(manager.isSessionBound("xa-session-1"), "XA session must not be invalidated while draining");
        assertSame(server1, manager.affinityServer("xa-session-1"));
    }

    // ---- retry of requests without a session ----

    @Test
    void shouldRetryRequestWithoutSessionOnAnotherServerWhenServerDraining() throws Exception {
        AtomicInteger drainingCalls = new AtomicInteger();
        AtomicInteger healthyCalls = new AtomicInteger();
        int drainingPort = startServer(new FakeStatementService(drainingCalls, true));
        int healthyPort = startServer(new FakeStatementService(healthyCalls, false));

        ServerEndpoint drainingServer = new ServerEndpoint("localhost", drainingPort);
        ServerEndpoint healthyServer = new ServerEndpoint("localhost", healthyPort);
        manager = new MultinodeConnectionManager(Arrays.asList(drainingServer, healthyServer));
        MultinodeStatementService service = new MultinodeStatementService(manager,
                "jdbc:ojp[localhost:" + drainingPort + ",localhost:" + healthyPort + "]_h2:mem:test");

        SessionInfo noSession = SessionInfo.newBuilder().setConnHash("hash").build();
        for (int i = 0; i < 4; i++) {
            OpResult result = service.executeUpdate(noSession, "UPDATE t SET a = 1",
                    Collections.emptyList(), Collections.<String, Object>emptyMap());
            assertEquals("healthy", result.getSession().getTargetServer());
        }

        assertTrue(drainingServer.isDraining(), "Server that answered with draining must be marked draining");
        assertTrue(drainingCalls.get() <= 1, "Draining server must not receive new requests once marked");
        assertEquals(4, healthyCalls.get());
    }

    @Test
    void shouldSurfaceDrainingErrorWhenNoOtherServerAvailable() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        int port = startServer(new FakeStatementService(calls, true));
        ServerEndpoint server = new ServerEndpoint("localhost", port);
        manager = new MultinodeConnectionManager(Collections.singletonList(server));
        MultinodeStatementService service = new MultinodeStatementService(manager,
                "jdbc:ojp[localhost:" + port + "]_h2:mem:test");

        SessionInfo noSession = SessionInfo.newBuilder().setConnHash("hash").build();
        SQLException ex = assertThrows(SQLException.class, () -> service.executeUpdate(noSession,
                "UPDATE t SET a = 1", Collections.emptyList(), Collections.<String, Object>emptyMap()));

        assertTrue(GrpcExceptionHandler.isServerDrainingError(ex));
        assertEquals(1, calls.get(), "Single server must not be retried in a loop");
    }

    private int startServer(StatementServiceGrpc.StatementServiceImplBase service) throws IOException {
        Server server = NettyServerBuilder.forPort(0).addService(service).build().start();
        servers.add(server);
        return server.getPort();
    }

    private static final class FakeStatementService extends StatementServiceGrpc.StatementServiceImplBase {
        private final AtomicInteger calls;
        private final boolean draining;

        FakeStatementService(AtomicInteger calls, boolean draining) {
            this.calls = calls;
            this.draining = draining;
        }

        @Override
        public void executeUpdate(StatementRequest request, StreamObserver<OpResult> responseObserver) {
            calls.incrementAndGet();
            if (draining) {
                responseObserver.onError(drainingError());
                return;
            }
            responseObserver.onNext(OpResult.newBuilder()
                    .setSession(SessionInfo.newBuilder().setTargetServer("healthy").build())
                    .build());
            responseObserver.onCompleted();
        }
    }
}
