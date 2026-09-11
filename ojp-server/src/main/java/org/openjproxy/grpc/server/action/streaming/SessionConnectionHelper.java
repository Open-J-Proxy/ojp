package org.openjproxy.grpc.server.action.streaming;

import com.openjproxy.grpc.SessionInfo;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.database.DatabaseUtils;
import org.openjproxy.grpc.server.AdmissionControlManager;
import org.openjproxy.grpc.server.ConnectionAcquisitionManager;
import org.openjproxy.grpc.server.ConnectionSessionDTO;
import org.openjproxy.grpc.server.PoolNotFoundException;
import org.openjproxy.grpc.server.Session;
import org.openjproxy.grpc.server.UnpooledConnectionDetails;
import org.openjproxy.grpc.server.action.ActionContext;

import javax.sql.DataSource;
import javax.sql.XAConnection;
import javax.sql.XADataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Utility class for managing database connections within gRPC streaming
 * sessions.
 * <p>
 * This helper implements lazy connection allocation, allowing connections to be
 * created
 * on-demand rather than eagerly during session initialization. It supports
 * multiple
 * connection modes:
 * </p>
 * <ul>
 * <li><b>Pooled connections:</b> Acquires connections from connection pools
 * (e.g., HikariCP)</li>
 * <li><b>Unpooled connections:</b> Creates direct connections via JDBC
 * DriverManager</li>
 * <li><b>XA connections:</b> Supports distributed transactions with both pooled
 * and unpooled modes</li>
 * </ul>
 * <p>
 * The class follows a reuse-first strategy: if a session already has an active
 * connection,
 * it will be reused. Otherwise, a new connection is allocated based on the
 * session's
 * configuration (XA vs regular, pooled vs unpooled).
 * </p>
 * <p>
 * <b>Connection Lifecycle:</b>
 * </p>
 * <ul>
 * <li>Existing sessions with valid UUIDs will reuse their stored
 * connections</li>
 * <li>New sessions trigger lazy allocation based on connection hash and XA
 * flag</li>
 * <li>Connections are validated (checked for closed state) before reuse</li>
 * <li>XA connections store the underlying XAConnection object for transaction
 * management</li>
 * </ul>
 * <p>
 * <b>Thread Safety:</b> This class provides static utility methods that operate
 * on
 * thread-local session managers and context objects. The thread safety depends
 * on the
 * underlying session manager and connection pool implementations.
 * </p>
 *
 * @author OpenJProxy
 * @since 1.0
 */
@Slf4j
public class SessionConnectionHelper {

    /**
     * Private constructor
     */
    private SessionConnectionHelper() {
    }

    /**
     * Finds a suitable connection for the current sessionInfo.
     * If there is a connection already in the sessionInfo reuse it, if not get a
     * fresh one from the data source.
     * This method implements lazy connection allocation for both Hikari and
     * Atomikos XA datasources.
     *
     * @param context          the action context containing the session manager
     * @param sessionInfo        - current sessionInfo object.
     * @param startSessionIfNone - if true will start a new sessionInfo if none
     *                           exists.
     * @return ConnectionSessionDTO
     * @throws SQLException if connection not found or closed (by timeout or other
     *                      reason)
     */
    public static ConnectionSessionDTO sessionConnection(ActionContext context, SessionInfo sessionInfo,
                                                         boolean startSessionIfNone)
            throws SQLException {
        return sessionConnection(context, sessionInfo, startSessionIfNone, null);
    }

