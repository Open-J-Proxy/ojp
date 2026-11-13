import * as grpc from '@grpc/grpc-js';
import { randomUUID } from 'crypto';
import { loadStatementServiceGrpcObject } from '../proto/loader';
import { OjpServerEndpoint, ParsedOjpUrl, parseOjpUrl } from './connectionString';
import { toOjpError, OjpConnectionError, isConnectionLevelError } from './errors';
import { fromParameterValue, OjpLobRef, OjpNullParam, toParameterProtos } from './valueMapper';
import { markEndpointUnhealthy, orderEndpointsForAttempt } from './endpointHealth';
import { StatementServiceClient } from './statementServiceClient';
import { OjpXAResource } from '../xa/OjpXAResource';
import { looksLikeQuery, returnsRowsFromDml } from './sqlStatementClassifier';
import { OjpPreparedStatement, OjpStatement } from './statement';
import {
  CallType,
  CallResourceRequest,
  CallResourceResponse,
  ConnectionDetails,
  LobDataBlock,
  LobReference,
  OpResult,
  PropertyEntry,
  ReadLobRequest,
  ResourceType,
  ResultSetFetchRequest,
  SessionInfo,
  SessionTerminationStatus,
  StatementRequest,
} from '../proto/types';

export { OjpLobRef, OjpTypedParam } from './valueMapper';
export { OjpNullParam } from './valueMapper';

export interface OjpClientStatementOptions {
  /** Request generated keys from an update, where the database supports them. */
  generatedKeys?: boolean;
}

export interface QueryColumnMetadata {
  index: number;
  label: string;
}

export interface QueryResultMetadata {
  columnCount: number;
  columns: QueryColumnMetadata[];
}

export interface OjpClientOptions {
  /** Target database user (passed through to the ojp-server). */
  user?: string;
  /** Target database password. */
  password?: string;
  /** Timeout (ms) applied to each unary gRPC call. Default: 30000. */
  callTimeoutMs?: number;
  /** Use an insecure gRPC channel (no TLS). Default: true (local/dev MVP). */
  insecure?: boolean;
  /**
   * Opens this session as an XA (distributed transaction) resource. When `true`,
   * transaction demarcation must go through `createXAResource()` — `startTransaction`/
   * `commit`/`rollback` are disabled on the session itself (mirroring the reference
   * JDBC driver's `OjpXALogicalConnection`). Default: false.
   */
  xa?: boolean;
}

export interface QueryResult {
  /** Column names, in the order returned by the result set. */
  columns: string[];
  /** Rows already converted to native JS values. */
  rows: Record<string, unknown>[];
  /** Basic result metadata available in the OJP streaming contract. */
  metadata: QueryResultMetadata;
}

/**
 * Flag sent by the ojp-server in `OpResult.flag` when the result set is in "row-by-row"
 * mode (used only by SQL Server and DB2 when the query returns LOBs: advancing the
 * cursor ahead would invalidate the LOBs, so the server sends one row at a time and
 * waits for the client to request the next one via fetchNextRows). Must mirror exactly
 * `CommonConstants.RESULT_SET_ROW_BY_ROW_MODE` in ojp-grpc-commons.
 */
const ROW_BY_ROW_MODE_FLAG = 'RESULT_SET_ROW_BY_ROW_MODE';

/**
 * Prefix used by the ojp-server to signal that a CLOB column returned only a reference
 * (UUID) instead of the already-hydrated content — unlike BLOB/VARBINARY, which always
 * arrive with the complete bytes. Must mirror exactly `CommonConstants.OJP_CLOB_PREFIX`
 * in ojp-grpc-commons.
 */
const CLOB_PLACEHOLDER_PREFIX = 'OJP_CLOB_PREFIX:';

/** Maximum size of each block sent in `createLob`, mirroring `CommonConstants.MAX_LOB_DATA_BLOCK_SIZE` (64KB) on the server. */
const LOB_CHUNK_SIZE = 65536;

/**
 * "Length" sent in `readLob` to request the entire LOB starting at `position=1`.
 * The server computes the actual available size from the stored LOB (see
 * `ReadLobAction.inputStreamFromClob`/`inputStreamFromBlob`: when `position + length`
 * exceeds the actual size, it uses `lobLength - position + 1`), so there's no need
 * to fetch the LOB size beforehand.
 */
const READ_LOB_MAX_LENGTH = 2147483647;
const CLIENT_UUID = randomUUID();

interface StreamedRow {
  columns: string[];
  row?: Record<string, unknown>;
}

/**
 * Node.js client for the ojp-server. Wraps the StatementService gRPC channel and the
 * lifecycle of a session (connect -> execute* -> [transaction] -> terminateSession).
 *
 * Does not implement a connection pool: the actual pool (pluggable via SPI on the server
 * side) lives in the ojp-server; each OjpClient instance corresponds to a logical
 * session, just like a java.sql.Connection in the JDBC driver.
 */
