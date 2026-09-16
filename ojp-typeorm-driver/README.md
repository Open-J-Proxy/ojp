# @ojp/typeorm-driver

TypeORM driver adapter for **OJP (Open J Proxy)**, usable directly from NestJS.

> Status: **MVP under development**. API subject to change until stable v1.0.0.

## Why this package exists

TypeORM has no concept of a pluggable transport for its `postgres`/`mssql` drivers — it
always calls `PlatformTools.load('pg')` / `PlatformTools.load('mssql')` and talks to the
real driver package directly. This package provides drop-in replacements for those
packages (`Pool`/`Client` for Postgres, `ConnectionPool`/`Request` for SQL Server) that
implement just enough of each real driver's surface for `PostgresDriver` /
`SqlServerDriver` to work, but route every call through
[`@ojp/node-driver`](../ojp-node-driver) (gRPC) to `ojp-server` instead of opening a
direct database connection.

```
[NestJS/TypeORM] --> [@ojp/typeorm-driver shim] --> [@ojp/node-driver] --gRPC/HTTP2--> [ojp-server] --JDBC--> [Database]
```

This means:

- No application-level connection pool: the shim leases lightweight `OjpClient` gRPC
  sessions from a small internal pool, but the *real* physical connection pool (pluggable
  via SPI on the server side) lives entirely in `ojp-server`. Never enable a real
  `pg`/`mssql` pool alongside this — see the "Critical rules" section in the main repo's
  `AGENTS.md`.
- TypeORM's query builder, migrations, entity hydration, transactions, etc. all work
  unmodified — only the transport underneath is swapped out.

## Supported dialects

| TypeORM `type` | Status | Shim module |
|---|---|---|
| `postgres` | Implemented | `src/postgres/` |
| `mssql` (SQL Server) | Implemented | `src/mssql/` |

Both shims share `src/common/rowShaping.ts`, which normalizes values that
`@ojp/node-driver` decodes as native JS `bigint` back into `string` for BIGINT columns —
matching what the real `pg` and `mssql` drivers return (and what TypeORM's own hydration
logic expects) and keeping results safely `JSON.stringify`-able.

## Installation

This package is not yet published; use a local `file:` dependency pointing at this
package's folder from the consuming project's `package.json`:

```json
{
  "dependencies": {
    "@ojp/typeorm-driver": "file:../ojp-typeorm-driver",
    "typeorm": "0.3.20"
  }
}
```

Both `@ojp/typeorm-driver` and its own dependency `@ojp/node-driver` must be built
(`npm run build`) before being consumed from another project's `node_modules`.

## Usage — PostgreSQL

```ts
import 'reflect-metadata';
import { DataSource } from 'typeorm';
import { createOjpPostgresDriver } from '@ojp/typeorm-driver';

const dataSource = new DataSource({
  type: 'postgres',
  driver: createOjpPostgresDriver(),
  username: 'app_user',
  password: 'secret',
  database: 'my_real_db',
  extra: {
    ojpUrl: 'jdbc:ojp[localhost:1059]_postgresql://real-db-host:5432/my_real_db',
  },
  entities: [MyEntity],
});

await dataSource.initialize();
```

## Usage — SQL Server

```ts
import 'reflect-metadata';
import { DataSource } from 'typeorm';
import { createOjpMssqlDriver } from '@ojp/typeorm-driver';

const dataSource = new DataSource({
  type: 'mssql',
  driver: createOjpMssqlDriver(),
  username: 'app_user',
  password: 'secret',
  database: 'my_real_db',
  extra: {
    ojpUrl: 'jdbc:ojp[localhost:1059]_sqlserver://real-db-host:1433;databaseName=my_real_db;'
      + 'encrypt=true;trustServerCertificate=true;',
  },
  entities: [MyEntity],
});

await dataSource.initialize();
```

### NestJS

Both shims plug into `TypeOrmModule.forRoot()` / `forRootAsync()` exactly the same way —
just pass `driver` and `extra.ojpUrl` alongside the usual TypeORM options:

```ts
TypeOrmModule.forRoot({
  type: 'mssql',
  driver: createOjpMssqlDriver(),
  username: process.env.DB_USER,
  password: process.env.DB_PASSWORD,
  database: process.env.DB_NAME,
  extra: { ojpUrl: process.env.OJP_URL },
  entities: [/* ... */],
});
```

### `extra` options (both dialects)

