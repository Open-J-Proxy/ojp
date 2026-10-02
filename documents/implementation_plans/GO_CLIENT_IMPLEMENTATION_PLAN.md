# Go Client Capability Implementation Plan

**Status:** Proposed; no client capabilities are implemented by this document.

**Goal:** Deliver the Go client in small, cumulative, independently testable increments. Each increment must finish one complete implementation level, including real-server/database integration tests, before the next level begins.

**Assessment confidence: High for source-level gaps; Medium for runtime behavior.** The evaluation inspected the Go sources, generated protocol bindings, existing tests, and CI configuration. It did not run the client or database suites, so existing workflows are evidence of available coverage, not a claim that their latest runs passed.

## 1. Scope and sources of truth

- [Implementation levels](../multi-language-client-spec/CLIENT_IMPLEMENTATION_LEVELS.md) define the L1–L10 capability boundaries. Each level includes all lower levels.
- [Client specification](../multi-language-client-spec/CLIENT_SPEC.md) and [AI-oriented contract](../multi-language-client-spec/CLIENT_SPEC_AI.md) define session propagation, wire types, routing, recovery, and operational expectations.
- [StatementService.proto](../../ojp-grpc-commons/src/main/proto/StatementService.proto) and [echo.proto](../../ojp-grpc-commons/src/main/proto/echo.proto), together with the current server implementation, determine actual wire behavior.
- [Go client](../../ojp-grpc-client-go/README.md), [service wrappers](../../ojp-grpc-client-go/internal/client/service.go), [multinode manager](../../ojp-grpc-client-go/internal/client/multinode.go), and [Go H2 workflow](../../.github/workflows/go-crud-test.yml) establish the current baseline.

Deliver a reusable Go protocol client, retaining the command-line application as a smoke example. A `database/sql` adapter, ORM integrations, and new database/server features are separate work, not prerequisites for these levels. Do not add an application-side database connection pool.

Do not change Java behavior or the shared protocol merely to simplify Go implementation. If an existing server limitation blocks a required case, record and resolve the dependency explicitly; do not silently narrow the level definition.

### Contract discrepancies to resolve during the relevant increment

The written contracts are not uniformly synchronized with the protocol:

- `createLob` is **bidirectional streaming** in the proto and server, although the specifications describe client-streaming. L5 must handle both directions and the actual reference lifecycle.
- `TimestampWithZone` contains a protobuf `instant`, not the flattened seconds/nanoseconds representation shown in parts of the AI contract. L2 must use the proto and server converter.
- Generated Go `SessionInfo` currently lacks the proto's `clientCount`, `maxAdmission`, and `observedPeak` fields. Regenerate rather than hand-edit bindings.
- The specifications' retry descriptions do not establish exactly-once writes. A stateless mutation whose result is unknown must not be replayed blindly.
- Some database/test examples in the specifications are broader than the concrete suites found in this checkout. Discover actual test classes and server support rather than treating example names or Java level claims as Go evidence.

## 2. Current evaluation

The Go README reports an overall L1 implementation, but lists every database's test-proven level as **Not established**. That is a reasonable starting distinction: basic code exists, but cumulative conformance has not been established.