export class OjpClient {
  private grpcClient?: StatementServiceClient;
  private boundServerEndpoint?: OjpServerEndpoint;
  private readonly credentials: grpc.ChannelCredentials;
  private readonly callTimeoutMs: number;
  private session?: SessionInfo;
  private readonly parsedUrl: ParsedOjpUrl;
  private readonly clientUUID: string;
  private connecting = false;
  private closed = false;

  constructor(ojpUrl: string, private readonly options: OjpClientOptions = {}) {
    this.parsedUrl = parseOjpUrl(ojpUrl);
    this.callTimeoutMs = options.callTimeoutMs ?? 30_000;
    this.clientUUID = CLIENT_UUID;
    this.credentials = options.insecure === false
      ? grpc.credentials.createSsl()
      : grpc.credentials.createInsecure();
  }

  /** The ojp-server endpoint this session is currently bound to (undefined before a successful `connect()`). */
  get boundEndpoint(): OjpServerEndpoint | undefined {
    return this.boundServerEndpoint;
  }

  private buildGrpcClient(endpoint: OjpServerEndpoint): StatementServiceClient {
    const grpcObject = loadStatementServiceGrpcObject() as unknown as {
      com: { openjproxy: { grpc: { StatementService: grpc.ServiceClientConstructor } } };
    };
    return new grpcObject.com.openjproxy.grpc.StatementService(
      `${endpoint.host}:${endpoint.port}`,
      this.credentials,
    ) as unknown as StatementServiceClient;
  }

  private deadline(): grpc.Deadline {
    return Date.now() + this.callTimeoutMs;
  }

  private buildConnectionDetails(): ConnectionDetails {
    return {
      // The ojp-server passes this URL to its JDBC connection pool provider, which
      // requires the "jdbc:" prefix (e.g. jdbc:postgresql://host/db). The OJP connection
      // string does not include that prefix, so it is added here.
      url: `jdbc:${this.parsedUrl.backendUrl}`,
      user: this.options.user ?? '',
      password: this.options.password ?? '',
      clientUUID: this.clientUUID,
      properties: this.parsedUrl.dataSourceName
        ? [{ key: 'ojp.datasource.name', stringValue: this.parsedUrl.dataSourceName }]
        : [],
      serverEndpoints: this.parsedUrl.endpoints.map((e) => `${e.host}:${e.port}`),
      isXA: !!this.options.xa,
    };
  }

  private rpcConnect(client: StatementServiceClient): Promise<SessionInfo> {
    return new Promise((resolve, reject) => {
      client.connect(this.buildConnectionDetails(), { deadline: this.deadline() }, (err, response) => {
        if (err) {
          reject(toOjpError(err));
          return;
        }
        resolve(response);
      });
    });
  }

  /**
   * Opens the logical session with the ojp-server, pointing to the target database.
   *
   * Multinode failover: when the connection string lists more than one endpoint, this
   * tries each candidate (ordered by `orderEndpointsForAttempt` — shared round-robin,
   * healthy endpoints first) until one accepts the connection. Only connection-level
   * failures (unreachable server, timeout — see `isConnectionLevelError`) advance to the
   * next candidate; a database/SQL-level failure (e.g. bad credentials, invalid backend
   * URL) fails fast without trying other nodes, since it would fail identically
   * everywhere. Once a session is established, it stays bound to that single endpoint
   * for its entire lifetime — mirroring the reference JDBC driver, this driver does NOT
   * transparently move an existing session/transaction to another node if its endpoint
   * later goes down (see `toClientError()` below); ACID guarantees for an in-flight
   * transaction cannot be preserved across a node failure.
   */
  async connect(): Promise<SessionInfo> {
    if (this.closed) {
      throw new OjpConnectionError('This client is closed. Create a new client to connect.');
    }
    if (this.connecting || this.session) {
      throw new OjpConnectionError('This client is already connected or connecting.');
    }
    this.connecting = true;
    try {
      return await this.connectToAvailableEndpoint();
    } finally {
      this.connecting = false;
    }
  }

  private async connectToAvailableEndpoint(): Promise<SessionInfo> {
    const candidates = orderEndpointsForAttempt(this.parsedUrl.endpoints);
    let lastError: Error | undefined;

    for (const endpoint of candidates) {
      const client = this.buildGrpcClient(endpoint);
      try {
        const session = await this.rpcConnect(client);
        this.grpcClient = client;
        this.boundServerEndpoint = endpoint;
        this.session = session;
        return session;
      } catch (err) {
        client.close();
        const mapped = err as OjpConnectionError & { cause?: unknown };
        if (mapped instanceof OjpConnectionError && isConnectionLevelError(mapped.cause)) {
          markEndpointUnhealthy(endpoint);
          lastError = mapped;
          continue;
        }
        throw err;
      }
    }

    throw lastError ?? new OjpConnectionError(
      `No healthy ojp-server endpoints available (tried: ${candidates.map((e) => `${e.host}:${e.port}`).join(', ')}).`,
    );
  }

  private requireSession(): SessionInfo {
    if (!this.session) {
      throw new OjpConnectionError('Session not started. Call connect() before executing commands.');
    }
    return this.session;
  }

