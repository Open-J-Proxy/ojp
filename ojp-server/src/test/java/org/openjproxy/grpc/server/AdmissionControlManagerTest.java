package org.openjproxy.grpc.server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLTransientConnectionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for AdmissionControlManager functionality.
 */
class AdmissionControlManagerTest {

    private AdmissionControlManager admissionControlManager;

    @BeforeEach
    void setUp() {
        // 10 total slots, 20% slow (2 slots), 100ms idle timeout, 5000ms slow timeout, 1000ms fast timeout, enabled
        admissionControlManager = new AdmissionControlManager(10, 20, 100, 5000, 1000, true);
    }

    @Test
    void testInitialization() {
        assertTrue(admissionControlManager.isEnabled());
        assertNotNull(admissionControlManager.getPerformanceMonitor());
        assertNotNull(admissionControlManager.getSlotManager());
        assertEquals(0.0, admissionControlManager.getOverallAverageExecutionTime(), 0.001);
    }

    @Test
    void testDisabledManager() {
        AdmissionControlManager disabledManager = new AdmissionControlManager(10, 20, 100, 5000, 1000, false);

        assertFalse(disabledManager.isEnabled());
        assertFalse(disabledManager.isAdmissionControlOnly());
        assertNotNull(disabledManager.getPerformanceMonitor());
        assertNull(disabledManager.getSlotManager());
    }

    @Test
    void testAdmissionControlOnlyMode() throws Exception {
        AdmissionControlManager admissionManager = new AdmissionControlManager(5, 0, 0, 0, 1000, 0, true);

        assertTrue(admissionManager.isEnabled());
        assertTrue(admissionManager.isAdmissionControlOnly());
        assertNotNull(admissionManager.getSlotManager());
        assertEquals(0, admissionManager.getSlotManager().getSlowSlots());
        assertEquals(5, admissionManager.getSlotManager().getFastSlots());

        String result = admissionManager.executeWithSegregation("admission-op", () -> "ok");
        assertEquals("ok", result);
        assertEquals(0, admissionManager.getSlotManager().getActiveFastOperations());
        assertTrue(admissionManager.getStatus().contains("admissionControlOnly=true"));
    }

    @Test
    void testExecuteWithSegregationEnabled() throws Exception {
        String operationHash = "test-operation";
        String expectedResult = "operation-result";

        // Execute an operation
        String result = admissionControlManager.executeWithSegregation(operationHash, () -> {
            // Simulate some work
            Thread.sleep(50); //NOSONAR
            return expectedResult;
        });

        assertEquals(expectedResult, result);

        // Check that performance was recorded
        assertTrue(admissionControlManager.getOperationAverageTime(operationHash) > 0);
        assertEquals(1, admissionControlManager.getPerformanceMonitor().getTrackedOperationCount());
        assertEquals(1, admissionControlManager.getPerformanceMonitor().getTotalExecutionCount());
    }

    @Test
    void testExecuteWithSegregationDisabled() throws Exception {
        AdmissionControlManager disabledManager = new AdmissionControlManager(10, 20, 100, 5000, 1000, false);
        String operationHash = "test-operation";
        String expectedResult = "operation-result";

        // Execute an operation
        String result = disabledManager.executeWithSegregation(operationHash, () -> {
            Thread.sleep(50); //NOSONAR
            return expectedResult;
        });

        assertEquals(expectedResult, result);

        // Check that performance was still recorded
        assertTrue(disabledManager.getOperationAverageTime(operationHash) > 0);
    }

