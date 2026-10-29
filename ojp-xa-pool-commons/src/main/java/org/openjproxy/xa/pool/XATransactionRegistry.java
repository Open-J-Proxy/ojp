package org.openjproxy.xa.pool;

import org.openjproxy.xa.pool.spi.XAConnectionPoolProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Registry for managing XA transaction contexts and their lifecycle.
 * <p>
 * This class orchestrates XA transaction state transitions, manages the binding of
 * Xids to backend sessions, and delegates durability to the backend database's
 * native XA implementation.
 * </p>
 * <p>
 * Thread-safe: All operations are synchronized appropriately. Multiple threads can
 * call XA methods concurrently for different Xids.
 * </p>
 *
 * <h3>Responsibilities:</h3>
 * <ul>
 *   <li>Maintain in-memory map of XidKey → TxContext</li>
 *   <li>Enforce XA state machine transitions</li>
 *   <li>Borrow/return backend sessions from pool</li>
 *   <li>Delegate prepare/commit/rollback to backend XAResource</li>
 *   <li>Handle recovery by querying backend XAResource.recover()</li>
 * </ul>
 *
 * <h3>Invariants:</h3>
 * <ul>
 *   <li>I1: One backend session is bound to at most one Xid at a time</li>
 *   <li>I2: All SQL for a given Xid routes to its bound session</li>
 *   <li>I3: A session is returned to pool only after COMPLETED (commit/rollback)</li>
 *   <li>I4: After prepare(Xid)=XA_OK, recover() returns Xid until completion</li>
 *   <li>I5: commit/rollback are idempotent</li>
 * </ul>
 */
public class XATransactionRegistry {
    private static final Logger log = LoggerFactory.getLogger(XATransactionRegistry.class);

    private final ConcurrentMap<XidKey, TxContext> contexts = new ConcurrentHashMap<>();
    private final XAConnectionPoolProvider poolProvider;
    private final Object poolDataSource; // XADataSource instance from provider
    private final String serverEndpointsHash; // Hash of serverEndpoints used to create this registry
    private final int maxPoolSize; // Max pool size used to create backend pool
    private final int minIdle; // Min idle connections used to create backend pool

    /**
     * Creates a new XA transaction registry.
     *
     * @param poolProvider the XA connection pool provider
     * @param poolDataSource the XADataSource instance from the provider
     * @param serverEndpointsHash hash of serverEndpoints configuration used to create backend pools
     * @param maxPoolSize the maximum pool size used to create the backend pool
     * @param minIdle the minimum idle connections used to create the backend pool
     */
    public XATransactionRegistry(XAConnectionPoolProvider poolProvider, Object poolDataSource, String serverEndpointsHash, int maxPoolSize, int minIdle) {
        this.poolProvider = poolProvider;
        this.poolDataSource = poolDataSource;
        this.serverEndpointsHash = serverEndpointsHash;
        this.maxPoolSize = maxPoolSize;
        this.minIdle = minIdle;
    }

    /**
     * Gets the serverEndpoints hash that was used to create this registry.
     * This allows detection of configuration changes that require registry recreation.
     *
     * @return the serverEndpoints hash, or null if created with old code
     */
    public String getServerEndpointsHash() {
        return serverEndpointsHash;
    }

    /**
     * Gets the maximum pool size used to create the backend pool.
     *
     * @return the max pool size
     */
    public int getMaxPoolSize() {
        return maxPoolSize;
    }

    /**
     * Gets the minimum idle connections used to create the backend pool.
     *
     * @return the min idle connections
     */
    public int getMinIdle() {
        return minIdle;
    }

    /**
     * Gets the pooled XA DataSource managed by this registry.
     *
     * @return the pooled XA DataSource instance
     */
    public Object getPooledXADataSource() {
        return poolDataSource;
    }

