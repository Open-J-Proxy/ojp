package org.openjproxy.jdbc;

import com.openjproxy.grpc.DbName;
import com.openjproxy.grpc.OpQueryResultProto;
import com.openjproxy.grpc.OpResult;
import com.openjproxy.grpc.ResultType;
import com.openjproxy.grpc.SessionInfo;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Regression tests for {@code resetMoreResultsState()} on {@link PreparedStatement}, mirroring
 * {@link StatementMoreResultsStateTest}. See PR #585: once the server reports that
 * {@code getMoreResults()} is exhausted for the current execution, a later {@code executeQuery()}
 * or {@code executeUpdate()} on the same, reused {@link PreparedStatement} must reset that local
 * cache; otherwise it would incorrectly short-circuit {@code getMoreResults()} for the new
 * execution's results.
 */
class PreparedStatementMoreResultsStateTest {

    // SessionInfo with an empty connHash so Connection#getThrottleManager() returns null and
    // client-side throttling is bypassed entirely for these unit tests.
    private static final SessionInfo SESSION = SessionInfo.newBuilder().setConnHash("").build();

    @Test
    void shouldResetMoreResultsStateAfterExecuteQuery() throws Exception {
        FakeStatementService fakeService = new FakeStatementService(null, emptyQueryResult());
        PreparedStatement statement = new PreparedStatement(newConnection(fakeService), "SELECT 1", fakeService);
        exhaustMoreResults(statement, fakeService);

        statement.executeQuery();
        fakeService.setCallResourceReturnValue(false);
        statement.getMoreResults();

        assertEquals(2, countMoreResultsInvocations(fakeService));
    }

    @Test
    void shouldResetMoreResultsStateAfterExecuteUpdate() throws Exception {
        FakeStatementService fakeService = new FakeStatementService(updateResult(1), null);
        PreparedStatement statement = new PreparedStatement(newConnection(fakeService), "UPDATE t SET x = ?",
                fakeService);
        exhaustMoreResults(statement, fakeService);

        statement.executeUpdate();
        fakeService.setCallResourceReturnValue(false);
        statement.getMoreResults();

        assertEquals(2, countMoreResultsInvocations(fakeService));
    }

    private static void exhaustMoreResults(PreparedStatement statement, FakeStatementService fakeService)
            throws Exception {
        fakeService.setCallResourceReturnValue(false);
        assertFalse(statement.getMoreResults());
        assertEquals(1, countMoreResultsInvocations(fakeService));
    }

    private static long countMoreResultsInvocations(FakeStatementService fakeService) {
        return fakeService.getCallResourceInvocations().stream()
                .filter(request -> "MoreResults".equals(request.getTarget().getResourceName()))
                .count();
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