  /**
   * Returns the gRPC client bound to this session's endpoint. Must only be called after
   * `requireSession()` succeeds — both are set together atomically in `connect()`.
   */
  private requireClient(): StatementServiceClient {
    if (!this.grpcClient) {
      throw new OjpConnectionError('Session not started. Call connect() before executing commands.');
    }
    return this.grpcClient;
  }

  /**
   * Maps a raw gRPC/driver error into the driver's typed error hierarchy and, if it's a
   * connection-level failure (see `isConnectionLevelError`), marks this session's bound
   * endpoint unhealthy for future connection attempts. A connection-level failure on an
   * established session is NOT transparently recovered — mirroring the reference JDBC
   * driver, the session/transaction is considered lost; the caller must create a new
   * `OjpClient` (new session) to retry.
   */
  private toClientError(err: unknown): Error {
    const mapped = toOjpError(err);
    if (mapped instanceof OjpConnectionError && this.boundServerEndpoint && isConnectionLevelError(mapped.cause)) {
      markEndpointUnhealthy(this.boundServerEndpoint);
      return new OjpConnectionError(
        `${mapped.message} (session bound to ${this.boundServerEndpoint.host}:${this.boundServerEndpoint.port} `
        + 'is no longer usable after a connection-level failure; in-flight transactions are not recovered — '
        + 'create a new OjpClient/session to retry.)',
        mapped.cause,
      );
    }
    return mapped;
  }

  /** Creates a plain Statement-style wrapper over this session. */
  createStatement(): OjpStatement {
    return new OjpStatement(this);
  }

  /** Creates a reusable PreparedStatement-style wrapper over this session. */
  prepareStatement(sql: string, options: OjpClientStatementOptions = {}): OjpPreparedStatement {
    return new OjpPreparedStatement(this, sql, options);
  }

  /** Executes INSERT/UPDATE/DELETE/DDL. Returns the number of affected rows. */
  async executeUpdate(sql: string, params: unknown[] = []): Promise<number> {
    return (await this.sendUpdate(sql, params)).intValue ?? 0;
  }

  /** @internal Runs an update for a statement wrapper and preserves server statement handles. */
  async executeStatementUpdate(
    sql: string,
    params: unknown[],
    options: OjpClientStatementOptions,
  ): Promise<{ affectedRows: number; statementUUID?: string }> {
    const result = await this.sendUpdate(sql, params, options);
    return { affectedRows: result.intValue ?? 0, statementUUID: result.uuid };
  }

  /** @internal Executes a prepared update and returns any handle registered by the server. */
  async executePreparedUpdate(
    sql: string,
    params: unknown[],
    options: OjpClientStatementOptions,
    statementUUID?: string,
  ): Promise<{ affectedRows: number; statementUUID?: string }> {
    const result = await this.sendUpdate(sql, params, options, statementUUID);
    return { affectedRows: result.intValue ?? 0, statementUUID: result.uuid };
  }

  private async sendUpdate(
    sql: string,
    params: unknown[],
    options: OjpClientStatementOptions = {},
    statementUUID = '',
    additionalProperties: PropertyEntry[] = [],
  ): Promise<OpResult> {
    const request: StatementRequest = {
      session: this.requireSession(),
      sql,
      parameters: toParameterProtos(params),
      statementUUID,
      properties: [
        ...additionalProperties,
        ...(options.generatedKeys ? [{ key: 'STATEMENT_AUTO_GENERATED_KEYS_KEY', intValue: 1 }] : []),
      ],
    };

    return new Promise((resolve, reject) => {
      this.requireClient().executeUpdate(request, (err, response) => {
        if (err) {
          reject(this.toClientError(err));
          return;
        }
        this.updateSessionFrom(response);
        resolve(response);
      });
    });
  }

  /** @internal Adds one parameter set to a prepared statement's server-side batch. */
  async addPreparedBatch(
    sql: string,
    params: unknown[],
    statementUUID: string | undefined,
    options: OjpClientStatementOptions,
  ): Promise<string> {
    const result = await this.sendUpdate(
      sql,
      params,
      options,
      statementUUID,
      [{ key: 'PREPARED_STATEMENT_ADD_BATCH_FLAG', boolValue: true }],
    );
    const uuid = result.uuidValue || result.uuid || statementUUID;
    if (!uuid) {
      throw new OjpConnectionError('The server did not return a prepared statement handle for the batch.');
    }
    return uuid;
  }

  /** @internal Executes and clears a prepared statement's server-side batch. */
  async executePreparedBatch(statementUUID: string): Promise<number[]> {
    return this.executeBatch('RES_PREPARED_STATEMENT', statementUUID);
  }

  /** @internal Adds an SQL string to a plain statement's server-side batch. */
  async addStatementBatch(sql: string, statementUUID?: string): Promise<string> {
    const response = await this.invokeCallResource({
      resourceType: 'RES_STATEMENT',
      resourceUUID: statementUUID,
      callType: 'CALL_ADD',
      resourceName: 'Batch',
      params: [sql],
    });
    const uuid = response.resourceUUID || statementUUID;
    if (!uuid) {
      throw new OjpConnectionError('The server did not return a statement handle for the batch.');
    }
    return uuid;
  }

