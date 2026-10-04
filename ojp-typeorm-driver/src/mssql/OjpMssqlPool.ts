import { OjpClient, OjpClientOptions } from '@ojp/node-driver';
import { EventEmitter } from 'events';
import { OjpMssqlTransaction } from './OjpMssqlTransaction';

/**
 * Shape of the object `SqlServerDriver.createPool()` builds and passes to `new
 * this.mssql.ConnectionPool(connectionOptions)` — see `SqlServerDriver.js`: `{
 * connectionTimeout, requestTimeout, stream, pool, options, server, database, port, user,
 * password, authentication, ...options.extra }`.
 *
 * Only `user`/`password`/`pool.max` are used directly by this shim (mapped onto
 * `OjpClientOptions`/pool sizing); the OJP-specific settings below must be supplied through
 * `DataSourceOptions.extra` (which TypeORM spreads into this same object), since standard
 * `SqlServerConnectionOptions` has no room for them:
 *
 * ```ts
 * new DataSource({
 *   type: 'mssql',
 *   driver: createOjpMssqlDriver(),
 *   username: 'app_user',
 *   password: 'secret',
 *   database: 'my_real_db', // used by TypeORM's own bookkeeping, not by this shim
 *   extra: {
 *     ojpUrl: 'jdbc:ojp[localhost:1059]_sqlserver://realHost:1433;databaseName=my_real_db',
 *   },
 * })
 * ```
 */
export interface OjpMssqlPoolOptions {
  user?: string;
  password?: string;
  pool?: { max?: number };
  /** Required. The OJP connection string: `jdbc:ojp[host:port]_sqlserver://realHost:1433;databaseName=realDb`. */
  ojpUrl: string;
  /** Forwarded to `OjpClientOptions.insecure`. Defaults to `true` (matches `OjpClient`'s own default). */
  ojpInsecure?: boolean;
  /** Forwarded to `OjpClientOptions.callTimeoutMs`. */
  ojpCallTimeoutMs?: number;
  /** Anything else TypeORM/`mssql` puts here (server, port, database, options, authentication, ...) is ignored. */
  [extra: string]: unknown;
}

/**
 * Stands in for `mssql.ConnectionPool`. `SqlServerDriver` only ever calls `new
 * this.mssql.ConnectionPool(...)`, `pool.on('error', ...)`, `pool.connect(callback)`,
 * `pool.close(callback)` and `pool.transaction()` — this shim implements exactly that subset.
 *
 * Pools a small number of already-connected `OjpClient` gRPC sessions (bounded by
 * `pool.max`, default 10 — same default `mssql`/TypeORM itself uses). This is NOT
 * client-side physical-connection pooling: an `OjpClient` session is a cheap gRPC handle,
 * not a pinned database connection — the real pool (pluggable via SPI) lives entirely on
 * the ojp-server. What's reused here is only the (comparatively expensive) gRPC channel/session
 * setup, mirroring the same design already used by `OjpPgPool`.
 *
 * Unlike the Postgres shim (where `pool.connect()` is the explicit per-caller lease point),
 * `mssql.ConnectionPool` is passed DIRECTLY into `new Request(pool)` for every ad-hoc
 * (non-transactional) query — leasing/releasing a session happens transparently inside
 * `OjpMssqlRequest.query()`, invisible to TypeORM. `acquireClient`/`releaseClient` are the
 * internal hooks `OjpMssqlRequest`/`OjpMssqlTransaction` use for that.
 */
export class OjpMssqlPool extends EventEmitter {
  private readonly idle: OjpClient[] = [];
  private readonly all = new Set<OjpClient>();
  private readonly waiters: Array<(client: OjpClient) => void> = [];
  private readonly max: number;
  private readonly ojpUrl: string;
  private readonly clientOptions: OjpClientOptions;
  private closed = false;

  constructor(options: OjpMssqlPoolOptions) {
    super();
    if (!options.ojpUrl) {
      throw new Error(
        '@ojp/typeorm-driver: missing "ojpUrl" in DataSourceOptions.extra. Example: '
        + 'extra: { ojpUrl: "jdbc:ojp[localhost:1059]_sqlserver://realHost:1433;databaseName=realDb" }',
      );
    }
    this.ojpUrl = options.ojpUrl;
    this.max = options.pool?.max && options.pool.max > 0 ? options.pool.max : 10;
    this.clientOptions = {
      user: options.user,
      password: options.password,
      insecure: options.ojpInsecure,
      callTimeoutMs: options.ojpCallTimeoutMs,
    };
  }

  /**
   * Mirrors `mssql.ConnectionPool.connect(callback)`: must return `this` synchronously (per
   * `SqlServerDriver.createPool()`, which resolves its own promise with whatever `pool.connect()`
   * returns). Eagerly warms up (and immediately releases) one session so connectivity/credential
   * failures surface at startup, matching the real driver's fail-fast behavior.
   */
  connect(callback?: (err: Error | null, pool?: this) => void): this {
    this.acquireClient()
      .then((client) => {
        this.releaseClient(client);
        callback?.(null, this);
      })
      .catch((err) => callback?.(err as Error));
    return this;
  }

  /** Mirrors `mssql.ConnectionPool.close(callback)`: closes every pooled session. */
  close(callback?: (err?: Error) => void): void {
    this.closed = true;
    this.idle.length = 0;
    const clients = Array.from(this.all);
    this.all.clear();
    Promise.all(clients.map((client) => client.close()))
      .then(() => callback?.())
      .catch((err) => callback?.(err as Error));
  }

  /** Mirrors `mssql.ConnectionPool.transaction()`: returns a Transaction object bound to this pool. */
  transaction(): OjpMssqlTransaction {
    return new OjpMssqlTransaction(this);
  }

  /** @internal Leases an `OjpClient` session, connecting a new one if under `max` and none are idle. */
  async acquireClient(): Promise<OjpClient> {
    if (this.closed) {
      throw new Error('@ojp/typeorm-driver: pool has already been closed via close().');
    }

    const idleClient = this.idle.pop();
    if (idleClient) {
      return idleClient;
    }

    if (this.all.size < this.max) {
      const client = new OjpClient(this.ojpUrl, this.clientOptions);
      await client.connect();
      this.all.add(client);
      return client;
    }

    // Pool exhausted: wait for a release, same back-pressure behavior as `mssql`'s own pool.
    return new Promise<OjpClient>((resolve) => {
      this.waiters.push(resolve);
    });
  }

  /** @internal Returns a session to the pool (or drops it, on a connection-level error). */
  releaseClient(client: OjpClient, err?: Error): void {
    if (!this.all.has(client)) {
      return;
    }
    if (err) {
      // A connection-level failure invalidates the session — drop it instead of reusing it.
      this.all.delete(client);
      void client.close().catch(() => { /* already broken, ignore close failures */ });
      this.emit('error', err);
      return;
    }

    const waiter = this.waiters.shift();
    if (waiter) {
      waiter(client);
      return;
    }
    this.idle.push(client);
  }
}
