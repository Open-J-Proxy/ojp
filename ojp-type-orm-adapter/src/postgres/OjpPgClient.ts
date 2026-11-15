import { EventEmitter } from 'events';
import { OjpClient } from '@ojp/node-driver';
import { stringifyBigInts } from '../common/rowShaping';
import { detectCommand, matchTransactionSentinel, producesResultSet, translatePositionalParams } from './sqlTranslate';

export interface OjpPgQueryResult {
  rows: Record<string, unknown>[];
  rowCount: number;
  command: string;
}

/**
 * Stands in for the `pg.Client`/pooled-connection object that `PostgresQueryRunner`
 * receives from `pool.connect()`. Wraps a single, already-connected `OjpClient` session.
 *
 * Extends `EventEmitter` only because `PostgresDriver.createPool()` unconditionally
 * attaches `connection.on('notice', ...)`/`connection.on('notification', ...)` listeners
 * when `options.logNotifications` is enabled, and `PostgresQueryRunner.connect()` always
 * attaches an `error` listener to detect connection-level failures. OJP has no equivalent
 * of Postgres `NOTICE`/`LISTEN/NOTIFY` today, so those events are simply never emitted.
 */
export type OjpPgQueryCallback = (err: Error | null, result?: OjpPgQueryResult) => void;

export class OjpPgClient extends EventEmitter {
  constructor(
    private readonly ojpClient: OjpClient,
    private readonly onRelease: (err?: Error) => void,
  ) {
    super();
  }

  /**
   * Executes a single statement. Mirrors `pg.Client`'s full `query()` overload set, since
   * `pg` supports both a promise-based and a Node-style-callback calling convention and
   * TypeORM's Postgres driver uses BOTH:
   *  - `PostgresQueryRunner.query()` uses the promise form: `await databaseConnection.query(text, params)`.
   *  - `PostgresDriver.executeQuery()` (used for `SELECT version()`, extension setup, etc.)
   *    uses the callback form: `connection.query(text, (err, result) => ...)`.
   * Without supporting the callback form, that second call site's callback would never be
   * invoked and its wrapping `new Promise(...)` would hang forever.
   */
  query(text: string): Promise<OjpPgQueryResult>;
  query(text: string, params: unknown[]): Promise<OjpPgQueryResult>;
  query(text: string, callback: OjpPgQueryCallback): void;
  query(text: string, params: unknown[], callback: OjpPgQueryCallback): void;
  query(
    text: string,
    paramsOrCallback?: unknown[] | OjpPgQueryCallback,
    maybeCallback?: OjpPgQueryCallback,
  ): Promise<OjpPgQueryResult> | void {
    let params: unknown[] = [];
    let callback: OjpPgQueryCallback | undefined;

    if (typeof paramsOrCallback === 'function') {
      callback = paramsOrCallback;
    } else if (paramsOrCallback) {
      params = paramsOrCallback;
      callback = maybeCallback;
    }

    const resultPromise = this.executeQuery(text, params);

    if (callback) {
      resultPromise
        .then((result) => callback!(null, result))
        .catch((err) => callback!(err as Error));
      return undefined;
    }

    return resultPromise;
  }

  private async executeQuery(text: string, params: unknown[]): Promise<OjpPgQueryResult> {
    const sentinel = matchTransactionSentinel(text);
    if (sentinel.kind === 'begin') {
      await this.ojpClient.startTransaction();
      return { rows: [], rowCount: 0, command: 'BEGIN' };
    }
    if (sentinel.kind === 'commit') {
      await this.ojpClient.commit();
      return { rows: [], rowCount: 0, command: 'COMMIT' };
    }
    if (sentinel.kind === 'rollback') {
      await this.ojpClient.rollback();
      return { rows: [], rowCount: 0, command: 'ROLLBACK' };
    }

    const { sql, params: orderedParams } = translatePositionalParams(text, params);
    const command = detectCommand(sql);

    if (producesResultSet(sql)) {
      const { rows } = await this.ojpClient.executeQuery(sql, orderedParams);
      const shapedRows = stringifyBigInts(rows);
      // For `INSERT/UPDATE/DELETE ... RETURNING`, Postgres yields exactly one row per
      // affected row, so `rows.length` is an exact stand-in for `rowCount` — there is no
      // separate affected-row count to ask the server for in that case.
      return { rows: shapedRows, rowCount: shapedRows.length, command };
    }

    const affectedRows = await this.ojpClient.executeUpdate(sql, orderedParams);
    return { rows: [], rowCount: affectedRows, command };
  }

  /** Releases this connection back to the owning `OjpPgPool`. Mirrors `pg`'s `release(err?)`. */
  release(err?: Error): void {
    this.onRelease(err);
  }
}
