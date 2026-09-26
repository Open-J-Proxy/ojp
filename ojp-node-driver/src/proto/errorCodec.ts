import * as path from 'path';
import * as protobuf from 'protobufjs';
import { PROTO_PATH, GOOGLE_PROTO_INCLUDE_DIR } from './loader';
import { SqlErrorResponse } from './types';

/**
 * The ojp-server sends the `SqlErrorResponse` (error reason, sqlState, vendorCode, etc.)
 * serialized as binary protobuf in a "-bin" gRPC metadata entry (see
 * `GrpcExceptionHandler.sendSQLExceptionMetadata` in ojp-server) — NEVER in the gRPC
 * status text field `details`. That's why we need to decode this metadata manually to
 * recover the real error message (e.g. "Feature not supported: ...", invalid SQL,
 * constraint violation, etc.) instead of just a generic "13 INTERNAL: ".
 *
 * `@grpc/proto-loader` (used for the gRPC service itself) doesn't expose a usable
 * decoder for standalone message types — that's why we load the same .proto here again,
 * but directly via `protobufjs`, which returns a `Type` with `.decode()`.
 */
let cachedType: protobuf.Type | undefined;

function getSqlErrorResponseType(): protobuf.Type {
  if (!cachedType) {
    const root = new protobuf.Root();
    root.resolvePath = (_origin: string, target: string): string => {
      if (path.isAbsolute(target)) {
        return target;
      }
      return path.resolve(GOOGLE_PROTO_INCLUDE_DIR, target);
    };
    root.loadSync(PROTO_PATH, { keepCase: true });
    cachedType = root.lookupType('com.openjproxy.grpc.SqlErrorResponse');
  }
  return cachedType;
}

/**
 * Name of the binary gRPC metadata used by the ojp-server to carry the `SqlErrorResponse`
 * (standard gRPC convention: fully-qualified protobuf type name, lowercase, + "-bin" suffix).
 */
export const SQL_ERROR_METADATA_KEY = 'com.openjproxy.grpc.sqlerrorresponse-bin';

/** Decodes a `SqlErrorResponse` from the raw bytes of the "-bin" metadata. */
export function decodeSqlErrorResponse(buffer: Buffer): SqlErrorResponse | undefined {
  try {
    const type = getSqlErrorResponseType();
    const message = type.decode(buffer);
    return type.toObject(message, { enums: String, longs: String, defaults: true }) as SqlErrorResponse;
  } catch {
    return undefined;
  }
}
