package org.openjproxy.grpc.server.action.session;

import com.openjproxy.grpc.SessionInfo;
import com.openjproxy.grpc.SessionTerminationStatus;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.grpc.server.Session;
import org.openjproxy.grpc.server.action.Action;
import org.openjproxy.grpc.server.action.ActionContext;
import org.openjproxy.xa.pool.XABackendSession;
import org.openjproxy.xa.pool.XATransactionRegistry;

import java.sql.SQLException;

import static org.openjproxy.grpc.server.GrpcExceptionHandler.sendSQLExceptionMetadata;

/**
 * Action to terminate a database session and clean up resources.
 * <p>
 * This action handles the termination of a session, including returning any
 * completed XA backend sessions to the pool if applicable.
 * <p>
 * This action is implemented as a singleton for thread-safety and memory efficiency.
 * It is stateless and receives all necessary context via parameters.
 */
@Slf4j
public class TerminateSessionAction implements Action<SessionInfo, SessionTerminationStatus> {

    private static final TerminateSessionAction INSTANCE = new TerminateSessionAction();

    /**
     * Private constructor prevents external instantiation.
     */
    private TerminateSessionAction() {
        // Private constructor for singleton pattern
    }

    /**
     * Returns the singleton instance of TerminateSessionAction.
     *
     * @return the singleton instance
     */
    public static TerminateSessionAction getInstance() {
        return INSTANCE;
    }

    /**
     * Executes the session termination operation.
     *
     * @param context          the action context containing shared state and services
     * @param sessionInfo      the session info to be terminated
     * @param responseObserver the response observer for sending the result
     */
    @Override
    public void execute(ActionContext context, SessionInfo sessionInfo, StreamObserver<SessionTerminationStatus> responseObserver) {
        try {
            log.info("Terminating session");

            // Before terminating, return any completed XA backend sessions to pool
            // This implements the dual-condition lifecycle: sessions are returned when
            // both transaction is complete AND XAConnection is closed
            log.debug("[XA-TERMINATE] terminateSession called for sessionUUID={}, isXA={}, connHash={}",
                    sessionInfo.getSessionUUID(), sessionInfo.getIsXA(), sessionInfo.getConnHash());

            Session session = context.getSessionManager().getSession(sessionInfo);
            if (session != null) {
                synchronized (session) {
                    if (context.getSessionManager().getSession(sessionInfo) == session) {
                        terminateSession(context, sessionInfo, session);
                    } else if (session.isXA()) {
                        returnCompletedSessions(context, session.getConnectionHash(), sessionInfo.getSessionUUID());
                    }
                }
            } else if (sessionInfo.getIsXA()) {
                returnCompletedSessions(context, sessionInfo.getConnHash(), sessionInfo.getSessionUUID());
            }

            responseObserver.onNext(SessionTerminationStatus.newBuilder().setTerminated(true).build());
            responseObserver.onCompleted();
        } catch (SQLException se) {
            log.error("SQLException during terminateSession", se);
            sendSQLExceptionMetadata(se, responseObserver);
        } catch (Exception e) {
            log.error("Unexpected error during terminateSession", e);
            sendSQLExceptionMetadata(new SQLException("Unable to terminate session: " + e.getMessage()), responseObserver);
        }
    }

    private void returnCompletedSessions(ActionContext context, String connHash, String sessionId) {
        XATransactionRegistry registry = context.getXaRegistries().get(connHash);
        if (registry != null) {
            registry.returnCompletedSessions(sessionId);
        }
    }

    private void terminateSession(ActionContext context, SessionInfo sessionInfo, Session session) throws Exception {
        XATransactionRegistry registry = session.isXA()
                ? context.getXaRegistries().get(session.getConnectionHash()) : null;
        XATransactionRegistry origin = session.getXaPoolRegistry();
        XABackendSession backend = (XABackendSession) session.getBackendSession();
        boolean noTransactions = registry == null || !registry.hasTransactionsForSession(session.getSessionUUID());
        boolean closeUnused = session.isXA() && origin != null && backend != null
                && !session.isXaTransactionStarted() && noTransactions
                && (origin == registry || !origin.hasTransactionsForSession(session.getSessionUUID()));

        if (session.isXA() && registry != null) {
            registry.returnCompletedSessions(session.getSessionUUID());
        }
        Exception cleanupError = null;
        try {
            context.getSessionManager().terminateSession(sessionInfo);
        } catch (Exception e) {
            cleanupError = e;
        }
        if (closeUnused) {
            try {
                context.getXaPoolProvider().invalidateSession(origin.getPooledXADataSource(), backend);
            } catch (Exception e) {
                if (cleanupError == null) {
                    throw e;
                }
                cleanupError.addSuppressed(e);
            }
        }
        if (cleanupError != null) {
            throw cleanupError;
        }
    }
}
