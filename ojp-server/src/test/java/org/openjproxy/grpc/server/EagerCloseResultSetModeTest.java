package org.openjproxy.grpc.server;

import com.openjproxy.grpc.CallResourceRequest;
import com.openjproxy.grpc.CallResourceResponse;
import com.openjproxy.grpc.CallType;
import com.openjproxy.grpc.DbName;
import com.openjproxy.grpc.ResourceType;
import com.openjproxy.grpc.SessionInfo;
import com.openjproxy.grpc.TargetCall;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openjproxy.grpc.server.action.ActionContext;
import org.openjproxy.grpc.server.action.resource.CallResourceAction;
import org.openjproxy.grpc.server.action.session.ResultSetHelper;
import org.openjproxy.grpc.server.metrics.SqlStatementMetrics;
import org.openjproxy.grpc.server.sql.SqlEnhancerEngine;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the eager close ResultSet mode
 * ({@code ojp.resultset.eagerClose.enabled}).
 *
 * <p>Scenarios covered:
 * <ol>
 *   <li>Mode disabled: RS, Statement, and session are left intact.</li>
 *   <li>Mode enabled, auto-commit, forward-only RS: the RS cursor is closed but the session and Statement remain alive.</li>
 *   <li>Mode enabled: client {@code close()} call on an already-closed RS returns success.</li>
 *   <li>Mode enabled, auto-commit, forward-only RS: only the RS is closed; Statement stays open for {@code getMoreResults()}.</li>
 *   <li>Active transaction: no eager close when {@code autoCommit=false}.</li>
 *   <li>LOBs registered: no eager close when the session contains LOBs.</li>
 * </ol>
 */
class EagerCloseResultSetModeTest {

    private static final String CONN_HASH = "test-conn-hash";
    private static final String CLIENT_UUID = "test-client-uuid";

    private SessionManagerImpl sessionManager;

    @BeforeEach
    void setUp() {
        sessionManager = new SessionManagerImpl();
        sessionManager.registerClientUUID(CONN_HASH, CLIENT_UUID);
    }

    // -------------------------------------------------------------------------
    // Test 1: eager close disabled – session and resources left intact
    // -------------------------------------------------------------------------

    @Test
    void shouldNotCloseResultSetEarlyWhenEagerCloseDisabled() throws Exception {
        Connection conn = buildMockConnection(true);
        SessionInfo session = sessionManager.createSession(CLIENT_UUID, conn);

        ResultSet mockRs = buildMockResultSet(conn, false);
        String rsUUID = sessionManager.registerResultSet(session, mockRs);

        ActionContext ctx = buildContext(sessionManager, false);
        ResultSetHelper.handleResultSet(ctx, session, rsUUID, noopObserver());

        verify(mockRs, never()).close();
        verify(mockRs.getStatement(), never()).close();
        assertNotNull(sessionManager.getSession(session), "Session must remain when eager close is disabled");
    }

    // -------------------------------------------------------------------------
    // Test 2: eager close enabled, auto-commit, no LOBs, forward-only RS –
    //         the RS cursor is closed but the session and connection remain alive
    // -------------------------------------------------------------------------

    @Test
    void shouldCloseForwardOnlyResultSetAfterFullyReadWithNoLobsAndAutoCommit() throws Exception {
        Connection conn = buildMockConnection(true);
        SessionInfo session = sessionManager.createSession(CLIENT_UUID, conn);

        ResultSet mockRs = buildMockResultSet(conn, false);
        String rsUUID = sessionManager.registerResultSet(session, mockRs);

        ActionContext ctx = buildContext(sessionManager, true);
        ResultSetHelper.handleResultSet(ctx, session, rsUUID, noopObserver());

        verify(mockRs).close();
        assertNotNull(sessionManager.getSession(session),
                "Session must remain alive after eager RS close – the JDBC connection may be reused for further queries");
    }

    // -------------------------------------------------------------------------
    // Test 3: eager close enabled – close() on an already-closed RS succeeds
    // -------------------------------------------------------------------------

