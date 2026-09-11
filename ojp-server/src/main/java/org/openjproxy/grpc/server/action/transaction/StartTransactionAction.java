package org.openjproxy.grpc.server.action.transaction;

import com.openjproxy.grpc.SessionInfo;
import com.openjproxy.grpc.TransactionInfo;
import com.openjproxy.grpc.TransactionStatus;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.grpc.server.ConnectionSessionDTO;
import org.openjproxy.grpc.server.action.Action;
import org.openjproxy.grpc.server.action.ActionContext;
import org.openjproxy.grpc.server.action.streaming.SessionConnectionHelper;
import org.openjproxy.grpc.server.action.util.ProcessClusterHealthAction;
import org.openjproxy.grpc.server.utils.SessionInfoUtils;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

import static org.openjproxy.grpc.server.GrpcExceptionHandler.sendSQLExceptionMetadata;

/**
 * Action that starts a database transaction for a session.
 * <p>
 * If the incoming {@link SessionInfo} has no session UUID, a new session is created first.
 * The underlying JDBC connection's auto-commit is set to {@code false}, and a new
 * {@link TransactionInfo} with status {@link TransactionStatus#TRX_ACTIVE} is built and
 * returned in the response. Cluster health from the request is processed before starting
 * the transaction.
 * <p>
 * The response echoes back the {@code targetServer} from the incoming request when a new
 * session is created.
 *
 * @see Action
 * @see SessionInfo
 * @see TransactionInfo
 */
@Slf4j
public class StartTransactionAction implements Action<SessionInfo, SessionInfo> {

    private static final StartTransactionAction INSTANCE = new StartTransactionAction();

    private StartTransactionAction() {
    }

    /**
     * Returns the singleton instance of this action.
     *
     * @return the singleton instance
     */
    public static StartTransactionAction getInstance() {
        return INSTANCE;
    }

    /**
     * Starts a transaction for the given session.
     * <p>
     * Creates a new session if none exists, then disables auto-commit on the connection
     * and returns an updated {@link SessionInfo} containing the new transaction metadata.
     *
     * @param context          the action context with session manager and datasource map
     * @param sessionInfo      the incoming session info (may lack session UUID for new sessions)
     * @param responseObserver the observer to send the updated session info or error metadata
     */
    @Override
    public void execute(ActionContext context, SessionInfo sessionInfo, StreamObserver<SessionInfo> responseObserver) {
        log.debug("Starting transaction");

        // Process cluster health from the request
        ProcessClusterHealthAction.getInstance().execute(context, sessionInfo);

        var sessionManager = context.getSessionManager();

        try {
            SessionInfo activeSessionInfo = sessionInfo;
            Connection sessionConnection;

            // Start a session if none started yet.
            if (sessionInfo.getSessionUUID().isEmpty()) {
                ConnectionSessionDTO dto = SessionConnectionHelper.sessionConnection(context, sessionInfo, true);
                sessionConnection = dto.getConnection();
                activeSessionInfo = dto.getSession();
                // Preserve targetServer from incoming request
                activeSessionInfo = SessionInfoUtils.withTargetServer(activeSessionInfo, getTargetServer(sessionInfo));
            } else {
                sessionConnection = sessionManager.getConnection(activeSessionInfo);
            }
            // Start a transaction
            sessionConnection.setAutoCommit(Boolean.FALSE);

            TransactionInfo transactionInfo = TransactionInfo.newBuilder()
                    .setTransactionStatus(TransactionStatus.TRX_ACTIVE)
                    .setTransactionUUID(UUID.randomUUID().toString())
                    .build();

            SessionInfo.Builder sessionInfoBuilder = SessionInfoUtils.newBuilderFrom(activeSessionInfo, context);
            sessionInfoBuilder.setTransactionInfo(transactionInfo);
            // Server echoes back targetServer from incoming request (preserved by
            // newBuilderFrom)

            responseObserver.onNext(sessionInfoBuilder.build());
            responseObserver.onCompleted();
        } catch (SQLException se) {
            sendSQLExceptionMetadata(se, responseObserver);
        } catch (Exception e) {
            sendSQLExceptionMetadata(new SQLException("Unable to start transaction: " + e.getMessage()),
                    responseObserver);
        }
    }

    /**
     * Returns the target server identifier from the incoming request.
     * Echoes back what the client sent without any override.
     *
     * @param incomingSessionInfo the session info from the client request
     * @return the target server string, or empty string if not present
     */
    private String getTargetServer(SessionInfo incomingSessionInfo) {
        // Echo back the targetServer from incoming request, or return empty string if
        // not present
        if (incomingSessionInfo != null &&
                !incomingSessionInfo.getTargetServer().isEmpty()) {
            return incomingSessionInfo.getTargetServer();
        }

        // Return empty string if client didn't send targetServer
        return "";
    }
}