    /**
     * Resizes the backend connection pool to the specified sizes.
     * <p>
     * This method is called during cluster rebalancing when the number of healthy
     * servers changes. The XA backend pool needs to expand or contract to maintain
     * proper load distribution across the cluster.
     * </p>
     *
     * @param newMaxPoolSize the new maximum pool size
     * @param newMinIdle the new minimum idle connections
     */
    public void resizeBackendPool(int newMaxPoolSize, int newMinIdle) {
        if (poolDataSource instanceof org.openjproxy.xa.pool.commons.CommonsPool2XADataSource) {
            org.openjproxy.xa.pool.commons.CommonsPool2XADataSource commonsPool =
                    (org.openjproxy.xa.pool.commons.CommonsPool2XADataSource) poolDataSource;

            int currentMaxTotal = commonsPool.getMaxTotal();
            int currentMinIdle = commonsPool.getMinIdle();

            log.debug("[XA-POOL-RESIZE] resizeBackendPool called: old=(max={}, min={}), new=(max={}, min={}), " +
                    "currentPoolState=(active={}, idle={})",
                    currentMaxTotal, currentMinIdle, newMaxPoolSize, newMinIdle,
                    commonsPool.getNumActive(), commonsPool.getNumIdle());

            // Log diagnostics BEFORE resize
            commonsPool.logPoolDiagnostics("BEFORE resize");

            // Determine resize direction
            boolean isDecreasing = (newMaxPoolSize < currentMaxTotal) || (newMinIdle < currentMinIdle);

            if (isDecreasing) {
                // When decreasing: set minIdle first, then maxTotal to avoid validation errors
                commonsPool.setMinIdle(newMinIdle);
                commonsPool.setMaxTotal(newMaxPoolSize);
                log.debug("[XA-POOL-RESIZE] XA backend pool resized (DECREASED): maxTotal={}, minIdle={}",
                        newMaxPoolSize, newMinIdle);
            } else {
                // When increasing: set maxTotal first, then minIdle
                commonsPool.setMaxTotal(newMaxPoolSize);
                commonsPool.setMinIdle(newMinIdle);
                log.debug("[XA-POOL-RESIZE] XA backend pool resized (INCREASED): maxTotal={}, minIdle={}",
                        newMaxPoolSize, newMinIdle);
            }

            // Log diagnostics AFTER resize
            commonsPool.logPoolDiagnostics("AFTER resize");

        } else {
            log.warn("Cannot resize XA backend pool: poolDataSource is not CommonsPool2XADataSource");
        }
    }

    /**
     * Registers an existing XABackendSession with a new XA transaction.
     * <p>
     * Used when XABackendSession is allocated eagerly (during connect) rather than
     * lazily (during xaStart). This avoids double allocation from the pool.
     * </p>
     *
     * @param xid the transaction branch identifier
     * @param session the existing XABackendSession to register
     * @param flags XA start flags (must be TMNOFLAGS for new transaction)
     * @param ojpSessionId the OJP session ID this transaction belongs to
     * @throws XAException if flags are invalid, xid already exists, or XA start fails
     */
    public void registerExistingSession(XidKey xid, XABackendSession session, int flags, String ojpSessionId) throws XAException {
        log.debug("registerExistingSession: xid={}, flags={}, ojpSessionId={}", xid, flagsToString(flags), ojpSessionId);

        // Validate flags - only TMNOFLAGS allowed for new transaction
        if (flags != XAResource.TMNOFLAGS) {
            throw new XAException(XAException.XAER_INVAL);
        }

        // Create context and ensure no duplicate
        TxContext existing = contexts.putIfAbsent(xid, new TxContext(xid, ojpSessionId));
        if (existing != null) {
            throw new XAException(XAException.XAER_DUPID);
        }

        TxContext ctx = contexts.get(xid);
        try {
            // Register the existing session
            ctx.transitionToActive(session);

            // Create Xid object once and store it for reuse
            javax.transaction.xa.Xid actualXid = xid.toXid();
            ctx.setActualXid(actualXid);

            // Call XAResource.start on backend with the stored Xid
            session.getXAResource().start(actualXid, flags);

            log.info("XA transaction registered with existing session: xid={}, ojpSessionId={}", xid, ojpSessionId);
        } catch (XAException e) {
            contexts.remove(xid);
            throw e;
        } catch (Exception e) {
            contexts.remove(xid);
            throw new XAException(XAException.XAER_RMERR);
        }
    }

