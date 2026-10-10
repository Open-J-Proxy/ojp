# OJP C++ ODBC Client

This module provides an ANSI ODBC driver for applications that access OJP
servers from C++. It currently provides **L1 for H2, PostgreSQL, and SQL Server**,
**L2-L3 for PostgreSQL**, **L2-L5 for H2 and SQL Server**, **L6 for H2 and SQL Server**, and
**L7-L9 for H2** and **L7-L10 for SQL Server** from the
[client implementation levels](../documents/multi-language-client-spec/CLIENT_IMPLEMENTATION_LEVELS.md).
It uses the canonical `StatementService.proto` from `ojp-grpc-commons` and
communicates with the server over gRPC.

## Current implementation level assessment

| Assessment | Value |
|---|---|
| Highest implemented level | **L9 for H2; L10 for SQL Server; L3 for PostgreSQL** |
| Summary | H2 and SQL Server add XA resource-manager operations through the OJP C API in `ojp_odbc_xa.h`. XA connections always connect with `isXA=true`, pin to one endpoint, and never reroute active XA operations. SQL Server L10 combines XA affinity with multinode failover and recovered-node reuse. |

### Current test-proven coverage by database

| Database | Highest achieved level (current tests) | Evidence |
|---|---:|---|
| **H2** | **L9** | L1-L9 integration suites passed, including stateless failover, pool-exhaustion safety, recovered-node reuse, and XA lifecycle/recovery in `h2_l9_integration_test.cpp`. |
| PostgreSQL | **L3** | Enabled PostgreSQL L1-L3 integration suites passed against PostgreSQL 17 and one OJP server. `l1_integration_test.cpp` covers connectivity and CRUD; `postgresql_l2_integration_test.cpp` adds typed parameters, statement variants, generated IDs, and metadata; `postgresql_l3_integration_test.cpp` adds multi-block results and cursor lifecycle. |
| SQL Server | **L10** | L1-L10 integration suites passed, including XA lifecycle/recovery in `sqlserver_l9_integration_test.cpp` and combined multinode/XA coverage in `sqlserver_l10_integration_test.cpp`. |
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

The H2, PostgreSQL, and SQL Server L3 suites retrieve 10,001 ordered rows through the
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

The SQL Server L7 suite covers multinode behavior with the SQL Server fixture,
including multi-endpoint CRUD, cached pool reuse, and an unavailable endpoint.
L8 adds failover/recovery checks and requires its first endpoint to be restartable
by a supervisor using a PID file. Health probes use the `connect` RPC every 5 seconds by default;
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

### PostgreSQL L2 coverage

The PostgreSQL L2 suite follows the JDBC driver's
`PostgresMultipleTypesIntegrationTest` and prepared-statement tests, using the
same standalone ODBC test format as H2 and SQL Server. It checks integer widths
(PostgreSQL uses `SMALLINT`, not `TINYINT`), booleans, floating-point values,
`NUMERIC` parameters and results, UTF-8 text, `BYTEA` (including embedded NUL,
large, empty, and NULL values), and date/time/timestamp values. Decimal struct
and decimal text bindings exercise BigDecimalWire, including negative values;
typed NULL bindings exercise the target JDBC type codes required by
`CLIENT_SPEC_AI.md` section 4.4.

PostgreSQL UUID and timezone-aware values use explicitly cast text parameters:
the ANSI ODBC client has no native UUID or offset temporal C binding. Java's
`java.sql` and `java.time` variants map to the same ODBC temporal structs.
Java-specific objects (`PGobject`, references, and SQL arrays) are not native
ODBC bindings. The suite instead round-trips JSON/JSONB as explicitly cast
text, validates JSON extraction operators and NULLs, and reads native UUID
results as text.
LOB/data-at-execution streams belong to L5 and are not claimed for PostgreSQL L2.
Generated identities are retrieved with PostgreSQL SQL, not a JDBC-style
`getGeneratedKeys()` API or session-dependent `currval`.
Basic metadata checks cover column names, counts, and scalar type inference;
complete JDBC descriptors, precision/scale, and empty-result type discovery
are not implemented.

