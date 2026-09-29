import * as path from 'path';
import * as protoLoader from '@grpc/proto-loader';
import * as grpc from '@grpc/grpc-js';

// The gRPC contract (same one used by ojp-jdbc-driver and ojp-server) is distributed as a
// versioned, hash-pinned package (see ojp-grpc-contract/README.md) instead of being copied
// into this repo, so a contract change always requires a deliberate dependency bump here.
export const PROTO_PATH = require.resolve('@ojp/grpc-contract/proto/StatementService.proto');
// google-proto-files provides the google/protobuf/*.proto and google/type/*.proto includes
// used by StatementService.proto (Timestamp, Wrappers, Date, TimeOfDay).
export const GOOGLE_PROTO_INCLUDE_DIR = path.dirname(require.resolve('google-proto-files/package.json'));

let cachedPackageDefinition: protoLoader.PackageDefinition | undefined;

/**
 * Loads StatementService.proto (the same contract used by ojp-jdbc-driver and ojp-server)
 * and returns the gRPC package definition ready to use with @grpc/grpc-js.
 */
export function loadStatementServiceProto(): protoLoader.PackageDefinition {
  if (!cachedPackageDefinition) {
    cachedPackageDefinition = protoLoader.loadSync(PROTO_PATH, {
      keepCase: false,
      longs: String,
      enums: String,
      defaults: true,
      oneofs: true,
      includeDirs: [path.dirname(PROTO_PATH), GOOGLE_PROTO_INCLUDE_DIR],
    });
  }
  return cachedPackageDefinition;
}

export function loadStatementServiceGrpcObject(): grpc.GrpcObject {
  return grpc.loadPackageDefinition(loadStatementServiceProto());
}

