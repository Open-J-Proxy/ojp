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
| **L2** | Typed Parameters + Statements | Broad `ParameterTypeProto` coverage; PreparedStatement and Statement variants; generated keys; basic metadata; DML result sets where the database supports `RETURNING`, `OUTPUT`, or data-change-table syntax. |
| **L3** | Result-Set Protocol | Server-streaming query handling, pagination (`fetchNextRows`), cursor/resource lifecycle through `callResource`. |
| **L4** | Local Transaction Semantics | `startTransaction`, `commitTransaction`, `rollbackTransaction`, savepoints (`RES_SAVEPOINT`), transaction isolation behavior. |
| **L5** | LOB + Stream Semantics | `createLob` / `readLob`; BLOB/CLOB and stream-based round trips; hydratable LOB references. |
| **L6** | Session Affinity + Routing Safety | Correct sticky routing via `sessionUUID`/`targetServer`; no silent reroute for sticky sessions; affinity failure paths covered. |
| **L7** | Multinode Operations | Load balancing (least-connections and round-robin), health checks, cluster health propagation, `connHash` cache + reconnect behavior. |
| **L8** | Resilience + Recovery Operations | Stateless failover retry, unhealthy/healthy transitions, recovered-node reuse and redistribution, pool-exhaustion safety. |
| **L9** | XA Transactions | XA RPC lifecycle (`xaStart`...`xaIsSameRM` as implemented), XA stickiness/error behavior, XA data source integration. |
| **L10** | Full Operational + mTLS Conformance | Combines L1–L9 behavior under multinode scenarios, including recovery paths, and validates mutual TLS authentication between client and server. |

---

## 2) RPC/Operation Mapping by Level

| Level | Proto / operational focus |
|---|---|
| **L1** | `connect`, `executeQuery`, `executeUpdate`, `terminateSession` |
| **L2** | `ParameterTypeProto`, statement variants, metadata-oriented `callResource` usage, and result-producing DML through `executeUpdate` plus result-set fetch/close operations |
| **L3** | `executeQuery` stream, `fetchNextRows`, cursor-oriented `callResource` |
| **L4** | `startTransaction`, `commitTransaction`, `rollbackTransaction`, savepoint calls via `callResource` |
| **L5** | `createLob` (client-streaming), `readLob` (server-streaming), LOB parameter flow |
| **L6** | `SessionInfo.sessionUUID`, `SessionInfo.targetServer` enforcement |
| **L7** | Endpoint selection, `clusterHealth` propagation, health-probe behavior, `NOT_FOUND` reconnect path |
| **L8** | Failover/recovery flow, redistribution behavior, admission/pool exhaustion safeguards |
| **L9** | `xaStart`, `xaEnd`, `xaPrepare`, `xaCommit`, `xaRollback`, `xaRecover`, `xaForget`, `xaSetTransactionTimeout`, `xaGetTransactionTimeout`, `xaIsSameRM` |
| **L10** | Combined L1–L9 multinode validation plus mutual TLS (mTLS) |

---

## 3) Current Test-Proven Coverage by Database (Java Reference)

This table describes what is currently demonstrated by tests in `ojp-jdbc-driver/src/test/java`.

| Database | Highest achieved level (current tests) | Evidence highlights |
|---|---:|---|
| **H2** | **L8** | CRUD, type coverage, transaction/savepoint, session affinity, and non-XA operational behavior (`H2*` integration suites + multinode client tests). |
| **PostgreSQL** | **L9** | Full non-XA + XA coverage (`PostgresXAIntegrationTest`), session affinity, slow-query/operational tests, and multinode/XA suites. mTLS is configured in the Java client but has no JDBC integration test, so L10 is not test-proven. |
| **MySQL** | **L8** | CRUD/types/session-affinity and operational multinode behavior are covered; no dedicated MySQL XA suite found. |
| **MariaDB** | **L6** | Covered mainly through shared MySQL/MariaDB suites and generic CRUD paths; dedicated MariaDB operational/XA depth is limited. |
| **Oracle** | **L9** | Strong CRUD/types/LOB/transaction coverage plus Oracle XA (`OracleXAIntegrationTest`); full multinode-XA conformance not database-dedicated. |
| **SQL Server** | **L9** | Broad SQL Server suites including metadata/result-set/LOB/session-affinity and XA (`SQLServerXAIntegrationTest`). |
| **DB2** | **L8** | Strong CRUD/types/LOB/transaction/session-affinity coverage; no dedicated DB2 XA integration suite found. |
| **CockroachDB** | **L8** | CRUD/types/LOB/transaction and large result-set coverage; no dedicated XA coverage found. |

