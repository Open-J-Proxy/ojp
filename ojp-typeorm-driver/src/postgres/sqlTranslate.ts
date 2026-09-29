/**
 * Translation helpers between the `pg`-style SQL text TypeORM generates for the
 * Postgres dialect and what `OjpClient` (and, ultimately, the JDBC `PreparedStatement`
 * on the ojp-server) expects.
 *
 * TypeORM's `PostgresQueryRunner` always calls `databaseConnection.query(sql, params)`
 * with `$1, $2, ...` positional placeholders (the native `pg` convention) and expects a
 * `pg`-shaped result back (`{ rows, rowCount, command }`). `OjpClient`, however, speaks
 * plain JDBC: `?` placeholders bound strictly by occurrence order. The two conventions
 * are NOT equivalent when a placeholder is referenced more than once in the same
 * statement (e.g. upserts referencing `$1` in both the INSERT and the `ON CONFLICT DO
 * UPDATE` clauses) — `?` must be repeated once per occurrence, with the matching value
 * duplicated in the parameter array, since JDBC binds positionally by occurrence, not
 * by referenced index.
 */

const POSTGRES_PARAM_PATTERN = /\$(\d+)/g;

export interface TranslatedQuery {
  sql: string;
  params: unknown[];
}

/** Rewrites `$1, $2, ...` placeholders into `?`, reordering/duplicating `params` as needed. */
export function translatePositionalParams(sql: string, params: ReadonlyArray<unknown> = []): TranslatedQuery {
  const orderedParams: unknown[] = [];
  const translatedSql = sql.replace(POSTGRES_PARAM_PATTERN, (_match, indexStr: string) => {
    orderedParams.push(params[Number(indexStr) - 1]);
    return '?';
  });
  return { sql: translatedSql, params: orderedParams };
}

const RESULT_SET_LEADING_KEYWORDS = /^\s*(SELECT|WITH|SHOW|EXPLAIN|VALUES|TABLE)\b/i;
const RETURNING_CLAUSE = /\bRETURNING\b/i;

/**
 * Decides whether a statement produces a JDBC `ResultSet` (must go through
 * `OjpClient.executeQuery`) or only an affected-row count (`OjpClient.executeUpdate`).
 *
 * Note this is NOT the same as "is this a SELECT": an `INSERT ... RETURNING *` (used by
 * TypeORM to read back generated columns after every insert) has zero to do with SELECT
 * but DOES return rows — one per affected row, which conveniently also means `rows.length`
 * is an exact, correct substitute for `rowCount` in that case.
 */
export function producesResultSet(sql: string): boolean {
  return RESULT_SET_LEADING_KEYWORDS.test(sql) || RETURNING_CLAUSE.test(sql);
}

const COMMAND_PATTERN = /^\s*([A-Za-z]+)/;

/** Extracts the leading SQL verb (`SELECT`, `INSERT`, `UPDATE`, ...), mirroring `pg`'s `result.command`. */
export function detectCommand(sql: string): string {
  const match = COMMAND_PATTERN.exec(sql);
  return match ? match[1].toUpperCase() : '';
}

export type TransactionSentinel =
  | { kind: 'begin'; isolationLevel?: string }
  | { kind: 'commit' }
  | { kind: 'rollback' }
  | { kind: 'passthrough' };

/**
 * Recognizes the literal SQL text TypeORM's `PostgresQueryRunner` sends to demarcate
 * transactions (`START TRANSACTION` / `COMMIT` / `ROLLBACK`) so they can be translated
 * into dedicated `OjpClient` RPCs instead of being forwarded as plain SQL — the OJP
 * protocol requires an explicit `startTransaction`/`commitTransaction`/`rollbackTransaction`
 * call to pin the session to one physical connection, it does not infer this from SQL text.
 *
 * `SAVEPOINT` / `RELEASE SAVEPOINT` / `ROLLBACK TO SAVEPOINT` (used for nested
 * transactions) are intentionally left as `passthrough`: by the time they run, the
 * session is already pinned by an outer `startTransaction`, so they can be executed as
 * ordinary SQL through that same sticky session.
 */
export function matchTransactionSentinel(sql: string): TransactionSentinel {
  const trimmed = sql.trim();
  if (/^(START\s+TRANSACTION|BEGIN)\s*$/i.test(trimmed)) {
    return { kind: 'begin' };
  }
  if (/^COMMIT\s*$/i.test(trimmed)) {
    return { kind: 'commit' };
  }
  if (/^ROLLBACK\s*$/i.test(trimmed)) {
    return { kind: 'rollback' };
  }
  return { kind: 'passthrough' };
}
