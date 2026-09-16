import * as grpc from '@grpc/grpc-js';
import {
  ConnectionDetails,
  LobDataBlock,
  LobReference,
  OpResult,
  ReadLobRequest,
  ResultSetFetchRequest,
  SessionInfo,
  SessionTerminationStatus,
  StatementRequest,
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

/** Shape of the generated gRPC client for `StatementService`, typed against this driver's TS types. */
export interface StatementServiceClient extends grpc.Client {
  connect(request: ConnectionDetails, callback: (err: grpc.ServiceError | null, response: SessionInfo) => void): void;
  connect(
    request: ConnectionDetails,
    options: grpc.CallOptions,
    callback: (err: grpc.ServiceError | null, response: SessionInfo) => void,
  ): void;
  executeUpdate(request: StatementRequest, callback: (err: grpc.ServiceError | null, response: OpResult) => void): void;
  executeQuery(request: StatementRequest): grpc.ClientReadableStream<OpResult>;
  fetchNextRows(request: ResultSetFetchRequest, callback: (err: grpc.ServiceError | null, response: OpResult) => void): void;
  createLob(): grpc.ClientDuplexStream<LobDataBlock, LobReference>;
  readLob(request: ReadLobRequest): grpc.ClientReadableStream<LobDataBlock>;
  terminateSession(request: SessionInfo, callback: (err: grpc.ServiceError | null, response: SessionTerminationStatus) => void): void;
  startTransaction(request: SessionInfo, callback: (err: grpc.ServiceError | null, response: SessionInfo) => void): void;
  commitTransaction(request: SessionInfo, callback: (err: grpc.ServiceError | null, response: SessionInfo) => void): void;
  rollbackTransaction(request: SessionInfo, callback: (err: grpc.ServiceError | null, response: SessionInfo) => void): void;

  // XA (distributed transaction) operations — see src/xa/OjpXAResource.ts.
  xaStart(request: XaStartRequest, callback: (err: grpc.ServiceError | null, response: XaResponse) => void): void;
  xaEnd(request: XaEndRequest, callback: (err: grpc.ServiceError | null, response: XaResponse) => void): void;
  xaPrepare(request: XaPrepareRequest, callback: (err: grpc.ServiceError | null, response: XaPrepareResponse) => void): void;
  xaCommit(request: XaCommitRequest, callback: (err: grpc.ServiceError | null, response: XaResponse) => void): void;
  xaRollback(request: XaRollbackRequest, callback: (err: grpc.ServiceError | null, response: XaResponse) => void): void;
  xaRecover(request: XaRecoverRequest, callback: (err: grpc.ServiceError | null, response: XaRecoverResponse) => void): void;
  xaForget(request: XaForgetRequest, callback: (err: grpc.ServiceError | null, response: XaResponse) => void): void;
  xaSetTransactionTimeout(
    request: XaSetTransactionTimeoutRequest,
    callback: (err: grpc.ServiceError | null, response: XaSetTransactionTimeoutResponse) => void,
  ): void;
  xaGetTransactionTimeout(
    request: XaGetTransactionTimeoutRequest,
    callback: (err: grpc.ServiceError | null, response: XaGetTransactionTimeoutResponse) => void,
  ): void;
  xaIsSameRM(request: XaIsSameRMRequest, callback: (err: grpc.ServiceError | null, response: XaIsSameRMResponse) => void): void;
}