The Java JDBC client has mTLS channel configuration, but no integration test under `ojp-jdbc-driver/src/test/java` currently validates a client-certificate handshake. L10 therefore remains unproven for every Java database until its required mTLS integration coverage passes.

The H2 Java reference client now has an XA lifecycle integration suite in
`ojp-jdbc-driver/src/test/java/org/openjproxy/jdbc/h2/H2XAIntegrationTest.java`.
H2 remains listed at L8 until the new suite passes CI.

### Non-Java client implementation targets

These entries describe the levels targeted by the language client modules; they do not increase the Java reference-client levels above.

| Client | Database | Target level | Evidence |
|---|---|---:|---|
| C++ ODBC (`ojp-client-cpp-odbc`) | H2 | **L9** | H2 L1-L9 integration suites passed, including XA lifecycle and resource-manager integration. |
| C++ ODBC (`ojp-client-cpp-odbc`) | SQL Server | **L9** | SQL Server suites cover XA lifecycle/affinity and multinode failover/recovery. The client currently uses plaintext gRPC, so it does not meet the new L10 mTLS requirement. |
| C++ ODBC (`ojp-client-cpp-odbc`) | PostgreSQL | **L9** | Existing L1-L9 suites cover the individual capabilities; the combined L10 suite is registered in CI, but the client currently uses plaintext gRPC and does not meet the mTLS requirement. |
| C++ ODBC (`ojp-client-cpp-odbc`) | MariaDB | **L9** | MariaDB suites cover database operations, multinode behavior, and XA lifecycle/recovery. The client currently uses plaintext gRPC, so it does not meet the new L10 mTLS requirement. |
| Go (`ojp-client-go-database-sql`) | H2 | **L3** | L1-L3 are covered by `client/h2_l1_integration_test.go`, `client/h2_l2_integration_test.go`, `client/h2_l3_integration_test.go`, and `client/h2_l3_fetch_integration_test.go`. |
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
| **C++ ODBC** | H2 | **L9** | L1-L9 real-server integration suites passed, including XA lifecycle and recovery in `ojp-client-cpp-odbc/tests/h2_l9_integration_test.cpp`. |
| **C++ ODBC** | SQL Server | **L9** | L1-L9 real-server integration suites passed, including XA lifecycle/recovery. The combined L10 suite does not validate mTLS. |
| **C++ ODBC** | PostgreSQL | **L8** | L1-L8 real-server suites have existing coverage. PostgreSQL L9 and L10 operational/XA suites are registered in CI, but do not test mTLS. |
| **C++ ODBC** | MariaDB | **L9** | L1-L9 suites cover database operations, multinode behavior, and XA lifecycle/recovery. The combined L10 suite does not validate mTLS. |
| **Go** | H2 | **L3** | L1-L3 real-server integration coverage: `ojp-client-go-database-sql/client/h2_l1_integration_test.go`, `h2_l2_integration_test.go`, `h2_l3_integration_test.go`, and `h2_l3_fetch_integration_test.go`. |
| **Dart** | H2 | **L1** | `ojp-client-dart-drift/test/h2_l1_integration_test.dart` |
| **.NET ADO.NET** | H2 | **L1** | `ojp-client-dotnet-ado-net/tests/Ojp.Client.IntegrationTests/H2L1IntegrationTests.cs` |
| **PHP PDO-compatible** | H2 | **L1** | `ojp-client-php-pdo/tests/h2_l1_integration.php` |
| **Python DB-API 2.0** | H2 | **L1** | `ojp-client-python-dbapi/tests/test_h2.py` |
| **Ruby DBI** | H2 | **L1** | `ojp-client-ruby-dbi/test/integration/h2_l1_test.rb` |

