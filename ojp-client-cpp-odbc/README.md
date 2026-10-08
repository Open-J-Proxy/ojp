# OJP C++ ODBC Client

This module provides an ANSI ODBC driver for applications that access OJP
servers from C++. It currently provides **L1 for H2, PostgreSQL, and SQL Server**,
**L2-L5 for H2 and SQL Server**, **L6 for H2 and SQL Server**, and
**L7 for H2 and SQL Server** from the
[client implementation levels](../documents/multi-language-client-spec/CLIENT_IMPLEMENTATION_LEVELS.md).
It uses the canonical `StatementService.proto` from `ojp-grpc-commons` and
communicates with the server over gRPC.

## Current implementation level assessment

| Assessment | Value |
|---|---|
| Highest implemented level | **L7 for H2 and SQL Server; L1 for PostgreSQL** |
| Summary | ANSI ODBC connectivity and CRUD support one or more configured OJP endpoints. H2 and SQL Server include level-specific coverage through L7, with shared endpoint channels, health monitoring, cluster-health propagation, and stateless `NOT_FOUND` recovery; session-bound requests remain sticky to their assigned server. |

### Current test-proven coverage by database

| Database | Highest achieved level (current tests) | Evidence |
|---|---:|---|
| **H2** | **L7** | L1-L6 suites plus `h2_l7_integration_test.cpp` cover multiple OJP endpoints and shared H2 data across nodes. |
| PostgreSQL | **L1** | `l1_integration_test.cpp` exercises ODBC → one OJP server → PostgreSQL. |
| SQL Server | **L7** | L1-L6 suites plus `sqlserver_l7_integration_test.cpp` cover shared pool reuse, multi-endpoint routing, and exclusion of an unavailable endpoint. |
| MySQL | Not established | No database-specific integration suite in this module. |
| MariaDB | Not established | No database-specific integration suite in this module. |
| Oracle | Not established | No database-specific integration suite in this module. |
| DB2 | Not established | No database-specific integration suite in this module. |
| CockroachDB | Not established | No database-specific integration suite in this module. |

Level definitions: [client implementation levels](../documents/multi-language-client-spec/CLIENT_IMPLEMENTATION_LEVELS.md).

## L1 capabilities

L1 includes connection establishment and termination, query and update
execution, basic result columns and scalar values, input parameters for common
ODBC scalar types, and SQL error diagnostics. The implementation supports
`SQLDriverConnect`, `SQLPrepare`/`SQLExecute`, `SQLBindParameter` for input
parameters, `SQLExecDirect`, forward-only `SQLFetch`/`SQLGetData`, and affected
row counts. The current parameter/value mapping covers null, booleans, signed
integers, floats, doubles, decimals, dates, times, timestamps, strings, and
binary values. NULL parameters are sent with the `java.sql.Types` code matching
the bound ODBC SQL type, because the server binds them with `setNull`. Disable application-side
connection pooling when using OJP.

The H2 L2 suite covers typed decimal, temporal, integer, floating-point,
boolean, text, and binary input parameters, both direct and prepared statement
execution, basic result-column metadata, and retrieval of the generated identity
value through H2 SQL. Unlike the JDBC H2 type suite, this ODBC suite does not
cover Java-specific types, timezone-aware values, or arrays; those have no
equivalent in the currently implemented ODBC parameter mapping. The ODBC API
has no portable equivalent of JDBC `getGeneratedKeys()`, so generated
identities are read with database SQL rather than a driver-specific
generated-keys API.

The H2 and SQL Server L3 suites retrieve 10,001 ordered rows through the
server-streaming query protocol, check result metadata and end-of-result
behavior, and exercise closing a partially consumed result and reusing the
statement for full and empty results. The SQL Server suite also returns multiple
`VARBINARY` rows, exercising the server's row-by-row result mode and
`fetchNextRows` pagination. The client consumes every `executeQuery` stream and
closes its server-side result set with `callResource(RES_RESULT_SET, CALL_CLOSE)`.
The client also uses `fetchNextRows` when the server marks a result as row-by-row.

