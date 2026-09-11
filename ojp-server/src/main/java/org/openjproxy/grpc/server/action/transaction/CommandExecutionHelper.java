package org.openjproxy.grpc.server.action.transaction;

import com.openjproxy.grpc.OpResult;
import com.openjproxy.grpc.SqlErrorType;
import com.openjproxy.grpc.StatementRequest;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.grpc.server.CircuitBreaker;
import org.openjproxy.grpc.server.PoolNotFoundException;
import org.openjproxy.grpc.server.AdmissionControlManager;
import org.openjproxy.grpc.server.ServerOverloadException;
import org.openjproxy.grpc.server.SqlStatementXXHash;
import org.openjproxy.grpc.server.action.ActionContext;
import org.openjproxy.grpc.server.action.util.ProcessClusterHealthAction;

import java.sql.SQLDataException;
import java.sql.SQLException;

import static org.openjproxy.grpc.server.GrpcExceptionHandler.sendServerOverload;
import static org.openjproxy.grpc.server.GrpcExceptionHandler.sendSQLExceptionMetadata;
import static org.openjproxy.grpc.server.action.session.ResultSetHelper.updateSessionActivity;

@Slf4j
public class CommandExecutionHelper {

    /**
     * Helper method to centralize session validation, activity updates, cluster
     * health processing, circuit breaker checks, and admission control for
     * statement execution. This resolves SonarQube duplication issues.
     *
     * @param context          the action context
     * @param request          the statement request
     * @param responseObserver the response observer for error reporting
     * @param executionLogic   the logic to execute (e.g., update or query)
     */
    public static void executeWithResilience(ActionContext context, StatementRequest request, StreamObserver<OpResult> responseObserver,
                                       StatementExecution executionLogic, SqlErrorType sqlDataExceptionType, String operationName) {

        // Ensure session isn't null
        if (request.getSession().getConnHash().isBlank()) {
            sendSQLExceptionMetadata(new SQLException("Invalid request: Session or ConnHash is missing"), responseObserver);
            log.error("Invalid {} request: Session or ConnHash is missing", operationName);
            return;
        }

        // Update session activity
        updateSessionActivity(context, request.getSession());

        String stmtHash = SqlStatementXXHash.hashSqlQuery(request.getSql());
        // Process cluster health from the request
        ProcessClusterHealthAction.getInstance().execute(context, request.getSession());


        String connHash = request.getSession().getConnHash();
        CircuitBreaker circuitBreaker = context.getCircuitBreakerRegistry().get(connHash);

        // Get the appropriate admission control manager for this datasource
        AdmissionControlManager manager = getAdmissionControlManagerForConnection(context, connHash);

        // If the session already owns a session-scoped admission permit (acquired at
        // session creation and released only on session termination), do not acquire
        // another per-statement slot — that would double-count the same session and
        // unnecessarily compete for capacity with brand new sessions.
        final boolean sessionHoldsPermit = sessionHoldsPermit(context, request);

        long sqlStartNs = System.nanoTime();
        try {
            circuitBreaker.preCheck(stmtHash);

            // Execute with admission control, passing actual SQL for metric labelling
            if (sessionHoldsPermit) {
                manager.executeWithMonitoringOnly(stmtHash, request.getSql(), () -> {
                    executionLogic.execute();
                    return null;
                });
            } else {
                manager.executeWithSegregation(stmtHash, request.getSql(), () -> {
                    executionLogic.execute();
                    return null;
                });
            }

            circuitBreaker.onSuccess(stmtHash);

        } catch (SQLDataException e) {
            circuitBreaker.onFailure(stmtHash, e);
            log.error("SQL data failure during {} execution: {}",
                    operationName, e.getMessage(), e);
            SqlErrorType type = sqlDataExceptionType != null
                    ? sqlDataExceptionType
                    : SqlErrorType.SQL_EXCEPTION;

            sendSQLExceptionMetadata(e, responseObserver, type);

        } catch (SQLException e) {
            circuitBreaker.onFailure(stmtHash, e);
            log.error("SQL failure during {} execution: {}",
                    operationName, e.getMessage(), e);
            sendSQLExceptionMetadata(e, responseObserver);
        } catch (ServerOverloadException e) {
            log.warn("Server overload during {} execution, request rejected: {}", operationName, e.getMessage());
            sendServerOverload(e, responseObserver);
        } catch (PoolNotFoundException e) {
            // Pool was not found for this connection hash. The server may have restarted
            // and lost its in-memory pool state. Signal the client to reconnect via
            // Status.NOT_FOUND so that the driver can transparently redo connect() and
            // retry the SQL call without surfacing an error to the application.
            log.warn("Pool not found during {} execution, signalling client to reconnect: {}",
                    operationName, e.getMessage());
            responseObserver.onError(Status.NOT_FOUND
                    .withDescription(e.getMessage())
                    .asRuntimeException());
        } catch (Exception e) {
            log.error("Unexpected failure during {} execution: {}",
                    operationName, e.getMessage(), e);
            if (e.getCause() instanceof SQLException sqlException) {
                circuitBreaker.onFailure(stmtHash, sqlException);
                sendSQLExceptionMetadata(sqlException, responseObserver);
            } else {
                SQLException sqlException = new SQLException("Unexpected error: " + e.getMessage(), e);
                circuitBreaker.onFailure(stmtHash, sqlException);
                sendSQLExceptionMetadata(sqlException, responseObserver);
            }
        } finally {
            // Record SQL execution time for all connections (XA and non-XA) regardless of
            // manager state. This is the single authoritative place for SQL metrics.
            String sql = request.getSql();
            if (!sql.isEmpty()) {
                long executionTimeMs = (System.nanoTime() - sqlStartNs) / 1_000_000L;
                context.getSqlStatementMetrics().recordSqlExecution(
                        sql, executionTimeMs, manager.isSlowOperation(stmtHash));
            }
        }
    }