### PostgreSQL L3 coverage

The PostgreSQL L3 suite follows the result iteration, metadata, and close/reuse
behavior of the JDBC `PostgresStatementExtensiveTests` and
`PostgresPreparedStatementExtensiveTests`, using the same
standalone ODBC format as H2 and SQL Server. It verifies 10,001 ordered rows
generated with `generate_series`, column labels, empty results, repeated
`SQL_NO_DATA`, partial cursor closure through `SQLFreeStmt` and `SQLCloseCursor`,
prepared-query reuse, and freeing an active statement. `BYTEA` results check
binary preservation, empty values, and SQL NULL handling across streamed blocks.

For `CLIENT_SPEC_AI.md` sections 4.3, 4.5.2, and 12.1, the shared driver consumes
every `executeQuery` response, updates session information, and closes the
server cursor with `callResource(RES_RESULT_SET, CALL_CLOSE)`. Rows are buffered
eagerly; closing an ODBC cursor discards the local result, not a live gRPC stream.
`fetchNextRows` is implemented for server-selected row-by-row mode and exercised
by SQL Server L3. PostgreSQL scalar and `BYTEA` results use streamed blocks, so
this suite does not claim database-specific pagination evidence or configurable
fetch sizes. PostgreSQL L4-L10 remain unestablished.

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
and client-side throttling are not implemented. XA operations use the same
session-affinity routing and do not retry or reroute after a branch has started.

## Conformance with `CLIENT_SPEC_AI.md`

The same driver code serves H2, PostgreSQL, and SQL Server, so these points
apply to all three databases.

Implemented rules:

| Spec rule | Implementation |
|---|---|
| 4.1.1 process-stable UUID v4 `clientUUID` | Generated once per process |
| 4.2 `ConnectionDetails` | Non-XA uses `isXA=false`; `OJP.XA=TRUE` connections use `isXA=true` and always make a connect RPC |
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
| L9 XA operations | `ojp_odbc_xa.h` exposes all ten XA RPCs; XA connections pin to their selected endpoint, and a failed active XA operation returns `XAER_RMFAIL` without rerouting |
| Section 3 transitions | Calls on a closed connection fail with `08003` without sending an RPC |

In row-by-row mode (SQL Server and DB2 results with binary or LOB columns),
the client pulls the remaining rows with `fetchNextRows`. Earlier versions
returned only the first row.

Spec rules outside the implemented levels or still incomplete:

- **L5 for PostgreSQL:** `createLob` and `readLob`.
- **L8:** full failover/recovery and connection redistribution.
- **Section 8:** client-side admission throttling.

## Build requirements

- CMake 3.20 or later, Ninja, and a C++17 compiler
- Protobuf and gRPC C++ development packages, including `protoc` and
  `grpc_cpp_plugin`
- ODBC development headers and an ODBC Driver Manager (unixODBC on Linux/macOS,
  Windows ODBC on Windows)
