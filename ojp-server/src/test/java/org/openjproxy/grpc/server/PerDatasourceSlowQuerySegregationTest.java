package org.openjproxy.grpc.server;

import com.openjproxy.grpc.ConnectionDetails;
import com.openjproxy.grpc.SessionInfo;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.openjproxy.grpc.ProtoConverter;
import org.openjproxy.grpc.server.action.ActionContext;
import org.openjproxy.grpc.server.action.transaction.CommandExecutionHelper;
import org.openjproxy.grpc.server.utils.ConnectionHashGenerator;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Test to verify that each datasource gets its own AdmissionControlManager
 * with pool sizes based on actual HikariCP configuration.
 */
class PerDatasourceSlowQuerySegregationTest {

    private StatementServiceImpl statementService;

    @BeforeEach
    void setUp() {
        // Explicitly enable slow query segregation for per-datasource tests
        System.setProperty("ojp.server.slowQuerySegregation.enabled", "true");
        ServerConfiguration serverConfiguration = new ServerConfiguration();
        SessionManager sessionManager = mock(SessionManager.class);
        // Create a real Registry using the configuration
        CircuitBreakerRegistry circuitBreakerRegistry = new CircuitBreakerRegistry(
                serverConfiguration.getCircuitBreakerTimeout(),
                serverConfiguration.getCircuitBreakerThreshold()
        );

        statementService = new StatementServiceImpl(sessionManager, circuitBreakerRegistry, serverConfiguration, new java.util.concurrent.ConcurrentHashMap<>());
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        System.clearProperty("ojp.server.slowQuerySegregation.enabled");
    }

    @Test
    void testPerDatasourceAdmissionControlManagerCreation() throws Exception {
        // Create properties for first connection
        Properties clientProperties1 = new Properties();
        clientProperties1.setProperty("ojp.connection.pool.maximumPoolSize", "10");
        clientProperties1.setProperty("ojp.connection.pool.minimumIdle", "2");
        
        Map<String, Object> propertiesMap1 = new HashMap<>();
        for (String key : clientProperties1.stringPropertyNames()) {
            propertiesMap1.put(key, clientProperties1.getProperty(key));
        }

        // Create properties for second connection
        Properties clientProperties2 = new Properties();
        clientProperties2.setProperty("ojp.connection.pool.maximumPoolSize", "20");
        clientProperties2.setProperty("ojp.connection.pool.minimumIdle", "5");
        
        Map<String, Object> propertiesMap2 = new HashMap<>();
        for (String key : clientProperties2.stringPropertyNames()) {
            propertiesMap2.put(key, clientProperties2.getProperty(key));
        }

        // Create two different connection details with different pool sizes
        ConnectionDetails connectionDetails1 = ConnectionDetails.newBuilder()
                .setUrl("jdbc:h2:mem:test1")
                .setUser("test")
                .setPassword("test")
                .setClientUUID("client-1")
                .addAllProperties(ProtoConverter.propertiesToProto(propertiesMap1))
                .build();

        ConnectionDetails connectionDetails2 = ConnectionDetails.newBuilder()
                .setUrl("jdbc:h2:mem:test2")
                .setUser("test")
                .setPassword("test")
                .setClientUUID("client-2")
                .addAllProperties(ProtoConverter.propertiesToProto(propertiesMap2))
                .build();

        StreamObserver<SessionInfo> responseObserver1 = Mockito.mock(StreamObserver.class);
        StreamObserver<SessionInfo> responseObserver2 = Mockito.mock(StreamObserver.class);

        // Connect with first datasource
        statementService.connect(connectionDetails1, responseObserver1);

        // Connect with second datasource  
        statementService.connect(connectionDetails2, responseObserver2);

        Field actionContextField = StatementServiceImpl.class.getDeclaredField("actionContext");
        actionContextField.setAccessible(true);
        ActionContext actionContext = (ActionContext) actionContextField.get(statementService);

        Map<String, AdmissionControlManager> managers = actionContext.getAdmissionControlManagers();

        // Verify that we have two separate managers
        assertEquals(2, managers.size(), "Should have created separate managers for each datasource");

        // Get the managers for each connection hash
        String connHash1 = ConnectionHashGenerator.hashConnectionDetails(connectionDetails1);
        String connHash2 = ConnectionHashGenerator.hashConnectionDetails(connectionDetails2);

        AdmissionControlManager manager1 = managers.get(connHash1);
        AdmissionControlManager manager2 = managers.get(connHash2);

        assertNotNull(manager1, "Manager for first datasource should exist");
        assertNotNull(manager2, "Manager for second datasource should exist");
        assertNotSame(manager1, manager2, "Managers should be different instances");

        // Verify that both managers are enabled (based on default configuration)
        assertTrue(manager1.isEnabled(), "Manager 1 should be enabled");
        assertTrue(manager2.isEnabled(), "Manager 2 should be enabled");

        // Verify that each manager has slot manager with appropriate pool sizes
        assertNotNull(manager1.getSlotManager(), "Manager 1 should have slot manager");
        assertNotNull(manager2.getSlotManager(), "Manager 2 should have slot manager");

        // Pool sizes should match the configured values (10 and 20)
        assertEquals(10, manager1.getSlotManager().getTotalSlots(), 
                "Manager 1 should have 10 total slots based on pool size");
        assertEquals(20, manager2.getSlotManager().getTotalSlots(), 
                "Manager 2 should have 20 total slots based on pool size");

        // Verify slot distribution (20% slow, 80% fast by default)
        assertEquals(2, manager1.getSlotManager().getSlowSlots(), 
                "Manager 1 should have 2 slow slots (20% of 10)");
        assertEquals(8, manager1.getSlotManager().getFastSlots(), 
                "Manager 1 should have 8 fast slots (80% of 10)");

        assertEquals(4, manager2.getSlotManager().getSlowSlots(), 
                "Manager 2 should have 4 slow slots (20% of 20)");
        assertEquals(16, manager2.getSlotManager().getFastSlots(), 
                "Manager 2 should have 16 fast slots (80% of 20)");
    }