    /**
     * Gets the admission control manager for a specific connection hash.
     * If no manager exists, creates a disabled one as a fallback.
     *
     * @param context  the action context with segregation managers
     * @param connHash the connection hash to look up
     * @return the admission control manager for the connection
     */
    private static AdmissionControlManager getAdmissionControlManagerForConnection(ActionContext context,
                                                                                           String connHash) {
        var admissionControlManagers = context.getAdmissionControlManagers();

        AdmissionControlManager manager = admissionControlManagers.get(connHash);
        if (manager == null) {
            log.warn("No AdmissionControlManager found for connection hash {}, creating disabled fallback",
                    connHash);
            manager = new AdmissionControlManager(1, 0, 0, 0, 0, 0, false);
            admissionControlManagers.put(connHash, manager);
        }
        return manager;
    }

    /**
     * Functional interface for statement execution logic, used by
     * {@link #executeWithResilience} to wrap the actual update/query execution.
     */
    @SuppressWarnings("java:S112")
    @FunctionalInterface
    public interface StatementExecution {
        /**
         * Executes the statement logic.
         */
        void execute() throws Exception;
    }

    /**
     * Returns true if the session referenced by {@code request} already owns a
     * session-scoped admission permit. Returns false if the session does not yet
     * exist (lazy creation during this very request), is XA/unpooled, or admission
     * control is disabled — in all of those cases the normal per-statement
     * acquisition path remains appropriate.
     */
    static boolean sessionHoldsPermit(ActionContext context, StatementRequest request) {
        try {
            if (request.getSession().getSessionUUID().isBlank()) {
                return false;
            }
            org.openjproxy.grpc.server.Session session =
                    context.getSessionManager().getSession(request.getSession());
            return session != null && session.hasConnectionPermit();
        } catch (Exception e) {
            // Defensive: never block statement execution on a lookup error.
            log.debug("Failed to check session permit ownership, falling back to per-statement slot", e);
            return false;
        }
    }
}
