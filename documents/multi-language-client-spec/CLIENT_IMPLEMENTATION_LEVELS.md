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

### Important interpretation note

- **Operational levels (L7/L8/L10)** are validated primarily in protocol-level multinode test suites under `org/openjproxy/grpc/client`.
- Those tests validate client behavior independent of SQL dialect, while database-specific suites validate SQL/type/driver behavior.
- For this reason, operational capability may be considered “platform-proven” even when a specific database does not have a dedicated multinode test class.

---

## 4) How to Use This Scale for New Clients

1. Start by targeting **L1 → L4** for first production viability.
2. Add **L5/L6** before claiming broad compatibility.
3. Add **L7/L8** before claiming multinode production readiness.
4. Add **L9/L10** when distributed transactions and full operational resilience are required.

When publishing a new language client, report:
- target level,
- tested level,
- database-by-database achieved level,
- explicit gaps by level (for example: “L9 missing for MySQL and DB2”).