PostgreSQL L5 is assessed against the PostgreSQL Java JDBC reference's
LOB/stream semantics, using `BYTEA`/`TEXT`, not OID large objects.
The client follows the existing Java JDBC path: binary input is hydrated
`PT_BINARY_STREAM` bytes and text input is `PT_STRING`; query results are also
hydrated. ODBC chunks are buffered locally, not streamed through
`createLob`/`readLob`. PostgreSQL JDBC does not support native
`createBlob`/`createClob`. As in the PostgreSQL JDBC reference, this hydrated
path satisfies the database-specific L5 level without LOB-handle RPCs.
This work does not modify the OJP server or claim the `LT_BINARY_STREAM`
reference path.
ODBC cancellation is supported for buffered data-at-execution only, not active RPCs.

The Ruby client targets L1 basic connectivity and CRUD. It does not yet provide an Active Record adapter or claim L2+ coverage; see [`ojp-client-ruby-dbi/README.md`](../../ojp-client-ruby-dbi/README.md) for the implemented API and current gaps.

---

## 5) How to Use This Scale for New Clients

1. Start by targeting **L1 → L4** for first production viability.
2. Add **L5/L6** before claiming broad compatibility.
3. Add **L7/L8** before claiming multinode production readiness.
4. Add **L9** for distributed transactions; add **L10** for mTLS plus full operational resilience.

When publishing a new language client, report:
- target level,
- tested level,
- database-by-database achieved level,
- explicit gaps by level (for example: “L9 missing for MySQL and DB2”).

## 5) Non-Java Client Implementations

The following table records the implemented target level and its real-server integration suite for each non-Java client module. A passing CI run is required before treating the level as test-proven.

| Client | Database | Implemented target level | Integration suite |
|---|---|---:|---|
| **C++ ODBC** | H2 | **L9** | `ojp-client-cpp-odbc/tests/h2_l9_integration_test.cpp` covers XA lifecycle and recovery; all H2 L1-L9 integration suites passed. |
| **C++ ODBC** | SQL Server | **L9** | `ojp-client-cpp-odbc/tests/sqlserver_l10_integration_test.cpp` covers combined operational/XA behavior, but the client currently lacks mTLS and therefore does not implement L10. |
| **C++ ODBC** | PostgreSQL | **L9** | `ojp-client-cpp-odbc/tests/postgresql_l10_integration_test.cpp` covers combined operational/XA behavior, but the client currently lacks mTLS and therefore does not implement L10. |
| **C++ ODBC** | MariaDB | **L9** | `ojp-client-cpp-odbc/tests/mariadb_l10_integration_test.cpp` covers combined operational/XA behavior, but the client currently lacks mTLS and therefore does not implement L10. |
| **Go (`database/sql`)** | H2 | **L1** | `ojp-client-go-database-sql/client/h2_l1_integration_test.go` |
| **Dart (Drift)** | H2 | **L1** | `ojp-client-dart-drift/test/h2_l1_integration_test.dart` |
| **.NET (ADO.NET)** | H2 | **L1** | `ojp-client-dotnet-ado-net/tests/Ojp.Client.IntegrationTests/H2L1IntegrationTests.cs` |
| **PHP (`PDO`-compatible)** | H2 | **L1** | `ojp-client-php-pdo/tests/h2_l1_integration.php` |
| **Python (DB-API 2.0)** | H2 | **L1** | `ojp-client-python-dbapi/tests/test_h2.py` |
| **Ruby DBI** | H2 | **L1** | `ojp-client-ruby-dbi/test/integration/h2_l1_test.rb` |

The PHP client currently exposes the L1 operations through a userland `PDO` subclass; it is not a native PDO driver. It targets a single OJP endpoint and does not implement L2+ behavior.

---

## 6) Certification Test Requirements by Level

