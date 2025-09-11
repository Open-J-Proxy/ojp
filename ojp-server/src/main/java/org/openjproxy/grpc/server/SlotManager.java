package org.openjproxy.grpc.server;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages execution slots for database operations, segregating slow and fast operations.
 * 
 * This class enforces limits on concurrent operations by maintaining separate pools
 * for slow and fast operations, with the ability to borrow slots between pools when
 * one is idle and the other is at capacity.
 */
@Slf4j
public class SlotManager {
    
    private final int totalSlots;
    private final int slowSlots;
    private final int fastSlots;
    private final long idleTimeoutMs;
    
    // Semaphores for slot management
    private final Semaphore slowOperationSemaphore;
    private final Semaphore fastOperationSemaphore;
    
    // Tracking for active operations
    private final AtomicInteger activeSlowOperations = new AtomicInteger(0);
    private final AtomicInteger activeFastOperations = new AtomicInteger(0);
    
    // Tracking for borrowed slots
    private final AtomicInteger slowSlotsBorrowedToFast = new AtomicInteger(0);
    private final AtomicInteger fastSlotsBorrowedToSlow = new AtomicInteger(0);
    
    // Idle time tracking  
    private final AtomicLong lastSlowActivity = new AtomicLong(0);
    private final AtomicLong lastFastActivity = new AtomicLong(0);
    
    // Configuration
    private final AtomicBoolean enabled = new AtomicBoolean(true);
    
    /**
     * Creates a new SlotManager.
     * 
     * @param totalSlots The maximum total number of concurrent operations (from HikariCP max pool size)
     * @param slowSlotPercentage The percentage of slots allocated to slow operations (0-100)
     * @param idleTimeoutMs The time in milliseconds before a slot is considered idle and eligible for borrowing
     */
    public SlotManager(int totalSlots, int slowSlotPercentage, long idleTimeoutMs) {
        if (totalSlots <= 0) {
            throw new IllegalArgumentException("Total slots must be positive");
        }
        if (slowSlotPercentage < 0 || slowSlotPercentage > 100) {
            throw new IllegalArgumentException("Slow slot percentage must be between 0 and 100");
        }
        if (idleTimeoutMs < 0) {
            throw new IllegalArgumentException("Idle timeout must be non-negative");
        }
        
        this.totalSlots = totalSlots;
        this.idleTimeoutMs = idleTimeoutMs;
        
        // Calculate slot allocation
        this.slowSlots = Math.max(1, (totalSlots * slowSlotPercentage) / 100);
        this.fastSlots = totalSlots - this.slowSlots;
        
        // Initialize semaphores
        this.slowOperationSemaphore = new Semaphore(this.slowSlots, true);
        this.fastOperationSemaphore = new Semaphore(this.fastSlots, true);
        
        log.info("SlotManager initialized with {} total slots: {} slow, {} fast, idle timeout {}ms", 
                totalSlots, this.slowSlots, this.fastSlots, idleTimeoutMs);
    }
    
    /**
     * Acquires a slot for a slow operation.
     * 
     * @param timeoutMs The maximum time to wait for a slot in milliseconds
     * @return true if a slot was acquired, false if timeout occurred
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public boolean acquireSlowSlot(long timeoutMs) throws InterruptedException {
        if (!enabled.get()) {
            return true; // If disabled, always allow
        }
        
        lastSlowActivity.set(System.currentTimeMillis());
        
        // First try to acquire immediately without waiting
        if (slowOperationSemaphore.tryAcquire()) {
            activeSlowOperations.incrementAndGet();
            log.debug("Acquired slow slot from slow pool immediately. Active slow: {}", activeSlowOperations.get());
            return true;
        }
        
        // If slow pool is exhausted, try to borrow from fast pool if it's idle
        if (canBorrowFromFastToSlow()) {
            if (fastOperationSemaphore.tryAcquire(100, TimeUnit.MILLISECONDS)) {
                activeSlowOperations.incrementAndGet();
                fastSlotsBorrowedToSlow.incrementAndGet();
                log.debug("Borrowed fast slot for slow operation. Active slow: {}, borrowed: {}", 
                         activeSlowOperations.get(), fastSlotsBorrowedToSlow.get());
                return true;
            }
        }
        
        // Only wait for slow slot if borrowing is not possible or failed
        if (slowOperationSemaphore.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) {
            activeSlowOperations.incrementAndGet();
            log.debug("Acquired slow slot from slow pool after waiting. Active slow: {}", activeSlowOperations.get());
            return true;
        }
        
        log.debug("Failed to acquire slow slot within {}ms timeout", timeoutMs);
        return false;
    }
    
    /**
     * Acquires a slot for a fast operation.
     * 
     * @param timeoutMs The maximum time to wait for a slot in milliseconds
     * @return true if a slot was acquired, false if timeout occurred
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public boolean acquireFastSlot(long timeoutMs) throws InterruptedException {
        if (!enabled.get()) {
            return true; // If disabled, always allow
        }
        
        lastFastActivity.set(System.currentTimeMillis());
        
        // First try to acquire immediately without waiting
        if (fastOperationSemaphore.tryAcquire()) {
            activeFastOperations.incrementAndGet();
            log.debug("Acquired fast slot from fast pool immediately. Active fast: {}", activeFastOperations.get());
            return true;
        }
        
        // If fast pool is exhausted, try to borrow from slow pool if it's idle
        if (canBorrowFromSlowToFast()) {
            if (slowOperationSemaphore.tryAcquire(100, TimeUnit.MILLISECONDS)) {
                activeFastOperations.incrementAndGet();
                slowSlotsBorrowedToFast.incrementAndGet();
                log.debug("Borrowed slow slot for fast operation. Active fast: {}, borrowed: {}", 
                         activeFastOperations.get(), slowSlotsBorrowedToFast.get());
                return true;
            }
        }
        
        // Only wait for fast slot if borrowing is not possible or failed
        if (fastOperationSemaphore.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) {
            activeFastOperations.incrementAndGet();
            log.debug("Acquired fast slot from fast pool after waiting. Active fast: {}", activeFastOperations.get());
            return true;
        }
        
        log.debug("Failed to acquire fast slot within {}ms timeout", timeoutMs);
        return false;
    }
    
    /**
     * Releases a slot that was acquired for a slow operation.
     */
    public void releaseSlowSlot() {
        if (!enabled.get()) {
            return; // If disabled, nothing to release
        }
        
        activeSlowOperations.decrementAndGet();
        
        // Check if this was a borrowed slot from fast pool
        if (fastSlotsBorrowedToSlow.get() > 0 && fastSlotsBorrowedToSlow.decrementAndGet() >= 0) {
            fastOperationSemaphore.release();
            log.debug("Released borrowed fast slot back to fast pool. Active slow: {}", activeSlowOperations.get());
        } else {
            // Increment back if we decremented incorrectly
            if (fastSlotsBorrowedToSlow.get() < 0) {
                fastSlotsBorrowedToSlow.incrementAndGet();
            }
            slowOperationSemaphore.release();
            log.debug("Released slow slot back to slow pool. Active slow: {}", activeSlowOperations.get());
        }
    }
    
