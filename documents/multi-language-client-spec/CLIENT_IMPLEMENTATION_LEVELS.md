# OJP Client Implementation Levels

> **Status:** Draft
> **Purpose:** Define a common maturity scale (max 10 levels) for OJP clients and show current test-proven level coverage by database in the Java reference client.
> **Companions:** [`CLIENT_SPEC.md`](CLIENT_SPEC.md), [`CLIENT_SPEC_AI.md`](CLIENT_SPEC_AI.md)
> **Protocol source:** `ojp-grpc-commons/src/main/proto/StatementService.proto`, `echo.proto`

---

## 1) Level Model (L1–L10)

Each level includes all lower levels.

| Level | Name | Capability boundary |
|---|---|---|
| **L1** | Basic Connectivity + CRUD | `connect`, `executeQuery`, `executeUpdate`, `terminateSession`; simple scalar types; basic row/result verification. |
| **L2** | Typed Parameters + Statements | Broad `ParameterTypeProto` coverage; PreparedStatement and Statement variants; generated keys; basic metadata. |
| **L3** | Result-Set Protocol | Server-streaming query handling, pagination (`fetchNextRows`), cursor/resource lifecycle through `callResource`. |
| **L4** | Local Transaction Semantics | `startTransaction`, `commitTransaction`, `rollbackTransaction`, savepoints (`RES_SAVEPOINT`), transaction isolation behavior. |
| **L5** | LOB + Stream Semantics | `createLob` / `readLob`; BLOB/CLOB and stream-based round trips; hydratable LOB references. |
| **L6** | Session Affinity + Routing Safety | Correct sticky routing via `sessionUUID`/`targetServer`; no silent reroute for sticky sessions; affinity failure paths covered. |
| **L7** | Multinode Operations | Load balancing (least-connections and round-robin), health checks, cluster health propagation, `connHash` cache + reconnect behavior. |
| **L8** | Resilience + Recovery Operations | Stateless failover retry, unhealthy/healthy transitions, recovered-node reuse and redistribution, pool-exhaustion safety. |
| **L9** | XA Transactions | XA RPC lifecycle (`xaStart`...`xaIsSameRM` as implemented), XA stickiness/error behavior, XA data source integration. |
| **L10** | Full Operational Conformance | Combines data-path, operational-path, and distributed-transaction behavior under multinode scenarios, including recovery paths. |

---

## 2) RPC/Operation Mapping by Level

| Level | Proto / operational focus |
|---|---|
| **L1** | `connect`, `executeQuery`, `executeUpdate`, `terminateSession` |
| **L2** | `ParameterTypeProto`, statement variants, metadata-oriented `callResource` usage |
| **L3** | `executeQuery` stream, `fetchNextRows`, cursor-oriented `callResource` |
| **L4** | `startTransaction`, `commitTransaction`, `rollbackTransaction`, savepoint calls via `callResource` |
| **L5** | `createLob` (client-streaming), `readLob` (server-streaming), LOB parameter flow |
| **L6** | `SessionInfo.sessionUUID`, `SessionInfo.targetServer` enforcement |
| **L7** | Endpoint selection, `clusterHealth` propagation, health-probe behavior, `NOT_FOUND` reconnect path |
| **L8** | Failover/recovery flow, redistribution behavior, admission/pool exhaustion safeguards |
| **L9** | `xaStart`, `xaEnd`, `xaPrepare`, `xaCommit`, `xaRollback`, `xaRecover`, `xaForget`, `xaSetTransactionTimeout`, `xaGetTransactionTimeout`, `xaIsSameRM` |
| **L10** | Combined validation of L1–L9 in multinode operational runs |

---

## 3) Current Test-Proven Coverage by Database (Java Reference)

This table describes what is currently demonstrated by tests in `ojp-jdbc-driver/src/test/java`.

