package org.openjproxy.grpc.server.action.transaction;

import io.grpc.Context;
import lombok.extern.slf4j.Slf4j;

import java.sql.SQLException;
import java.sql.Statement;

/**
 * Propagates the cancellation of the current gRPC call to a running JDBC statement.
 *
 * <p>When the client cancels the call (for example ODBC {@code SQLCancel}) or its deadline
 * expires (query timeout), the gRPC context is cancelled. Without propagation the database
 * keeps executing the statement and the session connection stays busy until it finishes.
 * This class calls {@link Statement#cancel()} so the database aborts the statement and the
 * blocked execute call fails promptly.
 *
 * <p>Usage:
 * <pre>{@code
 * try (StatementCancellation ignored = StatementCancellation.onCallCancelled(statement)) {
 *     statement.execute(sql);
 * }
 * }</pre>
 */
@Slf4j
final class StatementCancellation implements AutoCloseable {

    private final Context context;
    private final Context.CancellationListener listener;

    private StatementCancellation(Context context, Context.CancellationListener listener) {
        this.context = context;
        this.listener = listener;
    }

    /**
     * Registers {@code statement} to be cancelled when the current gRPC call is cancelled.
     *
     * @param statement the statement about to be executed
     * @return a handle that must be closed after the execution to remove the listener
     */
    static StatementCancellation onCallCancelled(Statement statement) {
        Context context = Context.current();
        Context.CancellationListener listener = cancelled -> cancel(statement, cancelled);
        // A direct executor also runs the listener immediately if the call is already cancelled.
        context.addListener(listener, Runnable::run);
        return new StatementCancellation(context, listener);
    }

    private static void cancel(Statement statement, Context cancelled) {
        try {
            if (!statement.isClosed()) {
                log.debug("gRPC call cancelled ({}), cancelling JDBC statement",
                        cancelled.cancellationCause() != null
                                ? cancelled.cancellationCause().getMessage() : "no cause");
                statement.cancel();
            }
        } catch (SQLException | RuntimeException e) {
            log.debug("Unable to cancel JDBC statement after gRPC cancellation: {}", e.getMessage());
        }
    }

    @Override
    public void close() {
        context.removeListener(listener);
    }
}
