import * as grpc from '@grpc/grpc-js';
import { SqlErrorResponse } from '../proto/types';
import { decodeSqlErrorResponse, SQL_ERROR_METADATA_KEY } from '../proto/errorCodec';

/**
 * Typed error equivalent to java.sql.SQLException, propagated by the ojp-server
 * via gRPC metadata (sqlState/vendorCode/sqlErrorType) when a SQL operation fails.
 */
export class OjpSqlError extends Error {
  public readonly sqlState?: string;
  public readonly vendorCode?: number;
  public readonly sqlErrorType?: SqlErrorResponse['sqlErrorType'];

  constructor(message: string, details?: Partial<SqlErrorResponse>) {
    super(message);
    this.name = 'OjpSqlError';
    this.sqlState = details?.sqlState;
    this.vendorCode = details?.vendorCode;
    this.sqlErrorType = details?.sqlErrorType;
  }
}

/** Connection/transport error with the ojp-server (unavailable gRPC channel, timeout, etc.). */
export class OjpConnectionError extends Error {
  constructor(message: string, public readonly cause?: unknown) {
    super(message);
    this.name = 'OjpConnectionError';
  }
}

/**
 * Extracts a SqlErrorResponse serialized in the binary gRPC metadata (key
 * "com.openjproxy.grpc.sqlerrorresponse-bin") and converts it into an OjpSqlError. The
 * gRPC status text field `details` always comes back empty for these errors — the
 * ojp-server carries the real message only in the binary metadata (see
 * GrpcExceptionHandler on the server) — that's why extracting the metadata is the main
 * path, not a fallback.
 *
 * If there's no structured metadata, the gRPC status code is checked next
 * (`isConnectionLevelError`) BEFORE falling back to the plain-text `details` field:
 * transport-level failures (unreachable server, connection refused/reset, cancelled
 * call) also populate `details` with a human-readable message (e.g. "No connection
 * established. Last error: connect ECONNREFUSED ..."), which is NOT a SQL error — it
 * must be classified as OjpConnectionError so multinode failover can react to it.
 */
export function toOjpError(err: unknown): Error {
  if (err instanceof Error) {
    const grpcError = err as Error & { code?: number; details?: string; metadata?: { get(key: string): unknown[] } };
    if (grpcError.metadata && typeof grpcError.metadata.get === 'function') {
      const values = grpcError.metadata.get(SQL_ERROR_METADATA_KEY);
      const raw = values && values[0];
      if (Buffer.isBuffer(raw)) {
        const decoded = decodeSqlErrorResponse(raw);
        if (decoded) {
          return new OjpSqlError(decoded.reason || grpcError.message, decoded);
        }
      }
    }
    if (isConnectionLevelError(grpcError)) {
      return new OjpConnectionError(grpcError.message, err);
    }
    if (grpcError.details) {
      return new OjpSqlError(grpcError.details);
    }
    return new OjpConnectionError(grpcError.message, err);
  }
  return new OjpConnectionError(String(err));
}

/**
 * Message fragments that identify a connection-level/transport failure when the gRPC
 * status code alone is ambiguous (e.g. `UNKNOWN`). Mirrors the non-gRPC-code text
 * matching done by `GrpcExceptionHandler` in the reference JDBC driver.
 */
const CONNECTION_LEVEL_MESSAGE_PATTERN = /connection|timeout|unavailable|failed to connect|no healthy servers/i;

/**
 * Classifies whether a raw gRPC error represents a connection-level/transport failure
 * (unreachable server, timed-out dial, network error) as opposed to a database/SQL-level
 * error (syntax error, bad credentials, missing table, etc.). Mirrors
 * `GrpcExceptionHandler` in the reference JDBC driver: only connection-level failures
 * should mark a multinode endpoint unhealthy and trigger failover to another node — SQL
 * errors must not, since they'd fail identically against any node.
 */
export function isConnectionLevelError(err: unknown): boolean {
  const grpcError = err as { code?: number; message?: string; details?: string } | undefined;
  if (!grpcError || typeof grpcError.code !== 'number') {
    return false;
  }
  if (
    grpcError.code === grpc.status.UNAVAILABLE
    || grpcError.code === grpc.status.DEADLINE_EXCEEDED
    // CANCELLED shows up when the underlying HTTP/2 stream/channel is torn down by the
    // transport itself (e.g. a node that just restarted and hasn't finished bringing its
    // gRPC service up yet) rather than by an explicit client-side call.cancel(). Calls we
    // deliberately cancel ourselves (early iteration break in streamOpResults) already
    // have their CANCELLED error swallowed before it ever reaches this function, so
    // treating it as connection-level here is safe and matches its real-world causes.
    || grpcError.code === grpc.status.CANCELLED
  ) {
    return true;
  }
  if (grpcError.code === grpc.status.UNKNOWN) {
    return CONNECTION_LEVEL_MESSAGE_PATTERN.test(grpcError.message ?? grpcError.details ?? '');
  }
  return false;
}