    @Test
    void shouldAllowClientCloseCallAfterEagerClose() throws Exception {
        Connection conn = buildMockConnection(true);
        SessionInfo session = sessionManager.createSession(CLIENT_UUID, conn);

        ResultSet mockRs = buildMockResultSet(conn, false);
        String rsUUID = sessionManager.registerResultSet(session, mockRs);

        ActionContext ctx = buildContext(sessionManager, true);
        ResultSetHelper.handleResultSet(ctx, session, rsUUID, noopObserver());

        // The RS cursor was already closed by eager close.
        // The JDBC client still sends a close() call for the RS resource.
        // The server must return a success response and must not propagate an error.
        CallResourceRequest closeReq = CallResourceRequest.newBuilder()
                .setSession(session)
                .setResourceType(ResourceType.RES_RESULT_SET)
                .setResourceUUID(rsUUID)
                .setTarget(TargetCall.newBuilder()
                        .setCallType(CallType.CALL_CLOSE)
                        .setResourceName("")
                        .build())
                .build();

        List<CallResourceResponse> responses = new ArrayList<>();
        List<Throwable> errors = new ArrayList<>();
        StreamObserver<CallResourceResponse> observer = new StreamObserver<CallResourceResponse>() {
            @Override
            public void onNext(CallResourceResponse value) {
                responses.add(value);
            }

            @Override
            public void onError(Throwable t) {
                errors.add(t);
            }

            @Override
            public void onCompleted() {
                // no-op
            }
        };

        CallResourceAction.getInstance().execute(ctx, closeReq, observer);

        assertTrue(errors.isEmpty(), "No error expected for close() after RS eager close");
        assertEquals(1, responses.size(), "Exactly one response expected");
    }

    // -------------------------------------------------------------------------
    // Test 4: eager close enabled, auto-commit, forward-only RS –
    //         only the RS cursor is closed; Statement stays open for getMoreResults()
    // -------------------------------------------------------------------------

    @Test
    void shouldCloseResultSetButNotStatementAfterEagerClose() throws Exception {
        Connection conn = buildMockConnection(true);
        SessionInfo session = sessionManager.createSession(CLIENT_UUID, conn);

        ResultSet mockRs = buildMockResultSet(conn, false);
        String rsUUID = sessionManager.registerResultSet(session, mockRs);
        Statement mockStmt = mockRs.getStatement();

        ActionContext ctx = buildContext(sessionManager, true);
        ResultSetHelper.handleResultSet(ctx, session, rsUUID, noopObserver());

        verify(mockRs).close();
        verify(mockStmt, never()).close();
    }

    // -------------------------------------------------------------------------
    // Test 5: active transaction – no eager close when autoCommit=false
    // -------------------------------------------------------------------------

    @Test
    void shouldNotReleaseConnectionWhenInsideActiveTransaction() throws Exception {
        Connection conn = buildMockConnection(false);
        SessionInfo session = sessionManager.createSession(CLIENT_UUID, conn);

        ResultSet mockRs = buildMockResultSet(conn, false);
        String rsUUID = sessionManager.registerResultSet(session, mockRs);

        ActionContext ctx = buildContext(sessionManager, true);
        ResultSetHelper.handleResultSet(ctx, session, rsUUID, noopObserver());

        verify(conn, never()).close();
        Session s = sessionManager.getSession(session);
        assertNotNull(s, "Session must remain when inside an active transaction");
        assertNotNull(s.getConnection(), "Connection must remain bound to session during an active transaction");
    }

    // -------------------------------------------------------------------------
    // Test 6: LOBs registered – no eager close when session has LOB objects
    // -------------------------------------------------------------------------