| Level | Existing implementation/evidence | Gap before declaring the level complete |
|---|---|---|
| L1 — Connectivity + CRUD | Unary connect/update/terminate wrappers; query stream drained into a slice. The CLI asserts row/column counts and runs CRUD. H2 CI executes the CLI against a real server. | Dedicated repeatable integration tests, decoded scalar-value/update-count assertions, reliable cleanup, error handling, session updates, and an externally usable API. |
| L2 — Typed parameters + statements | Generated messages expose parameters, statement IDs, properties, and resource calls. | Wrappers accept SQL only; no typed parameter API, reusable statement abstraction, generated-key API, or metadata API. |
| L3 — Result-set protocol | `ExecuteQuery` receives messages until EOF. A CLI helper has a mock-stream unit test. | No incremental row API, pagination wrapper, cursor lifecycle, or multi-page integration coverage. Receiving a stream is not complete result-set support. |
| L4 — Local transactions | Single-endpoint start/commit/rollback wrappers and CLI round trips check row presence. | Dedicated transaction assertions, savepoints, isolation/reset coverage, and consistent ownership of returned session state. Missing L2/L3 prevents a cumulative L4 claim. |
| L5 — LOB + streams | Generated RPC bindings exist. | No LOB wrappers, reference hydration, chunked round trips, or integration coverage. |
| L6 — Affinity + routing safety | Session-to-endpoint tracker exists. | `targetServer` is not enforced; operation responses do not update the tracker; unhealthy/unknown bindings can fall through to another endpoint. |
| L7 — Multinode operations | Selection, health configuration, channel cache, and health-string generation exist. | No Go multinode integration tests; no connHash cache/reconnect contract or complete cluster-health propagation. CLI selects only the first endpoint and discards datasource names. |
| L8 — Resilience + recovery | Generic backoff helper and a redistribution helper exist. | SQL execution has no complete failover loop. Redistribution only adjusts local metrics and is not wired into recovery. Pool exhaustion and recovered-node reuse are unproven. |
| L9 — XA | Nine XA wrappers exist. | Requests omit required session context, calls independently select endpoints, and `xaIsSameRM` is missing. No XA integration suite. These wrappers do not establish working XA. |
| L10 — Operational conformance | No combined Go suite found. | All cumulative capabilities and combined fault/recovery scenarios require evidence. |

### Cross-cutting findings that affect sequencing

- Both client helpers and generated types are under Go `internal/`; the README's import examples are not a supported API for an unrelated external application.
- The CLI uses fixed client IDs and fixed demo table/row identifiers; concurrent/repeated test runs are not isolated.
- `GrpcExceptionHandler` maps status codes to synthetic SQL states and is not wired into service execution. It does not decode the server's `SqlErrorResponse` trailers. `IsConnectionLevelError` treats all `INTERNAL` errors as connection failures, including SQL errors.
- `StartTransaction` in the multinode wrapper selects without the input session's affinity. `TerminateSession` can silently do nothing for an unbound session and does not remove the local binding.
- Endpoint constructors leave the health flag false, while both selectors fall back to the first endpoint when none is healthy. Load-aware selection uses a metric that queries increment by returned message count, not a trustworthy active-session count.
- Health checks currently call `connect` with the synthetic JDBC URL `health-check`; use the actual supported heartbeat/full-validation paths instead.
- Manager shutdown returns early if health checking never started, even when channels were created.

**Recommendation:** Establish trustworthy L1 evidence first, then advance one level at a time. Keep production-supported execution single-endpoint through L5. Implement the complete safety boundary before enabling multinode use at L6; do not ship the existing routing skeleton as production-ready.

## 3. Delivery rules for every increment

1. One acceptance unit delivers one full level: behavior, public API, unit tests, integration tests, CI wiring, and documentation together. Internal commits may be smaller; no partial level is published as achieved.
2. Increment N depends on the accepted output of increment N−1. Run all lower-level suites, not just the new tests.
3. Integrations must exercise **Go → real OJP server → real JDBC database**. Mocks support unit/protocol failure tests but never replace database integration evidence.
4. Maintain a result matrix with database, target level, highest cumulative passed level, executed tests, explicit gaps, topology, server/client revisions, and CI run evidence. Distinguish planned targets from achieved results.
5. A required unsupported operation blocks the claim unless the level contract explicitly allows a database limitation. Mark the gap and keep the database at its previous fully proven level; do not count a skipped case as a pass.
6. Use bounded contexts and polling, isolated test data, and guaranteed resource cleanup. Repeatability, cancellation, and failure cleanup are acceptance requirements.
7. Use the existing Go/Maven tooling and standard Go testing framework. Preserve protocol compatibility, keep credentials out of logs/artifacts, and avoid unnecessary dependencies.

## 4. Integration harness and database policy

Build the reusable harness **inside increment 1**, not as a separate release that delivers no implementation level.