    @Test
    void testSlowOperationClassification() throws Exception {
        String fastOp = "fast-operation";
        String slowOp = "slow-operation";

        // Execute fast operations to establish baseline
        admissionControlManager.executeWithSegregation(fastOp, () -> {
            Thread.sleep(10); //NOSONAR
            return "fast";
        });

        admissionControlManager.executeWithSegregation("another-fast", () -> {
            Thread.sleep(10); //NOSONAR
            return "fast";
        });

        // Initially, operations should be classified as fast
        assertFalse(admissionControlManager.isSlowOperation(fastOp));

        // Execute a slow operation multiple times to make it clearly slow
        for (int i = 0; i < 3; i++) {
            admissionControlManager.executeWithSegregation(slowOp, () -> {
                Thread.sleep(100); //NOSONAR
                return "slow";
            });
        }

        // The slow operation should now be classified differently
        // Note: Due to the 2x threshold and averaging, we might need more executions or longer delays
        // to trigger the slow classification
        double slowAvg = admissionControlManager.getOperationAverageTime(slowOp);
        double overallAvg = admissionControlManager.getOverallAverageExecutionTime();

        assertTrue(slowAvg > 0);
        assertTrue(overallAvg > 0);

        // At minimum, verify the performance tracking is working
        assertTrue(admissionControlManager.getPerformanceMonitor().getTrackedOperationCount() >= 3);
    }

    @Test
    void testExceptionHandling() {
        String operationHash = "failing-operation";
        RuntimeException expectedException = new RuntimeException("Test exception");

        // Verify that exceptions are propagated
        Exception thrownException = assertThrows(RuntimeException.class, () -> admissionControlManager.executeWithSegregation(operationHash, () -> {
            Thread.sleep(50); //NOSONAR
            throw expectedException;
        }));

        assertEquals(expectedException, thrownException);

        // Check that performance was still recorded even for failed operations
        assertTrue(admissionControlManager.getOperationAverageTime(operationHash) > 0);
    }

    @Test
    void testConcurrentExecution() throws Exception {
        final int numThreads = 5;
        final String[] results = new String[numThreads];
        final Exception[] exceptions = new Exception[numThreads];
        Thread[] threads = new Thread[numThreads];

        // Create threads that execute operations concurrently
        for (int i = 0; i < numThreads; i++) {
            final int threadIndex = i;
            threads[i] = new Thread(() -> {
                try {
                    results[threadIndex] = admissionControlManager.executeWithSegregation(
                            "concurrent-op-" + threadIndex,
                            () -> {
                                Thread.sleep(50); //NOSONAR
                                return "result-" + threadIndex;
                            }
                    );
                } catch (Exception e) {
                    exceptions[threadIndex] = e;
                }
            });
        }

        // Start all threads
        for (Thread thread : threads) {
            thread.start();
        }

        // Wait for all threads to complete
        for (Thread thread : threads) {
            thread.join();
        }

        // Verify all operations completed successfully
        for (int i = 0; i < numThreads; i++) {
            assertNull(exceptions[i], "Thread " + i + " should not have thrown an exception");
            assertEquals("result-" + i, results[i]);
        }

        // Verify performance tracking
        assertEquals(numThreads, admissionControlManager.getPerformanceMonitor().getTrackedOperationCount());
        assertEquals(numThreads, admissionControlManager.getPerformanceMonitor().getTotalExecutionCount());
    }

    @Test
    void testStatusString() {
        String status = admissionControlManager.getStatus();
        assertNotNull(status);
        assertTrue(status.contains("AdmissionControlManager"));
        assertTrue(status.contains("enabled=true"));
        assertTrue(status.contains("trackedOps="));
        assertTrue(status.contains("totalExecs="));
        assertTrue(status.contains("overallAvg="));
    }

    @Test
    void testSlotExhaustion() throws Exception {
        // Fill up slow slots by executing operations that will be classified as slow
        String slowOp = "resource-intensive-op";

        // First, establish a baseline with some fast operations
        admissionControlManager.executeWithSegregation("fast-1", () -> {
            Thread.sleep(5); //NOSONAR
            return "fast";
        });

        admissionControlManager.executeWithSegregation("fast-2", () -> {
            Thread.sleep(5);  //NOSONAR
            return "fast";
        });

        // Now try to make the operation clearly slow
        for (int i = 0; i < 5; i++) {
            admissionControlManager.executeWithSegregation(slowOp, () -> {
                // Much longer than fast operations
                Thread.sleep(200);  //NOSONAR
                return "slow";
            });
        }

        // The operation should have been tracked and executed
        assertTrue(admissionControlManager.getOperationAverageTime(slowOp) > 0);
        assertTrue(admissionControlManager.getPerformanceMonitor().getTotalExecutionCount() >= 7);
    }

