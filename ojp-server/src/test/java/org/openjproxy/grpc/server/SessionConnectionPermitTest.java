package org.openjproxy.grpc.server;

import com.openjproxy.grpc.SessionInfo;
import org.junit.jupiter.api.Test;

import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link Session#hasConnectionPermit()} and the wiring around
 * {@link Session#setConnectionPermitReleaseHook(Runnable)} that is consulted by
 * the per-statement admission control short-circuit.
 */
class SessionConnectionPermitTest {

    @Test
    void hasConnectionPermit_returnsFalseByDefault() {
        Session session = new Session(mock(Connection.class), "conn-hash", "client-uuid");
        assertFalse(session.hasConnectionPermit(),
                "A freshly created session should not own an admission permit");
    }

    @Test
    void hasConnectionPermit_returnsTrueAfterHookRegistered() {
        Session session = new Session(mock(Connection.class), "conn-hash", "client-uuid");
        session.setConnectionPermitReleaseHook(() -> { /* no-op */ });
        assertTrue(session.hasConnectionPermit(),
                "Session should report owning a permit once the release hook is registered");
    }

    @Test
    void hasConnectionPermit_returnsFalseAfterSessionClose() throws Exception {
        SessionManager sessionManager = new SessionManagerImpl();
        sessionManager.registerClientUUID("conn-hash", "client-uuid");
        SessionInfo info = sessionManager.createSession("client-uuid", mock(Connection.class));
        Session session = sessionManager.getSession(info);

        java.util.concurrent.atomic.AtomicInteger releaseCount = new java.util.concurrent.atomic.AtomicInteger();
        session.setConnectionPermitReleaseHook(releaseCount::incrementAndGet);
        assertTrue(session.hasConnectionPermit());

        session.terminate();

        assertFalse(session.hasConnectionPermit(),
                "Closing the session should release the permit and clear ownership");
        assertTrue(releaseCount.get() == 1,
                "Terminate should run the release hook exactly once");
    }
}
