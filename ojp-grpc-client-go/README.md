# OJP gRPC Go Client (Application Layout)

This folder contains a Go application client for OJP.
It connects to `ojp-server` over gRPC and runs a simple CRUD flow.

## Current Implementation Level Assessment

| Assessment | Value |
|---|---|
| Highest implemented level in this module | **L1** |
| Summary | A public single-endpoint L1 API is implemented. The H2 real-server L1 suite passes locally; CI confirmation is pending. |

### Current Test-Proven Coverage by Database (`ojp-grpc-client-go`)

| Database | Highest achieved level (current tests) | Evidence highlights |
|---|---:|---|
| **H2** | **L1** | `client_test.TestH2ConnectionShouldSupportL1CRUDAndLifecycle` passed twice locally: Go → one OJP server → H2; CI confirmation is pending. |
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
ojp-grpc-client-go/
  client/                     # public single-endpoint API and H2 L1 integration tests
  cmd/ojp-grpc-client/        # executable entrypoint (main package)
  internal/client/            # client-side connection/load-balancing helpers
  internal/gen/               # generated protobuf/gRPC Go stubs
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

The Go client can be imported and used programmatically in another Go application:

### Public API

```go
import (
    "context"
    "fmt"
    "log"
    "time"

    ojpclient "github.com/open-j-proxy/ojp-client/client"
)

func main() {
    ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
    defer cancel()

    client, err := ojpclient.NewClient("127.0.0.1:1059")
    if err != nil {
        log.Fatal(err)
    }
    defer client.Close()

    conn, err := client.Connect(ctx, ojpclient.Config{
        URL:  "jdbc:postgresql://postgres:5432/mydb",
        User: "dbuser",
    })
    if err != nil {
        log.Fatal(err)
    }
    defer conn.Close(context.Background())

    if _, err := conn.ExecuteUpdate(ctx, "CREATE TABLE IF NOT EXISTS items(id INT PRIMARY KEY, label VARCHAR(100))"); err != nil {
        log.Fatal(err)
    }
    if count, err := conn.ExecuteUpdate(ctx, "INSERT INTO items(id, label) VALUES (1, 'example')"); err != nil {
        log.Fatal(err)
    } else if count != 1 {
        log.Fatalf("expected one inserted row, got %d", count)
    }

    result, err := conn.Query(ctx, "SELECT id, label FROM items ORDER BY id")
    if err != nil {
        log.Fatal(err)
    }
    for _, row := range result.Rows {
        fmt.Printf("id=%v label=%v\n", row[0], row[1])
    }
}
```

### API Overview

| Method | Description |
|---|---|
| `NewClient(endpoint)` | Create a single-endpoint client |
| `Connect(ctx, Config)` | Open a database session; the client generates one UUID for the process |
| `Connection.ExecuteUpdate(ctx, sql)` | Execute DDL/DML and return the affected-row count |
| `Connection.Query(ctx, sql)` | Read rows, column labels, and scalar values |
| `Connection.State()` | Read the latest session state returned by the server |
| `Connection.Close(ctx)` | Terminate the session; repeated close is safe |
| `Client.Close()` | Close the shared gRPC channel |

SQL failures that include OJP SQL error trailers are returned as `*client.SQLError`, with SQLState and vendor code. Context cancellation and deadline errors are preserved. Generated protobuf messages remain internal to the module.

The public API currently implements the single-endpoint L1 surface. Transactions, typed parameter binding, cursors, LOBs, and multinode support are later levels.

## Run the Client

From `ojp-grpc-client-go`:

```bash
$env:OJP_JDBC_LINE='jdbc:ojp[localhost:1059]_h2:~/test,sa,'
go run ./cmd/ojp-grpc-client
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
go test -count=1 -v ./client -run '^TestH2ConnectionShouldSupportL1CRUDAndLifecycle$'
```

When `OJP_TEST_H2` is enabled, missing connection configuration or an unavailable database fails the test; the suite never treats an unavailable H2 server as a skip.

The test reads the JDBC URL, username, and password from `client/testdata/h2_l1_connection.csv`. It uses a unique table per run, performs a protocol/database readiness query, and checks exact row values, update counts, SQLState/vendor errors, deadline handling, empty results, and session termination. Run the test repeatedly with `-count=2` to check for conflicts.