The H2 L4 suite maps ODBC autocommit and `SQLEndTran`/`SQLTransact` to the
transaction RPCs, and maps `SQL_ATTR_TXN_ISOLATION` to connection resource calls.
ODBC has no portable savepoint API, so the client accepts `SAVEPOINT name`,
`ROLLBACK TO [SAVEPOINT] name`, and `RELEASE [SAVEPOINT] name` statements and
implements them with OJP savepoint resource calls. These statements are
intercepted by the client and are not sent to H2.

The H2 L5 suite binds `SQL_LONGVARBINARY` and `SQL_LONGVARCHAR` values using
ODBC data-at-execution (`SQLParamData`/`SQLPutData`). The client uploads BLOB and
CLOB chunks through `createLob`, then sends the returned handle as a `PT_BLOB`
or `PT_CLOB` parameter. `SQLGetData` hydrates BLOB and CLOB references through
`readLob`; the suite verifies a 180 KB binary value, multi-byte UTF-8 CLOB data,
and typed NULL LOB parameters.

The H2 L6 suite verifies session affinity with a local temporary table across
multiple statements, CRUD operations, and transaction boundaries. Session UUIDs
are bound to their target server; all session-scoped RPCs use that binding, and
an RPC failure is surfaced without retrying on another server.

The H2 L7 suite connects through multiple OJP endpoints, checks shared-pool
connection reuse and cluster CRUD, and includes an unavailable endpoint to
verify it is marked down and excluded from subsequent selection. The client
balances new connections by least active connections by default, with round-robin
available through `OJP.LOADAWARE.SELECTION.ENABLED=false`. Endpoint channels are
shared process-wide; background health checks propagate topology and recreate
pools before a recovered endpoint is marked healthy. Stateless `NOT_FOUND`
responses invalidate the cached pool and retry once after reconnecting.

The SQL Server L7 suite covers the same multinode behavior with the SQL Server
fixture, including multi-endpoint CRUD, cached pool reuse, and an unavailable
endpoint. Health probes use the `connect` RPC every 5 seconds by default;
configure a positive interval in milliseconds with the
`OJP_HEALTH_CHECK_INTERVAL_MS` environment variable. Active sessions are never
retried on another endpoint.

The SQL Server L2 suite mirrors the types in the JDBC driver's
`SQLServerMultipleTypesIntegrationTest`:

| SQL Server type (JDBC test) | ODBC binding / retrieval in the L2 suite |
|---|---|
| `INT`, `BIGINT`, `SMALLINT` | `SQL_C_SLONG`/`SQL_C_SBIGINT`/`SQL_C_SSHORT` parameters, read back as integers |
| `TINYINT` (value 255) | Bound as `SQL_C_SLONG`/`SQL_INTEGER`, like the JDBC test's `setInt`; SQL Server `TINYINT` is unsigned, so 255 does not fit `SQL_C_STINYINT` |
| `BIT` | `SQL_C_BIT` |
| `FLOAT`, `REAL` | `SQL_C_DOUBLE`, `SQL_C_FLOAT` |
| `DECIMAL(10, 2)`, `MONEY`, `SMALLMONEY` | `SQL_C_NUMERIC` and decimal text parameters, read back directly as decimal text |
| `NVARCHAR`, `NTEXT`, `TEXT`, `NVARCHAR(MAX)`, `VARCHAR(MAX)` | `SQL_C_CHAR` with UTF-8 text, including Chinese characters and an emoji, and a 50 KB value |
| `VARBINARY(1)`, `VARBINARY(4)`, `VARBINARY(MAX)` | `SQL_C_BINARY`, including a 10,000-byte value, and a multi-row `VARBINARY` query (the server sends these rows one at a time) |
| `DATE`, `TIME`, `DATETIME2`, `SMALLDATETIME` | `SQL_C_TYPE_DATE`, `SQL_C_TYPE_TIME`, `SQL_C_TYPE_TIMESTAMP` |
| `DATETIMEOFFSET` (`OffsetDateTime`, `OffsetTime`, `Instant`) | UTC timestamp structs and offset text such as `2024-12-01 10:10:10 +02:00`; values are read back as UTC, and a text `CAST` confirms the stored offset |
| `UNIQUEIDENTIFIER` | Generated with `NEWID()` and read back as GUID text |
| `IMAGE`, `XML`, `GEOMETRY`, `GEOGRAPHY`, `HIERARCHYID`, `SQL_VARIANT` | Created and returned as `SQL_NULL_DATA`, as in the JDBC test, which never writes them either |
| NULL values | Typed NULL parameters plus omitted columns returned as `SQL_NULL_DATA` |

