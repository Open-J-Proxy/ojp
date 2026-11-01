# OJP C++ ODBC Client

This module provides an ANSI and Unicode ODBC 3.x driver for applications that
access OJP servers from C++.
It uses the canonical `StatementService.proto` from `ojp-grpc-commons` and
communicates with the server over gRPC.
[ODBC_API_COVERAGE.md](ODBC_API_COVERAGE.md) lists the implemented ODBC 3.x
functions and the remaining work.

## Supported levels by database

| Database | Highest supported level |
|---|---:|
| H2 | **L9** |
| PostgreSQL | **L10** |
| SQL Server | **L10** |
| MySQL | **L10** |
| MariaDB | **L10** |
| Oracle | Not established |
| DB2 | Not established |
| CockroachDB | Not established |

### MariaDB streamed CLOB limitation

Large multi-chunk CLOB writes containing supplementary Unicode characters
(such as emoji) can be corrupted by the current interaction between OJP's
positioned CLOB writes and MariaDB Connector/J. The MariaDB L5 large-payload
round trip therefore uses the same ASCII `MEDIUMTEXT` and `MEDIUMBLOB` payloads
as the JDBC reference test; small UTF-8 CLOB round trips remain covered. Large
supplementary-Unicode CLOB round trips are not currently supported.

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

