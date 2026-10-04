# OJP PHP PDO Client

This folder contains a PHP client for OJP. It connects to `ojp-server` over gRPC and provides the basic PDO-style CRUD flow through a userland `PDO` subclass.

## Current Implementation Level Assessment

| Assessment | Value |
|---|---|
| Highest implemented level in this module | **L1** |
| Summary | A public single-endpoint PDO-compatible API is implemented. The H2 real-server L1 suite passes in CI. |

### Current Test-Proven Coverage by Database (`ojp-client-php-pdo`)

| Database | Highest achieved level (current tests) | Evidence highlights |
|---|---:|---|
| **H2** | **L1** | `tests/h2_l1_integration.php` exercises PHP PDO-compatible API → one OJP server → H2. |
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
ojp-client-php-pdo/
  src/                         # PDO-compatible API and gRPC client
  gen/                         # Generated protobuf message classes
  tests/                       # Unit checks and H2 L1 integration test
  generate-proto.sh            # Regenerate or verify PHP protocol bindings
  composer.json
  composer.lock
```

## Configuration

Create an `OjpPDO` using a DSN with one OJP endpoint and the backend JDBC URL:

```text
ojp:host=<host>;port=<port>;url=<backendJdbcUrl>
```

Example:

```text
ojp:host=127.0.0.1;port=1059;url=jdbc:h2:mem:app;DB_CLOSE_DELAY=-1
```

The username and password are credentials for the real database. The backend URL is everything after `url=`, so JDBC options separated by semicolons are preserved.

## Using as a Library

Install the Composer dependencies from this directory:

```bash
composer install
```

This API is a userland subclass, not a native PDO driver. Create `OpenJProxy\PDO\OjpPDO` directly; `new PDO('ojp:...')` is not supported.

### Requirements

- PHP 8.2 or later
- The PHP gRPC extension (`ext-grpc`)
- Composer
- Java 25 and Maven to build the OJP server or regenerate protobuf classes

```php
<?php

require __DIR__ . '/vendor/autoload.php';

use OpenJProxy\PDO\OjpPDO;

$pdo = new OjpPDO(
    'ojp:host=127.0.0.1;port=1059;url=jdbc:h2:mem:app;DB_CLOSE_DELAY=-1',
    'sa',
    ''
);

try {
    $pdo->exec('CREATE TABLE IF NOT EXISTS items (id INT PRIMARY KEY, name VARCHAR(100))');

    $insert = $pdo->prepare('INSERT INTO items (id, name) VALUES (?, ?)');
    $insert->execute([1, 'example']);

    $query = $pdo->prepare('SELECT id, name FROM items WHERE id = ?');
    $query->execute([1]);
    $item = $query->fetch(PDO::FETCH_ASSOC);
} finally {
    $pdo->close();
}
```

The L1 API supports single-endpoint `connect`, `executeQuery`, `executeUpdate`, and `terminateSession`. Positional parameters support null, boolean, integer, float, and string values. Query results support associative, numeric, both, and object fetch modes. SQLSTATE, vendor code, and message from OJP SQL error trailers are available through `PDOException::errorInfo`.

Application-side connection pools should not be used with OJP. This client uses an insecure gRPC channel, so run it only on a trusted network or behind a secured deployment boundary.

### L1 Scope

The client supports one endpoint and forward-only, fully buffered results. Named parameters, transactions, LOBs, metadata, generated keys, multinode routing/failover, and advanced PDO options are not implemented. L1 is not a claim of full PDO driver compatibility.

## Unit Tests

Install dependencies first, then run:

```bash
php tests/unit.php
```

## H2 L1 Integration Tests

The H2 suite calls the PHP PDO-compatible API through a real OJP gRPC server to an H2 database. The test reads the backend JDBC URL, username, and password from `tests/testdata/h2_l1_connection.csv`; set the OJP server endpoint separately.

Build the OJP server and download the H2 JDBC driver using the repository's Java 25 setup. Start the server with UTC timezone and its JDBC library path, then run:

```bash
OJP_TEST_H2=true \
OJP_TEST_H2_ADDR=localhost:1059 \
php tests/h2_l1_integration.php
```

When `OJP_TEST_H2` is enabled, missing configuration or unavailable server/database fails the test. Coverage includes readiness, prepared CRUD, scalar values, row counts and values, SQL error states, empty results, and session termination.

## Protobuf Generation

Generated protocol message classes are checked in under `gen/`. To regenerate or verify them, run `./generate-proto.sh` or `./generate-proto.sh --check` using Java 25 and Maven. L1 gRPC methods are implemented in `src/StatementServiceClient.php`.
