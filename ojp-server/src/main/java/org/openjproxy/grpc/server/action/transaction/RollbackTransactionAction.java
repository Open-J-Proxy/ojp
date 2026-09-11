package org.openjproxy.grpc.server.action.transaction;

import com.openjproxy.grpc.SessionInfo;
import com.openjproxy.grpc.TransactionInfo;
import com.openjproxy.grpc.TransactionStatus;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.grpc.server.action.Action;
import org.openjproxy.grpc.server.action.ActionContext;
import org.openjproxy.grpc.server.action.util.ProcessClusterHealthAction;
import org.openjproxy.grpc.server.utils.SessionInfoUtils;

import java.sql.Connection;
import java.sql.SQLException;

import static org.openjproxy.grpc.server.GrpcExceptionHandler.sendSQLExceptionMetadata;

/**
 * Action to rollback a database transaction.
 * <p>
 * This action performs a rollback on the connection associated with the session,
 * updates the transaction status to TRX_ROLLBACK, and returns the updated SessionInfo.
 * <p>
 * This action is implemented as a singleton for thread-safety and memory efficiency.
 * It is stateless and receives all necessary context via parameters.
 */
@Slf4j
public class RollbackTransactionAction implements Action<SessionInfo, SessionInfo> {

    private static final RollbackTransactionAction INSTANCE = new RollbackTransactionAction();

    /**
     * Private constructor prevents external instantiation.
     */
    private RollbackTransactionAction() {
        // Private constructor for singleton pattern
    }

    /**
     * Returns the singleton instance of RollbackTransactionAction.
     *
     * @return the singleton instance
     */
    public static RollbackTransactionAction getInstance() {
        return INSTANCE;
    }

    /**
     * Executes the rollback transaction operation.
     *
     * @param context          the action context containing shared state and services
     * @param sessionInfo      the session info containing session and transaction details
     * @param responseObserver the response observer for sending the result
     */
    @Override
    public void execute(ActionContext context, SessionInfo sessionInfo, StreamObserver<SessionInfo> responseObserver) {
        log.debug("Rollback transaction");

        // Process cluster health from the request
        ProcessClusterHealthAction.getInstance().execute(context, sessionInfo);

        try {
            Connection conn = context.getSessionManager().getConnection(sessionInfo);
            if (conn == null) {
                throw new SQLException("Connection not found for this session");
            }
            conn.rollback();

            TransactionInfo transactionInfo = TransactionInfo.newBuilder()
                    .setTransactionStatus(TransactionStatus.TRX_ROLLBACK)
                    .setTransactionUUID(sessionInfo.getTransactionInfo().getTransactionUUID())
                    .build();

            SessionInfo.Builder sessionInfoBuilder = SessionInfoUtils.newBuilderFrom(sessionInfo, context);
            sessionInfoBuilder.setTransactionInfo(transactionInfo);
            // Server echoes back targetServer from incoming request (preserved by newBuilderFrom)

            responseObserver.onNext(sessionInfoBuilder.build());
            responseObserver.onCompleted();
        } catch (SQLException se) {
            log.error("SQLException during rollback transaction", se);
            sendSQLExceptionMetadata(se, responseObserver);
        } catch (Exception e) {
            log.error("Unexpected error during rollback transaction", e);
            sendSQLExceptionMetadata(new SQLException("Unable to rollback transaction: " + e.getMessage(), e), responseObserver);
        }
    }
}