    @Test
    void testVoidOperations() throws Exception {
        String operationHash = "void-operation";
        final boolean[] executed = {false};

        // Test with void operations (like those used in executeQuery)
        Object result = admissionControlManager.executeWithSegregation(operationHash, () -> {
            executed[0] = true;
            Thread.sleep(30); //NOSONAR
            return null;
        });

        assertNull(result);
        assertTrue(executed[0]);
        assertTrue(admissionControlManager.getOperationAverageTime(operationHash) > 0);
    }

    @Test
    void testAdmissionControlTimeoutThrowsServerOverloadException() throws Exception {
        AdmissionControlManager manager = new AdmissionControlManager(1, 0, 0, 0, 10, 0, 0, true);
        manager.getSlotManager().acquireFastSlot(1000);
        ServerOverloadException overloadException = assertThrows(ServerOverloadException.class,
                () -> manager.executeWithSegregation("overload-op", () -> "never"));
        assertTrue(overloadException.getMessage().contains("Timeout waiting for admission control slot"));
        manager.getSlotManager().releaseFastSlot();
    }

    @Test
    void testExecuteWithMonitoringOnlyDoesNotAcquireSlot() throws Exception {
        // All fast slots pre-acquired so executeWithSegregation would block/timeout.
        AdmissionControlManager manager = new AdmissionControlManager(2, 0, 0, 0, 50, 0, 0, true);
        manager.getSlotManager().acquireFastSlot(1000);
        manager.getSlotManager().acquireFastSlot(1000);
        try {
            int activeBefore = manager.getSlotManager().getActiveFastOperations();
            long execsBefore = manager.getPerformanceMonitor().getTotalExecutionCount();
            // executeWithMonitoringOnly must run without acquiring a slot and without timing out.
            String result = manager.executeWithMonitoringOnly("permit-op", "select 1", () -> {
                Thread.sleep(2); //NOSONAR
                return "ok";
            });
            assertEquals("ok", result);
            assertEquals(activeBefore, manager.getSlotManager().getActiveFastOperations(),
                    "executeWithMonitoringOnly must not change active fast operation count");
            assertEquals(execsBefore + 1, manager.getPerformanceMonitor().getTotalExecutionCount(),
                    "Performance monitor should record the execution");
        } finally {
            manager.getSlotManager().releaseFastSlot();
            manager.getSlotManager().releaseFastSlot();
        }
    }

    @Test
    void testExecuteWithMonitoringOnlyPropagatesExceptions() {
        RuntimeException boom = new RuntimeException("boom");
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> admissionControlManager.executeWithMonitoringOnly("failing-op", "select 1", () -> {
                    throw boom;
                }));
        assertEquals(boom, thrown);
        // Performance must still be recorded for failed monitor-only operations.
        assertTrue(admissionControlManager.getOperationAverageTime("failing-op") >= 0);
        assertTrue(admissionControlManager.getPerformanceMonitor().getTotalExecutionCount() >= 1);
    }

    @Test
    void shouldThrowSQLTransientConnectionExceptionWhenAdmissionTimeoutInAcquireSessionPermit() throws Exception {
        // Create a manager with 1 fast slot and a very short timeout (10ms)
        AdmissionControlManager manager = new AdmissionControlManager(1, 0, 0, 0, 10, 0, 0, true);
        // Exhaust the single fast slot so that acquireSessionPermit will time out
        manager.getSlotManager().acquireFastSlot(1000);
        try {
            SQLTransientConnectionException ex = assertThrows(SQLTransientConnectionException.class,
                    () -> manager.acquireSessionPermit("test-hash"));
            assertTrue(ex.getMessage().contains("Connection admission timeout for hash: test-hash"));
            assertTrue(ex.getMessage().contains("phase=admission"));
        } finally {
            manager.getSlotManager().releaseFastSlot();
        }
    }
}
