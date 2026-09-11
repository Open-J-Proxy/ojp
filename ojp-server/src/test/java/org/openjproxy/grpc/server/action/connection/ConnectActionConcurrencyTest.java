package org.openjproxy.grpc.server.action.connection;

import com.openjproxy.grpc.ConnectionDetails;
import com.openjproxy.grpc.SessionInfo;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.openjproxy.grpc.server.CircuitBreakerRegistry;
import org.openjproxy.grpc.server.ClusterHealthTracker;
import org.openjproxy.grpc.server.MultinodeXaCoordinator;
import org.openjproxy.grpc.server.ServerConfiguration;
import org.openjproxy.grpc.server.SessionManager;
import org.openjproxy.grpc.server.UnpooledConnectionDetails;
import org.openjproxy.grpc.server.action.ActionContext;
import org.openjproxy.grpc.server.readwrite.ReadWriteDataSourceRegistry;
import org.openjproxy.xa.pool.spi.XAConnectionPoolProvider;

import javax.sql.DataSource;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Tests that ConnectAction handles concurrent pool creation correctly,
 * ensuring exactly one pool is created per connection hash even under
 * high concurrency.
 */
class ConnectActionConcurrencyTest {

    private static final int CONCURRENT_THREAD_COUNT = 20;

    /**
     * Verifies that when many clients connect simultaneously with the same credentials,
     * exactly one connection pool is created and all clients share it.
     * Uses an H2 in-memory database so the pool can actually be created without an external DB.
     */
    @Test
    void testConcurrentConnectionsCreateOnlyOnePool() throws InterruptedException {
        // Shared datasource map across all concurrent connect attempts
        Map<String, DataSource> datasourceMap = new ConcurrentHashMap<>();
        Map<String, UnpooledConnectionDetails> unpooledMap = new ConcurrentHashMap<>();

        SessionManager sessionManager = mock(SessionManager.class);
        ServerConfiguration serverConfiguration = new ServerConfiguration();
        CircuitBreakerRegistry circuitBreakerRegistry = new CircuitBreakerRegistry(
                serverConfiguration.getCircuitBreakerTimeout(),
                serverConfiguration.getCircuitBreakerThreshold());

        ActionContext context = new ActionContext(
                datasourceMap,
                new ConcurrentHashMap<>(),
                new ConcurrentHashMap<>(),
                unpooledMap,
                new ConcurrentHashMap<>(),
                new ConcurrentHashMap<>(),
                new ConcurrentHashMap<>(),
                new ReadWriteDataSourceRegistry(),
                mock(XAConnectionPoolProvider.class),
                new MultinodeXaCoordinator(),
                new ClusterHealthTracker(),
                sessionManager,
                circuitBreakerRegistry,
                serverConfiguration,
                null,
                null);

        // Use H2 in-memory database so the pool can actually be created without an external DB
        ConnectionDetails connectionDetails = ConnectionDetails.newBuilder()
                .setUrl("jdbc:h2:mem:concurrency_test;DB_CLOSE_DELAY=-1")
                .setUser("sa")
                .setPassword("")
                .setClientUUID("client-uuid")
                .build();

        int threadCount = CONCURRENT_THREAD_COUNT;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        // Submit all threads at the same time to maximize concurrency
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();  // All threads wait until released simultaneously
                    StreamObserver<SessionInfo> responseObserver = mock(StreamObserver.class);
                    ConnectAction.getInstance().execute(context, connectionDetails, responseObserver);
                    successCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Release all threads at once
        startLatch.countDown();

        // Wait for all threads to complete
        boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(completed, "Threads did not complete in time");
        assertEquals(threadCount, successCount.get(), "All threads should succeed");

        // The key assertion: only ONE pool should be created for the same connection hash
        assertEquals(1, datasourceMap.size(),
                "Exactly one connection pool should be created even with "
                + threadCount + " concurrent connect attempts, but "
                + datasourceMap.size() + " pools were created");
    }

    /**
     * Verifies that connections with different credentials get separate pools.
     */
    @Test
    void testConcurrentConnectionsDifferentHashesCreateSeparatePools() {
        Map<String, DataSource> datasourceMap = new ConcurrentHashMap<>();

        SessionManager sessionManager = mock(SessionManager.class);
        ServerConfiguration serverConfiguration = new ServerConfiguration();
        CircuitBreakerRegistry circuitBreakerRegistry = new CircuitBreakerRegistry(
                serverConfiguration.getCircuitBreakerTimeout(),
                serverConfiguration.getCircuitBreakerThreshold());

        ActionContext context = new ActionContext(
                datasourceMap,
                new ConcurrentHashMap<>(),
                new ConcurrentHashMap<>(),
                new ConcurrentHashMap<>(),
                new ConcurrentHashMap<>(),
                new ConcurrentHashMap<>(),
                new ConcurrentHashMap<>(),
                new ReadWriteDataSourceRegistry(),
                mock(XAConnectionPoolProvider.class),
                new MultinodeXaCoordinator(),
                new ClusterHealthTracker(),
                sessionManager,
                circuitBreakerRegistry,
                serverConfiguration,
                null,
                null);

        // Two different connection details producing different hashes (different DB names)
        ConnectionDetails connectionDetails1 = ConnectionDetails.newBuilder()
                .setUrl("jdbc:h2:mem:separate_pools_db1;DB_CLOSE_DELAY=-1")
                .setUser("sa")
                .setPassword("")
                .setClientUUID("client-1")
                .build();
        ConnectionDetails connectionDetails2 = ConnectionDetails.newBuilder()
                .setUrl("jdbc:h2:mem:separate_pools_db2;DB_CLOSE_DELAY=-1")
                .setUser("sa")
                .setPassword("")
                .setClientUUID("client-2")
                .build();

        StreamObserver<SessionInfo> obs1 = mock(StreamObserver.class);
        StreamObserver<SessionInfo> obs2 = mock(StreamObserver.class);

        ConnectAction.getInstance().execute(context, connectionDetails1, obs1);
        ConnectAction.getInstance().execute(context, connectionDetails2, obs2);

        // Each distinct connection should create its own pool
        assertEquals(2, datasourceMap.size(),
                "Two distinct connections should create two separate pools");
    }
}