  /** @internal Executes and clears a plain or prepared statement's batch. */
  async executeStatementBatch(statementUUID: string): Promise<number[]> {
    return this.executeBatch('RES_STATEMENT', statementUUID);
  }

  private async executeBatch(resourceType: ResourceType, statementUUID: string): Promise<number[]> {
    const response = await this.invokeCallResource({
      resourceType,
      resourceUUID: statementUUID,
      callType: 'CALL_EXECUTE',
      resourceName: 'Batch',
    });
    const value = response.values?.[0];
    if (value?.intArrayValue) {
      return value.intArrayValue.values;
    }
    throw new OjpConnectionError('The server returned an invalid response for batch execution.');
  }

  /** @internal Clears a plain or prepared statement's pending batch. */
  async clearStatementBatch(statementUUID: string, prepared: boolean): Promise<void> {
    await this.invokeCallResource({
      resourceType: prepared ? 'RES_PREPARED_STATEMENT' : 'RES_STATEMENT',
      resourceUUID: statementUUID,
      callType: 'CALL_CLEAR',
      resourceName: 'Batch',
    });
  }

  /** @internal Closes a server-registered plain statement. */
  async closeStatement(statementUUID: string): Promise<void> {
    await this.invokeCallResource({
      resourceType: 'RES_STATEMENT',
      resourceUUID: statementUUID,
      callType: 'CALL_CLOSE',
      resourceName: '',
    });
  }

  /** @internal Retrieves generated keys from a server-registered statement. */
  async getGeneratedKeys(statementUUID: string): Promise<QueryResult> {
    const response = await this.invokeCallResource({
      resourceType: 'RES_PREPARED_STATEMENT',
      resourceUUID: statementUUID,
      callType: 'CALL_GET',
      resourceName: 'GeneratedKeys',
    });
    const resultSetUUID = fromParameterValue(response.values?.[0]);
    if (typeof resultSetUUID !== 'string' || resultSetUUID.length === 0) {
      return this.toQueryResult([], []);
    }
    return this.fetchResultSet(resultSetUUID);
  }

  /** @internal Closes a server-registered prepared statement. */
  async closePreparedStatement(statementUUID: string): Promise<void> {
    await this.invokeCallResource({
      resourceType: 'RES_PREPARED_STATEMENT',
      resourceUUID: statementUUID,
      callType: 'CALL_CLOSE',
      resourceName: '',
    });
  }

  /** @internal Used by Statement.execute() to select the protocol operation. */
  isQuery(sql: string): boolean {
    return looksLikeQuery(sql);
  }

  /** @internal Identifies DML statements whose result rows arrive through executeUpdate. */
  isDmlReturning(sql: string): boolean {
    return returnsRowsFromDml(sql);
  }

  private invokeCallResource(target: {
    resourceType: ResourceType;
    resourceUUID?: string;
    callType: CallType;
    resourceName: string;
    params?: unknown[];
  }): Promise<CallResourceResponse> {
    const request: CallResourceRequest = {
      session: this.requireSession(),
      resourceType: target.resourceType,
      resourceUUID: target.resourceUUID,
      target: {
        callType: target.callType,
        resourceName: target.resourceName,
        params: (target.params ?? []).map((value) => toParameterProtos([value])[0].values[0]),
      },
    };
    return new Promise((resolve, reject) => {
      this.requireClient().callResource(request, (err, response) => {
        if (err) {
          reject(this.toClientError(err));
          return;
        }
        this.updateSessionFrom(response);
        resolve(response);
      });
    });
  }

  private async fetchResultSet(resultSetUUID: string): Promise<QueryResult> {
    let columns: string[] = [];
    const rows: Record<string, unknown>[] = [];
    try {
      for (;;) {
        const result = await this.fetchNextRows(resultSetUUID, 100);
        const query = result.queryResult;
        if (!query) {
          break;
        }
        if (query.labels?.length) {
          columns = query.labels;
        }
        for (const row of query.rows ?? []) {
          const record: Record<string, unknown> = {};
          row.columns.forEach((value, index) => {
            record[columns[index] ?? `col${index}`] = fromParameterValue(value);
          });
          rows.push(record);
        }
        if (!query.rows?.length || result.flag?.includes('RESULT_SET_LAST_BLOCK')) {
          break;
        }
      }
    } finally {
      await this.invokeCallResource({
        resourceType: 'RES_RESULT_SET',
        resourceUUID: resultSetUUID,
        callType: 'CALL_CLOSE',
        resourceName: '',
      });
    }
    return this.toQueryResult(columns, rows);
  }

  private toQueryResult(columns: string[], rows: Record<string, unknown>[]): QueryResult {
    return {
      columns,
      rows,
      metadata: {
        columnCount: columns.length,
        columns: columns.map((label, index) => ({ index: index + 1, label })),
      },
    };
  }

  private async fetchNextRows(resultSetUUID: string, size: number): Promise<OpResult> {
    const request: ResultSetFetchRequest = {
      session: this.requireSession(),
      resultSetUUID,
      size,
    };
    return new Promise((resolve, reject) => {
      this.requireClient().fetchNextRows(request, (err, response) => {
        if (err) {
          reject(this.toClientError(err));
          return;
        }
        this.updateSessionFrom(response);
        resolve(response);
      });
    });
  }