These JDBC cases are not ported, and here is why:

- **Java-specific types.** `LocalDate`, `LocalTime`, and `LocalDateTime` versus
  `java.sql.Date`, `Time`, and `Timestamp`: ODBC has one C struct per SQL type,
  so each pair maps to the same `DATE`, `TIME`, or `DATETIME2` binding.
- **Arrays.** `createArrayOf` has no ODBC equivalent.
- **Timezone-aware ODBC types.** The client does not implement SQL Server's
  driver-specific `SQL_SS_TIMESTAMPOFFSET` C type. Offsets are therefore sent as
  text.

Generated SQL Server identities are read with `IDENT_CURRENT` for the table
that the run creates.

## L5 LOB coverage

The SQL Server L5 suite streams `SQL_LONGVARBINARY` parameters through
`SQLParamData`/`SQLPutData`, which the driver forwards to `createLob` in 64 KiB
chunks. The integration test round-trips large, small, empty, and NULL
`VARBINARY(MAX)` values through SQL Server and verifies the returned bytes.
`readLob` is also supported for LOB references returned by the server; SQL
Server query results are currently hydrated as binary values by the server.

Decimal results arrive as BigDecimalWire bytes
([format](../documents/protocol/BIGDECIMAL_WIRE_FORMAT.md)). Like the JDBC
driver, the client decodes result bytes that match this layout exactly as
decimal text and returns other bytes as binary.

## L4 transaction coverage

The H2 L4 suite covers commit, rollback, autocommit transitions, savepoint
rollback/release, isolation, and invalidated savepoint handles. The SQL Server
suite follows transaction and savepoint cases in
`SQLServerConnectionExtensiveTests` and `SQLServerSavepointTests` from the JDBC
reference client, and verifies commit, rollback, autocommit transitions, nested
savepoint rollback/release, and isolation through OJP.

ODBC has no standard savepoint API. The client maps `SAVEPOINT name` and
`SAVE TRANSACTION name`, `ROLLBACK TO [SAVEPOINT] name` and
`ROLLBACK TRANSACTION name`, plus `RELEASE [SAVEPOINT] name` to the
`callResource` operations in `CLIENT_SPEC_AI.md`. These directives are
intercepted by the client and not forwarded to the database.

Output parameters, wide-character ODBC entry points, complete metadata
discovery, configurable fetch-size pagination, full L8 recovery/redistribution,
and client-side throttling are not implemented. The client shares gRPC channels
across configured endpoints, balances new connections, monitors endpoint health,
and routes sticky sessions only to their bound target.

## Conformance with `CLIENT_SPEC_AI.md`

The same driver code serves H2, PostgreSQL, and SQL Server, so these points
apply to all three databases.

Implemented rules:

