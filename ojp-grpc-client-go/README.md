# OJP gRPC Go Client (Application Layout)

This folder contains a Go application client for OJP.
It connects to `ojp-server` over gRPC and runs a simple CRUD flow.

## Folder Structure

```text
ojp-grpc-client-go/
  cmd/ojp-grpc-client/        # executable entrypoint (main package)
  internal/client/            # client-side connection/load-balancing helpers
  internal/gen/               # generated protobuf/gRPC Go stubs
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

### Import

```go
import (
    ojpb "github.com/open-j-proxy/ojp-client/internal/gen/go/com/openjproxy/grpc"
    ojpclient "github.com/open-j-proxy/ojp-client/internal/client"
)
```

### Basic CRUD Example

```go
package main

import (
    "context"
    "fmt"
    "log"
    "time"

    ojpb "github.com/open-j-proxy/ojp-client/internal/gen/go/com/openjproxy/grpc"
    ojpclient "github.com/open-j-proxy/ojp-client/internal/client"
)

func main() {
    // 1. Create a client connecting to OJP server at 127.0.0.1:1059
    client := ojpclient.NewGrpcStatementServiceClient("127.0.0.1:1059")
    defer client.Shutdown()

    ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
    defer cancel()

    // 2. Connect to the backend database through OJP
    conn, err := client.Connect(ctx, &ojpb.ConnectionDetails{
        Url:        "jdbc:postgresql://postgres:5432/mydb",
        User:       "dbuser",
        Password:   "dbpass",
        ClientUUID: "my-app-client",
    })
    if err != nil {
        log.Fatalf("connect failed: %v", err)
    }

    // 3. Execute DDL / UPDATE statements
    _, err = client.ExecuteUpdate(ctx, conn, "CREATE TABLE IF NOT EXISTS items(id INT PRIMARY KEY, label VARCHAR(100))")
    if err != nil {
        log.Fatalf("create failed: %v", err)
    }

    // 4. Execute INSERT
    _, err = client.ExecuteUpdate(ctx, conn, "INSERT INTO items(id, label) VALUES (1, 'example')")
    if err != nil {
        log.Fatalf("insert failed: %v", err)
    }

    // 5. Execute SELECT queries
    results, err := client.ExecuteQuery(ctx, conn, "SELECT id, label FROM items ORDER BY id")
    if err != nil {
        log.Fatalf("query failed: %v", err)
    }
    for _, msg := range results {
        if qr := msg.GetQueryResult(); qr != nil {
            for _, row := range qr.GetRows() {
                fmt.Printf("id=%v label=%v\n", row.GetColumns()[0], row.GetColumns()[1])
            }
        }
    }

    // 6. Clean up
    _ = client.TerminateSession(context.Background(), conn)
}
```

### Transaction Example

```go
// Start a transaction
tx, err := client.StartTransaction(ctx, conn)
if err != nil {
    log.Fatalf("start tx failed: %v", err)
}

// Execute statements inside the transaction
_, _ = client.ExecuteUpdate(ctx, tx, "UPDATE items SET label = 'updated' WHERE id = 1")

// Rollback
err = client.RollbackTransaction(ctx, tx)
if err != nil {
    log.Fatalf("rollback failed: %v", err)
}

// Or commit instead
// err = client.CommitTransaction(ctx, tx)
```

### API Overview

| Method                                | Description                                        |
|---------------------------------------|----------------------------------------------------|
| `NewGrpcStatementServiceClient(addr)` | Create a new client connected to an OJP server     |
| `Connect(ctx, *ConnectionDetails)`    | Open a session pointing to a backend database      |
| `ExecuteUpdate(ctx, session, sql)`    | Execute DDL / DML (INSERT, UPDATE, DELETE, CREATE) |
| `ExecuteQuery(ctx, session, sql)`     | Execute SELECT and stream result sets              |
| `StartTransaction(ctx, session)`      | Start a new database transaction                   |
| `CommitTransaction(ctx, tx)`          | Commit an active transaction                       |
| `RollbackTransaction(ctx, tx)`        | Rollback an active transaction                     |
| `TerminateSession(ctx, session)`      | Close the database session                         |
| `Shutdown()`                          | Close the gRPC connection and release resources    |

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

Unit tests are in:

- `cmd/ojp-grpc-client/main_test.go`

Covered functions:

- `parseOjpCsvLine`
- `selectCsvLine`
- `drainQueryStream` (with mock streaming client)

Run:

```bash
go test ./cmd/ojp-grpc-client
```