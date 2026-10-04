# OJP Dart Client

This package contains a Dart client for OJP. It connects to `ojp-server` over
gRPC and exposes SQL execution through a Drift `QueryExecutor`.

## Current Implementation Level Assessment

| Assessment | Value |
|---|---|
| Highest implemented level in this module | **L1** |
| Summary | A public single-endpoint `OjpConnection` and Drift executor implement L1 SQL operations. |

### Current Test-Proven Coverage by Database (`ojp-client-dart-drift`)

| Database | Highest achieved level (current tests) | Evidence highlights |
|---|---:|---|
| **H2** | **L1** | `H2 supports Dart L1 connectivity, CRUD, and session lifecycle` exercises Dart / Drift → one OJP server → H2. |
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
ojp-client-dart-drift/
  lib/
    ojp_client_dart_drift.dart          # public connection and Drift executor API
    src/                          # gRPC client and Drift QueryExecutor
    src/generated/                # generated protobuf/gRPC Dart bindings
  test/                           # CSV-backed H2 L1 integration test
  testdata/h2_l1_connection.csv   # backend JDBC URL, username, password
  generate-proto.sh               # regenerate Dart protocol bindings
  pubspec.yaml
```

## Configuration

Create an `OjpConnection` with the OJP endpoint and backend JDBC connection
details:

```dart
final connection = await OjpConnection.connect(
  endpoint: 'localhost:1059',
  jdbcUrl: 'jdbc:h2:mem:my_database',
  username: 'sa',
  password: '',
);
```

The endpoint is one `host:port`; the backend JDBC URL, username, and password
are passed separately. The client currently uses an insecure gRPC channel, so
use it only on a trusted network. The JDBC driver for the backend database must
be available to `ojp-server`.

## What the Example Does

1. Opens a gRPC connection to one OJP server and receives a session.
2. Wraps the connection in `OjpDriftExecutor`.
3. Creates a table and inserts one row.
4. Reads the row through a parameterized query.
5. Closes the executor, which terminates the OJP session.

## Using as a Library

Drift is Dart's reactive SQL library. `OjpDriftExecutor` implements Drift's
`QueryExecutor` interface and forwards raw SQL and positional parameters to the
OJP server. It can be passed to a Drift database's `GeneratedDatabase`
constructor in place of a local SQLite executor. `sql_conn` is a direct SQL
Server connector, not a remote Drift executor, so this OJP client uses Drift's
executor interface rather than `sql_conn`.

The executor can also be used directly for raw SQL:

```dart
import 'package:ojp_client_dart_drift/ojp_client_dart_drift.dart';

Future<void> main() async {
  final connection = await OjpConnection.connect(
    endpoint: '127.0.0.1:1059',
    jdbcUrl: 'jdbc:h2:mem:my_database',
    username: 'sa',
  );
  final executor = OjpDriftExecutor(connection);

  try {
    await executor.runCustom(
      'CREATE TABLE items (id INT PRIMARY KEY, name VARCHAR(100) NOT NULL)',
    );
    final inserted = await executor.runInsert(
      'INSERT INTO items (id, name) VALUES (?, ?)',
      [1, 'example'],
    );
    final rows = await executor.runSelect(
      'SELECT id, name FROM items WHERE id = ?',
      [1],
    );
    print('insert result=$inserted');
    print('rows=$rows');
  } finally {
    await executor.close();
  }
}
```

Application-side connection pools must be disabled when using OJP. Use one
executor per application connection and do not wrap it in another connection
pool.

## L1 Boundaries

L1 implements `connect`, `executeQuery`, `executeUpdate`, and `terminateSession`.
It propagates the latest `SessionInfo` on each request and maps nulls, booleans,
integers, floating-point numbers, strings, bytes, and timestamps.

- Only one OJP endpoint is supported; multinode routing, health checks, and
  failover are not implemented.
- Transactions and savepoints are not supported; Drift transaction methods
  throw `UnsupportedError`.
- Queries consume the initial `executeQuery` stream only; cursor pagination and
  result-set resource operations are not implemented.
- Generated insert IDs are not returned. `runInsert` executes the insert and
  returns `0`; supply keys directly instead of relying on generated-key
  retrieval.
- Drift's `QueryExecutor` is an internal API that may change between releases.
  The default dialect is SQLite; database-specific SQL remains the
  application's responsibility.
- Only H2 is integration-tested. Other databases and higher OJP levels are not
  established by this implementation.

## Unit Tests

Run all package tests (the real-server H2 test is skipped unless enabled):

```bash
cd ojp-client-dart-drift
dart pub get
dart analyze
dart test
```

## H2 L1 Integration Tests

The H2 suite runs Dart through a real OJP gRPC server to H2. Start `ojp-server`
with Java 25 and UTC, and make the H2 JDBC driver available to the server. Then
run:

```bash
OJP_TEST_H2=true \
OJP_TEST_H2_ADDR=localhost:1059 \
dart test --reporter=expanded
```

When `OJP_TEST_H2` is enabled, missing endpoint configuration or an unavailable
database fails the test rather than skipping it. The test reads the backend
JDBC URL, username, and password from
[`testdata/h2_l1_connection.csv`](testdata/h2_l1_connection.csv). It checks
readiness, CRUD values and counts, SQL error propagation, empty results, and
session termination.

## Regenerate Protocol Bindings

Generated bindings in `lib/src/generated/` come from
`ojp-grpc-commons/src/main/proto`. With Maven, Dart, and the Dart protoc plugin
installed, run from the repository root:

```bash
dart pub global activate protoc_plugin 25.1.0
bash ojp-client-dart-drift/generate-proto.sh
```
