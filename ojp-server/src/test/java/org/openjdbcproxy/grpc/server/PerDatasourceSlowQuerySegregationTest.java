package org.openjdbcproxy.grpc.server;

import com.google.protobuf.ByteString;
import com.openjdbcproxy.grpc.ConnectionDetails;
import com.openjdbcproxy.grpc.SessionInfo;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.openjdbcproxy.grpc.SerializationHandler;
import org.openjdbcproxy.grpc.server.utils.ConnectionHashGenerator;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to verify that each datasource gets its own SlowQuerySegregationManager
 * with pool sizes based on actual HikariCP configuration.
 */
public class PerDatasourceSlowQuerySegregationTest {

    private StatementServiceImpl statementService;
    private ServerConfiguration serverConfiguration;

    @BeforeEach
    public void setUp() {
        serverConfiguration = new ServerConfiguration();
        SessionManager sessionManager = Mockito.mock(SessionManager.class);
        CircuitBreaker circuitBreaker = Mockito.mock(CircuitBreaker.class);
        
        statementService = new StatementServiceImpl(sessionManager, circuitBreaker, serverConfiguration);
    }

    @Test
    public void testPerDatasourceSlowQuerySegregationManagerCreation() throws Exception {
        // Create properties for both dataSources in the first connection
        Properties clientProperties1 = new Properties();
        clientProperties1.setProperty("ds1.ojp.connection.pool.maximumPoolSize", "10");
        clientProperties1.setProperty("ds1.ojp.connection.pool.minimumIdle", "2");
        clientProperties1.setProperty("ds2.ojp.connection.pool.maximumPoolSize", "20");
        clientProperties1.setProperty("ds2.ojp.connection.pool.minimumIdle", "5");
        byte[] serializedProperties1 = SerializationHandler.serialize(clientProperties1);

        // Create properties for both dataSources in the second connection (same as first)
        Properties clientProperties2 = new Properties();
        clientProperties2.setProperty("ds1.ojp.connection.pool.maximumPoolSize", "10");
        clientProperties2.setProperty("ds1.ojp.connection.pool.minimumIdle", "2");
        clientProperties2.setProperty("ds2.ojp.connection.pool.maximumPoolSize", "20");
        clientProperties2.setProperty("ds2.ojp.connection.pool.minimumIdle", "5");
        byte[] serializedProperties2 = SerializationHandler.serialize(clientProperties2);

        // Create two different connection details with different dataSources
        ConnectionDetails connectionDetails1 = ConnectionDetails.newBuilder()
                .setUrl("jdbc:ojp[localhost:1059>ds1]_h2:mem:test1")
                .setUser("test")
                .setPassword("test")
                .setClientUUID("client-1")
                .setProperties(ByteString.copyFrom(serializedProperties1))
                .build();

        ConnectionDetails connectionDetails2 = ConnectionDetails.newBuilder()
                .setUrl("jdbc:ojp[localhost:1059>ds2]_h2:mem:test2")
                .setUser("test")
                .setPassword("test")
                .setClientUUID("client-2")
                .setProperties(ByteString.copyFrom(serializedProperties2))
                .build();

        // Mock StreamObservers to capture SessionInfo
        StreamObserver<SessionInfo> responseObserver1 = Mockito.mock(StreamObserver.class);
        StreamObserver<SessionInfo> responseObserver2 = Mockito.mock(StreamObserver.class);
        
        // Use ArgumentCaptors to capture the SessionInfo
        ArgumentCaptor<SessionInfo> sessionInfoCaptor1 = ArgumentCaptor.forClass(SessionInfo.class);
        ArgumentCaptor<SessionInfo> sessionInfoCaptor2 = ArgumentCaptor.forClass(SessionInfo.class);

        // Connect with first datasource
        statementService.connect(connectionDetails1, responseObserver1);

        // Connect with second datasource  
        statementService.connect(connectionDetails2, responseObserver2);

        // Verify that onNext was called and capture the SessionInfo
        Mockito.verify(responseObserver1).onNext(sessionInfoCaptor1.capture());
        Mockito.verify(responseObserver2).onNext(sessionInfoCaptor2.capture());
        
        SessionInfo sessionInfo1 = sessionInfoCaptor1.getValue();
        SessionInfo sessionInfo2 = sessionInfoCaptor2.getValue();

        // Use reflection to access the private slowQuerySegregationManagers map
        Field managersField = StatementServiceImpl.class.getDeclaredField("slowQuerySegregationManagers");
        managersField.setAccessible(true);
        
        @SuppressWarnings("unchecked")
        Map<String, SlowQuerySegregationManager> managers = 
                (Map<String, SlowQuerySegregationManager>) managersField.get(statementService);

        // Verify that we have two separate managers
        assertEquals(2, managers.size(), "Should have created separate managers for each datasource");

        // Get the managers for each actual connection hash
        String connHash1 = sessionInfo1.getConnHash();
        String connHash2 = sessionInfo2.getConnHash();

        SlowQuerySegregationManager manager1 = managers.get(connHash1);
        SlowQuerySegregationManager manager2 = managers.get(connHash2);

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
    public void testManagerRetrievalForExistingConnection() throws Exception {
        // Create properties for the testDS dataSource
        Properties clientProperties = new Properties();
        clientProperties.setProperty("testDS.ojp.connection.pool.maximumPoolSize", "15");
        byte[] serializedProperties = SerializationHandler.serialize(clientProperties);

        // Create connection with proper OJP URL format
        ConnectionDetails connectionDetails = ConnectionDetails.newBuilder()
                .setUrl("jdbc:ojp[localhost:1059>testDS]_h2:mem:test")
                .setUser("test")
                .setPassword("test")
                .setClientUUID("client-1")
                .setProperties(ByteString.copyFrom(serializedProperties))
                .build();

        // Mock StreamObserver to capture the SessionInfo
        StreamObserver<SessionInfo> responseObserver = Mockito.mock(StreamObserver.class);
        
        // Use ArgumentCaptor to capture the SessionInfo
        ArgumentCaptor<SessionInfo> sessionInfoCaptor = ArgumentCaptor.forClass(SessionInfo.class);
        
        statementService.connect(connectionDetails, responseObserver);
        
        // Verify that onNext was called and capture the SessionInfo
        Mockito.verify(responseObserver).onNext(sessionInfoCaptor.capture());
        SessionInfo sessionInfo = sessionInfoCaptor.getValue();
        
        // Use the actual connection hash from the SessionInfo
        String connHash = sessionInfo.getConnHash();
        
        java.lang.reflect.Method getManagerMethod = StatementServiceImpl.class
                .getDeclaredMethod("getSlowQuerySegregationManagerForConnection", String.class);
        getManagerMethod.setAccessible(true);
        
        SlowQuerySegregationManager manager = 
                (SlowQuerySegregationManager) getManagerMethod.invoke(statementService, connHash);

        assertNotNull(manager, "Should return existing manager for connection hash");
        assertTrue(manager.isEnabled(), "Manager should be enabled");
        assertEquals(15, manager.getSlotManager().getTotalSlots(), 
                "Manager should have 15 total slots based on pool size");
    }

    @Test
    public void testFallbackManagerForNonExistentConnection() throws Exception {
        // Use reflection to call the private method with non-existent connection hash
        java.lang.reflect.Method getManagerMethod = StatementServiceImpl.class
                .getDeclaredMethod("getSlowQuerySegregationManagerForConnection", String.class);
        getManagerMethod.setAccessible(true);
        
        SlowQuerySegregationManager manager = 
                (SlowQuerySegregationManager) getManagerMethod.invoke(statementService, "non-existent-hash");

        assertNotNull(manager, "Should return fallback manager for non-existent connection hash");
        assertFalse(manager.isEnabled(), "Fallback manager should be disabled");
    }
}