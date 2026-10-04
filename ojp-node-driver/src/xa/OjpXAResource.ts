import * as grpc from '@grpc/grpc-js';
import { isConnectionLevelError } from '../client/errors';
import { StatementServiceClient } from '../client/statementServiceClient';
import {
  SessionInfo,
  XaCommitRequest,
  XaEndRequest,
  XaForgetRequest,
  XaGetTransactionTimeoutRequest,
  XaGetTransactionTimeoutResponse,
  XaIsSameRMRequest,
  XaIsSameRMResponse,
  XaPrepareRequest,
  XaPrepareResponse,
  XaRecoverRequest,
  XaRecoverResponse,
  XaResponse,
  XaRollbackRequest,
  XaSetTransactionTimeoutRequest,
  XaSetTransactionTimeoutResponse,
  XaStartRequest,
} from '../proto/types';
import { OjpXaError } from './OjpXaError';
import { fromXidProto, toXidProto, Xid } from './xid';
import * as XA from './xaConstants';

/**
 * Dependencies injected by whoever creates an `OjpXAResource` (currently only
 * `OjpClient.createXAResource()`), decoupling the RPC wrapper from how the gRPC client/
 * session/endpoint-health tracking are actually managed.
 */
export interface OjpXAResourceHost {
  getClient(): StatementServiceClient;
  getSession(): SessionInfo;
  updateSession(session: SessionInfo): void;
  /** Maps a raw gRPC error to this driver's error hierarchy, marking the bound endpoint unhealthy as a side effect when applicable. */
  mapError(err: unknown): Error;
}

/** Transient gRPC status codes that `recover()` maps to `XAER_RMFAIL`, matching the reference JDBC driver's narrower check for this specific operation (see class docs). */
function isTransientRecoverError(err: unknown): boolean {
  const grpcError = err as { code?: number } | undefined;
  return grpcError?.code === grpc.status.DEADLINE_EXCEEDED || grpcError?.code === grpc.status.UNAVAILABLE;
}

/**
 * Wraps the ojp-server's dedicated XA RPCs (`xaStart`/`xaEnd`/`xaPrepare`/`xaCommit`/
 * `xaRollback`/`xaRecover`/`xaForget`/`xaSetTransactionTimeout`/`xaGetTransactionTimeout`/
 * `xaIsSameRM`) behind an API mirroring `javax.transaction.xa.XAResource`, for use by a
 * Node.js distributed transaction manager (e.g. a hand-rolled 2PC coordinator, or a
 * library that speaks this shape). Obtained via `OjpClient.createXAResource()` (client
 * created with `{ xa: true }`) or `OjpXAConnection.getXAResource()`.
 *
 * All operations reject with an `OjpXaError` carrying a standard XA error code:
 * - `errorCode === 0` — business-level failure reported by the server (`XaResponse.
 *   success === false`), mirroring the reference JDBC driver's `new XAException(message)`
 *   (uninitialized/default error code).
 * - `XAER_RMFAIL` — the resource manager (ojp-server) is unavailable (connection-level
 *   transport failure). Only assigned for `start()` and `recover()`, matching the Java
 *   reference; every other operation maps a transport failure to `XAER_RMERR` uniformly.
 * - `XAER_RMERR` — any other resource-manager-side error, including all non-connection-
 *   level transport failures.
 *
 * Known scope reduction vs. the reference JDBC driver's `OjpXAResource` (documented in
 * README.md's "XA support" section): no cross-node retry-with-session-recreation on a
 * connection-level failure in `start()`, and no proactive multinode health-listener
 * invalidation. Every operation still marks the bound endpoint unhealthy for future
 * `connect()` calls via the injected `mapError` callback, consistent with the ordinary
 * SQL path.
 */
export class OjpXAResource {
  constructor(private readonly host: OjpXAResourceHost) {}

  /** Starts work on behalf of a transaction branch. `flags`: `TMNOFLAGS` (new branch), `TMJOIN`, or `TMRESUME`. */
  async start(xid: Xid, flags: number = XA.TMNOFLAGS): Promise<void> {
    const request: XaStartRequest = { session: this.host.getSession(), xid: toXidProto(xid), flags };
    const response = await this.runUnary<XaResponse>(
      (client, cb) => client.xaStart(request, cb),
      (err) => (isConnectionLevelError(err) ? XA.XAER_RMFAIL : XA.XAER_RMERR),
    );
    this.assertSuccess(response);
  }

  /** Ends the association of the calling thread with the transaction branch. `flags`: `TMSUCCESS`, `TMFAIL`, or `TMSUSPEND`. */
  async end(xid: Xid, flags: number = XA.TMSUCCESS): Promise<void> {
    const request: XaEndRequest = { session: this.host.getSession(), xid: toXidProto(xid), flags };
    const response = await this.runUnary<XaResponse>((client, cb) => client.xaEnd(request, cb), () => XA.XAER_RMERR);
    this.assertSuccess(response);
  }

