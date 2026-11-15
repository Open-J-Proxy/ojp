export { OjpPgPool, OjpPgPoolOptions } from './postgres/OjpPgPool';
export { OjpPgClient, OjpPgQueryResult } from './postgres/OjpPgClient';
export {
  translatePositionalParams,
  producesResultSet,
  detectCommand,
  matchTransactionSentinel,
  TranslatedQuery,
  TransactionSentinel,
} from './postgres/sqlTranslate';

export { OjpMssqlPool, OjpMssqlPoolOptions } from './mssql/OjpMssqlPool';
export { OjpMssqlTransaction } from './mssql/OjpMssqlTransaction';
export { OjpMssqlRequest, OjpMssqlRawResult } from './mssql/OjpMssqlRequest';
export { mssqlTypes, OjpMssqlTypeDescriptor } from './mssql/mssqlTypes';
export {
  translateIndexedParams as translateMssqlIndexedParams,
  producesResultSet as mssqlProducesResultSet,
  TranslatedQuery as MssqlTranslatedQuery,
} from './mssql/sqlTranslate';

import { OjpPgPool } from './postgres/OjpPgPool';
import { OjpMssqlPool } from './mssql/OjpMssqlPool';
import { OjpMssqlRequest } from './mssql/OjpMssqlRequest';
import { mssqlTypes } from './mssql/mssqlTypes';

/**
 * Object shape expected by `PostgresDriver.loadDependencies()`
 * (`this.postgres = this.options.driver ?? PlatformTools.load("pg")`): only `.Pool` is
 * ever read off it (the real `pg` package's `Client`/`types`/`defaults` are never
 * touched by TypeORM unless `nativeDriver`/`parseInt8` options are set, which this
 * shim does not use).
 */
export interface OjpPostgresDriverPackage {
  Pool: typeof OjpPgPool;
}

/**
 * Builds the `driver` value to pass in TypeORM's `DataSourceOptions` for `type: 'postgres'`
 * so all Postgres traffic is routed through OJP instead of a direct `pg` connection.
 *
 * Usage:
 * ```ts
 * import { DataSource } from 'typeorm';
 * import { createOjpPostgresDriver } from '@ojp/typeorm-driver';
 *
 * const dataSource = new DataSource({
 *   type: 'postgres',
 *   driver: createOjpPostgresDriver(),
 *   username: 'app_user',
 *   password: 'secret',
 *   database: 'my_real_db',
 *   extra: {
 *     ojpUrl: 'jdbc:ojp[localhost:1059]_postgresql://real-db-host:5432/my_real_db',
 *   },
 *   entities: [MyEntity],
 * });
 * ```
 */
export function createOjpPostgresDriver(): OjpPostgresDriverPackage {
  return { Pool: OjpPgPool };
}

/**
 * Object shape expected by `SqlServerDriver.loadDependencies()`
 * (`this.mssql = this.options.driver || PlatformTools.load("mssql")`). Besides
 * `ConnectionPool`/`Request` (the only two constructed via `new this.mssql.X(...)`),
 * every `mssqlTypes` entry (type constructors + `ISOLATION_LEVEL`) is spread in, since
 * `SqlServerQueryRunner` reads those directly off this same object (e.g.
 * `this.driver.mssql.Int`, `this.driver.mssql.Decimal(p, s)`).
 */
export interface OjpMssqlDriverPackage {
  ConnectionPool: typeof OjpMssqlPool;
  Request: typeof OjpMssqlRequest;
  [typeConstructorOrConstant: string]: unknown;
}

/**
 * Builds the `driver` value to pass in TypeORM's `DataSourceOptions` for `type: 'mssql'`
 * so all SQL Server traffic is routed through OJP instead of a direct `mssql` connection.
 *
 * Usage:
 * ```ts
 * import { DataSource } from 'typeorm';
 * import { createOjpMssqlDriver } from '@ojp/typeorm-driver';
 *
 * const dataSource = new DataSource({
 *   type: 'mssql',
 *   driver: createOjpMssqlDriver(),
 *   username: 'app_user',
 *   password: 'secret',
 *   database: 'my_real_db',
 *   extra: {
 *     ojpUrl: 'jdbc:ojp[localhost:1059]_sqlserver://real-db-host:1433;databaseName=my_real_db',
 *   },
 *   entities: [MyEntity],
 * });
 * ```
 */
export function createOjpMssqlDriver(): OjpMssqlDriverPackage {
  return { ConnectionPool: OjpMssqlPool, Request: OjpMssqlRequest, ...mssqlTypes };
}
