package org.openjproxy.grpc.server.pool;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConnectionPoolWarmupTest {

    @Test
    void warmsMinimumConnectionsConcurrentlyBeforeReturning() throws Exception {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenAnswer(invocation -> {
            int current = active.incrementAndGet();
            peak.accumulateAndGet(current, Math::max);
            Connection connection = mock(Connection.class);
            when(connection.isClosed()).thenReturn(false);
            doAnswer(close -> {
                active.decrementAndGet();
                return null;
            }).when(connection).close();
            return connection;
        });

        ConnectionPoolWarmup.warmUp(dataSource, 4, 1000);

        assertEquals(4, peak.get(), "minimumIdle == maximumPoolSize should be warmed in parallel");
        assertEquals(0, active.get(), "warm-up connections should be returned before publication");
    }

    @Test
    void failsClearlyWhenPoolCannotCreateWarmupConnections() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("database unavailable"));

        SQLException error = assertThrows(SQLException.class,
                () -> ConnectionPoolWarmup.warmUp(dataSource, 2, 1000));

        assertTrue(error.getMessage().contains("Failed to warm up"));
        assertTrue(error.getCause().getMessage().contains("database unavailable"));
    }

    @Test
    void warmupUsesOneAcquisitionDeadline() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenAnswer(invocation -> {
            TimeUnit.MILLISECONDS.sleep(500);
            throw new SQLException("slow database");
        });

        long start = System.nanoTime();
        assertThrows(SQLException.class, () -> ConnectionPoolWarmup.warmUp(dataSource, 1, 100));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue(elapsedMs < 400, "warm-up should honor its shared timeout instead of waiting for driver completion");
    }
}