    /**
     * Starts an XA transaction branch.
     * <p>
     * For TMNOFLAGS: Creates new TxContext and borrows a backend session.<br>
     * For TMJOIN: Reuses existing TxContext and session.<br>
     * For TMRESUME: Resumes suspended TxContext and session.
     * </p>
     *
     * @param xid the transaction branch identifier
     * @param flags XA start flags (TMNOFLAGS, TMJOIN, TMRESUME)
     * @param ojpSessionId the OJP session ID this transaction belongs to (only used for TMNOFLAGS)
     * @throws XAException if state transition is invalid or session cannot be borrowed
     */
    public void xaStart(XidKey xid, int flags, String ojpSessionId) throws XAException {
        log.debug("xaStart: xid={}, flags={}, ojpSessionId={}", xid, flagsToString(flags), ojpSessionId);

        boolean isTmNoFlags = (flags == XAResource.TMNOFLAGS);
        boolean isTmJoin = (flags == XAResource.TMJOIN);
        boolean isTmResume = (flags == XAResource.TMRESUME);

        if (isTmNoFlags) {
            // New transaction branch - create context and borrow session
            TxContext existing = contexts.putIfAbsent(xid, new TxContext(xid, ojpSessionId));
            if (existing != null) {
                throw new XAException(XAException.XAER_DUPID);
            }

            TxContext ctx = contexts.get(xid);
            try {
                XABackendSession session = poolProvider.borrowSession(poolDataSource);
                ctx.transitionToActive(session);

                // Create Xid object once and store it for reuse
                javax.transaction.xa.Xid actualXid = xid.toXid();
                ctx.setActualXid(actualXid);

                // Call XAResource.start on backend with the stored Xid
                session.getXAResource().start(actualXid, flags);

                log.info("XA transaction started: xid={}, ojpSessionId={}", xid, ojpSessionId);
            } catch (Exception e) {
                contexts.remove(xid);
                throw new XAException(XAException.XAER_RMERR);
            }

        } else if (isTmJoin || isTmResume) {
            // Join or resume existing transaction branch
            TxContext ctx = contexts.get(xid);
            if (ctx == null) {
                throw new XAException(XAException.XAER_NOTA);
            }

            try {
                ctx.transitionToActiveFromEnded(isTmJoin);

                // Reuse the same Xid object that was used in original start()
                javax.transaction.xa.Xid actualXid = ctx.getActualXid();
                if (actualXid == null) {
                    throw new XAException(XAException.XAER_PROTO);
                }

                // Call XAResource.start on backend with the stored Xid
                ctx.getSession().getXAResource().start(actualXid, flags);

                log.debug("XA transaction {} resumed: xid={}", isTmJoin ? "joined" : "resumed", xid);
            } catch (IllegalStateException e) {
                throw new XAException(XAException.XAER_PROTO);
            }

        } else {
            throw new XAException(XAException.XAER_INVAL);
        }
    }

    /**
     * Ends an XA transaction branch.
     * <p>
     * For TMSUCCESS/TMFAIL: Disassociates session from thread, keeps it bound.<br>
     * For TMSUSPEND: Suspends transaction, session remains bound for later TMRESUME.
     * </p>
     *
     * @param xid the transaction branch identifier
     * @param flags XA end flags (TMSUCCESS, TMFAIL, TMSUSPEND)
     * @throws XAException if state transition is invalid
     */
    public void xaEnd(XidKey xid, int flags) throws XAException {
        log.debug("xaEnd: xid={}, flags={}", xid, flagsToString(flags));

        TxContext ctx = contexts.get(xid);
        if (ctx == null) {
            throw new XAException(XAException.XAER_NOTA);
        }

        boolean isTmSuccess = (flags == XAResource.TMSUCCESS);
        boolean isTmFail = (flags == XAResource.TMFAIL);
        boolean isTmSuspend = (flags == XAResource.TMSUSPEND);

        if (!isTmSuccess && !isTmFail && !isTmSuspend) {
            throw new XAException(XAException.XAER_INVAL);
        }

        try {
            // Reuse the same Xid object that was used in start()
            javax.transaction.xa.Xid actualXid = ctx.getActualXid();
            if (actualXid == null) {
                throw new XAException(XAException.XAER_PROTO);
            }

            // Call XAResource.end on backend with the stored Xid
            ctx.getSession().getXAResource().end(actualXid, flags);

            ctx.transitionToEnded(isTmSuspend);

            log.debug("XA transaction ended: xid={}, flags={}", xid, flagsToString(flags));
        } catch (IllegalStateException e) {
            throw new XAException(XAException.XAER_PROTO);
        }
    }

