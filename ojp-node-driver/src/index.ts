export { OjpClient, OjpClientOptions, QueryResult, OjpLobRef, OjpTypedParam } from './client/OjpClient';
export { OjpSqlError, OjpConnectionError } from './client/errors';
export { parseOjpUrl, ParsedOjpUrl, OjpServerEndpoint } from './client/connectionString';
export {
  SessionInfo,
  ParameterType,
  ParameterValue,
  ResultRow,
  OpResult,
  LobTypeProto,
  LobReference,
  TemporalType,
} from './proto/types';

// XA (distributed transaction) support.
export { OjpXADataSource } from './xa/OjpXADataSource';
export { OjpXAConnection } from './xa/OjpXAConnection';
export { OjpXAResource } from './xa/OjpXAResource';
export { OjpXaError } from './xa/OjpXaError';
export { Xid, xidEquals, xidToString } from './xa/xid';
export * as xaConstants from './xa/xaConstants';

