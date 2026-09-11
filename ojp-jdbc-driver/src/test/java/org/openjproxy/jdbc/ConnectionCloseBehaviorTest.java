package org.openjproxy.jdbc;

import com.openjproxy.grpc.DbName;
import com.openjproxy.grpc.SessionInfo;
import org.junit.jupiter.api.Test;
import org.openjproxy.grpc.client.StatementService;

import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionCloseBehaviorTest {

    @Test
    void shouldTerminateSessionSynchronouslyByDefault() throws SQLException {
        CountDownLatch terminated = new CountDownLatch(1);
        StatementService statementService = createStatementService(session -> terminated.countDown());
        Connection connection = new Connection(buildSessionInfo(), statementService, DbName.POSTGRES);

        connection.close();

        assertTrue(connection.isClosed());
        // Default is synchronous: terminateSession must have been called before close() returned
        assertEquals(0, terminated.getCount());
    }

    @Test
    void shouldPropagateTerminateFailureWhenCloseIsDefaultSynchronous() {
        StatementService statementService = createStatementService(session -> {
            throw new SQLException("terminate failed");
        });
        Connection connection = new Connection(buildSessionInfo(), statementService, DbName.POSTGRES);

        assertThrows(SQLException.class, connection::close);
    }

    @Test
    void shouldNotPropagateTerminateFailureWhenCloseIsConfiguredAsynchronous() throws SQLException, InterruptedException {
        CountDownLatch terminated = new CountDownLatch(1);
        StatementService statementService = createStatementService(session -> {
            terminated.countDown();
            throw new SQLException("terminate failed");
        });
        Connection connection = new Connection(buildSessionInfo(), statementService, DbName.POSTGRES, false);

        assertDoesNotThrow(connection::close);
        assertTrue(connection.isClosed());
        assertTrue(terminated.await(2, TimeUnit.SECONDS));
    }

    @Test
    void shouldPropagateTerminateFailureWhenCloseIsConfiguredSynchronous() {
        StatementService statementService = createStatementService(session -> {
            throw new SQLException("terminate failed");
        });
        Connection connection = new Connection(buildSessionInfo(), statementService, DbName.POSTGRES, true);

        assertThrows(SQLException.class, connection::close);
    }

    private SessionInfo buildSessionInfo() {
        return SessionInfo.newBuilder()
                .setSessionUUID("session-1")
                .setConnHash("conn-hash")
                .build();
    }

    private StatementService createStatementService(TerminateSessionBehavior behavior) {
        AtomicInteger calls = new AtomicInteger();
        return (StatementService) Proxy.newProxyInstance(
                StatementService.class.getClassLoader(),
                new Class<?>[]{StatementService.class},
                (proxy, method, args) -> {
                    if ("terminateSession".equals(method.getName())) {
                        calls.incrementAndGet();
                        behavior.terminate((SessionInfo) args[0]);
                        return null;
                    }
                    if ("toString".equals(method.getName())) {
                        return "TestStatementService";
                    }
                    if ("hashCode".equals(method.getName())) {
                        return System.identityHashCode(proxy);
                    }
                    if ("equals".equals(method.getName())) {
                        return proxy == args[0];
                    }
                    throw new UnsupportedOperationException("Unexpected method call: " + method.getName()
                            + " calls=" + calls.get());
                });
    }

    @FunctionalInterface
    private interface TerminateSessionBehavior {
        void terminate(SessionInfo sessionInfo) throws SQLException;
    }
}
