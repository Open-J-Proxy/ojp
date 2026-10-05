package org.openjproxy.grpc.server;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;

/**
 * Tracks the server lifecycle state used for graceful shutdown.
 *
 * <ul>
 *   <li>{@link State#RUNNING} – normal operation.</li>
 *   <li>{@link State#DRAINING} – new sessions/connections are rejected with
 *       {@link ServerDrainingException}; requests on existing sessions keep working.</li>
 *   <li>{@link State#TERMINATING} – the gRPC server is being stopped.</li>
 * </ul>
 *
 * <p>A single process-wide instance is used because the session acquisition path
 * ({@code SessionConnectionHelper}) is static.</p>
 */
@Slf4j
public final class ShutdownCoordinator {

    /**
     * Server lifecycle states.
     */
    public enum State {
        RUNNING,
        DRAINING,
        TERMINATING
    }

    private static final ShutdownCoordinator INSTANCE = new ShutdownCoordinator();
    private static final long DEFAULT_POLL_INTERVAL_MS = 200L;

    private final AtomicReference<State> state = new AtomicReference<>(State.RUNNING);

    private ShutdownCoordinator() {
    }

    public static ShutdownCoordinator getInstance() {
        return INSTANCE;
    }

    public State getState() {
        return state.get();
    }

    /**
     * @return {@code true} while the server accepts new sessions and connections.
     */
    public boolean isAcceptingNewSessions() {
        return state.get() == State.RUNNING;
    }

    /**
     * Throws {@link ServerDrainingException} when the server no longer accepts new sessions.
     *
     * @throws ServerDrainingException if the server is draining or terminating
     */
    public void checkAcceptingNewSessions() throws ServerDrainingException {
        if (!isAcceptingNewSessions()) {
            throw new ServerDrainingException();
        }
    }

    /**
     * Moves the server from RUNNING to DRAINING.
     *
     * @return {@code true} if the transition happened, {@code false} if already draining/terminating
     */
    public boolean startDraining() {
        boolean changed = state.compareAndSet(State.RUNNING, State.DRAINING);
        if (changed) {
            log.info("OJP server entered DRAINING state: new sessions will be rejected");
        }
        return changed;
    }

    /**
     * Moves the server to TERMINATING (from any state).
     */
    public void startTerminating() {
        State previous = state.getAndSet(State.TERMINATING);
        if (previous != State.TERMINATING) {
            log.info("OJP server entered TERMINATING state (previous state: {})", previous);
        }
    }

    /**
     * Waits until {@code activeSessions} reports zero or {@code timeoutMs} elapses.
     *
     * @param activeSessions supplier of the current number of active sessions
     * @param timeoutMs      maximum time to wait in milliseconds
     * @return {@code true} if all sessions finished before the timeout
     */
    public boolean awaitDrain(IntSupplier activeSessions, long timeoutMs) {
        return awaitDrain(activeSessions, timeoutMs, DEFAULT_POLL_INTERVAL_MS);
    }

    /**
     * Waits until {@code activeSessions} reports zero or {@code timeoutMs} elapses.
     *
     * @param activeSessions supplier of the current number of active sessions
     * @param timeoutMs      maximum time to wait in milliseconds
     * @param pollIntervalMs how often to check the session count
     * @return {@code true} if all sessions finished before the timeout
     */
    public boolean awaitDrain(IntSupplier activeSessions, long timeoutMs, long pollIntervalMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMs));
        long lastLog = 0L;
        while (true) {
            int remaining = activeSessions.getAsInt();
            if (remaining <= 0) {
                log.info("Drain complete: no active sessions remaining");
                return true;
            }
            long now = System.nanoTime();
            if (now - deadline >= 0) {
                log.warn("Drain timeout of {}ms reached with {} active session(s) remaining", timeoutMs, remaining);
                return false;
            }
            if (now - lastLog >= TimeUnit.SECONDS.toNanos(5)) {
                log.info("Draining: waiting for {} active session(s) to finish", remaining);
                lastLog = now;
            }
            try {
                long sleepMs = Math.min(Math.max(1L, pollIntervalMs), TimeUnit.NANOSECONDS.toMillis(deadline - now) + 1);
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while draining sessions");
                return false;
            }
        }
    }

    /**
     * Resets the state to RUNNING. Intended for tests only.
     */
    public void resetForTesting() {
        state.set(State.RUNNING);
    }
}
