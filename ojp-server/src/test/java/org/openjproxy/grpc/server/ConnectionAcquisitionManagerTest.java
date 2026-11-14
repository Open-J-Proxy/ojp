package org.openjproxy.grpc.server;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConnectionAcquisitionManagerTest {

    @Test
    void shouldRetryCapacityTimeoutWhilePoolCanGrow() throws Exception {
        HikariDataSource dataSource = createHikariDataSource(2, 1, 0);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection())
                .thenThrow(new SQLTransientConnectionException(
                        "Connection is not available, request timed out after 250ms."))
                .thenReturn(connection);

        Connection acquired;
        try (ConnectionAcquisitionManager.DeadlineScope ignored =
                     ConnectionAcquisitionManager.beginAcquisitionDeadline(1_000L)) {
            acquired = ConnectionAcquisitionManager.acquireConnection(dataSource, "test");
        }

        assertNotNull(acquired);
        assertEquals(connection, acquired);
        verify(dataSource, org.mockito.Mockito.times(2)).getConnection();
    }

    @Test
    void shouldNotRetryWhenPoolIsFull() throws Exception {
        HikariDataSource dataSource = createHikariDataSource(1, 1, 0);

        try (ConnectionAcquisitionManager.DeadlineScope ignored =
                     ConnectionAcquisitionManager.beginAcquisitionDeadline(1_000L)) {
            assertThrows(SQLTransientConnectionException.class,
                    () -> ConnectionAcquisitionManager.acquireConnection(dataSource, "test"));
        }

        verify(dataSource, never()).getConnection();
    }

    @Test
    void shouldNotRetryNonCapacityFailure() throws Exception {
        HikariDataSource dataSource = createHikariDataSource(2, 1, 0);
        when(dataSource.getConnection()).thenThrow(new SQLException("Login failed"));

        try (ConnectionAcquisitionManager.DeadlineScope ignored =
                     ConnectionAcquisitionManager.beginAcquisitionDeadline(1_000L)) {
            assertThrows(SQLTransientConnectionException.class,
                    () -> ConnectionAcquisitionManager.acquireConnection(dataSource, "test"));
        }

        verify(dataSource).getConnection();
    }

    @Test
    void shouldUseOnlyTimeRemainingAfterAdmission() throws Exception {
        HikariDataSource dataSource = createHikariDataSource(2, 1, 0);
        when(dataSource.getConnection()).thenThrow(new SQLTransientConnectionException(
                "Connection is not available, request timed out after 250ms."));

        try (ConnectionAcquisitionManager.DeadlineScope ignored =
                     ConnectionAcquisitionManager.beginAcquisitionDeadline(300L)) {
            Thread.sleep(250L);
            assertTrue(ConnectionAcquisitionManager.remainingAcquisitionNanos() < 0L
                    || ConnectionAcquisitionManager.remainingAcquisitionNanos()
                    < java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(250L));
            assertThrows(SQLTransientConnectionException.class,
                    () -> ConnectionAcquisitionManager.acquireConnection(dataSource, "test"));
        }

        verify(dataSource).getConnection();
    }

    @Test
    void shouldCloseConnectionReturnedAfterDeadline() throws Exception {
        HikariDataSource dataSource = createHikariDataSource(2, 1, 0);
        Connection lateConnection = mock(Connection.class);
        when(dataSource.getConnection()).thenAnswer(invocation -> {
            Thread.sleep(100L);
            return lateConnection;
        });

        try (ConnectionAcquisitionManager.DeadlineScope ignored =
                     ConnectionAcquisitionManager.beginAcquisitionDeadline(50L)) {
            assertThrows(SQLTransientConnectionException.class,
                    () -> ConnectionAcquisitionManager.acquireConnection(dataSource, "test"));
        }

        verify(lateConnection).close();
    }

    @Test
    void shouldPreserveEarlierDeadlineInNestedScope() {
        try (ConnectionAcquisitionManager.DeadlineScope outer =
                     ConnectionAcquisitionManager.beginAcquisitionDeadline(1_000L)) {
            long before = ConnectionAcquisitionManager.remainingAcquisitionNanos();
            try (ConnectionAcquisitionManager.DeadlineScope ignored =
                         ConnectionAcquisitionManager.beginAcquisitionDeadline(10L)) {
                assertTrue(ConnectionAcquisitionManager.remainingAcquisitionNanos()
                        > java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(100L));
                assertTrue(ConnectionAcquisitionManager.remainingAcquisitionNanos() <= before);
            }
        }
    }

    private HikariDataSource createHikariDataSource(int maximum, int total, int idle) {
        HikariDataSource dataSource = mock(HikariDataSource.class);
        HikariPoolMXBean pool = mock(HikariPoolMXBean.class);
        when(dataSource.getHikariPoolMXBean()).thenReturn(pool);
        when(dataSource.getConnectionTimeout()).thenReturn(250L);
        when(dataSource.getMaximumPoolSize()).thenReturn(maximum);
        when(dataSource.getMinimumIdle()).thenReturn(0);
        when(dataSource.isClosed()).thenReturn(false);
        when(pool.getActiveConnections()).thenReturn(total - idle);
        when(pool.getIdleConnections()).thenReturn(idle);
        when(pool.getTotalConnections()).thenReturn(total);
        when(pool.getThreadsAwaitingConnection()).thenReturn(0);
        return dataSource;
    }
}
