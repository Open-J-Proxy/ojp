package org.openjproxy.xa.baseline.smoke;

import org.junit.jupiter.api.Test;
import org.openjproxy.xa.baseline.common.XidGenerator;
import org.openjproxy.xa.baseline.common.TransactionCoordinator;

import javax.transaction.xa.Xid;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke test to verify Phase 1 infrastructure is properly set up.
 * 
 * This test validates:
 * - XidGenerator creates valid XIDs
 * - TransactionCoordinator can be instantiated
 * - Basic utility methods work
 */
public class Phase1InfrastructureSmokeTest {
    
    @Test
    public void testXidGeneratorCreatesUniqueXids() {
        // Create multiple XIDs and verify they are unique
        Xid xid1 = XidGenerator.createXid();
        Xid xid2 = XidGenerator.createXid();
        Xid xid3 = XidGenerator.createXid();
        
        assertNotNull(xid1, "XID 1 should not be null");
        assertNotNull(xid2, "XID 2 should not be null");
        assertNotNull(xid3, "XID 3 should not be null");
        
        // Verify they are different
        assertNotEquals(xid1, xid2, "XID 1 and 2 should be different");
        assertNotEquals(xid2, xid3, "XID 2 and 3 should be different");
        assertNotEquals(xid1, xid3, "XID 1 and 3 should be different");
    }
    
    @Test
    public void testXidGeneratorCreatesValidXids() {
        Xid xid = XidGenerator.createXid();
        
        // Verify XID components
        assertEquals(1, xid.getFormatId(), "Default format ID should be 1");
        
        byte[] globalTxId = xid.getGlobalTransactionId();
        assertNotNull(globalTxId, "Global transaction ID should not be null");
        assertTrue(globalTxId.length > 0, "Global transaction ID should not be empty");
        assertTrue(globalTxId.length <= 64, "Global transaction ID should not exceed 64 bytes");
        
        byte[] branchQual = xid.getBranchQualifier();
        assertNotNull(branchQual, "Branch qualifier should not be null");
        assertTrue(branchQual.length > 0, "Branch qualifier should not be empty");
        assertTrue(branchQual.length <= 64, "Branch qualifier should not exceed 64 bytes");
    }
    
    @Test
    public void testXidGeneratorWithCustomFormatId() {
        int customFormatId = 999;
        Xid xid = XidGenerator.createXid(customFormatId);
        
        assertEquals(customFormatId, xid.getFormatId(), "Format ID should match custom value");
    }
    
    @Test
    public void testXidGeneratorWithPrefix() {
        String prefix = "test";
        Xid xid = XidGenerator.createXid(1, prefix);
        
        String globalId = new String(xid.getGlobalTransactionId());
        assertTrue(globalId.startsWith(prefix), "Global ID should start with prefix");
        
        String branchId = new String(xid.getBranchQualifier());
        assertTrue(branchId.startsWith(prefix), "Branch ID should start with prefix");
    }
    
    @Test
    public void testXidGeneratorCreatesBranchXids() {
        String globalTxId = "global-tx-12345";
        
        Xid branch1 = XidGenerator.createBranchXid(1, globalTxId, "branch1");
        Xid branch2 = XidGenerator.createBranchXid(1, globalTxId, "branch2");
        
        // Both should have same global TX ID
        assertArrayEquals(globalTxId.getBytes(), branch1.getGlobalTransactionId(),
            "Branch 1 should have specified global TX ID");
        assertArrayEquals(globalTxId.getBytes(), branch2.getGlobalTransactionId(),
            "Branch 2 should have specified global TX ID");
        
        // But different branch qualifiers
        assertFalse(java.util.Arrays.equals(branch1.getBranchQualifier(), branch2.getBranchQualifier()),
            "Branch qualifiers should be different");
    }
    
    @Test
    public void testTransactionCoordinatorInstantiation() {
        // Verify TransactionCoordinator can be created
        TransactionCoordinator coordinator = new TransactionCoordinator();
        
        assertNotNull(coordinator, "Coordinator should not be null");
        assertEquals(0, coordinator.getResourceCount(), "New coordinator should have no resources");
        assertFalse(coordinator.areAllPrepared(), "New coordinator should have no prepared resources");
        assertFalse(coordinator.areAllCommitted(), "New coordinator should have no committed resources");
        assertFalse(coordinator.areAllRolledBack(), "New coordinator should have no rolled back resources");
    }
    
    @Test
    public void testTransactionCoordinatorClear() {
        TransactionCoordinator coordinator = new TransactionCoordinator();
        
        // Initially empty
        assertEquals(0, coordinator.getResourceCount());
        
        // Clear should not throw even when empty
        coordinator.clear();
        assertEquals(0, coordinator.getResourceCount());
    }
    
    @Test
    public void testXidToString() {
        Xid xid = XidGenerator.createXid();
        String xidString = xid.toString();
        
        assertNotNull(xidString, "XID toString should not be null");
        assertTrue(xidString.contains("XID"), "XID toString should contain 'XID'");
        assertTrue(xidString.contains("fmt="), "XID toString should contain format ID");
        assertTrue(xidString.contains("global="), "XID toString should contain global ID");
        assertTrue(xidString.contains("branch="), "XID toString should contain branch ID");
    }
    
    @Test
    public void testXidEquals() {
        Xid xid1 = XidGenerator.createXid();
        Xid xid2 = XidGenerator.createXid();
        
        // Same XID should equal itself
        assertEquals(xid1, xid1, "XID should equal itself");
        
        // Different XIDs should not be equal
        assertNotEquals(xid1, xid2, "Different XIDs should not be equal");
        
        // XID should not equal null
        assertNotEquals(null, xid1, "XID should not equal null");
    }
    
    @Test
    public void testXidHashCode() {
        Xid xid1 = XidGenerator.createXid();
        Xid xid2 = XidGenerator.createXid();
        
        // Hash codes should be consistent
        int hash1a = xid1.hashCode();
        int hash1b = xid1.hashCode();
        assertEquals(hash1a, hash1b, "Hash code should be consistent");
        
        // Different XIDs should (probably) have different hash codes
        int hash2 = xid2.hashCode();
        // Note: We don't assert inequality because hash collisions are theoretically possible,
        // but in practice they should be different
    }
}