    @Test
    void testManagerRetrievalForExistingConnection() throws Exception {
        // Create properties
        Properties clientProperties = new Properties();
        clientProperties.setProperty("ojp.connection.pool.maximumPoolSize", "15");
        
        Map<String, Object> propertiesMap = new HashMap<>();
        for (String key : clientProperties.stringPropertyNames()) {
            propertiesMap.put(key, clientProperties.getProperty(key));
        }

        // Create connection
        ConnectionDetails connectionDetails = ConnectionDetails.newBuilder()
                .setUrl("jdbc:h2:mem:test")
                .setUser("test")
                .setPassword("test")
                .setClientUUID("client-1")
                .addAllProperties(ProtoConverter.propertiesToProto(propertiesMap))
                .build();

        StreamObserver<SessionInfo> responseObserver = Mockito.mock(StreamObserver.class);
        statementService.connect(connectionDetails, responseObserver);

        // Use reflection to call the private method to get manager
        String connHash = ConnectionHashGenerator.hashConnectionDetails(connectionDetails);

        Field actionContextField = StatementServiceImpl.class.getDeclaredField("actionContext");
        actionContextField.setAccessible(true);
        ActionContext actionContext = (ActionContext) actionContextField.get(statementService);

        java.lang.reflect.Method getManagerMethod = CommandExecutionHelper.class
                .getDeclaredMethod("getAdmissionControlManagerForConnection", ActionContext.class, String.class);
        getManagerMethod.setAccessible(true);

        AdmissionControlManager manager =
                (AdmissionControlManager) getManagerMethod.invoke(null, actionContext, connHash);

        assertNotNull(manager, "Should return existing manager for connection hash");
        assertTrue(manager.isEnabled(), "Manager should be enabled");
        assertEquals(15, manager.getSlotManager().getTotalSlots(), 
                "Manager should have 15 total slots based on pool size");
    }