- Keep the current CLI smoke test. Add Go integration suites and reusable fixtures under the Go module, grouped by level and database; keep pure unit tests separately runnable.
- Use explicit per-database opt-in flags/configuration, modeled on the existing Java test flags. Default local runs must not unexpectedly require external databases. A selected CI suite must fail on missing infrastructure or zero executed tests, rather than silently skip.
- Extend the existing Go H2 workflow into the fast-fail gate. Build the server with **Java 25**, run it in **UTC**, download JDBC drivers, and use the actual `ojp.libs.path` setting. Respect the module's Go toolchain requirement.
- Probe readiness through a bounded protocol/database check, not only a startup log. Capture sanitized server logs and Go test reports; tear down server processes, containers, sessions, statements, cursors, LOBs, and test schemas even on failure.
- H2 remains embedded in the real Java server. From L6, use multiple server processes over a shared PostgreSQL database; separate embedded H2 instances are not evidence of shared-database failover.
- Reuse repository container images/configuration and the OJP Testcontainers setup where applicable to the Java harness. Do not assume the Java `ojp-testcontainers` module is directly importable from Go.
- For fault tests, control specific server processes/endpoints; verify requests and stored data with server-side evidence or a test observer. A metric change or successful retry alone is insufficient.

### Required progression matrix

These are **future targets**, not current certifications.

| Database/topology | Required acceptance path | Additional coverage policy |
|---|---|---|
| H2, one server | L1–L6 data-path regression; L7/L8 applicable single-server cases | No cumulative XA/L10 claim in this plan. |
| PostgreSQL, one server | L1–L5, then all applicable lower-level data-path cases | Mandatory alongside H2 from increment 1; add XA-enabled configuration at L9. |
| PostgreSQL, two servers | L6–L9 operational and affinity tests | Shared database; real server stop/restart and connection/resource accounting. |
| PostgreSQL, three servers | L10 combined operations | Include successive endpoint failures and recovery, not just one surviving pair. |
| MySQL | L1 in increment 1; cumulative L2–L8 as each increment lands | Database-specific SQL/type differences tested in the same level increment. L9/L10 remain unclaimed without dedicated XA evidence. |
| MariaDB, Oracle, SQL Server, DB2, CockroachDB | Individually opt-in certification against each completed level | Reuse the harness, supply appropriate dialect fixtures/drivers, and explicitly report unrun levels. These jobs do not block the initial H2/PostgreSQL/MySQL delivery; adding a database is a complete cumulative certification change, not a partial level. |

H2 gates database jobs; the required PostgreSQL/MySQL jobs must also pass before accepting their level increment. Proprietary database jobs may run in authorized environments but cannot produce a level claim without captured passing evidence. Java reference levels do not transfer automatically to Go.

## 5. Executable increments

### Increment 1 — Complete L1: basic connectivity and CRUD

**Dependencies:** None.

**Implementation checklist**

- [ ] Add a small supported public API with Go-owned connection/session/result types; keep wire details internal and preserve existing CLI behavior.
- [ ] Establish one authoritative current session per logical connection. Apply returned session updates before the next request; enforce closed-connection behavior and bounded, idempotent local cleanup.
- [ ] Generate a process-scoped client UUID, share endpoint channels safely, and close channels even when health checks were never started.
- [ ] Decode simple scalar results and update counts; capture/decode SQL trailers while preserving transport/context errors and vendor information.
- [ ] Add the harness and H2/PostgreSQL/MySQL CI acceptance described above; document single-endpoint support only.

**Unit/contract coverage:** scalar/null decoding, state transitions, connection configuration validation, SQL/transport/cancellation classification, and channel/session cleanup. Regenerate current bindings reproducibly from canonical proto sources and add descriptor/drift checks.

**Integration acceptance:** on each required database, create isolated data; insert, query, update, and delete; assert exact values and affected-row counts, including empty results. Test invalid SQL and constraints with real SQLState/vendor metadata, termination and post-close rejection, and deadline cleanup. Repeated runs must not conflict or leak sessions. Verify the public API from an external consumer package without importing `internal/`.

**Exit gate:** Dedicated cumulative L1 suites pass on H2/PostgreSQL/MySQL; the CLI remains functional. No claim of L2 or multinode support.

### Increment 2 — Complete L2: typed parameters and statements

**Dependencies:** Accepted L1.

**Implementation checklist**

