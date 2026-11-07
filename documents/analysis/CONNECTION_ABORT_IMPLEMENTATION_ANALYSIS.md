# Connection.abort(): Current Behavior and Implementation Possibilities

**Date:** 2026-11-07

**Status:** Analysis only; all designs below are proposals

**Source baseline:** `main` at `b1dcba780d466eedf261c4b61d022d475d87572b`

**Scope:** JDBC client, gRPC contract/routing, server sessions, non-XA pools,
primary/replica resources, and XA recovery. No Java, protocol, or configuration
changes are made by this analysis.

For a shorter comparison, see the
[summary](./CONNECTION_ABORT_ANALYSIS_SUMMARY.md).

## 1. Executive conclusion

`Connection.abort(Executor)` currently does nothing beyond checking connection
validity and throwing an unsupported-feature exception. Supporting it could
give applications a targeted emergency stop for stuck connections, abandoned
requests, and unhealthy backend sessions.

The best direction is **a dedicated, idempotent abort control operation combined
with pool-aware physical destruction and concurrent lifecycle fencing**. A
shortcut that submits `close()` is not enough: normal pool close is designed
to return connections for reuse, and graceful rollback can wait behind exactly
the work we need to stop.

An OJP connection is not necessarily one socket or even one physical connection.
It can initially have no server session, later acquire a primary and a replica,
and use a transport shared with other connections. The implementation must
terminate only resources still owned by that logical connection.

**Confidence:** High for the source-level findings below. Medium for the
recommended architecture: the ownership/race model is necessary, but exact
abort and eviction ordering must be proven against the deployed database
drivers and pool versions. No database termination experiments were performed
for this documentation-only analysis.

## 2. What is implemented today?

### 2.1 Client method

In [`Connection.java`](../../ojp-jdbc-driver/src/main/java/org/openjproxy/jdbc/Connection.java),
lines 622-626, `abort(Executor)` logs, calls `checkValid()`, and throws
`SQLFeatureNotSupportedException("Not supported.")`.

Consequently:

- A usable connection always throws, including when the executor is null.
- A closed connection throws from `checkValid()` rather than being a no-op.
- A force-invalid connection throws `SQLNonTransientConnectionException`
  with SQLState `08006` before reaching the unsupported-feature exception.
- No task is submitted; no local closed flag changes; no server request is made;
  no statement, stream, database connection, or pool entry is terminated.

`closed` is an ordinary boolean, while `forceInvalid` is volatile (lines 58-64).
`setSession()` accepts and publishes replacement session information without
an abort-state guard (lines 84-87). These are relevant to a future concurrent
implementation, not evidence that abort currently has partial support.

### 2.2 Related mechanisms that exist, but are not abort

| Mechanism | Current source evidence | Why it is not sufficient |
|---|---|---|
| JDBC `close()` | `Connection.java:236-258` captures the session and terminates synchronously or via a static daemon executor, then clears session and marks closed | Uses graceful termination; the async path does not use the caller's executor |
| Force invalidation | `Connection.java:119-148` marks a connection unusable for redistribution | Does not physically destroy its backend resources |
| Statement cancellation | [`Statement.java`](../../ojp-jdbc-driver/src/main/java/org/openjproxy/jdbc/Statement.java):314-318 sends a generic `CALL_CANCEL` | Cancels one statement, not the whole connection |
| Generic connection calls | [`CallResourceAction.java`](../../ojp-server/src/main/java/org/openjproxy/grpc/server/action/resource/CallResourceAction.java):165-169, 183-215 resolves a connection and invokes JDBC reflectively | Can lazily acquire a connection and does not coordinate abort lifecycle |
| Session termination | [`SessionManagerImpl.java`](../../ojp-server/src/main/java/org/openjproxy/grpc/server/SessionManagerImpl.java):255-280 removes the session, checks backend auto-commit, rolls back, and calls `terminate()` | Rollback can block/fail before final cleanup; removing a map entry does not stop a thread already holding the session |
| Session resource cleanup | [`Session.java`](../../ojp-server/src/main/java/org/openjproxy/grpc/server/Session.java):400-452 closes owned non-XA replica/primary connections and clears references; pooled XA is left to the registry | No backend `abort()`; clearing maps is not equivalent to explicitly stopping all streams/resources |
| Abandoned-session cleanup | [`SessionCleanupTask.java`](../../ojp-server/src/main/java/org/openjproxy/grpc/server/SessionCleanupTask.java):43-96 finds inactive sessions and invokes ordinary termination | Delayed graceful cleanup, not guaranteed interruption of stuck work |

