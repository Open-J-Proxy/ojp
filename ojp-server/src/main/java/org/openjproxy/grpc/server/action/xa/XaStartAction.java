package org.openjproxy.grpc.server.action.xa;

import static org.openjproxy.grpc.server.GrpcExceptionHandler.sendSQLExceptionMetadata;
import static org.openjproxy.grpc.server.action.transaction.XidHelper.convertXid;

import java.sql.SQLException;

import org.openjproxy.grpc.server.Session;
import org.openjproxy.grpc.server.action.Action;
import org.openjproxy.grpc.server.action.ActionContext;
import org.openjproxy.grpc.server.action.util.ProcessClusterHealthAction;
import org.openjproxy.xa.pool.XABackendSession;
import org.openjproxy.xa.pool.XATransactionRegistry;
import org.openjproxy.xa.pool.XidKey;

import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;

/**
 * Action to handle XA transaction start logic.
 */
@Slf4j
public class XaStartAction
        implements Action<com.openjproxy.grpc.XaStartRequest, com.openjproxy.grpc.XaResponse> {

    private static final XaStartAction INSTANCE = new XaStartAction();

    private XaStartAction() {
        // Private constructor prevents external instantiation
    }

    public static XaStartAction getInstance() {
        return INSTANCE;
    }

    @Override
    public void execute(ActionContext context, com.openjproxy.grpc.XaStartRequest request,
            StreamObserver<com.openjproxy.grpc.XaResponse> responseObserver) {
        log.debug("xaStart: session={}, xid={}, flags={}",
                request.getSession().getSessionUUID(), request.getXid(), request.getFlags());

        // Process cluster health changes before XA operation
        ProcessClusterHealthAction.getInstance().execute(context, request.getSession());

        try {
            Session session = context.getSessionManager().getSession(request.getSession());
            if (session == null || !session.isXA()) {
                throw new SQLException("Session is not an XA session");
            }

            synchronized (session) {
                if (context.getSessionManager().getSession(request.getSession()) != session) {
                    throw new SQLException("Session is closed");
                }
                // Branch based on XA pooling configuration
                if (context.getXaPoolProvider() != null) {
                    // **NEW PATH: Use XATransactionRegistry**
                    handleXAStartWithPooling(context, request, session, responseObserver);
                } else {
                    // **OLD PATH: Pass-through (legacy)**
                    handleXAStartPassThrough(context, request, session, responseObserver);
                }
            }

        } catch (Exception e) {
            log.error("Error in xaStart", e);

            // Provide additional context for Oracle XA errors
            String errorMsg = e.getMessage();
            if (errorMsg != null && errorMsg.contains("ORA-")) {
                if (errorMsg.contains("ORA-6550") || errorMsg.contains("ORA-24756") || errorMsg.contains("ORA-24757")) {
                    log.error("Oracle XA Error: The database user may not have required XA privileges. " +
                            "All of the following must be granted: " +
                            "GRANT SELECT ON sys.dba_pending_transactions TO user; " +
                            "GRANT SELECT ON sys.pending_trans$ TO user; " +
                            "GRANT SELECT ON sys.dba_2pc_pending TO user; " +
                            "GRANT EXECUTE ON sys.dbms_system TO user; " +
                            "GRANT FORCE ANY TRANSACTION TO user; " +
                            "Or for Oracle 12c+: GRANT XA_RECOVER_ADMIN TO user; " +
                            "See ojp-server/ORACLE_XA_SETUP.md for details.");
                }
            }

            SQLException sqlException = (e instanceof SQLException) ? (SQLException) e : new SQLException(e);
            sendSQLExceptionMetadata(sqlException, responseObserver);
        }
    }

    private void handleXAStartWithPooling(ActionContext context, com.openjproxy.grpc.XaStartRequest request,
            Session session,
            StreamObserver<com.openjproxy.grpc.XaResponse> responseObserver) throws Exception {
        String connHash = session.getSessionInfo().getConnHash();
        XATransactionRegistry registry = context.getXaRegistries().get(connHash);
        if (registry == null) {
            throw new SQLException("No XA registry found for connection hash: " + connHash);
        }

        // Convert proto Xid to XidKey
        XidKey xidKey = XidKey.from(convertXid(request.getXid()));
        int flags = request.getFlags();
        String ojpSessionId = session.getSessionInfo().getSessionUUID();

        // Route based on XA flags
        if (flags == javax.transaction.xa.XAResource.TMNOFLAGS) {
            // New transaction: use existing session from OJP Session
            XABackendSession backendSession = (XABackendSession) session.getBackendSession();
            if (backendSession == null) {
                throw new SQLException("No XABackendSession found in session");
            }
            registry.registerExistingSession(xidKey, backendSession, flags, ojpSessionId,
                    () -> session.setXaTransactionStarted(true));

        } else if (flags == javax.transaction.xa.XAResource.TMJOIN ||
                flags == javax.transaction.xa.XAResource.TMRESUME) {
            // Join or resume existing transaction: delegate to xaStart
            // This requires the context to exist (from previous TMNOFLAGS start)
            registry.xaStart(xidKey, flags, ojpSessionId);

        } else {
            throw new SQLException("Unsupported XA flags: " + flags);
        }

        com.openjproxy.grpc.XaResponse response = com.openjproxy.grpc.XaResponse.newBuilder()
                .setSession(session.getSessionInfo())
                .setSuccess(true)
                .setMessage("XA start successful (pooled)")
                .build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    private void handleXAStartPassThrough(ActionContext context, com.openjproxy.grpc.XaStartRequest request,
            Session session,
            StreamObserver<com.openjproxy.grpc.XaResponse> responseObserver) throws Exception {
        if (session.getXaResource() == null) {
            throw new SQLException("Session does not have XAResource");
        }

        javax.transaction.xa.Xid xid = convertXid(request.getXid());
        session.getXaResource().start(xid, request.getFlags());

        com.openjproxy.grpc.XaResponse response = com.openjproxy.grpc.XaResponse.newBuilder()
                .setSession(session.getSessionInfo())
                .setSuccess(true)
                .setMessage("XA start successful")
                .build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}
