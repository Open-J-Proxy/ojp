package org.openjproxy.grpc.server.action.transaction;

import io.grpc.Context;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StatementCancellation}, which cancels the running JDBC statement when
 * the gRPC call is cancelled by the client or its deadline expires.
 */
class StatementCancellationTest {

    @Test
    void cancelsStatementWhenCallIsCancelled() throws Exception {
        Statement statement = mock(Statement.class);
        Context.CancellableContext call = Context.current().withCancellation();
        Context previous = call.attach();
        try (StatementCancellation ignored = StatementCancellation.onCallCancelled(statement)) {
            call.cancel(null);
            verify(statement).cancel();
        } finally {
            call.detach(previous);
        }
    }

    @Test
    void cancelsImmediatelyWhenCallIsAlreadyCancelled() throws Exception {
        Statement statement = mock(Statement.class);
        Context.CancellableContext call = Context.current().withCancellation();
        call.cancel(null);
        Context previous = call.attach();
        try (StatementCancellation ignored = StatementCancellation.onCallCancelled(statement)) {
            verify(statement).cancel();
        } finally {
            call.detach(previous);
        }
    }

    @Test
    void doesNotCancelAfterExecutionFinished() throws Exception {
        Statement statement = mock(Statement.class);
        Context.CancellableContext call = Context.current().withCancellation();
        Context previous = call.attach();
        try {
            StatementCancellation.onCallCancelled(statement).close();
            call.cancel(null);
            verify(statement, never()).cancel();
        } finally {
            call.detach(previous);
        }
    }

    @Test
    void cancelsWhenDeadlineExpires() throws Exception {
        Statement statement = mock(Statement.class);
        CountDownLatch cancelled = new CountDownLatch(1);
        doAnswer(invocation -> {
            cancelled.countDown();
            return null;
        }).when(statement).cancel();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            Context.CancellableContext call = Context.current()
                    .withDeadlineAfter(50, TimeUnit.MILLISECONDS, scheduler);
            Context previous = call.attach();
            try (StatementCancellation ignored = StatementCancellation.onCallCancelled(statement)) {
                assertTrue(cancelled.await(5, TimeUnit.SECONDS), "Statement was not cancelled on deadline");
            } finally {
                call.detach(previous);
                call.cancel(null);
            }
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void ignoresClosedStatementsAndCancelFailures() throws Exception {
        Statement closed = mock(Statement.class);
        when(closed.isClosed()).thenReturn(true);
        Statement failing = mock(Statement.class);
        doThrow(new SQLException("cancel not supported")).when(failing).cancel();
        Context.CancellableContext call = Context.current().withCancellation();
        Context previous = call.attach();
        try (StatementCancellation first = StatementCancellation.onCallCancelled(closed);
             StatementCancellation second = StatementCancellation.onCallCancelled(failing)) {
            assertDoesNotThrow(() -> call.cancel(null));
            verify(closed, never()).cancel();
            verify(failing).cancel();
        } finally {
            call.detach(previous);
        }
    }

    @Test
    void abortsBlockedExecutionWhenCallIsCancelled() throws Exception {
        // Simulates a driver whose execute blocks until Statement.cancel() is called from another thread.
        CountDownLatch cancelled = new CountDownLatch(1);
        Statement statement = mock(Statement.class);
        doAnswer(invocation -> {
            cancelled.countDown();
            return null;
        }).when(statement).cancel();
        when(statement.executeQuery("SELECT slow")).thenAnswer(invocation -> {
            if (cancelled.await(10, TimeUnit.SECONDS)) {
                throw new SQLException("Statement was canceled", "HY008");
            }
            return null;
        });
        Context.CancellableContext call = Context.current().withCancellation();
        CompletableFuture<Void> canceller = CompletableFuture.runAsync(() -> {
            sleepQuietly(300);
            call.cancel(null);
        });
        Context previous = call.attach();
        long start = System.nanoTime();
        try (StatementCancellation ignored = StatementCancellation.onCallCancelled(statement)) {
            SQLException error = assertThrows(SQLException.class, () -> statement.executeQuery("SELECT slow"));
            assertTrue("HY008".equals(error.getSQLState()), "Unexpected SQLState: " + error.getSQLState());
        } finally {
            call.detach(previous);
        }
        canceller.join();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(elapsedMs < 5_000, "Execution was not aborted promptly: " + elapsedMs + " ms");
    }
    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