The existing termination path can also acquire an unused primary through
`getConnection()` before checking auto-commit. Lazy acquisition is visible in
`Session.java:224-245`; the new abort path should inspect already-owned
connections without allocating any.

### 2.3 Transport and routing

[`StatementService.proto`](../../ojp-grpc-commons/src/main/proto/StatementService.proto):474-495
has `terminateSession` and `callResource`, but no dedicated abort RPC.
The generic parameter representation does not carry executors;
[`ProtoConverter.java`](../../ojp-grpc-commons/src/main/java/org/openjproxy/grpc/ProtoConverter.java):430-436
rejects unsupported complex parameter values. An executor is executable,
process-local policy, not a value that should cross the wire.

[`StatementServiceGrpcClient.java`](../../ojp-jdbc-driver/src/main/java/org/openjproxy/grpc/client/StatementServiceGrpcClient.java):389-421
uses a blocking termination RPC, requires confirmation, and retries eligible
failures up to three attempts. That RPC invocation has no explicit per-call
deadline at this call site.

[`MultinodeStatementService.java`](../../ojp-jdbc-driver/src/main/java/org/openjproxy/grpc/client/MultinodeStatementService.java):631-686
terminates a known session on its bound server, otherwise attempts connected
servers. Its `finally` removes local tracking even on failure.
[`MultinodeConnectionManager.java`](../../ojp-jdbc-driver/src/main/java/org/openjproxy/grpc/client/MultinodeConnectionManager.java):1223-1238
also removes tracking keyed by `connHash`. Blindly reusing that cleanup for
abort needs care because the hash is shared across logical connections.

The driver reuses statement services (`Driver.java:53-55`), and the single-node
client explicitly keeps its channel shared (`StatementServiceGrpcClient.java:85-91`).
Closing a channel is therefore the wrong granularity for abort.

### 2.4 Existing tests do not specify working abort behavior

The H2 and PostgreSQL extensive tests call abort and tolerate
`SQLFeatureNotSupportedException`:

- [`H2ConnectionExtensiveTests.java`](../../ojp-jdbc-driver/src/test/java/org/openjproxy/jdbc/h2/H2ConnectionExtensiveTests.java):314-329.
- [`PostgresConnectionExtensiveTests.java`](../../ojp-jdbc-driver/src/test/java/org/openjproxy/jdbc/postgres/PostgresConnectionExtensiveTests.java):338-353.

Their comments say OJP does not support executors, but the actual gap is the
missing abort implementation; using an executor locally is entirely possible.
Both tests then use the same connection for network-timeout calls. Implementing
abort correctly would require changing those test scenarios to use a separate
connection and adding real termination assertions.

## 3. JDBC semantics to preserve

The Java 25 `java.sql.Connection` contract says abort marks the connection
closed, closes physical database connections, releases resources, and ensures
threads accessing the connection either make progress to completion or throw
`SQLException`. Cleanup may continue on the supplied executor after return.
Calling abort on an already-closed connection is a no-op; a null executor is
an SQL error for an open connection.