    /**
     * Releases a slot that was acquired for a fast operation.
     */
    public void releaseFastSlot() {
        if (!enabled.get()) {
            return; // If disabled, nothing to release
        }
        
        activeFastOperations.decrementAndGet();
        
        // Check if this was a borrowed slot from slow pool
        if (slowSlotsBorrowedToFast.get() > 0 && slowSlotsBorrowedToFast.decrementAndGet() >= 0) {
            slowOperationSemaphore.release();
            log.debug("Released borrowed slow slot back to slow pool. Active fast: {}", activeFastOperations.get());
        } else {
            // Increment back if we decremented incorrectly
            if (slowSlotsBorrowedToFast.get() < 0) {
                slowSlotsBorrowedToFast.incrementAndGet();
            }
            fastOperationSemaphore.release();
            log.debug("Released fast slot back to fast pool. Active fast: {}", activeFastOperations.get());
        }
    }
    
    /**
     * Checks if the fast pool has been idle long enough to allow borrowing for slow operations.
     */
    private boolean canBorrowFromFastToSlow() {
        long currentTime = System.currentTimeMillis();
        long lastActivity = lastFastActivity.get();
        
        // Pool must have had activity to be considered for borrowing
        if (lastActivity == 0) {
            return false;
        }
        
        long fastIdleTime = currentTime - lastActivity;
        boolean hasAvailableSlots = fastOperationSemaphore.availablePermits() > 0;
        boolean isIdle = fastIdleTime >= idleTimeoutMs;
        
        return hasAvailableSlots && isIdle;
    }
    
    /**
     * Checks if the slow pool has been idle long enough to allow borrowing for fast operations.
     */
    private boolean canBorrowFromSlowToFast() {
        long currentTime = System.currentTimeMillis();
        long lastActivity = lastSlowActivity.get();
        
        // Pool must have had activity to be considered for borrowing  
        if (lastActivity == 0) {
            return false;
        }
        
        long slowIdleTime = currentTime - lastActivity;
        boolean hasAvailableSlots = slowOperationSemaphore.availablePermits() > 0;
        boolean isIdle = slowIdleTime >= idleTimeoutMs;
        
        return hasAvailableSlots && isIdle;
    }
    
    /**
     * Gets the current status of the slot manager.
     * 
     * @return A status string with current slot usage
     */
    public String getStatus() {
        return String.format(
            "SlotManager[total=%d, slow=%d/%d, fast=%d/%d, borrowed(slow->fast)=%d, borrowed(fast->slow)=%d, enabled=%s]",
            totalSlots,
            activeSlowOperations.get(), slowSlots,
            activeFastOperations.get(), fastSlots,
            slowSlotsBorrowedToFast.get(),
            fastSlotsBorrowedToSlow.get(),
            enabled.get()
        );
    }
    
    /**
     * Enables or disables the slot management feature.
     * When disabled, all slot acquisition attempts will succeed immediately.
     * 
     * @param enabled true to enable slot management, false to disable
     */
    public void setEnabled(boolean enabled) {
        this.enabled.set(enabled);
        log.info("SlotManager enabled: {}", enabled);
    }
    
    /**
     * Checks if slot management is currently enabled.
     * 
     * @return true if enabled, false otherwise
     */
    public boolean isEnabled() {
        return enabled.get();
    }
    
    // Getters for monitoring
    public int getTotalSlots() { return totalSlots; }
    public int getSlowSlots() { return slowSlots; }
    public int getFastSlots() { return fastSlots; }
    public int getActiveSlowOperations() { return activeSlowOperations.get(); }
    public int getActiveFastOperations() { return activeFastOperations.get(); }
    public int getSlowSlotsBorrowedToFast() { return slowSlotsBorrowedToFast.get(); }
    public int getFastSlotsBorrowedToSlow() { return fastSlotsBorrowedToSlow.get(); }
    public long getIdleTimeoutMs() { return idleTimeoutMs; }
}