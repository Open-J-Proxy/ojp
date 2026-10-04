# OJP PHP PDO Client

This module provides an **L1** PHP client for OJP using a userland `PDO` subclass. It supports one OJP server endpoint and the basic connect, query, update, and session-termination flow. It is not a native PDO driver; instantiate `OpenJProxy\PDO\OjpPDO`, not `new PDO()`.

## Requirements

- PHP 8.2 or later
- The PHP gRPC extension (`ext-grpc`)
- Composer
- Java 25 and Maven when regenerating protobuf classes

## Install and use

Install the Composer dependencies from this directory:

```bash
composer install
```

Use an OJP DSN containing the OJP host and port, followed by the actual JDBC URL. The backend URL is the remainder after `url=`, so its semicolon-separated options are preserved.

```php
<?php

require __DIR__ . '/vendor/autoload.php';

use OpenJProxy\PDO\OjpPDO;

$pdo = new OjpPDO(
    'ojp:host=127.0.0.1;port=1059;url=jdbc:h2:mem:app;DB_CLOSE_DELAY=-1',
    'sa',
    ''
);

$pdo->exec('CREATE TABLE IF NOT EXISTS items (id INT PRIMARY KEY, name VARCHAR(100))');
$insert = $pdo->prepare('INSERT INTO items (id, name) VALUES (?, ?)');
$insert->execute([1, 'example']);

$query = $pdo->prepare('SELECT id, name FROM items WHERE id = ?');
$query->execute([1]);
$item = $query->fetch(PDO::FETCH_ASSOC);

$pdo->close();
```

The username and password are the credentials for the real database. The client communicates with OJP over an insecure gRPC channel; use this only on a trusted network or behind a secured deployment boundary. Do not add an application-side connection pool.

## L1 boundary

Implemented operations are `connect`, `executeQuery`, `executeUpdate`, and `terminateSession`. Positional PDO parameters support null, boolean, integer, float, and string values. Query results support associative, numeric, both, and object fetch modes. SQLSTATE/vendor error details carried in OJP gRPC trailers are exposed through `PDOException::errorInfo`.

This release supports one endpoint and forward-only, fully buffered results. Named parameters, transactions, LOBs, metadata, generated keys, multinode routing/failover, and advanced PDO options are not implemented. The L1 level is not a claim of full PDO driver compatibility.

## H2 L1 integration test

The integration test mirrors the Go L1 approach: it reads the backend JDBC URL, username, and password from `tests/testdata/h2_l1_connection.csv`, while the OJP endpoint is provided separately.

Build the OJP server and download the H2 JDBC driver using the repository's standard Java 25 setup. Start the server with UTC timezone and the configured JDBC library path, then run:

```bash
OJP_TEST_H2=true \
OJP_TEST_H2_ADDR=localhost:1059 \
php tests/h2_l1_integration.php
```

If `OJP_TEST_H2` is enabled, missing connection configuration and server/database failures fail the test. The suite checks readiness, prepared CRUD, basic scalar values, row counts and values, SQL error states, empty results, and session termination.

## Protobuf generation

Generated protocol message classes are checked in under `gen/`. To regenerate or verify them, run `./generate-proto.sh` or `./generate-proto.sh --check` using Java 25 and Maven. RPC client methods used for L1 are implemented in `src/StatementServiceClient.php`.
