package org.openjproxy.jdbc;

import com.openjproxy.grpc.CallResourceRequest;
import com.openjproxy.grpc.CallType;
import com.openjproxy.grpc.DbName;
import com.openjproxy.grpc.OpQueryResultProto;
import com.openjproxy.grpc.OpResult;
import com.openjproxy.grpc.ResultType;
import com.openjproxy.grpc.SessionInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ResultSet#getFetchDirection()}, {@link ResultSet#setFetchDirection(int)},
 * {@link ResultSet#getFetchSize()} and {@link ResultSet#setFetchSize(int)}.
 * <p>
 * These JDBC hints must be handled locally (without forcing the ResultSet into proxy mode) as long
 * as the direction stays {@code FETCH_FORWARD}, since that is the only direction compatible with
 * this ResultSet's forward-only, block-streaming model. Any other direction (or a ResultSet already
 * in proxy mode) must delegate to the remote proxy.
 */
class ResultSetFetchDirectionSizeTest {

    private static final SessionInfo SESSION = SessionInfo.newBuilder().setConnHash("test-conn-hash").build();

    private FakeStatementService fakeStatementService;
    private Connection connection;
    private Statement statement;

    @BeforeEach
    void setUp() {
        this.fakeStatementService = new FakeStatementService();
        this.connection = new Connection(SESSION, this.fakeStatementService, DbName.H2);
        this.statement = new Statement(this.connection, this.fakeStatementService);
    }

    private ResultSet newResultSet() throws SQLException {
        OpResult singleEmptyBlock = OpResult.newBuilder()
                .setSession(SESSION)
                .setType(ResultType.RESULT_SET_DATA)
                .setQueryResult(OpQueryResultProto.newBuilder().setResultSetUUID("rs-uuid").build())
                .build();
        Iterator<OpResult> it = Collections.singletonList(singleEmptyBlock).iterator();
        ResultSet resultSet = new ResultSet(it, this.fakeStatementService, this.statement);
        resultSet.setConnection(this.connection);
        return resultSet;
    }

    @Test
    void shouldReturnFetchForwardByDefaultWithoutProxyCall() throws SQLException {
        ResultSet resultSet = newResultSet();

        assertEquals(java.sql.ResultSet.FETCH_FORWARD, resultSet.getFetchDirection());
        assertTrue(this.fakeStatementService.getCallResourceInvocations().isEmpty());
    }

    @Test
    void shouldStoreFetchForwardHintLocallyWithoutForcingProxyMode() throws SQLException {
        ResultSet resultSet = newResultSet();

        resultSet.setFetchDirection(java.sql.ResultSet.FETCH_FORWARD);

        assertEquals(java.sql.ResultSet.FETCH_FORWARD, resultSet.getFetchDirection());
        assertTrue(this.fakeStatementService.getCallResourceInvocations().isEmpty());
    }

    @Test
    void shouldDelegateSetFetchDirectionToProxyForNonForwardDirection() throws SQLException {
        ResultSet resultSet = newResultSet();

        resultSet.setFetchDirection(java.sql.ResultSet.FETCH_REVERSE);

        List<CallResourceRequest> invocations = this.fakeStatementService.getCallResourceInvocations();
        assertEquals(1, invocations.size());
        assertEquals(CallType.CALL_SET, invocations.get(0).getTarget().getCallType());
        assertEquals("FetchDirection", invocations.get(0).getTarget().getResourceName());
    }

    @Test
    void shouldDelegateGetFetchDirectionToProxyAfterNonForwardDirectionForcedProxyMode() throws SQLException {
        ResultSet resultSet = newResultSet();
        resultSet.setFetchDirection(java.sql.ResultSet.FETCH_REVERSE);
        this.fakeStatementService.setCallResourceReturnValue(java.sql.ResultSet.FETCH_REVERSE);

        int direction = resultSet.getFetchDirection();

        assertEquals(java.sql.ResultSet.FETCH_REVERSE, direction);
        List<CallResourceRequest> invocations = this.fakeStatementService.getCallResourceInvocations();
        assertEquals(2, invocations.size());
        assertEquals(CallType.CALL_GET, invocations.get(1).getTarget().getCallType());
        assertEquals("FetchDirection", invocations.get(1).getTarget().getResourceName());
    }

    @Test
    void shouldReturnZeroFetchSizeByDefaultWithoutProxyCall() throws SQLException {
        ResultSet resultSet = newResultSet();

        assertEquals(0, resultSet.getFetchSize());
        assertTrue(this.fakeStatementService.getCallResourceInvocations().isEmpty());
    }

    @Test
    void shouldStoreFetchSizeHintLocallyWithoutProxyCall() throws SQLException {
        ResultSet resultSet = newResultSet();

        resultSet.setFetchSize(25);

        assertEquals(25, resultSet.getFetchSize());
        assertTrue(this.fakeStatementService.getCallResourceInvocations().isEmpty());
    }

    @Test
    void shouldThrowSQLExceptionForNegativeFetchSize() throws SQLException {
        ResultSet resultSet = newResultSet();

        assertThrows(SQLException.class, () -> resultSet.setFetchSize(-1));
        assertTrue(this.fakeStatementService.getCallResourceInvocations().isEmpty());
    }

    @Test
    void shouldDelegateFetchSizeToProxyWhenAlreadyInProxyMode() throws SQLException {
        ResultSet resultSet = newResultSet();
        // Force proxy mode via a non-forward fetch direction.
        resultSet.setFetchDirection(java.sql.ResultSet.FETCH_REVERSE);

        resultSet.setFetchSize(50);
        this.fakeStatementService.setCallResourceReturnValue(50);
        int fetchSize = resultSet.getFetchSize();

        assertEquals(50, fetchSize);
        List<CallResourceRequest> invocations = this.fakeStatementService.getCallResourceInvocations();
        // [0] = setFetchDirection, [1] = setFetchSize, [2] = getFetchSize
        assertEquals(3, invocations.size());
        assertEquals("FetchSize", invocations.get(1).getTarget().getResourceName());
        assertEquals(CallType.CALL_SET, invocations.get(1).getTarget().getCallType());
        assertEquals("FetchSize", invocations.get(2).getTarget().getResourceName());
        assertEquals(CallType.CALL_GET, invocations.get(2).getTarget().getCallType());
    }
}