- An extracted source archive of [googleapis](https://github.com/googleapis/googleapis) to
  provide `google/type/date.proto` and `google/type/timeofday.proto`
- The full OJP source tree: the build also reads
  `ojp-grpc-commons/src/main/proto/StatementService.proto`

**CMake is the cross-platform build tool**, rather than Maven or a separate
shell script for each OS. The checked-in `release` preset runs the same
configure/build steps from Bash, PowerShell, or a Windows developer terminal.
It builds only the driver, with integration tests disabled. No Java runtime is
needed to compile the C++ driver; the **OJP server** requires Java 25 and UTC.

### Linux (Debian / Ubuntu)

Install the system development packages (use equivalent packages on other
distributions):

```bash
sudo apt-get update
sudo apt-get install build-essential cmake ninja-build \
  libprotobuf-dev protobuf-compiler libgrpc++-dev protobuf-compiler-grpc \
  unixodbc unixodbc-dev odbcinst
```

Download and extract the googleapis source archive separately, then, from the
OJP source root:

```bash
cd ojp-client-cpp-odbc
cmake --preset release -DGOOGLEAPIS_PROTO_DIR=/absolute/path/to/googleapis
cmake --build --preset release --parallel
cmake --install build/release --prefix "$PWD/install"
```

The installed driver is normally `install/lib/libojp_odbc.so` (some systems use
`lib64` or another library directory; see the installation output).

### macOS

Use Xcode Command Line Tools and Homebrew's unixODBC, **not a mixture of
unixODBC and iODBC**. Match the architecture of the application and libraries
(Apple Silicon arm64 or Intel x86_64).

```bash
xcode-select --install
brew install cmake ninja protobuf grpc unixodbc
cd ojp-client-cpp-odbc
cmake --preset release -DGOOGLEAPIS_PROTO_DIR=/absolute/path/to/googleapis \
  -DCMAKE_PREFIX_PATH="$(brew --prefix)" \
  -DODBC_INCLUDE_DIR="$(brew --prefix unixodbc)/include" \
  -DODBC_LIBRARY="$(brew --prefix unixodbc)/lib/libodbc.dylib"
cmake --build --preset release --parallel
cmake --install build/release --prefix "$PWD/install"
```

The driver is normally `install/lib/libojp_odbc.dylib`.

### Windows

Install Visual Studio Build Tools with **Desktop development with C++**, the
Windows SDK (ODBC headers/libraries), CMake, and Ninja. Run these commands in
an **x64 Native Tools developer PowerShell** so CMake can find MSVC.
Use an existing [vcpkg](https://learn.microsoft.com/vcpkg/get_started/get-started)
installation for gRPC and Protobuf, then extract the googleapis archive.
Use a current vcpkg baseline and retain its resolved dependency versions for
repeatable builds.

```powershell
# VCPKG_ROOT must point to your existing vcpkg installation.
& "$env:VCPKG_ROOT/vcpkg.exe" install grpc:x64-windows protobuf:x64-windows
Set-Location ojp-client-cpp-odbc
cmake --preset release "-DGOOGLEAPIS_PROTO_DIR=C:/sources/googleapis" "-DCMAKE_TOOLCHAIN_FILE=$env:VCPKG_ROOT/scripts/buildsystems/vcpkg.cmake" -DVCPKG_TARGET_TRIPLET=x64-windows
cmake --build --preset release --parallel
cmake --install build/release --prefix "$PWD/install"
```

The DLL is `install/bin/ojp_odbc.dll`; the import library is under `install/lib`.
Keep required gRPC/Protobuf dependency DLLs and the matching Visual C++ runtime
available to the application's loader (for example alongside the application).
Installation does not bundle third-party runtime libraries.

**Platform status:** Linux has integration suites in this repository. The
macOS and Windows instructions describe the platform build/registration path,
not a claim that those platforms or language wrappers have passed integration
tests. The preset selects no architecture itself; for a 32-bit application,
use a separate build directory, x86 compiler environment and matching dependency
triplet. Never load a 64-bit driver into a 32-bit process or vice versa.

### Custom builds and tests

If Ninja is unavailable, use CMake directly with your platform's generator.
For example, from the OJP root:

```bash
cmake -S ojp-client-cpp-odbc -B ojp-client-cpp-odbc/build/custom \
  -DGOOGLEAPIS_PROTO_DIR=/absolute/path/to/googleapis \
  -DCMAKE_BUILD_TYPE=Release -DBUILD_TESTING=OFF
cmake --build ojp-client-cpp-odbc/build/custom --config Release --parallel
cmake --install ojp-client-cpp-odbc/build/custom --config Release \
  --prefix /absolute/path/to/ojp-odbc
```

For multi-configuration generators (such as Visual Studio), `--config Release`
selects the build/install configuration. To build the integration executables,
use a **separate** build directory with `-DBUILD_TESTING=ON`; see
[Integration tests](#integration-tests). Failure-injection suites use POSIX
process facilities and are not a portable Windows test harness.

The CMake build generates C++ bindings from the shared OJP proto source in the
build directory; generated files are not checked in. If configuration cannot
find gRPC or Protobuf, supply `CMAKE_PREFIX_PATH` or a package-manager toolchain;
ensure `protoc`, its headers/libraries, and `grpc_cpp_plugin` come from compatible
installations.

Prebuilt binaries are not published yet. See the
[ODBC driver distribution analysis](../documents/analysis/ODBC_DRIVER_DISTRIBUTION_ANALYSIS.md)
for the proposed per-OS delivery plan.

### Register the installed driver

On Linux/macOS with unixODBC, run `odbcinst -j` to locate the manager's
configuration. Add a driver entry to `odbcinst.ini`, substituting the **absolute
installed library path**, not the build-directory path:

```ini
[OJP]
Description=Open J Proxy ODBC driver
Driver=/absolute/path/to/ojp-client-cpp-odbc/install/lib/libojp_odbc.so
```

Use `.dylib` on macOS. Alternatively save that entry in a file and run
`odbcinst -i -d -f /absolute/path/to/ojp-driver.ini` (permissions depend on the
configuration location). For an isolated, user-owned configuration, put the
entry in `/absolute/path/to/odbc-config/odbcinst.ini` and set
`ODBCSYSINI=/absolute/path/to/odbc-config` **before starting the application**.
Confirm registration with `odbcinst -q -d -n OJP`. This is driver registration,
not a DSN; the driver currently requires a DSN-less connection string.

On Windows, register `OJP` in the matching architecture's ODBC driver registry.
There is no installer or setup-dialog DLL yet. An administrator can import this
`.reg` template for a **64-bit** driver after replacing the path:

```text
Windows Registry Editor Version 5.00

[HKEY_LOCAL_MACHINE\SOFTWARE\ODBC\ODBCINST.INI\ODBC Drivers]
"OJP"="Installed"

[HKEY_LOCAL_MACHINE\SOFTWARE\ODBC\ODBCINST.INI\OJP]
"Driver"="C:\\OJP\\install\\bin\\ojp_odbc.dll"
```

For a 32-bit driver on 64-bit Windows, the corresponding entries are under
`HKEY_LOCAL_MACHINE\SOFTWARE\WOW6432Node\ODBC\ODBCINST.INI`.
The 64-bit ODBC Administrator is `%windir%\System32\odbcad32.exe`; the 32-bit
one is `%windir%\SysWOW64\odbcad32.exe`. Their Drivers tabs show registration;
do not expect the DSN configuration wizard to work without a setup DLL.

Check runtime dependencies with `ldd` on Linux, `otool -L` on macOS, or
`dumpbin /DEPENDENTS` in a Windows developer terminal. A registered driver that
cannot load usually has a wrong path, missing dependent libraries, or a
process/driver architecture mismatch. Keep dependency libraries installed;
`cmake --install` installs OJP artifacts only.

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

Start an OJP server with Java 25 and `-Duser.timezone=UTC`, provide its backend
JDBC driver JAR, and ensure it can reach the database. `SERVER` is the **OJP
endpoint**, not the database address; `DATABASE` is a backend JDBC URL, not
`jdbc:ojp[...]`. See the [server quick start](../README.md#quick-start).

ODBC applications link against the **Driver Manager**, not directly against
gRPC or `ojp_odbc`. Use ANSI APIs and UTF-8 text; do not compile this example
with `UNICODE` defined. Disable Driver Manager and framework connection pooling
before allocating the first environment. The example below is a complete H2
smoke test; save it as `example.cpp` and set `OJP_ODBC_CONNECTION_STRING` to
the DSN-less string above (use environment-supplied credentials for real databases):

```cpp
#include <sql.h>
#include <sqlext.h>
#include <cstdlib>
#include <iostream>
#include <stdexcept>
#include <string>

void check(SQLRETURN result, SQLSMALLINT kind, SQLHANDLE handle) {
    if (SQL_SUCCEEDED(result)) {
        return;
    }
    SQLCHAR state[6] = {};
    SQLCHAR message[1024] = {};
    SQLINTEGER native = 0;
    SQLSMALLINT length = 0;
    for (SQLSMALLINT i = 1;
         SQL_SUCCEEDED(SQLGetDiagRec(kind, handle, i, state, &native,
                                    message, sizeof(message), &length)); ++i) {
        std::cerr << state << ": " << message << '\n';
    }
    throw std::runtime_error("ODBC call failed");
}

int main() {
    SQLHENV env = SQL_NULL_HENV;
    SQLHDBC conn = SQL_NULL_HDBC;
    SQLHSTMT stmt = SQL_NULL_HSTMT;
    bool connected = false;
    int status = 0;
    try {
        const char* value = std::getenv("OJP_ODBC_CONNECTION_STRING");
        if (!value) {
            throw std::runtime_error("Set OJP_ODBC_CONNECTION_STRING");
        }
        std::string connectionString(value);
        check(SQLSetEnvAttr(SQL_NULL_HENV, SQL_ATTR_CONNECTION_POOLING,
                           reinterpret_cast<SQLPOINTER>(SQL_CP_OFF), 0),
              SQL_HANDLE_ENV, SQL_NULL_HENV);
        check(SQLAllocHandle(SQL_HANDLE_ENV, SQL_NULL_HANDLE, &env),
              SQL_HANDLE_ENV, env);
        check(SQLSetEnvAttr(env, SQL_ATTR_ODBC_VERSION,
                           reinterpret_cast<SQLPOINTER>(SQL_OV_ODBC3), 0),
              SQL_HANDLE_ENV, env);
        check(SQLAllocHandle(SQL_HANDLE_DBC, env, &conn), SQL_HANDLE_ENV, env);
        check(SQLDriverConnect(conn, nullptr,
                               reinterpret_cast<SQLCHAR*>(connectionString.data()),
                               SQL_NTS, nullptr, 0, nullptr, SQL_DRIVER_NOPROMPT),
              SQL_HANDLE_DBC, conn);
        connected = true;
        check(SQLAllocHandle(SQL_HANDLE_STMT, conn, &stmt), SQL_HANDLE_DBC, conn);
        SQLCHAR sql[] = "SELECT 1";
        check(SQLExecDirect(stmt, sql, SQL_NTS), SQL_HANDLE_STMT, stmt);
        for (;;) {
            SQLRETURN result = SQLFetch(stmt);
            if (result == SQL_NO_DATA) {
                break;
            }
            check(result, SQL_HANDLE_STMT, stmt);
            SQLINTEGER number = 0;
            SQLLEN indicator = 0;
            check(SQLGetData(stmt, 1, SQL_C_SLONG, &number, sizeof(number), &indicator),
                  SQL_HANDLE_STMT, stmt);
            if (indicator != SQL_NULL_DATA) {
                std::cout << number << '\n';
            }
        }
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        status = 1;
    }
    if (stmt != SQL_NULL_HSTMT) {
        SQLFreeHandle(SQL_HANDLE_STMT, stmt);
    }
    if (connected) {
        SQLDisconnect(conn);
    }
    if (conn != SQL_NULL_HDBC) {
        SQLFreeHandle(SQL_HANDLE_DBC, conn);
    }
    if (env != SQL_NULL_HENV) {
        SQLFreeHandle(SQL_HANDLE_ENV, env);
    }
    return status;
}
```

Compile and run on Linux:

```bash
c++ -std=c++17 example.cpp -o example -lodbc
export OJP_ODBC_CONNECTION_STRING='DRIVER={OJP};SERVER=localhost:1059;DATABASE={jdbc:h2:mem:example;DB_CLOSE_DELAY=-1};UID=sa;PWD=;'
./example
```

On macOS add `-I"$(brew --prefix unixodbc)/include"` and
`-L"$(brew --prefix unixodbc)/lib"` to the compiler command. On Windows use
`cl /EHsc /std:c++17 example.cpp odbc32.lib` in the matching developer terminal,
set `$env:OJP_ODBC_CONNECTION_STRING` in PowerShell, then run `.\example.exe`.
Expect `1` when the server is reachable. Use positional `?` placeholders and
`SQLBindParameter` for user data rather than interpolating it into SQL.

XA applications additionally include installed `ojp_odbc_xa.h`, set
`OJP.XA=TRUE`, and link/load the exported OJP XA functions with an `OjpXid`.
These are **OJP-specific extensions**, not automatically available through
standard Python, ADO.NET, or FireDAC local transaction APIs.

### Using other languages through ODBC

The driver implements advanced OJP capabilities, but it is **not a complete
ODBC API implementation**. It exports ANSI functions, not the `...W` Unicode
entry points. `SQLConnect`/DSN-only connections, catalog functions such as
`SQLTables`/`SQLColumns`, `SQLColAttribute`, `SQLGetStmtAttr`, and
`SQLMoreResults` are not implemented; `SQLGetInfo` covers only a subset.
Driver Manager ANSI/Unicode conversion does not establish wrapper compatibility.
Some wrappers request unsupported metadata even for a simple query.

| Application API | Bridge | Status / guide |
|---|---|---|
| Python DB-API | `pyodbc` with ANSI connect and UTF-8 encoding | Candidate bridge; [Python README](../ojp-client-python-dbapi/README.rst) |
| .NET ADO.NET | `System.Data.Odbc` | Unicode/metadata compatibility gaps; [ADO.NET README](../ojp-client-dotnet-ado-net/README.md) |
| PHP PDO | `PDO_ODBC` | Candidate bridge with persistent connections disabled; [PHP README](../ojp-client-php-pdo/README.md) |
| Ruby | Ruby ODBC binding | Binding-dependent; [Ruby README](../ojp-client-ruby-dbi/README.md) |
| Go `database/sql` | Third-party ODBC adapter | Platform/adapter-dependent; [Go README](../ojp-client-go-database-sql/README.md) |
| Dart / Drift | Custom FFI or a separate service | No standard drop-in ODBC Drift backend; [Dart README](../ojp-client-dart-drift/README.md) |
| Delphi FireDAC | Generic FireDAC ODBC driver | Experimental, not validated; [Delphi guide](../documents/guides/DELPHI_FIREDAC_ODBC.md) |

Treat these as integration paths to evaluate while the corresponding native
clients mature, **not production-certified substitutes**. Validate
connect/query/parameters/transactions/UTF-8/cleanup with your exact wrapper,
Driver Manager, OS, architecture, and database. Disable all application-side
pooling. The current driver uses plaintext gRPC: use a trusted/private network
or an externally secured deployment boundary. Never log credential-bearing
connection strings. See the [ebook integration chapter](../documents/ebook/part2-chapter7-framework-integration.md).

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
fixture. PostgreSQL L2-L3 reuse the PostgreSQL L1 fixture and enable/endpoint
variables. The L2 suites are database-specific, so the shared L1 executable does
not need database-dependent branches.

The H2, PostgreSQL, and SQL Server L3 suites cover multi-block reads, result metadata,
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
The H2 L9 suite uses two OJP servers and a shared file-backed H2 database. It
covers XA connection mode, resource-manager identity, two-phase commit and
recovery, rollback, one-phase commit, timeout operations, forget, and
`XAER_RMFAIL` without rerouting when the server hosting an active XA branch stops.
The SQL Server L9 suite follows the Java SQL Server XA reference coverage and
also verifies recovery, resource-manager identity, forget, and
`XAER_RMFAIL` without rerouting when the server hosting an active XA branch stops.
The L10 suite combines the L9 XA lifecycle with a multinode CRUD failover,
surviving-node availability, SQL error classification, and reuse of the restarted
OJP node. SQL Server L10 is a client target; the Java reference-client matrix
currently records SQL Server at L9.

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
OJP_TEST_H2_L9=true \
OJP_TEST_H2_L9_ADDRS=localhost:1059,localhost:1060 \
OJP_TEST_H2_L9_TARGET_PID_FILE=/tmp/ojp-server-2.pid \
OJP_TEST_POSTGRESQL=true OJP_TEST_POSTGRESQL_ADDR=localhost:1059 \
OJP_TEST_SQLSERVER=true OJP_TEST_SQLSERVER_ADDR=localhost:1059 \
OJP_TEST_SQLSERVER_L7_ADDRS=localhost:1059,localhost:1060 \
OJP_TEST_SQLSERVER_L8=true OJP_TEST_SQLSERVER_L8_ADDRS=localhost:1060,localhost:1059 \
OJP_TEST_SQLSERVER_L8_FIRST_SERVER_PID_FILE=/tmp/ojp-server-2.pid \
OJP_TEST_SQLSERVER_L9=true \
OJP_TEST_SQLSERVER_L9_ADDRS=localhost:1060,localhost:1059 \
OJP_TEST_SQLSERVER_L9_TARGET_PID_FILE=/tmp/ojp-server-2.pid \
OJP_TEST_SQLSERVER_L10=true \
OJP_TEST_SQLSERVER_L10_ADDRS=localhost:1060,localhost:1059 \
OJP_TEST_SQLSERVER_L10_TARGET_PID_FILE=/tmp/ojp-server-2.pid \
  ctest --test-dir ojp-client-cpp-odbc/build --output-on-failure
```

Each test is skipped when its corresponding enable variable is unset or false.
When enabled, missing endpoint configuration or unavailable required servers
fail the test instead of silently skipping it. Set
`OJP_TEST_H2_L7_UNAVAILABLE_ADDR` or
`OJP_TEST_SQLSERVER_L7_UNAVAILABLE_ADDR` to override the default unused endpoint
(`127.0.0.1:1`) in the corresponding L7 test.
The H2 and SQL Server L8 tests require a PID file for their first endpoint and
restart that server automatically through the test supervisor. Their endpoint
lists put the restartable server first. Configure that server with
`ojp.server.maxConcurrentRequests=1` for the pool-exhaustion assertion.
The ODBC connection-string options `OJP.MULTINODE.RETRY.ATTEMPTS` and
`OJP.MULTINODE.RETRY.DELAY` configure stateless failover retries (defaults: 3
attempts, range 0–10; and 100 ms between attempts, range 0–60000).

The C++ ODBC PostgreSQL workflow job runs `OjpOdbcPostgreSqlL1Integration`,
`OjpOdbcPostgreSqlL2Integration`, and `OjpOdbcPostgreSqlL3Integration` against
PostgreSQL and one OJP server.
The C++ ODBC H2 workflow job runs `OjpOdbcH2L1Integration` through
`OjpOdbcH2L9Integration`; L7-L9 run against two OJP servers. L9 requires its
second endpoint's PID file and stops that server during the active-XA affinity
assertion. The C++ ODBC SQL Server
workflow job runs `OjpOdbcSqlServerL1Integration` through
`OjpOdbcSqlServerL2Integration`, `OjpOdbcSqlServerL3Integration`,
`OjpOdbcSqlServerL4Integration`, `OjpOdbcSqlServerL5Integration`,
`OjpOdbcSqlServerL6Integration`, `OjpOdbcSqlServerL7Integration`, and
`OjpOdbcSqlServerL8Integration`, `OjpOdbcSqlServerL9Integration`, and
`OjpOdbcSqlServerL10Integration`; L7-L10 use two OJP server processes. L8-L10
stop the first endpoint and rely on the workflow supervisor to restart it. L9
and L10 verify that active XA work does not fail over to the other endpoint;
L10 also exercises stateless failover and recovered-node reuse in the same suite.
