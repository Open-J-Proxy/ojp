# Request Boundary Methods: Current Behavior and Possibilities

## Executive summary

**OJP currently does nothing when `Connection.beginRequest()` or `Connection.endRequest()` is called.** Its JDBC connection inherits the default no-op implementations from `java.sql.Connection`. There is no `startRequest()` method in OJP's Java/protobuf sources; the standard JDBC name for the proposed start boundary is **`beginRequest()`**. This analysis interprets `startRequest` as that boundary, not as `startTransaction` or `XAResource.start`.

These methods could give OJP a useful signal: "this independent unit of work starts here and ends here." They are not transaction demarcation, and a unit of work can contain several transactions. Their biggest opportunities are request-level diagnostics, deterministic release of resources, and an explicit alternative to guessing whether SQL needs connection affinity.

**Recommendation:** Preserve the no-op baseline, prototype opt-in observation first, then evaluate acknowledged cleanup and explicit affinity as separate capabilities. Do not make request boundaries commit transactions, enable application-side pooling, silently replay writes, or imply that a logical request always uses one physical connection. A general interceptor SPI is a related but separate design.

**Confidence:** High for the current-behavior findings, supported by the driver, server actions, protocol, and Java 11 contract. Medium for the proposed architecture: driver/pool combinations, state reset, and failure recovery need real-server experiments. Performance benefits are hypotheses, not measured results.

**Scope:** Analysis only. No Java, protobuf, configuration, or runtime behavior is changed. Evidence is based on `main` at revision `b1dcba780d466eedf261c4b61d022d475d87572b`, inspected on November 7, 2026.

## 1. What the JDBC contract actually permits