    @Test
    void testFallbackManagerForNonExistentConnection() throws Exception {
        Field actionContextField = StatementServiceImpl.class.getDeclaredField("actionContext");
        actionContextField.setAccessible(true);
        ActionContext actionContext = (ActionContext) actionContextField.get(statementService);

        // Use reflection to call the private static method with non-existent connection hash
        java.lang.reflect.Method getManagerMethod = CommandExecutionHelper.class
                .getDeclaredMethod("getAdmissionControlManagerForConnection", ActionContext.class, String.class);
        getManagerMethod.setAccessible(true);
        
        AdmissionControlManager manager =
                (AdmissionControlManager) getManagerMethod.invoke(null, actionContext, "non-existent-hash");

        assertNotNull(manager, "Should return fallback manager for non-existent connection hash");
        assertFalse(manager.isEnabled(), "Fallback manager should be disabled");
    }

    @Test
    void shouldUseConfiguredSlowQuerySlotTimeoutsWhenSegregationEnabled() throws Exception {
        String originalSqsEnabled = System.getProperty("ojp.server.slowQuerySegregation.enabled");
        String originalFastSlotTimeout = System.getProperty("ojp.server.slowQuerySegregation.fastSlotTimeout");
        String originalSlowSlotTimeout = System.getProperty("ojp.server.slowQuerySegregation.slowSlotTimeout");

        System.setProperty("ojp.server.slowQuerySegregation.enabled", "true");
        System.setProperty("ojp.server.slowQuerySegregation.fastSlotTimeout", "9100");
        System.setProperty("ojp.server.slowQuerySegregation.slowSlotTimeout", "17300");

        try {
            ServerConfiguration localConfig = new ServerConfiguration();
            SessionManager localSessionManager = mock(SessionManager.class);
            CircuitBreakerRegistry localCircuitBreakerRegistry = new CircuitBreakerRegistry(
                    localConfig.getCircuitBreakerTimeout(),
                    localConfig.getCircuitBreakerThreshold()
            );
            StatementServiceImpl localStatementService = new StatementServiceImpl(
                    localSessionManager,
                    localCircuitBreakerRegistry,
                    localConfig,
                    new java.util.concurrent.ConcurrentHashMap<>()
            );

            Properties clientProperties = new Properties();
            clientProperties.setProperty("ojp.connection.pool.maximumPoolSize", "6");
            clientProperties.setProperty("ojp.connection.pool.connectionTimeout", "250");

            Map<String, Object> propertiesMap = new HashMap<>();
            for (String key : clientProperties.stringPropertyNames()) {
                propertiesMap.put(key, clientProperties.getProperty(key));
            }

            ConnectionDetails connectionDetails = ConnectionDetails.newBuilder()
                    .setUrl("jdbc:h2:mem:test-timeout-precedence")
                    .setUser("test")
                    .setPassword("test")
                    .setClientUUID("client-timeout-precedence")
                    .addAllProperties(ProtoConverter.propertiesToProto(propertiesMap))
                    .build();

            StreamObserver<SessionInfo> responseObserver = Mockito.mock(StreamObserver.class);
            localStatementService.connect(connectionDetails, responseObserver);

            Field actionContextField = StatementServiceImpl.class.getDeclaredField("actionContext");
            actionContextField.setAccessible(true);
            ActionContext actionContext = (ActionContext) actionContextField.get(localStatementService);

            String connHash = ConnectionHashGenerator.hashConnectionDetails(connectionDetails);
            AdmissionControlManager manager = actionContext.getAdmissionControlManagers().get(connHash);

            assertNotNull(manager, "Manager should exist for connected datasource");

            long fastTimeout = manager.getFastSlotTimeoutMs();
            long slowTimeout = manager.getSlowSlotTimeoutMs();

            assertEquals(9100L, fastTimeout, "Fast slot timeout should come from slow query segregation setting");
            assertEquals(17300L, slowTimeout, "Slow slot timeout should come from slow query segregation setting");
        } finally {
            restoreSystemProperty("ojp.server.slowQuerySegregation.enabled", originalSqsEnabled);
            restoreSystemProperty("ojp.server.slowQuerySegregation.fastSlotTimeout", originalFastSlotTimeout);
            restoreSystemProperty("ojp.server.slowQuerySegregation.slowSlotTimeout", originalSlowSlotTimeout);
        }
    }

    private static void restoreSystemProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
