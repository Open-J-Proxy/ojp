package org.openjproxy.xa.pool.commons;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.XAConnection;
import javax.sql.XADataSource;
import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for transaction isolation reset functionality in BackendSessionImpl.
 * These tests verify that the XA connection pool properly resets transaction isolation
 * when sessions are returned to the pool.
 */
class BackendSessionTransactionIsolationTest {

    @Test
    @DisplayName("Session factory should accept transaction isolation parameter")
    void testFactoryWithTransactionIsolation() throws Exception {
        XADataSource mockXADataSource = mock(XADataSource.class);
        XAConnection mockXAConnection = mock(XAConnection.class);
        Connection mockConnection = mock(Connection.class);

        when(mockXADataSource.getXAConnection()).thenReturn(mockXAConnection);
        when(mockXAConnection.getConnection()).thenReturn(mockConnection);

        // Create factory with READ_COMMITTED as default
        BackendSessionFactory factory = new BackendSessionFactory(
                mockXADataSource,
                Connection.TRANSACTION_READ_COMMITTED
        );

        assertNotNull(factory);

        // Create a session
        BackendSessionImpl session = (BackendSessionImpl) factory.makeObject().getObject();
        assertNotNull(session);

        // Clean up
        session.close();
    }

    @Test
    @DisplayName("Session factory should work without transaction isolation parameter")
    void testFactoryWithoutTransactionIsolation() throws Exception {
        XADataSource mockXADataSource = mock(XADataSource.class);
        XAConnection mockXAConnection = mock(XAConnection.class);
        Connection mockConnection = mock(Connection.class);

        when(mockXADataSource.getXAConnection()).thenReturn(mockXAConnection);
        when(mockXAConnection.getConnection()).thenReturn(mockConnection);

        // Create factory without transaction isolation
        BackendSessionFactory factory = new BackendSessionFactory(mockXADataSource);

        assertNotNull(factory);

        // Create a session
        BackendSessionImpl session = (BackendSessionImpl) factory.makeObject().getObject();
        assertNotNull(session);

        // Clean up
        session.close();
    }

    @Test
    @DisplayName("Session reset should handle transaction isolation")
    void testSessionResetHandlesIsolation() throws Exception {
        XADataSource mockXADataSource = mock(XADataSource.class);
        XAConnection mockXAConnection = mock(XAConnection.class);
        Connection mockConnection = mock(Connection.class);

        when(mockXADataSource.getXAConnection()).thenReturn(mockXAConnection);
        when(mockXAConnection.getConnection()).thenReturn(mockConnection);
        when(mockConnection.isClosed()).thenReturn(false);
        when(mockConnection.getAutoCommit()).thenReturn(false);

        // Simulate connection with changed isolation
        when(mockConnection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_SERIALIZABLE);

        // Create factory with READ_COMMITTED as default
        BackendSessionFactory factory = new BackendSessionFactory(
                mockXADataSource,
                Connection.TRANSACTION_READ_COMMITTED
        );

        BackendSessionImpl session = (BackendSessionImpl) factory.makeObject().getObject();

        // Reset the session (simulating return to pool)
        session.reset();

        // Verify that setTransactionIsolation was called to reset it
        verify(mockConnection, atLeastOnce()).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);