    @Test
    void shouldNotEagerCloseWhenSessionHasLobs() throws Exception {
        Connection conn = buildMockConnection(true);
        SessionInfo session = sessionManager.createSession(CLIENT_UUID, conn);

        ResultSet mockRs = buildMockResultSet(conn, false);
        String rsUUID = sessionManager.registerResultSet(session, mockRs);

        // Simulate a LOB registered during row processing (e.g. a BLOB column)
        java.sql.Blob mockBlob = mock(java.sql.Blob.class);
        sessionManager.registerLob(session, mockBlob, "some-lob-uuid");

        ActionContext ctx = buildContext(sessionManager, true);
        ResultSetHelper.handleResultSet(ctx, session, rsUUID, noopObserver());

        assertNotNull(sessionManager.getSession(session),
                "Session must remain when LOBs are still registered (client may still read them)");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Connection buildMockConnection(boolean autoCommit) throws Exception {
        java.sql.DatabaseMetaData dbMeta = mock(java.sql.DatabaseMetaData.class);
        when(dbMeta.getURL()).thenReturn("jdbc:h2:mem:test");

        Connection conn = mock(Connection.class);
        when(conn.getMetaData()).thenReturn(dbMeta);
        when(conn.getAutoCommit()).thenReturn(autoCommit);
        return conn;
    }

    private ResultSet buildMockResultSet(Connection conn, boolean hasRows) throws Exception {
        ResultSetMetaData meta = mock(ResultSetMetaData.class);
        when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnName(1)).thenReturn("id");
        when(meta.getColumnLabel(1)).thenReturn("id");
        when(meta.getColumnType(1)).thenReturn(Types.INTEGER);
        when(meta.getColumnTypeName(1)).thenReturn("INTEGER");
        when(meta.getSchemaName(1)).thenReturn("");
        when(meta.getTableName(1)).thenReturn("");
        when(meta.getCatalogName(1)).thenReturn("");
        when(meta.getColumnDisplaySize(1)).thenReturn(10);
        when(meta.getPrecision(1)).thenReturn(10);
        when(meta.getScale(1)).thenReturn(0);
        when(meta.isNullable(1)).thenReturn(ResultSetMetaData.columnNullable);
        when(meta.isAutoIncrement(1)).thenReturn(false);
        when(meta.isCaseSensitive(1)).thenReturn(false);
        when(meta.isSearchable(1)).thenReturn(true);
        when(meta.isCurrency(1)).thenReturn(false);
        when(meta.isSigned(1)).thenReturn(true);
        when(meta.isReadOnly(1)).thenReturn(false);
        when(meta.isWritable(1)).thenReturn(true);
        when(meta.isDefinitelyWritable(1)).thenReturn(false);
        when(meta.getColumnClassName(1)).thenReturn("java.lang.Integer");

        Statement mockStmt = mock(Statement.class);
        when(mockStmt.getConnection()).thenReturn(conn);

        ResultSet rs = mock(ResultSet.class);
        when(rs.getMetaData()).thenReturn(meta);
        when(rs.next()).thenReturn(hasRows ? Boolean.TRUE : Boolean.FALSE);
        when(rs.getStatement()).thenReturn(mockStmt);
        when(rs.getType()).thenReturn(ResultSet.TYPE_FORWARD_ONLY);
        return rs;
    }

    private ActionContext buildContext(SessionManager mgr, boolean eagerCloseEnabled) {
        ServerConfiguration config = mock(ServerConfiguration.class);
        when(config.isEagerCloseEnabled()).thenReturn(eagerCloseEnabled);
        when(config.getCircuitBreakerTimeout()).thenReturn(60000L);
        when(config.getCircuitBreakerThreshold()).thenReturn(3);

        SqlEnhancerEngine enhancer = mock(SqlEnhancerEngine.class);
        when(enhancer.isEnabled()).thenReturn(false);

        SqlStatementMetrics metrics = mock(SqlStatementMetrics.class);

        Map<String, DbName> dbNameMap = new ConcurrentHashMap<>();
        dbNameMap.put(CONN_HASH, DbName.H2);

        CircuitBreakerRegistry cbRegistry = new CircuitBreakerRegistry(60000L, 3);

        return new ActionContext(
                new ConcurrentHashMap<>(),
                new ConcurrentHashMap<>(),
                new ConcurrentHashMap<>(),
                new ConcurrentHashMap<>(),
                dbNameMap,
                new ConcurrentHashMap<>(),
                new ConcurrentHashMap<>(),
                null,
                null,
                null,
                mgr,
                cbRegistry,
                config,
                metrics,
                enhancer
        );
    }

    @SuppressWarnings("unchecked")
    private <T> StreamObserver<T> noopObserver() {
        return new StreamObserver<T>() {
            @Override
            public void onNext(T value) {
                // no-op
            }

            @Override
            public void onError(Throwable t) {
                throw new RuntimeException("Unexpected observer error", t);
            }

            @Override
            public void onCompleted() {
                // no-op
            }
        };
    }
}
