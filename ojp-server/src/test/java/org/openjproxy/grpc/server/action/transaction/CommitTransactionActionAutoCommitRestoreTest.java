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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Isolated regression test reproducing a suspected bug: when the JDBC client calls
 * {@code Connection.setAutoCommit(true)} to leave an active transaction, it relies on
 * the {@code commitTransaction} RPC (the same one used by a plain, explicit
 * {@code Connection.commit()}) to also restore the physical connection's autocommit
 * mode — see {@code org.openjproxy.jdbc.Connection#setAutoCommit}, which only calls
 * {@code commitTransaction} and then optimistically flips its own local
 * {@code this.autoCommit} flag to {@code true}, with no other RPC call in between.
 *
 * <p>{@link CommitTransactionAction}, however, only calls {@code conn.commit()} on the
 * physical JDBC connection — it never calls {@code conn.setAutoCommit(true)}. Since
 * this is the exact same action invoked for a plain {@code commit()} (which correctly
 * must NOT touch autocommit), the wire protocol currently has no way to distinguish
 * "commit and stay in manual mode" from "commit and restore autocommit", so
 * {@code setAutoCommit(true)} can never restore it either.</p>
 *
 * <p>This test currently FAILS, demonstrating the bug: after simulating the exact
 * sequence the driver performs for {@code setAutoCommit(true)}, the physical
 * connection is left with {@code autoCommit=false} even though the client believes
 * autocommit is back on.</p>
 */
class CommitTransactionActionAutoCommitRestoreTest {

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

            ServerConfiguration serverConfiguration = new ServerConfiguration();
            CircuitBreakerRegistry circuitBreakerRegistry = new CircuitBreakerRegistry(
                    serverConfiguration.getCircuitBreakerTimeout(),
                    serverConfiguration.getCircuitBreakerThreshold());

            ActionContext context = new ActionContext(
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

            // This mirrors exactly what org.openjproxy.jdbc.Connection#setAutoCommit sees
            // right before calling commitTransaction: an active transaction.
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

            // Act: this is precisely the RPC that Connection.setAutoCommit(true) triggers.
            CommitTransactionAction.getInstance().execute(context, sessionInfo, responseObserver);

            // Sanity check: the action must have completed successfully (no SQLException sent).
            verify(responseObserver).onNext(any());
            verify(responseObserver).onCompleted();

            // This is the bug: the client now believes autoCommit=true, but the physical
            // connection was only committed, never switched back to autocommit mode.
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
}
