# OJP .NET ADO.NET Client

The .NET client is an ADO.NET provider that communicates with `ojp-server` over gRPC. It targets **L1: Basic Connectivity + CRUD** for one OJP server; an opt-in H2 integration suite is included. It is an early client implementation, not a general-purpose or production-ready .NET database provider.

## Use the provider

```csharp
using System.Data.Common;
using Ojp.Client;

var connectionString = new OjpConnectionStringBuilder
{
    OjpUrl = "jdbc:ojp[localhost:1059]_jdbc:h2:mem:sample;DB_CLOSE_DELAY=-1",
    UserID = "sa",
    Password = ""
}.ConnectionString;

using DbConnection connection = new OjpConnection(connectionString);
connection.Open();

using var create = connection.CreateCommand();
create.CommandText = "CREATE TABLE items (id INT PRIMARY KEY, name VARCHAR(100))";
create.ExecuteNonQuery();

using var insert = connection.CreateCommand();
insert.CommandText = "INSERT INTO items (id, name) VALUES (?, ?)";
insert.Parameters.Add(new OjpParameter { Value = 1 });
insert.Parameters.Add(new OjpParameter { Value = "sample" });
insert.ExecuteNonQuery();

using var query = connection.CreateCommand();
query.CommandText = "SELECT id, name FROM items WHERE id=?";
query.Parameters.Add(new OjpParameter { Value = 1 });
using DbDataReader reader = query.ExecuteReader();
while (reader.Read())
{
    Console.WriteLine($"{reader.GetInt32(0)}: {reader.GetString(1)}");
}
```

`OjpUrl` contains the complete OJP URL: `jdbc:ojp[host:port]_backend-jdbc-url`. The connection string builder quotes the URL when required, so backend JDBC options containing semicolons remain part of that URL. `OjpConnection` can also be created empty and assigned a connection string before `Open()`.

## Current Implementation Level Assessment

| Assessment | Value |
|---|---|
| Highest implemented level in this module | **L1** |
| Summary | A public single-endpoint ADO.NET API and an opt-in H2 real-server L1 suite are included; CI has not yet confirmed the H2 suite. |

### Current Test-Proven Coverage by Database (`ojp-client-dotnet-ado-net`)

| Database | Highest achieved level (current tests) | Evidence highlights |
|---|---:|---|
| **H2** | **Not yet confirmed** | The opt-in `H2L1IntegrationTests` suite exercises ADO.NET → one OJP server → H2. |
| **PostgreSQL** | **Not established** | No database-specific integration suite in this module yet. |
| **MySQL** | **Not established** | No database-specific integration suite in this module yet. |
| **MariaDB** | **Not established** | No database-specific integration suite in this module yet. |
| **Oracle** | **Not established** | No database-specific integration suite in this module yet. |
| **SQL Server** | **Not established** | No database-specific integration suite in this module yet. |
| **DB2** | **Not established** | No database-specific integration suite in this module yet. |
| **CockroachDB** | **Not established** | No database-specific integration suite in this module yet. |

Level definitions: [`CLIENT_IMPLEMENTATION_LEVELS.md`](../documents/multi-language-client-spec/CLIENT_IMPLEMENTATION_LEVELS.md)

## L1 scope

- `OjpConnection`, `OjpCommand`, `OjpParameter`, and `OjpDataReader` expose the ADO.NET connection, command, parameter, and forward-only reader APIs.
- `Open()` creates a session with `connect`; commands send `executeUpdate` or consume the `executeQuery` server stream; `Close()` calls `terminateSession`.
- The latest `SessionInfo` is sent on each request and replaced from each response.
- Positional `?` parameters support null, Boolean, byte/short/int/long, float/double, string, byte arrays, and basic date/time/timestamp values. `CommandTimeout` is enforced as a gRPC deadline; zero disables the deadline.
- Query responses are fully buffered in memory before returning the reader.
- OJP SQL error trailers are surfaced as `OjpSqlException`, including SQL state and vendor error code.

The client supports one endpoint and plaintext gRPC only. Transactions, paging/fetch-next-rows, LOBs, metadata, multinode routing/failover, and broader parameter types are outside this L1 implementation. Do not add an application-side connection pool when using OJP.

## Experimental ODBC alternative: `System.Data.Odbc`

The native gRPC provider above is an early L1 implementation. A separate route
is .NET's standard `System.Data.Odbc` API → OJP C++ ODBC driver → OJP server.
This is a **bridge candidate requiring conformance testing**, not a working
replacement promised today. ODBC driver implementation levels do not establish
compatibility with a particular language wrapper.

