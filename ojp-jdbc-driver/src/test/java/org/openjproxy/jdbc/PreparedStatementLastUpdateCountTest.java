package org.openjproxy.jdbc;

import com.openjproxy.grpc.DbName;
import com.openjproxy.grpc.OpQueryResultProto;
import com.openjproxy.grpc.OpResult;
import com.openjproxy.grpc.ResultType;
import com.openjproxy.grpc.SessionInfo;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression tests for the {@code lastUpdateCount} JDBC contract on {@link PreparedStatement}.
 *
 * <p>Mirrors {@link StatementLastUpdateCountTest}: {@link java.sql.PreparedStatement#executeQuery()}
 * must leave {@code getUpdateCount()} at {@code -1}, otherwise clients that check
 * {@code getUpdateCount() == -1} to detect "no more results" (e.g. DataGrip) believe an update
 * result is still pending and hang indefinitely. See PR #580.</p>
 */
class PreparedStatementLastUpdateCountTest {

    // SessionInfo with an empty connHash so Connection#getThrottleManager() returns null and
    // client-side throttling is bypassed entirely for these unit tests.
    private static final SessionInfo SESSION = SessionInfo.newBuilder().setConnHash("").build();

    @Test
    void shouldReturnMinusOneByDefaultBeforeAnyStatementIsExecuted() throws Exception {
        FakeStatementService fakeService = new FakeStatementService();
        PreparedStatement statement = new PreparedStatement(newConnection(fakeService), "SELECT 1", fakeService);

        assertEquals(-1, statement.getUpdateCount());
    }

    @Test
    void shouldReturnMinusOneAfterExecuteQuery() throws Exception {
        FakeStatementService fakeService = new FakeStatementService(null, emptyQueryResult());
        Connection connection = newConnection(fakeService);
        PreparedStatement statement = new PreparedStatement(connection, "SELECT 1", fakeService);

        statement.executeQuery();

        assertEquals(-1, statement.getUpdateCount());
    }

    @Test
    void shouldReturnAffectedRowsFromExecuteUpdate() throws Exception {
        // executeUpdate() returns the affected-row count directly; it does not touch
        // lastUpdateCount (only execute()/executeQuery() do), so getUpdateCount() is
        // intentionally not asserted here.
        FakeStatementService fakeService = new FakeStatementService(updateResult(5), null);
        Connection connection = newConnection(fakeService);
        PreparedStatement statement = new PreparedStatement(connection, "UPDATE t SET x = ?", fakeService);

        int affectedRows = statement.executeUpdate();

        assertEquals(5, affectedRows);
    }

    @Test
    void shouldResetToMinusOneWhenExecuteQueryFollowsAnUpdate() throws Exception {
        FakeStatementService fakeService = new FakeStatementService(null, emptyQueryResult());
        Connection connection = newConnection(fakeService);
        PreparedStatement statement = new PreparedStatement(connection, "SELECT 1", fakeService);
        // Simulate the exact regression scenario from PR #580: the statement previously produced
        // a valid (>=0) update count (e.g. reused after an UPDATE), then executeQuery() must reset
        // lastUpdateCount back to -1 rather than leaving the stale value in place.
        statement.lastUpdateCount = 7;

        statement.executeQuery();

        assertEquals(-1, statement.getUpdateCount());
    }

    @Test
    void shouldReturnMinusOneWhenExecuteDelegatesToASelectStatement() throws Exception {
        FakeStatementService fakeService = new FakeStatementService(null, emptyQueryResult());
        Connection connection = newConnection(fakeService);
        PreparedStatement statement = new PreparedStatement(connection, "SELECT 1", fakeService);

        boolean isResultSet = statement.execute();

        assertEquals(true, isResultSet);
        assertEquals(-1, statement.getUpdateCount());
    }

    @Test
    void shouldReturnAffectedRowsWhenExecuteDelegatesToAnUpdateStatement() throws Exception {
        FakeStatementService fakeService = new FakeStatementService(updateResult(3), null);
        Connection connection = newConnection(fakeService);
        PreparedStatement statement = new PreparedStatement(connection, "UPDATE t SET x = ?", fakeService);

        boolean isResultSet = statement.execute();

        assertEquals(false, isResultSet);
        assertEquals(3, statement.getUpdateCount());
    }

    @Test
    void shouldTreatCallStatementsAsUpdateShapedByDefault() throws Exception {
        FakeStatementService fakeService = new FakeStatementService(updateResult(0), null);
        Connection connection = newConnection(fakeService);
        PreparedStatement statement = new PreparedStatement(connection, "CALL no_result_proc()", fakeService);

        boolean isResultSet = statement.execute();

        assertEquals(false, isResultSet);
        assertEquals(0, statement.getUpdateCount());
    }

    private static Connection newConnection(FakeStatementService fakeService) {
        return new Connection(SESSION, fakeService, DbName.H2);
    }

    private static OpResult updateResult(int affectedRows) {
        return OpResult.newBuilder()
                .setSession(SESSION)
                .setType(ResultType.INTEGER)
                .setIntValue(affectedRows)
                .build();
    }

    private static java.util.Iterator<OpResult> emptyQueryResult() {
        OpResult result = OpResult.newBuilder()
                .setSession(SESSION)
                .setType(ResultType.RESULT_SET_DATA)
                .setQueryResult(OpQueryResultProto.newBuilder().build())
                .build();
        return Collections.singletonList(result).iterator();
    }
}