| Database | Highest achieved level (current tests) | Evidence highlights |
|---|---:|---|
| **H2** | **L8** | CRUD, type coverage, transaction/savepoint, session affinity, and non-XA operational behavior (`H2*` integration suites + multinode client tests). |
| **PostgreSQL** | **L10** | Full non-XA + XA coverage (`PostgresXAIntegrationTest`), session affinity, slow-query/operational tests, plus multinode/XA operational suites. |
| **MySQL** | **L8** | CRUD/types/session-affinity and operational multinode behavior are covered; no dedicated MySQL XA suite found. |
| **MariaDB** | **L6** | Covered mainly through shared MySQL/MariaDB suites and generic CRUD paths; dedicated MariaDB operational/XA depth is limited. |
| **Oracle** | **L9** | Strong CRUD/types/LOB/transaction coverage plus Oracle XA (`OracleXAIntegrationTest`); full multinode-XA conformance not database-dedicated. |
| **SQL Server** | **L9** | Broad SQL Server suites including metadata/result-set/LOB/session-affinity and XA (`SQLServerXAIntegrationTest`). |
| **DB2** | **L8** | Strong CRUD/types/LOB/transaction/session-affinity coverage; no dedicated DB2 XA integration suite found. |
| **CockroachDB** | **L8** | CRUD/types/LOB/transaction and large result-set coverage; no dedicated XA coverage found. |

### Non-Java client implementation targets

These entries describe the levels targeted by the language client modules; they do not increase the Java reference-client levels above.

| Client | Database | Target level | Evidence |
|---|---|---:|---|
| C++ ODBC (`ojp-client-cpp-odbc`) | H2 | **L5** | H2 L5 suite covers chunked BLOB/CLOB stream round trips and typed NULL LOB parameters in addition to L1-L4 coverage. |
| C++ ODBC (`ojp-client-cpp-odbc`) | SQL Server | **L4** | SQL Server L4 suite covers transactions, savepoint rollback/release, and isolation in addition to L1-L3 coverage. |
| C++ ODBC (`ojp-client-cpp-odbc`) | PostgreSQL | **L1** | Shared real-server L1 suite covers connectivity, CRUD, diagnostics, and lifecycle. |
| Go (`ojp-client-go-database-sql`) | H2 | **L1** | `client/h2_l1_integration_test.go` exercises connectivity, CRUD, errors, empty results, and connection lifecycle. |
| Dart (`ojp-client-dart-drift`) | H2 | **L1** | `test/h2_l1_integration_test.dart` exercises the real-server CRUD and lifecycle path. |
| .NET ADO.NET (`ojp-client-dotnet-ado-net`) | H2 | **L1** | `tests/Ojp.Client.IntegrationTests/H2L1IntegrationTests.cs` exercises the real-server CRUD/lifecycle path using `TestData/h2_l1_connection.csv`. |
| PHP PDO-compatible (`ojp-client-php-pdo`) | H2 | **L1** | `tests/h2_l1_integration.php` exercises the real-server CRUD and lifecycle path. |
| Python DB-API (`ojp-client-python-dbapi`) | H2 | **L1** | `tests/test_h2.py` exercises the real-server CRUD and lifecycle path. |
| Ruby DBI (`ojp-client-ruby-dbi`) | H2 | **L1** | `test/integration/h2_l1_test.rb` exercises the real-server CRUD and lifecycle path. |

### Important interpretation note

- **Operational levels (L7/L8/L10)** are validated primarily in protocol-level multinode test suites under `org/openjproxy/grpc/client`.
- Those tests validate client behavior independent of SQL dialect, while database-specific suites validate SQL/type/driver behavior.
- For this reason, operational capability may be considered “platform-proven” even when a specific database does not have a dedicated multinode test class.

## 4) Non-Java Client Test-Proven Coverage

These levels are based on each language client's own integration tests and are independent of the Java reference-client matrix above.

