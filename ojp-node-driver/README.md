# @ojp/node-driver

Node.js/TypeScript driver for **OJP (Open J Proxy)**. Connects to `ojp-server` via
gRPC, using the same contract (`StatementService.proto`) as the official JDBC driver.

> Status: **MVP under development**. API subject to change until stable v1.0.0.

## Why this driver exists

OJP is made up of `ojp-server` (which owns the real connection pools via HikariCP) and
client drivers that make gRPC calls instead of opening direct connections to the database.
This package is the Node.js equivalent of `ojp-jdbc-driver` in Java — but since there's
no "JDBC" standard in the Node.js ecosystem, it exposes its own API (`pg`-style) that
could eventually power adapters for TypeORM/Knex (for use in NestJS).

## Architecture

```
[Node.js App] --> [@ojp/node-driver] --gRPC/HTTP2--> [ojp-server] --JDBC--> [Database]
```

- There is no connection pool on the Node client side — the real pool lives in `ojp-server`.
- Each `OjpClient` represents a logical session (equivalent to a `java.sql.Connection`).
- The `.proto` contract is consumed from [`@ojp/grpc-contract`](../ojp-grpc-contract), a
  versioned, hash-pinned package distributing the same contract used by `ojp-jdbc-driver`
  and `ojp-server` (see that package's README for its versioning policy and how it's kept
  in sync with the upstream `ojp-grpc-commons` source of truth).

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
  cancels the underlying gRPC call, releasing the result set on `ojp-server`. The
  connection remains usable normally afterward.
- `executeQuery()` (buffered) is internally implemented on top of the same streaming
  logic — both share the same code path and error semantics.
- SQL Server and DB2 use a special "row-by-row" mode when the result set contains
  LOBs (advancing the cursor ahead would invalidate the LOBs): the server signals this
  via `OpResult.flag`, and the driver then fetches each additional row with a unary
  `fetchNextRows` call, transparently to whoever consumes `executeQueryStream`/`executeQuery`.
  This path faithfully follows the protocol and reference JDBC driver's contract,
  but **has not yet been validated by real integration tests** (no SQL Server/DB2
  available in the current development environment) — treat it as experimental until then.

## Transaction semantics (important)

OJP faithfully replicates the `java.sql.Connection` contract: `startTransaction()`
turns off autocommit on the real physical connection on `ojp-server` (equivalent to
`setAutoCommit(false)`), and **there is no gRPC call equivalent to
`setAutoCommit(true)`** to turn autocommit back on after a `commit()`/`rollback()`.
This is the application's responsibility, just like in plain JDBC.

In practice, this means that **once a session enters transactional mode, it stays in
that mode for the rest of the connection's lifecycle** — even after a successful
`commit()` or `rollback()`. Every statement executed afterward remains part of an
implicit transaction, which is only persisted with a new explicit
`commit()`/`rollback()`. If one of these statements fails (e.g. invalid SQL),
Postgres aborts the transaction and **all** subsequent statements on the same
connection fail until a `rollback()` is called.

Recommendations:

