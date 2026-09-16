import { OjpClient } from '@ojp/node-driver';
import type { OjpMssqlPool } from './OjpMssqlPool';

export type OjpMssqlTransactionCallback = (err: Error | null) => void;

/**
 * Stands in for the object `mssql.ConnectionPool.transaction()` returns. `SqlServerQueryRunner`
 * only ever calls `.begin([isolationLevel], callback)`, `.commit(callback)` and
 * `.rollback(callback)` on it — see `SqlServerQueryRunner.startTransaction/commitTransaction/
 * rollbackTransaction`.
 *
 * Unlike Postgres (where `PostgresQueryRunner` sniffs `BEGIN`/`COMMIT`/`ROLLBACK` from SQL
 * text sent through the same connection object), SQL Server's TypeORM driver calls these
 * dedicated methods directly — they map 1:1 onto `OjpClient.startTransaction()/commit()/
 * rollback()`, no SQL-sentinel matching needed.
 *
 * A transaction pins exactly one `OjpClient` session (leased from the pool at `.begin()` time,
 * returned to the pool at `.commit()`/`.rollback()` time) for its entire duration, since the
 * OJP protocol requires the whole transaction to run against the same physical DB connection.
 * `OjpMssqlRequest` reads `activeClient` to run queries against that same pinned session while
 * the transaction is open.
 */
export class OjpMssqlTransaction {
  private client: OjpClient | undefined;

  constructor(private readonly pool: OjpMssqlPool) {}

  /** @internal Read by `OjpMssqlRequest` to route queries to the session pinned by this transaction. */
  get activeClient(): OjpClient | undefined {
    return this.client;
  }

  begin(callback?: OjpMssqlTransactionCallback): void;
  begin(isolationLevel: unknown, callback?: OjpMssqlTransactionCallback): void;
  begin(isolationLevelOrCallback?: unknown, maybeCallback?: OjpMssqlTransactionCallback): void {
    const callback = typeof isolationLevelOrCallback === 'function'
      ? (isolationLevelOrCallback as OjpMssqlTransactionCallback)
      : maybeCallback;

    this.pool.acquireClient()
      .then(async (client) => {
        this.client = client;
        // The isolation level is intentionally not forwarded: `OjpClient.startTransaction()`
        // has no isolation-level parameter today. Session-default isolation applies.
        await client.startTransaction();
        callback?.(null);
      })
      .catch((err) => callback?.(err as Error));
  }

  commit(callback?: OjpMssqlTransactionCallback): void {
    const client = this.client;
    if (!client) {
      callback?.(new Error('@ojp/typeorm-driver: commit() called without a matching begin().'));
      return;
    }
    client.commit()
      .then(() => {
        this.pool.releaseClient(client);
        this.client = undefined;
        callback?.(null);
      })
      .catch((err) => {
        this.pool.releaseClient(client, err as Error);
        this.client = undefined;
        callback?.(err as Error);
      });
  }

  rollback(callback?: OjpMssqlTransactionCallback): void {
    const client = this.client;
    if (!client) {
      callback?.(new Error('@ojp/typeorm-driver: rollback() called without a matching begin().'));
      return;
    }
    client.rollback()
      .then(() => {
        this.pool.releaseClient(client);
        this.client = undefined;
        callback?.(null);
      })
      .catch((err) => {
        this.pool.releaseClient(client, err as Error);
        this.client = undefined;
        callback?.(err as Error);
      });
  }
}
