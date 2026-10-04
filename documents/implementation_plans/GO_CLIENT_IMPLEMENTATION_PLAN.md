# Go Client Implementation Plan

**Status:** Proposed. This document does not add client features.

**Goal:** Grow the Go client in small steps. Each step delivers one complete implementation level for a database family, with integration tests.

**Confidence:** High for the code gaps listed below; Medium for runtime behavior. The review checked source code, tests, and CI setup, but did not run database tests.

## 1. Scope and references

- [Implementation levels](../multi-language-client-spec/CLIENT_IMPLEMENTATION_LEVELS.md): L1–L10. Each level includes all earlier levels.
- [Client specification](../multi-language-client-spec/CLIENT_SPEC.md) and [AI contract](../multi-language-client-spec/CLIENT_SPEC_AI.md): expected client behavior.
- [StatementService.proto](../../ojp-grpc-commons/src/main/proto/StatementService.proto) and [echo.proto](../../ojp-grpc-commons/src/main/proto/echo.proto): actual messages and RPCs.
- [Go README](../../ojp-client-go-database-sql/README.md), [client code](../../ojp-client-go-database-sql/client/), and [Go H2 L1 workflow](../../.github/workflows/go-h2-l1.yml): current implementation and coverage.

Build a reusable Go client and keep the command-line example. A `database/sql` adapter and ORM support are separate work. Do not add an application-side database pool.

Use the proto and current server behavior when the written specs differ. Record any server limitation that blocks a level; do not weaken the level just to mark it complete.

### Known differences to check

- `createLob` streams in **both directions**, although parts of the specs describe uploads only.
- `TimestampWithZone` uses a protobuf `instant`, not separate seconds/nanoseconds fields.
- Generated Go `SessionInfo` is missing `clientCount`, `maxAdmission`, and `observedPeak`. Regenerate it from the proto; do not edit generated code by hand.
- Retrying a write does not guarantee it runs only once. If the result is unknown, report that rather than blindly repeating it.
- Example test names and Java level claims are not proof of Go support.

## 2. Current state

The Go client now has a public single-endpoint L1 API and an opt-in H2 real-server integration suite. The H2 suite passes locally against one real OJP server and H2; CI confirmation is pending. Java coverage does not establish Go coverage.

| Level | What exists | What is missing |
|---|---|---|
| L1: Connect and CRUD | Public API; generated process UUID; session response updates; H2 L1 integration suite and CLI smoke test. | H2 CI confirmation is pending; other database families still need their own suites. |
| L2: Types and statements | Generated parameter and statement messages. | Typed binding, reusable statements, generated keys, and metadata APIs. |
| L3: Results and cursors | Query messages are read until EOF into a slice. | Incremental reads, pagination, cursor operations, and cleanup tests. |
| L4: Local transactions | Start/commit/rollback calls and CLI examples. | Savepoints, isolation/reset tests, and consistent session handling. |
| L5: LOBs and streams | Generated RPCs. | Upload/download APIs, usable LOB references, and chunked integration tests. |
| L6: Session routing | A session-to-server map. | Enforcing `targetServer`, updating bindings from responses, and refusing unsafe rerouting. |
| L7: Multiple servers | Basic selection, health settings, channels, and health-string generation. | Tested balancing, health propagation, pool-key caching, and reconnect behavior. |
| L8: Recovery | Backoff and redistribution helpers. | Complete failover/recovery flow and overload tests. Redistribution currently changes only local metrics. |
| L9: XA transactions | Nine XA wrappers. | Session fields in requests, stable routing, `xaIsSameRM`, and XA integration tests. |
| L10: Full operations | No combined Go suite found. | Combined data, routing, failure, and XA recovery tests. |

Other important gaps:

- Code and generated types are under Go `internal/`; unrelated applications cannot use the README's imports.
- The CLI uses fixed client IDs and table/row names, so runs can conflict.
- SQL error trailers are not decoded by service calls. The error helper can treat database errors as server failures.
- Transaction start can ignore an existing server binding. Session close can silently do nothing and leave a binding behind.
- Servers start with health set to false, but selection can fall back to an unhealthy server. Load metrics count query messages rather than active sessions.
- Health checks use the fake JDBC URL `health-check`. Shutdown can leave channels open if health checking never started.

**Recommendation:** Complete the lower levels first. Support one endpoint through L5. Do not enable general multinode use until L6 routing safety is tested.

## 3. Small increments by database family

An increment is **one family at one level**, not all databases at once. For example, `H2-L2` and `PG-L2` are separate changes. Each includes code, unit tests, integration tests, CI, and docs.

Use this family order at each level:

1. **H2**, where it can test the full level.
2. **PostgreSQL and CockroachDB** (`PG`).
3. **MySQL and MariaDB** (`MY`).
4. **Oracle** (`ORA`).
5. **SQL Server** (`SQL`).
6. **DB2** (`DB2`).

PostgreSQL and CockroachDB use the PostgreSQL JDBC driver. MySQL and MariaDB share a wire-protocol family, but may use different JDBC drivers. Reuse test setup where useful, but run each database separately: shared drivers/protocols do not mean identical SQL, types, isolation, LOBs, or XA support.

### Planned work queue

These are targets, not achieved levels. Each range means a separate increment at **every** level in the range.

| Family | Increment IDs | Family-specific integration checks |
|---|---|---|
| H2 | `H2-L1` through `H2-L8`, where supported | Embedded H2 first for SQL/types/transactions/LOBs. For multi-server tests, use a shared H2 TCP database if supported by the harness, not separate embedded databases. |
| PostgreSQL + CockroachDB | `PG-L1` through `PG-L8` | Run both databases. Check PostgreSQL types/timezones and CockroachDB transaction/isolation differences. Use a shared database for node failure tests. |
| PostgreSQL XA/full operations | `PG-L9`, `PG-L10` | PostgreSQL XA setup and prepared-transaction recovery. CockroachDB stays at its last proven level unless separate XA support is verified. |
| MySQL + MariaDB | `MY-L1` through `MY-L8` | Run both databases with their JDBC drivers. Check generated keys, decimal/temporal values, charset, isolation, and LOB behavior. |
| Oracle | `ORA-L1` through `ORA-L9` | Oracle SQL/identity fixtures, null/empty-string behavior, temporal values, LOBs, and configured XA support. |
| SQL Server | `SQL-L1` through `SQL-L9` | SQL Server SQL/identity fixtures, Unicode/temporal types, isolation, LOBs, and configured XA support. |
| DB2 | `DB2-L1` through `DB2-L8` | Schema setup, SQL/identity fixtures, types, isolation, and LOB behavior. |

XA/full-operation extensions for other databases are separate increments after support and test infrastructure are confirmed. Do not assume support from the family name.

### Dependencies and release rules

- A database can reach L3 only after its own L1 and L2 pass. Higher levels follow the same rule.
- The first family at a level adds shared Go features. Later families reuse them and add only the changes and tests they need.
- Start with `H2-L1`, then `PG-L1`, `MY-L1`, `ORA-L1`, `SQL-L1`, and `DB2-L1`; repeat that order for L2 and later levels. A blocked family need not hold back others, but cannot skip its own missing level.
- If H2 cannot prove a full level, record the gap and use the next supported family to implement it. Partial H2 checks are regression tests, not a new H2 level claim.
- A paired-family increment must pass on both members to be complete. If one is blocked, split it into clearly named database-specific increments and publish separate results.
- Oracle, SQL Server, and DB2 increments wait for authorized test environments and drivers. They are planned work, not required dependencies of the first H2 release.
- A skipped required case does not count as passing. Keep that database at its last complete level unless the level definition explicitly allows the limitation.
- After each merge, update a per-database table with achieved level, gaps, test names, topology, revisions, and CI results. Java coverage does not transfer to Go.

## 4. Integration test setup

Build the shared harness inside `H2-L1`; do not make a separate harness-only release.

- Tests must run **Go → real OJP server → real JDBC database**. Mocks help test errors and message handling, but do not replace integration tests.
- Keep unit tests separate and retain the CLI smoke test. Group integration tests by family, database, and level.
- Add a separate opt-in test switch for each database. A selected CI job must fail if its database is unavailable or no tests run.
- Extend the Go H2 workflow as the quick first gate. Add family jobs in their own increments; PostgreSQL/MySQL are not required to merge `H2-L1`.
- Build the server with **Java 25**, start it in **UTC**, and use the current Go module's toolchain requirement.
- Follow the existing driver-download/server-start setup. Reuse repository containers and Java Testcontainers fixtures where useful; the Java module cannot be imported directly into Go.
- Check readiness with an actual protocol/database call and a timeout, not just a log message.
- Use unique test data. Always close sessions, statements, cursors, LOBs, processes, and containers, including after failures.
- Save test reports and server logs without credentials. For routing tests, record which server received each request.
- L6–L9 use two OJP servers connected to one shared database. L10 uses three. Separate embedded databases cannot prove shared-data failover.
- Run the changed family's complete lower-level suite and regressions for already-supported families. CI may start with H2, but each released database needs its own passing evidence.

## 5. Level checklists

Apply the matching checklist below to **each family increment** in the queue. “Pass L1–Ln” means all required cases through level n pass for that database.

### L1 — Connect and CRUD

**Build**

