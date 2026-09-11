package org.openjproxy.grpc.server.action.streaming;

import com.openjproxy.grpc.ConnectionDetails;
import com.openjproxy.grpc.SessionInfo;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.openjproxy.grpc.ProtoConverter;
import org.openjproxy.grpc.server.AdmissionControlManager;
import org.openjproxy.grpc.server.CircuitBreakerRegistry;
import org.openjproxy.grpc.server.ClusterHealthTracker;
import org.openjproxy.grpc.server.MultinodeXaCoordinator;
import org.openjproxy.grpc.server.ServerConfiguration;
import org.openjproxy.grpc.server.SessionManager;
import org.openjproxy.grpc.server.SessionManagerImpl;
import org.openjproxy.grpc.server.UnpooledConnectionDetails;
import org.openjproxy.grpc.server.action.ActionContext;
import org.openjproxy.grpc.server.action.connection.ConnectAction;
import org.openjproxy.grpc.server.utils.ConnectionHashGenerator;
import org.openjproxy.xa.pool.XATransactionRegistry;
import org.openjproxy.xa.pool.spi.XAConnectionPoolProvider;

import javax.sql.DataSource;
import javax.sql.XADataSource;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class SessionConnectionHelperAdmissionTimeoutTest {

    private static final long ADMISSION_TIMEOUT_MS = 300L;
    private static final long ADMISSION_TIMEOUT_SAFETY_MARGIN_MS = 1200L;

    private ActionContext buildContext() {
        ServerConfiguration serverConfiguration = new ServerConfiguration();
        SessionManager sessionManager = new SessionManagerImpl(new ConcurrentHashMap<>());
        CircuitBreakerRegistry circuitBreakerRegistry = new CircuitBreakerRegistry(
                serverConfiguration.getCircuitBreakerTimeout(),
                serverConfiguration.getCircuitBreakerThreshold());

        Map<String, DataSource> datasourceMap = new ConcurrentHashMap<>();
        Map<String, XADataSource> xaDataSourceMap = new ConcurrentHashMap<>();
        Map<String, XATransactionRegistry> xaRegistries = new ConcurrentHashMap<>();
        Map<String, UnpooledConnectionDetails> unpooledConnectionDetailsMap = new ConcurrentHashMap<>();
        Map<String, com.openjproxy.grpc.DbName> dbNameMap = new ConcurrentHashMap<>();
        Map<String, AdmissionControlManager> admissionControlManagers = new ConcurrentHashMap<>();

        return new ActionContext(
                datasourceMap,
                xaDataSourceMap,
                xaRegistries,
                unpooledConnectionDetailsMap,
                dbNameMap,
                admissionControlManagers,
                new ConcurrentHashMap<>(),
                null,
                mock(XAConnectionPoolProvider.class),
                new MultinodeXaCoordinator(),
                new ClusterHealthTracker(),
                sessionManager,
                circuitBreakerRegistry,
                serverConfiguration,
                null,
                null);
    }

    @Test
    void shouldFailAtAdmissionGateWithSingleTimeoutWhenPoolIsExhausted() throws Exception {
        ActionContext context = buildContext();

        Properties properties = new Properties();
        properties.setProperty("ojp.connection.pool.maximumPoolSize", "1");
        properties.setProperty("ojp.connection.pool.minimumIdle", "0");
        properties.setProperty("ojp.connection.pool.connectionTimeout", String.valueOf(ADMISSION_TIMEOUT_MS));

        Map<String, Object> propertyMap = new HashMap<>();
        for (String key : properties.stringPropertyNames()) {
            propertyMap.put(key, properties.getProperty(key));
        }

        ConnectionDetails ownerConnection = ConnectionDetails.newBuilder()
                .setUrl("jdbc:h2:mem:single_timeout_test;DB_CLOSE_DELAY=-1")
                .setUser("sa")
                .setPassword("")
                .setClientUUID("owner-client")
                .addAllProperties(ProtoConverter.propertiesToProto(propertyMap))
                .build();

        connect(context, ownerConnection);
        String connHash = ConnectionHashGenerator.hashConnectionDetails(ownerConnection);

        SessionInfo ownerSessionSeed = SessionInfo.newBuilder()
                .setConnHash(connHash)
                .setClientUUID("owner-client")
                .setIsXA(false)
                .build();

        var ownerDto = SessionConnectionHelper.sessionConnection(context, ownerSessionSeed, true);

        int concurrentClients = 6;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(concurrentClients);
        ExecutorService executor = Executors.newFixedThreadPool(concurrentClients);
        AtomicInteger timedOutCount = new AtomicInteger(0);
        AtomicLong maxDurationMs = new AtomicLong(0L);
        List<String> errorMessages = new ArrayList<>();
        AtomicReference<Exception> unexpected = new AtomicReference<>();

        for (int i = 0; i < concurrentClients; i++) {
            String clientId = "contender-" + i;
            ConnectionDetails contenderConnect = ConnectionDetails.newBuilder(ownerConnection)
                    .setClientUUID(clientId)
                    .build();
            connect(context, contenderConnect);

            executor.submit(() -> {
                try {
                    SessionInfo contenderSeed = SessionInfo.newBuilder()
                            .setConnHash(connHash)
                            .setClientUUID(clientId)
                            .setIsXA(false)
                            .build();
                    startLatch.await();
                    long start = System.nanoTime();
                    try {
                        SessionConnectionHelper.sessionConnection(context, contenderSeed, true);
                    } catch (SQLException e) {
                        long durationMs = (System.nanoTime() - start) / 1_000_000L;
                        maxDurationMs.accumulateAndGet(durationMs, Math::max);
                        synchronized (errorMessages) {
                            errorMessages.add(e.getMessage());
                        }
                        timedOutCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    unexpected.compareAndSet(null, e);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean finished = doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        try {
            assertTrue(finished, "Contender threads should complete");
            assertNull(unexpected.get(), "No unexpected thread failures should occur");
            assertEquals(concurrentClients, timedOutCount.get(),
                    "All contenders should fail at connection admission while owner session holds permit");
            assertTrue(maxDurationMs.get() < ADMISSION_TIMEOUT_MS + ADMISSION_TIMEOUT_SAFETY_MARGIN_MS,
                    "Maximum observed failure latency should stay close to admission timeout and not become additive");
            assertTrue(errorMessages.stream().allMatch(message -> message != null && message.contains("phase=admission")),
                    "Failures should be attributed to admission timeout phase");
        } finally {
            context.getSessionManager().terminateSession(ownerDto.getSession());
        }
    }

    private void connect(ActionContext context, ConnectionDetails connectionDetails) {
        ConnectAction.getInstance().execute(context, connectionDetails, new StreamObserver<>() {
            @Override
            public void onNext(SessionInfo value) {
                // no-op
            }

            @Override
            public void onError(Throwable t) {
                throw new RuntimeException(t);
            }

            @Override
            public void onCompleted() {
                // no-op
            }
        });
    }
}
