# @ojp/node-driver

Node.js/TypeScript driver for **OJP (Open J Proxy)**. Connects to `ojp-server` via
gRPC, using the same contract (`StatementService.proto`) as the official JDBC driver.

> Status: **MVP under development**. API subject to change until stable v1.0.0.

## Current Implementation Level Assessment

Level definitions: [`CLIENT_IMPLEMENTATION_LEVELS.md`](../documents/multi-language-client-spec/CLIENT_IMPLEMENTATION_LEVELS.md).

| Assessment | Value |
|---|---|
| Target level | **L9** for DB2; **L10** for MariaDB, MySQL, Oracle, PostgreSQL and SQL Server |
| Cumulative level | **L1** for DB2; **L9** for H2; **L10** for MariaDB, MySQL, PostgreSQL and SQL Server |
| Higher-level features | L10 combines multinode recovery and XA behavior over mTLS |

Levels are cumulative: partial support for a higher level does not make it achieved.
The DB2 L1 suite uses `testdata/db2_connections.csv`; set `OJP_ENABLE_DB2_TESTS=true`
and run `npm run test:db2-l1` against an OJP server with the DB2 JDBC driver and a DB2
database. `OJP_TEST_DB2_CSV`, `OJP_TEST_DB2_PASSWORD`, and `OJP_TEST_SERVER_ENDPOINT`
override the fixture, password, and OJP endpoint respectively.
The H2, MariaDB, MySQL, PostgreSQL, and SQL Server L1 suites exercise CRUD, errors, and
session lifecycle through a real OJP server and database. H2, MariaDB, and MySQL run the
same four L1 scenarios through their public Node API, matching the JDBC
`BasicCrudIntegrationTest` and connection lifecycle checks. PostgreSQL L2 coverage is in
`test/integration.postgres.l2.test.ts`; PostgreSQL L3 coverage is in
`test/integration.postgres.l3.test.ts`; and PostgreSQL L4 coverage is in
`test/integration.postgres.l4.test.ts`; PostgreSQL L5 coverage is in
`test/integration.postgres.l5.test.ts`; PostgreSQL L6 covers session affinity in
`test/integration.postgres.l6.test.ts`; PostgreSQL L7 covers multinode operations and pool
recovery in `test/integration.postgres.l7.test.ts`; PostgreSQL L8 adds stateless
failover, recovery and redistribution, and validates sticky-session and pool-exhaustion
failure behavior in `test/integration.postgres.l8.test.ts`. L3 exercises streamed and buffered results across
10,001 rows, paged PostgreSQL `RETURNING` results, metadata, exhaustion, independent
cursors, and early cursor close. L4 covers transaction commit/rollback, autocommit
behavior, isolation levels, savepoints, and transaction-scoped cleanup. The stream and
fetch paths carry session state internally. Callers never manually pass session state:
`OjpClient` propagates it for each operation. Callers can manage the client lifetime with
`connect()`/`close()`, or use `withOjpClient()` to scope that lifecycle automatically.
H2 L2 coverage in `test/integration.h2.l2.test.ts` extends L1 with typed numeric, text,
binary and temporal values; typed NULLs; scalar Object, RowId and stream parameters;
prepared/plain statements and batches; generated keys; result metadata; SQL errors; and
H2 `FINAL TABLE` data-changing queries. H2's JDBC driver rejects URL parameters as
unsupported; the suite verifies that error. For H2 table columns, the server returns
base column names rather than SQL aliases; unquoted names are uppercase. The Node API
does not expose JDBC-only statement configuration, parameter metadata, or the full vendor-specific `DatabaseMetaData` and
`ResultSetMetaData` surfaces. PostgreSQL L2 extends L1 with
typed values, prepared/plain statements, batches, generated keys, query metadata, and
direct/prepared `RETURNING` DML.
H2 L3 coverage in `test/integration.h2.l3.test.ts` verifies empty and single-row
results, metadata and EOF, streamed and buffered results across 10,001 ordered rows,
10,001 H2 `FINAL TABLE` rows fetched through `fetchNextRows`, concurrent cursors, and
early cursor close with subsequent session usability. `OjpClient` propagates session
state and closes result-set resources internally; callers do not manage protocol session
tokens, result-set identifiers, or cursor cleanup.
H2 L4 coverage in `test/integration.h2.l4.test.ts` verifies autocommit, durable commit and
rollback, transaction isolation, managed transaction callbacks, named and nested
savepoints, rollback-to-savepoint, release, prepared-batch rollback, duplicate-key
recovery, and savepoint invalidation after rollback or commit. `OjpClient` propagates
session state automatically; `withTransaction()` manages transaction completion without
application-written transaction cleanup.
H2 L5 coverage in `test/integration.lob.h2.test.ts` and
`test/integration.lob.stream.h2.test.ts` verifies BLOB/CLOB upload and binding,
transparent CLOB hydration, exact streamed reads, readable uploads, reusable references,
updates, empty-versus-NULL values, and large Unicode/binary stream-parameter round trips.
`OjpClient` carries session state for LOB uploads, binding, hydration, and reads; callers
never pass session tokens or routing metadata.
H2 L6-L9 coverage is in `test/integration.h2.l6.test.ts` through
`test/integration.h2.l9.test.ts`. L6 verifies temporary-table affinity and that lost
sticky sessions are surfaced without rerouting. L7 covers endpoint selection, round-robin,
health propagation and recovery, `connHash` reuse, and safe stale-pool reconnect. L8
validates stateless failover, non-replay of sticky/transaction work, SQL-error and
pool-exhaustion handling, and recovered-node redistribution. L9 exercises XA one- and
two-phase commit, rollback, recovery, timeout semantics, resource identity, scoped
cleanup, and strict XA affinity/failure behavior.
MariaDB and MySQL L2 coverage is in `test/integration.mariadb.l2.test.ts` and
`test/integration.mysql.l2.test.ts`; the shared suite verifies supported typed values,
including URL, RowId, scalar Object, and character/binary stream parameters, reusable
prepared and plain statements, both batch forms, generated keys, query metadata, empty
results, and explicit unsupported types. MariaDB also verifies direct and prepared
`INSERT ... RETURNING`; MySQL verifies the unsupported syntax is surfaced as a SQL error.
MariaDB and MySQL L3 coverage is in `test/integration.mariadb.l3.test.ts` and
`test/integration.mysql.l3.test.ts`; both suites verify streamed and buffered 10,001-row
results across server blocks, result metadata and EOF, SQL pagination, independent
concurrent cursors, and early cursor close. `OjpClient` handles session propagation,
fetching, and cursor cleanup, so application code does not manage protocol session or
result-set identifiers. MariaDB and MySQL L4 coverage is in
`test/integration.mariadb.l4.test.ts` and `test/integration.mysql.l4.test.ts`; both suites
verify autocommit, durable commit/rollback, isolation get/set, managed transaction
callbacks, savepoint rollback/release/nesting/invalidation, batch rollback, and recovery
from a duplicate-key error. The public `OjpClient` API retains session state internally;
`withTransaction()` manages transaction completion and `withOjpClient()` can also scope
connection creation and cleanup, so application code never passes protocol session tokens.
MariaDB and MySQL L5 coverage is in `test/integration.mariadb.l5.test.ts` and
`test/integration.mysql.l5.test.ts`; both suites verify binary and character LOB uploads,
multi-chunk streams, parameter binding, streamed reads, hydrated query results, Unicode,
empty-versus-NULL values, updates, reference reuse, and closed-session behavior. `OjpClient`
propagates session state for upload/read RPCs and resolves returned CLOB references during
query hydration. Callers explicitly start a LOB upload with `createLob()` and bind its
returned reference; they never pass session or endpoint-routing metadata.
MariaDB and MySQL L6 affinity scenarios are in `test/integration.mariadb.l6.test.ts` and
`test/integration.mysql.l6.test.ts`. They cover temporary tables, session variables,
transaction boundaries, client close, and bound-node failure.
MariaDB and MySQL L7 multinode scenarios are in `test/integration.mariadb.l7.test.ts`
and `test/integration.mysql.l7.test.ts`. They exercise load balancing, node health and
recovery, pool-identity reuse and stale-pool reconnect, sticky-node failure, and SQL
error classification through the public client.
MariaDB and MySQL L8 suites add bounded stateless failover, outage transactions and
consistent reads, recovery and redistribution, batch and continuous operations, and
sticky-session, SQL-error and server-overload safety.
Their L9 suites exercise XA lifecycle, SQL outcomes, prepared-branch recovery, resource
identity, timeout behavior, errors, and strict multinode session affinity.
Their L10 suites combine SQL, streaming and LOBs, local and XA transactions, recovery
and failover over mTLS, and reject missing and untrusted client certificates.
SQL Server L1 passed in the
[Node client integration workflow](https://github.com/Open-J-Proxy/ojp/actions/runs/43517201512);
the SQL Server L2 suite passed in [run 43528198577](https://github.com/Open-J-Proxy/ojp/actions/runs/43528198577)
with all 53 integration tests passing. The SQL Server L3 suite covers streaming results,
pagination, metadata, EOF, concurrent cursors, and early close. The H2 suite also covers
datasource selection using `testdata/h2_connections.csv`.

| Level | Status | Notes |
|---|---|---|
| **L1** | DB2, H2, MariaDB, MySQL, PostgreSQL, SQL Server | `connect`/`close`, parameterized CRUD, SQL errors and empty results. DB2, H2, MariaDB, and MySQL also verify closed clients are unusable, scoped calls create distinct clients and hide session propagation, and callback failures still close the session. See `test/integration.db2.l1.test.ts`, `test/integration.h2.l1.test.ts`, `test/integration.mariadb.l1.test.ts`, `test/integration.mysql.l1.test.ts`, `test/integration.postgres.l1.test.ts`, and `test/integration.sqlServerAg.test.ts`. |
| **L2** | H2, MariaDB, MySQL, PostgreSQL, SQL Server | H2 covers typed numeric, text, binary, temporal, NULL, scalar Object, RowId, and stream parameters; prepared/plain execution and batches; generated keys; result metadata; SQL errors; and direct/prepared `FINAL TABLE` queries in `test/integration.h2.l2.test.ts`. H2 rejects URL parameters. MariaDB and MySQL cover the same broad parameter/statement surface and URL parameters; MariaDB covers `INSERT ... RETURNING`, while MySQL asserts unsupported syntax is reported as a SQL error. PostgreSQL covers reusable prepared bindings, batches, generated keys, column-label metadata, and `RETURNING` DML; SQL Server adds the equivalent `OUTPUT` result-set path. `ARRAY` and `REF` are explicit, tested API omissions. |
| **L3** | H2, MariaDB, MySQL, PostgreSQL, SQL Server | H2 covers streamed and buffered 10,001-row results, metadata, EOF, paged `FINAL TABLE` results, concurrent cursors, and early cursor close in `test/integration.h2.l3.test.ts`. MariaDB and MySQL cover streamed and buffered 10,001-row results across server blocks, metadata, EOF, SQL pagination, concurrent cursors, and early cursor close in `test/integration.mariadb.l3.test.ts` and `test/integration.mysql.l3.test.ts`. PostgreSQL additionally covers paged `RETURNING` results; SQL Server covers streaming, pagination, metadata, EOF, concurrent cursors, and early close. |
| **L4** | H2, MariaDB, MySQL, PostgreSQL, SQL Server (certified) | H2, MariaDB, and MySQL cover durable commit/rollback, autocommit behavior, isolation-level get/set, managed transaction callbacks, named/unnamed and nested savepoints, rollback-to-savepoint, release, prepared-batch rollback, duplicate-key recovery, and transaction completion invalidation in their database-specific L4 suites. PostgreSQL covers the same transaction/savepoint semantics; SQL Server additionally covers batch rollback and error recovery. |
| **L5** | H2, MariaDB, MySQL, PostgreSQL, SQL Server | H2 covers BLOB/CLOB LOB-RPC round trips and exact streamed reads, reference reuse, empty versus NULL, and large hydrated binary/Unicode stream parameters in `test/integration.lob.h2.test.ts` and `test/integration.lob.stream.h2.test.ts`. H2's JDBC driver only permits one write to a free-standing Blob/Clob, so `createLob()` uploads on H2 are limited to one 64KB protocol block; the larger values are verified through the supported typed stream-parameter path. MariaDB and MySQL cover BLOB/CLOB stream upload, Unicode, parameter binding, streamed reads, hydrated query results, empty versus NULL values, multi-chunk content, updates, and reusable references in `test/integration.mariadb.l5.test.ts` and `test/integration.mysql.l5.test.ts`. PostgreSQL validates the JDBC-compatible hydrated `BYTEA`/`TEXT` path. SQL Server covers multi-chunk BLOB/CLOB upload and binding, Unicode, empty and NULL values, updates, and reusable references; its JDBC driver rejects ranged BLOB/CLOB reads, which the L5 suite asserts. |
| **L6** | H2, MariaDB, MySQL, PostgreSQL, SQL Server | H2, MariaDB, and MySQL cover temporary-table state, transaction boundaries, close lifecycle, and bound-node failure without rerouting. PostgreSQL verifies temporary-table and `SET LOCAL` state across calls/transactions; SQL Server also covers temporary-table state and sticky-node failure. Session and target-server propagation remain internal to `OjpClient`. |
| **L7** | H2, MariaDB, MySQL, PostgreSQL, SQL Server | The H2, MariaDB, MySQL, and PostgreSQL suites verify least-connections selection, round-robin distribution, unavailable/recovered-node health, cached `connHash` reuse, stale-pool reconnect for stateless operations, and SQL-error classification. `test/multinodeClient.test.ts` also checks cluster-health propagation and retry limits. |
| **L8** | H2, MariaDB, MySQL, PostgreSQL, SQL Server | H2, MariaDB, and MySQL cover failover, recovery and redistribution, sticky-session and active-transaction loss, SQL-error classification, and pool-exhaustion safety. PostgreSQL and SQL Server cover the corresponding bounded resilience behavior in their L8 suites. |
| **L9** | H2, MariaDB, MySQL, PostgreSQL, SQL Server | H2, MariaDB, and MySQL cover XA lifecycle, SQL outcomes, prepared-branch recovery, resource identity, timeout behavior, errors, and strict multinode affinity. PostgreSQL and SQL Server cover XA lifecycle in `test/integration.xa.postgres.test.ts` and `test/integration.sqlServerXA.test.ts`. |
| **L10** | MariaDB, MySQL, PostgreSQL, SQL Server | The database-specific L10 suites authenticate against each mTLS endpoint, verify local and XA commit/rollback/recovery outcomes, stateless failover and redistribution, sticky-session and XA affinity failures, and reject missing or untrusted client certificates. MariaDB and MySQL also exercise LOB and streaming operations and unused XA session cleanup over mTLS. |

Oracle's L1–L10 suites use `testdata/oracle_connections.csv` and exercise CRUD,
typed values and generated keys, cursors, transactions, LOBs, affinity, multinode
recovery, XA and mTLS. The Node API does not expose Oracle `RETURNING INTO` output
binds or vendor-specific ARRAY/REF construction. Oracle 21c has no SQL BOOLEAN
column type, and its JDBC driver does not support releasing savepoints.
The Oracle workflow runs after the H2 integration gate alongside the other Node
database jobs. To run L1–L5 against a local OJP server and Oracle database:

```sh
OJP_ENABLE_ORACLE_TESTS=true npx jest --runInBand 'test/integration\.oracle\.l[1-5]\.test\.ts'
```

The L6–L10 suites additionally require dedicated OJP containers, with two
certificate-authenticated endpoints for L10; see the Oracle workflow for their
environment variables and setup.

PostgreSQL L5 uses the JDBC driver's supported `BYTEA`/`TEXT` hydrated path; PostgreSQL's
JDBC driver does not implement native `createBlob()`/`createClob()`. Binary stream
parameters use `PT_BINARY_STREAM`; text parameters use `PT_STRING`, and query results
are fully hydrated as `Buffer`/`string`. The Node API accepts the current session
internally for every operation. PostgreSQL L6 also keeps `sessionUUID` and `targetServer`
propagation private to `OjpClient`, routes sticky calls to the bound OJP endpoint, and
surfaces a bound-node failure rather than retrying stateful work elsewhere. Run the
PostgreSQL L6 suite with `OJP_ENABLE_INTEGRATION_TESTS=true npm run test:postgres-l6`.
The PostgreSQL L7 suite uses `OJP_ENABLE_POSTGRES_L7_TESTS=true npm run test:postgres-l7`
and requires at least two dedicated OJP node containers in `OJP_TEST_NODE_CONTAINERS`
(ports default to 1059–1061); all nodes must share the same PostgreSQL database. The L6
sticky-node failure scenario uses `OJP_ENABLE_MULTINODE_FAILOVER_TESTS` and the same
container configuration. The MySQL and MariaDB L6 sticky-node cases use the same
multinode flag, dedicated containers, and ports, with all nodes connected to the same
respective database. `OjpClient` manages `SessionInfo`, `sessionUUID`, and `connHash`
for every operation; `withOjpClient()` and `withTransaction()` also avoid manual
connect/close and transaction-cleanup boilerplate. Run the MySQL and MariaDB multinode
suites with `OJP_ENABLE_MYSQL_L7_TESTS=true npm run test:mysql-l7` and
`OJP_ENABLE_MARIADB_L7_TESTS=true npm run test:mariadb-l7`, respectively, using at least
two dedicated containers in `OJP_TEST_NODE_CONTAINERS` and matching ports in
`OJP_TEST_NODE_PORTS`. The tests stop and restart these containers.

The H2 L6-L9 suites use `testdata/h2_multinode_connections.csv`, a shared H2 file database
with `AUTO_SERVER=TRUE`, and two dedicated OJP containers named by
`OJP_TEST_NODE_CONTAINERS` with ports in `OJP_TEST_NODE_PORTS`. L8 also requires a separate
OJP container configured with `maxConcurrentRequests=1`, identified by
`OJP_TEST_H2_L8_THROTTLED_NODE_CONTAINER` and `OJP_TEST_H2_L8_THROTTLED_NODE_PORT`.
Run individual suites with `OJP_ENABLE_H2_L6_TESTS=true npm run test:h2-l6` (and the
corresponding `OJP_ENABLE_H2_L7_TESTS`/`test:h2-l7`, `OJP_ENABLE_H2_L8_TESTS`/`test:h2-l8`,
or `OJP_ENABLE_H2_L9_TESTS`/`test:h2-l9`). The Node integration workflow provisions this
topology and runs all four suites.

The Node integration workflow provisions all four databases and two-node OJP clusters
that require client certificates; it runs the L10 suites alongside L1–L9. H2 is at L9.
To run the MariaDB or MySQL L10 suite, set `OJP_TEST_MTLS_CERT_DIR` to the certificate
directory, `OJP_TEST_MYSQL_MARIADB_L10_MTLS_ENDPOINTS` to at least two distinct
`localhost:port` endpoints, and `OJP_TEST_MYSQL_MARIADB_L10_MTLS_CONTAINERS` to the
corresponding dedicated Docker containers in the same order. The suite stops and restarts
those containers. Run `OJP_ENABLE_MARIADB_L10_TESTS=true npm run test:mariadb-l10` or
`OJP_ENABLE_MYSQL_L10_TESTS=true npm run test:mysql-l10`; database fixture and password
overrides are shared with the L1–L9 suites.

SQL Server's L5 coverage documents that the JDBC driver rejects ranged BLOB/CLOB reads
with “This operation is not supported”; this affects `readLobStream` and transparent
CLOB hydration, not LOB upload/binding or binary query hydration. PostgreSQL's separate
L5 suite validates its JDBC-compatible hydrated `BYTEA`/`TEXT` path. H2 L5 verifies
successful BLOB/CLOB reads and exact byte/character contents, plus large hydrated stream
parameters; its multi-chunk LOB-RPC upload limitation is documented in the L5 matrix.
The Node metadata shape intentionally reports the stream
protocol's column labels; it does not expose JDBC's full vendor-specific `ResultSetMetaData`
surface or JDBC-style `DatabaseMetaData` and `ParameterMetaData` APIs.

MySQL and MariaDB temporary tables can survive session close when a server-side pooled physical
connection is reused. Drop temporary tables and clear session variables explicitly
before closing when this state must not leak to a subsequent client.

Explicit `OjpTypedParam` mappings cover scalar, decimal, date/time, text/binary stream
payloads, URL, row ID, and LOB-reference values. Use the exported `ParameterType` enum
(for example, `ParameterType.N_STRING`) for explicit type hints. The `OjpTypedParam` type
requires an enum member rather than a raw string. `ARRAY` and
`REF` are not currently representable through this API, and `OBJECT` is limited to scalar
values. These declared omissions fail explicitly rather than silently falling back to another
parameter type.

## Why this driver exists

OJP is made up of `ojp-server` (which owns the real connection pools — pluggable via SPI on
the server side) and client drivers that make gRPC calls instead of opening direct
connections to the database. This package is the Node.js equivalent of `ojp-jdbc-driver`
in Java — but since there's no "JDBC" standard in the Node.js ecosystem, it exposes its
own API (`pg`-style) that could eventually power adapters for TypeORM/Knex (for use in
NestJS).

## Architecture

```
[Node.js App] --> [@ojp/node-driver] --gRPC/HTTP2--> [ojp-server] --JDBC--> [Database]
```

- There is no connection pool on the Node client side — the real pool lives in `ojp-server`.
- Each `OjpClient` represents a logical session (equivalent to a `java.sql.Connection`).
- `OjpClient` owns and refreshes the protocol `SessionInfo`; callers never pass session
  state to individual operations. Use `withOjpClient(url, work, options)` to have the
  client connect before `work` and terminate its session in `finally`. Each call creates
  a fresh client; this helper does not pool or reuse virtual connections.

```ts
import { readFileSync } from 'node:fs';

const result = await withOjpClient(ojpUrl, async (db) => {
  return db.executeQuery('SELECT name FROM customers WHERE id = ?', [customerId]);
}, { user, password });
```

For TLS or mutual TLS, pass PEM-encoded buffers in `tls`; the client configures gRPC
credentials and continues to manage endpoint routing and session state internally:

```ts
const result = await withOjpClient(ojpUrl, async (db) => {
  return db.executeQuery('SELECT name FROM customers WHERE id = ?', [customerId]);
}, {
  user,
  password,
  tls: {
    rootCert: readFileSync('server-cert.pem'),
    privateKey: readFileSync('client-key.pem'),
    certChain: readFileSync('client-cert.pem'),
  },
});
```

`privateKey` and `certChain` must be supplied together. `tls` enables TLS; `rootCert`
configures server verification, while omitting both client-identity fields uses regular
server-authenticated TLS.

Server-side connection properties can be supplied as strings, for example:

```ts
const client = new OjpClient(url, {
  user,
  password,
  connectionProperties: {
    'ojp.connection.pool.maximumPoolSize': '20',
    'ojp.connection.pool.minimumIdle': '2',
  },
});
```

These settings configure the server's pool, not a Node connection pool. A server
identifies an existing pool by database URL, credentials, and datasource name;
use the datasource name in the OJP URL to isolate differently configured pools.

Pool identity caching is separated by transport mode, trust roots, and client identity,
so a client using different TLS credentials does not inherit another client's cached
connection. Session tokens and endpoint routing remain internal; applications still
explicitly upload LOBs with `createLob()` and supply the resulting reference when binding
them. `withTransaction()` automates local commit/rollback, and `withXAConnection()`
automates XA connection cleanup. Because Node.js has no standard XA API, the transaction
manager still creates the Xid and explicitly drives the XA branch lifecycle; it never
needs to propagate OJP session tokens or routing metadata.

For large LOB parameters, a future convenience wrapper could upload the supplied stream
and bind its reference in one operation. Explicit `OjpTypedParam`/`OjpNullParam` hints
are still needed when JavaScript values do not identify the intended SQL type; a
schema-aware adapter could supply those hints on behalf of an application.

### Multinode routing

When the OJP URL contains multiple endpoints, the client chooses the healthy endpoint
with the fewest active logical sessions; round-robin breaks ties. It probes endpoint
reachability before subsequent connections (5 seconds by default), propagates the
cluster-health view when creating or refreshing server pools, and caches the pool
`connHash` internally. A lost pool is reconnected and retried once only when the session
has not become sticky; transactions and sessions with server affinity are never rerouted.
These behaviors are automatic and do not require callers to pass `SessionInfo`,
`sessionUUID`, or `connHash`. For short operations, `withOjpClient()` also automates
connect/close and always creates a fresh client; long-lived work can use `OjpClient`
directly with an explicit `connect()`/`close()` scope.

The policy can be tuned per client with `loadBalancingStrategy` (`'least-connections'`
or `'round-robin'`), `healthCheckIntervalMs`, `maxFailoverAttempts` (default `3`), and
`failoverRetryDelayMs` (default `100`). Stateless queries and updates are retried after
transport failures; partially streamed results, server-bound sessions, active transactions,
SQL errors, and pool exhaustion are never replayed. The Node API intentionally remains a direct
gRPC-contract client rather than emulating JDBC; use `withOjpClient()` and
`withTransaction()` to avoid connect/close and transaction-cleanup boilerplate.
An update may have reached the database before a transport failure, so an automatic
retry cannot guarantee exactly-once execution. Use transactions or idempotent writes
when duplicate effects would be unsafe.

The client also shares a fail-fast admission limit across clients for the same pool.
`clientThrottleMode` selects `reactive` (default), `proactive`, `combined`, or `off`.
Active transactions bypass admission checks; callers do not manage permits.
- The `.proto` contract is consumed from [`@ojp/grpc-contract`](../ojp-grpc-contract), a
  versioned, hash-pinned package distributing the same contract used by `ojp-jdbc-driver`
  and `ojp-server` (see that package's README for its versioning policy and how it's kept
  in sync with the centralized contract).
- `src/proto/types.ts` is a **hand-written** mirror of the `.proto` messages (more ergonomic
  than a generic generator's output — e.g. `int64` as `string`, `oneof` members as plain
  optional properties, a custom `TimestampWithZone` shape). `scripts/verify-proto-types.js`
  guards it against silent drift: it walks every message/enum reachable from the RPCs this
  driver implements and fails (`npm run verify-types`, wired into `prebuild`/`pretest`) if
  message fields or enum values differ in either direction. It does not generate code.

## Connection string format

```
jdbc:ojp[host1:port1,host2:port2(dataSourceName)]_backendJdbcUrl
```

Examples:

```
jdbc:ojp[localhost:1059]_postgresql://localhost:5432/mydb
jdbc:ojp[localhost:1059(mainApp)]_postgresql://localhost:5432/mydb
jdbc:ojp[host1:1059,host2:1059]_mysql://localhost:3306/mydb
```

## Basic usage

```ts
import { OjpClient } from '@ojp/node-driver';

const client = new OjpClient('jdbc:ojp[localhost:1059]_h2:mem:testdb', {
  user: 'sa',
  password: '',
});

await client.connect();

await client.executeUpdate('CREATE TABLE IF NOT EXISTS items(id INT PRIMARY KEY, label VARCHAR(100))');
await client.executeUpdate('INSERT INTO items(id, label) VALUES (?, ?)', [1, 'example']);

const { columns, rows } = await client.executeQuery('SELECT id, label FROM items ORDER BY id');
console.log(columns, rows);

await client.startTransaction();
await client.executeUpdate('UPDATE items SET label = ? WHERE id = ?', ['updated', 1]);
await client.commit();

await client.close();
```

### Statements, prepared parameters, batches, and generated keys

The statement wrappers reuse the owning `OjpClient`; callers do not pass or update
`SessionInfo` themselves. Prepared parameter indexes are 1-based, and values can be
rebound between executions:

```ts
const statement = client.prepareStatement(
  'UPDATE inventory SET label = ? OUTPUT inserted.id WHERE id = ?',
);
statement.setParameter(1, 'updated');
statement.setParameter(2, 42);
const returnedRows = await statement.executeQuery();
await statement.close();

const insert = client.prepareStatement(
  'INSERT INTO inventory (label) VALUES (?)',
  { generatedKeys: true },
);
await insert.executeUpdate(['new item']);
const generatedKeys = await insert.getGeneratedKeys();
await insert.close();
```

For databases such as Oracle that require named generated-key columns, use
`{ generatedKeys: ['ID'] }` instead of `{ generatedKeys: true }`.

For prepared batches, call `addBatch(values)` for each parameter set and then
`executeBatch()`. Close prepared wrappers when finished; closing the owning client also
terminates the OJP session and releases its server-side resources.

## Real pagination for large result sets (`executeQueryStream`)

`executeQuery()` materializes the entire result set in memory before returning — great
for small queries, but risky for large tables. `executeQueryStream()` consumes the
`ojp-server`'s gRPC stream on demand, delivering one row at a time without ever
accumulating the whole result set:

```ts
for await (const row of client.executeQueryStream('SELECT * FROM huge_table')) {
  console.log(row.id, row.label);
  // can process (e.g. write elsewhere, HTTP streaming) without
  // waiting for the entire result set to arrive
}
```

Important points:

- The server sends the result set in blocks (`ojp.resultset.rowsPerBlock`, default 100
  rows/block); `executeQueryStream` consumes one block at a time as the consumer
  advances through the iteration — memory usage proportional to one block, not the
  entire result set.
- Interrupting the iteration early (`break`/`return` in a `for await`) automatically
  cancels the underlying gRPC call and closes the server-side result set through
  `callResource(RES_RESULT_SET, CALL_CLOSE)`. Normal exhaustion also closes the result
  set; the connection remains usable afterward.
- `executeQuery()` (buffered) is internally implemented on top of the same streaming
  logic — both share the same code path and error semantics.
- The client propagates the current `SessionInfo` on every stream/fetch/close RPC;
  callers do not manage session or result-set UUIDs. For short-lived work, wrapping
  iteration in `withOjpClient()` also handles connect and session termination.
- SQL Server and DB2 use a special "row-by-row" mode when the result set contains
  LOBs (advancing the cursor ahead would invalidate the LOBs): the server signals this
  via `OpResult.flag`, and the driver then fetches each additional row with a unary
  `fetchNextRows` call, transparently to whoever consumes `executeQueryStream`/`executeQuery`.
  This path faithfully follows the protocol and reference JDBC driver's contract, and has
  been **validated against a real SQL Server Always On AG cluster** (see the dedicated
  section below); DB2 support follows the same code path but remains untested (no DB2
  environment available in this session).

## Transaction semantics (important)

`startTransaction()` turns off autocommit on the real physical connection on
`ojp-server` (equivalent to `java.sql.Connection.setAutoCommit(false)`). Unlike plain
JDBC, this driver does **not** require the application to separately call an
equivalent of `setAutoCommit(true)` afterward: `commit()` and `rollback()` both
restore autocommit on the physical connection themselves before returning (via the
generic `callResource` RPC, the same mechanism the JDBC driver uses for
`setAutoCommit(true)`), so every `startTransaction()` / `commit()`/`rollback()` pair is
a **self-contained unit of work**.

This was fixed after discovering that plain statements issued right after a committed
or rolled-back transaction were silently swallowed on connection close, because the
physical connection was left stuck in manual-commit mode with no client-facing signal
to leave it. Regression coverage: `test/integration.ojpServer.test.ts` (autocommit
restored after both `commit()` and `rollback()`, verified via a fresh reconnect).

`getAutoCommit()` reports this client's current transaction mode. `withTransaction()`
wraps a unit of work in start/commit/rollback, so callers do not need to write transaction
cleanup boilerplate. Combine it with `withOjpClient()` to also manage connect/close:

```ts
const orderId = await withOjpClient(
  ojpUrl,
  (db) => db.withTransaction(async (transaction) => {
    await transaction.executeUpdate('INSERT INTO orders (id) VALUES (?)', [id]);
    return id;
  }),
  { user, password },
);
```

For savepoints, `setSavepoint(name?)` returns an opaque `OjpSavepoint` handle; pass that
handle to `rollback(savepoint)` or `releaseSavepoint(savepoint)`. The client retains the
savepoint UUID and session internally, rejects handles from another connection or
completed transaction, and invalidates later savepoints when rolling back to an earlier
one. Transaction isolation is accessed with `getTransactionIsolation()` and
`setTransactionIsolation(level)`, using the exported `TransactionIsolation` constants.
SQL Server does not support releasing savepoints through every JDBC driver; an unsupported
release is reported by the server and is not hidden. If an operation and its rollback both
fail inside `withTransaction()`, `OjpTransactionError` exposes both underlying errors.

Recommendations:

- Open a fresh `OjpClient` for each independent operation or explicit transaction,
  and close it promptly. Do not pool client-side virtual connections.
- If an error occurs during a transaction, always call `rollback()` before continuing
  to use the same connection — statements issued after a failed statement, but before
  `rollback()`, will still fail (Postgres aborts the whole transaction until rolled
  back), exactly as with plain JDBC.

## DECIMAL/NUMERIC/MONEY types (`java.math.BigDecimal`)

The result set streaming protocol (`executeQuery`) doesn't carry each column's SQL
type — only the raw value (`ParameterValue`). `DECIMAL`/`NUMERIC`/`MONEY` columns
arrive as `bytes_value`, encoded by `ojp-server` in a compact binary format
(`BigDecimalWire`: presence + length + digits + scale). The driver automatically
decodes this format and returns the value as a **decimal string** (e.g. `"1234.56"`),
the same convention adopted by the `pg` driver for `NUMERIC` — avoiding the precision
loss that would occur when converting to `number` (JS uses a 64-bit double). If the
buffer doesn't match the expected layout, the raw value (`Buffer`) is returned
unchanged.

## SQL Server integration

The real-server suites `test/integration.sqlServerAg.test.ts`,
`test/integration.sqlServerL3.test.ts`, `test/integration.sqlServerL4.test.ts`,
`test/integration.sqlServerL5.test.ts`, `test/integration.sqlServerL6.test.ts`,
`test/integration.sqlServerL7.test.ts`, `test/integration.sqlServerL8.test.ts`,
and `test/integration.sqlServerXA.test.ts`
cover SQL Server:

- L1 connectivity, CRUD, empty results, SQL errors, scoped session cleanup, and
  rejection of operations after close.
- CRUD with native SQL Server types (`NVARCHAR`, `DECIMAL`, `BIT`, `DATETIME2`).
- Transactions (`commit`/`rollback`) and `MONEY` columns.
- **"Row-by-row" mode (`fetchNextRows`)**: validated for the first time against a
  real database — `VARBINARY(MAX)` columns force SQL Server into this mode
  (signaled via `OpResult.flag == RESULT_SET_ROW_BY_ROW_MODE`), and the driver
  correctly consumes both a single isolated row and multiple rows via
  `executeQueryStream`.
- Real pagination (multiple blocks of 100+ rows) via `executeQueryStream`.
- L3 result-set behavior: empty, single-row, and multi-block results; metadata and EOF;
  SQL Server `OFFSET`/`FETCH` pagination; independent concurrent cursors; and early
  close followed by continued client use.
- L4 transaction behavior: default autocommit, commit/rollback durability, isolation
  get/set, savepoint rollback and release, nested savepoints, batch rollback, recovery
  after a SQL error, and rejection of savepoints after transaction completion.
- L5 LOB behavior: >64KB BLOB/CLOB uploads, Unicode, update, NULL versus empty values,
  reusable references, and Node `Readable` input. SQL Server ranged LOB reads are
  asserted as unsupported; successful exact-size BLOB/CLOB stream reads are covered on H2.
- L6 session affinity: local temporary-table operations across calls and transaction
  boundaries, plus sticky-session failure when a bound OJP node is stopped in the
  multinode integration cluster.
- L7/L8 multinode operations and recovery: health and pool propagation, safe stateless
  `NOT_FOUND` recovery, bounded transport-failure retry, sticky-session and transaction
  fail-fast behavior, SQL-error classification, and recovered-node redistribution.
- L9 XA behavior: XA connection lifecycle, two-phase and one-phase commit, rollback,
  timeout configuration, prepared-branch recovery, resource-manager identity, XA error
  mapping, and rejection of local transaction methods on XA sessions.

Run locally with an OJP server and SQL Server. Set `OJP_TEST_SQLSERVER_CSV` to a
connection fixture if the default does not match your database, and provide
`OJP_TEST_SQLSERVER_PASSWORD`:

```powershell
$env:OJP_ENABLE_SQLSERVER_AG_TESTS = 'true'
$env:OJP_ENABLE_SQLSERVER_L4_TESTS = 'true'
$env:OJP_ENABLE_SQLSERVER_L5_TESTS = 'true'
$env:OJP_ENABLE_SQLSERVER_L6_TESTS = 'true'
$env:OJP_ENABLE_SQLSERVER_L7_TESTS = 'true'
$env:OJP_ENABLE_SQLSERVER_L8_TESTS = 'true'
$env:OJP_ENABLE_SQLSERVER_XA_TESTS = 'true'
npm run test:sqlserver-ag
npm run test:sqlserver-l4
npm run test:sqlserver-l5
npm run test:sqlserver-l6
npm run test:sqlserver-l7
npm run test:sqlserver-l8
npm run test:sqlserver-xa
```

The L6-L9 multinode cases require `OJP_TEST_NODE_CONTAINERS` to name at least two
dedicated OJP containers; the L8 and L9 suites stop and restart a bound node. Set
`OJP_TEST_NODE_PORTS` to match their ports (default: `1059,1060,1061`) and optionally
set `OJP_TEST_SQLSERVER_XA_ADDRS` to the corresponding comma-separated endpoint list.
The Node client integration workflow configures these containers, runs PostgreSQL L7/L8,
and provisions a separate OJP node capped at one concurrent request for the L8 overload
assertion.

**Known observations/limitations:**

- Multinode failover/round-robin is implemented (see the dedicated section below). Note that
  without a stable *listener* in front of the AG, a **database-level** failover behind
  a fixed hostname (i.e. the SQL Server primary itself changing) is not detected by
  OJP — this driver's failover only reacts to an `ojp-server` node becoming
  unreachable, not to the backend database switching replicas (same limitation
  already documented in the reference Java driver/POC).
- The `BigDecimalWire` decoding heuristic (above) has an inherent theoretical
  ambiguity in the protocol: a genuine `VARBINARY`/`BLOB` value whose bytes happened
  to exactly match the wire format layout would be misinterpreted as a decimal. In
  practice this is extremely unlikely (the same risk exists in the reference JDBC
  driver), but it's worth keeping in mind when dealing with binary columns.

## LOB support (`createLob` / transparent BLOB/CLOB reading)

Large `BLOB`/`CLOB` columns don't comfortably fit in a single `ParameterValue` — the
protocol treats LOBs as a separate resource, referenced by a `LobReference`
(UUID) instead of the entire value embedded in the row:

- **Reading**: native `CLOB` columns are transparently dereferenced to JavaScript
  strings, and binary columns arrive as `Buffer` values. For explicit streaming reads
  from an `OjpLobRef`, use `readLobStream(ref)`; it returns a Node `Readable` of bytes
  (use `setEncoding('utf8')` for CLOB text).
- **Writing** (`createLob(data, lobKind)`): accepts a `Buffer`, string, or async
  iterable of `Buffer`/string chunks, including a Node `Readable`. It streams data to
  the server in blocks of at most 64KB and returns an `OjpLobRef` for `INSERT`/`UPDATE`.
  The client manages `SessionInfo` internally for upload, binding, transparent CLOB
  hydration, and `readLobStream`; application code never passes session tokens or
  protocol session objects.

For multi-chunk CLOB uploads, the client advances positions in UTF-16 units. MariaDB
Connector/J uses code-point positions, so supplementary characters (such as 🚀)
before a later chunk can cause incorrect writes. Supplying smaller input chunks does
not avoid this: the client combines them into blocks of up to 64 KB. Arrange such
characters in the final block to avoid this position mismatch. Even then, JDBC binding
may corrupt a supplementary character split across reader chunks. Arbitrary
supplementary Unicode CLOB round trips are not guaranteed with these drivers.

```ts
import { createReadStream } from 'fs';

const ref = await client.createLob(Buffer.from('large content...'), 'BLOB');
await client.executeUpdate('INSERT INTO files (id, content) VALUES (?, ?)', [1, ref]);

const fileRef = await client.createLob(createReadStream('archive.bin'), 'BLOB');
await client.executeUpdate('INSERT INTO files (id, content) VALUES (?, ?)', [2, fileRef]);
```

`OjpLobRef` is the explicit resource handle for a separate LOB upload; reusing one
reference for multiple parameter bindings is supported. For ordinary values that fit
the statement protocol, pass a `Buffer` or string directly as a query parameter and
avoid the extra LOB upload step.

**Important protocol detail (multi-chunk upload):** the first chunk is sent with the
connection's "normal" session; the first acknowledgement (`LobReference`) returned by
the server already carries a "promoted" (stateful) session, tied to the same physical
connection while the LOB is being written. Every subsequent chunk **must** use this
promoted session, not the original one — otherwise the server tries to (re)open the
LOB on a different connection and the upload fails. The driver already handles this
internally; it's documented here only because it was the cause of a real bug fixed
during development (multi-chunk uploads were silently failing).

### Backend compatibility

| Backend | `createLob` (1 chunk, ≤64KB) | `createLob` (multi-chunk) | Notes |
|---|---|---|---|
| **SQL Server** (`mssql-jdbc`) | ✅ | ✅ | Validated end-to-end (write + bind + read, byte by byte) against a real AG cluster. |
| **H2** | ✅ | ❌ | H2's free-standing `Blob`/`Clob` (`Connection.createBlob()/createClob()`) only accept **a single** write call (`setBytes`/`setCharacterStream`); a second call at another chunk throws `SQLFeatureNotSupportedException` ("Allocate a new object to set its value.", SQLState `HYC00`). Limitation of H2's JDBC driver, not of OJP or of this driver. |
| **PostgreSQL** (`pgjdbc`) | ❌ | ❌ | `PgConnection.createBlob()`/`createClob()` immediately throw `SQLFeatureNotSupportedException` ("not yet implemented") — feature not implemented in `pgjdbc`. Note: this message is localized based on the server JVM's default locale (e.g. Portuguese on a pt-BR JVM), so don't assert on the English wording specifically — the method name (`createBlob`) is the only locale-invariant part. **Warning**: if `createLob` fails like this mid-session, that session's physical connection becomes unusable for subsequent calls — always use a dedicated connection to test/probe for LOB support, never a long-lived shared connection. |
| **MySQL / MariaDB** | ✅ | ✅ | L5 integration suites validate single- and multi-chunk BLOB/CLOB upload, binding, hydrated query results, and streamed reads. |
| **Oracle** | Oracle L5 suite | Oracle L5 suite | Exercises BLOB/CLOB upload and binding, including multi-chunk values and streamed reads. |
| DB2 | untested | untested | LOB behavior is not covered by the DB2 L1 suite. |

## Multinode failover / round-robin between endpoints

When the connection string lists more than one `ojp-server` endpoint
(`jdbc:ojp[host1:port1,host2:port2]_...`), `connect()` tries each candidate — ordered
by a shared, process-wide round-robin cursor, healthy endpoints first — until one
accepts the connection. This mirrors the reference JDBC driver's
`MultinodeConnectionManager`:

- **Only connection-level failures trigger failover** (unreachable server, dial
  timeout, gRPC transport error). A database/SQL-level failure (bad credentials,
  invalid backend JDBC URL, syntax error) fails fast on the very first candidate,
  without trying the others — it would fail identically everywhere, so cycling
  through the remaining nodes would just waste time and hide the real problem.
- **Endpoint health is tracked process-wide**, not per `OjpClient`: once an endpoint
  fails a connection attempt, every `OjpClient` created afterward in the same process
  deprioritizes it (still tries it as a last resort, in case the health information is
  stale) for `DEFAULT_UNHEALTHY_RETRY_DELAY_MS` (30s by default). There's no active
  background health-check ping — the next real connection attempt against a
  deprioritized endpoint *is* the health check.
- **Session stickiness is strict**: once `connect()` succeeds, that session stays
  bound to that single endpoint for its entire lifetime (`client.boundEndpoint`
  exposes which one). If that endpoint goes down *after* the session is established,
  the driver does **not** transparently move the session to another node — every
  further call on that `OjpClient` fails with a clear `OjpConnectionError` explaining
  the session is no longer usable. In particular, **an in-flight transaction is never
  silently recovered** on another node: doing so would risk breaking atomicity/
  consistency, so the driver prefers a clear failure over a subtly incorrect recovery.
  Create a new `OjpClient` (new session) to retry after such a failure.

```ts
const client = new OjpClient(
  'jdbc:ojp[host1:1059,host2:1059,host3:1059]_postgresql://db:5432/mydb',
  { user: 'app', password: '***' },
);
await client.connect();
console.log(client.boundEndpoint); // { host: 'host2', port: 1059 } (whichever accepted first)
```

The suite `test/integration.multinode.test.ts` uses three dedicated OJP nodes
and SQL Server, with real `docker kill`/
`docker start` orchestration to simulate a node going down):

- `connect()` succeeds by failing over to a healthy endpoint when one node is down.
- A node marked unhealthy after a failed attempt is also avoided by later `connect()` calls.
- An established session's operations fail with a clear, non-recovered error when its
  bound node goes down mid-session.
- Repeated `connect()` calls round-robin across all healthy endpoints.

Run locally with three dedicated OJP containers and a Docker CLI. Set
`OJP_TEST_NODE_CONTAINERS` to their comma-separated names and, if not using ports
1059–1061, set `OJP_TEST_NODE_PORTS`. These containers will be stopped and started by
the test. Do not point it at shared or production servers.

```powershell
$env:OJP_ENABLE_MULTINODE_FAILOVER_TESTS = 'true'
$env:OJP_TEST_NODE_CONTAINERS = 'node-test-1,node-test-2,node-test-3'
npm run test:multinode-failover
```

When the server reports `NOT_FOUND` because a pool is missing, stateless operations
rebuild that endpoint's pool and retry once. The retry is restricted to operations with
no sticky session, statement resource, or active transaction; unrelated `NOT_FOUND`
errors are surfaced without retry. XA sessions do not use the non-XA pool cache.

## XA support (distributed transactions)

`OjpXADataSource` / `OjpXAConnection` / `OjpXAResource` expose the ojp-server's
dedicated XA RPCs (`xaStart`/`xaEnd`/`xaPrepare`/`xaCommit`/`xaRollback`/`xaRecover`/
`xaForget`/`xa*TransactionTimeout`/`xaIsSameRM`) behind an API mirroring
`javax.transaction.xa.XAResource`, for use by a Node.js distributed transaction
manager (a hand-rolled 2PC coordinator, or a library that speaks this shape).

```ts
import { OjpXADataSource, xaConstants } from '@ojp/node-driver';

const xaDs = new OjpXADataSource(
  'jdbc:ojp[localhost:1065]_postgresql://localhost:5442/ojptest',
  { user: 'ojptest', password: process.env.OJP_TEST_PASSWORD },
);

const xid = { formatId: 1, globalTransactionId: myGtrid, branchQualifier: myBqual };

await xaDs.withXAConnection(async (xaConn) => {
  const xaRes = xaConn.getXAResource();
  const conn = xaConn.getConnection(); // ordinary OjpClient, for executing SQL

  await xaRes.start(xid, xaConstants.TMNOFLAGS);
  await conn.executeUpdate('INSERT INTO orders (id, total) VALUES (?, ?)', [1, 99.9]);
  await xaRes.end(xid, xaConstants.TMSUCCESS);

  const prepareResult = await xaRes.prepare(xid);
  if (prepareResult !== xaConstants.XA_RDONLY) {
    await xaRes.commit(xid, false); // two-phase commit
  }
});
```

- `OjpClient` owns and refreshes the protocol `SessionInfo` for every SQL and XA RPC;
  applications never pass session tokens or endpoint bindings. `withXAConnection()`
  also guarantees session termination on success or failure. The transaction manager
  still supplies Xids and drives the XA start/end/prepare/commit/rollback decisions,
  which are inherent to coordinating a distributed transaction; the driver automates
  session propagation, endpoint affinity, and connection cleanup.
- **Xids are never generated by this driver** — they must come from your transaction
  manager (`formatId` + `globalTransactionId` + `branchQualifier`, exactly like
  `javax.transaction.xa.Xid`). See `src/xa/xid.ts` (`Xid`, `xidEquals`, `xidToString`).
- **Remote XA errors** are `OjpXaError` instances (`message` + `errorCode`, mirroring
  `javax.transaction.xa.XAException`). Local lifecycle and affinity errors can instead
  be `OjpConnectionError`:
  - `errorCode === 0` — business-level failure reported by the server
    (`XaResponse.success === false`), matching the reference JDBC driver's
    `new XAException(message)` (uninitialized/default error code).
  - `XAER_RMFAIL` (`-7`) — the resource manager is unavailable (connection-level
    transport failure) on any XA operation.
  - `XAER_RMERR` (`-3`) — any other resource-manager-side error, including every other
    non-connection-level operation failure and unexpected resource-manager exceptions (e.g. the
    PostgreSQL driver's `PGXAException` for an unknown/expired xid, observed during
    integration testing — see below).
  - All standard JTA/XA flag and error-code constants (`TMNOFLAGS`, `TMJOIN`,
    `TMONEPHASE`, `XA_RDONLY`, `XAER_*`, etc.) are exported as `xaConstants` — exact
    values confirmed against a local JDK 25 install (`javap -constants
    javax.transaction.xa.XAResource/XAException`), not guessed.
- **An XA session cannot use the ordinary transaction methods**: `startTransaction()`/
  `commit()`/`rollback()` throw a clear error on a client created with `{ xa: true }`
  (`OjpXADataSource` always sets this) — transaction demarcation must go through
  `OjpXAResource`, mirroring the reference JDBC driver's `OjpXALogicalConnection`.
- **No separate "logical connection" layer**: unlike the reference JDBC driver,
  `xaConn.getConnection()` returns the very same `OjpClient` used internally by
  `xaConn.getXAResource()`. Call `xaConn.close()` (not `conn.close()`) to end the
  session cleanly.

### PostgreSQL requires `max_prepared_transactions > 0`

Discovered empirically while validating this feature: PostgreSQL disables two-phase
commit entirely by default (`max_prepared_transactions = 0`), so `xaRes.prepare()`
fails with `PGXAException` until the server is configured with e.g.:

```sql
ALTER SYSTEM SET max_prepared_transactions = 10;
-- requires a PostgreSQL restart (postmaster-level parameter, not reloadable)
```

This is a PostgreSQL server requirement, not an ojp-server/driver limitation — every
XA-capable PostgreSQL deployment needs it (see `postgresql.conf`'s
`max_prepared_transactions` docs).

### XA session and recovery model

The Node client keeps the current `SessionInfo` and its `targetServer` binding private.
All XA operations, including `start()`, stay pinned to their session and are not replayed
after a transport failure; the outcome can be ambiguous. The caller receives
`XAER_RMFAIL` and must resolve any uncertain branch through its transaction manager.

The gRPC contract requires a valid XA session on each RPC. A new XA connection can
scan prepared Xids without application-managed session tokens. Resolution depends on
the OJP server's XA registry; in pooled mode, restarting the server or changing the
endpoint list can discard its in-memory branch context even when the database still
reports the Xid. Keep the same endpoint configuration while XA work is active and
use a transaction manager to handle in-doubt branches.
The server's `isSameRM` RPC can compare two sessions only when both belong to the same
OJP node; it cannot look up a session held by another node.
The pooled XA server also retains backend connections for closed sessions that never
start a branch; repeated scoped connections can exhaust its pool.

Validated end-to-end with real two-phase commit, rollback, one-phase commit
optimization, `recover()`, `isSameRM()`, timeout operations, error mapping, and
multinode XA affinity/failure against MariaDB (`test/integration.mariadb.l9.test.ts`),
MySQL (`test/integration.mysql.l9.test.ts`), PostgreSQL
(`test/integration.xa.postgres.test.ts`) and SQL Server
(`test/integration.sqlServerXA.test.ts`), each using real OJP servers.
Run locally:

```powershell
$env:OJP_ENABLE_XA_TESTS = 'true'
$env:OJP_TEST_NODE_CONTAINERS = 'ojp-node-1,ojp-node-2'
$env:OJP_TEST_NODE_PORTS = '1059,1060'
npm run test:xa
```

## Real SQL errors (`OjpSqlError`) via gRPC metadata

`ojp-server` sends the real details of a SQL exception (message, `sqlState`,
`vendorCode`) as a **binary gRPC metadata trailer** (`SqlErrorResponse`
serialized in protobuf, key `com.openjproxy.grpc.sqlerrorresponse-bin`) — never in
the gRPC status text field `details`. The driver automatically decodes this trailer
(via `protobufjs`) and builds an `OjpSqlError` with the real message/sqlState/
vendorCode from the original database exception, instead of a generic `"13 INTERNAL"`.

## MVP scope (phase 1)

- [x] Connection string parser (`jdbc:ojp[...]_...`, multinode and named datasource).
- [x] `connect`, `executeUpdate`, `executeQuery` (with streaming consumed into memory).
- [x] `startTransaction` / `commit` / `rollback`.
- [x] Basic type mapping (null, boolean, int, long/bigint, double, string, bytes, date/timestamp).
- [x] Typed errors (`OjpSqlError`, `OjpConnectionError`), with real message/sqlState/vendorCode
      decoded from the gRPC metadata (`SqlErrorResponse`).
- [x] Real pagination (`executeQueryStream`): on-demand consumption of the gRPC stream,
      without accumulating the entire result set in memory, with automatic cancellation
      and result-set resource closure on early interruption or normal completion.
      Includes support for "row-by-row" mode via `fetchNextRows`
      (SQL Server/DB2 + LOBs), **validated with real integration against SQL Server**.
- [x] `DECIMAL`/`NUMERIC`/`MONEY` mapping (`BigDecimalWire` → decimal string).
- [x] Real integration validation against SQL Server in an Always On AG (multinode).
- [x] LOB upload/binding (`createLob`) validated against SQL Server. Ranged SQL Server
      BLOB/CLOB reads are unsupported by its JDBC driver; exact-size read streams are
      validated against H2 (see the compatibility table above).
- [x] Multinode failover / round-robin between endpoints — connection-level-only
      failover, strict session stickiness, validated end-to-end against a real
      3-node cluster (see the dedicated section above).
- [x] XA support (distributed transactions) — `OjpXADataSource`/`OjpXAConnection`/
      `OjpXAResource`, validated against MariaDB, MySQL, PostgreSQL and SQL Server
      (two-phase commit, rollback, one-phase optimization, `recover`, `isSameRM`); see
      the dedicated section above for the recovery model.
- [ ] Framework adapters are outside this module.

## Scripts

`proto-includes/google/type` contains minimal wire-compatible Google `Date` and
`TimeOfDay` schemas imported by the gRPC contract. The `google/protobuf`
well-known types are resolved from protobufjs's built-in definitions. Keep
`proto-includes` in the published package when changing its inclusion or build
configuration.

```bash
npm install
npm run verify-types # fails if src/proto/types.ts drifted from StatementService.proto (also runs as prebuild/pretest)
npm run build        # compiles TypeScript to dist/
npm test             # unit tests (parser, type mapping) — no external infrastructure needed
npm run test:integration  # integration against ojp-server + PostgreSQL (requires OJP_ENABLE_INTEGRATION_TESTS=true)
npm run test:integration:all # all integration suites; configure all test infrastructure and flags
OJP_ENABLE_MYSQL_TESTS=true npm run test:mysql-l1 # MySQL L1 against a real server
OJP_ENABLE_MARIADB_TESTS=true npm run test:mariadb-l1 # MariaDB L1 against a real server
OJP_ENABLE_MYSQL_TESTS=true npm run test:mysql-l2 # MySQL L2 against a real server
OJP_ENABLE_MARIADB_TESTS=true npm run test:mariadb-l2 # MariaDB L2 against a real server
OJP_TEST_H2=true OJP_TEST_H2_ADDR=localhost:1059 npm run test:h2-l2 # H2 L2 against a real OJP server
OJP_ENABLE_MYSQL_TESTS=true npm run test:mysql-l3 # MySQL L3 against a real server
OJP_ENABLE_MARIADB_TESTS=true npm run test:mariadb-l3 # MariaDB L3 against a real server
OJP_ENABLE_MYSQL_TESTS=true npm run test:mysql-l4 # MySQL L4 against a real server
OJP_ENABLE_MARIADB_TESTS=true npm run test:mariadb-l4 # MariaDB L4 against a real server
OJP_ENABLE_MYSQL_TESTS=true npm run test:mysql-l5 # MySQL L5 against a real server
OJP_ENABLE_MARIADB_TESTS=true npm run test:mariadb-l5 # MariaDB L5 against a real server
OJP_ENABLE_MYSQL_TESTS=true npm run test:mysql-l6 # MySQL session affinity
OJP_ENABLE_MARIADB_TESTS=true npm run test:mariadb-l6 # MariaDB session affinity
OJP_ENABLE_MYSQL_L7_TESTS=true npm run test:mysql-l7 # MySQL multinode operations
OJP_ENABLE_MARIADB_L7_TESTS=true npm run test:mariadb-l7 # MariaDB multinode operations
OJP_ENABLE_MYSQL_L8_TESTS=true npm run test:mysql-l8 # MySQL recovery
OJP_ENABLE_MARIADB_L8_TESTS=true npm run test:mariadb-l8 # MariaDB recovery
OJP_ENABLE_MYSQL_L9_TESTS=true npm run test:mysql-l9 # MySQL XA
OJP_ENABLE_MARIADB_L9_TESTS=true npm run test:mariadb-l9 # MariaDB XA
npm run test:postgres-l8 # PostgreSQL L8 recovery; requires three OJP nodes and a maxConcurrentRequests=1 node
npm run test:postgres-l10 # PostgreSQL L10 combined multinode/mTLS certification
npm run test:lob-h2       # LOB integration against ojp-server + H2 (requires OJP_ENABLE_LOB_H2_TESTS=true)
npm run test:sqlserver-ag # SQL Server integration (requires OJP_ENABLE_SQLSERVER_AG_TESTS=true)
npm run test:sqlserver-l8 # SQL Server L8 multinode failover/recovery integration
npm run test:multinode-failover # multinode failover, orchestrates real Docker containers (requires OJP_ENABLE_MULTINODE_FAILOVER_TESTS=true)
npm run test:xa           # PostgreSQL L9 XA against at least two OJP nodes (requires OJP_ENABLE_XA_TESTS=true and OJP_TEST_NODE_CONTAINERS)
npm run test:sqlserver-xa # XA integration against ojp-server + SQL Server (requires OJP_ENABLE_SQLSERVER_XA_TESTS=true)
```

## Testing locally against ojp-server

The Node client workflow configures H2, PostgreSQL, SQL Server, three dedicated OJP
nodes, a separate PostgreSQL L8 overload-test node, and dedicated PostgreSQL and SQL
Server mTLS clusters; it runs every integration suite.
The MySQL/MariaDB L9 suites require at least two dedicated OJP containers named by
`OJP_TEST_NODE_CONTAINERS` with corresponding `OJP_TEST_NODE_PORTS`; they stop and
restart those containers. MySQL's test user requires `XA_RECOVER_ADMIN`.
For PostgreSQL L8, set `OJP_ENABLE_POSTGRES_L8_TESTS=true`,
`OJP_TEST_NODE_CONTAINERS`, and `OJP_TEST_POSTGRES_L8_THROTTLED_NODE_CONTAINER` /
`OJP_TEST_POSTGRES_L8_THROTTLED_NODE_PORT` for an OJP node started with
`-Dojp.server.maxConcurrentRequests=1`. MySQL and MariaDB L8 use the same dedicated
cluster and require `OJP_TEST_L8_THROTTLED_NODE_CONTAINER` and
`OJP_TEST_L8_THROTTLED_NODE_PORT` for a distinct node with that limit. The suites stop
and restart their configured containers. For the H2 L1-L4 suites, start an OJP
server with the H2 JDBC driver and run:

```bash
OJP_TEST_H2=true OJP_TEST_H2_ADDR=localhost:1059 npx jest --runInBand test/integration.h2.l1.test.ts
OJP_TEST_H2=true OJP_TEST_H2_ADDR=localhost:1059 npm run test:h2-l2
OJP_TEST_H2=true OJP_TEST_H2_ADDR=localhost:1059 npm run test:h2-l3
OJP_TEST_H2=true OJP_TEST_H2_ADDR=localhost:1059 npm run test:h2-l4
```

Each suite reads every JDBC URL, username and password row from its CSV fixture.
The Node workflow uses MySQL 8.0 on port 3306 and MariaDB 10.11 on port 3307, with
the OJP server's matching JDBC drivers. Override fixture paths with
`OJP_TEST_H2_CSV`, `OJP_TEST_MYSQL_CSV`,
`OJP_TEST_MARIADB_CSV`, `OJP_TEST_POSTGRES_CSV` or `OJP_TEST_SQLSERVER_CSV`. Set
`OJP_TEST_SERVER_ENDPOINT` for MariaDB, MySQL, PostgreSQL and SQL Server, and
`OJP_TEST_H2_ADDR` for H2. Database passwords can be supplied via
`OJP_TEST_MYSQL_PASSWORD`, `OJP_TEST_MARIADB_PASSWORD`, `OJP_TEST_PASSWORD` (PostgreSQL)
and `OJP_TEST_SQLSERVER_PASSWORD`.

```bash
cd ../ojp-server
bash download-drivers.sh
mvn verify -pl ojp-server -Prun-ojp-server   # leave running in another terminal
```

Then, from the root of this module (PowerShell):

```powershell
$env:OJP_ENABLE_INTEGRATION_TESTS = 'true'
npm run test:integration

$env:OJP_ENABLE_LOB_H2_TESTS = 'true'
npm run test:lob-h2
```