| Option | Required | Description |
|---|---|---|
| `ojpUrl` | Yes | The OJP connection string: `jdbc:ojp[host:port]_<backendJdbcUrl>`. |
| `ojpInsecure` | No | Forwarded to `OjpClientOptions.insecure` (defaults to `true`, no TLS on the gRPC channel). |
| `ojpCallTimeoutMs` | No | Forwarded to `OjpClientOptions.callTimeoutMs`. Raise this for slow/loaded environments. |

`pool.max` (standard TypeORM/`mssql` option) bounds how many `OjpClient` gRPC sessions the
shim keeps open concurrently; it is not a physical database connection pool size.

## SQL Server type mapping notes

`ojp-server`'s JDBC write path binds parameters purely off a declared `ParameterType`, so
`src/mssql/mssqlTypes.ts` only needs to preserve that type information — it does not need
(and discards) the length/precision/scale arguments the real `mssql` package's type
constructors accept (e.g. `mssql.Decimal(10, 2)`).

Precision-sensitive types are carried as `OjpTypedParam` values from `@ojp/node-driver`:

- `DECIMAL`/`NUMERIC`/`MONEY` are encoded via `encodeBigDecimalWire()` to avoid
  floating-point rounding.
- `DATETIMEOFFSET` carries an explicit `TEMPORAL_TYPE_OFFSET_DATE_TIME` hint so
  `ojp-server` reconstructs a Java `OffsetDateTime` instead of assuming a plain
  `Timestamp`.
- `BIGINT` columns are always returned as `string` (see "Supported dialects" above).

## Development

```bash
npm install
npm run build   # tsc — compiles src/ to dist/
npm test        # unit tests only (no external infrastructure required)
```

### Unit tests

`npm test` runs the full unit suite (SQL translation, pool/transaction/request behavior
against a mocked `OjpClient`, and row-shaping helpers) for both dialects — no running
`ojp-server` or database is required.

### Integration tests

Integration tests exercise the shims against a real `ojp-server` and a real database, and
are skipped by default so `npm test` stays fast and infrastructure-free.

**PostgreSQL** (`npm run test:integration`):

Requires a local `ojp-server` on `localhost:1065` and a Postgres instance reachable by it
(see `test/integration.postgres.test.ts` for the exact connection details/env vars).

**SQL Server** (`npm run test:integration:mssql`):

Requires:
- A local `ojp-server` listening on `localhost:1065` (or `OJP_TEST_PORT`), with the
  Microsoft JDBC driver for SQL Server present in `ojp-libs/` (see
  `ojp-server/download-drivers.sh` / the SQL Server driver license terms).
- A reachable SQL Server instance with a database matching `OJP_TEST_MSSQL_DATABASE`
  (defaults to `ojp_typeorm_driver_test`).
- Set `OJP_ENABLE_SQLSERVER_AG_TESTS=true` to enable the suite; otherwise it is skipped.

Relevant environment variables (all optional, with sensible local defaults — see
`test/integration.mssql.test.ts`):

| Variable | Default |
|---|---|
| `OJP_ENABLE_SQLSERVER_AG_TESTS` | `false` (suite skipped) |
| `OJP_TEST_PORT` | `1065` |
| `OJP_TEST_MSSQL_BACKEND_HOST` | `localhost` |
| `OJP_TEST_MSSQL_BACKEND_PORT` | `14330` |
| `OJP_TEST_MSSQL_DATABASE` | `ojp_typeorm_driver_test` |
| `OJP_TEST_SQLSERVER_USER` | `sa` |
| `OJP_TEST_SQLSERVER_PASSWORD` | a local-only test password, see the test file |

This suite validates, against real infrastructure: basic CRUD, transaction
commit/rollback, unique constraint violations, pagination, typed columns
(decimal/bigint/datetimeoffset), and TypeORM's `DECLARE`/`INSERT ... OUTPUT ... INTO`/
`SELECT` batch used to retrieve generated columns after an insert.

## Known limitations

- Only the subset of the real `pg`/`mssql` driver surface that `PostgresDriver` /
  `SqlServerDriver` actually touch is implemented — this is not a general-purpose
  replacement for either package.
- SQL Server DB2/LOB row-by-row fetch mode inherited from `@ojp/node-driver` has not been
  validated against a live DB2 instance (see that package's README).
- `mssqlTypes.ISOLATION_LEVEL` markers are accepted by `SqlServerQueryRunner` but are not
  currently forwarded to `ojp-server` (`OjpClient.startTransaction()` takes no isolation
  level argument yet).
