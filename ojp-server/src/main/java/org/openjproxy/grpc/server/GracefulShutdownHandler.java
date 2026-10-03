package org.openjproxy.grpc.server;

import com.openjproxy.grpc.SessionInfo;
import io.grpc.Server;
import io.grpc.health.v1.HealthCheckResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Runs the OJP server graceful shutdown sequence (normally from the JVM shutdown hook):
 *
 * <ol>
 *   <li>Enter {@link ShutdownCoordinator.State#DRAINING}: new sessions and {@code connect()}
 *       calls are rejected with {@code UNAVAILABLE} + {@code ojp-server-draining} trailer.</li>
 *   <li>Set the gRPC health status to {@code NOT_SERVING} for Kubernetes probes / load balancers.</li>
 *   <li>Wait until there are no active sessions, or until the drain timeout expires.</li>
 *   <li>Enter {@link ShutdownCoordinator.State#TERMINATING}, call {@code server.shutdown()},
 *       wait for in-flight calls, then {@code shutdownNow()} anything left.</li>
 *   <li>Roll back and terminate any remaining sessions (logged).</li>
 *   <li>Close every connection pool.</li>
 *   <li>Stop the SQL enhancer, the session cleanup executor and the gRPC executor.</li>
 * </ol>
 *
 * <p>When graceful shutdown is disabled, the drain steps are skipped and the server goes
 * straight to {@code server.shutdown()} as in previous versions.</p>
 */
@Slf4j
public class GracefulShutdownHandler implements Runnable {

    private static final long LEGACY_TERMINATION_TIMEOUT_SECONDS = 30L;
    private static final long EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS = 5L;

    private final ServerConfiguration config;
    private final Server server;
    private final StatementServiceImpl statementService;
    private final SessionManager sessionManager;
    private final ExecutorService sessionCleanupExecutor;
    private final ExecutorService grpcExecutor;
    private final ShutdownCoordinator coordinator;

    public GracefulShutdownHandler(ServerConfiguration config, Server server, StatementServiceImpl statementService,
                                   SessionManager sessionManager, ExecutorService sessionCleanupExecutor,
                                   ExecutorService grpcExecutor) {
        this(config, server, statementService, sessionManager, sessionCleanupExecutor, grpcExecutor,
                ShutdownCoordinator.getInstance());
    }

    GracefulShutdownHandler(ServerConfiguration config, Server server, StatementServiceImpl statementService,
                            SessionManager sessionManager, ExecutorService sessionCleanupExecutor,
                            ExecutorService grpcExecutor, ShutdownCoordinator coordinator) {
        this.config = config;
        this.server = server;
        this.statementService = statementService;
        this.sessionManager = sessionManager;
        this.sessionCleanupExecutor = sessionCleanupExecutor;
        this.grpcExecutor = grpcExecutor;
        this.coordinator = coordinator;
    }

    @Override
    public void run() {
        log.info("Shutting down OJP gRPC Server...");
        boolean graceful = config.isGracefulShutdownEnabled();

        if (graceful) {
            coordinator.startDraining();
        }
        markNotServing();

        if (graceful) {
            long drainTimeoutMs = TimeUnit.SECONDS.toMillis(config.getGracefulShutdownDrainTimeoutSeconds());
            log.info("Draining OJP server: waiting up to {}ms for {} active session(s) to finish",
                    drainTimeoutMs, activeSessionCount());
            coordinator.awaitDrain(this::activeSessionCount, drainTimeoutMs);
        }

        coordinator.startTerminating();
        long terminationTimeoutSeconds = graceful
                ? config.getGracefulShutdownTerminationTimeoutSeconds()
                : LEGACY_TERMINATION_TIMEOUT_SECONDS;
        stopGrpcServer(terminationTimeoutSeconds);

        terminateRemainingSessions();
        closePools();

        if (statementService != null) {
            log.info("Shutting down SQL enhancer engine...");
            statementService.shutdown();
        }
        shutdownExecutor(sessionCleanupExecutor, "Session cleanup executor");
        shutdownExecutor(grpcExecutor, "gRPC server executor");
        log.info("OJP gRPC Server shutdown complete");
    }

    private void markNotServing() {
        try {
            OjpHealthManager.setServiceStatus(OjpHealthManager.Services.OJP_SERVER,
                    HealthCheckResponse.ServingStatus.NOT_SERVING);
        } catch (Exception e) {
            log.warn("Failed to set gRPC health status to NOT_SERVING: {}", e.getMessage());
        }
    }

    int activeSessionCount() {
        if (sessionManager == null) {
            return 0;
        }
        try {
            return sessionManager.getAllSessions().size();
        } catch (Exception e) {
            log.warn("Unable to count active sessions: {}", e.getMessage());
            return 0;
        }
    }

    private void stopGrpcServer(long terminationTimeoutSeconds) {
        if (server == null) {
            return;
        }
        server.shutdown();
        try {
            if (!server.awaitTermination(terminationTimeoutSeconds, TimeUnit.SECONDS)) {
                log.warn("Server did not terminate within {}s, forcing shutdown", terminationTimeoutSeconds);
                server.shutdownNow();
            }
        } catch (InterruptedException e) {
            log.warn("Interrupted while waiting for server shutdown");
            server.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Rolls back and terminates sessions that did not finish during the drain window,
     * so their connections are returned to the pools before the pools are closed.
     */
    void terminateRemainingSessions() {
        if (sessionManager == null) {
            return;
        }
        List<Session> remaining = new ArrayList<>(sessionManager.getAllSessions());
        if (remaining.isEmpty()) {
            return;
        }
        log.warn("{} session(s) still open after drain; rolling back and terminating them", remaining.size());
        for (Session session : remaining) {
            try {
                SessionInfo sessionInfo = session.getSessionInfo();
                log.warn("Terminating open session at shutdown: sessionUUID={}, clientUUID={}, isXA={}",
                        session.getSessionUUID(), session.getClientUUID(), session.isXA());
                if (sessionInfo != null) {
                    sessionManager.terminateSession(sessionInfo);
                }
            } catch (Exception e) {
                log.warn("Failed to terminate session {} at shutdown: {}", session.getSessionUUID(), e.getMessage());
            }
        }
    }

    private void closePools() {
        if (statementService == null) {
            return;
        }
        try {
            statementService.closeAllPools();
        } catch (Exception e) {
            log.warn("Failed to close connection pools during shutdown: {}", e.getMessage());
        }
    }

    private static void shutdownExecutor(ExecutorService executor, String executorName) {
        if (executor == null) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("{} did not terminate gracefully, forcing shutdown", executorName);
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            log.warn("Interrupted while shutting down {}", executorName);
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
