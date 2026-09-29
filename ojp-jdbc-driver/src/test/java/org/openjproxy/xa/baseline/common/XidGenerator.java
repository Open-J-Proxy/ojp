package org.openjproxy.xa.baseline.common;

import javax.transaction.xa.Xid;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Utility class for generating unique XIDs for XA transaction testing.
 * 
 * XID format:
 * - Format ID: User-defined identifier (0-999999)
 * - Global Transaction ID: Unique global identifier (max 64 bytes)
 * - Branch Qualifier: Branch identifier within global transaction (max 64 bytes)
 * 
 * This generator ensures uniqueness across test runs using:
 * - Timestamp-based global IDs
 * - Sequential counters
 * - UUID components
 */
public class XidGenerator {
    
    private static final int DEFAULT_FORMAT_ID = 1;
    private static final AtomicLong counter = new AtomicLong(0);
    
    /**
     * Creates a unique XID with default format ID.
     * 
     * @return a new unique Xid
     */
    public static Xid createXid() {
        return createXid(DEFAULT_FORMAT_ID);
    }
    
    /**
     * Creates a unique XID with specified format ID.
     * 
     * @param formatId the format identifier
     * @return a new unique Xid
     */
    public static Xid createXid(int formatId) {
        long count = counter.incrementAndGet();
        String globalId = "gtx-" + System.currentTimeMillis() + "-" + count;
        String branchId = "branch-" + count;
        return new TestXid(formatId, globalId, branchId);
    }
    
    /**
     * Creates a unique XID with specified format ID and custom prefix.
     * 
     * @param formatId the format identifier
     * @param prefix custom prefix for identification
     * @return a new unique Xid
     */
    public static Xid createXid(int formatId, String prefix) {
        long count = counter.incrementAndGet();
        String globalId = prefix + "-gtx-" + System.currentTimeMillis() + "-" + count;
        String branchId = prefix + "-branch-" + count;
        return new TestXid(formatId, globalId, branchId);
    }
    
    /**
     * Creates a unique XID for distributed transactions with same global ID but different branch.
     * 
     * @param formatId the format identifier
     * @param globalTxId the global transaction ID (shared across branches)
     * @param branchSuffix unique suffix for this branch
     * @return a new Xid with specified global ID
     */
    public static Xid createBranchXid(int formatId, String globalTxId, String branchSuffix) {
        String branchId = "branch-" + branchSuffix + "-" + counter.incrementAndGet();
        return new TestXid(formatId, globalTxId, branchId);
    }
    
    /**
     * Test implementation of XID interface.
     */
    static class TestXid implements Xid {
        private final int formatId;
        private final byte[] globalTransactionId;
        private final byte[] branchQualifier;
        
        public TestXid(int formatId, String globalId, String branchId) {
            this.formatId = formatId;
            this.globalTransactionId = globalId.getBytes(StandardCharsets.UTF_8);
            this.branchQualifier = branchId.getBytes(StandardCharsets.UTF_8);
            
            // Validate XID constraints
            if (globalTransactionId.length > 64) {
                throw new IllegalArgumentException("Global transaction ID exceeds 64 bytes: " + globalTransactionId.length);
            }
            if (branchQualifier.length > 64) {
                throw new IllegalArgumentException("Branch qualifier exceeds 64 bytes: " + branchQualifier.length);
            }
        }
        
        @Override
        public int getFormatId() {
            return formatId;
        }
        
        @Override
        public byte[] getGlobalTransactionId() {
            return globalTransactionId;
        }
        
        @Override
        public byte[] getBranchQualifier() {
            return branchQualifier;
        }
        
        @Override
        public String toString() {
            return String.format("XID[fmt=%d, global=%s, branch=%s]",
                formatId,
                new String(globalTransactionId, StandardCharsets.UTF_8),
                new String(branchQualifier, StandardCharsets.UTF_8));
        }
        
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof Xid)) return false;
            
            Xid other = (Xid) obj;
            return formatId == other.getFormatId() &&
                   java.util.Arrays.equals(globalTransactionId, other.getGlobalTransactionId()) &&
                   java.util.Arrays.equals(branchQualifier, other.getBranchQualifier());
        }
        
        @Override
        public int hashCode() {
            int result = formatId;
            result = 31 * result + java.util.Arrays.hashCode(globalTransactionId);
            result = 31 * result + java.util.Arrays.hashCode(branchQualifier);
            return result;
        }
    }
}
