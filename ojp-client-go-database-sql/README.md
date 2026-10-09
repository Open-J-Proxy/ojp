# OJP gRPC Go Client (Application Layout)

This folder contains a Go application client for OJP.
It connects to `ojp-server` over gRPC and runs a simple CRUD flow.

## Current Implementation Level Assessment

| Assessment | Value |
|---|---|
| Highest implemented level in this module | **L1** |
| Summary | A public single-endpoint L1 API is implemented. The H2 real-server L1 suite passes locally; CI confirmation is pending. |

### Current Test-Proven Coverage by Database (`ojp-client-go-database-sql`)

| Database | Highest achieved level (current tests) | Evidence highlights |
|---|---:|---|
| **H2** | **L1** | `client_test.TestH2DatabaseShouldSupportL1CRUDAndLifecycle` exercises Go `database/sql` → one OJP server → H2. |
| **PostgreSQL** | **Not established** | No database-specific integration suite in this module yet. |
| **MySQL** | **Not established** | No database-specific integration suite in this module yet. |
| **MariaDB** | **Not established** | No database-specific integration suite in this module yet. |
| **Oracle** | **Not established** | No database-specific integration suite in this module yet. |
| **SQL Server** | **Not established** | No database-specific integration suite in this module yet. |
| **DB2** | **Not established** | No database-specific integration suite in this module yet. |
| **CockroachDB** | **Not established** | No database-specific integration suite in this module yet. |

Level definitions: [`../documents/multi-language-client-spec/CLIENT_IMPLEMENTATION_LEVELS.md`](../documents/multi-language-client-spec/CLIENT_IMPLEMENTATION_LEVELS.md)

## Folder Structure

```text
ojp-client-go-database-sql/
  client/                      # database/sql driver and H2 L1 integration tests
  cmd/ojp-client/        # executable entrypoint (main package)
  internal/gen/                # generated protobuf/gRPC Go stubs
  generate-proto.sh           # regenerate or verify the Go protocol bindings
  go.mod
  go.sum
```

## Configuration

Primary (normal use case):

- `OJP_JDBC_LINE`

Format:

```text
jdbc:ojp[host:port]_backendJdbcUrl,user,password
```

Example:

```text
jdbc:ojp[localhost:1059]_postgresql://localhost:5432/defaultdb,testuser,testpassword
```

## What `main` Does

1. Parses connection values from env (`addr`, backend JDBC URL, user, password).
2. Opens gRPC connection to OJP server.
3. Runs CRUD on table `demo`:
   - `CREATE TABLE IF NOT EXISTS`
   - insert
   - read
   - update
   - read
   - delete
   - read

## Using as a Library

