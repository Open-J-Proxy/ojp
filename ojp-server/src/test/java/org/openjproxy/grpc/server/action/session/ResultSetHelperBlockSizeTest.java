package org.openjproxy.grpc.server.action.session;

import com.openjproxy.grpc.OpResult;
import com.openjproxy.grpc.SessionInfo;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.openjproxy.grpc.ProtoConverter;
import org.openjproxy.grpc.dto.OpQueryResult;
import org.openjproxy.grpc.server.ServerConfiguration;
import org.openjproxy.grpc.server.SessionManager;
import org.openjproxy.grpc.server.action.ActionContext;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ResultSetHelper#handleResultSet} verifying that the
 * configured {@code ojp.resultset.rowsPerBlock} value controls the streaming
 * block size and that all rows are delivered regardless of block size.
 */
class ResultSetHelperBlockSizeTest {

    private static final String SESSION_UUID = "test-session-uuid";
    private static final String RS_UUID = "test-rs-uuid";
    private static final String POSTGRES_URL = "jdbc:postgresql://localhost:5432/testdb";

    @Mock
    private SessionManager sessionManager;

    @Mock
    private StreamObserver<OpResult> responseObserver;

    private AutoCloseable mocks;

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
    }

    @AfterEach
    void tearDown() throws Exception {
        System.clearProperty("ojp.resultset.rowsPerBlock");
        mocks.close();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Builds a minimal ActionContext backed by a real {@link ServerConfiguration}
     * so that {@code getResultsetRowsPerBlock()} reflects any system property
     * set before the call.
     */
    private ActionContext buildActionContext() {
        ServerConfiguration serverConfiguration = new ServerConfiguration();
        return new ActionContext(
                new java.util.concurrent.ConcurrentHashMap<>(),
                new java.util.concurrent.ConcurrentHashMap<>(),
                new java.util.concurrent.ConcurrentHashMap<>(),
                new java.util.concurrent.ConcurrentHashMap<>(),
                new java.util.concurrent.ConcurrentHashMap<>(),
                new java.util.concurrent.ConcurrentHashMap<>(),
                new java.util.concurrent.ConcurrentHashMap<>(),
                null,
                null,
                null,
                null,
                sessionManager,
                null,
                serverConfiguration,
                null,
                null);
    }

    private SessionInfo buildSession() {
        return SessionInfo.newBuilder()
                .setSessionUUID(SESSION_UUID)
                .setConnHash("conn-hash")
                .setClientUUID("client-uuid")
                .build();
    }

    /**
     * Creates a mock JDBC {@link java.sql.ResultSet} backed by {@code rowCount}
     * single-column integer rows.
     */
    private java.sql.ResultSet buildMockResultSet(int rowCount) throws SQLException {
        java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
        ResultSetMetaData meta = mock(ResultSetMetaData.class);
        Statement stmt = mock(Statement.class);
        Connection conn = mock(Connection.class);
        DatabaseMetaData dbMeta = mock(DatabaseMetaData.class);

        when(rs.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnName(1)).thenReturn("VALUE");
        when(meta.getColumnType(1)).thenReturn(Types.INTEGER);
        when(meta.getColumnTypeName(1)).thenReturn("INTEGER");

        when(rs.getStatement()).thenReturn(stmt);
        when(stmt.getConnection()).thenReturn(conn);
        when(conn.getMetaData()).thenReturn(dbMeta);
        when(dbMeta.getURL()).thenReturn(POSTGRES_URL);

        // Use a counter-based answer so next() correctly simulates rowCount rows then stops
        AtomicInteger callCount = new AtomicInteger(0);
        when(rs.next()).thenAnswer(inv -> callCount.getAndIncrement() < rowCount);

        when(rs.getObject(1)).thenReturn(42);

        return rs;
    }

    /**
     * Counts the total number of rows across all {@code onNext} calls captured
     * on the response observer.
     */
    private int countTotalRowsDelivered(List<OpResult> capturedBlocks) {
        int total = 0;
        for (OpResult opResult : capturedBlocks) {
            OpQueryResult qr = ProtoConverter.fromProto(opResult.getQueryResult());
            total += qr.getRows().size();
        }
        return total;
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    void shouldUseDefaultBlockSizeOf100() throws SQLException {
        // With default config (100 rows/block) and exactly 100 rows we expect 1 block sent before completed.
        int rowCount = 100;
        java.sql.ResultSet rs = buildMockResultSet(rowCount);
        when(sessionManager.getResultSet(buildSession(), RS_UUID)).thenReturn(rs);

        ActionContext context = buildActionContext();
        assertEquals(100, context.getServerConfiguration().getResultsetRowsPerBlock());
        ResultSetHelper.handleResultSet(context, buildSession(), RS_UUID, responseObserver);

        verify(responseObserver, atLeastOnce()).onNext(org.mockito.ArgumentMatchers.any());
        verify(responseObserver).onCompleted();

        ArgumentCaptor<OpResult> captor = ArgumentCaptor.forClass(OpResult.class);
        verify(responseObserver, atLeastOnce()).onNext(captor.capture());
        assertEquals(rowCount, countTotalRowsDelivered(captor.getAllValues()));
    }

    @Test
    void shouldSendAllRowsWhenBlockSizeSmallerThanResultSize() throws SQLException {
        int rowCount = 50;
        System.setProperty("ojp.resultset.rowsPerBlock", "10");

        java.sql.ResultSet rs = buildMockResultSet(rowCount);
        when(sessionManager.getResultSet(buildSession(), RS_UUID)).thenReturn(rs);

        ActionContext context = buildActionContext();
        assertEquals(10, context.getServerConfiguration().getResultsetRowsPerBlock());
        ResultSetHelper.handleResultSet(context, buildSession(), RS_UUID, responseObserver);

        ArgumentCaptor<OpResult> captor = ArgumentCaptor.forClass(OpResult.class);
        verify(responseObserver, atLeastOnce()).onNext(captor.capture());
        verify(responseObserver).onCompleted();

        List<OpResult> blocks = captor.getAllValues();
        // 50 rows with block size 10 → 5 full blocks (each triggers a send)
        assertEquals(5, blocks.size());
        assertEquals(rowCount, countTotalRowsDelivered(blocks));
    }

    @Test
    void shouldSendAllRowsWhenBlockSizeEqualsResultSize() throws SQLException {
        int rowCount = 25;
        System.setProperty("ojp.resultset.rowsPerBlock", "25");

        java.sql.ResultSet rs = buildMockResultSet(rowCount);
        when(sessionManager.getResultSet(buildSession(), RS_UUID)).thenReturn(rs);

        ActionContext context = buildActionContext();
        ResultSetHelper.handleResultSet(context, buildSession(), RS_UUID, responseObserver);

        ArgumentCaptor<OpResult> captor = ArgumentCaptor.forClass(OpResult.class);
        verify(responseObserver, atLeastOnce()).onNext(captor.capture());
        verify(responseObserver).onCompleted();

        assertEquals(rowCount, countTotalRowsDelivered(captor.getAllValues()));
    }

    @Test
    void shouldSendAllRowsWhenBlockSizeLargerThanResultSize() throws SQLException {
        int rowCount = 5;
        System.setProperty("ojp.resultset.rowsPerBlock", "500");

        java.sql.ResultSet rs = buildMockResultSet(rowCount);
        when(sessionManager.getResultSet(buildSession(), RS_UUID)).thenReturn(rs);

        ActionContext context = buildActionContext();
        assertEquals(500, context.getServerConfiguration().getResultsetRowsPerBlock());
        ResultSetHelper.handleResultSet(context, buildSession(), RS_UUID, responseObserver);

        ArgumentCaptor<OpResult> captor = ArgumentCaptor.forClass(OpResult.class);
        verify(responseObserver, atLeastOnce()).onNext(captor.capture());
        verify(responseObserver).onCompleted();

        // Only one block: all rows fit in a single send
        assertEquals(1, captor.getAllValues().size());
        assertEquals(rowCount, countTotalRowsDelivered(captor.getAllValues()));
    }

    @Test
    void shouldSendAllRowsWhenRowCountIsNotMultipleOfBlockSize() throws SQLException {
        int rowCount = 55;
        System.setProperty("ojp.resultset.rowsPerBlock", "10");

        java.sql.ResultSet rs = buildMockResultSet(rowCount);
        when(sessionManager.getResultSet(buildSession(), RS_UUID)).thenReturn(rs);

        ActionContext context = buildActionContext();
        ResultSetHelper.handleResultSet(context, buildSession(), RS_UUID, responseObserver);

        ArgumentCaptor<OpResult> captor = ArgumentCaptor.forClass(OpResult.class);
        verify(responseObserver, atLeastOnce()).onNext(captor.capture());
        verify(responseObserver).onCompleted();

        List<OpResult> blocks = captor.getAllValues();
        // 5 full blocks of 10 + 1 remainder block of 5 = 6 blocks
        assertEquals(6, blocks.size());
        assertEquals(rowCount, countTotalRowsDelivered(blocks));
    }

    @Test
    void shouldHandleEmptyResultSet() throws SQLException {
        System.setProperty("ojp.resultset.rowsPerBlock", "10");

        java.sql.ResultSet rs = buildMockResultSet(0);
        when(sessionManager.getResultSet(buildSession(), RS_UUID)).thenReturn(rs);

        ActionContext context = buildActionContext();
        ResultSetHelper.handleResultSet(context, buildSession(), RS_UUID, responseObserver);

        // Even for empty result set, one block (empty) must be sent before onCompleted
        ArgumentCaptor<OpResult> captor = ArgumentCaptor.forClass(OpResult.class);
        verify(responseObserver, atLeastOnce()).onNext(captor.capture());
        verify(responseObserver).onCompleted();

        assertEquals(0, countTotalRowsDelivered(captor.getAllValues()));
    }

    @Test
    void shouldSendAllRowsWithBlockSizeOf1() throws SQLException {
        int rowCount = 3;
        System.setProperty("ojp.resultset.rowsPerBlock", "1");

        java.sql.ResultSet rs = buildMockResultSet(rowCount);
        when(sessionManager.getResultSet(buildSession(), RS_UUID)).thenReturn(rs);

        ActionContext context = buildActionContext();
        assertEquals(1, context.getServerConfiguration().getResultsetRowsPerBlock());
        ResultSetHelper.handleResultSet(context, buildSession(), RS_UUID, responseObserver);

        ArgumentCaptor<OpResult> captor = ArgumentCaptor.forClass(OpResult.class);
        verify(responseObserver, atLeastOnce()).onNext(captor.capture());
        verify(responseObserver).onCompleted();

        List<OpResult> blocks = captor.getAllValues();
        assertEquals(rowCount, blocks.size());
        assertEquals(rowCount, countTotalRowsDelivered(blocks));
        // Every block must contain exactly 1 row
        for (OpResult block : blocks) {
            OpQueryResult qr = ProtoConverter.fromProto(block.getQueryResult());
            assertEquals(1, qr.getRows().size());
        }
    }

    @Test
    void shouldSendLabelsOnlyInFirstBlock() throws SQLException {
        int rowCount = 15;
        System.setProperty("ojp.resultset.rowsPerBlock", "5");

        java.sql.ResultSet rs = buildMockResultSet(rowCount);
        when(sessionManager.getResultSet(buildSession(), RS_UUID)).thenReturn(rs);

        ActionContext context = buildActionContext();
        ResultSetHelper.handleResultSet(context, buildSession(), RS_UUID, responseObserver);

        ArgumentCaptor<OpResult> captor = ArgumentCaptor.forClass(OpResult.class);
        verify(responseObserver, atLeastOnce()).onNext(captor.capture());

        List<OpResult> blocks = captor.getAllValues();
        assertEquals(3, blocks.size());

        // First block carries column labels
        OpQueryResult firstBlock = ProtoConverter.fromProto(blocks.get(0).getQueryResult());
        assertNotNull(firstBlock.getLabels());
        assertFalse(firstBlock.getLabels().isEmpty());

        // Subsequent blocks do not carry labels (builder is recreated each block)
        for (int i = 1; i < blocks.size(); i++) {
            OpQueryResult subsequentBlock = ProtoConverter.fromProto(blocks.get(i).getQueryResult());
            assertFalse(subsequentBlock.getLabels() != null && !subsequentBlock.getLabels().isEmpty(),
                    "Block " + i + " should not carry labels");
        }
    }
}