Sources: [Java 25 API contract](https://docs.oracle.com/en/java/javase/25/docs/api/java.sql/java/sql/Connection.html#abort(java.util.concurrent.Executor))
and [OpenJDK 25 source](https://github.com/openjdk/jdk25u/blob/master/src/java.sql/share/classes/java/sql/Connection.java).
The [Java 21 source](https://github.com/openjdk/jdk21u/blob/master/src/java.sql/share/classes/java/sql/Connection.java)
also documents `SQLPermission("callAbort")` checks when a SecurityManager
exists. OJP's client supports Java 11, so a future implementation must account
for legacy runtimes without assuming the Java 25 server still has that mechanism.

Important distinctions:

| Operation | Intended scope | Is the connection reusable afterward? |
|---|---|---|
| `Statement.cancel()` | One statement's current execution | Potentially, depending on transaction/driver state |
| Query timeout | Statement execution budget | Driver-dependent; not necessarily connection termination |
| Network timeout | JDBC network-response wait policy | Not interchangeable with an OJP gRPC deadline |
| `close()` | Normal end of logical use; pooled connection can be returned | JDBC object: no; backend: potentially yes |
| `abort()` | Emergency end of connection and owned physical resources | No; aborted physical connections must not return to service |

Abort is **not rollback confirmation**. A write or commit may have completed
before the stop arrived. An ambiguous result must not trigger automatic SQL
replay, pretend rollback succeeded, or transparently revive the same JDBC object.
The specification permits an already-running operation to complete; it does
not require rewriting a known successful result as cancellation.

## 4. Implementation possibilities, with pros and cons

### Option A: Keep abort explicitly unsupported

**Idea:** Leave the honest unsupported-feature behavior until backend teardown
and race handling can be implemented together.

**Pros:** No new lifecycle hazards; no false promise of physical termination;
existing behavior remains stable.

**Cons:** No emergency connection-level escape hatch; callers cannot rely on
standard JDBC abort for stuck sessions.

**Verdict:** Better than a success-shaped no-op. A legitimate initial policy
for unverified pool providers or XA, but not the long-term goal.

### Option B: Run existing close/termination on the supplied executor

**Idea:** Mark the connection closed locally and submit existing
`terminateSession()` plumbing.

**Pros:** Small implementation; reuses multinode routing and ordinary cleanup;
can release healthy idle resources.

**Cons:** Graceful rollback and close may block. Pool close can recycle a
physical connection instead of destroying it. Existing termination is not a
fence against late work, has lazy-acquisition side effects, and does not track
all client RPCs. Simply submitting `this::close` would also mark closed too late
if the executor queues the task.

**Verdict:** Useful as a separate graceful asynchronous-close feature, not a
complete implementation of `abort()`.

### Option C: Dedicated abort RPC delegating to backend JDBC abort

**Idea:** Add an explicit control RPC that fences the logical connection,
captures already-owned resources, and invokes backend `Connection.abort()`
with a server-owned executor.

**Pros:** Closest semantic match; portable JDBC API; clear metrics and
idempotence boundary; can target a stuck primary and replica independently.

**Cons:** Real drivers differ in scheduling, interruption, and support.
RPC acceptance is not proof that a socket has closed. Client/server state,
resource ownership, and pool retirement still need coordination. Backend abort
may itself block or schedule work that takes time.

**Verdict:** Preferred core, with Options D and F. Do not serialize an executor
or implement this as unrestricted reflective `callResource("abort")`.

### Option D: Pool-aware physical abort and per-connection invalidation

**Idea:** Add a narrow provider capability to retire one owned lease and abort
its physical delegate without allowing it to be borrowed again.

**Pros:** Prevents poisoned connection reuse and restores usable pool capacity;
preserves the existing ServiceLoader provider architecture; avoids whole-pool
shutdown.

**Cons:** The non-XA
[`ConnectionPoolProvider`](../../ojp-datasource-api/src/main/java/org/openjproxy/datasource/ConnectionPoolProvider.java)
currently has no per-connection retirement API. The implementation needs the
originating datasource/provider, not just a JDBC wrapper. Abort and destruction
ordering is delicate because pool destruction may race JDBC operations.

Version-specific upstream evidence:

| Provider | Available primitive | Limitation |
|---|---|---|
| HikariCP 7.0.2 | [`HikariDataSource.evictConnection()`](https://github.com/brettwooldridge/HikariCP/blob/HikariCP-7.0.2/src/main/java/com/zaxxer/hikari/HikariDataSource.java) targets one connection | Pool eviction ultimately schedules ordinary physical close via [`HikariPool`](https://github.com/brettwooldridge/HikariCP/blob/HikariCP-7.0.2/src/main/java/com/zaxxer/hikari/pool/HikariPool.java) and [`PoolBase`](https://github.com/brettwooldridge/HikariCP/blob/HikariCP-7.0.2/src/main/java/com/zaxxer/hikari/pool/PoolBase.java); this is not native JDBC abort |
| DBCP2 2.14.0 | [`BasicDataSource.invalidateConnection()`](https://github.com/apache/commons-dbcp/blob/rel/commons-dbcp-2.14.0/src/main/java/org/apache/commons/dbcp2/BasicDataSource.java) unwraps and invalidates a pool object | Unwrapping may fail; destruction can wait; invalidation alone does not establish prompt interruption |
| OJP XA SPI | [`XAConnectionPoolProvider.invalidateSession()`](../../ojp-xa-pool-commons/src/main/java/org/openjproxy/xa/pool/spi/XAConnectionPoolProvider.java):300-310 | Requires transaction-registry coordination and cannot replace XA recovery decisions |

Hikari's ordinary
[`ProxyConnection.close()`](https://github.com/brettwooldridge/HikariCP/blob/HikariCP-7.0.2/src/main/java/com/zaxxer/hikari/pool/ProxyConnection.java)
cleans state and recycles its entry. Calling backend abort on a wrapper alone
is therefore not proof that pool bookkeeping is correct.

**Verdict:** Required for pooled mode. Establish a provider-specific atomic
ownership/retirement step and test physical abort plus pool accounting
separately. If a provider cannot safely fence and destroy one lease, report
unsupported capability rather than closing the datasource or recycling it.

### Option E: Cancel active statements, then escalate

**Idea:** Fence the connection, request cancellation of its active statements,
and perform physical abort; optionally allow a small, configured grace period
before escalation.

**Pros:** Can stop database work and release locks sooner; useful diagnostics
distinguish cooperative cancellation from hard teardown; reuses JDBC cancel.

**Cons:** A driver's cancel can block or race statement creation. A statement
may not yet have a client-visible UUID. Cancellation does not end the
connection, and waiting for cancel must not indefinitely delay physical abort.

**Verdict:** Optional optimization, not a cancel-only implementation. Register
executions server-side before entering JDBC; use bounded independent work so
one stuck cancel does not prevent escalation.

### Option F: Per-logical-connection gRPC cancellation

**Idea:** Track client RPC contexts/streams under the JDBC object's identity
and cancel only those when abort begins, while separately sending the abort RPC.

**Pros:** Releases client waiters and stream buffers; helps prevent retries and
late session publication; keeps unrelated connections on the same channel alive.

**Cons:** gRPC cancellation does not by itself interrupt synchronous backend
JDBC execution. Requires explicit cancellation propagation and JDBC-exception
mapping, including streaming paths. Client cancellation can hide whether a
commit completed.

**Verdict:** Important companion to server teardown. Never implement abort by
shutting down a shared `ManagedChannel`.

### Option G: Database-native emergency termination

**Idea:** A database-specific adapter uses a separate authorized administrative
connection to terminate the exact backend session when JDBC abort is unsupported
or stuck. PostgreSQL's backend termination facilities are one candidate;
other databases need their own semantics and capability verification.

**Pros:** Potential escape hatch independent of the blocked JDBC socket;
valuable for an operator-controlled kill switch.

**Cons:** Elevated privileges, vendor coupling, additional connection capacity,
and stale/reused backend identifiers can cause collateral termination.
Database acceptance of a kill request is not rollback confirmation.

**Verdict:** Explicitly opt-in last resort, outside the portable default.
Capture and validate backend identity at acquisition; recheck lease ownership
before action; audit every request. Never concatenate arbitrary client-supplied
kill commands or depend on the saturated workload pool for admin capacity.

### Option H: Lease-based orphan cleanup and durable abort intent

**Idea:** Associate logical connection generations with server leases; retain
abort intent/tombstones so lost requests, partitions, or late work cannot
silently resurrect a connection. Expired leases enter the same retirement path.

**Pros:** Handles process death and failed abort delivery; gives a convergence
story beyond one RPC; allows inspection of unresolved cleanup.

**Cons:** Heartbeats, persistence/retention, and recovery add operational cost.
Lease expiry can terminate legitimate long-running work if liveness is inferred
only from SQL inactivity. Existing session cleanup is not this mechanism.
Even a durable intent cannot reach an isolated database immediately.

**Verdict:** Later reliability enhancement, not a substitute for direct abort.
Use explicit liveness rules and a separate XA recovery policy.

## 5. Recommended architecture and difficult races

### 5.1 Two executors, two lifecycle boundaries

The caller's executor belongs to the client process. It should execute the
abort delivery/cleanup task. The server independently owns a bounded control
executor for backend teardown; Java 25 virtual threads can simplify waiting,
but do not create unlimited sockets or guaranteed driver interruption.

Proposed client states are `OPEN -> ABORTING -> CLOSED`, with `ABORTING`
already observed as closed by JDBC callers. Keep cleanup progress separately,
because logical closure is not physical destruction. Use an atomic/synchronized
transition shared with close and session publication, not another independent
boolean.

Before normal return, detach the immutable cleanup target and prevent new work.
Late replies must not publish new session state or restart retries. Existing
statements, prepared/callable statements, result sets, LOBs, and metadata proxies
need consistent access checks; `Statement.checkClosed()` currently checks only
its own flag (`Statement.java:83-87`).

An `Executor` is not necessarily asynchronous: `Runnable::run` executes inline,
and a caller-owned executor may queue indefinitely or reject submission.
Bound transport waits and document this limitation rather than promising
constant-time return for arbitrary executors. On rejection, surface the error;
do not silently switch to the existing async-close executor. The exact choice
of remaining closed with recorded cleanup failure versus rejecting before the
state transition needs a deliberate, tested policy. Once remote abort can have
started, reopening the object is unsafe.

### 5.2 Fence creation before the session UUID exists

Non-XA connect can build a local `SessionInfo` from a cached `connHash` without
an RPC (`MultinodeConnectionManager.java:204-240`). The first SQL call can create
the actual session (`SessionConnectionHelper.java:114-283`).

Consider:

1. A query starts with an empty session UUID and acquires a physical connection.
2. Abort snapshots the client's still-empty session UUID.
3. The server creates the session; its reply arrives after abort.

Aborting only the snapshot UUID misses the new session. Suppressing the reply
locally prevents resurrection but does not release the database resources.

Add a **per-logical-connection token plus generation**, carried on every
resource-creating/executing request. Do not use `clientUUID` alone:
[`ClientUUID.java`](../../ojp-jdbc-driver/src/main/java/org/openjproxy/jdbc/ClientUUID.java):8-17
creates one static UUID shared by the application. Do not use `connHash`
alone: it identifies shared datasource configuration.

The server must fence creation, capture leases already owned, and retire a
lease if acquisition finishes after the abort fence. Retain a bounded tombstone
long enough for permitted in-flight requests/retries, or reject old generations
through another explicit mechanism. An empty UUID is a safe immediate no-op
only when there truly is no in-flight work and no owned resource.

### 5.3 Keep the stop button reachable under overload

`ConcurrencyThrottleInterceptor.java:28-44` applies the global in-flight limit
to every RPC without a method exemption. A new abort RPC through that path
could be rejected when SQL is saturated.
`GrpcServer.java:217-224` also supports a fixed worker pool as well as virtual
threads. Scheduling abort behind blocked SQL on the same exhausted executor
can make the stop button useless.

Reserve **bounded** control admission and dispatch capacity for abort and
cleanup. Avoid ordinary SQL admission and circuit-breaker prechecks on the
teardown path. An unlimited bypass would create a new denial-of-service path;
authenticate ownership, rate-limit control traffic, and coalesce duplicate
requests. Separate delivery deadlines, cooperative-cancel budgets, and backend
cleanup tracking instead of pretending one timeout bounds every driver call.

### 5.4 Keep owned leases out of circulation

Atomically fence new work and claim cleanup ownership before touching a lease.
The session can own both primary and replica (`Session.java:43-50, 249-289`).
Abort both if acquired, neither if unused; deduplicate identical delegates.
Capture datasource/provider origin at acquisition, including fallback replicas.

Race cleanup against close, transaction completion, session expiry, stream
callbacks, and acquisition completion. Exactly one path may retire a lease,
release its pool/admission bookkeeping, and clear resource references.
A request already holding a JDBC reference must not act on a connection that
has been returned to another borrower.

Do not perform graceful rollback before emergency physical abort: a blocked
rollback defeats the purpose. Do not clear the only references to resources
until cleanup ownership and retry tracking are established. Coordinate pool
release with actual retirement; retain visibility into physically stuck work
so replacement connections cannot invisibly exceed the intended database budget.

### 5.5 Multinode targeting and idempotence

Capture the actual owner endpoint and token/generation before unbinding.
Use the owner endpoint directly even if ordinary health routing considers it
unhealthy; abort is not a query that should fail over.

If session creation may have reached multiple endpoints before ownership is
known, send the same scoped abort intent to the candidate endpoints and fence
that generation there. This is not permission to terminate every session with
the same datasource hash or application UUID.

A duplicate abort should return the recorded result. Distinguish:

- Accepted/fenced: no new work may start for the generation.
- Cleanup complete: owned physical resources have been retired.
- Unknown/unreachable: delivery or physical outcome is not confirmed.

These are proposed internal states, not a changed JDBC return signature.
Do not lose the cleanup target when ordinary local session tracking is removed,
and do not transparently reconnect the aborted object.

### 5.6 XA is a separate design milestone

[`OjpXALogicalConnection.java`](../../ojp-jdbc-driver/src/main/java/org/openjproxy/jdbc/xa/OjpXALogicalConnection.java):20, 66-80
inherits abort but maintains its own closed flag and overrides close/isClosed.
Changing only the parent's abort would leave inconsistent lifecycle reporting.
The owning `OjpXAConnection` and its event listeners also need coordinated
failure handling; a logical-handle close is not an XA backend termination.

`TerminateSessionAction.java:62-75` returns completed XA contexts before normal
session termination. `XATransactionRegistry.java:693-750` returns only completed
contexts for that OJP session. This reuse-oriented path must not race an abort
that needs to invalidate the same backend.

The XA backend contract explicitly forbids reset/sanitization in PREPARED
state (`XABackendSession.java:24-25, 80-89, 117-121`).

| XA state | Proposed handling | Risk / constraint |
|---|---|---|
| No branch / completed | Fence handle and retire owned backend through XA SPI | Do not let ordinary completion return it before invalidation |
| ACTIVE / ENDED | Stop transport/work and notify transaction manager; retain branch outcome metadata | Do not call ordinary local JDBC rollback as a substitute for XA protocol |
| PREPARED | Close/fence affected logical access; preserve Xid and recovery information | Never reset/recycle; physical teardown must preserve the database's recoverable branch, not claim rollback |
| Commit/rollback in flight | Record uncertain outcome and keep recovery route | Never replay SQL or assert the transaction outcome from a disconnected client |

Initial explicit non-support for XA is defensible. A later recovery-aware
implementation must reconcile termination semantics with the repository's
pinned-backend invariant; it cannot quietly retain a live physical connection
and label that full JDBC abort, or destroy the only recovery path.

## 6. Creative applications beyond basic JDBC compatibility

| Suggestion | What we could do | Pros | Cons / guardrails |
|---|---|---|---|
| Request-budget watchdog | After a caller deadline, abort its exclusively owned connection if cooperative cancellation fails | Releases abandoned work; reduces tail-latency resource retention | Must not stop a connection shared by unrelated request work; commit may be ambiguous |
| Poisoned-session circuit breaker | Escalate repeated connection-level errors to targeted abort | Avoids repeated use of a damaged session | Classify intentional abort separately so it does not falsely trip datasource outage detection |
| Two-stage deployment drain | Stop new work, allow a grace period, then abort selected stragglers | Predictable shutdown without immediately killing every transaction | Requires ownership inventory, explicit grace policy, and XA recovery |
| Tenant emergency stop | An administrative selector expands into many individual, scoped aborts | Contains a runaway tenant without stopping the whole server | Separate API/authorization; audited selection; bounded fan-out and false-positive risk |
| Replica/cursor leak watchdog | Abort one orphaned logical connection and both owned backend leases | Reclaims read cursors, LOB streams, and replica capacity | Distinguish slow live consumers from dead ones; never allocate an unused primary |
| Diagnostic receipt | Correlate reason, owner, generation, driver/provider, stages, and unresolved cleanup | Makes "abort returned" versus "database stopped" observable | Bounded retention; avoid credentials/SQL parameter capture and high-cardinality metric labels |
| Recovery/chaos test primitive | Inject abort around acquire, execute, fetch, commit, and prepare | Finds pool leaks, stale-response resurrection, and outcome ambiguity | Isolated test environments only; database-side verification required |
| Future asynchronous OJP extension | Offer an OJP-specific cleanup receipt/future alongside standard `void abort()` | Operators can await or inspect teardown without changing JDBC | Extra API and protocol state; must not imply success when only intent was accepted |

Abort should remain a per-connection primitive. Bulk tenant actions, deployment
drain, and management-plane kills are separate orchestration features, not
hidden behavior changes to the standard JDBC method.

## 7. Compatibility and delivery strategy

1. **Capability experiments:** Measure deployed backend drivers' abort,
   concurrent cancel, pool wrapper, socket-close, and XA recovery behavior.
   Include Hikari, DBCP, unpooled mode, and primary/replica ownership.
2. **Non-XA core:** Add lifecycle fencing, per-logical identity, scoped control
   admission/RPC, physical abort, provider-specific retirement, and observable
   failures together. Wire all request and resource paths, not just
   `Connection.java`.
3. **Operational enhancements:** Add bounded cancel escalation, receipts, and
   orphan convergence where evidence warrants them.
4. **XA milestone:** Design transaction-manager events and prepared-branch
   recovery explicitly; retain unsupported behavior until those guarantees
   are proven.

An additive RPC avoids reinterpreting the existing `terminateSession` contract.
Negotiate capability or map an old server's `UNIMPLEMENTED` to
`SQLFeatureNotSupportedException`; do not downgrade silently to graceful close.
Establish support before irreversible transition where possible. Report
submission/transport failures synchronously when encountered there; later
cleanup failures require repository-standard logging and diagnostics because
the JDBC method has already returned.

Open implementation decisions:

- Which backend driver/pool combinations are supported initially?
- How should executor rejection affect local closure before any remote work?
- What budgets apply to delivery and escalation, and how is stuck native work
  retained and surfaced after those budgets expire?
- How long are generation fences retained across retries and server restart?
- What internal acknowledgement/receipt state is sufficient for operations?
- What recovery route survives abort of a prepared XA branch?

These choices are intentionally unresolved, not proposed default property names
or an implicit commitment to a configurable abort framework.

## 8. Proposed acceptance-test matrix

These are tests for a future implementation, not tests added by this PR.
Use deterministic barriers/fake executors for lifecycle races and real server
plus database-side observations for interruption and destruction claims.

| Scenario | Required observation |
|---|---|
| Open connection, accepted task | `isClosed()` true and `isValid()` false before normal abort return; queued task uses supplied executor |
| Already closed / repeated abort | No-op; no extra task, lease retirement, or admission release; explicitly specify closed + null precedence |
| Null/rejecting/direct/never-running executor | Open + null throws `SQLException`; rejection is surfaced; direct execution and delayed cleanup have documented behavior |
| SQL, commit, fetch, LOB activity in flight | Each caller completes or receives mapped `SQLException`; no indefinite client waiter attributable to missing cancellation wiring |
| First query with empty session UUID | Abort fence covers sessions created after abort starts; late acquisition is retired; no leaked session/connection |
| Late successful response / retry | Closed object is not resurrected or rebound; no automatic SQL replay; known completion versus unknown outcome stays explicit |
| Close/abort/expiry/completion race | Exactly one cleanup owner; no double pool return or permit release; never abort a lease acquired by a subsequent borrower |
| Healthy and stuck primary/replica | Only acquired owned connections are targeted; all owned leases retired; unused primary remains unallocated |
| Hikari/DBCP/unpooled | Database-side physical session disappears when destruction is claimed; next borrower never receives aborted delegate; counts converge |
| Saturated global admission / fixed executor | Abort reaches bounded control execution despite saturated SQL; flood of aborts does not bypass all resource limits |
| Two connections sharing hash/client/channel | Aborting one does not cancel, unbind, or terminate the other |
| Multinode partition / lost acknowledgement | Correct endpoint/token targeted; repeated abort idempotent; failed delivery remains visible and is not reported as destruction |
| Old server / unsupported driver/provider | Explicit unsupported-feature error; no success-shaped close fallback |
| Client process death / lost cleanup task | Documented orphan-convergence behavior; unresolved resources remain observable rather than silently disappearing from accounting |
| XA idle/active/ended/prepared/commit race | Unified logical/owner closed state, appropriate failure events, no reset/recycle of prepared branches, recoverable Xid/outcome |
| Legacy Java client permissions | Runtime-appropriate permission check; denial surfaced without partial abort |

Timing assertions should compare measured caller progress, control dispatch,
and database teardown against explicit chosen budgets; "RPC returned" alone is
not the acceptance criterion. A driver that cannot interrupt a blocked native
call must be identified as a limitation rather than hidden by mocked success.

## 9. Final recommendation

Implement abort as a **targeted, fenced teardown**, not a spelling variation of
close, cancel, failover, or datasource shutdown. Combine the dedicated control
RPC with per-call client cancellation and pool-aware physical retirement.

Keep the first supported scope explicit and non-XA, then expand based on real
driver evidence. The creative extensions become valuable only after the core
can answer three separate questions honestly: is the JDBC object closed, can
any new work start for its generation, and have its owned physical resources
actually stopped?