Use this checklist to certify the **tested-achieved** level of a client. A level is cumulative: to claim L*n* for a database, the client must pass the required coverage for every level from L1 through L*n* against that database. A passing test for one database does not certify another database.

### Certification rules

- Run client-facing integration tests through the public API and a real OJP server. Test against every database for which the level is claimed; database-neutral protocol tests supplement, but do not replace, those runs.
- Use a fast database (H2 is the JDBC/ODBC reference baseline) for routine CI, and gate optional database and multi-server environments with explicit per-database/environment flags. A skipped or disabled suite is not a passing result.
- Verify the public connection factory does not pool virtual connections, and that closing a connection sends `terminateSession`, makes the closed object unusable, and releases server-side session resources for reuse.
- For L7–L10, run against at least two OJP servers sharing the test database. Exercise the new client itself; server-side tests or unit tests alone do not certify client behavior.
- Assert results and externally observable behavior: returned values, row counts, resource state, routing/failure outcomes, and SQLSTATE or equivalent error class where applicable. Tests must clean up resources and test data.
- Report the client/API, database, claimed level, test command or CI job, and a link to the passing test evidence. Mark untested levels as gaps; do not infer a level from implementation alone.
- The test names below describe required scenarios, not mandatory source-file names. One test may cover several scenarios if each assertion is explicit and independently diagnosable.

### Minimum cumulative integration-test checklist