  /** Executes an update and returns its result set (for SQL Server OUTPUT / RETURNING). */
  private async executeDmlReturning(sql: string, params: unknown[]): Promise<QueryResult> {
    const result = await this.sendUpdate(sql, params, {}, '', [
      { key: 'STATEMENT_EXECUTE_UPDATE_RESULT_SET_KEY', boolValue: true },
    ]);
    if (result.flag !== 'UPDATE_RESULT_SET' || !result.uuid) {
      throw new OjpConnectionError('The DML statement did not return a result set.');
    }
    return this.fetchResultSet(result.uuid);
  }

  private async *streamDmlReturningRows(
    sql: string,
    params: unknown[],
  ): AsyncGenerator<Record<string, unknown>, void, undefined> {
    const result = await this.sendUpdate(sql, params, {}, '', [
      { key: 'STATEMENT_EXECUTE_UPDATE_RESULT_SET_KEY', boolValue: true },
    ]);
    if (result.flag !== 'UPDATE_RESULT_SET' || !result.uuid) {
      throw new OjpConnectionError('The DML statement did not return a result set.');
    }

    try {
      for (;;) {
        const page = await this.fetchNextRows(result.uuid, 100);
        const query = page.queryResult;
        if (!query || !query.rows?.length) {
          break;
        }
        const columns = query.labels ?? [];
        for (const row of query.rows) {
          const record: Record<string, unknown> = {};
          for (let index = 0; index < row.columns.length; index++) {
            const value = fromParameterValue(row.columns[index]);
            record[columns[index] ?? `col${index}`] =
              typeof value === 'string' && value.startsWith(CLOB_PLACEHOLDER_PREFIX)
                ? await this.readLobAsString(value.slice(CLOB_PLACEHOLDER_PREFIX.length))
                : value;
          }
          yield record;
        }
        if (page.flag?.includes('RESULT_SET_LAST_BLOCK')) {
          break;
        }
      }
    } finally {
      await this.invokeCallResource({
        resourceType: 'RES_RESULT_SET',
        resourceUUID: result.uuid,
        callType: 'CALL_CLOSE',
        resourceName: '',
      });
    }
  }

  /** @internal Executes a prepared update and returns rows when it has OUTPUT/RETURNING. */
  async executeDmlReturningQuery(sql: string, params: unknown[]): Promise<QueryResult> {
    return this.executeDmlReturning(sql, params);
  }

  /**
   * Requests a single additional batch of rows ("row-by-row" mode), used only
   * when the result set signals `ROW_BY_ROW_MODE_FLAG` (SQL Server/DB2 + LOBs).
   * The requested size (`size`) is not honored by the server in this mode — it
   * always returns at most 1 row per call; an empty block signals the end of data.
   */
  private fetchNextRowsOnce(resultSetUUID: string): Promise<OpResult> {
    const request: ResultSetFetchRequest = {
      session: this.requireSession(),
      resultSetUUID,
      size: 1,
    };

    return new Promise((resolve, reject) => {
      this.requireClient().fetchNextRows(request, (err, response) => {
        if (err) {
          reject(this.toClientError(err));
          return;
        }
        this.updateSessionFrom(response);
        resolve(response);
      });
    });
  }

  /**
   * Consumes the `executeQuery` gRPC stream on demand (one `OpResult`/block at a time),
   * without accumulating the entire result set in memory. If the result set enters
   * "row-by-row" mode (SQL Server/DB2 + LOBs), it keeps pulling rows via `fetchNextRows`
   * until it receives an empty block.
   *
   * Interrupting the iteration early (e.g. `break` in a `for await`) automatically
   * cancels the underlying gRPC call, releasing the result set on the server.
   */
  private async *streamOpResults(request: StatementRequest): AsyncGenerator<OpResult, void, undefined> {
    const stream = this.requireClient().executeQuery(request);
    // Prevents an error emitted after explicit cancellation (e.g. "Cancelled on
    // client") from becoming an unhandled exception in the process — the real
    // reason for an early interruption is already known by the code that caused it.
    stream.on('error', () => undefined);

    let endedNormally = false;
    try {
      let rowByRowMode = false;
      let firstBlock = true;
      let resultSetUUID = '';

      try {
        for await (const opResult of stream as unknown as AsyncIterable<OpResult>) {
          this.updateSessionFrom(opResult);
          if (firstBlock) {
            firstBlock = false;
            rowByRowMode = opResult.flag === ROW_BY_ROW_MODE_FLAG;
          }
          if (opResult.queryResult?.resultSetUUID) {
            resultSetUUID = opResult.queryResult.resultSetUUID;
          }
          yield opResult;
        }
      } catch (err) {
        throw this.toClientError(err);
      }

      if (rowByRowMode) {
        for (;;) {
          const next = await this.fetchNextRowsOnce(resultSetUUID);
          if (!next.queryResult?.rows?.length) {
            break;
          }
          yield next;
        }
      }

      endedNormally = true;
    } finally {
      if (!endedNormally) {
        stream.cancel();
      }
    }
  }

