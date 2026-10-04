# OJP Dart Client

This package is the Dart client implementation for OJP. It connects to one
`ojp-server` over gRPC and exposes SQL execution through an OJP connection and a
Drift `QueryExecutor`.

## Implementation level

| Assessment | Value |
|---|---|
| Highest implemented level | **L1 — Basic Connectivity + CRUD** |
| Highest test-proven level | **H2 L1** |
| Integration path | Dart / Drift executor → one OJP server → H2 |

L1 implements `connect`, `executeQuery`, `executeUpdate`, and
`terminateSession`. It propagates the latest `SessionInfo` on each request and
supports nulls, booleans, integers, floating-point numbers, strings, bytes,
and timestamps. The opt-in H2 integration test checks readiness, create,
insert, select, update, delete, SQL error propagation, empty results, and
session termination. Connection details are loaded from
[`testdata/h2_l1_connection.csv`](testdata/h2_l1_connection.csv).

This implementation targets `drift`, whose `QueryExecutor` currently provides
a raw-SQL adapter point. The `sql_conn` Flutter package is a direct SQL Server
connector rather than a custom remote executor, so it is not used to route
requests through OJP.

## Use

Application-side connection pools must remain disabled when using OJP. Use one
executor per application connection and do not wrap it in another connection
pool.

```dart
import 'package:ojp_grpc_client/ojp_grpc_client.dart';

Future<void> main() async {
  final connection = await OjpConnection.connect(
    endpoint: 'localhost:1059',
    jdbcUrl: 'jdbc:h2:mem:my_database',
    username: 'sa',
  );
  final executor = OjpDriftExecutor(connection);

  try {
    await executor.runCustom(
      'CREATE TABLE items (id INT PRIMARY KEY, name VARCHAR(100) NOT NULL)',
    );
    await executor.runInsert(
      'INSERT INTO items (id, name) VALUES (?, ?)',
      [1, 'example'],
    );
    final rows = await executor.runSelect(
      'SELECT id, name FROM items WHERE id = ?',
      [1],
    );
    print(rows);
  } finally {
    await executor.close();
  }
}
```

`OjpConnection` takes an OJP `host:port` endpoint and the backend JDBC URL,
username, and password separately. The client uses an insecure gRPC channel;
use it only on a trusted network. The H2 backend JDBC driver must be available
to `ojp-server`.

## L1 boundaries

- One OJP endpoint; multinode routing, health checks, and failover are not
  implemented.
- Transactions and savepoints are not implemented. Drift transaction methods
  therefore throw `UnsupportedError`.
- The L1 query path consumes the initial `executeQuery` stream only; cursor
  pagination and result-set resource operations are not implemented.
- Generated insert IDs are not returned by the L1 protocol. `runInsert`
  executes the insert and returns `0`; applications should supply their own
  keys and must not rely on generated-key retrieval.
- Drift's `QueryExecutor` is an internal API and may change between Drift
  releases. The default SQL dialect is Drift's SQLite dialect, which is used
  here for its portable SQL types and quoting; database-specific SQL remains
  the application's responsibility.
- Only H2 is currently integration-tested. Other databases and higher OJP
  levels are not established by this implementation.

## Tests

The unit suite runs without an OJP server:

```bash
cd ojp-grpc-client-dart
dart pub get
dart analyze
dart test
```

The real-server H2 suite is opt-in. Start `ojp-server` with Java 25, UTC, and
the H2 JDBC driver available, then run:

```bash
OJP_TEST_H2=true \
OJP_TEST_H2_ADDR=localhost:1059 \
dart test --reporter=expanded
```

When enabled, missing endpoint configuration or a failed database connection
fails the test rather than skipping it.

## Regenerate protocol bindings

The generated bindings in `lib/src/generated/` come from
`ojp-grpc-commons/src/main/proto`. With Maven, Dart, and the Dart protoc plugin
installed, regenerate them from the repository root:

```bash
dart pub global activate protoc_plugin 25.1.0
bash ojp-grpc-client-dart/generate-proto.sh
```
