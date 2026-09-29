import * as grpc from '@grpc/grpc-js';
import { randomUUID } from 'crypto';
import { loadStatementServiceGrpcObject } from '../proto/loader';
import { OjpServerEndpoint, ParsedOjpUrl, parseOjpUrl } from './connectionString';
import { toOjpError, OjpConnectionError, isConnectionLevelError } from './errors';
import { fromParameterValue, OjpLobRef, toParameterProtos } from './valueMapper';
import { markEndpointUnhealthy, orderEndpointsForAttempt } from './endpointHealth';
import { StatementServiceClient } from './statementServiceClient';
import { OjpXAResource } from '../xa/OjpXAResource';
import {
  CallResourceRequest,
  ConnectionDetails,
  LobDataBlock,
  LobReference,
  OpResult,
  ReadLobRequest,
  ResultSetFetchRequest,
  SessionInfo,
  SessionTerminationStatus,
  StatementRequest,
} from '../proto/types';

export { OjpLobRef, OjpTypedParam } from './valueMapper';

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

interface StreamedRow {
  columns: string[];
  row: Record<string, unknown>;
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

  constructor(ojpUrl: string, private readonly options: OjpClientOptions = {}) {
    this.parsedUrl = parseOjpUrl(ojpUrl);
    this.callTimeoutMs = options.callTimeoutMs ?? 30_000;
    this.clientUUID = randomUUID();
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

  /** Executes INSERT/UPDATE/DELETE/DDL. Returns the number of affected rows. */
  async executeUpdate(sql: string, params: unknown[] = []): Promise<number> {
    const request: StatementRequest = {
      session: this.requireSession(),
      sql,
      parameters: toParameterProtos(params),
      // Empty statementUUID: each call creates/prepares its own statement on the
      // server (stateless usage). A UUID should only be sent when referencing a
      // previously registered statement (PreparedStatement reuse), which is not
      // yet supported at this stage of the driver.
      statementUUID: '',
    };

    return new Promise((resolve, reject) => {
      this.requireClient().executeUpdate(request, (err, response) => {
        if (err) {
          reject(this.toClientError(err));
          return;
        }
        this.updateSessionFrom(response);
        resolve(response.intValue ?? 0);
      });
    });
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
  private async *queryRows(sql: string, params: unknown[]): AsyncGenerator<StreamedRow, void, undefined> {
    const request: StatementRequest = {
      session: this.requireSession(),
      sql,
      parameters: toParameterProtos(params),
      statementUUID: '',
    };

    let columns: string[] = [];
    for await (const opResult of this.streamOpResults(request)) {
      const queryResult = opResult.queryResult;
      if (!queryResult) {
        continue;
      }
      if (queryResult.labels?.length) {
        columns = queryResult.labels;
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
    for await (const { row } of this.queryRows(sql, params)) {
      yield row;
    }
  }

  /**
   * Executes a SELECT and materializes the entire result set in memory. For large
   * result sets, prefer `executeQueryStream`, which consumes the gRPC stream on demand.
   */
  async executeQuery(sql: string, params: unknown[] = []): Promise<QueryResult> {
    let columns: string[] = [];
    const rows: Record<string, unknown>[] = [];
    for await (const item of this.queryRows(sql, params)) {
      columns = item.columns;
      rows.push(item.row);
    }
    return { columns, rows };
  }

  private updateSessionFrom(response: OpResult | SessionInfo | LobReference | LobDataBlock): void {
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
    if (this.session && this.grpcClient) {
      const client = this.grpcClient;
      await new Promise<void>((resolve) => {
        client.terminateSession(this.session as SessionInfo, () => {
          // Ignores termination failures: the channel will be closed regardless.
          resolve();
        });
      });
      this.session = undefined;
    }
    this.grpcClient?.close();
  }
}
