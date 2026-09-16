/**
 * TypeScript types that mirror the StatementService.proto messages.
 * Used as the data contract for calls made via @grpc/proto-loader
 * (loaded with keepCase:false, enums:String, oneofs:true).
 */

export interface PropertyEntry {
  key: string;
  boolValue?: boolean;
  intValue?: number;
  longValue?: string;
  floatValue?: number;
  doubleValue?: number;
  stringValue?: string;
  bytesValue?: Buffer;
}

export interface ConnectionDetails {
  url: string;
  user: string;
  password: string;
  clientUUID: string;
  properties?: PropertyEntry[];
  isXA?: boolean;
  serverEndpoints?: string[];
  clusterHealth?: string;
}

export type TransactionStatus = 'TRX_ACTIVE' | 'TRX_COMMITED' | 'TRX_ROLLBACK';
export type SessionStatus = 'SESSION_ACTIVE' | 'SESSION_TERMINATED';

export interface TransactionInfo {
  transactionUUID: string;
  transactionStatus: TransactionStatus;
}

export interface SessionInfo {
  connHash: string;
  clientUUID: string;
  sessionUUID: string;
  transactionInfo?: TransactionInfo;
  sessionStatus: SessionStatus;
  isXA?: boolean;
  targetServer?: string;
  clusterHealth?: string;
  clientCount?: number;
  maxAdmission?: number;
  observedPeak?: number;
}

/** ParameterTypeProto enum from the .proto, used to type each ParameterProto. */
export type ParameterType =
  | 'PT_NULL' | 'PT_BOOLEAN' | 'PT_BYTE' | 'PT_SHORT' | 'PT_INT' | 'PT_LONG'
  | 'PT_FLOAT' | 'PT_DOUBLE' | 'PT_BIG_DECIMAL' | 'PT_STRING' | 'PT_BYTES'
  | 'PT_DATE' | 'PT_TIME' | 'PT_TIMESTAMP' | 'PT_ASCII_STREAM' | 'PT_UNICODE_STREAM'
  | 'PT_BINARY_STREAM' | 'PT_OBJECT' | 'PT_CHARACTER_READER' | 'PT_REF' | 'PT_BLOB'
  | 'PT_CLOB' | 'PT_ARRAY' | 'PT_URL' | 'PT_ROW_ID' | 'PT_N_STRING'
  | 'PT_N_CHARACTER_STREAM' | 'PT_N_CLOB' | 'PT_SQL_XML';

export type TemporalType =
  | 'TEMPORAL_TYPE_UNSPECIFIED' | 'TEMPORAL_TYPE_TIMESTAMP' | 'TEMPORAL_TYPE_CALENDAR'
  | 'TEMPORAL_TYPE_OFFSET_DATE_TIME' | 'TEMPORAL_TYPE_LOCAL_DATE_TIME' | 'TEMPORAL_TYPE_INSTANT'
  | 'TEMPORAL_TYPE_LOCAL_DATE' | 'TEMPORAL_TYPE_LOCAL_TIME' | 'TEMPORAL_TYPE_OFFSET_TIME';

export interface TimestampWithZone {
  instant?: { seconds: string; nanos: number };
  timezone?: string;
  originalType?: TemporalType;
}

export interface ParameterValue {
  boolValue?: boolean;
  intValue?: number;
  longValue?: string;
  floatValue?: number;
  doubleValue?: number;
  stringValue?: string;
  bytesValue?: Buffer;
  intArrayValue?: { values: number[] };
  longArrayValue?: { values: string[] };
  isNull?: boolean;
  timestampValue?: TimestampWithZone;
  dateValue?: { year: number; month: number; day: number };
  timeValue?: { hours: number; minutes: number; seconds: number; nanos: number };
  urlValue?: string;
  rowidValue?: string;
  uuidValue?: string;
  bigintegerValue?: string;
  stringArrayValue?: { values: string[] };
  rowidlifetimeValue?: string;
}

export interface ParameterProto {
  index: number;
  type: ParameterType;
  values: ParameterValue[];
}

