import * as grpc from '@grpc/grpc-js';
import { createHash, randomUUID } from 'crypto';
import { PassThrough, Readable } from 'stream';
import { loadStatementServiceGrpcObject } from '../proto/loader';
import { decodeSqlErrorResponse, SQL_ERROR_METADATA_KEY } from '../proto/errorCodec';
import { OjpServerEndpoint, ParsedOjpUrl, parseOjpUrl } from './connectionString';
import { toOjpError, OjpConnectionError, OjpTransactionError, isConnectionLevelError } from './errors';
import { fromParameterValue, OjpLobRef, OjpNullParam, toParameterProtos } from './valueMapper';
import {
  clusterHealth,
  isEndpointHealthy,
  markEndpointHealthy,
  markEndpointUnhealthy,
  orderEndpointsForAttempt,
  registerEndpointSession,
  shouldProbeEndpoints,
  unregisterEndpointSession,
} from './endpointHealth';
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
  XaResponse,
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

export interface OjpTlsOptions {
  /** PEM-encoded certificate authorities used to verify the OJP server. */
  rootCert?: Buffer;
  /** PEM-encoded client private key for mutual TLS. Requires `certChain`. */
  privateKey?: Buffer;
  /** PEM-encoded client certificate chain for mutual TLS. Requires `privateKey`. */
  certChain?: Buffer;
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
  /** TLS trust roots and optional client identity. Supplying this enables TLS. */
  tls?: OjpTlsOptions;
  /** Endpoint selection policy in multinode mode. Default: least-connections. */
  loadBalancingStrategy?: 'least-connections' | 'round-robin';
  /** Interval between shared multinode health probes, in milliseconds. Default: 5000. */
  healthCheckIntervalMs?: number;
  /** Maximum transport-failure retries for stateless operations. Default: 3. */
  maxFailoverAttempts?: number;
  /** Delay between stateless operation failover attempts, in milliseconds. Default: 100. */
  failoverRetryDelayMs?: number;
  /**
   * Opens this session as an XA (distributed transaction) resource. When `true`,
   * transaction demarcation must go through `createXAResource()` — `startTransaction`/
   * `commit`/`rollback` are disabled on the session itself (mirroring the reference
   * JDBC driver's `OjpXALogicalConnection`). Default: false.
   */
  xa?: boolean;
}

export const TransactionIsolation = {
  READ_UNCOMMITTED: 1,
  READ_COMMITTED: 2,
  REPEATABLE_READ: 4,
  SERIALIZABLE: 8,
} as const;

export type TransactionIsolationLevel = (typeof TransactionIsolation)[keyof typeof TransactionIsolation];

const transactionIsolationLevels = new Set<number>(Object.values(TransactionIsolation));
const isTransactionIsolationLevel = (value: unknown): value is TransactionIsolationLevel =>
  typeof value === 'number' && transactionIsolationLevels.has(value);

export class OjpSavepoint {
  private released = false;
  private invalidated = false;

  constructor(
    private readonly owner: OjpClient,
    private readonly uuid: string,
    private readonly transactionGeneration: number,
    private readonly sequence: number,
    readonly name?: string,
  ) {}

  /** @internal */
  assertUsable(owner: OjpClient, transactionGeneration: number): string {
    if (this.owner !== owner || this.transactionGeneration !== transactionGeneration
      || this.released || this.invalidated) {
      throw new OjpConnectionError('Savepoint does not belong to the active transaction.');
    }
    return this.uuid;
  }

  /** @internal */
  invalidate(): void {
    this.invalidated = true;
  }

  /** @internal */
  markReleased(): void {
    this.released = true;
  }