| Spec rule | Implementation |
|---|---|
| 4.1.1 process-stable UUID v4 `clientUUID` | Generated once per process |
| 4.2 `ConnectionDetails` | `url`, `user`, `password`, `clientUUID`, `isXA=false` |
| 4.3.1–4.3.2 send and replace `SessionInfo` | Sent with every request and replaced from every `executeQuery`, `executeUpdate`, `fetchNextRows`, and `callResource` response |
| 4.3.4 `terminateSession` exactly once | Sent once by `SQLDisconnect`; the connection is unusable afterwards, even if the call fails |
| 4.4.1 empty `statementUUID` for new statements | Always sent empty; prepared statements are not reused on the server |
| 4.4.2 1-based parameter indexes | ODBC parameter numbers are passed through |
| 4.4.3 `PT_BIG_DECIMAL` as BigDecimalWire `bytes_value` | Decimal parameters are encoded, and decimal results decoded, in this format |
| 4.4.4 `StringValue` wrapper fields | `uuid_value`, `biginteger_value`, `url_value`, `rowid_value`, and `rowidlifetime_value` results are decoded as text |
| 4.4.5 `PT_NULL` with a `java.sql.Types` code in `int_value` | Derived from the bound ODBC SQL type; unknown types send `0` (`Types.NULL`) |
| 4.5.2 close result sets | Rows are read eagerly, then the result set is closed with `callResource(RES_RESULT_SET, CALL_CLOSE)` |
| L4 transaction lifecycle | `startTransaction`, `commitTransaction`, and `rollbackTransaction` replace local `SessionInfo` from each response |
| 4.5.3 savepoint lifecycle | Savepoints are created through `RES_CONNECTION/CALL_SET` and invalidated locally after transaction completion |
| L5 LOB lifecycle | `createLob` sends 64 KB `LT_BLOB`/`LT_CLOB` chunks, updates the session from returned references, and `readLob` concatenates response blocks |
| L4 ODBC operations | `SQL_ATTR_AUTOCOMMIT`, `SQLEndTran`/`SQLTransact`, and transaction-isolation attributes map to transaction RPCs and `callResource` |
| L5 SQL Server LOBs | `SQL_LONGVARBINARY` data-at-execution uses chunked `createLob`; LOB references can be read with `readLob` |
| L6 session affinity | H2 and SQL Server route session-scoped RPCs exclusively to the bound `targetServer`; failures are surfaced without retry or reroute |
| L7 multinode operations | Shared endpoint channels, least-connections/round-robin selection, health probes and cluster-health propagation, connHash caching, and stateless `NOT_FOUND` reconnect/retry |
| Section 3 transitions | Calls on a closed connection fail with `08003` without sending an RPC |

In row-by-row mode (SQL Server and DB2 results with binary or LOB columns),
the client pulls the remaining rows with `fetchNextRows`. Earlier versions
returned only the first row.

Spec rules outside the implemented levels or still incomplete:

- **L5 for PostgreSQL:** `createLob` and `readLob`.
- **L8:** full failover/recovery and connection redistribution.
- **Section 8:** client-side admission throttling.

## Build requirements

- CMake 3.20 or later and a C++17 compiler
- Protobuf and gRPC C++ development packages, including `protoc` and
  `grpc_cpp_plugin`