The Go client uses `database/sql` because it is Go's standard-library API for accessing SQL databases. It gives Go applications a familiar, driver-independent interface for queries, transactions, and connection pooling, while the OJP driver handles communication with the server. This follows the [OJP multi-language client specification](https://github.com/Open-J-Proxy/ojp/blob/main/documents/multi-language-client-spec/CLIENT_SPEC.md), which identifies `database/sql` as Go's standard database-access API. See the [Go database access documentation](https://go.dev/doc/database/) for details.

Importing the client registers the `ojp` driver:

### `database/sql` API

```go
import (
    "context"
    "database/sql"
    "log"
    "time"

    ojpclient "github.com/open-j-proxy/ojp-client-go-database-sql/client"
)

func main() {
    ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
    defer cancel()

    db, err := sql.Open(ojpclient.DriverName,
        "jdbc:ojp[127.0.0.1:1059]_jdbc:postgresql://postgres:5432/mydb,dbuser,")
    if err != nil {
        log.Fatal(err)
    }
    defer db.Close()

    if err := db.PingContext(ctx); err != nil {
        log.Fatal(err)
    }

    if _, err := db.ExecContext(ctx, "CREATE TABLE IF NOT EXISTS items(id INT PRIMARY KEY, label VARCHAR(100))"); err != nil {
        log.Fatal(err)
    }
    if result, err := db.ExecContext(ctx, "INSERT INTO items(id, label) VALUES (1, 'example')"); err != nil {
        log.Fatal(err)
    } else if count, err := result.RowsAffected(); err != nil || count != 1 {
        log.Fatalf("expected one inserted row, got %d (error %v)", count, err)
    }

    rows, err := db.QueryContext(ctx, "SELECT id, label FROM items ORDER BY id")
    if err != nil {
        log.Fatal(err)
    }
    defer rows.Close()
    for rows.Next() {
        var id int64
        var label string
        if err := rows.Scan(&id, &label); err != nil {
            log.Fatal(err)
        }
        log.Printf("id=%d label=%s", id, label)
    }
    if err := rows.Err(); err != nil {
        log.Fatal(err)
    }
}
```

The data source name (DSN) is a CSV record containing the OJP JDBC URL, database username, and password. For credentials containing commas, use normal CSV quoting:

`database/sql` manages OJP sessions and gRPC connections through its normal pool lifecycle. Positional parameters support nil, booleans, integers, floating-point values, strings, byte slices, and timestamps; named parameters and other driver values are not supported yet. `BeginTx`, `Commit`, and `Rollback` use OJP's transaction RPCs. SQL failures with OJP error trailers are returned as `*client.SQLError`.

`database/sql` is the only supported Go database access API. The generated protocol bindings remain internal implementation details.

## Experimental ODBC alternative: third-party `database/sql` adapter

The native driver above is a single-endpoint L1 implementation with H2 coverage,
not full database or OJP-level coverage. Go's `database/sql` has **no built-in
ODBC driver**. A third-party adapter, for example
[`github.com/alexbrainman/odbc`](https://github.com/alexbrainman/odbc), can
provide the conceptual route `database/sql` → ODBC → OJP C++ driver → OJP server.
It is not a dependency of this module, nor a verified OJP integration.

A successful Linux driver build/install does not verify this bridge.
Windows and macOS driver builds and these wrapper integrations remain untested.

That adapter uses Windows ODBC DLLs on Windows and cgo/unixODBC elsewhere.
It therefore requires a supported native platform, driver manager, matching
driver architecture, and (on Unix) a C toolchain and unixODBC development
libraries. Do not assume pure-Go, cross-compilation, mobile, or browser support.
Build and register the OJP driver as `OJP`; see
[build requirements and registration](../ojp-client-cpp-odbc/README.md#build-requirements).
Start the server using Java 25 and UTC with its H2 JDBC driver available.

Use the full DSN-less string:

```text
DRIVER={OJP};SERVER=localhost:1059;DATABASE={jdbc:h2:mem:sample;DB_CLOSE_DELAY=-1};UID={environment-user};PWD=;
```

`SERVER` is the OJP endpoint, `DATABASE` the backend JDBC URL, and `UID`/`PWD`
the database credentials. The template shows an empty password; the example
below reads both credentials from the environment.
`ENDPOINT`, `URL`, and `USER` are accepted aliases
for `SERVER`, `DATABASE`, and `UID`. The driver manager resolves `DRIVER`.
Braces preserve JDBC semicolons; escape literal `}` as `}}`.
`SQLConnect` and DSN-only connections are unsupported: the adapter must use
`SQLDriverConnect` and supply the endpoint and URL.

**Compatibility blockers:** the OJP driver is ANSI-only, with no `W` exports.
The example adapter uses Unicode calls, including `SQLDriverConnectW`.
Driver-manager translation cannot be assumed to resolve that incompatibility.
`SQLGetInfo` is limited and `SQLGetStmtAttr`, `SQLMoreResults`,
`SQLColAttribute`, `SQLTables`, and `SQLColumns` are absent. Adapters may need
these even for ordinary queries. These are experimental bridge candidates
requiring conformance testing, not working alternatives promised today;
ODBC implementation levels do not prove adapter compatibility.

For an application that already has the adapter installed, this complete
program illustrates standard API usage **after** compatibility is resolved:

```go
package main

import (
    "context"
    "database/sql"
    "fmt"
    "log"
    "os"
    "strings"
    "time"

    _ "github.com/alexbrainman/odbc"
)

func requiredEnv(name string) string {
    value, ok := os.LookupEnv(name)
    if !ok {
        log.Fatalf("Set %s", name)
    }
    return value
}

func brace(value string) string {
    return "{" + strings.ReplaceAll(value, "}", "}}") + "}"
}

func field(key, value string) string {
    return key + "=" + brace(value) + ";"
}

func main() {
    text := "DRIVER={OJP};SERVER=localhost:1059;" +
        "DATABASE={jdbc:h2:mem:sample;DB_CLOSE_DELAY=-1};" +
        field("UID", requiredEnv("DB_USER")) +
        field("PWD", requiredEnv("DB_PASSWORD"))
    db, err := sql.Open("odbc", text)
    if err != nil {
        log.Fatal(err)
    }
    defer db.Close()
    db.SetMaxIdleConns(0)
    db.SetMaxOpenConns(1)
    ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
    defer cancel()
    var result int
    if err := db.QueryRowContext(ctx, "SELECT 1").Scan(&result); err != nil {
        log.Fatal(err)
    }
    fmt.Println(result)
}
```

Set `DB_USER` and `DB_PASSWORD` in the environment; an empty password is allowed
for a suitably configured H2. `database/sql` always manages connections;
`SetMaxIdleConns(0)` disables retained idle connections, not that lifecycle,
and `SetMaxOpenConns(1)` limits concurrency. Disable driver-manager pooling
before connections and do not layer another application/framework pool on top.
Do not log the credential-bearing string. ODBC does not add gRPC encryption;
use a trusted/private network or an externally secured boundary.

## Run the Client

The module path is `github.com/open-j-proxy/ojp-client-go-database-sql`; run commands from the `ojp-client-go-database-sql` directory:

```bash
$env:OJP_JDBC_LINE='jdbc:ojp[localhost:1059]_h2:~/test,sa,'
go run ./cmd/ojp-client
```

Expected output:

```text
READ after CREATE/INSERT:
opResult: type=RESULT_SET_DATA uuid=...
READ after UPDATE:
opResult: type=RESULT_SET_DATA uuid=...
READ after DELETE:
opResult: type=RESULT_SET_DATA uuid=...
```

## Unit Tests

Run all unit tests:

```bash
go test ./...
```

## H2 L1 Integration Tests

The H2 suite runs Go through a real OJP gRPC server to an H2 database. Start the server using Java 25 and UTC, then run:

```bash
OJP_TEST_H2=true \
OJP_TEST_H2_ADDR=localhost:1059 \
go test -count=1 -v ./client -run '^TestH2DatabaseShouldSupportL1CRUDAndLifecycle$'
```

When `OJP_TEST_H2` is enabled, missing connection configuration or an unavailable database fails the test; the suite never treats an unavailable H2 server as a skip.

The test reads the JDBC URL, username, and password from `client/testdata/h2_l1_connection.csv` and exercises the standard `database/sql` API. It uses a unique table per run, performs a protocol/database readiness check, and checks exact row values, update counts, SQLState/vendor errors, deadline handling, empty results, and database close behavior. Run the test repeatedly with `-count=2` to check for conflicts.