- [ ] Add a supported public API; keep generated messages internal and preserve the CLI.
- [ ] Store current session state and apply server responses before the next request.
- [ ] Generate one client UUID per process; share channels safely and close them reliably.
- [ ] Decode basic values, update counts, SQL error trailers, and transport/context errors.
- [ ] Add the harness, the family's CI job, and repeatable proto generation checks.

**Unit tests:** Null/scalar values, session states, configuration validation, error handling, and cleanup.

**Integration tests:** Create isolated data; insert, read, update, and delete. Check exact values, affected rows, and empty results. Test bad SQL, constraints, SQLState/vendor errors, timeouts, session close, and rejection after close. Repeat without conflicts or leaks. Check the API from an external package without `internal/` imports.

**Done:** The database passes L1 and the CLI still works. Support remains single-endpoint.

### L2 — Types and statements

**Build**

- [ ] Bind parameters without SQL interpolation; add reusable statements, IDs/properties, generated keys, and basic metadata through `callResource`.
- [ ] Map numeric, string, bytes, exact decimal, temporal/timezone, and null values without losing data.
- [ ] List every `ParameterTypeProto` mapping and test supported arrays, URL/RowId, national strings, XML, and object values. Reject unsupported forms explicitly.
- [ ] Add any LOB plumbing needed for resource-valued parameter tests; complete LOB streaming remains L5 work.

**Unit tests:** Null versus empty values, numeric limits, decimal scale, nanoseconds/zones, parameter indexes, properties, and unsupported types.

**Integration tests:** Exact typed round trips, Unicode/binary/null values, timezone-sensitive data, statement reuse/rebinding, plain statement variants, generated keys, metadata, invalid bindings, and close behavior. Use each database's supported types and record limitations.

**Done:** L1–L2 pass. Type support is not based on mock tests alone.

### L3 — Results and cursors

**Build**

- [ ] Add a row iterator that does not collect the whole result in memory; keep eager reads as a convenience.
- [ ] Handle every result message and session update; implement `fetchNextRows`.
- [ ] Add supported cursor operations and close via `callResource`; distinguish EOF, failure, cancellation, and early close.

**Unit tests:** Mixed messages, page boundaries, partial errors, invalid responses, iterator states, and double close.

**Integration tests:** Read more than one fetch block; check each row exactly once in order. Test pagination, metadata, supported navigation, empty results, early close, and cancellation. Confirm server resources are released and the connection can be reused.

**Done:** L1–L3 pass, including multi-page reads and failure cleanup.

### L4 — Local transactions

**Build**

- [ ] Complete start/commit/rollback state handling and returned-session updates.
- [ ] Add named/unnamed savepoints, rollback-to-savepoint, release, and isolation control.
- [ ] Define repeated completion, invalid savepoint, error, timeout, and close behavior.

**Unit tests:** Transaction states, savepoint ownership, invalid operations, and session updates.

**Integration tests:** Check commit and rollback from an independent connection. Test savepoints, supported isolation with two connections, isolation reset after reuse, cursors inside transactions, and rollback/cleanup after errors or close. Use each database's isolation rules.

**Done:** L1–L4 pass. This is single-endpoint transaction support, not multinode readiness.

### L5 — LOBs and streams

**Build**

- [ ] Complete two-way `createLob`, streaming `readLob`, usable LOB references, and BLOB/CLOB/stream binding.
- [ ] Keep session, statement, and LOB IDs; check byte/character position rules.
- [ ] Add bounded chunks, partial reads, resource release, and cancellation.

**Unit tests:** Empty/large data, chunk boundaries, Unicode lengths, bad references/positions, send/receive completion, and interrupted streams.

**Integration tests:** Exact binary/text round trips over several chunks, null/empty values, partial reads, streamed statement binding, LOBs in committed/rolled-back transactions, reference reuse/release, and interrupted upload/download cleanup. Test each database's LOB types.

**Done:** L1–L5 pass without unbounded buffering or server resource leaks.

### L6 — Session routing safety

**Build**

- [ ] Parse all endpoints/datasource names; route stateful calls using `sessionUUID` and `targetServer`.
- [ ] Update bindings from every response, including streams and resource/LOB calls.
- [ ] Fail when the owner is unknown or down; never erase a binding and silently reroute.
- [ ] Keep transactions, statements, cursors, savepoints, and LOBs on their owner.

**Unit tests:** Addresses, missing/conflicting owners, new sessions, stale bindings, and concurrent map updates.

**Integration tests:** Use two servers over the family's shared database. Verify every stateful resource stays on its owner. Stop that owner; assert failure and **no request to the other server**, including commit, rollback, and close. Independent stateless connections must still work. Check binding cleanup.

