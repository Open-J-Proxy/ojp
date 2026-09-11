package org.openjproxy.grpc.server.action.connection;

import com.openjproxy.grpc.ConnectionDetails;
import com.openjproxy.grpc.SessionInfo;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openjproxy.grpc.server.CircuitBreakerRegistry;
import org.openjproxy.grpc.server.ClusterHealthTracker;
import org.openjproxy.grpc.server.MultinodeXaCoordinator;
import org.openjproxy.grpc.server.ServerConfiguration;
import org.openjproxy.grpc.server.SessionManager;
import org.openjproxy.grpc.server.AdmissionControlManager;
import org.openjproxy.grpc.server.action.ActionContext;
import org.openjproxy.grpc.server.readwrite.ReadWriteDataSourceRegistry;
import org.openjproxy.grpc.server.xa.XADataSourceFactory;
import org.openjproxy.xa.pool.spi.XAConnectionPoolProvider;

import javax.sql.XADataSource;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
/**
 * Tests for HandleUnpooledXAConnectionAction.
 */
class HandleUnpooledXAConnectionActionTest {

    @Test
    @SuppressWarnings("unchecked")
    void testExecuteCreatesAdmissionControlManager() {
        // Arrange
        Map<String, AdmissionControlManager> admissionControlManagers = new ConcurrentHashMap<>();

        SessionManager sessionManager = mock(SessionManager.class);
        ServerConfiguration serverConfiguration = new ServerConfiguration();
        CircuitBreakerRegistry circuitBreakerRegistry = new CircuitBreakerRegistry(
                serverConfiguration.getCircuitBreakerTimeout(),
                serverConfiguration.getCircuitBreakerThreshold());

        ActionContext context = new ActionContext(
                new ConcurrentHashMap<>(),
                new ConcurrentHashMap<>(),
                new ConcurrentHashMap<>(),
                new ConcurrentHashMap<>(),
                new ConcurrentHashMap<>(),
                admissionControlManagers,
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

        ConnectionDetails connectionDetails = ConnectionDetails.newBuilder()
                .setUrl("ojp:postgresql://localhost:5432/testdb")
                .setUser("user")
                .setPassword("pass")
                .setClientUUID("test-client-uuid")
                .build();

        String connHash = "test-conn-hash";
        StreamObserver<SessionInfo> responseObserver = mock(StreamObserver.class);

        XADataSource mockXaDataSource = mock(XADataSource.class);

        // Act – use static mock so no real DB is needed
        try (MockedStatic<XADataSourceFactory> factory = mockStatic(XADataSourceFactory.class)) {
            factory.when(() -> XADataSourceFactory.createXADataSource(any(), any()))
                    .thenReturn(mockXaDataSource);

            HandleUnpooledXAConnectionAction.getInstance().execute(
                    context, connectionDetails, connHash, responseObserver);
        }

        // Assert – a AdmissionControlManager must now be registered for this connHash
        assertNotNull(admissionControlManagers.get(connHash),
                "AdmissionControlManager should be created for unpooled XA connection");
    }
}