- Treat each `OjpClient` as a **unit of work** (the "one connection per
  transaction/request" pattern), just as you would with `java.sql.Connection` in JDBC
  pools. Open, execute, finish (commit/rollback), and close (`close()`) — don't reuse
  the same connection indefinitely mixing transactional and non-transactional code.
- If you need to reuse the same connection after a `commit()`/`rollback()`, make sure
  every subsequent statement is also inside an explicit `startTransaction()` /
  `commit()`/`rollback()` pair.
- If an error occurs during a transaction, always call `rollback()` before continuing
  to use the same connection.

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

## Validated against a real SQL Server Always On Availability Group (AG)

The driver has been tested end-to-end against a real cluster: 3 independent
`ojp-server` instances (multinode) pointing at a SQL Server 2022 in an Availability
Group (2 replicas), using the URL in the format
`jdbc:ojp[host1:port1,host2:port2,host3:port3(name)]_sqlserver://...`.
Validated coverage (see `test/integration.sqlServerAg.test.ts`):

- CRUD with native SQL Server types (`NVARCHAR`, `DECIMAL`, `BIT`, `DATETIME2`).
- Transactions (`commit`/`rollback`) and reading real data from `AdventureWorks`
  (including `MONEY` columns, e.g. `Production.Product.ListPrice`).
- **"Row-by-row" mode (`fetchNextRows`)**: validated for the first time against a
  real database — `VARBINARY(MAX)` columns force SQL Server into this mode
  (signaled via `OpResult.flag == RESULT_SET_ROW_BY_ROW_MODE`), and the driver
  correctly consumes both a single isolated row and multiple rows via
  `executeQueryStream`.
- Real pagination (multiple blocks of 100+ rows) via `executeQueryStream`.

Run locally (requires the AG Docker infrastructure already running):

```powershell
$env:OJP_ENABLE_SQLSERVER_AG_TESTS = 'true'
npm run test:sqlserver-ag
```

Optional environment variables: `OJP_TEST_SQLSERVER_URL`, `OJP_TEST_SQLSERVER_USER`,
`OJP_TEST_SQLSERVER_PASSWORD` (defaults point at the local `sql-primary` topology
used in this validation).

**Known observations/limitations:**

- Multinode failover/round-robin is implemented (see the dedicated section below);
  this AG cluster is also used as the real infrastructure for those tests. Note that
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

- **Reading**: when reading a native `CLOB` column, the driver transparently
  "dereferences" it — you receive the content as a `string`, without needing to know
  that there's a LOB underneath. For explicit/streaming reads in chunks, use
  `readLobAsString(ref)` / `readLobChunks(ref)`.
- **Writing** (`createLob(data: Buffer | string, lobKind: 'BLOB' | 'CLOB')`): writes
  the content to the server via bidirectional gRPC streaming (one `LobDataBlock` per
  64KB chunk) and returns an `OjpLobRef` (`{ session, lobReference }`) to use as a
  parameter in `INSERT`/`UPDATE`.

```ts
const ref = await client.createLob(Buffer.from('large content...'), 'BLOB');
await client.executeUpdate('INSERT INTO files (id, content) VALUES (?, ?)', [1, ref]);
```

**Important protocol detail (multi-chunk upload):** the first chunk is sent with the
connection's "normal" session; the first acknowledgement (`LobReference`) returned by
the server already carries a "promoted" (stateful) session, tied to the same physical
connection while the LOB is being written. Every subsequent chunk **must** use this
promoted session, not the original one — otherwise the server tries to (re)open the
LOB on a different connection and the upload fails. The driver already handles this
internally; it's documented here only because it was the cause of a real bug fixed
during development (multi-chunk uploads were silently failing).

### Backend compatibility (validated in this session)

| Backend | `createLob` (1 chunk, ≤64KB) | `createLob` (multi-chunk) | Notes |
|---|---|---|---|
| **SQL Server** (`mssql-jdbc`) | ✅ | ✅ | Validated end-to-end (write + bind + read, byte by byte) against a real AG cluster. |
| **H2** | ✅ | ❌ | H2's free-standing `Blob`/`Clob` (`Connection.createBlob()/createClob()`) only accept **a single** write call (`setBytes`/`setCharacterStream`); a second call at another chunk throws `SQLFeatureNotSupportedException` ("Allocate a new object to set its value.", SQLState `HYC00`). Limitation of H2's JDBC driver, not of OJP or of this driver. |
| **PostgreSQL** (`pgjdbc`) | ❌ | ❌ | `PgConnection.createBlob()`/`createClob()` immediately throw `SQLFeatureNotSupportedException` ("not yet implemented") — feature not implemented in `pgjdbc`. Note: this message is localized based on the server JVM's default locale (e.g. Portuguese on a pt-BR JVM), so don't assert on the English wording specifically — the method name (`createBlob`) is the only locale-invariant part. **Warning**: if `createLob` fails like this mid-session, that session's physical connection becomes unusable for subsequent calls — always use a dedicated connection to test/probe for LOB support, never a long-lived shared connection. |
| MySQL / MariaDB / Oracle / DB2 | untested | untested | No environment available in this session to validate. |

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

Validated end-to-end (`test/integration.multinode.test.ts`, using the same 3-node
`ojp-server` + SQL Server AG cluster as the section above, with real `docker kill`/
`docker start` orchestration to simulate a node going down):

- `connect()` succeeds by failing over to a healthy endpoint when one node is down.
- A node marked unhealthy after a failed attempt is also avoided by later `connect()` calls.
- An established session's operations fail with a clear, non-recovered error when its
  bound node goes down mid-session.
- Repeated `connect()` calls round-robin across all healthy endpoints.

Run locally (requires the same AG Docker infrastructure as above, plus a Docker CLI
with permission to stop/start the `ojp-server-1/2/3` containers):

```powershell
$env:OJP_ENABLE_MULTINODE_FAILOVER_TESTS = 'true'
npm run test:multinode-failover
```

**Not yet implemented** (deferred, out of scope for this first pass): the reference
JDBC driver also recovers from a `NOT_FOUND` server response (server lost its
in-memory session/pool, e.g. after a restart) by rebuilding and retrying the same
operation once against the *same* endpoint. This Node driver does not yet special-case
that scenario — a `NOT_FOUND` currently surfaces as a regular SQL-level error instead
of being retried automatically.

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
  { user: 'ojptest', password: 'ojptest123' },
);

const xaConn = await xaDs.getXAConnection();
const xaRes = xaConn.getXAResource();
const conn = xaConn.getConnection(); // ordinary OjpClient, for executing SQL

const xid = { formatId: 1, globalTransactionId: myGtrid, branchQualifier: myBqual };

await xaRes.start(xid, xaConstants.TMNOFLAGS);
await conn.executeUpdate('INSERT INTO orders (id, total) VALUES (?, ?)', [1, 99.9]);
await xaRes.end(xid, xaConstants.TMSUCCESS);