- [ ] Expose parameter binding without SQL interpolation, reusable Statement/PreparedStatement operations, statement UUIDs/properties, generated keys, and basic column/database metadata through `callResource`.
- [ ] Define lossless Go-to-wire mappings for scalar numeric/string/byte types, exact decimals, temporal values/zones, and explicit SQL nulls.
- [ ] Enumerate every `ParameterTypeProto` value with its representation, server support, tests, and limitations. Cover supported arrays, URL/RowId, national strings, XML, and object values; reject unsupported representations explicitly.
- [ ] Define safe handling of resource-valued parameters in this matrix. If testing them requires LOB transport plumbing, include the necessary plumbing now; L5 still owns the complete streaming/LOB level.

**Unit/contract coverage:** oneof presence, null versus empty values, numeric overflow, decimal scale, temporal nanoseconds/timezones, 1-based indexes, property encoding, and unknown/unsupported types.

**Integration acceptance:** H2/PostgreSQL/MySQL parameter round trips with exact values, Unicode, binary and null data; timezone-sensitive values; prepared statement reuse/rebinding; plain statement variants; generated-key identity checks; label/type metadata; invalid binding and statement-close behavior. Use supported database-native fixtures for specialized types and record genuine driver limitations.

**Exit gate:** L1–L2 pass; no supported type relies only on mocked conversion tests. Any unresolved broad type-coverage requirement blocks the cumulative L2 claim for the affected database.

### Increment 3 — Complete L3: result-set protocol

**Dependencies:** Accepted L2.

**Implementation checklist**

- [ ] Add an incremental, bounded-memory row/result iterator and retain eager collection as a compatibility convenience.
- [ ] Process all stream result variants/session updates; retain cursor/statement handles and implement `fetchNextRows`.
- [ ] Expose supported cursor operations and close through `callResource`; distinguish EOF, query failure, cancellation, and explicit early close.

**Unit/contract coverage:** mixed stream messages, page boundaries, partial stream errors, malformed responses, iterator state, and double close.

**Integration acceptance:** force results larger than one fetch block; assert every row exactly once and in deterministic order, with metadata and session continuity. Exercise explicit pagination, supported navigation, empty results, early close, cancellation, and connection reuse after resource closure. Verify resources are released, not merely that the client iterator stops.

**Exit gate:** L1–L3 pass on required databases; multi-page reads and failure cleanup are proven without collecting all results internally.

### Increment 4 — Complete L4: local transactions

**Dependencies:** Accepted L3.

**Implementation checklist**

- [ ] Complete transaction ownership/state transitions and apply returned sessions after start, commit, and rollback.
- [ ] Add named/unnamed savepoints, rollback-to-savepoint, release, and isolation control via connection/savepoint resources.
- [ ] Define behavior for duplicate completion, invalid savepoints, operation errors, close during a transaction, and deadline expiry.

**Unit/contract coverage:** transaction state machine, savepoint ownership, invalid transitions, and current-session propagation.

**Integration acceptance:** verify commit durability and rollback from an independent connection, not just the transaction's own query. Test partial rollback/release and invalid savepoints; supported isolation behavior with two connections; isolation reset after reuse; query/cursor use inside transactions; failure/close rollback and no leaked transactions. Use database-supported isolation expectations rather than assuming identical semantics everywhere.

**Exit gate:** L1–L4 pass on H2/PostgreSQL/MySQL. This establishes single-endpoint transaction viability, not general production or multinode readiness.

### Increment 5 — Complete L5: LOB and stream semantics

**Dependencies:** Accepted L4.

**Implementation checklist**

- [ ] Complete bidirectional `createLob`, server-streaming `readLob`, hydratable references, and binding of BLOB/CLOB and supported stream types.
- [ ] Preserve returned session/statement/LOB identities and verify server-specific byte/character position conventions.
- [ ] Support bounded chunking, partial reads, freeing/closing resources, and cancellation without orphaned streams.

**Unit/contract coverage:** empty/large payloads, chunk boundaries, multibyte character lengths, invalid references/positions, simultaneous send/receive completion, and interrupted streams.

**Integration acceptance:** exact binary and Unicode text round trips over multiple chunks, including empty/null values; partial reads; streamed prepared-statement binding; LOBs inside committed/rolled-back transactions; reuse and freeing references; interrupted upload/download and cleanup. Verify database-specific BLOB/CLOB equivalents rather than relying on H2 alone.