| Level | Required tests and minimum coverage | Reference-suite examples |
|---|---|---|
| **L1 — Connectivity + CRUD** | Connect and close cleanly; verify close sends `terminateSession`, releases server-side resources, and makes the virtual connection unusable. Confirm the public connection factory does not pool/reuse virtual connections. Execute SELECT, INSERT, UPDATE, and DELETE; verify returned rows, affected-row counts, empty results, and basic SQL-error reporting. Exercise the client’s ordinary statement/parameter path, not only direct protocol calls. | JDBC `BasicCrudIntegrationTest`, `ConnectionCloseBehaviorTest`; ODBC `tests/l1_integration_test.cpp` |
| **L2 — Typed parameters + statements** | Round-trip each supported protocol parameter/value type, including boundary-relevant numeric, text, binary, date/time, and NULL values; verify 1-based parameter binding and returned types/values. Cover the client’s supported statement forms (plain, prepared, batch, generated keys, and metadata where exposed) and repeated execution/rebinding. Where a database supports DML that returns rows (`RETURNING`, `OUTPUT`, or `SELECT ... FROM FINAL TABLE (...)`), verify direct and prepared executions return rows through the client’s result-set API while executing through the update/write path. Declare and test intentional type or API omissions. | JDBC `*MultipleTypesIntegrationTest`, `*PreparedStatementExtensiveTests`, `*StatementExtensiveTests`; ODBC `tests/h2_l2_integration_test.cpp` and database-specific `*_l2_integration_test.cpp` |
| **L3 — Result-set protocol** | Fetch an empty result, a single row, and enough ordered rows to cross at least one server fetch/page boundary; verify no rows are missing, duplicated, or reordered. Check result metadata, end-of-results behavior, early result/cursor close, and resource cleanup. | JDBC `ReadMultipleBlocksOfDataIntegrationTest`, `ResultSetTest`; ODBC `tests/h2_l3_integration_test.cpp` (10,001-row pagination and partial close) |
| **L4 — Transactions + savepoints** | Verify autocommit behavior; commit persists changes; rollback removes/reverts changes; get/set isolation behaves as documented. Create savepoints, rollback to and release them, verify partial rollback, and reject use after transaction completion. | JDBC `H2TransactionCommitRollbackIntegrationTest`, `*SavepointTests`; ODBC `tests/h2_l4_integration_test.cpp` |
| **L5 — LOBs + streams** | Round-trip binary and character LOB data including multi-chunk/large values, Unicode text, empty values, and NULL. Cover each supported stream input/output API and LOB-reference reuse/lifecycle. Exercise the client’s declared transport path (LOB RPC handles or database-specific hydrated parameters/results); document any database-specific limitation rather than treating unsupported behavior as passed. | JDBC `BlobIntegrationTest`, `BinaryStreamIntegrationTest`, `*BlobIntegrationTest`; ODBC `tests/h2_l5_integration_test.cpp` |
| **L6 — Session affinity** | Create server-session state (for example, a temporary table or open cursor) and use it across multiple calls; prove the client routes every call to the bound server. With the bound server unavailable, prove sticky state is not silently sent to another server; verify the failure reaches the caller and close removes session state/binding. | JDBC `H2SessionAffinityIntegrationTest`, `MultinodeTargetServerBindingTest`; ODBC `tests/h2_l6_integration_test.cpp` |
| **L7 — Multinode operations** | With two or more servers, verify configured endpoint selection/balancing and that new sessions can use healthy nodes. Verify health probing detects down/up nodes, cluster-health information is propagated, and cached pool identity is reused; simulate a stale/missing pool (`NOT_FOUND`) and verify reconnect plus one safe retry only when no sticky session exists. | JDBC `LoadAwareServerSelectionTest`, `MultinodeConnectionManagerClusterHealthTest`, `ConnectRpcSkipOptimisationTest`; ODBC `tests/h2_l7_integration_test.cpp` and database-specific `*_l7_integration_test.cpp` |
| **L8 — Resilience + recovery** | Stop one node and verify eligible stateless operations retry on a healthy node within configured limits; restart it and verify health recovery, successful reuse, and redistribution. Confirm sticky-session loss is surfaced, SQL/database errors do not trigger failover, pool exhaustion is surfaced without retry, and failures do not corrupt subsequent healthy operations. | JDBC `MultinodeFailoverTest`, `MultinodeRecoveryTest`, `MultinodeConnectionManagerErrorHandlingTest`; ODBC `tests/h2_l8_integration_test.cpp` and database-specific `*_l8_integration_test.cpp` |
| **L9 — XA** | Exercise XA start/end, one- and two-phase commit, rollback, prepare/recovery scan, forget where supported, transaction timeout behavior, and resource-manager identity (`isSameRM` or equivalent). Verify XA session affinity, XA-specific errors, and that operations are not retried unsafely; include XA data-source/host API integration. | JDBC `H2XAIntegrationTest`, `*XAIntegrationTest`; ODBC `tests/h2_l9_integration_test.cpp` and database-specific `*_l9_integration_test.cpp` |
| **L10 — Combined multinode + mTLS conformance** | Run a combined multi-server scenario that exercises L1–L9 behavior: non-XA failover and recovered-node reuse; sticky-session and XA affinity/failure behavior; SQL-error versus transport-error classification; and correct XA commit/rollback/recovery without unsafe rerouting or retries. All tested endpoints must require trusted client certificates. Verify successful client-certificate authentication through the public client API, and that connections fail when the client certificate is absent or untrusted; verify data outcomes after each recovery/failure transition. | Existing JDBC evidence covers multinode and XA separately (`org/openjproxy/grpc/client/MultinodeIntegrationTest`, `MultinodeXAIntegrationTest`, and database XA suites) but no JDBC mTLS integration test exists. ODBC combined suites cover operational/XA behavior but currently use plaintext gRPC. |

Oracle PL/SQL `RETURNING ... INTO` binds output parameters and is not a result-set form; it is outside this L2 capability and requires callable-statement output-parameter support.

These are minimum certification scenarios, not a replacement for database-specific compatibility tests. Add cases for any advertised client API, protocol feature, or database-specific behavior not exercised by this checklist. L10 requires a passing real multi-server run with mTLS for each database reported at L10.

### Interpreting reference-client evidence

The JDBC and C++ ODBC paths organize tests differently. JDBC has broad database-specific API suites and separate `org/openjproxy/grpc/client` operational tests; `MultinodeIntegrationTest` and `MultinodeXAIntegrationTest` provide examples of the non-XA and XA portions, but neither validates mTLS. ODBC's numbered suites cover database-specific L2–L10 operational behavior, but its channels currently use plaintext. Use these as examples of partial evidence, not as a claim that either client has achieved L10. Only a passing run of the candidate client’s own required scenarios counts toward its certification.
