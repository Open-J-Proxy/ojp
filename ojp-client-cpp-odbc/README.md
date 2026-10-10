# OJP C++ ODBC Client

This module provides an ANSI ODBC driver for applications that access OJP
servers from C++.
It uses the canonical `StatementService.proto` from `ojp-grpc-commons` and
communicates with the server over gRPC.

## Supported levels by database

| Database | Highest supported level |
|---|---:|
| H2 | **L9** |
| PostgreSQL | **L4** |
| SQL Server | **L10** |
| MySQL | Not established |
| MariaDB | Not established |
| Oracle | Not established |
| DB2 | Not established |
| CockroachDB | Not established |

Level definitions: [client implementation levels](../documents/multi-language-client-spec/CLIENT_IMPLEMENTATION_LEVELS.md).

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
Use disposable test servers: H2 L8-L9 and SQL Server L8-L10 stop an OJP server.
PID files must identify the endpoint being stopped, and a supervisor must
restart that server.

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