  /** @internal */
  getSequence(): number {
    return this.sequence;
  }
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
const DEFAULT_MAX_FAILOVER_ATTEMPTS = 3;
const DEFAULT_FAILOVER_RETRY_DELAY_MS = 100;

interface CachedPool {
  connHash: string;
  connectionDetails: ConnectionDetails;
  initializedEndpoints: Set<string>;
  clusterHealth: string;
  endpoints: Map<string, OjpServerEndpoint>;
}

const poolCache = new Map<string, CachedPool>();

function endpointKey(endpoint: OjpServerEndpoint): string {
  return `${endpoint.host}:${endpoint.port}`;
}

function connectionKey(details: ConnectionDetails): string {
  const identity = JSON.stringify([
    details.url,
    details.user,
    details.password,
    details.properties ?? [],
    details.isXA ?? false,
  ]);
  return createHash('sha256').update(identity).digest('hex');
}

function isPoolNotFound(err: unknown): boolean {
  if (typeof err !== 'object' || err === null) {
    return false;
  }
  const grpcError = err as {
    code?: number;
    details?: string;
    message?: string;
    metadata?: { get(key: string): unknown[] };
  };
  const rawMessage = `${grpcError.details ?? ''} ${grpcError.message ?? ''}`;
  if (grpcError.code === grpc.status.NOT_FOUND && /no pool found for connection hash/i.test(rawMessage)) {
    return true;
  }
  const raw = grpcError.metadata?.get(SQL_ERROR_METADATA_KEY)[0];
  if (!Buffer.isBuffer(raw)) {
    return false;
  }
  return /no pool found for connection hash/i.test(decodeSqlErrorResponse(raw)?.reason ?? '');
}

type LobInput = Buffer | string | AsyncIterable<Buffer | string>;

async function* singleLobChunk(input: Buffer | string): AsyncGenerator<Buffer | string, void, undefined> {
  yield input;
}

async function* chunkLobInput(input: LobInput, clob: boolean): AsyncGenerator<Buffer, void, undefined> {
  const source: AsyncIterable<Buffer | string> =
    typeof input === 'string' || Buffer.isBuffer(input) ? singleLobChunk(input) : input;
  let pending: Buffer = Buffer.alloc(0);

  for await (const chunk of source) {
    const bytes = typeof chunk === 'string' ? Buffer.from(chunk, 'utf8') : chunk;
    if (bytes.length === 0) {
      continue;
    }
    pending = pending.length === 0 ? bytes : Buffer.concat([pending, bytes]);
    while (pending.length >= LOB_CHUNK_SIZE) {
      let chunkSize = LOB_CHUNK_SIZE;
      if (clob) {
        while (chunkSize > 0 && (pending[chunkSize] & 0xc0) === 0x80) {
          chunkSize -= 1;
        }
      }
      yield pending.subarray(0, chunkSize);
      pending = pending.subarray(chunkSize);
    }
  }

  if (pending.length > 0) {
    yield pending;
  }
}

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
  private readonly poolKey: string;
  private endpointRegistered = false;
  private healthCheckTimer?: NodeJS.Timeout;
  private connecting = false;
  private closed = false;
  private affinityError?: OjpConnectionError;
  private autoCommit = true;
  private transactionActive = false;
  private transactionGeneration = 0;
  private savepointSequence = 0;
  private readonly savepoints: OjpSavepoint[] = [];

