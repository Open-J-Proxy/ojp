/**
 * JTA/XA standard constants, mirroring `javax.transaction.xa.XAResource` and
 * `javax.transaction.xa.XAException` exactly (values confirmed against a local JDK 25
 * install via `javap -constants javax.transaction.xa.XAResource/XAException`, since
 * these are part of the public java.xa module's binary contract and never change).
 *
 * Kept here (instead of importing a third-party npm XA package) because there is no
 * de-facto standard XA library in the Node ecosystem — the reference is the JTA spec
 * itself, which the ojp-server/ojp-jdbc-driver already implement faithfully.
 */

/** No special flags (used e.g. for a brand new branch in `start`). */
export const TMNOFLAGS = 0;
/** Caller is joining an existing transaction branch. */
export const TMJOIN = 2097152;
/** Caller is using the `xa_recover` flag to end a recovery scan. */
export const TMENDRSCAN = 8388608;
/** Caller is disassociating from a transaction branch, marking it as failed. */
export const TMFAIL = 536870912;
/** Caller is using a one-phase commit optimization in `commit`. */
export const TMONEPHASE = 1073741824;
/** Caller is resuming an association with a suspended transaction branch. */
export const TMRESUME = 134217728;
/** Caller is starting a recovery scan. */
export const TMSTARTRSCAN = 16777216;
/** Caller is disassociating from a transaction branch, marking it as successful. */
export const TMSUCCESS = 67108864;
/** Caller is suspending (not ending) its association with a transaction branch. */
export const TMSUSPEND = 33554432;

/** Normal (successful) return value for XA operations. */
export const XA_OK = 0;
/** Returned by `prepare()` when the resource manager did no work and needs no `commit`/`rollback`. */
export const XA_RDONLY = 3;

/** Resource manager has rolled back the branch; base value for the XA_RB* range check. */
export const XA_RBBASE = 100;
/** Rollback caused by an unspecified reason. */
export const XA_RBROLLBACK = 100;
/** Rollback caused by a communication failure. */
export const XA_RBCOMMFAIL = 101;
/** Rollback caused by a deadlock. */
export const XA_RBDEADLOCK = 102;
/** Rollback caused by a condition that violates the integrity of the resource. */
export const XA_RBINTEGRITY = 103;
/** Rollback caused by some other reason not fitting any of the other XA_RB* codes. */
export const XA_RBOTHER = 104;
/** Rollback caused by a protocol error in the resource manager. */
export const XA_RBPROTO = 105;
/** Rollback caused by a timeout. */
export const XA_RBTIMEOUT = 106;
/** Rollback caused by the resource manager becoming unavailable transiently. */
export const XA_RBTRANSIENT = 107;
/** Highest value in the XA_RB* rollback-reason range (same value as XA_RBTRANSIENT). */
export const XA_RBEND = 107;
/** Resource manager does not support migration of transactions. */
export const XA_NOMIGRATE = 9;
/** Transaction branch may have been heuristically completed with a mix of commit/rollback (hazard). */
export const XA_HEURHAZ = 8;
/** Transaction branch has been heuristically committed. */
export const XA_HEURCOM = 7;
/** Transaction branch has been heuristically rolled back. */
export const XA_HEURRB = 6;
/** Transaction branch has been heuristically completed with a mix of commit/rollback decisions. */
export const XA_HEURMIX = 5;
/** Routine returned with no effect and may be reissued. */
export const XA_RETRY = 4;

/** Resource manager is doing work asynchronously. */
export const XAER_ASYNC = -2;
/** Resource manager error occurred in the transaction branch. */
export const XAER_RMERR = -3;
/** The XID is not valid. */
export const XAER_NOTA = -4;
/** Invalid arguments were given. */
export const XAER_INVAL = -5;
/** Routine was invoked in an improper context. */
export const XAER_PROTO = -6;
/** Resource manager is unavailable. */
export const XAER_RMFAIL = -7;
/** The XID already exists. */
export const XAER_DUPID = -8;
/** The resource manager is doing work outside the global transaction. */
export const XAER_OUTSIDE = -9;
