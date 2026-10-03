package org.openjproxy.grpc.server.action.streaming;

import com.openjproxy.grpc.ConnectionDetails;
import com.openjproxy.grpc.SessionInfo;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openjproxy.grpc.server.ConnectionSessionDTO;
import org.openjproxy.grpc.server.GrpcExceptionHandler;
import org.openjproxy.grpc.server.ServerDrainingException;
import org.openjproxy.grpc.server.SessionManager;
import org.openjproxy.grpc.server.ShutdownCoordinator;
import org.openjproxy.grpc.server.action.ActionContext;
import org.openjproxy.grpc.server.action.connection.ConnectAction;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Verifies that a draining server rejects requests that would create a new session,
 * while requests on existing sessions keep working.
 */
class SessionConnectionHelperDrainingTest {

    @AfterEach
    void reset() {
        ShutdownCoordinator.getInstance().resetForTesting();
    }

    @Test
    void shouldRejectRequestWithoutSessionWhenDraining() {
        ShutdownCoordinator.getInstance().startDraining();
        ActionContext context = mock(ActionContext.class);
        SessionInfo noSession = SessionInfo.newBuilder().setConnHash("hash").build();

        assertThrows(ServerDrainingException.class,
                () -> SessionConnectionHelper.sessionConnection(context, noSession, true));
    }

    @Test
    void shouldServeRequestOnExistingSessionWhenDraining() throws SQLException {
        ShutdownCoordinator.getInstance().startDraining();
        SessionInfo existing = SessionInfo.newBuilder().setConnHash("hash").setSessionUUID("session-1").build();
        Connection connection = mock(Connection.class);
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        when(metaData.getURL()).thenReturn("jdbc:h2:mem:test");
        when(connection.getMetaData()).thenReturn(metaData);
        SessionManager sessionManager = mock(SessionManager.class);
        when(sessionManager.getConnection(existing)).thenReturn(connection);
        ActionContext context = mock(ActionContext.class);
        when(context.getSessionManager()).thenReturn(sessionManager);

        ConnectionSessionDTO dto = SessionConnectionHelper.sessionConnection(context, existing, true);

        assertSame(connection, dto.getConnection());
    }

    @Test
    void shouldRejectConnectAndHeartbeatWhenDraining() {
        ShutdownCoordinator.getInstance().startDraining();
        ActionContext context = mock(ActionContext.class);
        AtomicReference<Throwable> error = new AtomicReference<>();
        StreamObserver<SessionInfo> observer = new StreamObserver<>() {
            @Override
            public void onNext(SessionInfo value) {
                // not expected
            }

            @Override
            public void onError(Throwable t) {
                error.set(t);
            }

            @Override
            public void onCompleted() {
                // not expected
            }
        };

        ConnectAction.getInstance().execute(context, ConnectionDetails.newBuilder().build(), observer);

        StatusRuntimeException sre = assertInstanceOf(StatusRuntimeException.class, error.get());
        assertEquals(Status.Code.UNAVAILABLE, sre.getStatus().getCode());
        assertEquals("true", sre.getTrailers().get(GrpcExceptionHandler.SERVER_DRAINING_KEY));
        verifyNoInteractions(context);
    }
}
