package org.openjproxy.grpc.server;

import com.openjproxy.grpc.SessionInfo;
import io.grpc.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GracefulShutdownHandlerTest {

    private final ShutdownCoordinator coordinator = ShutdownCoordinator.getInstance();

    private ServerConfiguration config;
    private Server server;
    private StatementServiceImpl statementService;
    private SessionManager sessionManager;
    private ExecutorService cleanupExecutor;
    private ExecutorService grpcExecutor;

    @BeforeEach
    void setUp() throws InterruptedException {
        config = mock(ServerConfiguration.class);
        when(config.isGracefulShutdownEnabled()).thenReturn(true);
        when(config.getGracefulShutdownDrainTimeoutSeconds()).thenReturn(1L);
        when(config.getGracefulShutdownTerminationTimeoutSeconds()).thenReturn(1L);
        server = mock(Server.class);
        when(server.awaitTermination(anyLong(), any(TimeUnit.class))).thenReturn(true);
        statementService = mock(StatementServiceImpl.class);
        sessionManager = mock(SessionManager.class);
        cleanupExecutor = mock(ExecutorService.class);
        grpcExecutor = mock(ExecutorService.class);
        when(cleanupExecutor.awaitTermination(anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(grpcExecutor.awaitTermination(anyLong(), any(TimeUnit.class))).thenReturn(true);
    }

    @AfterEach
    void reset() {
        coordinator.resetForTesting();
    }

    private GracefulShutdownHandler handler() {
        return new GracefulShutdownHandler(config, server, statementService, sessionManager,
                cleanupExecutor, grpcExecutor, coordinator);
    }

    @Test
    void shouldDrainBeforeStoppingServerAndClosePoolsAfterwards() throws InterruptedException {
        AtomicReference<ShutdownCoordinator.State> stateWhenServerStopped = new AtomicReference<>();
        doAnswer(inv -> {
            stateWhenServerStopped.set(coordinator.getState());
            return server;
        }).when(server).shutdown();
        when(sessionManager.getAllSessions()).thenReturn(Collections.emptyList());

        handler().run();

        assertEquals(ShutdownCoordinator.State.TERMINATING, stateWhenServerStopped.get());
        InOrder order = inOrder(server, statementService, cleanupExecutor, grpcExecutor);
        order.verify(server).shutdown();
        order.verify(server).awaitTermination(1L, TimeUnit.SECONDS);
        order.verify(statementService).closeAllPools();
        order.verify(statementService).shutdown();
        order.verify(cleanupExecutor).shutdown();
        order.verify(grpcExecutor).shutdown();
        verify(server, never()).shutdownNow();
    }

    @Test
    void shouldWaitForActiveSessionsToFinishBeforeStoppingServer() throws Exception {
        Session session = mock(Session.class);
        List<Session> oneSession = Collections.singletonList(session);
        long[] firstEmptyAt = new long[1];
        long start = System.nanoTime();
        when(sessionManager.getAllSessions()).thenAnswer(inv -> {
            if (System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(300)) {
                return oneSession;
            }
            if (firstEmptyAt[0] == 0) {
                firstEmptyAt[0] = System.nanoTime();
            }
            return Collections.<Session>emptyList();
        });
        long[] serverStoppedAt = new long[1];
        doAnswer(inv -> {
            serverStoppedAt[0] = System.nanoTime();
            return server;
        }).when(server).shutdown();

        handler().run();

        assertTrue(serverStoppedAt[0] >= firstEmptyAt[0] && firstEmptyAt[0] > 0,
                "server.shutdown() must be called only after sessions drained");
        verify(sessionManager, never()).terminateSession(any());
    }

    @Test
    void shouldRollBackRemainingSessionsWhenDrainTimeoutExpires() throws Exception {
        Session session = mock(Session.class);
        SessionInfo info = SessionInfo.newBuilder().setSessionUUID("s-1").build();
        when(session.getSessionInfo()).thenReturn(info);
        when(session.getSessionUUID()).thenReturn("s-1");
        Collection<Session> stuck = Collections.singletonList(session);
        when(sessionManager.getAllSessions()).thenReturn(stuck);

        handler().run();

        InOrder order = inOrder(server, sessionManager, statementService);
        order.verify(server).shutdown();
        order.verify(sessionManager).terminateSession(info);
        order.verify(statementService).closeAllPools();
    }

    @Test
    void shouldForceShutdownWhenInFlightCallsDoNotFinish() throws InterruptedException {
        when(sessionManager.getAllSessions()).thenReturn(Collections.emptyList());
        when(server.awaitTermination(anyLong(), any(TimeUnit.class))).thenReturn(false);

        handler().run();

        verify(server).shutdownNow();
    }

    @Test
    void shouldSkipDrainWhenGracefulShutdownDisabled() throws InterruptedException {
        when(config.isGracefulShutdownEnabled()).thenReturn(false);
        when(sessionManager.getAllSessions()).thenReturn(Collections.singletonList(mock(Session.class)));
        AtomicReference<ShutdownCoordinator.State> stateWhenServerStopped = new AtomicReference<>();
        long start = System.nanoTime();
        doAnswer(inv -> {
            stateWhenServerStopped.set(coordinator.getState());
            return server;
        }).when(server).shutdown();

        handler().run();

        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(elapsedMs < 1000L, "No drain wait expected when disabled, took " + elapsedMs + "ms");
        assertEquals(ShutdownCoordinator.State.TERMINATING, stateWhenServerStopped.get());
        verify(server).awaitTermination(30L, TimeUnit.SECONDS);
    }
}