  constructor(ojpUrl: string, private readonly options: OjpClientOptions = {}) {
    this.parsedUrl = parseOjpUrl(ojpUrl);
    this.callTimeoutMs = options.callTimeoutMs ?? 30_000;
    if (!Number.isSafeInteger(options.maxFailoverAttempts ?? DEFAULT_MAX_FAILOVER_ATTEMPTS)
        || (options.maxFailoverAttempts ?? DEFAULT_MAX_FAILOVER_ATTEMPTS) < 0) {
      throw new RangeError('maxFailoverAttempts must be a non-negative integer.');
    }
    if (!Number.isFinite(options.failoverRetryDelayMs ?? DEFAULT_FAILOVER_RETRY_DELAY_MS)
        || (options.failoverRetryDelayMs ?? DEFAULT_FAILOVER_RETRY_DELAY_MS) < 0) {
      throw new RangeError('failoverRetryDelayMs must be a non-negative number.');
    }
    this.clientUUID = CLIENT_UUID;
    if (!!options.tls?.privateKey !== !!options.tls?.certChain) {
      throw new TypeError('tls.privateKey and tls.certChain must be provided together.');
    }
    if (options.tls && options.insecure === true) {
      throw new TypeError('tls options cannot be combined with insecure: true.');
    }
    this.credentials = options.tls || options.insecure === false
      ? grpc.credentials.createSsl(
        options.tls?.rootCert,
        options.tls?.privateKey,
        options.tls?.certChain,
      )
      : grpc.credentials.createInsecure();
    this.autoCommit = !options.xa;
    this.poolKey = connectionKey(this.buildConnectionDetails());
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

  private deadline(timeoutMs = this.callTimeoutMs): grpc.Deadline {
    return Date.now() + timeoutMs;
  }

  private buildConnectionDetails(): ConnectionDetails {
    const properties = this.parsedUrl.dataSourceName
      ? [{ key: 'ojp.datasource.name', stringValue: this.parsedUrl.dataSourceName }]
      : [];
    return {
      // The ojp-server passes this URL to its JDBC connection pool provider, which
      // requires the "jdbc:" prefix (e.g. jdbc:postgresql://host/db). The OJP connection
      // string does not include that prefix, so it is added here.
      url: `jdbc:${this.parsedUrl.backendUrl}`,
      user: this.options.user ?? '',
      password: this.options.password ?? '',
      clientUUID: this.clientUUID,
      properties,
      serverEndpoints: this.parsedUrl.endpoints.map((e) => `${e.host}:${e.port}`),
      isXA: !!this.options.xa,
      clusterHealth: clusterHealth(this.parsedUrl.endpoints),
    };
  }

  private rpcConnect(client: StatementServiceClient, details = this.buildConnectionDetails()): Promise<SessionInfo> {
    return new Promise((resolve, reject) => {
      client.connect(details, { deadline: this.deadline() }, (err, response) => {
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
   * tries each candidate (ordered by `orderEndpointsForAttempt` — least-connections
   * with round-robin tie-breaking, healthy endpoints first) until one accepts the
   * connection. Only connection-level failures (unreachable server, timeout — see
   * `isConnectionLevelError`) advance to the next candidate; a database/SQL-level failure
   * (e.g. bad credentials, invalid backend URL) fails fast without trying other nodes,
   * since it would fail identically everywhere. Stateless operations may be retried on
   * a healthy endpoint after a transport failure. Server-side sessions and transactions
   * remain bound to their endpoint and are never transparently rerouted.
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

  private async connectToAvailableEndpoint(excludedEndpoints: Set<string> = new Set()): Promise<SessionInfo> {
    await this.probeEndpointsIfDue();
    const candidates = orderEndpointsForAttempt(
      this.parsedUrl.endpoints,
      this.options.loadBalancingStrategy ?? 'least-connections',
    ).filter((endpoint) => !excludedEndpoints.has(endpointKey(endpoint)));
    let lastError: Error | undefined;

    for (const endpoint of candidates) {
      const client = this.buildGrpcClient(endpoint);
      try {
        const cachedPool = this.options.xa ? undefined : poolCache.get(this.poolKey);
        let session: SessionInfo;
        if (cachedPool) {
          await this.ensurePoolOnHealthyEndpoints(cachedPool);
          session = {
            connHash: cachedPool.connHash,
            clientUUID: this.clientUUID,
            sessionUUID: '',
            sessionStatus: 'SESSION_ACTIVE',
            isXA: false,
            clusterHealth: clusterHealth(this.parsedUrl.endpoints),
          };
        } else {
          session = await this.rpcConnect(client);
          markEndpointHealthy(endpoint);
          if (!this.options.xa && session.connHash) {
            const health = clusterHealth(this.parsedUrl.endpoints);
            const pool: CachedPool = {
              connHash: session.connHash,
              connectionDetails: this.buildConnectionDetails(),
              initializedEndpoints: new Set([endpointKey(endpoint)]),
              clusterHealth: health,
              endpoints: new Map(this.parsedUrl.endpoints.map((candidate) => [
                endpointKey(candidate),
                candidate,
              ])),
            };
            poolCache.set(this.poolKey, pool);
            await this.ensurePoolOnHealthyEndpoints(pool, endpoint);
          }
        }
        session.clusterHealth = clusterHealth(this.parsedUrl.endpoints);
        const targetServer = session.targetServer || this.endpointAddress(endpoint);
        const targetEndpoint = this.endpointForTargetServer(targetServer);
        if (!targetEndpoint) {
          throw new OjpConnectionError(
            `The ojp-server returned targetServer "${targetServer}", which is not a configured endpoint.`,
          );
        }
        session.targetServer = targetServer;
        const targetClient = endpointKey(targetEndpoint) === endpointKey(endpoint)
          ? client
          : this.buildGrpcClient(targetEndpoint);
        if (targetClient !== client) {
          client.close();
        }
        this.grpcClient = targetClient;
        this.boundServerEndpoint = targetEndpoint;
        this.session = session;
        registerEndpointSession(targetEndpoint);
        this.endpointRegistered = true;
        this.startHealthMonitor();
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

  private async probeEndpointsIfDue(): Promise<void> {
    if (this.parsedUrl.endpoints.length < 2
        || !shouldProbeEndpoints(this.options.healthCheckIntervalMs ?? 5_000)) {
      return;
    }

    await Promise.all(this.parsedUrl.endpoints.map(async (endpoint) => {
      const client = this.buildGrpcClient(endpoint);
      try {
        await new Promise<void>((resolve, reject) => {
          client.connect(
            { url: '', user: '', password: '', clientUUID: this.clientUUID },
            { deadline: this.deadline(Math.min(this.callTimeoutMs, 5_000)) },
            (err) => err ? reject(err) : resolve(),
          );
        });
        markEndpointHealthy(endpoint);
      } catch {
        markEndpointUnhealthy(endpoint);
      } finally {
        client.close();
      }
    }));
  }

  private startHealthMonitor(): void {
    const intervalMs = this.options.healthCheckIntervalMs ?? 5_000;
    if (this.parsedUrl.endpoints.length < 2 || intervalMs <= 0 || this.healthCheckTimer) {
      return;
    }

    this.healthCheckTimer = setInterval(() => {
      void this.refreshEndpointHealth().catch((err: unknown) => {
        process.emitWarning(`OJP multinode health refresh failed: ${String(err)}`);
      });
    }, intervalMs);
    this.healthCheckTimer.unref();
  }

  private async refreshEndpointHealth(): Promise<void> {
    const before = clusterHealth(this.parsedUrl.endpoints);
    await this.probeEndpointsIfDue();
    const currentHealth = clusterHealth(this.parsedUrl.endpoints);
    if (before !== currentHealth) {
      if (this.session) {
        this.session = { ...this.session, clusterHealth: currentHealth };
      }
      for (const pool of poolCache.values()) {
        await this.ensurePoolOnHealthyEndpoints(pool);
      }
    }
  }

  private async ensurePoolOnHealthyEndpoints(pool: CachedPool, alreadyConnected?: OjpServerEndpoint): Promise<void> {
    for (const endpoint of this.parsedUrl.endpoints) {
      pool.endpoints.set(endpointKey(endpoint), endpoint);
    }
    const endpoints = [...pool.endpoints.values()];
    const health = clusterHealth(endpoints);
    const healthChanged = health !== pool.clusterHealth;
    const targets = endpoints.filter((endpoint) =>
      (!alreadyConnected || endpointKey(endpoint) !== endpointKey(alreadyConnected))
      && isEndpointHealthy(endpoint)
      && (!pool.initializedEndpoints.has(endpointKey(endpoint)) || healthChanged));

    for (const endpoint of targets) {
      if (!isEndpointHealthy(endpoint)) {
        continue;
      }
      const client = this.buildGrpcClient(endpoint);
      try {
        const details = {
          ...pool.connectionDetails,
          serverEndpoints: endpoints.map(endpointKey),
          clusterHealth: health,
        };
        const session = await this.rpcConnect(client, details);
        await new Promise<void>((resolve, reject) => {
          client.terminateSession(session, { deadline: this.deadline() }, (err) => err ? reject(err) : resolve());
        });
        pool.initializedEndpoints.add(endpointKey(endpoint));
      } catch (err) {
        if (isConnectionLevelError(err)) {
          markEndpointUnhealthy(endpoint);
          continue;
        }
        throw err;
      } finally {
        client.close();
      }
    }
    pool.clusterHealth = health;
    pool.connectionDetails = {
      ...pool.connectionDetails,
      serverEndpoints: endpoints.map(endpointKey),
      clusterHealth: health,
    };
  }

  private requireSession(): SessionInfo {
    if (this.closed) {
      throw new OjpConnectionError('This client is closed. Create a new client to connect.');
    }
    if (this.affinityError) {
      throw this.affinityError;
    }
    if (!this.session) {
      throw new OjpConnectionError('Session not started. Call connect() before executing commands.');
    }
    if (this.session.sessionUUID && this.boundServerEndpoint && !isEndpointHealthy(this.boundServerEndpoint)) {
      throw new OjpConnectionError(
        `The session bound to ${this.endpointAddress(this.boundServerEndpoint)} is no longer usable `
        + 'while its endpoint is unhealthy; create a new OjpClient/session to retry.',
      );
    }
    const health = clusterHealth(this.parsedUrl.endpoints);
    if (this.session.clusterHealth !== health) {
      this.session = { ...this.session, clusterHealth: health };
    }
    if (!this.session.targetServer && this.boundServerEndpoint) {
      this.session = {
        ...this.session,
        targetServer: this.endpointAddress(this.boundServerEndpoint),
      };
    }
    return this.session;
  }

  private endpointAddress(endpoint: OjpServerEndpoint): string {
    return `${endpoint.host}:${endpoint.port}`;
  }

  private endpointForTargetServer(targetServer: string): OjpServerEndpoint | undefined {
    const normalizedTarget = targetServer.toLowerCase();
    return this.parsedUrl.endpoints.find((endpoint) =>
      this.endpointAddress(endpoint).toLowerCase() === normalizedTarget);
  }

  private rebindToEndpoint(endpoint: OjpServerEndpoint): void {
    const previousEndpoint = this.boundServerEndpoint;
    if (previousEndpoint && endpointKey(previousEndpoint) === endpointKey(endpoint)) {
      return;
    }
    const previousClient = this.grpcClient;
    if (this.endpointRegistered && previousEndpoint) {
      unregisterEndpointSession(previousEndpoint);
    }
    this.boundServerEndpoint = endpoint;
    this.grpcClient = this.buildGrpcClient(endpoint);
    registerEndpointSession(endpoint);
    this.endpointRegistered = true;
    previousClient?.close();
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

  /** Maps errors and marks the bound endpoint unhealthy for connection-level failures. */
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

  private canRetryStatelessOperation(statementUUID = ''): boolean {
    return !this.session?.sessionUUID
      && !statementUUID
      && !this.transactionActive
      && !this.options.xa
      && this.parsedUrl.endpoints.length > 1;
  }

  private async reconnectAfterConnectionFailure(attemptedEndpoints: Set<string>): Promise<void> {
    const failedEndpoint = this.boundServerEndpoint;
    if (failedEndpoint) {
      attemptedEndpoints.add(endpointKey(failedEndpoint));
      markEndpointUnhealthy(failedEndpoint);
    }

    const previousClient = this.grpcClient;
    if (this.endpointRegistered && failedEndpoint) {
      unregisterEndpointSession(failedEndpoint);
      this.endpointRegistered = false;
    }
    this.session = undefined;
    this.grpcClient = undefined;
    this.boundServerEndpoint = undefined;
    previousClient?.close();

    const delayMs = this.options.failoverRetryDelayMs ?? DEFAULT_FAILOVER_RETRY_DELAY_MS;
    if (delayMs > 0) {
      await new Promise((resolve) => setTimeout(resolve, delayMs));
    }
    await this.connectToAvailableEndpoint(attemptedEndpoints);
  }

  private async retryStatelessOperation<T>(
    operation: () => Promise<T>,
    firstError: unknown,
    statementUUID = '',
    attemptedEndpoints = new Set<string>(),
  ): Promise<T> {
    let failure = firstError;
    const maxAttempts = this.options.maxFailoverAttempts ?? DEFAULT_MAX_FAILOVER_ATTEMPTS;
    for (let attempt = 0; attempt < maxAttempts; attempt++) {
      if (!isConnectionLevelError(failure) || !this.canRetryStatelessOperation(statementUUID)) {
        throw this.toClientError(failure);
      }
      try {
        await this.reconnectAfterConnectionFailure(attemptedEndpoints);
        return await operation();
      } catch (retryError) {
        failure = retryError;
      }
    }
    throw this.toClientError(failure);
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
    const parameters = toParameterProtos(params);
    const invoke = (): Promise<OpResult> => {
      const request: StatementRequest = {
        session: this.requireSession(),
        sql,
        parameters,
        statementUUID,
        properties: [
          ...additionalProperties,
          ...(options.generatedKeys ? [{ key: 'STATEMENT_AUTO_GENERATED_KEYS_KEY', intValue: 1 }] : []),
        ],
      };

      return new Promise((resolve, reject) => {
        this.requireClient().executeUpdate(request, (err, response) => {
          if (err) {
            reject(err);
            return;
          }
          const sessionError = this.updateSessionFrom(response);
          if (sessionError) {
            reject(sessionError);
            return;
          }
          resolve(response);
        });
      });
    };

    try {
      return await invoke();
    } catch (err) {
      if (isPoolNotFound(err) && !this.session?.sessionUUID && !statementUUID
          && !this.transactionActive && !this.options.xa) {
        await this.reconnectAfterPoolNotFound();
        try {
          return await invoke();
        } catch (retryError) {
          if (isConnectionLevelError(retryError) && this.canRetryStatelessOperation(statementUUID)) {
            return this.retryStatelessOperation(invoke, retryError, statementUUID);
          }
          throw this.toClientError(retryError);
        }
      }
      if (isConnectionLevelError(err) && this.canRetryStatelessOperation(statementUUID)) {
        return this.retryStatelessOperation(invoke, err, statementUUID);
      }
      throw this.toClientError(err);
    }
  }

  private async reconnectAfterPoolNotFound(): Promise<void> {
    const previousClient = this.grpcClient;
    const previousEndpoint = this.boundServerEndpoint;
    if (this.endpointRegistered && previousEndpoint) {
      unregisterEndpointSession(previousEndpoint);
      this.endpointRegistered = false;
    }
    this.session = undefined;
    this.grpcClient = undefined;
    this.boundServerEndpoint = undefined;
    previousClient?.close();
    poolCache.delete(this.poolKey);

    try {
      await this.connectToAvailableEndpoint();
    } catch (err) {
      throw this.toClientError(err);
    }
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
        const sessionError = this.updateSessionFrom(response);
        if (sessionError) {
          reject(sessionError);
          return;
        }
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
        const sessionError = this.updateSessionFrom(response);
        if (sessionError) {
          reject(sessionError);
          return;
        }
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
        const sessionError = this.updateSessionFrom(response);
        if (sessionError) {
          reject(sessionError);
          return;
        }
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
   * Exhausting or interrupting the iteration closes the server-side result set.
   */
  private async *streamOpResults(
    request: StatementRequest,
    poolRetryAllowed = true,
    failoverAttemptsRemaining = this.options.maxFailoverAttempts ?? DEFAULT_MAX_FAILOVER_ATTEMPTS,
    attemptedEndpoints = new Set<string>(),
  ): AsyncGenerator<OpResult, void, undefined> {
    const stream = this.requireClient().executeQuery(request);
    // Prevents an error emitted after explicit cancellation (e.g. "Cancelled on
    // client") from becoming an unhandled exception in the process — the real
    // reason for an early interruption is already known by the code that caused it.
    stream.on('error', () => undefined);

    let endedNormally = false;
    let resultSetUUID = '';
    try {
      let rowByRowMode = false;
      let firstBlock = true;

      try {
        for await (const opResult of stream as unknown as AsyncIterable<OpResult>) {
          const sessionError = this.updateSessionFrom(opResult);
          if (sessionError) {
            throw sessionError;
          }
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
        if (poolRetryAllowed && firstBlock && !request.statementUUID && !this.session?.sessionUUID
            && !this.transactionActive && !this.options.xa && isPoolNotFound(err)) {
          await this.reconnectAfterPoolNotFound();
          yield* this.streamOpResults({ ...request, session: this.requireSession() }, false);
          return;
        }
        if (firstBlock && failoverAttemptsRemaining > 0
            && this.canRetryStatelessOperation(request.statementUUID)
            && isConnectionLevelError(err)) {
          await this.reconnectAfterConnectionFailure(attemptedEndpoints);
          yield* this.streamOpResults(
            { ...request, session: this.requireSession() },
            poolRetryAllowed,
            failoverAttemptsRemaining - 1,
            attemptedEndpoints,
          );
          return;
        }
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
      if (resultSetUUID) {
        await this.invokeCallResource({
          resourceType: 'RES_RESULT_SET',
          resourceUUID: resultSetUUID,
          callType: 'CALL_CLOSE',
          resourceName: '',
        });
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

  private updateSessionFrom(
    response: OpResult | SessionInfo | CallResourceResponse | LobReference | LobDataBlock,
  ): Error | undefined {
    const session = 'session' in response ? response.session : response;
    if (this.boundServerEndpoint) {
      markEndpointHealthy(this.boundServerEndpoint);
    }
    if (session) {
      const targetServer = session.targetServer || this.session?.targetServer
        || (this.boundServerEndpoint ? this.endpointAddress(this.boundServerEndpoint) : undefined);
      if (targetServer) {
        const endpoint = this.endpointForTargetServer(targetServer);
        if (!endpoint) {
          this.affinityError = new OjpConnectionError(
            `The ojp-server returned targetServer "${targetServer}", which is not a configured endpoint; `
            + 'refusing to route this session.',
          );
          return this.affinityError;
        }
        this.rebindToEndpoint(endpoint);
      }
      this.session = {
        ...session,
        targetServer,
        clusterHealth: session.clusterHealth ?? this.session?.clusterHealth,
      };
    }
    return undefined;
  }

  /**
   * Sends a BLOB/CLOB to the ojp-server via bidirectional streaming (`createLob`) and
   * returns a reference (`OjpLobRef`) that can be used as a parameter in
   * `executeUpdate`/`executeQuery` (e.g. `INSERT INTO t (col) VALUES (?)` with `[lobRef]`).
   *
   * Accepts a Buffer, string, or async iterable of Buffer/string chunks (including a Node
   * Readable). The client owns the session propagation for every upload block.
   */
  async createLob(data: LobInput, lobKind: 'BLOB' | 'CLOB' = 'BLOB'): Promise<OjpLobRef> {
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

    const isClob = lobKind === 'CLOB';
    const iterator = chunkLobInput(data, isClob)[Symbol.asyncIterator]();
    const firstChunk = await iterator.next();
    const firstBytes = firstChunk.done ? Buffer.alloc(0) : firstChunk.value;

    return new Promise<OjpLobRef>((resolve, reject) => {
      const call = this.requireClient().createLob();
      let lastRef: LobReference | undefined;
      let firstAckReceived = false;
      let settled = false;

      // Blob positions count bytes; Clob.setCharacterStream positions count UTF-16 units.
      let position = 1;
      const writeChunk = (chunk: Buffer): Promise<void> => new Promise((writeResolve, writeReject) => {
        const chunkPosition = position;
        position += isClob ? chunk.toString('utf8').length : chunk.length;
        call.write(
          { session, position: chunkPosition.toString(), data: chunk, lobType: protoLobType },
          (err?: Error | null) => {
            if (err) {
              writeReject(err);
              return;
            }
            writeResolve();
          },
        );
      });
      const writeRemainingChunks = async (): Promise<void> => {
        try {
          for (;;) {
            const next = await iterator.next();
            if (next.done) {
              break;
            }
            await writeChunk(next.value);
          }
          call.end();
        } catch (error) {
          call.destroy(error instanceof Error ? error : new Error(String(error)));
        }
      };

      call.on('data', (ref: LobReference) => {
        lastRef = ref;
        const sessionError = this.updateSessionFrom(ref);
        if (sessionError) {
          settled = true;
          reject(sessionError);
          call.destroy(sessionError);
          return;
        }
        if (!firstAckReceived) {
          firstAckReceived = true;
          // From here on, use the "stateful" session returned by the server (with
          // sessionUUID/connection affinity) for the remaining blocks.
          session = ref.session;
          void writeRemainingChunks();
        }
      });
      call.on('error', (err) => {
        if (settled) {
          return;
        }
        settled = true;
        void iterator.return?.();
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
      void writeChunk(firstBytes).catch((error: unknown) => call.destroy(
        error instanceof Error ? error : new Error(String(error)),
      ));
    });
  }

  /**
   * Reads a LOB reference as a Node.js readable stream. The active OJP session and LOB
   * type come from this client and the reference, so callers never pass protocol session
   * state.
   */
  readLobStream(reference: OjpLobRef): Readable {
    const request: ReadLobRequest = {
      lobReference: {
        session: this.requireSession(),
        uuid: reference.uuid,
        lobType: reference.lobKind === 'BLOB' ? 'LT_BLOB' : 'LT_CLOB',
      },
      position: '1',
      length: READ_LOB_MAX_LENGTH,
    };
    const call = this.requireClient().readLob(request);
    const output = new PassThrough();

    call.on('data', (block: LobDataBlock) => {
      const sessionError = this.updateSessionFrom(block);
      if (sessionError) {
        output.destroy(sessionError);
        call.cancel();
        return;
      }
      if (block.position !== '-1' && block.data?.length && !output.write(block.data)) {
        call.pause();
      }
    });
    output.on('drain', () => call.resume());
    call.on('error', (error) => output.destroy(this.toClientError(error)));
    call.on('end', () => output.end());
    output.on('close', () => {
      if (!output.readableEnded) {
        call.cancel();
      }
    });
    return output;
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
        const sessionError = this.updateSessionFrom(block);
        if (sessionError) {
          settled = true;
          reject(sessionError);
          stream.cancel();
          return;
        }
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
    if (this.transactionActive) {
      throw new OjpConnectionError('A transaction is already active.');
    }
    const invoke = (): Promise<void> => new Promise((resolve, reject) => {
      this.requireClient().startTransaction(this.requireSession(), (err, response) => {
        if (err) {
          reject(err);
          return;
        }
        const sessionError = this.updateSessionFrom(response);
        if (sessionError) {
          reject(sessionError);
          return;
        }
        resolve();
      });
    });

    try {
      await invoke();
    } catch (err) {
      if (isPoolNotFound(err) && !this.session?.sessionUUID && !this.options.xa) {
        await this.reconnectAfterPoolNotFound();
        try {
          await invoke();
        } catch (retryError) {
          throw this.toClientError(retryError);
        }
      } else {
        throw this.toClientError(err);
      }
    }
    this.transactionGeneration++;
    this.savepoints.length = 0;
    this.transactionActive = true;
    this.autoCommit = false;
  }

  getAutoCommit(): boolean {
    return this.autoCommit;
  }

  async withTransaction<T>(operation: (client: OjpClient) => T | Promise<T>): Promise<T> {
    await this.startTransaction();
    try {
      const result = await operation(this);
      await this.commit();
      return result;
    } catch (error) {
      if (this.transactionActive) {
        try {
          await this.rollback();
        } catch (rollbackError) {
          throw new OjpTransactionError(error, rollbackError);
        }
      }
      throw error;
    }
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
        const sessionError = this.updateSessionFrom(response);
        if (sessionError) {
          reject(sessionError);
          return;
        }
        resolve();
      });
    });
    this.endTransaction();
    await this.restoreAutoCommit();
    this.autoCommit = true;
  }

  async rollback(savepoint?: OjpSavepoint): Promise<void> {
    this.assertNotXA('rollback');
    if (savepoint) {
      const savepointUUID = savepoint.assertUsable(this, this.transactionGeneration);
      if (!this.transactionActive) {
        throw new OjpConnectionError('Cannot roll back to a savepoint outside an active transaction.');
      }
      await this.invokeCallResource({
        resourceType: 'RES_CONNECTION',
        callType: 'CALL_ROLLBACK',
        resourceName: '',
        params: [savepointUUID],
      });
      for (const laterSavepoint of this.savepoints) {
        if (laterSavepoint.getSequence() > savepoint.getSequence()) {
          laterSavepoint.invalidate();
        }
      }
      return;
    }

    const session = this.requireSession();
    await new Promise<void>((resolve, reject) => {
      this.requireClient().rollbackTransaction(session, (err, response) => {
        if (err) {
          reject(this.toClientError(err));
          return;
        }
        const sessionError = this.updateSessionFrom(response);
        if (sessionError) {
          reject(sessionError);
          return;
        }
        resolve();
      });
    });
    this.endTransaction();
    await this.restoreAutoCommit();
    this.autoCommit = true;
  }

  async setSavepoint(name?: string): Promise<OjpSavepoint> {
    this.assertNotXA('setSavepoint');
    if (!this.transactionActive) {
      throw new OjpConnectionError('Savepoints require an active transaction.');
    }
    const response = await this.invokeCallResource({
      resourceType: 'RES_CONNECTION',
      callType: 'CALL_SET',
      resourceName: 'Savepoint',
      params: name === undefined ? [] : [name],
    });
    const savepointUUID = fromParameterValue(response.values?.[0]);
    if (typeof savepointUUID !== 'string' || savepointUUID.length === 0) {
      throw new OjpConnectionError('The server did not return a savepoint identifier.');
    }
    const savepoint = new OjpSavepoint(
      this,
      savepointUUID,
      this.transactionGeneration,
      this.savepointSequence++,
      name,
    );
    this.savepoints.push(savepoint);
    return savepoint;
  }

  async releaseSavepoint(savepoint: OjpSavepoint): Promise<void> {
    this.assertNotXA('releaseSavepoint');
    const savepointUUID = savepoint.assertUsable(this, this.transactionGeneration);
    if (!this.transactionActive) {
      throw new OjpConnectionError('Cannot release a savepoint outside an active transaction.');
    }
    await this.invokeCallResource({
      resourceType: 'RES_CONNECTION',
      callType: 'CALL_RELEASE',
      resourceName: 'Savepoint',
      params: [savepointUUID],
    });
    savepoint.markReleased();
  }

  async getTransactionIsolation(): Promise<TransactionIsolationLevel> {
    const response = await this.invokeCallResource({
      resourceType: 'RES_CONNECTION',
      callType: 'CALL_GET',
      resourceName: 'TransactionIsolation',
    });
    const level = fromParameterValue(response.values?.[0]);
    if (!isTransactionIsolationLevel(level)) {
      throw new OjpConnectionError(`The server returned an unsupported transaction isolation level: ${String(level)}.`);
    }
    return level;
  }

  async setTransactionIsolation(level: TransactionIsolationLevel): Promise<void> {
    if (!transactionIsolationLevels.has(level)) {
      throw new RangeError(`Unsupported transaction isolation level: ${String(level)}.`);
    }
    await this.invokeCallResource({
      resourceType: 'RES_CONNECTION',
      callType: 'CALL_SET',
      resourceName: 'TransactionIsolation',
      params: [level],
    });
  }

  private endTransaction(): void {
    this.transactionActive = false;
    for (const savepoint of this.savepoints) {
      savepoint.invalidate();
    }
    this.savepoints.length = 0;
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
        const sessionError = this.updateSessionFrom(response);
        if (sessionError) {
          reject(sessionError);
          return;
        }
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
      startWithFailover: (operation) => this.startXaWithFailover(operation),
      mapError: (err) => this.toClientError(err),
    });
  }

  private async startXaWithFailover(
    operation: (client: StatementServiceClient, session: SessionInfo) => Promise<XaResponse>,
  ): Promise<XaResponse> {
    const excludedEndpoints = new Set<string>();
    const maxAttempts = this.options.maxFailoverAttempts ?? DEFAULT_MAX_FAILOVER_ATTEMPTS;
    let attempts = 0;

    while (true) {
      try {
        return await operation(this.requireClient(), this.requireSession());
      } catch (err) {
        const failedEndpoint = this.boundServerEndpoint;
        if (!isConnectionLevelError(err) || attempts >= maxAttempts || !failedEndpoint) {
          throw err;
        }

        this.toClientError(err);
        excludedEndpoints.add(endpointKey(failedEndpoint));
        this.releaseXaSessionAfterTransportFailure();
        attempts += 1;
        const delayMs = this.options.failoverRetryDelayMs ?? DEFAULT_FAILOVER_RETRY_DELAY_MS;
        if (delayMs > 0) {
          await new Promise((resolve) => setTimeout(resolve, delayMs));
        }
        try {
          await this.connectToAvailableEndpoint(excludedEndpoints);
        } catch {
          throw err;
        }
      }
    }
  }

  private releaseXaSessionAfterTransportFailure(): void {
    const endpoint = this.boundServerEndpoint;
    if (this.healthCheckTimer) {
      clearInterval(this.healthCheckTimer);
      this.healthCheckTimer = undefined;
    }
    if (this.endpointRegistered && endpoint) {
      unregisterEndpointSession(endpoint);
      this.endpointRegistered = false;
    }
    this.session = undefined;
    this.boundServerEndpoint = undefined;
    const client = this.grpcClient;
    this.grpcClient = undefined;
    client?.close();
  }

  /** Terminates the session on the ojp-server and closes the gRPC channel. Idempotent. */
  async close(): Promise<void> {
    if (this.connecting) {
      throw new OjpConnectionError('Cannot close while connecting. Wait for connect() to finish.');
    }
    this.closed = true;
    this.endTransaction();
    this.autoCommit = true;
    const session = this.session;
    const client = this.grpcClient;
    const endpoint = this.boundServerEndpoint;
    if (this.healthCheckTimer) {
      clearInterval(this.healthCheckTimer);
      this.healthCheckTimer = undefined;
    }
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
      if (this.endpointRegistered && endpoint) {
        unregisterEndpointSession(endpoint);
        this.endpointRegistered = false;
      }
      client?.close();
    }
  }
}

/** @internal Test isolation for the process-wide pool identity cache. */
export function resetPoolCacheForTests(): void {
  poolCache.clear();
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
