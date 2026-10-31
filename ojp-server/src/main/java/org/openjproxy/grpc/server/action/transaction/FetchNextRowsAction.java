package org.openjproxy.grpc.server.action.transaction;

import com.openjproxy.grpc.OpResult;
import com.openjproxy.grpc.ResultSetFetchRequest;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.grpc.server.ConnectionSessionDTO;
import org.openjproxy.grpc.server.action.Action;
import org.openjproxy.grpc.server.action.ActionContext;
import org.openjproxy.grpc.server.action.util.ProcessClusterHealthAction;

import java.sql.SQLException;

import static org.openjproxy.grpc.server.GrpcExceptionHandler.sendSQLExceptionMetadata;
import static org.openjproxy.grpc.server.action.session.ResultSetHelper.handleResultSet;
import static org.openjproxy.grpc.server.action.session.ResultSetHelper.updateSessionActivity;
import static org.openjproxy.grpc.server.action.streaming.SessionConnectionHelper.sessionConnection;

/**
 * Action to fetch the next batch of rows from a server-side result set.
 * <p>
 * This action resolves the session connection, updates session activity, processes cluster health
 * from the request, and streams the next rows from the result set identified by
 * {@link ResultSetFetchRequest#getResultSetUUID()} to the client. Any {@link java.sql.SQLException}
 * encountered during the operation is sent to the client via the response observer.
 * <p>
 * This action is implemented as a singleton for thread-safety and memory efficiency.
 *
 * @see Action
 * @see ResultSetFetchRequest
 * @see OpResult
 */
@Slf4j
@SuppressWarnings("java:S6548")
public class FetchNextRowsAction implements Action<ResultSetFetchRequest, OpResult> {

    private static final FetchNextRowsAction INSTANCE = new FetchNextRowsAction();

    /**
     * Private constructor prevents external instantiation.
     */
    private FetchNextRowsAction() {
        // Private constructor for singleton pattern
    }

    /**
     * Returns the singleton instance of FetchNextRowsAction.
     *
     * @return the singleton instance
     */
    public static FetchNextRowsAction getInstance() {
        return INSTANCE;
    }

    /**
     * Executes the fetch next rows operation for the specified result set.
     * <p>
     * Updates session activity, processes cluster health, resolves the session connection,
     * and streams the next rows to the client. SQL exceptions are sent as metadata
     * via the response observer.
     *
     * @param context          the action context containing shared state and services
     * @param request          the result set fetch request containing session and result set UUID
     * @param responseObserver the response observer for streaming the result
     */
    @Override
    public void execute(ActionContext context, ResultSetFetchRequest request, StreamObserver<OpResult> responseObserver) {
        log.debug("Executing fetch next rows for result set  {}", request.getResultSetUUID());

        // Update session activity
        updateSessionActivity(context, request.getSession());

        // Process cluster health from the request
        ProcessClusterHealthAction.getInstance().execute(context, request.getSession());

        try {
            ConnectionSessionDTO dto = sessionConnection(context, request.getSession(), false);
            handleResultSet(context, dto.getSession(), request.getResultSetUUID(), responseObserver,
                    context.getServerConfiguration().getResultsetRowsPerBlock());
        } catch (SQLException e) {
            log.error("Failure fetch next rows for result set: {}", e.getMessage(), e);
            sendSQLExceptionMetadata(e, responseObserver);
        }
    }
}
