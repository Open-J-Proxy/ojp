# OJP C++ ODBC Client

This module provides an ANSI ODBC driver for applications that access an OJP
server from C++. It is an early implementation and targets **L1 (Basic
Connectivity + CRUD)** from the [client implementation levels](../documents/multi-language-client-spec/CLIENT_IMPLEMENTATION_LEVELS.md).
It uses the canonical `StatementService.proto` from `ojp-grpc-commons` and
communicates with the server over gRPC.

## Implementation level

| Item | Status |
|---|---|
| Target level | L1 |
| Highest implemented level | L1 |
| H2 integration coverage | Opt-in real-server test; this checkout has not run it |
| Endpoint support | One OJP server per ODBC connection |

L1 includes connection establishment and termination, query and update
execution, basic result columns and scalar values, input parameters for common
ODBC scalar types, and SQL error diagnostics. The implementation supports
`SQLDriverConnect`, `SQLPrepare`/`SQLExecute`, `SQLBindParameter` for input
parameters, `SQLExecDirect`, forward-only `SQLFetch`/`SQLGetData`, and affected
row counts.

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
cmake -S ojp-odbc-client-cpp -B ojp-odbc-client-cpp/build \
  -DGOOGLEAPIS_PROTO_DIR=/path/to/googleapis
cmake --build ojp-odbc-client-cpp/build
```

The CMake build generates C++ bindings from the shared OJP proto source in the
build directory; generated files are not checked in.

## Connect from an ODBC application

Register the driver with the ODBC Driver Manager as `OJP`, then use
`SQLDriverConnect` with these connection fields:

- `SERVER`: OJP server `host:port`
- `DATABASE`: the backend JDBC URL (quote values containing semicolons with
  ODBC braces)
- `UID` and `PWD`: backend credentials

For example:

```text
DRIVER={OJP};SERVER={localhost:1059};DATABASE={jdbc:h2:mem:example;DB_CLOSE_DELAY=-1};UID={sa};
```

The ODBC connection-string braces protect semicolons in the H2 URL; a literal
closing brace in a value is escaped by doubling it. `SQLConnect` with a DSN is
not implemented yet.

## H2 L1 integration test

The test follows the JDBC and Go real-server pattern: it reads the backend URL,
user, and password from
[`tests/testdata/h2_l1_connection.csv`](tests/testdata/h2_l1_connection.csv),
then exercises the ODBC API through the Driver Manager against a running OJP
server and H2. It uses a unique table per run and verifies connection
readiness, prepared INSERT/SELECT/UPDATE, DELETE, row counts, result values,
empty results, SQL error diagnostics, and session termination.

Start OJP using Java 25 and UTC. Then build and run the test:

```sh
OJP_TEST_H2=true OJP_TEST_H2_ADDR=localhost:1059 \
  cmake -S ojp-odbc-client-cpp -B ojp-odbc-client-cpp/build \
  -DGOOGLEAPIS_PROTO_DIR=/path/to/googleapis
cmake --build ojp-odbc-client-cpp/build
OJP_TEST_H2=true OJP_TEST_H2_ADDR=localhost:1059 \
  ctest --test-dir ojp-odbc-client-cpp/build --output-on-failure
```

The test is skipped when `OJP_TEST_H2` is unset or false. When enabled, a
missing endpoint or unavailable server fails the test instead of silently
skipping it.
