package org.openjproxy.grpc.server.action.connection;

import com.openjproxy.grpc.ConnectionDetails;
import com.openjproxy.grpc.PropertyEntry;
import com.openjproxy.grpc.SessionInfo;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.openjproxy.grpc.server.ClusterHealthTracker;
import org.openjproxy.grpc.server.ServerConfiguration;
import org.openjproxy.grpc.server.SessionManager;
import org.openjproxy.grpc.server.action.ActionContext;
import org.openjproxy.grpc.server.action.util.ProcessClusterHealthAction;
import org.openjproxy.grpc.server.pool.ConnectionPoolConfigurer;
import org.openjproxy.grpc.server.pool.DatabaseConnectionBudgetManager;
import org.openjproxy.xa.pool.XABackendSession;
import org.openjproxy.xa.pool.XATransactionRegistry;
import org.openjproxy.xa.pool.spi.XAConnectionPoolProvider;

import javax.sql.XADataSource;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.withSettings;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HandleXAConnectionWithPoolingActionTest {
    private static final String CONN_HASH = "xa-registry-reuse-test";
    private XAConnectionPoolProvider provider;
    private XADataSource dataSource;
    private ActionContext context;
    private DatabaseConnectionBudgetManager budgetManager;
    private MockedStatic<DatabaseConnectionBudgetManager> budgetMock;

    @BeforeEach
    void setUp() throws Exception {
        budgetManager = new DatabaseConnectionBudgetManager(new Properties());
        budgetMock = mockStatic(DatabaseConnectionBudgetManager.class);
        budgetMock.when(DatabaseConnectionBudgetManager::getInstance).thenAnswer(invocation -> budgetManager);
        provider = mock(XAConnectionPoolProvider.class);
        dataSource = mock(XADataSource.class, withSettings().extraInterfaces(AutoCloseable.class));
        when(provider.createXADataSource(any())).thenReturn(dataSource);
        when(provider.supportsDynamicResizing()).thenReturn(true);
        when(provider.resizePool(any(), anyInt(), anyInt())).thenReturn(true);
        when(provider.borrowSession(any())).thenReturn(mock(XABackendSession.class));

        SessionManager sessionManager = mock(SessionManager.class);
        when(sessionManager.createXASession(anyString(), any(), any()))
                .thenReturn(SessionInfo.newBuilder().setConnHash(CONN_HASH).setSessionUUID("session").build());
        context = new ActionContext(
                new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), new ConcurrentHashMap<>(),
                new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), new ConcurrentHashMap<>(),
                new ConcurrentHashMap<>(), null, provider, null, new ClusterHealthTracker(),
                sessionManager, null, new ServerConfiguration(), null, null);
    }

    @AfterEach
    void tearDown() {
        budgetMock.close();
        ConnectionPoolConfigurer.getPoolCoordinator().removeAllocation(CONN_HASH);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void shouldReuseRegistryAfterHealthDrivenExpansionAndRecovery(int healthyServers) throws Exception {
        ConnectionDetails details = connectionDetails();
        connect(details);
        XATransactionRegistry registry = context.getXaRegistries().get(CONN_HASH);
        assertEquals(7, registry.getMaxPoolSize());

        String health = "localhost:1059(UP);localhost:1060("
                + (healthyServers == 2 ? "UP" : "DOWN") + ");localhost:1061(DOWN)";
        processHealth(health);
        assertEquals(healthyServers == 1 ? 20 : 10, registry.getMaxPoolSize());
        assertEquals(healthyServers == 1 ? 5 : 3, registry.getMinIdle());
        connect(details);
        connect(details);
        assertSame(registry, context.getXaRegistries().get(CONN_HASH));
        assertEquals(healthyServers,
                ConnectionPoolConfigurer.getPoolCoordinator().getPoolAllocation(CONN_HASH).getHealthyServers());

        processHealth("localhost:1059(UP);localhost:1060(UP);localhost:1061(UP)");
        connect(details);
        assertSame(registry, context.getXaRegistries().get(CONN_HASH));
        assertEquals(7, registry.getMaxPoolSize());
        assertEquals(2, registry.getMinIdle());
        verify(provider, times(1)).createXADataSource(any());
        verify((AutoCloseable) dataSource, never()).close();
    }

    @Test
    void shouldReuseRegistryAfterBudgetRebalancesItsPool() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("ojp.server.databaseBudgets.test.match.jdbcUrlPattern", "jdbc:postgresql:*");
        properties.setProperty("ojp.server.databaseBudgets.test.maxTotalConnections", "12");
        budgetManager = new DatabaseConnectionBudgetManager(properties);
        ConnectionDetails details = connectionDetails();
        connect(details);
        XATransactionRegistry registry = context.getXaRegistries().get(CONN_HASH);
        budgetManager.registerPool("xa:other", "jdbc:postgresql://localhost/test", "test", 20, 5, true, 3);
        assertEquals(2, registry.getMaxPoolSize());

        connect(details);
        assertSame(registry, context.getXaRegistries().get(CONN_HASH));
        budgetManager.unregisterPool("xa:other");
        connect(details);
        assertSame(registry, context.getXaRegistries().get(CONN_HASH));
        assertEquals(4, registry.getMaxPoolSize());
        verify((AutoCloseable) dataSource, never()).close();
    }

    @Test
    void shouldRecreateRegistryWhenRequestedConfigurationChanges() throws Exception {
        ConnectionDetails details = connectionDetails();
        connect(details);
        XATransactionRegistry registry = context.getXaRegistries().get(CONN_HASH);
        connect(details.toBuilder().setProperties(0, PropertyEntry.newBuilder()
                .setKey("ojp.xa.connection.pool.maximumPoolSize").setStringValue("30")).build());
        assertNotSame(registry, context.getXaRegistries().get(CONN_HASH));
        assertEquals(10, context.getXaRegistries().get(CONN_HASH).getMaxPoolSize());
        verify((AutoCloseable) dataSource).close();
    }

    @Test
    void shouldRecreateRegistryWhenEndpointsChange() throws Exception {
        ConnectionDetails details = connectionDetails();
        connect(details);
        XATransactionRegistry registry = context.getXaRegistries().get(CONN_HASH);
        connect(details.toBuilder().clearServerEndpoints().addServerEndpoints("localhost:1059").build());
        assertNotSame(registry, context.getXaRegistries().get(CONN_HASH));
        assertEquals(20, context.getXaRegistries().get(CONN_HASH).getMaxPoolSize());
        verify((AutoCloseable) dataSource).close();
    }

    @Test
    void shouldDetectConfigurationChangeEvenWhenBudgetKeepsLiveSizeUnchanged() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("ojp.server.databaseBudgets.test.match.jdbcUrlPattern", "jdbc:postgresql:*");
        properties.setProperty("ojp.server.databaseBudgets.test.maxTotalConnections", "12");
        budgetManager = new DatabaseConnectionBudgetManager(properties);
        ConnectionDetails details = connectionDetails();
        connect(details);
        XATransactionRegistry registry = context.getXaRegistries().get(CONN_HASH);
        assertEquals(4, registry.getMaxPoolSize());
        connect(details.toBuilder().setProperties(0, PropertyEntry.newBuilder()
                .setKey("ojp.xa.connection.pool.maximumPoolSize").setStringValue("30")).build());
        assertNotSame(registry, context.getXaRegistries().get(CONN_HASH));
        assertEquals(4, context.getXaRegistries().get(CONN_HASH).getMaxPoolSize());
        verify((AutoCloseable) dataSource).close();
    }

    @Test
    void shouldDetectMinimumIdleChangeToZero() throws Exception {
        ConnectionDetails details = connectionDetails();
        connect(details);
        XATransactionRegistry registry = context.getXaRegistries().get(CONN_HASH);
        connect(details.toBuilder().setProperties(1, PropertyEntry.newBuilder()
                .setKey("ojp.xa.connection.pool.minimumIdle").setStringValue("0")).build());
        assertNotSame(registry, context.getXaRegistries().get(CONN_HASH));
        assertEquals(0, context.getXaRegistries().get(CONN_HASH).getMinIdle());
        verify((AutoCloseable) dataSource).close();
    }

    private ConnectionDetails connectionDetails() {
        return ConnectionDetails.newBuilder()
                .setUrl("jdbc:ojp[localhost:1059,localhost:1060,localhost:1061]_postgresql://localhost/test")
                .setUser("test").setClientUUID("client")
                .addServerEndpoints("localhost:1059").addServerEndpoints("localhost:1060")
                .addServerEndpoints("localhost:1061")
                .addProperties(PropertyEntry.newBuilder()
                        .setKey("ojp.xa.connection.pool.maximumPoolSize").setStringValue("20"))
                .addProperties(PropertyEntry.newBuilder()
                        .setKey("ojp.xa.connection.pool.minimumIdle").setStringValue("5"))
                .build();
    }

    private void processHealth(String health) {
        ProcessClusterHealthAction.getInstance().execute(context,
                SessionInfo.newBuilder().setConnHash(CONN_HASH).setIsXA(true).setClusterHealth(health).build());
    }

    @SuppressWarnings("unchecked")
    private void connect(ConnectionDetails details) {
        StreamObserver<SessionInfo> observer = mock(StreamObserver.class);
        HandleXAConnectionWithPoolingAction.getInstance().execute(context, details, CONN_HASH, 50, 60_000, observer);
        verify(observer).onCompleted();
        verify(observer, never()).onError(any());
    }
}
