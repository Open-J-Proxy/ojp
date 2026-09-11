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
 * Regression tests for the {@code lastUpdateCount} JDBC contract on {@link Statement}.
 *
 * <p>Per the JDBC spec, {@link java.sql.Statement#getUpdateCount()} must return {@code -1}
 * when the last executed statement produced a {@link java.sql.ResultSet} (or no statement has
 * been executed yet), and the actual affected-row count otherwise. Returning {@code 0} instead
 * of {@code -1} after a query makes clients that check {@code getUpdateCount() == -1} to detect
 * "no more results" (e.g. DataGrip) believe an update result is still pending, causing them to
 * hang indefinitely. See PR #580.</p>
 */
class StatementLastUpdateCountTest {

    // SessionInfo with an empty connHash so Connection#getThrottleManager() returns null and
    // client-side throttling is bypassed entirely for these unit tests.
    private static final SessionInfo SESSION = SessionInfo.newBuilder().setConnHash("").build();

    @Test
    void shouldReturnMinusOneByDefaultBeforeAnyStatementIsExecuted() throws Exception {
        Statement statement = new Statement(newConnection(new FakeStatementService()), new FakeStatementService());

        assertEquals(-1, statement.getUpdateCount());
    }

    @Test
    void shouldReturnMinusOneAfterExecuteQuery() throws Exception {
        FakeStatementService fakeService = new FakeStatementService(null, emptyQueryResult());
        Connection connection = newConnection(fakeService);
        Statement statement = new Statement(connection, fakeService);

        statement.executeQuery("SELECT 1");

        assertEquals(-1, statement.getUpdateCount());
    }

    @Test
    void shouldReturnAffectedRowsFromExecuteUpdate() throws Exception {
        // executeUpdate(sql) returns the affected-row count directly; it does not touch
        // lastUpdateCount (only execute(sql)/executeQuery(sql) do), so getUpdateCount() is
        // intentionally not asserted here.
        FakeStatementService fakeService = new FakeStatementService(updateResult(5), null);
        Connection connection = newConnection(fakeService);
        Statement statement = new Statement(connection, fakeService);

        int affectedRows = statement.executeUpdate("UPDATE t SET x = 1");

        assertEquals(5, affectedRows);
    }

    @Test
    void shouldResetToMinusOneWhenExecuteQueryFollowsAnUpdate() throws Exception {
        FakeStatementService fakeService = new FakeStatementService(updateResult(7), emptyQueryResult());
        Connection connection = newConnection(fakeService);
        Statement statement = new Statement(connection, fakeService);

        statement.execute("UPDATE t SET x = 1");
        assertEquals(7, statement.getUpdateCount());

        statement.executeQuery("SELECT 1");

        assertEquals(-1, statement.getUpdateCount());
    }

    @Test
    void shouldReturnMinusOneWhenExecuteDelegatesToASelectStatement() throws Exception {
        FakeStatementService fakeService = new FakeStatementService(null, emptyQueryResult());
        Connection connection = newConnection(fakeService);
        Statement statement = new Statement(connection, fakeService);

        boolean isResultSet = statement.execute("SELECT 1");

        assertEquals(true, isResultSet);
        assertEquals(-1, statement.getUpdateCount());
    }

    @Test
    void shouldReturnAffectedRowsWhenExecuteDelegatesToAnUpdateStatement() throws Exception {
        FakeStatementService fakeService = new FakeStatementService(updateResult(3), null);
        Connection connection = newConnection(fakeService);
        Statement statement = new Statement(connection, fakeService);

        boolean isResultSet = statement.execute("UPDATE t SET x = 1");

        assertEquals(false, isResultSet);
        assertEquals(3, statement.getUpdateCount());
    }

    @Test
    void shouldTreatCallStatementsAsUpdateShapedByDefault() throws Exception {
        FakeStatementService fakeService = new FakeStatementService(updateResult(0), null);
        Connection connection = newConnection(fakeService);
        Statement statement = new Statement(connection, fakeService);

        boolean isResultSet = statement.execute("CALL no_result_proc()");

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
