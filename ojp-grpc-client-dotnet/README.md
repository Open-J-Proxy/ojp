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

## L1 scope

- `OjpConnection`, `OjpCommand`, `OjpParameter`, and `OjpDataReader` expose the ADO.NET connection, command, parameter, and forward-only reader APIs.
- `Open()` creates a session with `connect`; commands send `executeUpdate` or consume the `executeQuery` server stream; `Close()` calls `terminateSession`.
- The latest `SessionInfo` is sent on each request and replaced from each response.
- Positional `?` parameters support null, Boolean, byte/short/int/long, float/double, string, byte arrays, and basic date/time/timestamp values. `CommandTimeout` is enforced as a gRPC deadline; zero disables the deadline.
- Query responses are fully buffered in memory before returning the reader.
- OJP SQL error trailers are surfaced as `OjpSqlException`, including SQL state and vendor error code.

The client supports one endpoint and plaintext gRPC only. Transactions, paging/fetch-next-rows, LOBs, metadata, multinode routing/failover, and broader parameter types are outside this L1 implementation. Do not add an application-side connection pool when using OJP.

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
