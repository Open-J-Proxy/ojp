package org.openjproxy.jdbc;

import com.openjproxy.grpc.DbName;
import com.openjproxy.grpc.SessionInfo;
import com.openjproxy.grpc.TransactionInfo;
import com.openjproxy.grpc.TransactionStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Client-side regression tests for the {@code setAutoCommit} restore bug: {@link Connection}
 * must tell the server (via the {@code restoreAutoCommit} field on the {@code commitTransaction}
 * request) to restore the physical connection's autocommit mode when {@code setAutoCommit(true)}
 * resolves an active transaction, but a plain, explicit {@link Connection#commit()} must not.
 * See {@code CommitTransactionActionAutoCommitRestoreTest} in {@code ojp-server} for the
 * equivalent server-side coverage.
 */
class ConnectionSetAutoCommitTest {

    private static final SessionInfo SESSION = SessionInfo.newBuilder().setConnHash("").build();

    private static final SessionInfo ACTIVE_TRANSACTION_SESSION = SessionInfo.newBuilder()
            .setConnHash("")
            .setTransactionInfo(TransactionInfo.newBuilder()
                    .setTransactionStatus(TransactionStatus.TRX_ACTIVE)
                    .build())
            .build();

    @Test
    void setAutoCommitTrueShouldRequestServerToRestoreAutoCommitWhenLeavingAnActiveTransaction() throws Exception {
        RecordingStatementService fakeService = new RecordingStatementService();
        Connection connection = new Connection(SESSION, fakeService, DbName.H2);

        connection.setAutoCommit(false);
        connection.setAutoCommit(true);

        assertEquals(1, fakeService.commitTransactionRequests.size());
        assertTrue(fakeService.commitTransactionRequests.get(0).getRestoreAutoCommit(),
                "setAutoCommit(true) resolving an active transaction must ask the server to restore "
                        + "autoCommit on the physical connection, otherwise the server has no way to "
                        + "distinguish this from a plain commit() that must stay in manual-commit mode.");
        assertTrue(connection.getAutoCommit());
    }

    @Test
    void plainCommitShouldNotRequestAutoCommitRestore() throws Exception {
        RecordingStatementService fakeService = new RecordingStatementService();
        Connection connection = new Connection(SESSION, fakeService, DbName.H2);

        connection.setAutoCommit(false);
        connection.commit();

        assertEquals(1, fakeService.commitTransactionRequests.size());
        assertFalse(fakeService.commitTransactionRequests.get(0).getRestoreAutoCommit(),
                "A plain, explicit commit() must not ask the server to restore autoCommit; only "
                        + "setAutoCommit(true) resolving an active transaction should.");
        assertFalse(connection.getAutoCommit());
    }

    @Test
    void setAutoCommitTrueAfterAnExplicitCommitShouldStillRequestAutoCommitRestore() throws Exception {
        RecordingStatementService fakeService = new RecordingStatementService();
        Connection connection = new Connection(SESSION, fakeService, DbName.H2);

        connection.setAutoCommit(false);
        connection.commit();
        connection.setAutoCommit(true);

        assertEquals(2, fakeService.commitTransactionRequests.size());
        assertFalse(fakeService.commitTransactionRequests.get(0).getRestoreAutoCommit(),
                "The explicit commit() itself must not request autoCommit restore.");
        assertTrue(fakeService.commitTransactionRequests.get(1).getRestoreAutoCommit(),
                "setAutoCommit(true) called right after an explicit commit(), with no active "
                        + "transaction in between, must still request the server to restore "
                        + "autoCommit. Gating this solely on the last-known transaction status "
                        + "would incorrectly skip it, since the physical connection stays in "
                        + "manual-commit mode until autoCommit is explicitly restored.");
        assertTrue(connection.getAutoCommit());
    }

    /**
     * {@link FakeStatementService} extension that records every {@code startTransaction}/
     * {@code commitTransaction} request so tests can assert on the exact {@link SessionInfo}
     * sent by {@link Connection}.
     */
    private static final class RecordingStatementService extends FakeStatementService {

        private final List<SessionInfo> startTransactionRequests = new ArrayList<>();
        private final List<SessionInfo> commitTransactionRequests = new ArrayList<>();

        @Override
        public SessionInfo startTransaction(SessionInfo session) {
            this.startTransactionRequests.add(session);
            return ACTIVE_TRANSACTION_SESSION;
        }

        @Override
        public SessionInfo commitTransaction(SessionInfo session) {
            this.commitTransactionRequests.add(session);
            return SESSION;
        }
    }
}
