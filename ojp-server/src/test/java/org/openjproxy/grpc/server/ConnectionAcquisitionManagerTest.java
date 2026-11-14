package org.openjproxy.grpc.server;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConnectionAcquisitionManagerTest {

    @Test
    void shouldRetryAcquisitionUntilGracePeriodWhenPoolBorrowFails() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection())
                .thenThrow(new SQLTransientConnectionException("not ready"))
                .thenReturn(connection);
        ConnectionAcquisitionManager.registerCreationGracePeriod(dataSource, 100);

        assertSame(connection, ConnectionAcquisitionManager.acquireConnection(dataSource, "test"));

        verify(dataSource, times(2)).getConnection();
    }

    @Test
    void shouldNotRetryWhenGracePeriodIsDisabled() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLTransientConnectionException("unavailable"));

        assertThrows(SQLException.class,
                () -> ConnectionAcquisitionManager.acquireConnection(dataSource, "test"));

        verify(dataSource).getConnection();
    }
}
