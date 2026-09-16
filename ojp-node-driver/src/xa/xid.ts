import { XidProto } from '../proto/types';

/**
 * Distributed transaction identifier, mirroring `javax.transaction.xa.Xid` exactly:
 * a format identifier plus two opaque byte arrays (global transaction id + branch
 * qualifier). Xids are normally created/owned by a transaction manager (e.g. an
 * Atomikos/Narayana-style coordinator, or a hand-rolled 2PC coordinator) — this
 * driver never generates one on its own behalf.
 */
export interface Xid {
  formatId: number;
  globalTransactionId: Buffer;
  branchQualifier: Buffer;
}

/** Structural equality (JTA's `Xid` contract does not require reference equality). */
export function xidEquals(a: Xid, b: Xid): boolean {
  return (
    a.formatId === b.formatId
    && a.globalTransactionId.equals(b.globalTransactionId)
    && a.branchQualifier.equals(b.branchQualifier)
  );
}

/** Human-readable representation for logging/debugging (hex-encoded byte arrays). */
export function xidToString(xid: Xid): string {
  return `Xid(formatId=${xid.formatId}, gtrid=${xid.globalTransactionId.toString('hex')}, `
    + `bqual=${xid.branchQualifier.toString('hex')})`;
}

/** @internal Converts a driver-facing `Xid` into the wire-format `XidProto`. */
export function toXidProto(xid: Xid): XidProto {
  return {
    formatId: xid.formatId,
    globalTransactionId: xid.globalTransactionId,
    branchQualifier: xid.branchQualifier,
  };
}

/** @internal Converts a wire-format `XidProto` (e.g. from `recover()`) back into a driver-facing `Xid`. */
export function fromXidProto(proto: XidProto): Xid {
  return {
    formatId: proto.formatId,
    globalTransactionId: proto.globalTransactionId,
    branchQualifier: proto.branchQualifier,
  };
}
