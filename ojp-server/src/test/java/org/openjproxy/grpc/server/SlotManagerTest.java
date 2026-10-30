package org.openjproxy.grpc.server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for SlotManager functionality.
 */
 class SlotManagerTest {

    private SlotManager slotManager;

    @BeforeEach
     void setUp() {
        // 10 total slots, 20% slow (2 slots), 80% fast (8 slots), 100ms idle timeout
        slotManager = new SlotManager(10, 20, 100);
    }

    @Test
     void testInitialization() {
        assertEquals(10, slotManager.getTotalSlots());
        assertEquals(2, slotManager.getSlowSlots());
        assertEquals(8, slotManager.getFastSlots());
        assertEquals(100, slotManager.getIdleTimeoutMs());
        assertTrue(slotManager.isEnabled());
        assertEquals(0, slotManager.getActiveSlowOperations());
        assertEquals(0, slotManager.getActiveFastOperations());
    }

    @Test
     void testInvalidConfigurationHandling() {
        // Test invalid total slots
        assertThrows(IllegalArgumentException.class, () -> new SlotManager(0, 20, 100));
        assertThrows(IllegalArgumentException.class, () -> new SlotManager(-1, 20, 100));

        // Test invalid percentage
        assertThrows(IllegalArgumentException.class, () -> new SlotManager(10, -1, 100));
        assertThrows(IllegalArgumentException.class, () -> new SlotManager(10, 101, 100));

        // Test invalid timeout
        assertThrows(IllegalArgumentException.class, () -> new SlotManager(10, 20, -1));
        assertThrows(IllegalArgumentException.class, () -> new SlotManager(10, 20, 100, -1));
    }

    @Test
     void testSlowSlotAcquisitionAndRelease() throws InterruptedException {
        // Acquire a slow slot
        assertTrue(slotManager.acquireSlowSlot(1000));
        assertEquals(1, slotManager.getActiveSlowOperations());

        // Acquire another slow slot
        assertTrue(slotManager.acquireSlowSlot(1000));
        assertEquals(2, slotManager.getActiveSlowOperations());

        // Try to acquire a third slow slot (should fail as we only have 2)
        assertFalse(slotManager.acquireSlowSlot(100));
        assertEquals(2, slotManager.getActiveSlowOperations());

        // Release one slow slot
        slotManager.releaseSlowSlot();
        assertEquals(1, slotManager.getActiveSlowOperations());

        // Should be able to acquire again
        assertTrue(slotManager.acquireSlowSlot(1000));
        assertEquals(2, slotManager.getActiveSlowOperations());

        // Release all
        slotManager.releaseSlowSlot();
        slotManager.releaseSlowSlot();
        assertEquals(0, slotManager.getActiveSlowOperations());
    }

    @Test
     void testFastSlotAcquisitionAndRelease() throws InterruptedException {
        // Acquire fast slots up to the limit (8)
        for (int i = 0; i < 8; i++) {
            assertTrue(slotManager.acquireFastSlot(1000));
            assertEquals(i + 1, slotManager.getActiveFastOperations());
        }

        // Try to acquire one more (should fail)
        assertFalse(slotManager.acquireFastSlot(100));
        assertEquals(8, slotManager.getActiveFastOperations());

        // Release one and try again
        slotManager.releaseFastSlot();
        assertEquals(7, slotManager.getActiveFastOperations());

        assertTrue(slotManager.acquireFastSlot(1000));
        assertEquals(8, slotManager.getActiveFastOperations());

        // Release all
        for (int i = 0; i < 8; i++) {
            slotManager.releaseFastSlot();
        }
        assertEquals(0, slotManager.getActiveFastOperations());
    }

    @Test
     void testSlotBorrowingFastToSlow() throws InterruptedException {
        // Fill up slow slots
        assertTrue(slotManager.acquireSlowSlot(1000));
        assertTrue(slotManager.acquireSlowSlot(1000));
        assertEquals(2, slotManager.getActiveSlowOperations());

        // Try to acquire another slow slot immediately (should fail - no idle time yet)
        assertFalse(slotManager.acquireSlowSlot(100));

        // Wait for idle timeout and try again (should still fail because fast slots aren't idle)
        Thread.sleep(150); //NOSONAR
        assertFalse(slotManager.acquireSlowSlot(100));

        // Release slow slots for cleanup
        slotManager.releaseSlowSlot();
        slotManager.releaseSlowSlot();
    }

    @Test
     void testSlotBorrowingSlowToFast() throws InterruptedException {
        // Fill up fast slots
        for (int i = 0; i < 8; i++) {
            assertTrue(slotManager.acquireFastSlot(1000));
        }
        assertEquals(8, slotManager.getActiveFastOperations());

        // Try to acquire another fast slot immediately (should fail - no idle time yet)
        assertFalse(slotManager.acquireFastSlot(100));

        // Wait for idle timeout and try again (should still fail because slow slots aren't idle)
        Thread.sleep(150);//NOSONAR
        assertFalse(slotManager.acquireFastSlot(100));

        // Release fast slots for cleanup
        for (int i = 0; i < 8; i++) {
            slotManager.releaseFastSlot();
        }
    }

    @Test
     void testDisabledSlotManager() throws InterruptedException {
        SlotManager disabledManager = new SlotManager(5, 20, 100);
        disabledManager.setEnabled(false);

        assertFalse(disabledManager.isEnabled());

        // When disabled, all acquisitions should succeed
        for (int i = 0; i < 20; i++) { // Try to acquire more than the limit
            assertTrue(disabledManager.acquireSlowSlot(100));
            assertTrue(disabledManager.acquireFastSlot(100));
        }

        // Releasing should also work (but be no-ops)
        for (int i = 0; i < 20; i++) {
            disabledManager.releaseSlowSlot();
            disabledManager.releaseFastSlot();
        }
    }

    @Test
     void testEdgeCaseConfigurations() {
        // Test 100% slow slots
        SlotManager allSlowManager = new SlotManager(10, 100, 100);
        assertEquals(10, allSlowManager.getSlowSlots());
        assertEquals(0, allSlowManager.getFastSlots());

        // Test 0% slow slots (all slots become fast for admission-control-only mode)
        SlotManager noSlowManager = new SlotManager(10, 0, 100);
        assertEquals(0, noSlowManager.getSlowSlots());
        assertEquals(10, noSlowManager.getFastSlots());

        // Test single slot
        SlotManager singleSlotManager = new SlotManager(1, 50, 100);
        assertEquals(1, singleSlotManager.getSlowSlots());
        assertEquals(0, singleSlotManager.getFastSlots());
    }

    @Test
     void testStatusString() {
        String status = slotManager.getStatus();
        assertNotNull(status);
        assertTrue(status.contains("SlotManager"));
        assertTrue(status.contains("total=10"));
        assertTrue(status.contains("slow=0/2"));
        assertTrue(status.contains("fast=0/8"));
        assertTrue(status.contains("enabled=true"));
    }

    @Test
    void testQueueDepthLimitFailsFastForWaitingRequests() throws InterruptedException {
        SlotManager singleSlotManager = new SlotManager(1, 0, 100, 1);
        assertEquals(1, singleSlotManager.getMaxWaitQueueDepth());
        assertTrue(singleSlotManager.acquireFastSlot(1000));

        CountDownLatch waiterStarted = new CountDownLatch(1);
        Thread waitingThread = new Thread(() -> {
            waiterStarted.countDown();
            try {
                singleSlotManager.acquireFastSlot(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        waitingThread.start();

        assertTrue(waiterStarted.await(1, TimeUnit.SECONDS));
        long waitStart = System.nanoTime();
        while (waitingThread.getState() != Thread.State.TIMED_WAITING
                && TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - waitStart) < 1000) {
            Thread.yield(); //NOSONAR - test-only spin wait to observe waiter state before fail-fast assertion
        }

        assertFalse(singleSlotManager.acquireFastSlot(1000));

        singleSlotManager.releaseFastSlot();
        waitingThread.join();
    }

    @Test
     void testConcurrentSlotAcquisition() throws InterruptedException {
        // Create a SlotManager with very long idle timeout to prevent borrowing during test
        SlotManager testSlotManager = new SlotManager(10, 20, 60000); // 60 second idle timeout
        
        final int numThreads = 5;
        final boolean[] results = new boolean[numThreads];
        Thread[] threads = new Thread[numThreads];

        // Create threads that try to acquire slow slots concurrently
        for (int i = 0; i < numThreads; i++) {
            final int threadIndex = i;
            threads[i] = new Thread(() -> {
                try {
                    results[threadIndex] = testSlotManager.acquireSlowSlot(1000);
                } catch (InterruptedException e) {
                    results[threadIndex] = false;
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

        // Count successful acquisitions
        int successCount = 0;
        for (boolean result : results) {
            if (result) successCount++;
        }

        // Should only have 2 successful acquisitions (our slow slot limit)
        assertEquals(2, successCount);
        assertEquals(2, testSlotManager.getActiveSlowOperations());

        // Clean up
        testSlotManager.releaseSlowSlot();
        testSlotManager.releaseSlowSlot();
    }

    @Test
     void testSuccessfulBorrowingFromFastToSlow() throws InterruptedException {
        // Create a SlotManager with short idle timeout for testing
        SlotManager testSlotManager = new SlotManager(10, 20, 50); // 50ms idle timeout
        
        // Fill up slow slots
        assertTrue(testSlotManager.acquireSlowSlot(1000));
        assertTrue(testSlotManager.acquireSlowSlot(1000));
        assertEquals(2, testSlotManager.getActiveSlowOperations());
        
        // Establish activity in fast pool first
        assertTrue(testSlotManager.acquireFastSlot(1000));
        testSlotManager.releaseFastSlot();
        
        // Wait for fast pool to become idle
        Thread.sleep(100); //NOSONAR
        
        // Now try to acquire another slow slot - should succeed by borrowing from fast
        assertTrue(testSlotManager.acquireSlowSlot(1000));
        assertEquals(3, testSlotManager.getActiveSlowOperations());
        assertEquals(1, testSlotManager.getFastSlotsBorrowedToSlow());
        
        // Clean up
        testSlotManager.releaseSlowSlot(); // This should release the borrowed slot
        testSlotManager.releaseSlowSlot();
        testSlotManager.releaseSlowSlot();
        assertEquals(0, testSlotManager.getFastSlotsBorrowedToSlow());
    }

    @Test
     void testSuccessfulBorrowingFromSlowToFast() throws InterruptedException {
        // Create a SlotManager with short idle timeout for testing
        SlotManager testSlotManager = new SlotManager(10, 20, 50); // 50ms idle timeout
        
        // Fill up fast slots
        for (int i = 0; i < 8; i++) {
            assertTrue(testSlotManager.acquireFastSlot(1000));
        }
        assertEquals(8, testSlotManager.getActiveFastOperations());
        
        // Establish activity in slow pool first
        assertTrue(testSlotManager.acquireSlowSlot(1000));
        testSlotManager.releaseSlowSlot();
        
        // Wait for slow pool to become idle
        Thread.sleep(100); //NOSONAR
        
        // Now try to acquire another fast slot - should succeed by borrowing from slow
        assertTrue(testSlotManager.acquireFastSlot(1000));
        assertEquals(9, testSlotManager.getActiveFastOperations());
        assertEquals(1, testSlotManager.getSlowSlotsBorrowedToFast());
        
        // Clean up
        for (int i = 0; i < 9; i++) {
            testSlotManager.releaseFastSlot(); // This should release the borrowed slot
        }
        assertEquals(0, testSlotManager.getSlowSlotsBorrowedToFast());
    }

    @Test
     void testBorrowingRequiresActivity() throws InterruptedException {
        // Create a SlotManager with short idle timeout for testing
        SlotManager testSlotManager = new SlotManager(10, 20, 50); // 50ms idle timeout
        
        // Fill up slow slots without any fast activity
        assertTrue(testSlotManager.acquireSlowSlot(1000));
        assertTrue(testSlotManager.acquireSlowSlot(1000));
        assertEquals(2, testSlotManager.getActiveSlowOperations());
        
        // Wait past idle timeout
        Thread.sleep(100); //NOSONAR
        
        // Try to acquire another slow slot - should fail because fast pool never had activity
        assertFalse(testSlotManager.acquireSlowSlot(100));
        assertEquals(2, testSlotManager.getActiveSlowOperations());
        assertEquals(0, testSlotManager.getFastSlotsBorrowedToSlow());
        
        // Clean up
        testSlotManager.releaseSlowSlot();
        testSlotManager.releaseSlowSlot();
    }

    @Test
     void testBorrowingRequiresAvailableSlots() throws InterruptedException {
        // Create a SlotManager with short idle timeout for testing
        SlotManager testSlotManager = new SlotManager(10, 20, 50); // 50ms idle timeout
        
        // Fill up slow slots
        assertTrue(testSlotManager.acquireSlowSlot(1000));
        assertTrue(testSlotManager.acquireSlowSlot(1000));
        assertEquals(2, testSlotManager.getActiveSlowOperations());
        
        // Fill up fast slots completely
        for (int i = 0; i < 8; i++) {
            assertTrue(testSlotManager.acquireFastSlot(1000));
        }
        assertEquals(8, testSlotManager.getActiveFastOperations());
        
        // Wait for pools to become idle
        Thread.sleep(100); //NOSONAR
        
        // Try to acquire another slow slot - should fail because no fast slots are available
        assertFalse(testSlotManager.acquireSlowSlot(100));
        assertEquals(2, testSlotManager.getActiveSlowOperations());
        assertEquals(0, testSlotManager.getFastSlotsBorrowedToSlow());
        
        // Clean up
        testSlotManager.releaseSlowSlot();
        testSlotManager.releaseSlowSlot();
        for (int i = 0; i < 8; i++) {
            testSlotManager.releaseFastSlot();
        }
    }

    @Test
     void testSimpleBorrowingScenario() throws InterruptedException {
        // Create a SlotManager with short idle timeout for testing
        SlotManager testSlotManager = new SlotManager(8, 50, 50); // 8 slots: 4 slow, 4 fast, 50ms idle timeout
        
        // Establish activity in fast pool
        assertTrue(testSlotManager.acquireFastSlot(1000));
        testSlotManager.releaseFastSlot();
        
        // Fill up slow slots
        assertTrue(testSlotManager.acquireSlowSlot(1000));
        assertTrue(testSlotManager.acquireSlowSlot(1000));
        assertTrue(testSlotManager.acquireSlowSlot(1000));
        assertTrue(testSlotManager.acquireSlowSlot(1000));
        assertEquals(4, testSlotManager.getActiveSlowOperations());
        
        // Wait for fast pool to become idle
        Thread.sleep(100); //NOSONAR
        
        // Try to acquire another slow slot - should borrow from fast
        assertTrue(testSlotManager.acquireSlowSlot(1000)); // Should borrow from fast
        
        assertEquals(5, testSlotManager.getActiveSlowOperations());
        assertEquals(1, testSlotManager.getFastSlotsBorrowedToSlow());
        
        // Clean up
        testSlotManager.releaseSlowSlot(); // Release borrowed one first
        testSlotManager.releaseSlowSlot();
        testSlotManager.releaseSlowSlot();
        testSlotManager.releaseSlowSlot();
        testSlotManager.releaseSlowSlot();
        
        assertEquals(0, testSlotManager.getFastSlotsBorrowedToSlow());
    }

    @Test
     void testBorrowedSlotReleaseAccounting() throws InterruptedException {
        // Create a SlotManager with short idle timeout for testing
        SlotManager testSlotManager = new SlotManager(10, 20, 50); // 50ms idle timeout
        
        // Establish activity and enable borrowing
        assertTrue(testSlotManager.acquireSlowSlot(1000));
        assertTrue(testSlotManager.acquireFastSlot(1000));
        testSlotManager.releaseSlowSlot();
        testSlotManager.releaseFastSlot();
        
        // Fill up slow slots
        assertTrue(testSlotManager.acquireSlowSlot(1000));
        assertTrue(testSlotManager.acquireSlowSlot(1000));
        
        // Wait for fast pool to become idle
        Thread.sleep(100); //NOSONAR
        
        // Borrow from fast for slow operation
        assertTrue(testSlotManager.acquireSlowSlot(1000));
        assertEquals(3, testSlotManager.getActiveSlowOperations());
        assertEquals(1, testSlotManager.getFastSlotsBorrowedToSlow());
        
        // Release the borrowed slot (should be the last one acquired)
        testSlotManager.releaseSlowSlot();
        assertEquals(2, testSlotManager.getActiveSlowOperations());
        assertEquals(0, testSlotManager.getFastSlotsBorrowedToSlow());
        
        // Verify we can now acquire a fast slot (the borrowed slot was returned)
        assertTrue(testSlotManager.acquireFastSlot(1000));
        assertEquals(1, testSlotManager.getActiveFastOperations());
        
        // Clean up
        testSlotManager.releaseSlowSlot();
        testSlotManager.releaseSlowSlot();
        testSlotManager.releaseFastSlot();
    }
}