const prepareResult = await xaRes.prepare(xid);
if (prepareResult !== xaConstants.XA_RDONLY) {
  await xaRes.commit(xid, false); // two-phase commit
}
// or: await xaRes.rollback(xid);
// or, for a single resource manager: await xaRes.commit(xid, true) directly after end() (skips prepare).

await xaConn.close();
```

- **Xids are never generated by this driver** — they must come from your transaction
  manager (`formatId` + `globalTransactionId` + `branchQualifier`, exactly like
  `javax.transaction.xa.Xid`). See `src/xa/xid.ts` (`Xid`, `xidEquals`, `xidToString`).
- **Errors** are `OjpXaError` instances (`message` + `errorCode`, mirroring
  `javax.transaction.xa.XAException`):
  - `errorCode === 0` — business-level failure reported by the server
    (`XaResponse.success === false`), matching the reference JDBC driver's
    `new XAException(message)` (uninitialized/default error code).
  - `XAER_RMFAIL` (`-7`) — the resource manager is unavailable (connection-level
    transport failure). Only assigned for `start()` and `recover()`.
  - `XAER_RMERR` (`-3`) — any other resource-manager-side error, including every other
    operation's transport failures and unexpected resource-manager exceptions (e.g. the
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

### Known limitations vs. the reference JDBC driver (`org.openjproxy.jdbc.xa.*`)

This first pass focuses on a correct, faithful **wire-protocol-level** XA
implementation with proper standard error codes — not a full transaction-manager-grade
high-availability XA connection pool. Deliberately not implemented yet:

- **No cross-node retry-with-session-recreation** in `start()` on a connection-level
  failure. The Java driver retries across healthy multinode endpoints before giving up;
  this driver fails immediately with `XAER_RMFAIL`.
- **No proactive multinode health-listener invalidation** of XA connections
  (`ServerHealthListener`/`onServerUnhealthy`/`onServerRecovered` in the Java driver).
  An XA session still benefits from the same endpoint-health tracking as ordinary
  sessions on its *next* `connect()` attempt, just not a proactive push notification.
- **Xid-only lookup is not possible** — every XA RPC requires a valid `SessionInfo`,
  not just an `Xid` (this is an ojp-server/protocol characteristic, not something this
  driver adds). A transaction manager recovering across process restarts must retain
  or reconstruct the session/server binding to `commit`/`rollback`/`recover` a
  previously prepared transaction — a bare `Xid` is not enough on its own.

Validated end-to-end with real two-phase commit, rollback, one-phase commit
optimization, `recover()`, `isSameRM()`, and error mapping
(`test/integration.xa.postgres.test.ts`, against a real `ojp-server` + PostgreSQL).
Run locally:

```powershell
$env:OJP_ENABLE_XA_TESTS = 'true'
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
      on early interruption. Includes support for "row-by-row" mode via `fetchNextRows`
      (SQL Server/DB2 + LOBs), **validated with real integration against SQL Server**.
- [x] `DECIMAL`/`NUMERIC`/`MONEY` mapping (`BigDecimalWire` → decimal string).
- [x] Real integration validation against SQL Server in an Always On AG (multinode).
- [x] LOBs (`createLob`/transparent CLOB reading) — full support validated against
      SQL Server; H2 (single chunk) and PostgreSQL have limitations of their own
      backend's JDBC driver (see the compatibility table above).
- [x] Multinode failover / round-robin between endpoints — connection-level-only
      failover, strict session stickiness, validated end-to-end against a real
      3-node cluster (see the dedicated section above).
- [x] XA support (distributed transactions) — `OjpXADataSource`/`OjpXAConnection`/
      `OjpXAResource`, validated end-to-end against a real `ojp-server` + PostgreSQL
      (two-phase commit, rollback, one-phase optimization, `recover`, `isSameRM`); see
      the dedicated section above for known scope reductions vs. the JDBC reference.
- [ ] TypeORM adapter (`@ojp/typeorm-driver`) for direct use in NestJS.

## Scripts

```bash
npm install
npm run build        # compiles TypeScript to dist/
npm test             # unit tests (parser, type mapping) — no external infrastructure needed
npm run test:integration  # integration against ojp-server + PostgreSQL (requires OJP_ENABLE_INTEGRATION_TESTS=true)
npm run test:lob-h2       # LOB integration against ojp-server + H2 (requires OJP_ENABLE_LOB_H2_TESTS=true)
npm run test:sqlserver-ag # integration against a real SQL Server Always On AG (requires OJP_ENABLE_SQLSERVER_AG_TESTS=true)
npm run test:multinode-failover # multinode failover, orchestrates real Docker containers (requires OJP_ENABLE_MULTINODE_FAILOVER_TESTS=true)
npm run test:xa           # XA (distributed transaction) integration against ojp-server + PostgreSQL (requires OJP_ENABLE_XA_TESTS=true)
```

## Testing locally against ojp-server

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