export interface StatementRequest {
  session: SessionInfo;
  sql: string;
  parameters?: ParameterProto[];
  statementUUID?: string;
  properties?: PropertyEntry[];
}

export interface ResultRow {
  columns: ParameterValue[];
}

export interface OpQueryResultProto {
  resultSetUUID: string;
  labels: string[];
  rows: ResultRow[];
}

export type ResultType = 'INTEGER' | 'RESULT_SET_DATA' | 'UUID_STRING';

export interface OpResult {
  session: SessionInfo;
  type: ResultType;
  intValue?: number;
  queryResult?: OpQueryResultProto;
  uuidValue?: string;
  uuid?: string;
  flag?: string;
}

export interface ResultSetFetchRequest {
  session: SessionInfo;
  resultSetUUID: string;
  size: number;
}

export interface SessionTerminationStatus {
  terminated: boolean;
}

export interface SqlErrorResponse {
  reason: string;
  sqlState: string;
  vendorCode: number;
  sqlErrorType: 'SQL_EXCEPTION' | 'SQL_DATA_EXCEPTION' | 'SQL_TRANSIENT_CONNECTION_EXCEPTION';
}

/** LobType enum from the .proto. */
export type LobTypeProto =
  | 'LT_BLOB' | 'LT_CLOB' | 'LT_BINARY_STREAM' | 'LT_ASCII_STREAM'
  | 'LT_UNICODE_STREAM' | 'LT_CHARACTER_STREAM';

export interface LobReference {
  session: SessionInfo;
  uuid: string;
  bytesWritten?: number;
  lobType: LobTypeProto;
  columnIndex?: number;
  stmtUUID?: string;
}

export interface ReadLobRequest {
  lobReference: LobReference;
  /** int64 in the .proto — serialized/deserialized as a string (proto-loader's longs:String option). */
  position: string;
  length: number;
}

export interface LobDataBlock {
  session: SessionInfo;
  /** int64 in the .proto — serialized/deserialized as a string (proto-loader's longs:String option). */
  position: string;
  data: Buffer;
  lobType: LobTypeProto;
  metadata?: PropertyEntry[];
}

/**
 * Distributed transaction identifier, mirroring javax.transaction.xa.Xid's three
 * fields exactly (formatId + globalTransactionId + branchQualifier).
 */
export interface XidProto {
  formatId: number;
  globalTransactionId: Buffer;
  branchQualifier: Buffer;
}

export interface XaStartRequest {
  session: SessionInfo;
  xid: XidProto;
  flags: number;
}

export interface XaEndRequest {
  session: SessionInfo;
  xid: XidProto;
  flags: number;
}

export interface XaPrepareRequest {
  session: SessionInfo;
  xid: XidProto;
}

export interface XaPrepareResponse {
  session: SessionInfo;
  /** XA_OK (0) or XA_RDONLY (3) — see xaConstants.ts. */
  result: number;
}

export interface XaCommitRequest {
  session: SessionInfo;
  xid: XidProto;
  onePhase: boolean;
}

export interface XaRollbackRequest {
  session: SessionInfo;
  xid: XidProto;
}

export interface XaRecoverRequest {
  session: SessionInfo;
  flag: number;
}

export interface XaRecoverResponse {
  session: SessionInfo;
  xids: XidProto[];
}

export interface XaForgetRequest {
  session: SessionInfo;
  xid: XidProto;
}

export interface XaSetTransactionTimeoutRequest {
  session: SessionInfo;
  seconds: number;
}

export interface XaSetTransactionTimeoutResponse {
  session: SessionInfo;
  success: boolean;
}

export interface XaGetTransactionTimeoutRequest {
  session: SessionInfo;
}

export interface XaGetTransactionTimeoutResponse {
  session: SessionInfo;
  seconds: number;
}

export interface XaIsSameRMRequest {
  session1: SessionInfo;
  session2: SessionInfo;
}

export interface XaIsSameRMResponse {
  isSame: boolean;
}

/** Generic response for XA operations that only report success/failure (start/end/commit/rollback/forget). */
export interface XaResponse {
  session: SessionInfo;
  success: boolean;
  message: string;
}
