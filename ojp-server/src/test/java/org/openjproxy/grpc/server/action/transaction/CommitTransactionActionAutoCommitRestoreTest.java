package org.openjproxy.grpc.server.action.transaction;

import com.openjproxy.grpc.SessionInfo;
import com.openjproxy.grpc.TransactionInfo;
import com.openjproxy.grpc.TransactionStatus;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.openjproxy.grpc.server.CircuitBreakerRegistry;
import org.openjproxy.grpc.server.ClusterHealthTracker;
import org.openjproxy.grpc.server.MultinodeXaCoordinator;
import org.openjproxy.grpc.server.ServerConfiguration;
import org.openjproxy.grpc.server.SessionManager;
import org.openjproxy.grpc.server.action.ActionContext;
import org.openjproxy.grpc.server.readwrite.ReadWriteDataSourceRegistry;
import org.openjproxy.xa.pool.spi.XAConnectionPoolProvider;

import javax.sql.XADataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression test for a bug where {@code Connection.setAutoCommit(true)} failed to
 * restore the physical connection's autocommit mode after committing an active
 * transaction — see {@code org.openjproxy.jdbc.Connection#setAutoCommit}, which calls
 * the same {@code commitTransaction} RPC that a plain, explicit
 * {@code Connection.commit()} also uses, and then optimistically flips its own local
 * {@code this.autoCommit} flag to {@code true}, with no other RPC call in between.
 *
 * <p>The fix adds a {@code restoreAutoCommit} flag to the {@code SessionInfo} request
 * message. The driver sets it to {@code true} only when {@code setAutoCommit(true)}
 * is resolving an active transaction; {@link CommitTransactionAction} checks the flag
 * and calls {@code conn.setAutoCommit(true)} after {@code conn.commit()} only when it
 * is set, so a plain {@code commit()} (flag left at its {@code false} default)
 * correctly stays in manual-commit mode.</p>
 */
class CommitTransactionActionAutoCommitRestoreTest {

    private static ActionContext newActionContext(SessionManager sessionManager) {
        ServerConfiguration serverConfiguration = new ServerConfiguration();
        CircuitBreakerRegistry circuitBreakerRegistry = new CircuitBreakerRegistry(
                serverConfiguration.getCircuitBreakerTimeout(),
                serverConfiguration.getCircuitBreakerThreshold());

        return new ActionContext(
                new ConcurrentHashMap<>(),
                new ConcurrentHashMap<>(),
                new ConcurrentHashMap<>(),
                new ConcurrentHashMap<>(),
                new ConcurrentHashMap<>(),
                new ConcurrentHashMap<>(),
                new ConcurrentHashMap<>(),
                new ReadWriteDataSourceRegistry(),
                mock(XAConnectionPoolProvider.class),
                new MultinodeXaCoordinator(),
                new ClusterHealthTracker(),
                sessionManager,
                circuitBreakerRegistry,
                serverConfiguration,
                null,
                null);
    }

    @Test
    void setAutoCommitTrueShouldRestoreAutoCommitOnThePhysicalConnectionAfterCommit() throws SQLException {
        // Arrange: a real H2 connection with autoCommit already disabled, emulating the
        // state left behind by StartTransactionAction (sessionConnection.setAutoCommit(false))
        // when a transaction was started.
        Connection physicalConnection = DriverManager.getConnection(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        try {
            physicalConnection.setAutoCommit(false);
            try (java.sql.Statement stmt = physicalConnection.createStatement()) {
                stmt.executeUpdate("CREATE TABLE t (id INT)");
                stmt.executeUpdate("INSERT INTO t (id) VALUES (1)");
            }

            SessionManager sessionManager = mock(SessionManager.class);
            when(sessionManager.getConnection(any())).thenReturn(physicalConnection);
            ActionContext context = newActionContext(sessionManager);

            // This mirrors exactly what org.openjproxy.jdbc.Connection#setAutoCommit sends
            // right before calling commitTransaction to leave an active transaction:
            // restoreAutoCommit=true on top of the active-transaction state.
            TransactionInfo activeTransaction = TransactionInfo.newBuilder()
                    .setTransactionStatus(TransactionStatus.TRX_ACTIVE)
                    .setTransactionUUID(UUID.randomUUID().toString())
                    .build();
            SessionInfo sessionInfo = SessionInfo.newBuilder()
                    .setSessionUUID(UUID.randomUUID().toString())
                    .setTransactionInfo(activeTransaction)
                    .setRestoreAutoCommit(true)
                    .build();

            @SuppressWarnings("unchecked")
            StreamObserver<SessionInfo> responseObserver = mock(StreamObserver.class);

            // Act: this is precisely the RPC that Connection.setAutoCommit(true) triggers.
            CommitTransactionAction.getInstance().execute(context, sessionInfo, responseObserver);

            // Sanity check: the action must have completed successfully (no SQLException sent).
            verify(responseObserver).onNext(any());
            verify(responseObserver).onCompleted();

            assertTrue(physicalConnection.getAutoCommit(),
                    "The physical connection's autoCommit should be restored to true after "
                            + "commitTransaction resolves an active transaction on behalf of "
                            + "Connection.setAutoCommit(true) — otherwise every statement run "
                            + "afterwards silently opens an implicit transaction that the client "
                            + "will never explicitly commit or roll back.");
        } finally {
            physicalConnection.close();
        }
    }

    @Test
    void plainCommitShouldNotRestoreAutoCommitWhenRestoreFlagIsNotSet() throws SQLException {
        // Regression guard: a plain, explicit Connection.commit() call must leave the
        // physical connection in manual-commit mode. It goes through the very same
        // action/RPC as setAutoCommit(true), differing only by NOT setting
        // restoreAutoCommit (left at its false default).
        Connection physicalConnection = DriverManager.getConnection(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        try {
            physicalConnection.setAutoCommit(false);
            try (java.sql.Statement stmt = physicalConnection.createStatement()) {
                stmt.executeUpdate("CREATE TABLE t (id INT)");
                stmt.executeUpdate("INSERT INTO t (id) VALUES (1)");
            }

            SessionManager sessionManager = mock(SessionManager.class);
            when(sessionManager.getConnection(any())).thenReturn(physicalConnection);
            ActionContext context = newActionContext(sessionManager);

            TransactionInfo activeTransaction = TransactionInfo.newBuilder()
                    .setTransactionStatus(TransactionStatus.TRX_ACTIVE)
                    .setTransactionUUID(UUID.randomUUID().toString())
                    .build();
            SessionInfo sessionInfo = SessionInfo.newBuilder()
                    .setSessionUUID(UUID.randomUUID().toString())
                    .setTransactionInfo(activeTransaction)
                    .build();

            @SuppressWarnings("unchecked")
            StreamObserver<SessionInfo> responseObserver = mock(StreamObserver.class);

            CommitTransactionAction.getInstance().execute(context, sessionInfo, responseObserver);

            verify(responseObserver).onNext(any());
            verify(responseObserver).onCompleted();

            assertFalse(physicalConnection.getAutoCommit(),
                    "A plain commit() (restoreAutoCommit not set) must leave the physical "
                            + "connection in manual-commit mode so a subsequent statement stays "
                            + "inside a new, explicit transaction.");
        } finally {
            physicalConnection.close();
        }
    }
}
