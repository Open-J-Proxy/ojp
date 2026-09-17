package org.openjproxy.jdbc;

import com.openjproxy.grpc.CallResourceRequest;
import com.openjproxy.grpc.CallType;
import com.openjproxy.grpc.DbName;
import com.openjproxy.grpc.SessionInfo;
import org.junit.jupiter.api.Test;
import org.openjproxy.grpc.ProtoConverter;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Client-side regression tests for a bug where {@link Connection#setAutoCommit(boolean)
 * setAutoCommit(true)} failed to restore autoCommit on the physical database connection held
 * by {@code ojp-server}.
 *
 * <p>The fix delegates {@code setAutoCommit(true)} straight to the physical connection's own
 * {@code setAutoCommit(boolean)} method via the generic {@code callResource}/{@code CALL_SET}
 * mechanism (the same one used for {@code setCatalog}, {@code setTransactionIsolation}, etc.),
 * instead of going through {@code commitTransaction}. Every JDBC-compliant driver already
 * implicitly commits a pending transaction when switching out of manual-commit mode, so this
 * single generic call both commits and restores autoCommit on the physical connection - no
 * special-casing is needed on the server side.
 *
 * <p>A plain, explicit {@link Connection#commit()} is unaffected: it must keep the physical
 * connection in manual-commit mode, and continues to go through {@code commitTransaction} as
 * before.
 */
class ConnectionSetAutoCommitTest {

    private static final SessionInfo SESSION = SessionInfo.newBuilder().setConnHash("").build();

    @Test
    void setAutoCommitTrueShouldDelegateToThePhysicalConnectionViaCallResource() throws Exception {
        RecordingStatementService fakeService = new RecordingStatementService();
        Connection connection = new Connection(SESSION, fakeService, DbName.H2);

        connection.setAutoCommit(false);
        connection.setAutoCommit(true);

        assertEquals(0, fakeService.commitTransactionRequests.size(),
                "setAutoCommit(true) must not call commitTransaction; it must delegate directly "
                        + "to the physical connection's own setAutoCommit(true), which already "
                        + "commits any pending transaction per the JDBC contract.");
        assertEquals(1, fakeService.getCallResourceInvocations().size());

        CallResourceRequest request = fakeService.getCallResourceInvocations().get(0);
        assertEquals(CallType.CALL_SET, request.getTarget().getCallType());
        assertEquals("AutoCommit", request.getTarget().getResourceName());
        assertEquals(1, request.getTarget().getParamsCount());
        assertEquals(Boolean.TRUE, ProtoConverter.fromParameterValue(request.getTarget().getParams(0)));
        assertTrue(connection.getAutoCommit());
    }

    @Test
    void plainCommitShouldStillGoThroughCommitTransactionAndNotTouchAutoCommit() throws Exception {
        RecordingStatementService fakeService = new RecordingStatementService();
        Connection connection = new Connection(SESSION, fakeService, DbName.H2);

        connection.setAutoCommit(false);
        connection.commit();

        assertEquals(1, fakeService.commitTransactionRequests.size(),
                "A plain, explicit commit() must go through commitTransaction as before.");
        assertEquals(0, fakeService.getCallResourceInvocations().size(),
                "A plain commit() must not touch autoCommit on the physical connection.");
        assertFalse(connection.getAutoCommit());
    }

    @Test
    void setAutoCommitTrueAfterAnExplicitCommitShouldStillRestoreAutoCommit() throws Exception {
        RecordingStatementService fakeService = new RecordingStatementService();
        Connection connection = new Connection(SESSION, fakeService, DbName.H2);

        connection.setAutoCommit(false);
        connection.commit();
        connection.setAutoCommit(true);

        assertEquals(1, fakeService.commitTransactionRequests.size(),
                "The explicit commit() itself must still go through commitTransaction.");
        assertEquals(1, fakeService.getCallResourceInvocations().size(),
                "setAutoCommit(true) called right after an explicit commit(), with no statement "
                        + "executed in between, must still restore autoCommit on the physical "
                        + "connection - the fix must not be gated on any cached transaction "
                        + "status.");

        CallResourceRequest request = fakeService.getCallResourceInvocations().get(0);
        assertEquals(CallType.CALL_SET, request.getTarget().getCallType());
        assertEquals("AutoCommit", request.getTarget().getResourceName());
        assertTrue(connection.getAutoCommit());
    }

    /**
     * {@link FakeStatementService} extension that also records every {@code startTransaction}/
     * {@code commitTransaction} request, so tests can assert {@link Connection} did NOT call
     * them when it should have used the generic {@code callResource} path instead.
     */
    private static final class RecordingStatementService extends FakeStatementService {

        private final List<SessionInfo> startTransactionRequests = new ArrayList<>();
        private final List<SessionInfo> commitTransactionRequests = new ArrayList<>();

        @Override
        public SessionInfo startTransaction(SessionInfo session) {
            this.startTransactionRequests.add(session);
            return SESSION;
        }

        @Override
        public SessionInfo commitTransaction(SessionInfo session) {
            this.commitTransactionRequests.add(session);
            return SESSION;
        }
    }
}
