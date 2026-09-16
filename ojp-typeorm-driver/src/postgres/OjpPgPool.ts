import { OjpClient, OjpClientOptions } from '@ojp/node-driver';
import { EventEmitter } from 'events';
import { OjpPgClient } from './OjpPgClient';

/**
 * Shape of the object `PostgresDriver.createPool()` builds and passes to `new
 * this.postgres.Pool(connectionOptions)` — see `PostgresDriver.ts` in TypeORM:
 * `{ connectionString, host, user, password, database, port, ssl,
 * connectionTimeoutMillis, application_name, max, ...options.extra }`.
 *
 * Only `user`/`password`/`max` are used directly by this shim (mapped onto
 * `OjpClientOptions`/pool sizing); the OJP-specific settings below must be supplied
 * through `DataSourceOptions.extra` (which TypeORM spreads into this same object),
 * since standard `PostgresConnectionOptions` has no room for them:
 *
 * ```ts
 * new DataSource({
 *   type: 'postgres',
 *   driver: createOjpPostgresDriver(),
 *   username: 'app_user',
 *   password: 'secret',
 *   database: 'my_real_db', // used by TypeORM's own bookkeeping, not by this shim
 *   extra: {
 *     ojpUrl: 'jdbc:ojp[localhost:1059]_postgresql://real-db-host:5432/my_real_db',
 *   },
 * })
 * ```
 */
export interface OjpPgPoolOptions {
  user?: string;
  password?: string;
  max?: number;
  /** Required. The OJP connection string: `jdbc:ojp[host:port]_postgresql://realHost:5432/realDb`. */
  ojpUrl: string;
  /** Forwarded to `OjpClientOptions.insecure`. Defaults to `true` (matches `OjpClient`'s own default). */
  ojpInsecure?: boolean;
  /** Forwarded to `OjpClientOptions.callTimeoutMs`. */
  ojpCallTimeoutMs?: number;
  /** Anything else TypeORM/`pg` puts here (host, port, database, ssl, connectionString, ...) is ignored. */
  [extra: string]: unknown;
}

/**
 * Stands in for `pg.Pool`. `PostgresDriver` only ever calls `new this.postgres.Pool(...)`,
 * `pool.on('error', ...)`, `pool.connect(callback)` and `pool.end(callback)` — this shim
 * implements exactly that subset.
 *
 * Pools a small number of already-connected `OjpClient` gRPC sessions (bounded by
 * `max`, default 10 — same default TypeORM itself uses for `poolSize`). This is NOT
 * client-side physical-connection pooling: an `OjpClient` session is a cheap gRPC
 * handle, not a pinned database connection — the real pool (HikariCP) lives entirely
 * on the ojp-server. What's reused here is only the (comparatively expensive) gRPC
 * channel/session setup, exactly like reusing `java.sql.Connection` handles that the
 * JDBC driver itself does not eagerly bind to a physical connection either.
 */
export class OjpPgPool extends EventEmitter {
  private readonly idle: OjpClient[] = [];
  private readonly all = new Set<OjpClient>();
  private readonly waiters: Array<(client: OjpClient) => void> = [];
  private readonly max: number;
  private readonly ojpUrl: string;
  private readonly clientOptions: OjpClientOptions;
  private ended = false;

  constructor(options: OjpPgPoolOptions) {
    super();
    if (!options.ojpUrl) {
      throw new Error(
        '@ojp/typeorm-driver: missing "ojpUrl" in DataSourceOptions.extra. Example: '
        + 'extra: { ojpUrl: "jdbc:ojp[localhost:1059]_postgresql://realHost:5432/realDb" }',
      );
    }
    this.ojpUrl = options.ojpUrl;
    this.max = options.max && options.max > 0 ? options.max : 10;
    this.clientOptions = {
      user: options.user,
      password: options.password,
      insecure: options.ojpInsecure,
      callTimeoutMs: options.ojpCallTimeoutMs,
    };
  }

  /** Mirrors `pg.Pool.connect((err, connection, release) => ...)`. */
  connect(callback: (err: Error | null, connection?: OjpPgClient, release?: (err?: Error) => void) => void): void {
    this.acquire()
      .then((client) => {
        const releaseFn = (err?: Error): void => this.release(client, err);
        callback(null, new OjpPgClient(client, releaseFn), releaseFn);
      })
      .catch((err) => callback(err as Error));
  }

  /** Mirrors `pg.Pool.end((err?) => ...)`: closes every pooled session. */
  end(callback?: (err?: Error) => void): void {
    this.ended = true;
    this.idle.length = 0;
    const clients = Array.from(this.all);
    this.all.clear();
    Promise.all(clients.map((client) => client.close()))
      .then(() => callback?.())
      .catch((err) => callback?.(err as Error));
  }

  private async acquire(): Promise<OjpClient> {
    if (this.ended) {
      throw new Error('@ojp/typeorm-driver: pool has already been closed via end().');
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

    // Pool exhausted: wait for a release, same back-pressure behavior as `pg-pool`.
    return new Promise<OjpClient>((resolve) => {
      this.waiters.push(resolve);
    });
  }

  private release(client: OjpClient, err?: Error): void {
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
