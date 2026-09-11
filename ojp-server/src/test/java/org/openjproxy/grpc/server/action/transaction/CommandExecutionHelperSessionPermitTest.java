package org.openjproxy.grpc.server.action.transaction;

import com.openjproxy.grpc.SessionInfo;
import com.openjproxy.grpc.StatementRequest;
import org.junit.jupiter.api.Test;
import org.openjproxy.grpc.server.Session;
import org.openjproxy.grpc.server.SessionManager;
import org.openjproxy.grpc.server.action.ActionContext;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CommandExecutionHelper#sessionHoldsPermit}, the predicate
 * that controls whether per-statement execution should skip slot acquisition
 * because the session already owns a session-scoped admission permit.
 */
class CommandExecutionHelperSessionPermitTest {

    private static StatementRequest requestFor(String sessionUUID) {
        return StatementRequest.newBuilder()
                .setSession(SessionInfo.newBuilder()
                        .setConnHash("conn-hash")
                        .setSessionUUID(sessionUUID)
                        .build())
                .setSql("select 1")
                .build();
    }

    @Test
    void returnsFalseWhenSessionUUIDIsBlank() {
        ActionContext context = mock(ActionContext.class);
        StatementRequest request = requestFor("");
        assertFalse(CommandExecutionHelper.sessionHoldsPermit(context, request),
                "Sessions not yet created (blank UUID) must not be treated as permit-holding");
    }

    @Test
    void returnsFalseWhenSessionNotFoundInManager() {
        ActionContext context = mock(ActionContext.class);
        SessionManager sessionManager = mock(SessionManager.class);
        when(context.getSessionManager()).thenReturn(sessionManager);
        when(sessionManager.getSession(org.mockito.ArgumentMatchers.any(SessionInfo.class))).thenReturn(null);

        assertFalse(CommandExecutionHelper.sessionHoldsPermit(context, requestFor("missing-uuid")));
    }

    @Test
    void returnsFalseWhenSessionDoesNotHoldPermit() {
        ActionContext context = mock(ActionContext.class);
        SessionManager sessionManager = mock(SessionManager.class);
        Session session = mock(Session.class);
        when(context.getSessionManager()).thenReturn(sessionManager);
        when(sessionManager.getSession(org.mockito.ArgumentMatchers.any(SessionInfo.class))).thenReturn(session);
        when(session.hasConnectionPermit()).thenReturn(false);

        assertFalse(CommandExecutionHelper.sessionHoldsPermit(context, requestFor("uuid-1")));
    }

    @Test
    void returnsTrueWhenSessionHoldsPermit() {
        ActionContext context = mock(ActionContext.class);
        SessionManager sessionManager = mock(SessionManager.class);
        Session session = mock(Session.class);
        when(context.getSessionManager()).thenReturn(sessionManager);
        when(sessionManager.getSession(org.mockito.ArgumentMatchers.any(SessionInfo.class))).thenReturn(session);
        when(session.hasConnectionPermit()).thenReturn(true);

        assertTrue(CommandExecutionHelper.sessionHoldsPermit(context, requestFor("uuid-1")));
    }

    @Test
    void returnsFalseAndSwallowsLookupExceptions() {
        ActionContext context = mock(ActionContext.class);
        SessionManager sessionManager = mock(SessionManager.class);
        when(context.getSessionManager()).thenReturn(sessionManager);
        when(sessionManager.getSession(org.mockito.ArgumentMatchers.any(SessionInfo.class)))
                .thenThrow(new RuntimeException("lookup boom"));

        // Defensive contract: never propagate the exception, fall back to per-statement slot path.
        assertFalse(CommandExecutionHelper.sessionHoldsPermit(context, requestFor("uuid-1")));
    }
}
