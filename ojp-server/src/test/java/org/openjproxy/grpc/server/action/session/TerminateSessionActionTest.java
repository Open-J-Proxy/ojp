package org.openjproxy.grpc.server.action.session;

import com.openjproxy.grpc.SessionInfo;
import com.openjproxy.grpc.SessionTerminationStatus;
import com.openjproxy.grpc.XaStartRequest;
import com.openjproxy.grpc.XidProto;
import com.google.protobuf.ByteString;
import io.grpc.stub.StreamObserver;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openjproxy.grpc.server.Session;
import org.openjproxy.grpc.server.SessionManagerImpl;
import org.openjproxy.grpc.server.action.ActionContext;
import org.openjproxy.grpc.server.action.xa.XaStartAction;
import org.openjproxy.xa.pool.XABackendSession;
import org.openjproxy.xa.pool.XATransactionRegistry;
import org.openjproxy.xa.pool.XidKey;
import org.openjproxy.xa.pool.commons.CommonsPool2XADataSource;
import org.openjproxy.xa.pool.commons.CommonsPool2XAProvider;
import org.openjproxy.xa.pool.spi.XAConnectionPoolProvider;

import javax.sql.XAConnection;
import javax.transaction.xa.XAResource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TerminateSessionActionTest {
    private final String connHash = "test-hash";
    private final String clientId = "test-client";
    private final Object pool = new Object();
    private XAConnectionPoolProvider provider;
    private XATransactionRegistry registry;
    private SessionManagerImpl manager;
    private ActionContext context;
    private Connection connection;
    private XAConnection xaConnection;
    private XAResource resource;
    private XABackendSession backend;
    private Map<String, XATransactionRegistry> registries;

    @BeforeEach
    void setUp() throws Exception {
        provider = mock(XAConnectionPoolProvider.class);
        registry = new XATransactionRegistry(provider, pool, connHash, 1, 0);
        registries = new ConcurrentHashMap<>();
        registries.put(connHash, registry);
        manager = new SessionManagerImpl();
        manager.registerClientUUID(connHash, clientId);
        context = mock(ActionContext.class);
        when(context.getSessionManager()).thenReturn(manager);
        when(context.getXaRegistries()).thenReturn(registries);
        when(context.getXaPoolProvider()).thenReturn(provider);
        connection = mock(Connection.class);
        when(connection.getAutoCommit()).thenReturn(true);
        xaConnection = mock(XAConnection.class);
        resource = mock(XAResource.class);
        when(xaConnection.getXAResource()).thenReturn(resource);
        backend = mock(XABackendSession.class);
        when(backend.getConnection()).thenReturn(connection);
        when(backend.getXAResource()).thenReturn(resource);
    }

    private SessionInfo newSession() {
        SessionInfo info = manager.createXASession(clientId, connection, xaConnection);
        Session session = manager.getSession(info);
        session.setBackendSession(backend);
        session.setXaPoolRegistry(registry);
        return info;
    }

    private void terminate(SessionInfo info) {
        @SuppressWarnings("unchecked")
        StreamObserver<SessionTerminationStatus> observer = mock(StreamObserver.class);
        TerminateSessionAction.getInstance().execute(context, info, observer);
        verify(observer).onNext(SessionTerminationStatus.newBuilder().setTerminated(true).build());
        verify(observer).onCompleted();
        verify(observer, never()).onError(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldCloseUnusedBackendAfterSessionRemovalAndOnlyOnce() throws Exception {
        SessionInfo info = newSession();
        doAnswer(invocation -> {
            assertNull(manager.getSession(info));
            return null;
        }).when(provider).invalidateSession(pool, backend);

        terminate(info);
        terminate(info);

        verify(provider, times(1)).invalidateSession(pool, backend);
    }

    @Test
    void shouldCloseUnusedBackendInItsOriginalPoolAfterRegistryReplacement() throws Exception {
        SessionInfo info = newSession();
        Object replacementPool = new Object();
        registries.put(connHash, new XATransactionRegistry(provider, replacementPool, connHash, 1, 0));

        terminate(info);

        verify(provider).invalidateSession(pool, backend);
        verify(provider, never()).invalidateSession(replacementPool, backend);
    }

    @Test
    void shouldLeaveActiveTransactionBackendWithRegistry() throws Exception {
        SessionInfo info = newSession();
        XidKey xid = new XidKey(1, new byte[]{1}, new byte[]{1});
        registry.registerExistingSession(xid, backend, XAResource.TMNOFLAGS, info.getSessionUUID());

        terminate(info);

        assertTrue(registry.hasTransactionsForSession(info.getSessionUUID()));
        verify(provider, never()).returnSession(pool, backend);
        verify(provider, never()).invalidateSession(pool, backend);
    }

    @Test
    void shouldReturnCompletedTransactionBackendOnlyOnce() throws Exception {
        SessionInfo info = newSession();
        XidKey xid = new XidKey(1, new byte[]{1}, new byte[]{1});
        registry.registerExistingSession(xid, backend, XAResource.TMNOFLAGS, info.getSessionUUID());
        registry.xaEnd(xid, XAResource.TMSUCCESS);
        registry.xaCommit(xid, true);

        terminate(info);
        terminate(info);

        verify(provider, times(1)).returnSession(pool, backend);
        assertNull(registry.getContext(xid));
    }

    @Test
    void shouldCloseUnusedBackendOnlyOnceForConcurrentTermination() throws Exception {
        SessionInfo info = newSession();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(() -> terminate(info));
            Future<?> second = executor.submit(() -> terminate(info));
            first.get();
            second.get();
        }

        verify(provider, times(1)).invalidateSession(pool, backend);
    }

    @Test
    void shouldInvalidateUnusedBackendWhenSessionCleanupFails() throws Exception {
        SessionInfo info = newSession();
        Session session = manager.getSession(info);
        when(connection.getAutoCommit()).thenThrow(new SQLException("cleanup failed"));
        @SuppressWarnings("unchecked")
        StreamObserver<SessionTerminationStatus> observer = mock(StreamObserver.class);

        TerminateSessionAction.getInstance().execute(context, info, observer);

        verify(observer).onError(org.mockito.ArgumentMatchers.any());
        assertThrows(RuntimeException.class, session::getConnection);
        verify(provider).invalidateSession(pool, backend);
        verify(provider, never()).returnSession(pool, backend);
        assertNull(manager.getSession(info));
    }

    @Test
    void shouldCloseUnusedBackendAfterRejectedDuplicateStart() throws Exception {
        SessionInfo info = newSession();
        XidKey xid = new XidKey(1, new byte[]{1}, new byte[]{1});
        XABackendSession otherBackend = mock(XABackendSession.class);
        when(otherBackend.getXAResource()).thenReturn(resource);
        registry.registerExistingSession(xid, otherBackend, XAResource.TMNOFLAGS, "other");
        @SuppressWarnings("unchecked")
        StreamObserver<com.openjproxy.grpc.XaResponse> observer = mock(StreamObserver.class);

        XaStartAction.getInstance().execute(context, startRequest(info, XAResource.TMNOFLAGS), observer);
        verify(observer).onError(org.mockito.ArgumentMatchers.any());
        terminate(info);

        verify(provider).invalidateSession(pool, backend);
        verify(provider, never()).invalidateSession(pool, otherBackend);
    }

    @Test
    void shouldCloseOwnUnusedBackendAfterJoiningAnotherSessionTransaction() throws Exception {
        SessionInfo info = newSession();
        XidKey xid = new XidKey(1, new byte[]{1}, new byte[]{1});
        XABackendSession otherBackend = mock(XABackendSession.class);
        when(otherBackend.getXAResource()).thenReturn(resource);
        registry.registerExistingSession(xid, otherBackend, XAResource.TMNOFLAGS, "other");
        registry.xaEnd(xid, XAResource.TMSUCCESS);
        @SuppressWarnings("unchecked")
        StreamObserver<com.openjproxy.grpc.XaResponse> observer = mock(StreamObserver.class);

        XaStartAction.getInstance().execute(context, startRequest(info, XAResource.TMJOIN), observer);
        verify(observer).onCompleted();
        terminate(info);

        verify(provider).invalidateSession(pool, backend);
        verify(provider, never()).invalidateSession(pool, otherBackend);
    }

    @Test
    void shouldReleaseSingleSlotPoolAcrossUnusedSessions() throws Exception {
        JdbcDataSource vendor = new JdbcDataSource();
        vendor.setURL("jdbc:h2:mem:unused-xa-session");
        CommonsPool2XAProvider realProvider = new CommonsPool2XAProvider();
        try (CommonsPool2XADataSource realPool = new CommonsPool2XADataSource(vendor, Map.of(
                "xa.maxPoolSize", "1", "xa.minIdle", "0", "xa.connectionTimeoutMs", "500"))) {
            XATransactionRegistry realRegistry = new XATransactionRegistry(realProvider, realPool, connHash, 1, 0);
            registries.put(connHash, realRegistry);
            when(context.getXaPoolProvider()).thenReturn(realProvider);

            for (int i = 0; i < 5; i++) {
                XABackendSession borrowed = realProvider.borrowSession(realPool);
                assertEquals("PUBLIC", borrowed.getConnection().getSchema());
                try (Statement statement = borrowed.getConnection().createStatement()) {
                    statement.execute("CREATE SCHEMA IF NOT EXISTS PRIVATE_SCHEMA");
                }
                borrowed.getConnection().setSchema("PRIVATE_SCHEMA");
                SessionInfo info = manager.createXASession(clientId, borrowed.getConnection(), borrowed.getXAConnection());
                Session session = manager.getSession(info);
                session.setBackendSession(borrowed);
                session.setXaPoolRegistry(realRegistry);
                terminate(info);
                assertEquals(0, realPool.getNumActive());
            }
        }
    }

    @Test
    void shouldWaitForConcurrentStartBeforeCheckingForTransactions() throws Exception {
        SessionInfo info = newSession();
        CountDownLatch starting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            starting.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return null;
        }).when(resource).start(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt());
        @SuppressWarnings("unchecked")
        StreamObserver<com.openjproxy.grpc.XaResponse> observer = mock(StreamObserver.class);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> start = executor.submit(() ->
                    XaStartAction.getInstance().execute(context, startRequest(info, XAResource.TMNOFLAGS), observer));
            try {
                assertTrue(starting.await(5, TimeUnit.SECONDS));
                Future<?> close = executor.submit(() -> terminate(info));
                assertThrows(TimeoutException.class, () -> close.get(200, TimeUnit.MILLISECONDS));
                verify(provider, never()).invalidateSession(pool, backend);
                release.countDown();
                start.get(5, TimeUnit.SECONDS);
                close.get(5, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
        }
        verify(observer).onCompleted();
        verify(provider, never()).returnSession(pool, backend);
        verify(provider, never()).invalidateSession(pool, backend);
    }

    @Test
    void shouldWaitForConcurrentSessionManagerCleanup() throws Exception {
        SessionInfo info = newSession();
        XidKey xid = new XidKey(1, new byte[]{1}, new byte[]{1});
        registry.registerExistingSession(xid, backend, XAResource.TMNOFLAGS, info.getSessionUUID());
        registry.xaEnd(xid, XAResource.TMSUCCESS);
        registry.xaCommit(xid, true);
        CountDownLatch cleaning = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(connection.getAutoCommit()).thenAnswer(invocation -> {
            cleaning.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return true;
        });
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> cleanup = executor.submit(() -> {
                try {
                    manager.terminateSession(info);
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            });
            try {
                assertTrue(cleaning.await(5, TimeUnit.SECONDS));
                Future<?> close = executor.submit(() -> terminate(info));
                assertThrows(TimeoutException.class, () -> close.get(200, TimeUnit.MILLISECONDS));
                verify(provider, never()).invalidateSession(pool, backend);
                release.countDown();
                cleanup.get(5, TimeUnit.SECONDS);
                close.get(5, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
        }
        verify(provider, times(1)).returnSession(pool, backend);
        assertNull(registry.getContext(xid));
    }

    @Test
    void shouldReturnCompletedBackendOnlyOnceWhenMissingSessionIsClosedConcurrently() throws Exception {
        SessionInfo info = newSession();
        XidKey xid = new XidKey(1, new byte[]{1}, new byte[]{1});
        registry.registerExistingSession(xid, backend, XAResource.TMNOFLAGS, info.getSessionUUID());
        registry.xaEnd(xid, XAResource.TMSUCCESS);
        registry.xaCommit(xid, true);
        manager.terminateSession(info);
        CountDownLatch returning = new CountDownLatch(1);
        CountDownLatch allowReturn = new CountDownLatch(1);
        doAnswer(invocation -> {
            returning.countDown();
            assertTrue(allowReturn.await(5, TimeUnit.SECONDS));
            return null;
        }).when(provider).returnSession(pool, backend);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> terminate(info));
            assertTrue(returning.await(5, TimeUnit.SECONDS));
            Future<?> second = executor.submit(() -> terminate(info));
            allowReturn.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        } finally {
            allowReturn.countDown();
            executor.shutdownNow();
        }
        verify(provider, times(1)).returnSession(pool, backend);
    }

    @Test
    void shouldCloseBackendAfterFailedXaStart() throws Exception {
        SessionInfo info = newSession();
        doThrow(new javax.transaction.xa.XAException(javax.transaction.xa.XAException.XAER_RMERR))
                .when(resource).start(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt());
        @SuppressWarnings("unchecked")
        StreamObserver<com.openjproxy.grpc.XaResponse> observer = mock(StreamObserver.class);

        XaStartAction.getInstance().execute(context, startRequest(info, XAResource.TMNOFLAGS), observer);
        verify(observer).onError(org.mockito.ArgumentMatchers.any());
        terminate(info);

        verify(provider).invalidateSession(pool, backend);
    }

    private XaStartRequest startRequest(SessionInfo info, int flags) {
        return XaStartRequest.newBuilder().setSession(info).setFlags(flags)
                .setXid(XidProto.newBuilder().setFormatId(1)
                        .setGlobalTransactionId(ByteString.copyFrom(new byte[]{1}))
                        .setBranchQualifier(ByteString.copyFrom(new byte[]{1}))).build();
    }
}