  /** Maps `OpResult` blocks into rows already converted to JS values, with the currently active columns. */
  private async *queryRows(
    sql: string,
    params: unknown[],
    statementUUID = '',
  ): AsyncGenerator<StreamedRow, void, undefined> {
    const request: StatementRequest = {
      session: this.requireSession(),
      sql,
      parameters: toParameterProtos(params),
      statementUUID,
    };

    let columns: string[] = [];
    for await (const opResult of this.streamOpResults(request)) {
      const queryResult = opResult.queryResult;
      if (!queryResult) {
        continue;
      }
      if (queryResult.labels?.length) {
        columns = queryResult.labels;
        yield { columns };
      }
      for (const row of queryResult.rows ?? []) {
        const record: Record<string, unknown> = {};
        for (let i = 0; i < row.columns.length; i++) {
          const value = fromParameterValue(row.columns[i]);
          record[columns[i] ?? `col${i}`] =
            typeof value === 'string' && value.startsWith(CLOB_PLACEHOLDER_PREFIX)
              ? await this.readLobAsString(value.slice(CLOB_PLACEHOLDER_PREFIX.length))
              : value;
        }
        yield { columns, row: record };
        }
    }
  }

  /**
   * Executes a SELECT and returns an async iterable of rows, consumed on demand
   * directly from the gRPC stream — without loading the entire result set into memory.
   *
   * Recommended for large result sets: stop iterating (`break`/`return`) at any
   * time to cancel the stream and release the resources on the ojp-server.
   *
   * ```ts
   * for await (const row of client.executeQueryStream('SELECT * FROM big_table')) {
   *   console.log(row.id);
   * }
   * ```
   */
  async *executeQueryStream(sql: string, params: unknown[] = []): AsyncGenerator<Record<string, unknown>, void, undefined> {
    if (returnsRowsFromDml(sql)) {
      yield* this.streamDmlReturningRows(sql, params);
      return;
    }
    for await (const { row } of this.queryRows(sql, params)) {
      if (row) {
        yield row;
      }
    }
  }

  /**
   * Executes a SELECT and materializes the entire result set in memory. For large
   * result sets, prefer `executeQueryStream`, which consumes the gRPC stream on demand.
   */
  async executeQuery(sql: string, params: unknown[] = []): Promise<QueryResult> {
    return this.runQuery(sql, params, '');
  }

  /** @internal Executes a prepared query without exposing statement IDs to application code. */
  async executePreparedQuery(sql: string, params: unknown[]): Promise<QueryResult> {
    return this.runQuery(sql, params, '');
  }

  private async runQuery(sql: string, params: unknown[], statementUUID: string): Promise<QueryResult> {
    if (returnsRowsFromDml(sql)) {
      return this.executeDmlReturning(sql, params);
    }
    let columns: string[] = [];
    const rows: Record<string, unknown>[] = [];
    for await (const item of this.queryRows(sql, params, statementUUID)) {
      columns = item.columns;
      if (item.row) {
        rows.push(item.row);
      }
    }
    return this.toQueryResult(columns, rows);
  }

  private updateSessionFrom(response: OpResult | SessionInfo | CallResourceResponse | LobReference | LobDataBlock): void {
    const session = 'session' in response ? response.session : response;
    if (session) {
      this.session = session;
    }
  }