| Client | Database | Highest achieved level | Evidence |
|---|---|---:|---|
| **C++ ODBC** | H2 | **L5** | L1-L4 real-server coverage plus `ojp-client-cpp-odbc/tests/h2_l5_integration_test.cpp` for streamed BLOB/CLOB round trips and NULL LOB values. |
| **C++ ODBC** | SQL Server | **L4** | L1-L3 real-server coverage plus `ojp-client-cpp-odbc/tests/sqlserver_l4_integration_test.cpp` for transactions, savepoints, and isolation. |
| **C++ ODBC** | PostgreSQL | **L1** | Real-server connectivity, CRUD, diagnostics, and lifecycle coverage in `ojp-client-cpp-odbc/tests/l1_integration_test.cpp`. |
| **Go** | H2 | **L1** | `ojp-client-go-database-sql/client/h2_l1_integration_test.go` |
| **Dart** | H2 | **L1** | `ojp-client-dart-drift/test/h2_l1_integration_test.dart` |
| **.NET ADO.NET** | H2 | **L1** | `ojp-client-dotnet-ado-net/tests/Ojp.Client.IntegrationTests/H2L1IntegrationTests.cs` |
| **PHP PDO-compatible** | H2 | **L1** | `ojp-client-php-pdo/tests/h2_l1_integration.php` |
| **Python DB-API 2.0** | H2 | **L1** | `ojp-client-python-dbapi/tests/test_h2.py` |
| **Ruby DBI** | H2 | **L1** | `ojp-client-ruby-dbi/test/integration/h2_l1_test.rb` |

The Ruby client targets L1 basic connectivity and CRUD. It does not yet provide an Active Record adapter or claim L2+ coverage; see [`ojp-client-ruby-dbi/README.md`](../../ojp-client-ruby-dbi/README.md) for the implemented API and current gaps.

---

## 5) How to Use This Scale for New Clients

1. Start by targeting **L1 → L4** for first production viability.
2. Add **L5/L6** before claiming broad compatibility.
3. Add **L7/L8** before claiming multinode production readiness.
4. Add **L9/L10** when distributed transactions and full operational resilience are required.

When publishing a new language client, report:
- target level,
- tested level,
- database-by-database achieved level,
- explicit gaps by level (for example: “L9 missing for MySQL and DB2”).

## 5) Non-Java Client Implementations

The following table records the implemented target level and its real-server integration suite for each non-Java client module. A passing CI run is required before treating the level as test-proven.

| Client | Database | Implemented target level | Integration suite |
|---|---|---:|---|
| **C++ ODBC** | H2 | **L5** | `ojp-client-cpp-odbc/tests/h2_l5_integration_test.cpp` adds `createLob`/`readLob` BLOB/CLOB streaming to the L1-L4 suites. |
| **C++ ODBC** | SQL Server | **L4** | `ojp-client-cpp-odbc/tests/sqlserver_l4_integration_test.cpp` adds transaction, savepoint, and isolation coverage to L1-L3. |
| **C++ ODBC** | PostgreSQL | **L1** | `ojp-client-cpp-odbc/tests/l1_integration_test.cpp` covers real-server connectivity, CRUD, diagnostics, and lifecycle. |
| **Go (`database/sql`)** | H2 | **L1** | `ojp-client-go-database-sql/client/h2_l1_integration_test.go` |
| **Dart (Drift)** | H2 | **L1** | `ojp-client-dart-drift/test/h2_l1_integration_test.dart` |
| **.NET (ADO.NET)** | H2 | **L1** | `ojp-client-dotnet-ado-net/tests/Ojp.Client.IntegrationTests/H2L1IntegrationTests.cs` |
| **PHP (`PDO`-compatible)** | H2 | **L1** | `ojp-client-php-pdo/tests/h2_l1_integration.php` |
| **Python (DB-API 2.0)** | H2 | **L1** | `ojp-client-python-dbapi/tests/test_h2.py` |
| **Ruby DBI** | H2 | **L1** | `ojp-client-ruby-dbi/test/integration/h2_l1_test.rb` |

The PHP client currently exposes the L1 operations through a userland `PDO` subclass; it is not a native PDO driver. It targets a single OJP endpoint and does not implement L2+ behavior.
