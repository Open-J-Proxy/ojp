package org.openjproxy.grpc.server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for QueryPerformanceMonitor functionality.
 */
class QueryPerformanceMonitorTest {

    private QueryPerformanceMonitor monitor;

    @BeforeEach
    void setUp() {
        monitor = new QueryPerformanceMonitor();
    }

    @Test
    void testInitialState() {
        assertEquals(0, monitor.getTrackedOperationCount());
        assertEquals(0, monitor.getTotalExecutionCount());
        assertEquals(0.0, monitor.getOverallAverageExecutionTime(), 0.001);
    }

    @Test
    void testRecordMultipleExecutionsSameOperationUsesEwma() {
        String operationHash = "test-hash-1";

        // First sample sets initial average to 100
        monitor.recordExecutionTime(operationHash, 100.0);
        monitor.recordExecutionTime(operationHash, 200.0); // ((100*4)+200)/5 = 120
        monitor.recordExecutionTime(operationHash, 50.0);  // ((120*4)+50)/5 = 106

        assertEquals(106.0, monitor.getOperationAverageTime(operationHash), 0.001);
        assertEquals(1, monitor.getTrackedOperationCount());
        assertEquals(3, monitor.getTotalExecutionCount());
    }

    @Test
    void testDefaultClassificationModeIsRelativeFastBaseline() {
        assertEquals(SlowQueryClassificationMode.RELATIVE_FAST_BASELINE, monitor.getClassificationMode());
    }

    @Test
    void testUnknownOperationIsNotSlow() {
        assertFalse(monitor.isSlowOperation("unknown-op"));
    }

    @Test
    void testMinSamplesBlocksClassification() {
        QueryPerformanceMonitor localMonitor = new QueryPerformanceMonitor(
                0L, SlowQueryClassificationMode.RELATIVE_FAST_BASELINE, 1000L,
                100L, 5.0, 3.0, 20, 50, 10L);
        localMonitor.recordExecutionTime("fast", 10.0);
        localMonitor.recordExecutionTime("slow", 1000.0);

        assertFalse(localMonitor.isSlowOperation("slow"));
    }

    @Test
    void testAbsoluteThresholdClassificationBelowEqualAboveAndUnknown() {
        QueryPerformanceMonitor absoluteMonitor = new QueryPerformanceMonitor(
                0L, SlowQueryClassificationMode.ABSOLUTE_THRESHOLD, 1000L,
                100L, 5.0, 3.0, 1, 50, 10L);

        absoluteMonitor.recordExecutionTime("below", 999.0);
        absoluteMonitor.recordExecutionTime("equal", 1000.0);
        absoluteMonitor.recordExecutionTime("above", 1500.0);

        assertFalse(absoluteMonitor.isSlowOperation("below"));
        assertTrue(absoluteMonitor.isSlowOperation("equal"));
        assertTrue(absoluteMonitor.isSlowOperation("above"));
        assertFalse(absoluteMonitor.isSlowOperation("unknown"));
    }

    @Test
    void testTwoQueryShapeRegressionRelativeFastBaseline() {
        QueryPerformanceMonitor relativeMonitor = new QueryPerformanceMonitor(
                0L, SlowQueryClassificationMode.RELATIVE_FAST_BASELINE, 1000L,
                100L, 5.0, 3.0, 20, 50, 10L);

        for (int i = 0; i < 20; i++) {
            relativeMonitor.recordExecutionTime("fast", 10.0);
            relativeMonitor.recordExecutionTime("slow", 1000.0);
        }

        assertTrue(relativeMonitor.isSlowOperation("slow"));
        assertFalse(relativeMonitor.isSlowOperation("fast"));
    }

    @Test
    void testHysteresisEnterRemainRecover() {
        QueryPerformanceMonitor relativeMonitor = new QueryPerformanceMonitor(
                0L, SlowQueryClassificationMode.RELATIVE_FAST_BASELINE, 1000L,
                35L, 5.0, 3.0, 1, 50, 10L);

        relativeMonitor.recordExecutionTime("fast", 10.0);
        relativeMonitor.recordExecutionTime("candidate", 50.0);

        assertTrue(relativeMonitor.isSlowOperation("candidate")); // enter slow at baseline*5

        relativeMonitor.recordExecutionTime("candidate", 2.5); // avg becomes 40.5
        assertTrue(relativeMonitor.isSlowOperation("candidate")); // remain slow (between baseline*3 and baseline*5)

        relativeMonitor.recordExecutionTime("candidate", 1.0); // avg becomes 32.6
        assertFalse(relativeMonitor.isSlowOperation("candidate")); // recover below minimumSlowQueryMs
    }

    @Test
    void testExcludeAlreadySlowOperationsFromBaseline() {
        QueryPerformanceMonitor relativeMonitor = new QueryPerformanceMonitor(
                0L, SlowQueryClassificationMode.RELATIVE_FAST_BASELINE, 1000L,
                0L, 5.0, 3.0, 1, 50, 10L);

        relativeMonitor.recordExecutionTime("fast", 10.0);
        relativeMonitor.recordExecutionTime("slow", 1000.0);
        assertTrue(relativeMonitor.isSlowOperation("slow")); // mark as currently slow

        relativeMonitor.recordExecutionTime("candidate", 60.0);
        assertTrue(relativeMonitor.isSlowOperation("candidate"));
    }

    @Test
    void testNoEligibleBaselineReturnsFalse() {
        QueryPerformanceMonitor relativeMonitor = new QueryPerformanceMonitor(
                0L, SlowQueryClassificationMode.RELATIVE_FAST_BASELINE, 1000L,
                100L, 5.0, 3.0, 20, 50, 10L);

        relativeMonitor.recordExecutionTime("only-op", 1000.0);

        assertFalse(relativeMonitor.isSlowOperation("only-op"));
    }

    @Test
    void testInvalidInputHandling() {
        monitor.recordExecutionTime(null, 100.0);
        monitor.recordExecutionTime("test", -10.0);

        assertEquals(0, monitor.getTrackedOperationCount());
        assertEquals(0.0, monitor.getOperationAverageTime("non-existent"), 0.001);
    }

    @Test
    void testClear() {
        monitor.recordExecutionTime("op1", 100.0);
        monitor.recordExecutionTime("op2", 200.0);

        monitor.clear();

        assertEquals(0, monitor.getTrackedOperationCount());
        assertEquals(0, monitor.getTotalExecutionCount());
        assertEquals(0.0, monitor.getOverallAverageExecutionTime(), 0.001);
        assertEquals(0.0, monitor.getFastBaselineMs(), 0.001);
    }
}
