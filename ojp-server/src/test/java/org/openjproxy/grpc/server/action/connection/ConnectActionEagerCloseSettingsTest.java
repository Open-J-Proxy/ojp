package org.openjproxy.grpc.server.action.connection;

import com.openjproxy.grpc.ConnectionDetails;
import com.openjproxy.grpc.SessionInfo;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.openjproxy.grpc.ProtoConverter;
import org.openjproxy.grpc.server.CircuitBreakerRegistry;
import org.openjproxy.grpc.server.ClusterHealthTracker;
import org.openjproxy.grpc.server.MultinodeXaCoordinator;
import org.openjproxy.grpc.server.ServerConfiguration;
import org.openjproxy.grpc.server.SessionManager;
import org.openjproxy.grpc.server.action.ActionContext;
import org.openjproxy.grpc.server.readwrite.ReadWriteDataSourceRegistry;
import org.openjproxy.grpc.server.utils.ConnectionHashGenerator;
import org.openjproxy.xa.pool.spi.XAConnectionPoolProvider;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ConnectActionEagerCloseSettingsTest {

    @Test
    void shouldUseServerDefaultWhenClientPropertiesAreMissing() {
        ActionContext context = newContext(new ServerConfiguration());
        ConnectionDetails details = baseConnectionDetails("jdbc:h2:mem:eager_close_default;DB_CLOSE_DELAY=-1");
        StreamObserver<SessionInfo> observer = mock(StreamObserver.class);

        ConnectAction.getInstance().execute(context, details, observer);

        String connHash = ConnectionHashGenerator.hashConnectionDetails(details);
        assertTrue(context.getStatementEagerCloseEnabledByConnHash().get(connHash));
    }

    @Test
    void shouldApplyClientOverrideWhenEagerCloseIsDisabledPerDatasource() {
        ActionContext context = newContext(new ServerConfiguration());
        Map<String, Object> props = new HashMap<>();
        props.put("ojp.statement.eagerClose.enabled", "false");
        ConnectionDetails details = baseConnectionDetails("jdbc:h2:mem:eager_close_override;DB_CLOSE_DELAY=-1")
                .toBuilder()
                .addAllProperties(ProtoConverter.propertiesToProto(props))
                .build();
        StreamObserver<SessionInfo> observer = mock(StreamObserver.class);

        ConnectAction.getInstance().execute(context, details, observer);

        String connHash = ConnectionHashGenerator.hashConnectionDetails(details);
        assertFalse(context.getStatementEagerCloseEnabledByConnHash().get(connHash));
    }

    private static ActionContext newContext(ServerConfiguration serverConfiguration) {
        Map<String, DataSource> datasourceMap = new ConcurrentHashMap<>();
        SessionManager sessionManager = mock(SessionManager.class);
        CircuitBreakerRegistry circuitBreakerRegistry = new CircuitBreakerRegistry(
                serverConfiguration.getCircuitBreakerTimeout(),
                serverConfiguration.getCircuitBreakerThreshold());

        return new ActionContext(
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
    }

    private static ConnectionDetails baseConnectionDetails(String url) {
        return ConnectionDetails.newBuilder()
                .setUrl(url)
                .setUser("sa")
                .setPassword("")
                .setClientUUID("client-uuid")
                .build();
    }
}
