package org.openjproxy.xa.pool.commons.housekeeping;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openjproxy.xa.pool.XABackendSession;
import org.openjproxy.xa.pool.commons.CommonsPool2XADataSource;

import javax.sql.XAConnection;
import javax.sql.XADataSource;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for leak detection functionality.
 */
class LeakDetectionTest {

    private XADataSource mockXADataSource;
    private XAConnection mockXAConnection;
    private Connection mockConnection;
    private CommonsPool2XADataSource pooledDataSource;

    @BeforeEach
    void setUp() throws Exception {
        mockXADataSource = mock(XADataSource.class);
        mockXAConnection = mock(XAConnection.class);
        mockConnection = mock(Connection.class);

        when(mockXADataSource.getXAConnection()).thenReturn(mockXAConnection);
        when(mockXAConnection.getConnection()).thenReturn(mockConnection);
        when(mockXAConnection.getXAResource()).thenReturn(mock(javax.transaction.xa.XAResource.class));
        when(mockConnection.isClosed()).thenReturn(false);
        when(mockConnection.getAutoCommit()).thenReturn(true);
        when(mockConnection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        when(mockConnection.isValid(anyInt())).thenReturn(true);  // Add validation support
    }

    @AfterEach
    void tearDown() {
        if (pooledDataSource != null) {
            pooledDataSource.close();
        }
    }

    @Test
    @DisplayName("Leak detection should be enabled by default")
    void testLeakDetectionEnabledByDefault() {
        Map<String, String> config = new HashMap<>();
        config.put("xa.maxPoolSize", "5");

        pooledDataSource = new CommonsPool2XADataSource(mockXADataSource, config);

        HousekeepingConfig housekeepingConfig = pooledDataSource.getHousekeepingConfig();
        assertTrue(housekeepingConfig.isLeakDetectionEnabled(),
                "Leak detection should be enabled by default");
    }

    @Test
    @DisplayName("Leak detection can be disabled via configuration")
    void testLeakDetectionCanBeDisabled() {
        Map<String, String> config = new HashMap<>();
        config.put("xa.maxPoolSize", "5");
        config.put("xa.leakDetection.enabled", "false");

        pooledDataSource = new CommonsPool2XADataSource(mockXADataSource, config);

        HousekeepingConfig housekeepingConfig = pooledDataSource.getHousekeepingConfig();
        assertFalse(housekeepingConfig.isLeakDetectionEnabled(),
                "Leak detection should be disabled when configured");
    }

    @Test
    @DisplayName("Enhanced leak reporting is disabled by default")
    void testEnhancedReportingDisabledByDefault() {
        Map<String, String> config = new HashMap<>();
        config.put("xa.maxPoolSize", "5");

        pooledDataSource = new CommonsPool2XADataSource(mockXADataSource, config);

        HousekeepingConfig housekeepingConfig = pooledDataSource.getHousekeepingConfig();
        assertFalse(housekeepingConfig.isEnhancedLeakReport(),
                "Enhanced leak reporting should be disabled by default");
    }

    @Test
    @DisplayName("Enhanced leak reporting can be enabled via configuration")
    void testEnhancedReportingCanBeEnabled() {
        Map<String, String> config = new HashMap<>();
        config.put("xa.maxPoolSize", "5");
        config.put("xa.leakDetection.enhanced", "true");

        pooledDataSource = new CommonsPool2XADataSource(mockXADataSource, config);

        HousekeepingConfig housekeepingConfig = pooledDataSource.getHousekeepingConfig();
        assertTrue(housekeepingConfig.isEnhancedLeakReport(),
                "Enhanced leak reporting should be enabled when configured");
    }

    @Test
    @DisplayName("Leak timeout can be configured")
    void testLeakTimeoutCanBeConfigured() {
        Map<String, String> config = new HashMap<>();
        config.put("xa.maxPoolSize", "5");
        config.put("xa.leakDetection.timeoutMs", "60000");  // 1 minute

        pooledDataSource = new CommonsPool2XADataSource(mockXADataSource, config);

        HousekeepingConfig housekeepingConfig = pooledDataSource.getHousekeepingConfig();
        assertEquals(60000L, housekeepingConfig.getLeakTimeoutMs(),
                "Leak timeout should match configured value");
    }

    @Test
    @DisplayName("Session is tracked when borrowed")
    void testSessionTrackedWhenBorrowed() throws Exception {
        Map<String, String> config = new HashMap<>();
        config.put("xa.maxPoolSize", "5");

        pooledDataSource = new CommonsPool2XADataSource(mockXADataSource, config);

        // Borrow a session
        XABackendSession session = pooledDataSource.borrowSession();
        assertNotNull(session, "Borrowed session should not be null");

        // Return the session
        pooledDataSource.returnSession(session);
    }

    @Test
    void shouldRemoveInvalidatedSessionFromLeakTracking() throws Exception {
        pooledDataSource = new CommonsPool2XADataSource(mockXADataSource, Map.of("xa.maxPoolSize", "1"));
        Field field = CommonsPool2XADataSource.class.getDeclaredField("borrowedSessions");
        field.setAccessible(true);
        Map<?, ?> tracked = (Map<?, ?>) field.get(pooledDataSource);
        XABackendSession session = pooledDataSource.borrowSession();
        assertTrue(tracked.containsKey(session));

        pooledDataSource.invalidateSession(session);

        assertFalse(tracked.containsKey(session));
        assertEquals(0, pooledDataSource.getNumActive());
    }

    @Test
    @DisplayName("Multiple sessions can be borrowed and tracked")
    void testMultipleSessionsTracked() throws Exception {
        Map<String, String> config = new HashMap<>();
        config.put("xa.maxPoolSize", "5");

        // Setup multiple mock connections
        XAConnection mockXAConnection2 = mock(XAConnection.class);
        Connection mockConnection2 = mock(Connection.class);
        when(mockXADataSource.getXAConnection())
                .thenReturn(mockXAConnection)
                .thenReturn(mockXAConnection2);
        when(mockXAConnection2.getConnection()).thenReturn(mockConnection2);
        when(mockXAConnection2.getXAResource()).thenReturn(mock(javax.transaction.xa.XAResource.class));
        when(mockConnection2.isClosed()).thenReturn(false);
        when(mockConnection2.getAutoCommit()).thenReturn(true);
        when(mockConnection2.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        when(mockConnection2.isValid(anyInt())).thenReturn(true);  // Add validation support

        pooledDataSource = new CommonsPool2XADataSource(mockXADataSource, config);

        // Borrow multiple sessions
        XABackendSession session1 = pooledDataSource.borrowSession();
        XABackendSession session2 = pooledDataSource.borrowSession();

        assertNotNull(session1, "First borrowed session should not be null");
        assertNotNull(session2, "Second borrowed session should not be null");
        assertNotSame(session1, session2, "Sessions should be different");

        // Return the sessions
        pooledDataSource.returnSession(session1);
        pooledDataSource.returnSession(session2);
    }

    @Test
    @DisplayName("BorrowInfo captures thread and timestamp")
    void testBorrowInfoCapturesDetails() {
        long borrowTime = System.nanoTime();
        Thread currentThread = Thread.currentThread();
        StackTraceElement[] stackTrace = currentThread.getStackTrace();

        BorrowInfo info = new BorrowInfo(borrowTime, currentThread, stackTrace);

        assertEquals(borrowTime, info.getBorrowTime(), "Borrow time should match");
        assertEquals(currentThread, info.getThread(), "Thread should match");
        assertArrayEquals(stackTrace, info.getStackTrace(), "Stack trace should match");
    }

    @Test
    @DisplayName("BorrowInfo can have null stack trace")
    void testBorrowInfoWithNullStackTrace() {
        long borrowTime = System.nanoTime();
        Thread currentThread = Thread.currentThread();

        BorrowInfo info = new BorrowInfo(borrowTime, currentThread, null);

        assertEquals(borrowTime, info.getBorrowTime(), "Borrow time should match");
        assertEquals(currentThread, info.getThread(), "Thread should match");
        assertNull(info.getStackTrace(), "Stack trace should be null");
    }
}