  /** Asks the resource manager to prepare for a commit. Returns `XA_OK` or `XA_RDONLY` (see xaConstants.ts). */
  async prepare(xid: Xid): Promise<number> {
    const request: XaPrepareRequest = { session: this.host.getSession(), xid: toXidProto(xid) };
    const response = await this.runUnary<XaPrepareResponse>(
      (client, cb) => client.xaPrepare(request, cb),
      () => XA.XAER_RMERR,
    );
    this.host.updateSession(response.session);
    return response.result;
  }

  /** Commits the transaction branch. `onePhase=true` skips the prepare phase (single resource manager optimization). */
  async commit(xid: Xid, onePhase: boolean): Promise<void> {
    const request: XaCommitRequest = { session: this.host.getSession(), xid: toXidProto(xid), onePhase };
    const response = await this.runUnary<XaResponse>((client, cb) => client.xaCommit(request, cb), () => XA.XAER_RMERR);
    this.assertSuccess(response);
  }

  /** Rolls back work done on behalf of the transaction branch. */
  async rollback(xid: Xid): Promise<void> {
    const request: XaRollbackRequest = { session: this.host.getSession(), xid: toXidProto(xid) };
    const response = await this.runUnary<XaResponse>((client, cb) => client.xaRollback(request, cb), () => XA.XAER_RMERR);
    this.assertSuccess(response);
  }

  /**
   * Obtains the list of prepared (in-doubt) transaction branches, for recovery after a
   * crash. `flag` should be `TMSTARTRSCAN` on the first call, `TMNOFLAGS` on subsequent
   * calls, and OR'd with `TMENDRSCAN` on the last one (or `TMSTARTRSCAN | TMENDRSCAN` to
   * scan everything in a single call) — same contract as `XAResource.recover`.
   */
  async recover(flag: number): Promise<Xid[]> {
    const request: XaRecoverRequest = { session: this.host.getSession(), flag };
    const response = await this.runUnary<XaRecoverResponse>(
      (client, cb) => client.xaRecover(request, cb),
      (err) => (isTransientRecoverError(err) ? XA.XAER_RMFAIL : XA.XAER_RMERR),
    );
    this.host.updateSession(response.session);
    return (response.xids ?? []).map(fromXidProto);
  }

  /** Tells the resource manager to forget about a heuristically completed transaction branch. */
  async forget(xid: Xid): Promise<void> {
    const request: XaForgetRequest = { session: this.host.getSession(), xid: toXidProto(xid) };
    const response = await this.runUnary<XaResponse>((client, cb) => client.xaForget(request, cb), () => XA.XAER_RMERR);
    this.assertSuccess(response);
  }

  /** Sets the transaction timeout value (seconds) for this resource manager; `0` resets it to the default. */
  async setTransactionTimeout(seconds: number): Promise<boolean> {
    const request: XaSetTransactionTimeoutRequest = { session: this.host.getSession(), seconds };
    const response = await this.runUnary<XaSetTransactionTimeoutResponse>(
      (client, cb) => client.xaSetTransactionTimeout(request, cb),
      () => XA.XAER_RMERR,
    );
    this.host.updateSession(response.session);
    return response.success;
  }

  /** Gets the current transaction timeout value (seconds) for this resource manager. */
  async getTransactionTimeout(): Promise<number> {
    const request: XaGetTransactionTimeoutRequest = { session: this.host.getSession() };
    const response = await this.runUnary<XaGetTransactionTimeoutResponse>(
      (client, cb) => client.xaGetTransactionTimeout(request, cb),
      () => XA.XAER_RMERR,
    );
    this.host.updateSession(response.session);
    return response.seconds;
  }

  /** Checks whether `other` represents the same resource manager as this one (used to decide join-vs-separate branches). */
  async isSameRM(other: OjpXAResource): Promise<boolean> {
    const request: XaIsSameRMRequest = { session1: this.host.getSession(), session2: other.host.getSession() };
    const response = await this.runUnary<XaIsSameRMResponse>(
      (client, cb) => client.xaIsSameRM(request, cb),
      () => XA.XAER_RMERR,
    );
    return response.isSame;
  }

  /** Shared unary-call + transport-error-wrapping helper. `resolveErrorCode` decides the XA error code for a transport failure. */
  private async runUnary<TRes>(
    invoke: (client: StatementServiceClient, cb: (err: grpc.ServiceError | null, res: TRes) => void) => void,
    resolveErrorCode: (err: unknown) => number,
  ): Promise<TRes> {
    const client = this.host.getClient();
    try {
      return await new Promise<TRes>((resolve, reject) => {
        invoke(client, (err, res) => (err ? reject(err) : resolve(res)));
      });
    } catch (err) {
      const mapped = this.host.mapError(err);
      throw new OjpXaError(mapped.message, resolveErrorCode(err), err);
    }
  }

  /** Updates local session state and, for success/failure-shaped responses, throws on a business-level failure (errorCode 0). */
  private assertSuccess(response: XaResponse): void {
    this.host.updateSession(response.session);
    if (!response.success) {
      throw new OjpXaError(response.message, 0);
    }
  }
}