    /**
     * Same as {@link #sessionConnection(ActionContext, SessionInfo, boolean)} but
     * allows the caller to supply a {@code replicaDataSource} that overrides the
     * pooled-mode datasource lookup.  When {@code replicaDataSource} is non-{@code null}
     * and the session has no existing UUID (i.e. stateless / auto-commit mode), the
     * connection is acquired from {@code replicaDataSource} instead of the primary pool.
     * XA, unpooled, and existing-session paths are not affected by this parameter.
     *
     * @param context            the action context containing the session manager
     * @param sessionInfo        current sessionInfo object
     * @param startSessionIfNone if {@code true} a new session will be started when none exists
     * @param replicaDataSource  optional datasource to use in place of the primary pool;
     *                           {@code null} means "use the primary pool as normal"
     * @return ConnectionSessionDTO
     * @throws SQLException if a connection cannot be obtained
     */
    public static ConnectionSessionDTO sessionConnection(ActionContext context, SessionInfo sessionInfo,
                                                         boolean startSessionIfNone,
                                                         javax.sql.DataSource replicaDataSource)
            throws SQLException {
        ConnectionSessionDTO.ConnectionSessionDTOBuilder dtoBuilder = ConnectionSessionDTO.builder();
        dtoBuilder.session(sessionInfo);
        Connection conn = null;
        var sessionManager = context.getSessionManager();

        if (!sessionInfo.getSessionUUID().isEmpty()) {
            if (replicaDataSource != null) {
                // Replica-routed request on an existing session: do not trigger lazy primary
                // acquisition here.  ExecuteQueryAction will call
                // session.getOrCreateReplicaConnection() to get the replica connection.
                // conn remains null — caller is responsible for obtaining the right connection.
            } else {
                // Primary path: get the connection from the existing session (may lazily acquire)
                conn = sessionManager.getConnection(sessionInfo);
                if (conn == null) {
                    throw new SQLException("Connection not found for this sessionInfo");
                }
                dtoBuilder.dbName(DatabaseUtils.resolveDbName(conn.getMetaData().getURL()));
                if (conn.isClosed()) {
                    throw new SQLException("Connection is closed");
                }
            }
        } else {
            // Lazy allocation: check if this is an XA or regular connection
            String connHash = sessionInfo.getConnHash();
            boolean isXA = sessionInfo.getIsXA();

            if (isXA) {
                // XA connection - check if unpooled or pooled mode
                XADataSource xaDataSource = context.getXaDataSourceMap().get(connHash);

                if (xaDataSource != null) {
                    // Unpooled XA mode: create XAConnection on demand
                    try {
                        log.debug("Creating unpooled XAConnection for hash: {}", connHash);
                        XAConnection xaConnection = xaDataSource.getXAConnection();
                        conn = xaConnection.getConnection();

                        // Store the XAConnection in session for XA operations
                        if (startSessionIfNone) {
                            SessionInfo updatedSession = sessionManager.createSession(sessionInfo.getClientUUID(),
                                    conn);
                            // Store XAConnection as an attribute for XA operations
                            sessionManager.registerAttr(updatedSession, "xaConnection", xaConnection);
                            dtoBuilder.session(updatedSession);
                        }
                        log.debug("Successfully created unpooled XAConnection for hash: {}", connHash);
                    } catch (SQLException e) {
                        log.error("Failed to create unpooled XAConnection for hash: {}. Error: {}",
                                connHash, e.getMessage());
                        throw e;
                    }
                } else {
                    // Pooled XA mode - should already have a session created in connect()
                    // This shouldn't happen as XA sessions are created eagerly
                    throw new SQLException("XA session should already exist. Session UUID is missing.");
                }
            } else {
                // Regular connection - check if pooled or unpooled mode
                UnpooledConnectionDetails unpooledDetails = context.getUnpooledConnectionDetailsMap().get(connHash);
                boolean sessionCreated = false;

                if (unpooledDetails != null) {
                    // Unpooled mode: create direct connection without pooling
                    try {
                        log.debug("Creating unpooled (passthrough) connection for hash: {}", connHash);
                        conn = java.sql.DriverManager.getConnection(
                                unpooledDetails.getUrl(),
                                unpooledDetails.getUsername(),
                                unpooledDetails.getPassword());
                        log.debug("Successfully created unpooled connection for hash: {}", connHash);
                    } catch (SQLException e) {
                        log.error("Failed to create unpooled connection for hash: {}. Error: {}",
                                connHash, e.getMessage());
                        throw e;
                    }
                } else {
                    // Pooled mode: create a lazy dual-datasource session when a replica is
                    // provided so that the primary connection is only acquired if actually
                    // needed (e.g. for a subsequent write on the same session).
                    DataSource primaryDataSource = context.getDatasourceMap().get(connHash);

                    if (primaryDataSource == null) {
                        // Signal the client to reconnect. NOT_FOUND is caught by
                        // CommandExecutionHelper and translated to Status.NOT_FOUND so that the
                        // driver can transparently reconnect and retry the SQL call.
                        throw new PoolNotFoundException(connHash);
                    }

                    if (replicaDataSource != null) {
                        // Replica path: create a lazy session with both datasources.
                        // No connection is acquired here; the caller (ExecuteQueryAction) will
                        // obtain the replica connection via session.getOrCreateReplicaConnection().
                        if (startSessionIfNone) {
                            SessionInfo updatedSession = sessionManager.createSession(
                                    sessionInfo.getClientUUID(), sessionInfo.getConnHash(),
                                    primaryDataSource, replicaDataSource);
                            dtoBuilder.session(updatedSession);
                            sessionCreated = true;
                        }
                        // conn remains null — caller must resolve the connection from the session
                    } else {
                        // Primary path: eager acquisition with admission control
                        AdmissionControlManager.SessionPermit sessionPermit = null;
                        try {
                            AdmissionControlManager admissionControlManager =
                                    context.getAdmissionControlManagers().get(connHash);
                            if (startSessionIfNone && admissionControlManager != null) {
                                sessionPermit = admissionControlManager.claimCurrentThreadPermitForSession();
                                if (sessionPermit == null) {
                                    sessionPermit = admissionControlManager.acquireSessionPermit(connHash);
                                }
                            }

                            // Use enhanced connection acquisition with timeout protection
                            conn = ConnectionAcquisitionManager.acquireConnection(primaryDataSource, connHash);
                            log.debug("Successfully acquired connection from pool for hash: {}", connHash);

                            if (startSessionIfNone) {
                                SessionInfo updatedSession = sessionManager.createSession(
                                        sessionInfo.getClientUUID(), sessionInfo.getConnHash(), conn);
                                if (sessionPermit != null) {
                                    Session createdSession = sessionManager.getSession(updatedSession);
                                    if (createdSession != null) {
                                        AdmissionControlManager.SessionPermit permitForHook = sessionPermit;
                                        createdSession.setConnectionPermitReleaseHook(permitForHook::release);
                                        sessionPermit = null;
                                    } else {
                                        sessionPermit.release();
                                        sessionPermit = null;
                                    }
                                }
                                dtoBuilder.session(updatedSession);
                                sessionCreated = true;
                            }
                        } catch (SQLException e) {
                            if (sessionPermit != null) {
                                sessionPermit.release();
                            }
                            log.error("Failed to acquire connection from pool for hash: {}. Error: {}",
                                    connHash, e.getMessage());

                            // Re-throw the enhanced exception from ConnectionAcquisitionManager
                            throw e;
                        }
                    }
                }

                if (startSessionIfNone && !sessionCreated) {
                    SessionInfo updatedSession = sessionManager.createSession(sessionInfo.getClientUUID(), conn);
                    dtoBuilder.session(updatedSession);
                }
            }
        }
        dtoBuilder.connection(conn);

        return dtoBuilder.build();
    }
}