A successful Linux driver build/install does not verify this bridge.
Windows and macOS driver builds and these wrapper integrations remain untested.

Build the driver and register it as `OJP` with the platform's ODBC driver manager;
see [build requirements and registration](../ojp-client-cpp-odbc/README.md#build-requirements).
The application needs `System.Data.Odbc` and a matching-architecture driver
manager and driver. Disable driver-manager connection pooling before any
connections and do not add framework pooling; the server owns database pools.
Start the server using Java 25 and UTC, with its H2 JDBC driver available.

Use a complete, DSN-less connection string, not the native provider's OJP URL:

```text
DRIVER={OJP};SERVER=localhost:1059;DATABASE={jdbc:h2:mem:sample;DB_CLOSE_DELAY=-1};UID={environment-user};PWD=;
```

`SERVER` is the OJP endpoint, `DATABASE` is the backend JDBC URL, and `UID`/`PWD`
are database credentials; the template shows an empty password, while the
example below reads both credentials from the environment. Braces preserve
JDBC semicolons; escape a literal
`}` as `}}`. The driver accepts `ENDPOINT`, `URL`, and `USER` as aliases for
`SERVER`, `DATABASE`, and `UID`; the driver manager resolves `DRIVER`.
`SQLConnect` and DSN-only connections are unsupported: use `SQLDriverConnect`
with endpoint and backend URL explicitly supplied.

**Compatibility blocker:** the driver is ANSI-only and exports no `W` entry
points. `System.Data.Odbc` normally makes Unicode calls such as
`SQLDriverConnectW`; driver-manager translation is platform-dependent and
cannot be assumed to make this provider work. `SQLGetInfo` is limited, and
`SQLGetStmtAttr`, `SQLMoreResults`, `SQLColAttribute`, `SQLTables`, and
`SQLColumns` are absent. Wrappers may need these even for a simple query.
FireDAC is likewise only an experimental bridge candidate, not a verified
working integration.

This complete C# query illustrates the standard API **after** those compatibility
requirements have been resolved and tested. Set `DB_USER` and `DB_PASSWORD`
in the environment (an empty password is valid for a suitably configured H2).
It is not expected to establish current .NET compatibility:

```csharp
using System;
using System.Data.Odbc;

static string Required(string name) =>
    Environment.GetEnvironmentVariable(name)
    ?? throw new InvalidOperationException($"Set {name}");
var text = new OdbcConnectionStringBuilder { Driver = "OJP" };
text["SERVER"] = "localhost:1059";
text["DATABASE"] = "jdbc:h2:mem:sample;DB_CLOSE_DELAY=-1";
text["UID"] = Required("DB_USER");
text["PWD"] = Required("DB_PASSWORD");
using var connection = new OdbcConnection(text.ConnectionString);
connection.Open();
using var command = connection.CreateCommand();
command.CommandText = "SELECT 1";
Console.WriteLine(command.ExecuteScalar());
```

Do not log the connection string; it contains credentials. Use a trusted/private
network or externally secured transport; ODBC does not add gRPC encryption.

## Build and tests

The projects target .NET 8. From this directory:

```bash
dotnet test Ojp.Client.DotNet.sln
```

The H2 integration test is opt-in and talks to a real OJP server. Start the server using Java 25 with `-Duser.timezone=UTC`, and make the H2 JDBC driver available to it. Then run:

```bash
OJP_TEST_H2=true \
OJP_TEST_H2_ADDR=localhost:1059 \
dotnet test Ojp.Client.DotNet.sln --filter "Category=Integration"
```

On PowerShell, set `$env:OJP_TEST_H2='true'` and `$env:OJP_TEST_H2_ADDR='localhost:1059'` before running `dotnet test`.

The suite loads the backend JDBC URL and credentials from `tests/Ojp.Client.IntegrationTests/TestData/h2_l1_connection.csv`. When enabled, missing endpoint configuration or an unavailable server fails the test. It checks readiness, insert/select/update/delete, empty results, H2 SQL errors, and connection/session close behavior using unique table names.

## Protocol and maturity

The protocol bindings are generated from `ojp-grpc-commons/src/main/proto/StatementService.proto` as part of the build. L1 capability boundaries are defined in [`CLIENT_IMPLEMENTATION_LEVELS.md`](../documents/multi-language-client-spec/CLIENT_IMPLEMENTATION_LEVELS.md); protocol and compatibility requirements are in [`CLIENT_SPEC.md`](../documents/multi-language-client-spec/CLIENT_SPEC.md).