The [Java 11 `Connection` contract](https://docs.oracle.com/en/java/javase/11/docs/api/java.sql/java/sql/Connection.html#beginRequest()) and [OpenJDK source](https://github.com/openjdk/jdk11u/blob/master/src/java.sql/share/classes/java/sql/Connection.java) define optional, vendor-specific hints, introduced in Java 9:

| Rule | Consequence for OJP |
| --- | --- |
| The default implementations are no-ops | Today's inherited behavior is an intentional JDBC fallback, not missing abstract-method implementation |
| A request is independent of other work with respect to connection-local state | Cross-boundary temporary tables, session variables, and other non-reproducible state cannot be assumed to survive |
| A request may contain multiple transactions | `beginRequest()` must not mean `setAutoCommit(false)`; `endRequest()` must not mean `commit()` |
| Boundaries are not nested | Repeated begin within an active request is a no-op, not a nested scope or an error |
| Repeated end, or end without begin, is a no-op | Cleanup must not run twice or release another request's resources |
| Rejecting a boundary with an active transaction is recommended, not required | A strict OJP policy can reject with `SQLException`, but that is a documented behavior change from today's no-op |
| Use should be largely transparent | Destructive reset, affinity, and admission behavior need explicit contracts and compatibility gates |

The API notes target pooling managers. **That does not mean OJP should introduce or recommend a client-side pool.** OJP's documented model disables application-side pooling: obtain a fresh virtual connection for an independent operation/unit of work, then close it promptly. Request hints can still be exposed on that connection, but ordinary `getConnection()` / `close()` already supplies a useful lifecycle. Implementing hints is most valuable if it adds information or capabilities beyond that existing lifecycle.

Also distinguish three scopes:

```text
Application unit of work: beginRequest -> SQL -> commit -> SQL -> endRequest
Database transaction:                  start -> SQL -> commit
Transport operation:       RPC 1          RPC 2            RPC 3 / streaming
```

Ending an RPC is not ending an application request. A gRPC SQL response can leave a cursor registered for later fetches; returning its first rows does not mean the unit of work has finished.

## 2. Current OJP implementation

### Driver and protocol

[`Connection.java`](../../ojp-jdbc-driver/src/main/java/org/openjproxy/jdbc/Connection.java#L37) implements `java.sql.Connection`, but does not override either boundary. Consequently, a boundary call does not validate the OJP connection, update a request flag, contact the server, reset state, or release resources. It also does not run the driver's normal `checkValid()` guard, including on a closed or force-invalidated connection.

The existing H2 and PostgreSQL extensive tests simply invoke the inherited methods and expect no exception: [H2 lines 329-331](../../ojp-jdbc-driver/src/test/java/org/openjproxy/jdbc/h2/H2ConnectionExtensiveTests.java#L329) and [PostgreSQL lines 353-355](../../ojp-jdbc-driver/src/test/java/org/openjproxy/jdbc/postgres/PostgresConnectionExtensiveTests.java#L353). They do not establish remote forwarding, cleanup, request identity, affinity, or transaction safeguards.

[`StatementService.proto`](../../ojp-grpc-contract/proto/StatementService.proto#L473) has connection, SQL, cursor/LOB, transaction, generic resource, and XA RPCs, but no dedicated request-boundary RPC. [`SessionInfo`](../../ojp-grpc-contract/proto/StatementService.proto#L183) has session and transaction identity plus server affinity and admission information, not a unit-of-work identifier or boundary generation.

### What already manages resources

| Existing mechanism | Current behavior | Why it matters |
| --- | --- | --- |
| Regular connect | [`ConnectAction`](../../ojp-server/src/main/java/org/openjproxy/grpc/server/action/connection/ConnectAction.java#L379) returns connection/client information without creating a database session | Beginning an empty request should not accidentally acquire a scarce database connection |
| Session/connection acquisition | [`SessionConnectionHelper`](../../ojp-server/src/main/java/org/openjproxy/grpc/server/action/streaming/SessionConnectionHelper.java#L116) reuses an existing session or obtains a connection; it can create a session and attach an admission-permit release hook | Request state must integrate with lazy allocation and existing ownership, not create a second pool or semaphore |
| Updates | [`ExecuteUpdateAction`](../../ojp-server/src/main/java/org/openjproxy/grpc/server/action/transaction/ExecuteUpdateAction.java#L109) requests a session for batches, tracked generated keys, returned result sets, and detected session-specific SQL; its stateless cleanup closes the statement/connection | Not every autocommit statement currently retains a database connection |
| Queries | [`ExecuteQueryAction`](../../ojp-server/src/main/java/org/openjproxy/grpc/server/action/transaction/ExecuteQueryAction.java#L76) obtains a session and registers a result set for consumption | A cursor lifetime can exceed a transport call |
| Explicit transactions | [`Connection.setAutoCommit`](../../ojp-jdbc-driver/src/main/java/org/openjproxy/jdbc/Connection.java#L182) starts a remote transaction when switching to manual commit; commit/rollback actions operate on the existing connection | Commit/rollback do not terminate that session or themselves establish a request boundary |
| Virtual connection close | [`Connection.close`](../../ojp-jdbc-driver/src/main/java/org/openjproxy/jdbc/Connection.java#L236) sends session termination synchronously or asynchronously according to existing configuration, then closes the client handle | `endRequest()` cannot simply call `close()` if the JDBC handle is to remain usable |
| Session termination | [`SessionManagerImpl`](../../ojp-server/src/main/java/org/openjproxy/grpc/server/SessionManagerImpl.java#L255) removes the session and rolls back dangling manual-commit work based on physical connection state; [`Session.terminate`](../../ojp-server/src/main/java/org/openjproxy/grpc/server/Session.java#L399) closes acquired non-XA connections, clears resource references, and releases its permit | This is whole-session disposal, not a reusable request-reset primitive; some close errors are logged rather than propagated |
| Abandoned sessions | [`SessionCleanupTask`](../../ojp-server/src/main/java/org/openjproxy/grpc/server/SessionCleanupTask.java#L42) detects inactivity and terminates sessions | A safety net exists, but it is not acknowledgement that an explicit request ended |
| Multinode and XA | [`MultinodeStatementService`](../../ojp-jdbc-driver/src/main/java/org/openjproxy/grpc/client/MultinodeStatementService.java#L168) binds sessions to servers; [`TerminateSessionAction`](../../ojp-server/src/main/java/org/openjproxy/grpc/server/action/session/TerminateSessionAction.java#L59) cooperates with the XA registry's completion/close conditions | An ordinary request end cannot override XA ownership or migrate live database-local state |

**Important correction to older analysis:** [SESSION_AFFINITY_ANALYSIS.md](./SESSION_AFFINITY_ANALYSIS.md) describes a historical absence of SQL affinity detection. Current code has [`SqlSessionAffinityDetector`](../../ojp-server/src/main/java/org/openjproxy/grpc/server/sql/SqlSessionAffinityDetector.java#L80), called by the update action. It recognizes several temporary-table, session-variable, and SQL `PREPARE` patterns in the first 200 characters. Explicit request affinity would complement that heuristic, not introduce OJP's first affinity mechanism. A bound OJP server is also not necessarily a single database connection: a session can lazily own both primary and replica connections.

## 3. Implementation strategies

These strategies are alternatives or composable layers, not a proposal to enable everything.

### A. Keep inherited no-ops; document the contract

Explain that the methods provide no additional lifecycle guarantees and retain ordinary virtual connection close as the boundary.

**Pros:** Zero new RPCs or request bookkeeping; maximum compatibility; no new pool/driver assumptions. Fits the recommended fresh-connection-per-unit-of-work model.

**Cons:** No request-level visibility or early release signal. Applications cannot explicitly ask for capabilities beyond current SQL/transaction/resource-driven session creation.

**Assessment:** A sound baseline, especially if there is no concrete caller that benefits. Explicit validating no-op overrides would improve diagnostics on closed/invalid handles, but are a separate observable change, not equivalent to today's inheritance.

### B. Forward hints to the underlying JDBC driver

Override the methods and invoke `beginRequest()` / `endRequest()` on backend JDBC connections. This would expose vendor-specific optimizations or request reset without initially making OJP own those semantics.

**Pros:** Leverages backend driver capabilities; familiar JDBC API; potentially useful vendor instrumentation or reset.

**Cons:** May still do nothing. The object obtained from a pool can be a wrapper whose behavior differs from the physical driver. Blind forwarding can force allocation, miss replica connections, or double-pair boundaries if the pool already invokes them. OJP's own cached state and resource UUIDs remain its responsibility.

**Design constraint:** Track each acquired lease, call begin before its first operation, and end only leases successfully begun. Inspect and test pool/provider behavior rather than unwrapping private internals or assuming universal support. Do not borrow connections merely to deliver hints for an empty request.

**Assessment:** Worth a vendor-specific experiment, not a portable cleanup guarantee.

### C. Track a logical request without changing SQL ownership

Maintain an active/inactive marker and generated request identity on the virtual connection. Propagate identity with operations where server observation is required, while keeping current acquisition, routing, and release rules.

**Pros:** Low semantic risk; does not require physical pinning; identifies one business operation across SQL calls and transactions. Can start with client-local timing and later add server visibility.

**Cons:** Client-only tracking cannot prove server cleanup or measure database occupancy. Server propagation requires protocol work, bounded state, and identity handling across lazy session creation and multinode routing.

**Assessment:** Best first prototype. Optional metadata could travel on existing RPCs without two additional boundary round trips, but a purely piggybacked end cannot acknowledge cleanup when there is no later RPC.

### D. Acknowledged request cleanup, keeping the virtual handle open

Use end as an opt-in release point for completed, request-owned server resources and connection leases. Preserve the client's open virtual connection and connection-level settings; create fresh remote session state lazily on later work.

**Pros:** Deterministic release earlier than abandonment timeout; reduces idle backend occupancy in units of work that finish before their virtual handle is closed. Gives operators a direct cleanup outcome.

**Cons:** Mostly redundant if callers already close promptly. Requires explicit ownership for cursors, statements, LOBs, and state. Reusing whole-session termination today can deregister a client and destroy UUIDs/settings; reacquisition needs correct registration and settings restoration. Acknowledgement adds latency.

**Design constraint:** Reject unfinished local transactions instead of committing them or switching autocommit on. Decide whether live resources cause rejection or are explicitly closed under the selected policy. Invalidate remote handles after successful cleanup; never let old UUIDs resolve into a new request. A close error must prevent a claim of clean reuse.

**Assessment:** Valuable only with demonstrated retained-resource workloads. Do not encourage long-lived client connection pools to manufacture that workload.

### E. Explicit affinity for the unit of work

Treat an opt-in begin as an intent to retain a database connection from first use through end, even across several autocommit statements or transactions. In a primary/replica deployment, a stronger "one physical connection" policy would need to select a role and prevent incompatible routing.

**Pros:** Avoids SQL pattern guessing for stored procedures, vendor session context, and temporary objects. Makes an intentional affinity scope visible. Supports a multi-step database-local workflow without holding a transaction merely to obtain affinity.

**Cons:** Pins a scarce connection during application think time, reducing multiplexing and potentially worsening backpressure. Forgotten end becomes a lease leak until close/timeout. Backend loss destroys local state; moving to another node does not restore it.

**Design constraint:** Acquire lazily, use the existing admission budget, and release exactly once. Preserve current behavior when unused. Do not infer single-connection affinity from `sessionUUID` alone, particularly with read/write splitting.

**Assessment:** Strongest functional use case, but require explicit opt-in and bounded duration. Callers needing this today should not assume invoking the inherited methods supplies it.

### F. A general request lifecycle interceptor SPI

Publish begin/end observation or narrowly controlled hooks to server plugins, integrating with the existing [request lifecycle interceptor analysis](./REQUEST_LIFECYCLE_INTERCEPTOR_ANALYSIS.md).

**Pros:** Reusable integrations for tracing, auditing, and policy; follows the project's extension-point approach. One unit-of-work context could correlate several per-RPC interceptor invocations.

**Cons:** A substantially larger public compatibility surface. Plugins can fail, block, or retain context; ordering and cleanup require precise guarantees. Per-RPC hooks alone cannot discover an application end. Database resource ownership must remain in core.

**Assessment:** Do not make implementing two JDBC hints depend on delivering a broad SPI. Start with built-in observation and expose extension points only for demonstrated needs.

## 4. Creative applications and their tradeoffs

| Possibility | What we could do | Pros | Cons / boundary of the guarantee |
| --- | --- | --- | --- |
| Unit-of-work flight recorder | Aggregate statement count, execution time, pool wait, rows fetched, failure, and end/close/timeout outcome by request | Finds chatty ORM/N+1 workflows and long application pauses that per-query metrics miss | Requires bounded buffers/sampling; SQL values can contain sensitive data; high-cardinality request IDs belong in traces, not metric labels |
| Request budgets | Share a deadline or maximum row/byte budget across all operations in a unit of work | Prevents many individually fast queries from becoming one expensive request; gives predictable workload limits | Duration includes application think time; streaming/cancellation is complex; exhaustion must be explicit and must not silently commit/rollback; reuse existing admission controls rather than holding another permit at begin |
| Database attribution capsule | Associate an authenticated application identity and trace context with each backend lease, then clear vendor session context at end | Correlates database diagnostics and audit events with the business operation | Needs vendor-specific set/reset; client-supplied labels are not authorization; arbitrary tenant context must never be trusted for access control or leaked to the next lease |
| Scoped micro-cache | Cache approved repeated reads only within the request, rather than across unrelated clients | Can cut duplicate reads and reduce global invalidation requirements | A request boundary is not a snapshot: external writes, volatile functions, isolation level, and local writes still matter; opt-in rules and transaction-aware invalidation are required; reuse existing query-cache machinery |
| Request-aware routing | Choose a role/policy at first use, keep it for an affinity scope, or retain read-your-writes intent until end | More predictable multi-step workflows; avoids reading replica data after a write when the policy requires primary | Primary pinning loses replica offload; primary routing is not snapshot isolation; database-local state cannot migrate; do not silently override existing routing when hints are unused |
| Leak coach | Report unfinished transactions, open cursors/LOBs, and requests that end only through close or inactivity timeout | Actionable developer feedback without initially changing lifecycle semantics | Noise if resources are intentionally live; warning mode is not cleanup; do not silently downgrade an enabled strict contract to warning-only |
| Replay eligibility envelope | Mark explicit independent units as candidates for a future opt-in replay facility | Gives a bounded place to record deterministic operations and detect unsupported dependencies | Boundaries do not imply idempotency or exactly-once execution; ambiguous commits, external effects, sequences, streaming, and local state defeat generic replay; unsafe as automatic failover behavior |

For example, a flight recorder could explain: "one request ran 40 short SELECTs but held its backend lease mostly while the application was idle." That is a different operational question from "which query was slow?" It should measure actual lease occupancy; a logical request without a pinned connection may hold no connection between statements.

An affinity scope could help a reporting workflow that calls a procedure to prepare temporary data, runs several reads, and then releases the scope. That must select a compatible database role and include vendor-aware cleanup. Returning a pooled connection does not, by itself, prove that arbitrary SQL-created local state has been removed.

**Most promising experiments:** flight recorder, leak coach, and explicit affinity. **Most speculative:** scoped caching and replay. Do not bundle speculative semantics into standard hint calls.

## 5. Candidate contract and implementation surfaces

### Small state model

For an enabled stateful mode, use connection-owned state, not a thread-local; callers may change threads.

```text
INACTIVE --successful begin--> ACTIVE(request ID, generation)
ACTIVE   --begin-------------> ACTIVE (no-op)
INACTIVE --end---------------> INACTIVE (no-op)
ACTIVE   --successful end----> INACTIVE
ACTIVE   --policy rejection--> ACTIVE (caller can resolve the dependency)
ANY      --close-------------> CLOSED
```

A network failure after a remote end was applied is different from a local policy rejection. The client cannot assume either "still active" or "cleanly ended." Preserve an explicit uncertain/invalid state until the same boundary is resolved or the connection is disposed; do not run new work under an unverified cleanup guarantee.

Serializing boundary transitions prevents a late end from releasing a newer request. A request ID plus generation should bind all remote mutations and resource registrations to the correct scope. This is not a promise that arbitrary concurrent use of one JDBC connection becomes supported.

### Wire design alternatives

| Wire approach | Pros | Cons |
| --- | --- | --- |
| Dedicated begin/end RPCs, response carrying updated session/request state | Clear lifecycle, acknowledgement and SQL error mapping; suitable for cleanup and pinning | Additional round trips; explicit capability negotiation and client/server updates |
| Piggyback request identity/start metadata on existing operations | Keeps an unused/empty scope cheap; preserves lazy allocation | No end acknowledgement without an actual end message; every applicable operation and reconnect path must carry context |
| Extend generic `callResource` | Reuses reflective backend connection dispatch | Current [`MethodNameGenerator`](../../ojp-server/src/main/java/org/openjproxy/grpc/server/utils/MethodNameGenerator.java#L22) has no begin/end prefixes; current [`RES_CONNECTION` handling](../../ojp-server/src/main/java/org/openjproxy/grpc/server/action/resource/CallResourceAction.java#L163) creates/acquires a session; reflection does not model request cleanup/identity |

For observation, start with local state or piggybacked context. For acknowledged cleanup/affinity, prefer an explicit contract rather than inventing resource-method names or overloading transaction RPCs. An empty begin/end pair should not borrow a database connection.

An implementation would need coordinated changes in the JDBC connection and resource wrappers, `StatementService` client implementations, protobuf, multinode routing/reconnect logic, server action/context/session ownership, and applicable pool providers. It must cover queries, updates, batches, generated keys, callable statements, generic resources, cursor fetches, LOB streams, and transaction operations. Java's methods take no metadata arguments; richer budgets/role selection would need separately documented configuration or an OJP-specific API, not a fictional JDBC `startRequest(options)` overload.

### Correctness and compatibility conditions

| Concern | Required policy for an implementation |
| --- | --- |
| Local transactions | Never commit implicitly. Detect unfinished work on physical state, not solely the client's transaction-status enum. Because commit/rollback leave manual-commit mode active, `autoCommit=false` is a conservative dependency signal, not proof of pending writes; document strict rejection or a more reliable detector |
| XA | Never equate request end with `xaEnd`, prepare, completion, or logical XA connection close. Initially keep enabled stateful cleanup/affinity out of XA and reject unsupported use explicitly; later integrate only with registry ownership |
| Connection state | Specify what survives: autocommit, isolation, read-only, schema/catalog, timeouts, client info, and type mappings. Reapply reproducible settings after reacquisition and keep local driver caches consistent. Pool defaults and a begin-time snapshot are different baselines |
| Vendor SQL state | Standard setters cannot erase every temporary object, session variable, lock, or prepared SQL object. Require proven vendor reset, explicit disposal, or rejection of unsupported clean-reuse guarantees |
| Streams and resources | Do not close an in-flight cursor or LOB merely because an RPC completed. Request end must reject or explicitly coordinate live work under its documented policy; cancellation and close need bounded cleanup |
| Failures | Surface boundary and cleanup errors through standard SQL error mapping. If reset fails, evict/dispose the affected lease and invalidate untrusted client state; never report successful reuse |
| End versus close | Keep end synchronous when it promises cleanup. Existing optional asynchronous close remains a separate policy. Close must still attempt cleanup when end failed |
| Registration and permits | Do not deregister the client accidentally while retaining the virtual handle; preserve fair-share accounting. Release existing session permits once and only after resource ownership ends |
| Retries and multinode | Route end to the scope's owner. Deduplicate the same request/generation without repeating vendor end/reset; bound deduplication retention. Never replay SQL to recover a lost backend-local affinity scope |
| Mixed versions | Additive protobuf fields alone do not guarantee semantics. Negotiate capabilities: observation may degrade explicitly; requested cleanup/pinning must fail clearly when unsupported, not silently succeed |
| Abandonment | Retain close and inactivity cleanup as fallbacks; a live request must not disable timeout protection. Cleanup may invalidate the caller's scope and must be detectable |

## 6. Suggested evaluation sequence

1. **Establish the baseline and callers.** Keep no-ops by default. Determine whether applications/frameworks actually invoke hints with OJP's non-pooled client model, and compare against fresh virtual connection plus close. If there is no additional value, stop here.
2. **Prototype observation.** Track a connection-owned scope and optionally propagate identity. Measure whole-request latency, actual lease occupancy, and resource-at-end diagnostics without changing SQL or routing.
3. **Evaluate strict cleanup separately.** Prototype non-XA acknowledged end with transaction/resource guards, reproducible state restoration, and explicit invalidation on failure. Demonstrate value beyond prompt close before adopting it.
4. **Evaluate affinity and backend forwarding separately.** Test multi-step local-state workloads, primary/replica behavior, lease limits, and pool/driver pairing. Only enable a capability where its guarantees are demonstrated.
5. **Consider advanced policy/plugin uses.** Budgets or plugin hooks should build on a proven scope. Caching and replay require independent correctness designs, not just begin/end implementation.

No default-on behavior change or universal vendor support is recommended by this document.

## 7. Evidence an implementation should produce

| Scenario | Required result |
| --- | --- |
| Default mode and callers that never use hints | Existing results, transaction behavior, routing, and resource lifecycle unchanged |
| Empty request, duplicate begin/end, end without begin | No physical lease for the empty scope; exactly one effective transition/release for a stateful scope |
| Request spanning several transactions | No automatic commit at end; committed work retained; unfinished work rejected under strict policy |
| Ordinary SQL, batches, generated keys, callables, cursor fetches, LOBs | Consistent scope ownership and no usable stale remote UUIDs after cleanup |
| State restoration and vendor reset | Standard settings reproduce correctly; unsupported SQL-local reset is not claimed; reset failure removes the lease from reuse |
| Affinity under primary/replica and multinode | Verify backend identity, not merely session UUID; appropriate role maintained; node loss surfaces lost state rather than successful transparent migration |
| Interrupted/end-response-lost cleanup | Same boundary retried safely; uncertainty blocks unsafe reuse; next generation cannot be released by a delayed end |
| XA and mixed versions | Clear unsupported-mode errors or explicitly tested support; no early XA backend return or success-shaped downgrade |
| Resource cleanup and fair-share accounting | Pool active leases, admission permits, registered resources, and client counts return to the expected baseline |

Use targeted unit tests for state transitions and protocol handling, plus public JDBC integration scenarios against a real OJP server and the relevant database/pool combinations. The current "should not throw" calls are insufficient for an enabled capability.

Compare at least: hints unused, empty hints, observation-only, prompt close, cleanup, and affinity. Report throughput, p50/p95/p99 latency, extra RPC count, pool wait, backend lease occupancy, retained resources, and error outcomes. Measure pool saturation and application think time; request pinning can improve affinity correctness while making capacity worse. Agree acceptable overhead and resource-release targets before evaluating results rather than inventing performance percentages.

## Conclusion

The methods are useful **signals**, not missing transaction primitives. OJP already has much of the resource and affinity machinery, but not a reusable request-boundary contract. The safest creative direction is to make units of work visible first, then add explicitly chosen lifecycle capabilities where measurements and compatibility evidence justify them. Keep fresh virtual connections and prompt close as the ordinary model.