        // Clean up
        session.close();
    }

    @Test
    @DisplayName("Session reset should not use local transaction methods on an XA connection")
    void testSessionResetDoesNotManageTransactionsThroughJdbc() throws Exception {
        XADataSource mockXADataSource = mock(XADataSource.class);
        XAConnection mockXAConnection = mock(XAConnection.class);
        Connection mockConnection = mock(Connection.class);

        when(mockXADataSource.getXAConnection()).thenReturn(mockXAConnection);
        when(mockXAConnection.getConnection()).thenReturn(mockConnection);

        BackendSessionImpl session = (BackendSessionImpl) new BackendSessionFactory(mockXADataSource)
                .makeObject().getObject();

        session.reset();

        verify(mockConnection, never()).rollback();
        verify(mockConnection, never()).getAutoCommit();
        verify(mockConnection, never()).setAutoCommit(true);
        session.close();
    }

    @Test
    @DisplayName("Session reset should not reset isolation if not changed")
    void testSessionResetSkipsIfNotChanged() throws Exception {
        XADataSource mockXADataSource = mock(XADataSource.class);
        XAConnection mockXAConnection = mock(XAConnection.class);
        Connection mockConnection = mock(Connection.class);

        when(mockXADataSource.getXAConnection()).thenReturn(mockXAConnection);
        when(mockXAConnection.getConnection()).thenReturn(mockConnection);
        when(mockConnection.isClosed()).thenReturn(false);
        when(mockConnection.getAutoCommit()).thenReturn(true);

        // Connection already has default isolation
        when(mockConnection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);

        // Create factory with READ_COMMITTED as default
        BackendSessionFactory factory = new BackendSessionFactory(
                mockXADataSource,
                Connection.TRANSACTION_READ_COMMITTED
        );

        BackendSessionImpl session = (BackendSessionImpl) factory.makeObject().getObject();

        // Reset the session
        session.reset();

        // Verify setTransactionIsolation was NOT called since isolation hasn't changed
        verify(mockConnection, never()).setTransactionIsolation(anyInt());

        // Clean up
        session.close();
    }

    @Test
    @DisplayName("Session reset should not reset without default isolation configured")
    void testSessionResetWithoutDefaultIsolation() throws Exception {
        XADataSource mockXADataSource = mock(XADataSource.class);
        XAConnection mockXAConnection = mock(XAConnection.class);
        Connection mockConnection = mock(Connection.class);

        when(mockXADataSource.getXAConnection()).thenReturn(mockXAConnection);
        when(mockXAConnection.getConnection()).thenReturn(mockConnection);
        when(mockConnection.isClosed()).thenReturn(false);
        when(mockConnection.getAutoCommit()).thenReturn(true);

        // Connection has some isolation level
        when(mockConnection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_SERIALIZABLE);

        // Create factory without default isolation
        BackendSessionFactory factory = new BackendSessionFactory(mockXADataSource);

        BackendSessionImpl session = (BackendSessionImpl) factory.makeObject().getObject();

        // Reset the session
        session.reset();

        // Verify setTransactionIsolation was NOT called
        verify(mockConnection, never()).setTransactionIsolation(anyInt());

        // Clean up
        session.close();
    }

    @Test
    @DisplayName("Session reset should handle all isolation levels")
    void testAllIsolationLevels() throws Exception {
        int[] isolationLevels = {
                Connection.TRANSACTION_NONE,
                Connection.TRANSACTION_READ_UNCOMMITTED,
                Connection.TRANSACTION_READ_COMMITTED,
                Connection.TRANSACTION_REPEATABLE_READ,
                Connection.TRANSACTION_SERIALIZABLE
        };

        for (int defaultLevel : isolationLevels) {
            XADataSource mockXADataSource = mock(XADataSource.class);
            XAConnection mockXAConnection = mock(XAConnection.class);
            Connection mockConnection = mock(Connection.class);

            when(mockXADataSource.getXAConnection()).thenReturn(mockXAConnection);
            when(mockXAConnection.getConnection()).thenReturn(mockConnection);
            when(mockConnection.isClosed()).thenReturn(false);
            when(mockConnection.getAutoCommit()).thenReturn(false);

            // Connection has different isolation from default (use READ_UNCOMMITTED if default is SERIALIZABLE, else SERIALIZABLE)
            int currentIsolation = (defaultLevel == Connection.TRANSACTION_SERIALIZABLE)
                    ? Connection.TRANSACTION_READ_UNCOMMITTED
                    : Connection.TRANSACTION_SERIALIZABLE;
            when(mockConnection.getTransactionIsolation()).thenReturn(currentIsolation);

            BackendSessionFactory factory = new BackendSessionFactory(mockXADataSource, defaultLevel);
            BackendSessionImpl session = (BackendSessionImpl) factory.makeObject().getObject();

            // Reset should set to default
            session.reset();
            // Verify that setTransactionIsolation was called with the default level
            // Note: It will be called at least once in open() and once in reset()
            verify(mockConnection, atLeast(2)).setTransactionIsolation(defaultLevel);

            // Clean up
            session.close();
        }
    }

    @Test
    @DisplayName("Session reset should handle SQLException gracefully")
    void testSQLExceptionDuringReset() throws Exception {
        XADataSource mockXADataSource = mock(XADataSource.class);
        XAConnection mockXAConnection = mock(XAConnection.class);
        Connection mockConnection = mock(Connection.class);

        when(mockXADataSource.getXAConnection()).thenReturn(mockXAConnection);
        when(mockXAConnection.getConnection()).thenReturn(mockConnection);
        when(mockConnection.isClosed()).thenReturn(false);
        when(mockConnection.getAutoCommit()).thenReturn(false);

        // First call returns a value (during open), subsequent calls throw exception (during reset)
        when(mockConnection.getTransactionIsolation())
                .thenReturn(Connection.TRANSACTION_READ_COMMITTED)  // First call in open()
                .thenThrow(new SQLException("Connection closed"));  // Second call in reset()

        BackendSessionFactory factory = new BackendSessionFactory(
                mockXADataSource,
                Connection.TRANSACTION_READ_COMMITTED
        );

        BackendSessionImpl session = (BackendSessionImpl) factory.makeObject().getObject();

        // Reset should not throw exception (SQLException is caught and logged)
        assertDoesNotThrow(() -> session.reset());

        // Clean up
        session.close();
    }
}