- ODBC development headers and an ODBC Driver Manager (unixODBC on Linux)
- A checkout of [googleapis](https://github.com/googleapis/googleapis) to
  provide `google/type/date.proto` and `google/type/timeofday.proto`

Build the driver and test executable:

```sh
cmake -S ojp-client-cpp-odbc -B ojp-client-cpp-odbc/build \
  -DGOOGLEAPIS_PROTO_DIR=/path/to/googleapis
cmake --build ojp-client-cpp-odbc/build
```

The CMake build generates C++ bindings from the shared OJP proto source in the
build directory; generated files are not checked in.

Prebuilt binaries are not published yet. See the
[ODBC driver distribution analysis](../documents/analysis/ODBC_DRIVER_DISTRIBUTION_ANALYSIS.md)
for the proposed per-OS delivery plan.

## Using from a C++ ODBC application

Register the driver with the ODBC Driver Manager as `OJP`, then use
`SQLDriverConnect` with these connection fields:

- `SERVER`: one OJP server `host:port` or a comma-separated cluster such as
  `host1:port1,host2:port2`
- `DATABASE`: the backend JDBC URL (quote values containing semicolons with
  ODBC braces)
- `UID` and `PWD`: backend credentials

For example:

```text
DRIVER={OJP};SERVER={localhost:1059};DATABASE={jdbc:h2:mem:example;DB_CLOSE_DELAY=-1};UID={sa};PWD=;
```

The ODBC connection-string braces protect semicolons in the H2 URL; a literal
closing brace in a value is escaped by doubling it. `SQLConnect` with a DSN is
not implemented yet.

ODBC applications use the standard ODBC API; the gRPC protocol bindings are
internal to the driver. For example, after allocating an environment, connection,
and statement handle, a client can connect and execute SQL like this:

```cpp
SQLCHAR connection_string[] =
    "DRIVER={OJP};SERVER={localhost:1059};"
    "DATABASE={jdbc:h2:mem:example;DB_CLOSE_DELAY=-1};UID={sa};PWD=;";

SQLRETURN result = SQLDriverConnect(
    connection, nullptr, connection_string, SQL_NTS,
    nullptr, 0, nullptr, SQL_DRIVER_NOPROMPT);
if (!SQL_SUCCEEDED(result)) {
    // Retrieve the connection diagnostic with SQLGetDiagRec.
}

SQLCHAR create_table[] =
    "CREATE TABLE IF NOT EXISTS demo(id INTEGER PRIMARY KEY, label VARCHAR(100))";
SQLCHAR insert_row[] = "INSERT INTO demo(id, label) VALUES (1, 'example')";
SQLCHAR select_rows[] = "SELECT id, label FROM demo ORDER BY id";
SQLExecDirect(statement, create_table, SQL_NTS);
SQLExecDirect(statement, insert_row, SQL_NTS);
SQLExecDirect(statement, select_rows, SQL_NTS);

while (SQLFetch(statement) == SQL_SUCCESS) {
    SQLINTEGER id = 0;
    SQLCHAR label[101] = {};
    SQLGetData(statement, 1, SQL_C_SLONG, &id, sizeof(id), nullptr);
    SQLGetData(statement, 2, SQL_C_CHAR, label, sizeof(label), nullptr);
    // Use id and label.
}
```

Check every ODBC return code in application code and use `SQLGetDiagRec` on the
relevant handle to inspect errors.

## Integration tests

The H2, PostgreSQL, and SQL Server tests read backend connection details from
their respective fixtures:
[`h2_l1_connection.csv`](tests/testdata/h2_l1_connection.csv),
[`postgresql_l1_connection.csv`](tests/testdata/postgresql_l1_connection.csv),
and
[`sqlserver_l1_connection.csv`](tests/testdata/sqlserver_l1_connection.csv).
The H2 L7 multinode suite uses
[`h2_l7_connection.csv`](tests/testdata/h2_l7_connection.csv), whose file-backed
H2 database enables `AUTO_SERVER` so both OJP server processes see the same data.
They exercise the ODBC API through the Driver Manager against a running OJP
server and the respective database. The SQL Server fixture follows the OJP JDBC
driver's SQL Server test setup (`defaultdb`, `testuser`, and SQL Server 2022),
including the SQLSTATE expected for the L1 suite's invalid SQL. The Microsoft
JDBC driver reports `42S01` for this syntax error rather than the standard
`42000`, and OJP passes it through unchanged. Each test uses a unique
table per run and verifies connection readiness, prepared INSERT/SELECT/UPDATE,
DELETE, row counts, result values, empty results, SQL error diagnostics, and
session termination. The separate H2 L2 suite reuses the H2 L1 connection
fixture and covers typed parameters, generated identity retrieval, and basic
result metadata. The SQL Server L2 suite does the same using the SQL Server
fixture. The L2 suites are database-specific, so the shared L1 executable does
not need database-dependent branches.

The H2 and SQL Server L3 suites cover multi-block reads, result metadata,
end-of-result behavior, empty results, and closing a result before reusing the
statement. SQL Server L3 also selects multiple `VARBINARY` rows to exercise
row-by-row server streaming through `fetchNextRows`.

The H2 L5 suite additionally checks BLOB/CLOB input streams sent in multiple
ODBC chunks, multi-block LOB reads, UTF-8 character preservation, and SQL NULL
handling for both LOB types. H2 and SQL Server L6 verify temporary-table state
across SQL operations and committed transactions. Both L7 suites use at least
two OJP endpoints to check multinode CRUD, cached-pool reuse, and handling of an
unavailable endpoint. H2 L8 stops and
restarts the first configured OJP server to verify stateless operation failover,
pool-exhaustion and SQL-error handling, and reuse of the recovered server. Run
it only with disposable test servers; its PID file must identify the first
endpoint, and a supervisor must restart that server after it exits.

Start OJP using Java 25 and UTC, with each database reachable at the address in
its CSV fixture. SQL Server must have `defaultdb` and a `testuser` login with
database-owner permissions, as in the JDBC integration-test container setup.
Then build and run the tests:

```sh
cmake -S ojp-client-cpp-odbc -B ojp-client-cpp-odbc/build \
  -DGOOGLEAPIS_PROTO_DIR=/path/to/googleapis
cmake --build ojp-client-cpp-odbc/build
OJP_TEST_H2=true OJP_TEST_H2_ADDR=localhost:1059 \
OJP_TEST_H2_L7=true OJP_TEST_H2_L7_ADDRS=localhost:1059,localhost:1060 \
OJP_TEST_H2_L8=true OJP_TEST_H2_L8_ADDRS=localhost:1060,localhost:1059 \
OJP_TEST_H2_L8_FIRST_SERVER_PID_FILE=/tmp/ojp-server-2.pid \
OJP_TEST_POSTGRESQL=true OJP_TEST_POSTGRESQL_ADDR=localhost:1059 \
OJP_TEST_SQLSERVER=true OJP_TEST_SQLSERVER_ADDR=localhost:1059 \
OJP_TEST_SQLSERVER_L7_ADDRS=localhost:1059,localhost:1060 \
  ctest --test-dir ojp-client-cpp-odbc/build --output-on-failure
```

Each test is skipped when its corresponding enable variable is unset or false.
When enabled, missing endpoint configuration or unavailable required servers
fail the test instead of silently skipping it. Set
`OJP_TEST_H2_L7_UNAVAILABLE_ADDR` or
`OJP_TEST_SQLSERVER_L7_UNAVAILABLE_ADDR` to override the default unused endpoint
(`127.0.0.1:1`) in the corresponding L7 test.
The H2 L8 test requires a PID file for its first endpoint and restarts that
server automatically through the test supervisor.
The ODBC connection-string options `OJP.MULTINODE.RETRY.ATTEMPTS` and
`OJP.MULTINODE.RETRY.DELAY` configure stateless failover retries (defaults: 3
attempts, range 0–10; and 100 ms between attempts, range 0–60000).

The C++ ODBC H2 workflow job runs `OjpOdbcH2L1Integration` through
`OjpOdbcH2L8Integration`; L7 and L8 run against two OJP servers. The C++ ODBC SQL Server
workflow job runs `OjpOdbcSqlServerL1Integration` through
`OjpOdbcSqlServerL2Integration`, `OjpOdbcSqlServerL3Integration`,
`OjpOdbcSqlServerL4Integration`, `OjpOdbcSqlServerL5Integration`,
`OjpOdbcSqlServerL6Integration`, and `OjpOdbcSqlServerL7Integration`; L7 uses
two OJP server processes.