    /**
     * Prepares an XA transaction branch for commit (phase 1 of 2PC).
     * <p>
     * Delegates to backend XAResource.prepare(). If prepare returns XA_RDONLY,
     * the transaction is optimized away (no second phase needed).
     * </p>
     *
     * @param xid the transaction branch identifier
     * @return XA_OK if prepared successfully, XA_RDONLY if read-only optimization applies
     * @throws XAException if state transition is invalid or prepare fails
     */
    public int xaPrepare(XidKey xid) throws XAException {
        log.debug("xaPrepare: xid={}", xid);

        TxContext ctx = contexts.get(xid);
        if (ctx == null) {
            throw new XAException(XAException.XAER_NOTA);
        }

        try {
            // Reuse the same Xid object that was used in start()
            javax.transaction.xa.Xid actualXid = ctx.getActualXid();
            if (actualXid == null) {
                throw new XAException(XAException.XAER_PROTO);
            }

            // Call XAResource.prepare on backend
            int result = ctx.getSession().getXAResource().prepare(actualXid);

            if (result == XAResource.XA_RDONLY) {
                // Read-only optimization: no prepare needed, transaction can complete immediately
                ctx.transitionToCommitted();
                returnSessionToPool(ctx);
                contexts.remove(xid);

                log.info("XA transaction prepared (read-only optimization): xid={}", xid);
                return XAResource.XA_RDONLY;
            } else {
                // Normal 2PC: transition to PREPARED state
                ctx.transitionToPrepared();

                log.info("XA transaction prepared: xid={}", xid);
                return XAResource.XA_OK;
            }
        } catch (IllegalStateException e) {
            throw new XAException(XAException.XAER_PROTO);
        }
    }

    /**
     * Commits an XA transaction branch.
     * <p>
     * For onePhase=true: 1PC optimization (combine prepare + commit).<br>
     * For onePhase=false: Phase 2 of 2PC (after successful prepare).
     * </p>
     * <p>
     * Idempotent: If already committed, returns success.
     * </p>
     *
     * @param xid the transaction branch identifier
     * @param onePhase true for 1PC optimization, false for 2PC
     * @throws XAException if state transition is invalid or commit fails
     */
    public void xaCommit(XidKey xid, boolean onePhase) throws XAException {
        log.debug("xaCommit: xid={}, onePhase={}", xid, onePhase);

        TxContext ctx = contexts.get(xid);
        if (ctx == null) {
            throw new XAException(XAException.XAER_NOTA);
        }

        // Idempotency: if already committed, succeed silently
        if (ctx.getState() == TxState.COMMITTED) {
            log.debug("XA transaction already committed (idempotent): xid={}", xid);
            return;
        }

        try {
            // Reuse the same Xid object that was used in start()
            javax.transaction.xa.Xid actualXid = ctx.getActualXid();
            if (actualXid == null) {
                throw new XAException(XAException.XAER_PROTO);
            }

            // Call XAResource.commit on backend
            ctx.getSession().getXAResource().commit(actualXid, onePhase);

            ctx.transitionToCommitted();

            // Mark transaction as complete but keep in contexts
            // Backend session will be returned to pool when XAConnection.close() is called
            ctx.markTransactionComplete();

            // IMPORTANT: Sanitize the session to reset XA state for next transaction
            // The session stays bound to OJP Session (doesn't return to pool)
            // but needs XA state reset between transactions
            try {
                ctx.getSession().sanitizeAfterTransaction();
                log.debug("Session sanitized after commit for xid={}", xid);
            } catch (java.sql.SQLException e) {
                log.warn("Failed to sanitize session after commit for xid={}: {}", xid, e.getMessage());
                // Don't throw - commit was successful, sanitization is best-effort
                // If sanitization fails, next transaction may have issues, but commit succeeded
            }

            // DO NOT remove from contexts here - keep it until XAConnection.close()
            // DO NOT return to pool here - session stays bound until XAConnection.close()

            log.info("XA transaction committed: xid={}, onePhase={}", xid, onePhase);
        } catch (XAException e) {
            log.error("XA error during commit for xid=" + xid, e);
            // Mark as complete even on error to allow cleanup
            if (ctx != null) {
                ctx.markTransactionComplete();
            }
            // DO NOT remove from contexts - keep it until XAConnection.close()
            // DO NOT return session to pool - it stays with OJP Session
            throw e;  // Rethrow original XAException to preserve error code
        } catch (IllegalStateException e) {
            log.error("Invalid state transition during commit for xid=" + xid, e);
            throw new XAException(XAException.XAER_PROTO);
        } catch (Exception e) {
            log.error("Unexpected error during commit for xid=" + xid, e);
            // Mark as complete even on error to allow cleanup
            if (ctx != null) {
                ctx.markTransactionComplete();
            }
            // DO NOT remove from contexts - keep it until XAConnection.close()
            // DO NOT return session to pool - it stays with OJP Session
            XAException xae = new XAException(XAException.XAER_RMERR);
            xae.initCause(e);  // Preserve original exception
            throw xae;
        }
    }