  /**
   * Sends a BLOB/CLOB to the ojp-server via bidirectional streaming (`createLob`) and
   * returns a reference (`OjpLobRef`) that can be used as a parameter in
   * `executeUpdate`/`executeQuery` (e.g. `INSERT INTO t (col) VALUES (?)` with `[lobRef]`).
   *
   * For CLOB, `data` can be passed as a string (automatically UTF-8 encoded)
   * or as a Buffer already encoded in UTF-8.
   */
  async createLob(data: Buffer | string, lobKind: 'BLOB' | 'CLOB' = 'BLOB'): Promise<OjpLobRef> {
    const bytes = typeof data === 'string' ? Buffer.from(data, 'utf8') : data;
    const protoLobType = lobKind === 'BLOB' ? 'LT_BLOB' : 'LT_CLOB';

    // "Stateless" session (empty sessionUUID): normal connect()/executeQuery/executeUpdate
    // do not pin a dedicated physical connection (lazy allocation on the server). For
    // createLob, this matters: the first block can be sent with this session (the server
    // creates a dedicated connection on demand and initializes the Blob/Clob), but the
    // server only returns a session with sessionUUID populated (connection affinity) in
    // the FIRST response — subsequent blocks need to use this "stateful" session to land
    // on the same already-started Blob/Clob; otherwise, each block would allocate a new
    // connection/Blob (see CreateLobAction.initializeLobIfNeeded/
    // SessionConnectionHelper.sessionConnection).
    let session = this.requireSession();

    const chunks: Buffer[] = [];
    if (bytes.length === 0) {
      // Empty LOB: we still need to send one block for the server to initialize the Blob/Clob.
      chunks.push(Buffer.alloc(0));
    } else {
      for (let offset = 0; offset < bytes.length; offset += LOB_CHUNK_SIZE) {
        chunks.push(bytes.subarray(offset, offset + LOB_CHUNK_SIZE));
      }
    }

    return new Promise<OjpLobRef>((resolve, reject) => {
      const call = this.requireClient().createLob();
      let lastRef: LobReference | undefined;
      let firstAckReceived = false;
      let settled = false;

      // 1-based position, advancing by the size (in bytes) of each block sent —
      // mirrors exactly the position tracking of the reference JDBC driver.
      // Note: for CLOB with multi-byte characters (accents, emojis) near a
      // block boundary, the position advances in bytes, not characters — same
      // limitation as the original protocol (see ParameterHandler/CreateLobAction).
      let position = 1;
      let nextChunkIndex = 0;
      const writeNextChunk = (): void => {
        const chunk = chunks[nextChunkIndex];
        nextChunkIndex += 1;
        call.write({ session, position: position.toString(), data: chunk, lobType: protoLobType });
        position += chunk.length;
      };

      call.on('data', (ref: LobReference) => {
        lastRef = ref;
        this.updateSessionFrom(ref);
        if (!firstAckReceived) {
          firstAckReceived = true;
          // From here on, use the "stateful" session returned by the server (with
          // sessionUUID/connection affinity) for the remaining blocks.
          session = ref.session;
          while (nextChunkIndex < chunks.length) {
            writeNextChunk();
          }
          call.end();
        }
      });
      call.on('error', (err) => {
        if (settled) {
          return;
        }
        settled = true;
        reject(this.toClientError(err));
      });
      call.on('end', () => {
        if (settled) {
          return;
        }
        settled = true;
        if (!lastRef) {
          reject(new OjpConnectionError('createLob did not return any LOB reference.'));
          return;
        }
        resolve(new OjpLobRef(lastRef.uuid, lobKind));
      });

      // Sends only the first block and waits for the ack ('data' event above) before
      // sending the rest — see the comment about connection affinity right above.
      writeNextChunk();
    });
  }


  /**
   * Reads the complete content of a CLOB from the UUID extracted from the
   * `OJP_CLOB_PREFIX:<uuid>` placeholder — used internally by `queryRows` to make
   * CLOB reading transparent, just like BLOB/VARBINARY (which already arrive
   * hydrated directly in the result set, without needing a reference).
   */
  private async readLobAsString(uuid: string): Promise<string> {
    const chunks = await this.readLobChunks(uuid, 'LT_CLOB');
    return Buffer.concat(chunks).toString('utf8');
  }

  /** Fetches all blocks of a LOB via `readLob`, concatenating them in the order received. */
  private readLobChunks(uuid: string, lobType: LobReference['lobType']): Promise<Buffer[]> {
    const request: ReadLobRequest = {
      lobReference: { session: this.requireSession(), uuid, lobType },
      // position=1 with a max length makes the server compute and return the entire
      // LOB at once (see ReadLobAction), with no extra round-trip to get the size.
      position: '1',
      length: READ_LOB_MAX_LENGTH,
    };

    return new Promise<Buffer[]>((resolve, reject) => {
      const stream = this.requireClient().readLob(request);
      const chunks: Buffer[] = [];
      let settled = false;

      stream.on('data', (block: LobDataBlock) => {
        this.updateSessionFrom(block);
        // position === "-1" is the sentinel for "LOB reference not found"
        // (see ReadLobAction); in that case there is no data to concatenate.
        if (block.position === '-1') {
          return;
        }
        if (block.data?.length) {
          chunks.push(block.data);
        }
      });
      stream.on('error', (err) => {
        if (settled) {
          return;
        }
        settled = true;
        reject(this.toClientError(err));
      });
      stream.on('end', () => {
        if (settled) {
          return;
        }
        settled = true;
        resolve(chunks);
      });
    });
  }

  async startTransaction(): Promise<void> {
    this.assertNotXA('startTransaction');
    const session = this.requireSession();
    await new Promise<void>((resolve, reject) => {
      this.requireClient().startTransaction(session, (err, response) => {
        if (err) {
          reject(this.toClientError(err));
          return;
        }
        this.session = response;
        resolve();
      });
    });
  }

  async commit(): Promise<void> {
    this.assertNotXA('commit');
    const session = this.requireSession();
    await new Promise<void>((resolve, reject) => {
      this.requireClient().commitTransaction(session, (err, response) => {
        if (err) {
          reject(this.toClientError(err));
          return;
        }
        this.session = response;
        resolve();
      });
    });
    await this.restoreAutoCommit();
  }

  async rollback(): Promise<void> {
    this.assertNotXA('rollback');
    const session = this.requireSession();
    await new Promise<void>((resolve, reject) => {
      this.requireClient().rollbackTransaction(session, (err, response) => {
        if (err) {
          reject(this.toClientError(err));
          return;
        }
        this.session = response;
        resolve();
      });
    });
    await this.restoreAutoCommit();
  }

