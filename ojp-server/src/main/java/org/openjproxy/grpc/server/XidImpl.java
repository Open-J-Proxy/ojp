package org.openjproxy.grpc.server;

import javax.transaction.xa.Xid;
import java.util.Arrays;

/**
 * Implementation of javax.transaction.xa.Xid with proper equals() and hashCode().
 * This is necessary for XA resource managers to correctly match Xid instances
 * across different XA operations (start, end, prepare, commit, rollback).
 */
public class XidImpl implements Xid {
    
    private final int formatId;
    private final byte[] globalTransactionId;
    private final byte[] branchQualifier;
    
    public XidImpl(int formatId, byte[] globalTransactionId, byte[] branchQualifier) {
        this.formatId = formatId;
        this.globalTransactionId = globalTransactionId != null ? globalTransactionId.clone() : new byte[0];
        this.branchQualifier = branchQualifier != null ? branchQualifier.clone() : new byte[0];
    }
    
    @Override
    public int getFormatId() {
        return formatId;
    }
    
    @Override
    public byte[] getGlobalTransactionId() {
        return globalTransactionId.clone();
    }
    
    @Override
    public byte[] getBranchQualifier() {
        return branchQualifier.clone();
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof Xid)) {
            return false;
        }
        Xid other = (Xid) obj;
        return formatId == other.getFormatId()
                && Arrays.equals(globalTransactionId, other.getGlobalTransactionId())
                && Arrays.equals(branchQualifier, other.getBranchQualifier());
    }
    
    @Override
    public int hashCode() {
        int result = formatId;
        result = 31 * result + Arrays.hashCode(globalTransactionId);
        result = 31 * result + Arrays.hashCode(branchQualifier);
        return result;
    }
    
    @Override
    public String toString() {
        return String.format("XidImpl[formatId=%d, gtrid=%s, bqual=%s]",
                formatId,
                Arrays.toString(globalTransactionId),
                Arrays.toString(branchQualifier));
    }
}
