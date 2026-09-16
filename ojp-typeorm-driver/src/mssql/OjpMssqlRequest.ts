import { OjpClient, OjpTypedParam } from '@ojp/node-driver';
import { stringifyBigInts } from '../common/rowShaping';
import { OjpMssqlPool } from './OjpMssqlPool';
import { OjpMssqlTransaction } from './OjpMssqlTransaction';
import { isTypeDescriptor } from './mssqlTypes';
import { producesResultSet, translateIndexedParams } from './sqlTranslate';

/**
 * Minimal shape TypeORM's `SqlServerQueryRunner.query()` reads off the object returned by
 * `request.query(sql, callback)`: `raw.recordset` (rows) and `raw.rowsAffected[0]` (affected
 * row count). The real `mssql` package's result also carries `recordsets`/`output`, but
 * neither is read anywhere in `SqlServerQueryRunner`/`SqlServerDriver`, so they are omitted.
 */
export interface OjpMssqlRawResult {
  recordset: Record<string, unknown>[];
  rowsAffected: number[];
}

export type OjpMssqlQueryCallback = (err: Error | null, raw?: OjpMssqlRawResult) => void;

/**
 * Stands in for `mssql.Request`. `SqlServerQueryRunner` only ever calls `new
 * this.driver.mssql.Request(poolOrTransaction)`, `.input(name, [type], value)` and
 * `.query(sqlText, callback)` — this shim implements exactly that subset (`PreparedStatement`,
 * stored-procedure multi-recordset execution, and `.toReadableStream()` are unused by
 * TypeORM's own generated SQL and are intentionally not implemented).
 *
 * `.input()` is called once per bound parameter, in ascending numeric-string order (`"0",
 * "1", "2", ...`, matching the `@0, @1, @2, ...` placeholders `SqlServerDriver` emits). When
 * TypeORM passes an explicit type (from an `MssqlParameter`, itself only produced for
 * INSERT/UPDATE column values via `SqlServerDriver.parametrizeValue()`), the value is wrapped
 * in an `OjpTypedParam` so `@ojp/node-driver` binds it with that exact `ParameterType` instead
 * of inferring one from the raw JS value (see `mssqlTypes.ts` for the type-marker mapping).
 */
export class OjpMssqlRequest {
  private readonly inputs: unknown[] = [];

  constructor(private readonly target: OjpMssqlPool | OjpMssqlTransaction) {}

  /** Mirrors `mssql.Request.input(name, value)` / `.input(name, type, value)`. Chainable, like the real API. */
  input(name: string, ...rest: unknown[]): this {
    const index = Number(name);
    if (rest.length >= 2 && isTypeDescriptor(rest[0])) {
      const descriptor = rest[0];
      this.inputs[index] = new OjpTypedParam(rest[1], descriptor.ojpParamType, descriptor.temporalType);
      return this;
    }
    // 1-arg (or non-descriptor 2-arg) form: bind the raw value, no explicit type hint.
    this.inputs[index] = rest[0];
    return this;
  }

  query(sqlText: string): Promise<OjpMssqlRawResult>;
  query(sqlText: string, callback: OjpMssqlQueryCallback): void;
  query(sqlText: string, callback?: OjpMssqlQueryCallback): Promise<OjpMssqlRawResult> | void {
    const resultPromise = this.executeQuery(sqlText);
    if (callback) {
      resultPromise
        .then((raw) => callback(null, raw))
        .catch((err) => callback(err as Error));
      return undefined;
    }
    return resultPromise;
  }

  private async executeQuery(sqlText: string): Promise<OjpMssqlRawResult> {
    const { sql, params } = translateIndexedParams(sqlText, this.inputs);

    if (this.target instanceof OjpMssqlTransaction) {
      const client = this.target.activeClient;
      if (!client) {
        throw new Error(
          '@ojp/typeorm-driver: no active transaction session (begin() was not called, or the '
          + 'transaction has already been committed/rolled back).',
        );
      }
      return this.runAgainst(client, sql, params);
    }

    const pool = this.target;
    const client = await pool.acquireClient();
    try {
      const result = await this.runAgainst(client, sql, params);
      pool.releaseClient(client);
      return result;
    } catch (err) {
      pool.releaseClient(client, err as Error);
      throw err;
    }
  }

  private async runAgainst(client: OjpClient, sql: string, params: unknown[]): Promise<OjpMssqlRawResult> {
    if (producesResultSet(sql)) {
      const { rows } = await client.executeQuery(sql, params);
      const shapedRows = stringifyBigInts(rows);
      return { recordset: shapedRows, rowsAffected: [shapedRows.length] };
    }
    const affectedRows = await client.executeUpdate(sql, params);
    return { recordset: [], rowsAffected: [affectedRows] };
  }
}
