/**
 * Error raised by `OjpXAResource` operations, mirroring `javax.transaction.xa.XAException`:
 * carries a standard XA error code (see `xaConstants.ts`) so a transaction manager can
 * branch on it (e.g. retry on `XAER_RMFAIL`, treat `errorCode === 0` as a business-level
 * failure reported by the resource manager itself).
 *
 * `errorCode` defaults to `0`, matching the Java reference's `new XAException(message)`
 * constructor (which leaves `errorCode` at its uninitialized default) for business-level
 * failures reported by the server (`XaResponse.success === false`).
 */
export class OjpXaError extends Error {
  readonly errorCode: number;
  readonly cause?: unknown;

  constructor(message: string, errorCode = 0, cause?: unknown) {
    super(message);
    this.name = 'OjpXaError';
    this.errorCode = errorCode;
    this.cause = cause;
    Object.setPrototypeOf(this, OjpXaError.prototype);
  }
}