    /**
     * Rolls back an XA transaction branch.
     * <p>
     * Can be called from ACTIVE, ENDED, or PREPARED state.
     * Idempotent: If already rolled back, returns success.
     * </p>
     *
     * @param xid the transaction branch identifier
     * @throws XAException if rollback fails
     */
    public void xaRollback(XidKey xid) throws XAException {
        log.debug("xaRollback: xid={}", xid);

        TxContext ctx = contexts.get(xid);
        if (ctx == null) {
            throw new XAException(XAException.XAER_NOTA);
        }

        // Idempotency: if already rolled back, succeed silently
        if (ctx.getState() == TxState.ROLLEDBACK) {
            log.debug("XA transaction already rolled back (idempotent): xid={}", xid);
            return;
        }

        try {
            // Reuse the same Xid object that was used in start()
            javax.transaction.xa.Xid actualXid = ctx.getActualXid();
            if (actualXid == null) {
                throw new XAException(XAException.XAER_PROTO);
            }

            // Call XAResource.rollback on backend
            ctx.getSession().getXAResource().rollback(actualXid);

            ctx.transitionToRolledBack();

            // Mark transaction as complete but keep in contexts
            // Backend session will be returned to pool when XAConnection.close() is called
            ctx.markTransactionComplete();

            // IMPORTANT: Sanitize the session to reset XA state for next transaction
            // The session stays bound to OJP Session (doesn't return to pool)
            // but needs XA state reset between transactions
            try {
                ctx.getSession().sanitizeAfterTransaction();
                log.debug("Session sanitized after rollback for xid={}", xid);
            } catch (java.sql.SQLException e) {
                log.warn("Failed to sanitize session after rollback for xid={}: {}", xid, e.getMessage());
                // Don't throw - rollback was successful, sanitization is best-effort
            }

            // DO NOT remove from contexts here - keep it until XAConnection.close()
            // DO NOT return to pool here - session stays bound until XAConnection.close()

            log.info("XA transaction rolled back: xid={}", xid);
        } catch (XAException e) {
            log.error("XA error during rollback for xid=" + xid, e);
            // Mark as complete even on error to allow cleanup
            if (ctx != null) {
                ctx.markTransactionComplete();
            }
            // DO NOT remove from contexts - keep it until XAConnection.close()
            // DO NOT return session to pool - it stays with OJP Session
            throw e;  // Rethrow original XAException to preserve error code
        } catch (Exception e) {
            log.error("Unexpected error during rollback for xid=" + xid, e);
            // Mark as complete even on error to allow cleanup
            if (ctx != null) {
                ctx.markTransactionComplete();
            }
            // DO NOT remove from contexts - keep it until XAConnection.close()
            // DO NOT return session to pool - it stays with OJP Session
            XAException xae = new XAException(XAException.XAER_RMERR);
            xae.initCause(e);  // Preserve original exception
            throw xae;
        }
    }

