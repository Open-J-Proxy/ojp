package org.openjproxy.grpc.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShutdownCoordinatorTest {

    private final ShutdownCoordinator coordinator = ShutdownCoordinator.getInstance();

    @AfterEach
    void reset() {
        coordinator.resetForTesting();
    }

    @Test
    void shouldAcceptNewSessionsWhenRunning() {
        assertEquals(ShutdownCoordinator.State.RUNNING, coordinator.getState());
        assertTrue(coordinator.isAcceptingNewSessions());
        assertDoesNotThrow(coordinator::checkAcceptingNewSessions);
    }

    @Test
    void shouldRejectNewSessionsWhenDraining() {
        assertTrue(coordinator.startDraining());

        assertEquals(ShutdownCoordinator.State.DRAINING, coordinator.getState());
        assertFalse(coordinator.isAcceptingNewSessions());
        ServerDrainingException ex = assertThrows(ServerDrainingException.class,
                coordinator::checkAcceptingNewSessions);
        assertEquals("08001", ex.getSQLState());
    }

    @Test
    void shouldOnlyStartDrainingOnceWhenCalledTwice() {
        assertTrue(coordinator.startDraining());
        assertFalse(coordinator.startDraining());
    }

    @Test
    void shouldRejectNewSessionsWhenTerminating() {
        coordinator.startDraining();
        coordinator.startTerminating();

        assertEquals(ShutdownCoordinator.State.TERMINATING, coordinator.getState());
        assertFalse(coordinator.isAcceptingNewSessions());
        assertFalse(coordinator.startDraining(), "Must not go back to DRAINING from TERMINATING");
    }

    @Test
    void shouldReturnTrueWhenSessionsFinishBeforeDrainTimeout() {
        AtomicInteger sessions = new AtomicInteger(3);

        boolean drained = coordinator.awaitDrain(sessions::getAndDecrement, 5_000L, 10L);

        assertTrue(drained);
    }

    @Test
    void shouldReturnFalseWhenDrainTimeoutExpires() {
        long start = System.nanoTime();

        boolean drained = coordinator.awaitDrain(() -> 1, 150L, 10L);

        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertFalse(drained);
        assertTrue(elapsedMs >= 150L, "Should wait for the full drain timeout, waited " + elapsedMs + "ms");
    }

    @Test
    void shouldReturnImmediatelyWhenNoSessions() {
        assertTrue(coordinator.awaitDrain(() -> 0, 10_000L, 10L));
    }
}
