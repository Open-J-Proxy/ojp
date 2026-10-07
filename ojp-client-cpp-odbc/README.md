# OJP C++ ODBC Client

This module provides an ANSI ODBC driver for applications that access an OJP
server from C++. It is an early implementation and targets **L1 (Basic
Connectivity + CRUD)** from the [client implementation levels](../documents/multi-language-client-spec/CLIENT_IMPLEMENTATION_LEVELS.md).
It uses the canonical `StatementService.proto` from `ojp-grpc-commons` and
communicates with the server over gRPC.

## Current implementation level assessment

| Assessment | Value |
|---|---|
| Highest implemented level | **L1** |
| Summary | ANSI ODBC connectivity and CRUD are implemented for one OJP server per connection. H2 and PostgreSQL real-server integration suites are available. |

### Current test-proven coverage by database

| Database | Highest achieved level (current tests) | Evidence |
|---|---:|---|
| **H2** | **L1** | `l1_integration_test.cpp` exercises ODBC → one OJP server → H2. |
| PostgreSQL | **L1** | `l1_integration_test.cpp` exercises ODBC → one OJP server → PostgreSQL. |
| MySQL | Not established | No database-specific integration suite in this module. |
| MariaDB | Not established | No database-specific integration suite in this module. |
| Oracle | Not established | No database-specific integration suite in this module. |
| SQL Server | Not established | No database-specific integration suite in this module. |
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
integers, floats, doubles, strings, and binary values. Disable application-side
connection pooling when using OJP.

Transactions, output parameters, wide-character ODBC entry points, metadata
discovery, LOBs, pagination, session affinity, multinode routing, health
checking, and failover are not implemented. Use autocommit mode. The client
uses one gRPC channel per ODBC connection and a process-stable client UUID.

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

## Using from a C++ ODBC application

Register the driver with the ODBC Driver Manager as `OJP`, then use
`SQLDriverConnect` with these connection fields:

- `SERVER`: OJP server `host:port`
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

## L1 integration tests

The H2 and PostgreSQL tests read backend connection details from
[`tests/testdata/h2_l1_connection.csv`](tests/testdata/h2_l1_connection.csv) and
[`tests/testdata/postgresql_l1_connection.csv`](tests/testdata/postgresql_l1_connection.csv),
then exercise the ODBC API through the Driver Manager against a running OJP
server and the respective database. Each test uses a unique table per run and
verifies connection readiness, prepared INSERT/SELECT/UPDATE, DELETE, row
counts, result values, empty results, SQL error diagnostics, and session
termination.

Start OJP using Java 25 and UTC, with PostgreSQL reachable at the address in the
CSV fixture. Then build and run both tests:

```sh
cmake -S ojp-client-cpp-odbc -B ojp-client-cpp-odbc/build \
  -DGOOGLEAPIS_PROTO_DIR=/path/to/googleapis
cmake --build ojp-client-cpp-odbc/build
OJP_TEST_H2=true OJP_TEST_H2_ADDR=localhost:1059 \
OJP_TEST_POSTGRESQL=true OJP_TEST_POSTGRESQL_ADDR=localhost:1059 \
  ctest --test-dir ojp-client-cpp-odbc/build --output-on-failure
```

Both tests are skipped when their corresponding `OJP_TEST_H2` or
`OJP_TEST_POSTGRESQL` variable is unset or false. When enabled, a missing
endpoint or unavailable server fails the test instead of silently skipping it.