    /**
     * Recovers prepared XA transaction branches from the backend database.
     * <p>
     * Delegates to backend XAResource.recover(). This queries the database's
     * transaction log for transactions in PREPARED state.
     * </p>
     * <p>
     * For multinode deployments: Each proxy node calls recover() on its backend
     * sessions. The Transaction Manager broadcasts recovery to all nodes and
     * completes transactions based on its own log.
     * </p>
     *
     * @param flag XA recover flags (TMSTARTRSCAN, TMENDRSCAN, TMNOFLAGS)
     * @return list of Xids in PREPARED state
     * @throws XAException if recovery fails
     */
    public List<XidKey> xaRecover(int flag) throws XAException {
        log.debug("xaRecover: flag={}", flag);

        List<XidKey> preparedXids = new ArrayList<>();

        // Delegate to backend XAResource.recover()
        // In a real implementation, we'd need a backend session to call recover() on
        XABackendSession session = null;
        try {
            session = poolProvider.borrowSession(poolDataSource);
            javax.transaction.xa.Xid[] xids = session.getXAResource().recover(flag);

            if (xids != null) {
                for (javax.transaction.xa.Xid xid : xids) {
                    preparedXids.add(XidKey.from(xid));
                }
            }

            log.info("XA recover returned {} prepared transactions", preparedXids.size());
            return preparedXids;

        } catch (Exception e) {
            log.error("Failed to recover prepared transactions", e);
            throw new XAException(XAException.XAER_RMERR);
        } finally {
            if (session != null) {
                try {
                    poolProvider.returnSession(poolDataSource, session);
                } catch (Exception e) {
                    log.error("Failed to return session after recover()", e);
                }
            }
        }
    }

    /**
     * Forgets a heuristically completed XA transaction branch.
     * <p>
     * Currently not implemented. Most databases don't require explicit forget.
     * </p>
     *
     * @param xid the transaction branch identifier
     * @throws XAException with XAER_NOTA if not supported
     */
    public void xaForget(XidKey xid) throws XAException {
        log.debug("xaForget: xid={} (not implemented)", xid);
        throw new XAException(XAException.XAER_NOTA);
    }

    /**
     * Returns the transaction context for a given Xid.
     * Used by SQL execution path to route queries to the correct backend session.
     *
     * @param xid the transaction branch identifier
     * @return the transaction context, or null if not found
     */
    public TxContext getContext(XidKey xid) {
        return contexts.get(xid);
    }

    /**
     * Returns the backend session associated with a transaction.
     * <p>
     * This is called by SQL execution handlers to route queries to the correct
     * backend session for the active transaction.
     * </p>
     *
     * @param xid the transaction branch identifier
     * @return the backend session, or null if transaction not found or not in valid state
     */
    public XABackendSession getSessionForTransaction(XidKey xid) {
        TxContext ctx = contexts.get(xid);
        if (ctx != null && ctx.getState().canPerformWork()) {
            return ctx.getSession();
        }
        return null;
    }

    /**
     * Returns all completed transaction sessions for a given OJP session to the pool.
     * <p>
     * This method is called when XAConnection.close() is invoked. It identifies all
     * transactions that have completed (committed or rolled back) and returns their
     * backend sessions to the pool for reuse.
     * </p>
     * <p>
     * This implements the dual-condition lifecycle: backend sessions are returned only
     * when BOTH conditions are met:
     * 1. Transaction is complete (commit/rollback called)
     * 2. XAConnection is closed (OJP session terminating)
     * </p>
     *
     * @param ojpSessionId the OJP session identifier (from SessionInfo)
     * @return the number of sessions returned to pool
     */
    public int returnCompletedSessions(String ojpSessionId) {
        log.debug("[XA-RETURN-SESSIONS] returnCompletedSessions called for ojpSessionId={}, total contexts={}",
                ojpSessionId, contexts.size());
        int returnedCount = 0;
        List<XidKey> toRemove = new ArrayList<>();
        Set<XABackendSession> returnedSessions = new HashSet<>();  // Track sessions already returned

        // Find all completed transactions belonging to the specified OJP session and return their sessions
        for (Map.Entry<XidKey, TxContext> entry : contexts.entrySet()) {
            TxContext ctx = entry.getValue();
            XidKey xidKey = entry.getKey();
            log.debug("[XA-RETURN-SESSIONS] Evaluating xid={}, isComplete={}, ctxOjpSessionId={}, targetOjpSessionId={}, match={}",
                    xidKey, ctx.isTransactionComplete(), ctx.getOjpSessionId(), ojpSessionId,
                    ctx.isTransactionComplete() && ojpSessionId.equals(ctx.getOjpSessionId()));

            // IMPORTANT: Only return sessions that belong to the specified OJP session AND are complete
            if (ctx.isTransactionComplete() && ojpSessionId.equals(ctx.getOjpSessionId())) {
                toRemove.add(xidKey);

                // Return session to pool (but only once per unique session object)
                XABackendSession session = ctx.getSession();
                if (session != null) {
                    // Check if we've already returned this exact session object
                    if (returnedSessions.contains(session)) {
                        log.debug("[XA-POOL-RETURN] SKIP: Backend session already returned for xid={}, ojpSessionId={}", xidKey, ojpSessionId);
                    } else {
                        try {
                            log.debug("[XA-POOL-RETURN] BEFORE return: Returning backend session for xid={}, ojpSessionId={}", xidKey, ojpSessionId);
                            poolProvider.returnSession(poolDataSource, session);
                            returnedSessions.add(session);  // Mark as returned
                            returnedCount++;
                            log.debug("[XA-POOL-RETURN] AFTER return: Successfully returned backend session for xid={}, ojpSessionId={}", xidKey, ojpSessionId);
                        } catch (Exception e) {
                            log.error("[XA-POOL-RETURN] Failed to return session to pool for xid={}: {}", xidKey, e.getMessage(), e);
                            // Best effort: try to invalidate
                            try {
                                poolProvider.invalidateSession(poolDataSource, session);
                            } catch (Exception e2) {
                                log.error("Failed to invalidate session for xid={}", xidKey, e2);
                            }
                        }
                    }
                } else {
                    log.warn("[XA-RETURN-SESSIONS] Session is null for completed transaction xid={}", xidKey);
                }
            }
        }

        // Remove completed transactions from registry
        for (XidKey xid : toRemove) {
            contexts.remove(xid);
            log.debug("Removed completed transaction from registry: xid={}, ojpSessionId={}", xid, ojpSessionId);
        }

        log.debug("[XA-RETURN-SESSIONS] returnCompletedSessions complete: returned={}, removed={} contexts for ojpSessionId={}",
                returnedCount, toRemove.size(), ojpSessionId);

        return returnedCount;
    }