On Windows the build also produces the DSN setup DLL `install/bin/ojp_odbc_setup.dll`
(see [Register the installed driver](#register-the-installed-driver)). Disable it with
`-DOJP_ODBC_BUILD_SETUP=OFF`; it is never built on other platforms. Both DLLs link
`odbccp32.lib` together with `legacy_stdio_definitions.lib`, which the modern
Universal CRT requires for that import library.

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
not a DSN; see [Data sources (DSN)](#data-sources-dsn) to define one in `odbc.ini`.
The DSN support needs the unixODBC/iODBC `odbcinst` library at build time; CMake
warns and builds a DSN-less driver when it is missing.

On Windows, register `OJP` in the matching architecture's ODBC driver registry.
The [Windows installer](installer/windows/README.md) builds an Inno Setup installer
(or runs `install.ps1`) that copies the 32-bit and 64-bit drivers and writes these
entries. To register by hand, an administrator can import this `.reg` template for a
**64-bit** driver after replacing the paths; `Setup` enables the DSN dialog:

```text
Windows Registry Editor Version 5.00

[HKEY_LOCAL_MACHINE\SOFTWARE\ODBC\ODBCINST.INI\ODBC Drivers]
"OJP"="Installed"

[HKEY_LOCAL_MACHINE\SOFTWARE\ODBC\ODBCINST.INI\OJP]
"Driver"="C:\\OJP\\install\\bin\\ojp_odbc.dll"
"Setup"="C:\\OJP\\install\\bin\\ojp_odbc_setup.dll"
```

For a 32-bit driver on 64-bit Windows, the corresponding entries are under
`HKEY_LOCAL_MACHINE\SOFTWARE\WOW6432Node\ODBC\ODBCINST.INI`.
The 64-bit ODBC Administrator is `%windir%\System32\odbcad32.exe`; the 32-bit
one is `%windir%\SysWOW64\odbcad32.exe`. With `Setup` registered, **Add** and
**Configure** on the User DSN / System DSN tabs open the OJP dialog, which edits
the DSN fields, TLS options and offers **Test Connection**. The password typed for
the test is never saved.

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
closing brace in a value is escaped by doubling it.

### Data sources (DSN)

A DSN stores every connection field except `PWD`, which is never read from or
written to `ODBC.INI`. Connect with `DSN=name;UID=...;PWD=...` through
`SQLDriverConnect`, or with `SQLConnect(dbc, "name", ..., uid, ..., pwd, ...)`.
Fields given explicitly in the connection string override the DSN. An unknown
DSN fails with SQLSTATE `IM002`.

On Windows create the DSN with the ODBC Administrator (see above). With unixODBC,
add it to `odbc.ini` (`odbcinst -j` shows the location):

```ini
[OjpExample]
Driver=OJP
Description=Example OJP data source
SERVER=localhost:1059
DATABASE=jdbc:h2:mem:example;DB_CLOSE_DELAY=-1
UID=sa
```

Stored keys: `Description`, `SERVER`, `DATABASE`, `UID`,
`OJP.DATASOURCE.NAME`, `OJP.XA`, `OJP.LOADAWARE.SELECTION.ENABLED`,
`OJP.MULTINODE.RETRY.ATTEMPTS`, `OJP.MULTINODE.RETRY.DELAY` and the
`OJP.CLIENT.TLS.*` keys described below.

Start an OJP server with Java 25 and `-Duser.timezone=UTC`, provide its backend
JDBC driver JAR, and ensure it can reach the database. `SERVER` is the **OJP
endpoint**, not the database address; `DATABASE` is a backend JDBC URL, not
`jdbc:ojp[...]`. See the [server quick start](../README.md#quick-start).

### TLS and mutual TLS

Plaintext is the default for compatibility. Enable TLS for connections outside
a trusted/private network, and configure the OJP server to require client
certificates for mutual TLS.

| Connection option | Environment fallback | Value |
|---|---|---|
| `OJP.CLIENT.TLS.ENABLED` | `OJP_CLIENT_TLS_ENABLED` | Enable verified TLS |
| `OJP.CLIENT.TLS.ROOTCERT` | `OJP_CLIENT_TLS_ROOTCERT` | PEM file containing trusted server certificates / CA roots; omitted uses gRPC's default roots |
| `OJP.CLIENT.TLS.CERT` | `OJP_CLIENT_TLS_CERT` | PEM client certificate chain |
| `OJP.CLIENT.TLS.KEY` | `OJP_CLIENT_TLS_KEY` | PEM unencrypted client private key |

Connection options override environment values, including explicitly empty
values. Supply both client certificate and private key, or neither for
server-authenticated TLS. TLS credential options require TLS to be enabled.
Unreadable/empty credential files and invalid configuration fail with
`HY024`; connection/handshake failures return `08001`. Server certificate
trust and endpoint hostname verification remain enabled; there is no insecure
certificate-verification bypass or plaintext fallback. The same credentials
apply to every endpoint, including health probes and recovered nodes.

```text
DRIVER={OJP};SERVER={localhost:1059,localhost:1060};DATABASE={jdbc:postgresql://localhost:5432/defaultdb};OJP.CLIENT.TLS.ENABLED=true;OJP.CLIENT.TLS.ROOTCERT={/secure/server-ca.pem};OJP.CLIENT.TLS.CERT={/secure/client-chain.pem};OJP.CLIENT.TLS.KEY={/secure/client-key.pem};
```

Add backend credentials through your application's secret configuration.
Restrict private-key file permissions to the client account. TLS options are
local driver configuration, not backend JDBC properties. This secures the
application-to-OJP hop; configure database TLS separately for the OJP-to-database
hop.

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

The driver implements the common connection, statement, transaction, result,
and metadata paths, but is **not a complete ODBC API implementation**. The
supported functions include `SQLDriverConnect[W]`, `SQLConnect[W]`,
`SQLExecDirect[W]`, `SQLPrepare[W]`, `SQLNumParams`, `SQLBindParameter`, `SQLBindCol`,
`SQLGetData`, `SQLTables[W]`, `SQLColumns[W]`, `SQLPrimaryKeys[W]`,
`SQLForeignKeys[W]`, `SQLGetTypeInfo`, `SQLDescribeCol[W]`,
`SQLColAttribute[W]`, `SQLGetInfo[W]`, `SQLGetFunctions`, and
`SQLGetDiagRec[W]`. The W entry points share their ODBC function IDs with the
ANSI forms in `SQLGetFunctions`.

#### Unicode encoding and lengths

ANSI `SQLCHAR` text is treated as UTF-8; the driver does not convert from the
process locale or Windows code page. W entry points convert between UTF-8 and
the platform's `SQLWCHAR` representation (UTF-16 code units where
`sizeof(SQLWCHAR) == 2`, otherwise UTF-32 code units). Invalid wide sequences
fail with SQLSTATE `22018`. Use `SQL_C_WCHAR` with `SQL_WCHAR`, `SQL_WVARCHAR`,
or `SQL_WLONGVARCHAR` for Unicode parameters and results.

String lengths passed to W connection, statement, and catalog functions count
`SQLWCHAR` units; `SQL_NTS` is supported. `SQLBindParameter`, `SQLBindCol`, and
`SQLGetData` buffer lengths and indicators for `SQL_C_WCHAR` are byte counts.
Output buffers are terminated when they have room for a terminator, and
truncation returns `SQL_SUCCESS_WITH_INFO` with SQLSTATE `01004`. Applications
should size wide buffers using `sizeof(SQLWCHAR)`, not `sizeof(wchar_t)`.

Example prepared Unicode query (the input buffer includes a trailing zero, but
the indicator reports only the payload bytes):

```cpp
SQLWCHAR sql[] = { 'S','E','L','E','C','T',' ','?','\0' };
SQLWCHAR text[] = { 0x6771, 0x4eac, 0 };
SQLLEN text_bytes = 2 * sizeof(SQLWCHAR);
SQLPrepareW(statement, sql, SQL_NTS);
SQLBindParameter(statement, 1, SQL_PARAM_INPUT, SQL_C_WCHAR, SQL_WVARCHAR,
                 32, 0, text, sizeof(text), &text_bytes);
SQLExecute(statement);
```

For a Unicode connection, build the same `DRIVER`, `SERVER`, `DATABASE`, `UID`,
and `PWD` fields in a `SQLWCHAR` buffer and pass it to `SQLDriverConnectW`
(connection-string syntax is unchanged). The earlier ANSI example shows the
corresponding `SQLDriverConnect` path.

Metadata and transaction calls use the regular ODBC statement/connection
handles:

```cpp
SQLSetConnectAttr(connection, SQL_ATTR_AUTOCOMMIT,
                  reinterpret_cast<SQLPOINTER>(SQL_AUTOCOMMIT_OFF), 0);
SQLCHAR table_type[] = "TABLE";
SQLTables(statement, nullptr, 0, nullptr, 0, table_name, SQL_NTS,
          table_type, SQL_NTS);
while (SQLFetch(statement) == SQL_SUCCESS) {
    // Read TABLE_NAME with SQLGetData(statement, 3, SQL_C_CHAR, ...).
}
SQLEndTran(SQL_HANDLE_DBC, connection, SQL_COMMIT);
```

Use `SQLDriverConnect` or `SQLDriverConnectW` for DSN-less connections, and
`SQLConnect`/`SQLConnectW` with a DSN name (see [Data sources (DSN)](#data-sources-dsn)).
Do not pass a `SERVER=...;DATABASE=...` options string as the `SQLConnect` server
name: the Driver Manager interprets that argument as a DSN name and may reject it
before calling the driver.

#### Metadata and known limitations

`SQLTables`, `SQLColumns`, `SQLPrimaryKeys`, `SQLForeignKeys`, and
`SQLGetTypeInfo` call the connected JDBC driver's `DatabaseMetaData` through
OJP Server, so catalog discovery does not embed database-specific SQL. Their
result sets can be fetched with the normal statement APIs. `SQLGetInfo` reports
the backend product/transaction information and a supported subset of ODBC
capabilities; unrecognized information types return `HYC00`.

`SQLDescribeCol` and `SQLColAttribute` can report the result label and infer a
limited type/size from returned values. The current gRPC query result does not
carry the complete JDBC `ResultSetMetaData`, so these functions cannot promise
the database's declared precision, scale, nullability, or base-table identity
for arbitrary query expressions. Use `SQLColumns` for authoritative declared
table-column metadata. Catalog functions not listed above (including
`SQLStatistics`, `SQLProcedures`, `SQLSpecialColumns`, and privilege catalogs),
and `SQLGetStmtAttr` remain unsupported. A Driver Manager or
language wrapper may require one of these functions; verify its exact calls
with the target Driver Manager and database.

Bindings with `SQL_PARAM_OUTPUT` or `SQL_PARAM_INPUT_OUTPUT` (including the
return value of `{? = CALL proc(?)}`) execute through the backend JDBC
`CallableStatement`; output values are written to the bound buffers after
`SQLExecute`/`SQLExecDirect`. Streamed output parameters, LOB or
data-at-execution inputs in the same call, and output parameters combined with
`SQL_ATTR_PARAMSET_SIZE` > 1 return `HYC00`. `SQLSetDescField` accepts
`SQL_DESC_NAME` on the implementation parameter descriptor and ignores it,
because parameters are always bound by position; other descriptor fields return
`HYC00`.

A procedure call (`EXEC`/`EXECUTE`/`CALL`/`{CALL ...}`) or a statement whose SQL
contains a top-level `;` followed by more code and a
`SELECT`, `EXEC`/`EXECUTE`, `OUTPUT`, or `RETURNING` keyword is treated as a
multi-result batch: it runs through a backend JDBC `CallableStatement` that
stays open while results remain, so `SQLMoreResults` advances to the next result
set (update counts in between are skipped) and returns `SQL_NO_DATA` at the end.
`SQLCloseCursor`, `SQLFreeStmt(SQL_CLOSE)`, re-execution, catalog calls, and
freeing the statement close the pending results. Batches without a top-level `;`
(for example `IF ... SELECT ... ELSE SELECT ...`), batches with LOB or
data-at-execution parameters, and output-parameter calls expose only the first
result; `SQLMoreResults` then returns `SQL_NO_DATA`. With
`SQL_ATTR_PARAMSET_SIZE` > 1, only the results of the last parameter row remain
available.

The L1 ODBC integration suite exercises ANSI and Unicode connection/statement
paths plus catalog discovery against the existing H2, PostgreSQL, MariaDB/MySQL,
and SQL Server fixtures. Tests require a running OJP Server and database;
disabled database suites skip unless their existing `OJP_TEST_*` variables are
enabled. Driver Manager ANSI/Unicode conversion alone does not establish
wrapper compatibility.

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
pooling. Enable [TLS/mTLS](#tls-and-mutual-tls) or use a trusted/private network
or an externally secured deployment boundary. Never log credential-bearing
connection strings. See the [ebook integration chapter](../documents/ebook/part2-chapter7-framework-integration.md).

## Integration tests

The H2, PostgreSQL, MariaDB, MySQL, and SQL Server tests read backend connection details from
their respective fixtures:
[`h2_l1_connection.csv`](tests/testdata/h2_l1_connection.csv),
[`postgresql_l1_connection.csv`](tests/testdata/postgresql_l1_connection.csv),
[`mariadb_l1_connection.csv`](tests/testdata/mariadb_l1_connection.csv),
and
[`sqlserver_l1_connection.csv`](tests/testdata/sqlserver_l1_connection.csv).
The H2 L7 multinode suite uses
[`h2_l7_connection.csv`](tests/testdata/h2_l7_connection.csv), whose file-backed
H2 database enables `AUTO_SERVER` so both OJP server processes see the same data.
Every integration executable runs its original assertions sequentially for every
CSV record, with fresh ODBC handles and cleanup for each connection. Records have
three fields: JDBC URL, username, and password. Quoted commas, doubled quotes,
CRLF, and quoted multiline fields are supported. Empty lines are ignored; empty
input and malformed nonempty records fail before contacting a server. Reports
identify the physical starting row and an allowlisted backend label only.
Raw driver diagnostics are not logged because they can reformat credentials.
Failures stop the suite immediately.
Disabled suites return 77 without opening the CSV or contacting a server.

MySQL and MariaDB share all ten MariaDB test executables and the
`mariadb_l1_connection.csv` fixture, which contains both JDBC URLs.
The existing `OJP_TEST_MARIADB*` flags enable both records. CTest registers only
`OjpOdbcMariaDBL1Integration` through `OjpOdbcMariaDBL10Integration`; there are no
separate MySQL registrations or flags. Main CI starts MariaDB 10.11 and MySQL 8.4
simultaneously and runs these suites once.
The MySQL row uses port 3306; the MariaDB row uses port 3307. Both require
`defaultdb` and a disposable `testuser` account. The MySQL row disables database TLS
and allows public-key retrieval for local tests only; do not use these settings
for production connections. Supply each backend's JDBC driver to OJP.
For MySQL L9/L10 recovery scans, grant the disposable test account
`XA_RECOVER_ADMIN` on `*.*` in addition to its privileges on `defaultdb`;
Main CI applies this grant automatically.
The shared MariaDB/MySQL L7-L8 suites require two OJP endpoints that can both
reach each database in the shared fixture. L8 stops and recovers its first
endpoint, so that server must be supervised by a restart loop and its PID file
must identify the server process. Configure it with
`ojp.server.maxConcurrentRequests=1` for the pool-exhaustion assertion.
For L9/L10, restart it with the default unlimited concurrency (`0`); the L8-only
limit can reject concurrent health probes and XA calls. Main CI makes this
transition between L8 and L9 while retaining the same restart supervisor.
The PostgreSQL L7-L9 suites use the PostgreSQL fixture and require two OJP endpoints
connected to the same PostgreSQL database. PostgreSQL L8 stops the first endpoint,
so its PID file must identify the server process that a supervisor will restart.
Configure that server with `ojp.server.maxConcurrentRequests=1` for the pool-exhaustion
assertion. PostgreSQL L9 exercises XA, including two-phase commit and prepared
transaction recovery, so configure PostgreSQL with `max_prepared_transactions > 0`.
Use disposable test servers: H2 L8-L9, PostgreSQL L8-L9, and MariaDB/MySQL and SQL Server L8-L10 stop an OJP server.
PID files must identify the endpoint being stopped, and a supervisor must
restart that server.
L8–L10 wait for the stopped server's new PID and a successful readiness query on
that endpoint, not a fixed sleep. L9 checks XA node loss before waiting, then
waits before continuing to the next record. Run restart suites sequentially,
not with parallel CTest jobs.
Execution deadlines apply separately to each record (180 seconds; 120 for H2
L8), so CSV files can contain any number of connections without exhausting a
single suite-wide CTest timeout. A timed-out record reports its row and
terminates the test process immediately, as a CTest timeout would; subsequent
records cannot hide it.

Start OJP using Java 25 and UTC, with each database reachable at the address in
its CSV fixture. SQL Server must have `defaultdb` and a `testuser` login with
database-owner permissions, as in the JDBC integration-test container setup.
For L10, both OJP servers must require trusted client certificates, including
after supervisor restarts. Generate disposable credentials with the same
fixture used by the [JDBC mTLS test](../ojp-jdbc-driver/src/test/java/org/openjproxy/jdbc/MtlsIntegrationTest.java).
The generator requires Java 25's `keytool` and the OpenSSL command-line tool.

```sh
bash scripts/generate-jdbc-mtls-test-certificates.sh /tmp/ojp-odbc-mtls-certs
export OJP_CLIENT_TLS_ENABLED=true
export OJP_CLIENT_TLS_ROOTCERT=/tmp/ojp-odbc-mtls-certs/server-cert.pem
export OJP_CLIENT_TLS_CERT=/tmp/ojp-odbc-mtls-certs/client-cert.pem
export OJP_CLIENT_TLS_KEY=/tmp/ojp-odbc-mtls-certs/client-key.pem
export OJP_TEST_TLS_UNTRUSTED_CERT=/tmp/ojp-odbc-mtls-certs/untrusted-client-cert.pem
export OJP_TEST_TLS_UNTRUSTED_KEY=/tmp/ojp-odbc-mtls-certs/untrusted-client-key.pem
```

Start each Java 25 server with UTC and these additional JVM properties
(test-store password: `ojp-test-only`; never reuse these credentials outside tests):

```text
-Dojp.server.tls.enabled=true
-Dojp.server.tls.keystore.path=/tmp/ojp-odbc-mtls-certs/server-keystore.p12
-Dojp.server.tls.keystore.******
-Dojp.server.tls.keystore.type=PKCS12
-Dojp.server.tls.truststore.path=/tmp/ojp-odbc-mtls-certs/server-truststore.p12
-Dojp.server.tls.truststore.******
-Dojp.server.tls.truststore.type=PKCS12
-Dojp.server.tls.clientAuthRequired=true
```

The generated server certificate covers `localhost` and `127.0.0.1`.
The L10 suites authenticate and query every endpoint, reject missing and
untrusted client certificates, then run the combined failover, recovery,
sticky-session, SQL-error and XA scenarios with the trusted identity.
The shared MariaDB suite runs both MySQL and MariaDB CSV rows. Main CI enables
mTLS for all PostgreSQL, MariaDB/MySQL and SQL Server suites, not just L10.
Enabled L10 suites fail if TLS settings or negative-test credentials are missing.

Then build and run the tests (omit L10 flags and TLS exports when deliberately
running lower-level suites against plaintext servers):

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
OJP_TEST_POSTGRESQL_L7=true \
OJP_TEST_POSTGRESQL_L7_ADDRS=localhost:1059,localhost:1060 \
OJP_TEST_POSTGRESQL_L8=true \
OJP_TEST_POSTGRESQL_L8_ADDRS=localhost:1060,localhost:1059 \
OJP_TEST_POSTGRESQL_L8_FIRST_SERVER_PID_FILE=/tmp/ojp-server-2.pid \
OJP_TEST_POSTGRESQL_L9=true \
OJP_TEST_POSTGRESQL_L9_ADDRS=localhost:1060,localhost:1059 \
OJP_TEST_POSTGRESQL_L9_TARGET_PID_FILE=/tmp/ojp-server-2.pid \
OJP_TEST_POSTGRESQL_L10=true \
OJP_TEST_POSTGRESQL_L10_ADDRS=localhost:1060,localhost:1059 \
OJP_TEST_POSTGRESQL_L10_TARGET_PID_FILE=/tmp/ojp-server-2.pid \
OJP_TEST_MARIADB=true OJP_TEST_MARIADB_ADDR=localhost:1059 \
OJP_TEST_MARIADB_L7=true \
OJP_TEST_MARIADB_L7_ADDRS=localhost:1059,localhost:1060 \
OJP_TEST_MARIADB_L8=true \
OJP_TEST_MARIADB_L8_ADDRS=localhost:1060,localhost:1059 \
OJP_TEST_MARIADB_L8_FIRST_SERVER_PID_FILE=/tmp/ojp-server-2.pid \
OJP_TEST_MARIADB_L9=true \
OJP_TEST_MARIADB_L9_ADDRS=localhost:1060,localhost:1059 \
OJP_TEST_MARIADB_L9_TARGET_PID_FILE=/tmp/ojp-server-2.pid \
OJP_TEST_MARIADB_L10=true \
OJP_TEST_MARIADB_L10_ADDRS=localhost:1060,localhost:1059 \
OJP_TEST_MARIADB_L10_TARGET_PID_FILE=/tmp/ojp-server-2.pid \
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
`OJP_TEST_H2_L7_UNAVAILABLE_ADDR`,
`OJP_TEST_MARIADB_L7_UNAVAILABLE_ADDR`,
`OJP_TEST_POSTGRESQL_L7_UNAVAILABLE_ADDR`, or
`OJP_TEST_SQLSERVER_L7_UNAVAILABLE_ADDR` to override the default unused endpoint
(`127.0.0.1:1`) in the corresponding L7 test.
The H2, PostgreSQL, MariaDB, MySQL, and SQL Server L8 tests require a PID file for their first endpoint and
restart that server automatically through the test supervisor. The PostgreSQL,
MariaDB, MySQL, and SQL Server L9/L10 tests also require a PID file for their first
endpoint, which must be restarted by a supervisor. Their endpoint lists put the
restartable server first.
H2 L9 instead puts the surviving server first and the restartable XA server
last; its target PID file must identify that last endpoint.
Configure the L8 server with `ojp.server.maxConcurrentRequests=1` for the pool-exhaustion assertion.
The ODBC connection-string options `OJP.MULTINODE.RETRY.ATTEMPTS` and
`OJP.MULTINODE.RETRY.DELAY` configure stateless failover retries (defaults: 3
attempts, range 0–10; and 100 ms between attempts, range 0–60000).

`OJP.LOB.CHUNK.SIZE` (connection string or DSN; fallback env `OJP_LOB_CHUNK_SIZE`) sets the
block size in bytes used to upload LOB parameters (default 4128768 = 4 MiB - 64 KiB, range
65536-268435456). The server writes each block with `Blob.setBytes`, which re-copies the whole
value on SQL Server, so larger blocks make big uploads much faster. Each block must fit in the
server's `ojp.server.maxRequestSize` (default 4 MiB): raise both together, keeping ~64 KiB of
headroom. Execution, fetch and LOB streams have no client deadline unless the application sets
`SQL_ATTR_QUERY_TIMEOUT`; the client accepts responses of any size.