**Exit gate:** L1–L5 pass on required databases, with bounded-memory stream behavior and server resource cleanup demonstrated.

### Increment 6 — Complete L6: affinity and routing safety

**Dependencies:** Accepted L5.

**Implementation checklist**

- [ ] Parse/configure all endpoints and datasource names; make `sessionUUID` plus returned `targetServer` authoritative for every stateful operation.
- [ ] Register/update affinity on every response, including query streams and resource/LOB calls; clear ownership only at the correct lifecycle boundary.
- [ ] Fail closed for unknown/unhealthy owners. Never erase a failed binding and then treat the session as stateless.
- [ ] Route transaction start using existing session state; keep cursor, statement, savepoint, and LOB operations on their owner.

**Unit/contract coverage:** address normalization, conflicting/unknown owners, newly assigned sessions, stale bindings, and concurrent tracker updates.

**Integration acceptance:** two real servers sharing PostgreSQL; confirm transactions, cursors, prepared statements, savepoints, and LOBs stay on the assigned node. Stop that node and assert an explicit failure with **no request sent to the other node**, including terminate/commit/rollback paths. Confirm independent stateless connections remain usable and tracker cleanup is correct. Retain H2/MySQL lower-level regressions and applicable affinity cases.

**Exit gate:** Complete cumulative L6; multinode safety is supported, but balancing/recovery readiness is not yet claimed.

### Increment 7 — Complete L7: multinode operations

**Dependencies:** Accepted L6.

**Implementation checklist**

- [ ] Complete round-robin and least-connections selection with meaningful active-session accounting; never select an unhealthy endpoint as fallback.
- [ ] Start/manage health checking automatically, separating heartbeat from full pool validation. Reinitialize required pools before marking recovered endpoints eligible.
- [ ] Generate, transmit, and consume cluster health; propagate changes without blocking query paths.
- [ ] Add thread-safe connHash caching, retained connection details, cache-hit connect avoidance, datasource isolation, and bounded stateless `NOT_FOUND` reconnect.

**Unit/contract coverage:** deterministic selection/ties, accounting, cache concurrency, property/datasource identity, health transitions, and shutdown ordering.

**Integration acceptance:** verify observed routing distribution and counts on shared PostgreSQL; healthy/down health strings and server receipt; readiness probes without leaking sessions; repeated connects skipping unnecessary RPCs; distinct datasource/credential caches. Restart a server to trigger real pool loss and assert one stateless reconnect; sticky sessions must fail instead. Validate MySQL equivalents for its L7 claim.

**Exit gate:** L1–L7 pass. Health metrics and cache behavior must have real-server evidence, not only local map assertions.

### Increment 8 — Complete L8: resilience and recovery

**Dependencies:** Accepted L7.

**Implementation checklist**

- [ ] Add bounded, context-aware stateless failover, attempted-endpoint exclusion, and a per-operation retry-safety policy.
- [ ] Never retry SQL errors, cancellation, pool exhaustion, sticky requests, or partially consumed streams. Surface ambiguous mutation/commit outcomes; do not promise exactly-once execution without a server guarantee.
- [ ] Wire recovery and bounded idle redistribution into actual connection/routing lifecycle; never migrate active transactions/cursors/LOBs or merely edit counters.
- [ ] Implement applicable client admission/throttle modes, session feedback, fair-share limits, transaction bypass, and recovery behavior required by the client contract.

**Unit/contract coverage:** retry budget/deadline, classification with trailers, admission counters/modes, AIMD updates, and ownership-preserving redistribution.

**Integration acceptance:** stop/restart real PostgreSQL nodes during stateless reads and new connections; assert unhealthy-node avoidance, pool reinitialization, recovered-node reuse, and bounded redistribution. Test all-nodes-down deadlines, failures after partial rows, and a mutation whose acknowledgement is lost with no unsafe duplicate replay. Saturate a deliberately small server pool; assert overload does not mark nodes unhealthy or cause a retry storm, fast work recovers, and throttling releases permits on every error path. Verify active transactions bypass client throttling without losing affinity. Run MySQL's applicable L8 cases.

