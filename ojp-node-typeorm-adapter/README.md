# OJP Node TypeORM adapter

This adapter lets a TypeORM application use PostgreSQL or SQL Server through
[OJP](../README.md). Your application sends requests to `ojp-server` through
the [OJP Node driver](../ojp-node-driver); the server connects to the database.
Use your normal TypeORM entities, repositories and transactions.

The adapter implements the parts of the `pg` and `mssql` interfaces used by
TypeORM, not a general-purpose replacement for those packages. It is an
experimental, private package and is not published to npm.

## Connection pooling

**Remove or disable any local database connection pool in your application.**
Do not put a separate `pg`, `mssql`, or other pool in front of this adapter.
The physical database connection pool belongs on the OJP server.

TypeORM expects objects called `Pool` or `ConnectionPool`; the adapter provides
those names for compatibility only. They do not cache or reuse OJP sessions.
Each operation opens and closes a session; a transaction keeps one session
until it commits or rolls back. Settings such as `poolSize` and `pool.max` do
not configure a client-side session pool.

## Install

Build the Node driver first, then install and build the adapter from the
repository root:

```sh
cd ojp-node-driver
npm ci
npm run build
cd ../ojp-node-typeorm-adapter
npm ci
npm run build
```

For an application directory inside the repository root, add a local
dependency on the adapter and install TypeORM and `reflect-metadata`:

```json
{
  "dependencies": {
    "@ojp/node-typeorm-adapter": "file:../ojp-node-typeorm-adapter",
    "reflect-metadata": "^0.2.2",
    "typeorm": "^1.1.3"
  }
}
```

The adapter requires TypeORM 1.1.3 or later and Node.js 20.19.0, 22.13.0,
24.11.0, or later.

Keep `ojp-node-driver` and `ojp-grpc-contract` alongside the adapter as they
are in this repository. Build the Node driver and adapter before installing
the application dependencies. Adjust the `file:` path if your application
is elsewhere.

## Use PostgreSQL

Start an OJP server that can reach your database. Define your TypeORM entities
as usual, then pass the adapter as the TypeORM driver:

```ts
import 'reflect-metadata';
import { DataSource } from 'typeorm';
import { createOjpPostgresDriver } from '@ojp/node-typeorm-adapter';
import { MyEntity } from './MyEntity';

const dataSource = new DataSource({
  type: 'postgres',
  driver: createOjpPostgresDriver(),
  username: process.env.DB_USER,
  password: process.env.DB_PASSWORD,
  database: 'my_database',
  extra: {
    ojpUrl: 'jdbc:ojp[localhost:1059]_postgresql://db-host:5432/my_database',
  },
  entities: [MyEntity],
});

await dataSource.initialize();
// Use dataSource.getRepository(MyEntity), transactions, and other TypeORM APIs.
await dataSource.destroy();
```

## Use SQL Server

Use the SQL Server factory and a SQL Server JDBC URL instead:

```ts
import 'reflect-metadata';
import { DataSource } from 'typeorm';
import { createOjpMssqlDriver } from '@ojp/node-typeorm-adapter';
import { MyEntity } from './MyEntity';

const dataSource = new DataSource({
  type: 'mssql',
  driver: createOjpMssqlDriver(),
  username: process.env.DB_USER,
  password: process.env.DB_PASSWORD,
  database: 'my_database',
  extra: {
    ojpUrl: 'jdbc:ojp[localhost:1059]_sqlserver://db-host:1433;databaseName=my_database;encrypt=true',
  },
  entities: [MyEntity],
});

await dataSource.initialize();
// Use TypeORM as usual.
await dataSource.destroy();
```

For NestJS, pass the same options to `TypeOrmModule.forRoot()` or
`forRootAsync()`. Keep credentials outside source control, and configure
database and OJP transport security for your environment.

Both factories accept these TypeORM `extra` options:

| Option | Meaning |
| --- | --- |
| `ojpUrl` | Required: `jdbc:ojp[server-host:port]_<database JDBC URL>`. |
| `ojpInsecure` | Defaults to `true`, which disables TLS for the OJP gRPC connection. Set to `false` to use TLS. |
| `ojpCallTimeoutMs` | Optional gRPC call timeout in milliseconds. |

## Tests

Run `npm test` in this directory for unit tests. Integration tests need a
running OJP server and real databases, and are disabled by default. They use
the Node driver's CSV fixtures in `../ojp-node-driver/testdata/`; override
them with `OJP_TEST_POSTGRES_CSV` or `OJP_TEST_SQLSERVER_CSV` if needed.
The tests create uniquely named tables and remove them afterwards.

```sh
OJP_ENABLE_INTEGRATION_TESTS=true npm run test:integration
OJP_ENABLE_SQLSERVER_AG_TESTS=true npm run test:integration:mssql
```

Set `OJP_TEST_SERVER_ENDPOINT` if the server is not at `localhost:1059`,
and `OJP_TEST_PASSWORD` or `OJP_TEST_SQLSERVER_PASSWORD` for fixture
passwords. The server must have the database's JDBC driver installed.
The suites exercise example CRUD and rollback, plus TypeORM queries,
transactions and database-specific types.

## Limitations

- Only PostgreSQL and SQL Server are supported; not every `pg` or `mssql`
  feature is implemented or tested.
- SQL Server's `SNAPSHOT` transaction isolation level is not supported.
- BIGINT result values are returned as strings to preserve precision.
