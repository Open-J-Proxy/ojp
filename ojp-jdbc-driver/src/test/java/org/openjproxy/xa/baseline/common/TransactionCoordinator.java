package org.openjproxy.xa.baseline.common;

import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manual 2PC (Two-Phase Commit) coordinator helper for XA transaction testing.
 * 
 * This class simulates a simple transaction manager that coordinates distributed
 * transactions across multiple XA resources. It implements the standard 2PC protocol:
 * 
 * Phase 1 (Prepare): Ask all participants if they can commit
 * Phase 2 (Commit/Rollback): Based on votes, commit or rollback all participants
 * 
 * This is for testing purposes only and does not include production features like:
 * - Transaction logging
 * - Recovery after crash
 * - Heuristic outcome handling
 * - Timeout management
 */
public class TransactionCoordinator {
    
    /**
     * Represents a resource participating in a distributed transaction.
     */
    public static class TransactionBranch {
        private final XAResource xaResource;
        private final Xid xid;
        private boolean prepared = false;
        private boolean committed = false;
        private boolean rolledBack = false;
        
        public TransactionBranch(XAResource xaResource, Xid xid) {
            this.xaResource = xaResource;
            this.xid = xid;
        }
        
        public XAResource getXaResource() {
            return xaResource;
        }
        
        public Xid getXid() {
            return xid;
        }
        
        public boolean isPrepared() {
            return prepared;
        }
        
        public void setPrepared(boolean prepared) {
            this.prepared = prepared;
        }
        
        public boolean isCommitted() {
            return committed;
        }
        
        public void setCommitted(boolean committed) {
            this.committed = committed;
        }
        
        public boolean isRolledBack() {
            return rolledBack;
        }
        
        public void setRolledBack(boolean rolledBack) {
            this.rolledBack = rolledBack;
        }
    }
    
    private final List<TransactionBranch> branches = new ArrayList<>();
    private final Map<Xid, TransactionBranch> branchMap = new HashMap<>();
    
    /**
     * Enlists a new resource in the distributed transaction.
     * 
     * @param xaResource the XA resource to enlist
     * @param xid the transaction ID for this branch
     */
    public void enlistResource(XAResource xaResource, Xid xid) {
        TransactionBranch branch = new TransactionBranch(xaResource, xid);
        branches.add(branch);
        branchMap.put(xid, branch);
    }
    
    /**
     * Executes Phase 1 of 2PC: Prepare all participants.
     * 
     * @return true if all participants voted to commit, false otherwise
     * @throws XAException if prepare fails
     */
    public boolean prepareAll() throws XAException {
        for (TransactionBranch branch : branches) {
            int result = branch.getXaResource().prepare(branch.getXid());
            
            if (result == XAResource.XA_OK) {
                branch.setPrepared(true);
            } else if (result == XAResource.XA_RDONLY) {
                // Read-only optimization: this branch doesn't need commit
                branch.setPrepared(true);
                branch.setCommitted(true); // Already completed
            } else {
                // Unexpected result
                throw new XAException("Unexpected prepare result: " + result);
            }
        }
        return true;
    }
    
    /**
     * Executes Phase 2 of 2PC: Commit all prepared participants.
     * 
     * @throws XAException if commit fails
     */
    public void commitAll() throws XAException {
        List<XAException> exceptions = new ArrayList<>();
        
        for (TransactionBranch branch : branches) {
            if (!branch.isPrepared()) {
                throw new IllegalStateException("Cannot commit unprepared branch: " + branch.getXid());
            }
            
            // Skip branches that were read-only (already completed in prepare)
            if (branch.isCommitted()) {
                continue;
            }
            
            try {
                branch.getXaResource().commit(branch.getXid(), false);
                branch.setCommitted(true);
            } catch (XAException e) {
                exceptions.add(e);
                // Continue to attempt commit on other branches
            }
        }
        
        if (!exceptions.isEmpty()) {
            XAException firstException = exceptions.get(0);
            if (exceptions.size() > 1) {
                System.err.println("Multiple commit failures detected (" + exceptions.size() + " branches failed)");
            }
            throw firstException;
        }
    }
    
    /**
     * Rolls back all participants.
     * Can be called before or after prepare.
     * 
     * @throws XAException if rollback fails
     */
    public void rollbackAll() throws XAException {
        List<XAException> exceptions = new ArrayList<>();
        
        for (TransactionBranch branch : branches) {
            // Skip branches that are already committed (shouldn't happen in normal flow)
            if (branch.isCommitted()) {
                continue;
            }
            
            try {
                branch.getXaResource().rollback(branch.getXid());
                branch.setRolledBack(true);
            } catch (XAException e) {
                exceptions.add(e);
                // Continue to attempt rollback on other branches
            }
        }
        
        if (!exceptions.isEmpty()) {
            XAException firstException = exceptions.get(0);
            if (exceptions.size() > 1) {
                System.err.println("Multiple rollback failures detected (" + exceptions.size() + " branches failed)");
            }
            throw firstException;
        }
    }
    
    /**
     * Executes one-phase commit optimization for single resource transactions.
     * 
     * @throws XAException if commit fails
     * @throws IllegalStateException if multiple resources are enlisted
     */
    public void onePhaseCommit() throws XAException {
        if (branches.size() != 1) {
            throw new IllegalStateException("One-phase commit requires exactly one resource, but " + 
                                          branches.size() + " are enlisted");
        }
        
        TransactionBranch branch = branches.get(0);
        branch.getXaResource().commit(branch.getXid(), true); // onePhase = true
        branch.setCommitted(true);
    }
    
    /**
     * Gets the number of enlisted resources.
     * 
     * @return the number of resources
     */
    public int getResourceCount() {
        return branches.size();
    }
    
    /**
     * Gets all transaction branches.
     * 
     * @return list of transaction branches
     */
    public List<TransactionBranch> getBranches() {
        return new ArrayList<>(branches);
    }
    
    /**
     * Checks if all branches are prepared.
     * 
     * @return true if all prepared, false otherwise (including when no resources are enlisted)
     */
    public boolean areAllPrepared() {
        return !branches.isEmpty() && branches.stream().allMatch(TransactionBranch::isPrepared);
    }
    
    /**
     * Checks if all branches are committed.
     * 
     * @return true if all committed, false otherwise (including when no resources are enlisted)
     */
    public boolean areAllCommitted() {
        return !branches.isEmpty() && branches.stream().allMatch(TransactionBranch::isCommitted);
    }
    
    /**
     * Checks if all branches are rolled back.
     * 
     * @return true if all rolled back, false otherwise (including when no resources are enlisted)
     */
    public boolean areAllRolledBack() {
        return !branches.isEmpty() && branches.stream().allMatch(TransactionBranch::isRolledBack);
    }
    
    /**
     * Clears all enlisted resources.
     * Used to reset the coordinator for a new transaction.
     */
    public void clear() {
        branches.clear();
        branchMap.clear();
    }
}
