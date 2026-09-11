package org.openjproxy.jdbc;

import org.junit.jupiter.api.Test;

import java.sql.SQLTransientConnectionException;

import static org.junit.jupiter.api.Assertions.assertThrows;

class StatementThrottleTest {

    @Test
    void shouldThrowSqlTransientConnectionExceptionWhenClientThrottleRejectsRequest() throws Exception {
        TestStatement statement = new TestStatement();
        ClientThrottleManager throttleManager = new ClientThrottleManager();
        throttleManager.updateFromSessionInfo(com.openjproxy.grpc.SessionInfo.newBuilder()
                .setConnHash("test")
                .setClientCount(1)
                .setMaxAdmission(1)
                .build());

        // Fill the single available slot so the next acquire is rejected.
        throttleManager.tryAcquire(ClientThrottleMode.PROACTIVE, false);

        assertThrows(SQLTransientConnectionException.class,
                () -> statement.callAcquireThrottle(throttleManager, ClientThrottleMode.PROACTIVE, false));
    }

    private static class TestStatement extends Statement {
        TestStatement() {
            super(null, null);
        }

        boolean callAcquireThrottle(ClientThrottleManager throttle, ClientThrottleMode mode, boolean inTransaction)
                throws Exception {
            return acquireThrottle(throttle, mode, inTransaction);
        }
    }
}