    /**
     * Closes the registry and releases all resources.
     * <p>
     * Should be called during proxy shutdown or when registry needs to be recreated.
     * Returns all active sessions to the pool and clears transaction contexts.
     * </p>
     */
    public void close() {
        try {
            if (!contexts.isEmpty()) {
                log.warn("Closing XATransactionRegistry with {} active transactions", contexts.size());
            }

            // Return all active sessions to pool
            for (TxContext ctx : contexts.values()) {
                try {
                    if (ctx != null && ctx.getSession() != null) {
                        poolProvider.returnSession(poolDataSource, ctx.getSession());
                    }
                } catch (Exception e) {
                    log.warn("Failed to return session during registry close: {}", e.getMessage());
                }
            }
            contexts.clear();

            // Close the pool datasource so it releases its connection pool and deregisters
            // any OpenTelemetry metric callbacks. Without this, old callbacks accumulate in
            // the OTel SDK each time a pool is recreated, causing DuplicateLabelsException.
            if (poolDataSource instanceof AutoCloseable) {
                try {
                    ((AutoCloseable) poolDataSource).close();
                } catch (Exception e) {
                    log.warn("Failed to close pool datasource during registry close: {}", e.getMessage());
                }
            }

            log.info("XA registry closed successfully");
        } catch (Exception e) {
            log.error("Error during XA registry close: {}", e.getMessage(), e);
        }
    }

    // Private helper methods

    private void returnSessionToPool(TxContext ctx) {
        XABackendSession session = ctx.getSession();
        if (session != null) {
            try {
                poolProvider.returnSession(poolDataSource, session);
            } catch (Exception e) {
                log.error("Failed to return session to pool", e);
                // Best effort: try to invalidate
                try {
                    poolProvider.invalidateSession(poolDataSource, session);
                } catch (Exception e2) {
                    log.error("Failed to invalidate session", e2);
                }
            }
        }
    }

    private static String flagsToString(int flags) {
        switch (flags) {
            case XAResource.TMNOFLAGS: return "TMNOFLAGS";
            case XAResource.TMJOIN: return "TMJOIN";
            case XAResource.TMRESUME: return "TMRESUME";
            case XAResource.TMSUCCESS: return "TMSUCCESS";
            case XAResource.TMFAIL: return "TMFAIL";
            case XAResource.TMSUSPEND: return "TMSUSPEND";
            case XAResource.TMSTARTRSCAN: return "TMSTARTRSCAN";
            case XAResource.TMENDRSCAN: return "TMENDRSCAN";
            default: return "UNKNOWN(" + flags + ")";
        }
    }
}