**Done:** L1–L6 pass. Safe routing is supported; balancing/recovery is not yet claimed.

### L7 — Multiple-server operations

**Build**

- [ ] Complete round-robin/least-connections selection and active-session counts. Never fall back to an unhealthy endpoint.
- [ ] Manage health checks; separate heartbeat from database/pool validation. Prepare pools before a recovered node is eligible.
- [ ] Send and read cluster health without blocking queries.
- [ ] Cache pool keys (`connHash`) safely; keep connection details and datasource separation. Reconnect once on stateless `NOT_FOUND`.

**Unit tests:** Selection/ties, counts, cache concurrency, datasource identity, health changes, and shutdown.

**Integration tests:** Verify actual routing and server counts, health messages, and leak-free probes. Check repeated connects skip unnecessary RPCs and separate credentials/datasources stay separate. Restart a server to lose its pool; check one stateless reconnect and rejection of lost sticky sessions.

**Done:** L1–L7 pass with real-server evidence, not only client-side map checks.

### L8 — Failure and recovery

**Build**

- [ ] Add timeout-aware stateless failover, retry limits, and exclusion of already-tried nodes.
- [ ] Do not retry SQL errors, cancellation, overload, sticky calls, or partially read streams. Report unknown write/commit results.
- [ ] Connect recovery/idle redistribution to real routing and resource changes; never move active transactions/cursors/LOBs.
- [ ] Add required admission/throttle modes, fair-share limits, transaction bypass, and gradual recovery.

**Unit tests:** Retry limits/deadlines, error trailers, throttle modes/counters, gradual limit changes, and safe redistribution.

**Integration tests:** Stop/restart nodes during stateless reads/connects. Verify avoidance, pool recreation, recovered-node reuse, and bounded redistribution. Test all nodes down, partial results, and lost write acknowledgements without duplicate replay. Fill a small server pool; verify clean overload errors, healthy nodes, no retry storm, permit cleanup, and recovery. Transactions must bypass client throttling without losing their owner.

**Done:** L1–L8 pass. Resolve spec/server retry conflicts before claiming full support.

### L9 — XA transactions

**Build**

- [ ] Connect XA sessions explicitly, include their current session in all requests, and apply responses.
- [ ] Complete all ten XA calls, including `xaIsSameRM`, XID checks, flags, timeouts, and errors/votes.
- [ ] Keep branch ownership and resource-manager identity for recovery.
- [ ] Define safe `xaStart` retries; never relocate a live branch or replay end/prepare/commit/rollback.

**Unit tests:** XID limits, flags, XA votes, request session fields, operation mapping, and invalid states.

**Integration tests:** Use the database's verified XA setup. Test one-phase and two-phase commit, rollback, read-only votes, timeout calls, recovery scans, and same/different resource managers. Test bad flags/XIDs and `xaForget` success or its expected unsupported/error result. Stop the owner and reject cross-node continuation. Recover prepared work through a new recovery session where supported; verify stored data.

**Done:** That database passes L1–L9 with recovery evidence. Paired databases without XA evidence remain at their earlier level.

### L10 — Full operations

**Build**

- [ ] Combine L1–L9 in a three-server setup: statements, pages, LOBs, local transactions, and XA.
- [ ] Complete configuration, multiple-datasource, admission, and compatibility checks.
- [ ] Publish repeatable test results and remaining limits.

**Unit tests:** Protocol changes, missing optional fields, unsupported enums, concurrent cleanup, and public API behavior.

**Integration tests:** Successive node failures/restarts during mixed work, prepared-XA recovery, health updates, and cleanup after partial streams. Run bounded concurrent load, deadlines/cancellation, overload, and slow/fast queries together. Check channel/goroutine/session leaks. Compare stored data with expected committed work; never count an unknown result as success. Test documented throttle modes and datasource overrides. Check older servers only where a supported compatibility policy and test setup exist.

**Done:** That database passes L1–L10; earlier supported families still pass regression tests. Missing required cases block the claim.

## 6. Done checklist for every family/level increment

- [ ] Complete the level's code, unit tests, and integration cases for each selected database.
- [ ] Pass all earlier levels for that database and regressions for already-supported families.
- [ ] Run existing builds/checks and Go race tests for shared state with the required toolchains.
- [ ] Confirm required tests actually ran; skips are not passes.
- [ ] Check final stored data, request routing, and resource cleanup.
- [ ] Scan for credentials and review routing/retry safety.
- [ ] Update API examples, configuration docs, and the per-database achieved-level table with CI evidence.
- [ ] Merge the complete increment before raising that database's claimed level.

Before execution, confirm the public package location, proto-generation tools, compatibility policy, and available database CI environments. Keep these decisions small; they do not replace integration tests.