  /**
   * Restores autoCommit on the physical connection after `commit()`/`rollback()`.
   *
   * `commitTransaction`/`rollbackTransaction` mirror the JDBC `Connection.commit()`/
   * `rollback()` methods, which per the JDBC spec do NOT change the connection's autoCommit
   * mode — it stays in manual-commit mode until `Connection.setAutoCommit(true)` is called
   * explicitly. This client, however, has no separate "set autocommit" API: every
   * `startTransaction()`/`commit()`/`rollback()` here is meant to be one self-contained
   * transaction, after which the session should behave like a fresh, autocommitting
   * connection again (matching real Postgres/MySQL session semantics, and what
   * `@ojp/typeorm-driver` relies on for any plain statement issued after a
   * `dataSource.transaction()` block). Without this, such statements silently run inside an
   * unclosed transaction and are lost when the session is later closed.
   *
   * Uses the same generic `callResource(CALL_SET, "AutoCommit", ...)` RPC the JDBC driver
   * uses for `Connection.setAutoCommit(true)` (see `ojp-jdbc-driver`'s `Connection.java`),
   * which the server dispatches, via reflection, straight to the physical connection's own
   * `setAutoCommit(true)` — the same call that both commits any still-pending work and
   * switches the connection back to autocommit mode.
   */
  private async restoreAutoCommit(): Promise<void> {
    const session = this.requireSession();
    const request: CallResourceRequest = {
      session,
      resourceType: 'RES_CONNECTION',
      target: {
        callType: 'CALL_SET',
        resourceName: 'AutoCommit',
        params: [{ boolValue: true }],
      },
    };
    await new Promise<void>((resolve, reject) => {
      this.requireClient().callResource(request, (err, response) => {
        if (err) {
          reject(this.toClientError(err));
          return;
        }
        this.session = response.session;
        resolve();
      });
    });
  }

  /**
   * Guards ordinary transaction demarcation on an XA session — mirrors the reference
   * JDBC driver's `OjpXALogicalConnection.commit()`/`rollback()`, which reject these
   * calls because the transaction boundary must be controlled by the XA resource
   * manager (`OjpXAResource`/`createXAResource()`), not the connection itself.
   */
  private assertNotXA(operation: string): void {
    if (this.options.xa) {
      throw new OjpConnectionError(
        `${operation}() is not allowed on an XA session. Use createXAResource() and drive the transaction `
        + 'through OjpXAResource.start()/end()/prepare()/commit()/rollback() instead.',
      );
    }
  }

  /**
   * Returns the `OjpXAResource` (two-phase-commit RPC wrapper) bound to this session.
   * Requires the client to have been created with `{ xa: true }` and already connected.
   * Reuses this client's gRPC channel and multinode endpoint-health tracking, so a
   * connection-level failure during any XA operation also marks the bound endpoint
   * unhealthy for future `connect()` calls — consistent with the ordinary SQL path.
   */
  createXAResource(): OjpXAResource {
    if (!this.options.xa) {
      throw new OjpConnectionError('createXAResource() requires the client to be created with `{ xa: true }`.');
    }
    return new OjpXAResource({
      getClient: () => this.requireClient(),
      getSession: () => this.requireSession(),
      updateSession: (session) => {
        this.session = session;
      },
      mapError: (err) => this.toClientError(err),
    });
  }

  /** Terminates the session on the ojp-server and closes the gRPC channel. Idempotent. */
  async close(): Promise<void> {
    if (this.connecting) {
      throw new OjpConnectionError('Cannot close while connecting. Wait for connect() to finish.');
    }
    this.closed = true;
    const session = this.session;
    const client = this.grpcClient;
    this.session = undefined;
    this.grpcClient = undefined;
    this.boundServerEndpoint = undefined;
    try {
      if (session && client) {
        await new Promise<void>((resolve, reject) => {
          client.terminateSession(session, { deadline: this.deadline() }, (err) => {
            if (err) {
              reject(toOjpError(err));
            } else {
              resolve();
            }
          });
        });
      }
    } finally {
      client?.close();
    }
  }
}

/**
 * Use this for a short, self-contained task, such as running a query or transaction.
 * The client manages its `SessionInfo`; callers do not need to pass session state.
 *
 * ```ts
 * const result = await withOjpClient(url, (client) =>
 *   client.executeQuery('SELECT name FROM customers'),
 * );
 * ```
 *
 * The callback must finish before the client is closed. Do not use this helper when
 * you need to keep a session open after the callback, or return the client for later
 * use. Manage that client directly instead:
 *
 * ```ts
 * const client = new OjpClient(url);
 * await client.connect();
 * try {
 *   await client.executeQuery('SELECT name FROM customers');
 *   // Run more work while this session is needed.
 * } finally {
 *   await client.close();
 * }
 * ```
 *
 * Every call creates a fresh client and closes it when the callback finishes,
 * including when the callback fails. This helper does not pool or reuse clients.
 */
export async function withOjpClient<T>(
  ojpUrl: string,
  operation: (client: OjpClient) => T | Promise<T>,
  options: OjpClientOptions = {},
): Promise<T> {
  const client = new OjpClient(ojpUrl, options);
  try {
    await client.connect();
    return await operation(client);
  } finally {
    await client.close();
  }
}
