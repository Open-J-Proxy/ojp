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
 * Action to commit a transaction.
 * Extracts logic from StatementServiceImpl.commitTransaction.
 */
@Slf4j
public class CommitTransactionAction implements Action<SessionInfo, SessionInfo> {

    private static final CommitTransactionAction INSTANCE = new CommitTransactionAction();

    private CommitTransactionAction() {
    }

    public static CommitTransactionAction getInstance() {
        return INSTANCE;
    }

    /**
     * Execute the action to commit a transaction.
     *
     * @param context          The action context containing shared state
     * @param sessionInfo      Session info
     * @param responseObserver Response observer
     */
    @Override
    public void execute(ActionContext context, SessionInfo sessionInfo, StreamObserver<SessionInfo> responseObserver) {
        log.debug("Committing transaction");

        // Process cluster health from the request
        ProcessClusterHealthAction.getInstance().execute(context, sessionInfo);

        try {
            Connection conn = context.getSessionManager().getConnection(sessionInfo);
            conn.commit();

            TransactionInfo transactionInfo = TransactionInfo.newBuilder()
                    .setTransactionStatus(TransactionStatus.TRX_COMMITED)
                    .setTransactionUUID(sessionInfo.getTransactionInfo().getTransactionUUID())
                    .build();

            SessionInfo.Builder sessionInfoBuilder = SessionInfoUtils.newBuilderFrom(sessionInfo, context);
            sessionInfoBuilder.setTransactionInfo(transactionInfo);

            responseObserver.onNext(sessionInfoBuilder.build());
            responseObserver.onCompleted();
        } catch (SQLException se) {
            sendSQLExceptionMetadata(se, responseObserver);
        } catch (Exception e) {
            log.error("Error in commitTransaction action", e);
            sendSQLExceptionMetadata(new SQLException("Unable to commit transaction: " + e.getMessage()), responseObserver);
        }
    }
}