**Exit gate:** L1–L8 pass; recovery preserves data/resource integrity and overload remains bounded. Any spec/server retry conflict is documented and resolved before claiming full conformance.

### Increment 9 — Complete L9: XA transactions

**Dependencies:** Accepted L8; a verified PostgreSQL XA datasource and prepared-transaction configuration.

**Implementation checklist**

- [ ] Always establish XA sessions through connect; include current session in all XA requests and propagate responses.
- [ ] Add `xaIsSameRM` and complete all ten XA operations, XID validation, flags, timeout handling, and XA-specific errors/votes.
- [ ] Maintain owner affinity throughout branch lifetime and retain enough resource-manager identity for explicit recovery.
- [ ] Define xaStart retry boundaries against the actual server contract; never silently relocate an existing branch or replay end/prepare/commit/rollback.

**Unit/contract coverage:** XID byte limits, flags, XA_OK/XA_RDONLY votes, session-containing requests, all operation mappings, and invalid lifecycle transitions.

**Integration acceptance:** real PostgreSQL XA setup; one-phase commit, two-phase prepare/commit, rollback, read-only vote, timeout set/get, recover scan semantics, and same/different resource-manager checks. Cover invalid flags/XIDs and `xaForget` success where supported or the correct real unsupported/error outcome. Stop the owning server and assert no cross-node branch continuation. Recover a prepared branch through a newly established recovery session where supported and verify final database contents.

**Exit gate:** PostgreSQL L1–L9 pass, with recovery evidence. Do not promote H2/MySQL or other databases to L9 because generated XA bindings or Java tests exist.

### Increment 10 — Complete L10: full operational conformance

**Dependencies:** Accepted L9 and resolution of all blocking specification/server compatibility gaps.

**Implementation checklist**

- [ ] Compose all L1–L9 capabilities in a three-node shared PostgreSQL topology with mixed SQL, prepared statements, pagination, LOBs, local transactions, and XA branches.
- [ ] Complete compatibility/configuration checks and operational cases still outstanding, including multidatasource behavior and admission feedback.
- [ ] Publish reproducible conformance results and explicit limitations; keep optional database certification separate.

**Unit/contract coverage:** protocol compatibility/descriptors, missing optional fields, unsupported enum behavior, lifecycle concurrency, and public API contracts.

**Integration acceptance:** successive node failures/restarts, recovery during mixed workloads, prepared-XA recovery, topology/health propagation, and resource cleanup after partial streams. Include bounded concurrency soak, cancellation/deadlines, pool overload, slow/fast workload coexistence, and channel/goroutine/session leak checks. Compare final database state with the expected committed-operation ledger; ambiguous outcomes must remain visible rather than counted as success. Exercise every documented client throttle/configuration mode and datasource override. Verify current server interoperability and supported previous versions where an actual compatibility policy/fixture exists.

**Exit gate:** PostgreSQL cumulative L10 passes; all required lower-level H2/MySQL regressions still pass. Publish database-specific achieved levels with links to executed CI evidence; unresolved required cases prohibit a full-conformance claim.

## 6. Common definition of done and execution order

For each numbered increment:

- [ ] Complete its implementation checklist and real-database integration acceptance.
- [ ] Run existing unit, build, static-check, and cumulative integration gates with the required toolchains; run Go race-enabled coverage for shared state.
- [ ] Ensure mandatory suites executed tests and did not pass by skipping.
- [ ] Assert final stored data and cleanup, not just RPC success or client-side counters.
- [ ] Scan changes/artifacts for credentials and review error/routing/retry safety.
- [ ] Update the Go README's API/configuration examples and achieved-level database matrix, recording exact evidence and remaining gaps.
- [ ] Merge the complete level increment before starting the next one.

Execute **L1 → L2 → L3 → L4 → L5 → L6 → L7 → L8 → L9 → L10**. Do not prioritize polishing existing XA/multinode skeletons ahead of missing lower-level data-path behavior.

**Decisions to confirm at the start of execution:** the supported public package/module publishing location, mandatory database CI availability, protocol-generation tooling, and the intended compatibility policy. Default to the topology/database gates above; do not let these decisions replace testable level acceptance.
