# Transaction Multiplexing and JDBC Request Boundaries

**Date:** 2026-11-01

**Scope:** Architectural analysis; no runtime behavior is changed.

**Baseline:** OJP `main` at `676cf5d2f663781a4389aca04ec11045b54dad7e`.

**Summary:** [Decision summary](TRANSACTION_MULTIPLEXING_ANALYSIS_SUMMARY.md).

## 1. Recommendation and confidence

OJP could implement transaction pooling, similar to PgBouncer, by keeping a **logical client session** alive while borrowing a **physical database connection only for a transaction or an outstanding connection-dependent resource**. A database connection is still exclusively owned by one borrower at a time: multiplexing means time-sharing, not executing different clients' transactions concurrently on one connection.

Implement this as an **opt-in, non-XA mode**, retaining current session-bound behavior as the default. Start with a restricted, documented stateless workload profile and conservative resource pinning. Do not advertise transparent compatibility with arbitrary JDBC applications. Keeping prepared statements usable across transactions requires logical statement descriptors, not moving existing JDBC objects to another connection.

Use JDBC `beginRequest()` / `endRequest()` as an **optional, driver-certified enhancement at physical lease boundaries**, not as the mechanism that discovers transaction completion or makes multiplexing safe. They can assist cleanup, resource tracking, and Oracle continuity features. They do not portably commit, roll back, clear all SQL session state, or return a connection to OJP's pool.

**Confidence: High** in the architectural gap and JDBC contract, based on the source paths and upstream sources below. **Medium** in the implementation recommendation until real-driver lifecycle, reset, and performance experiments are completed. Oracle/Db2 configuration-specific behavior is not certified by this analysis.

## 2. What OJP does today

The protocol already distinguishes `connHash`, `clientUUID`, `sessionUUID`, and transaction metadata. The comment on `sessionUUID` describes preserving a connection across transactions or LOB operations. Actual runtime ownership lasts longer than a transaction.

| Surface | Current behavior | Consequence for multiplexing |
| --- | --- | --- |
| `Session` | Stores primary and replica connections; lazy acquisition caches them | Lazy acquisition is not transaction pooling; acquired connections remain attached |
| `SessionConnectionHelper` | Reuses an existing session's connection; ordinary new primary sessions borrow eagerly | Subsequent transactions reuse the same physical connection |
| `StartTransactionAction` | Obtains the connection and calls `setAutoCommit(false)` | A physical connection is attached before subsequent transactional SQL |
| Commit/rollback actions | Invoke JDBC commit/rollback and update response metadata | Neither action detaches the connection or enables auto-commit |
| JDBC `Connection` | Retains logical `autoCommit=false` after commit/rollback; changing it to true uses `callResource` | The next transaction must work without requiring another client `startTransaction` call |
| Resource registry | Holds actual JDBC statements, result sets, callable statements, LOBs, arrays, and savepoints behind UUIDs | These objects cannot simply be rebound to a different connection |
| Admission control | Primary session creation can transfer a permit to the session; later statements skip another acquisition | Returning just the connection would leave admission capacity occupied |
| Session termination | Rolls back when the physical connection has auto-commit disabled, then closes connections and releases its permit | Logical termination and physical release are currently coupled |
| Multinode routing | A nonempty session UUID routes to its owning OJP endpoint | Releasing a database connection does not make the logical session portable |

Relevant baseline locations, relative to the repository root:

- `ojp-grpc-commons/src/main/proto/StatementService.proto:172-208`: transaction and session representation.
- `ojp-server/src/main/java/org/openjproxy/grpc/server/Session.java:225-320,338-493`: cached acquisition, transaction-mode check, resources, termination, and permits.
- `ojp-server/src/main/java/org/openjproxy/grpc/server/action/streaming/SessionConnectionHelper.java:132-150,217-292`: reuse, replica-session creation, and primary acquisition/permit transfer.
- `ojp-server/src/main/java/org/openjproxy/grpc/server/action/transaction/StartTransactionAction.java:78-107`: transaction start.
- `ojp-server/src/main/java/org/openjproxy/grpc/server/action/transaction/CommitTransactionAction.java:47-59` and `RollbackTransactionAction.java:60-76`: transaction completion without release.
- `ojp-jdbc-driver/src/main/java/org/openjproxy/jdbc/Connection.java:182-226`: auto-commit transitions and repeated transactions.
- `ojp-server/src/main/java/org/openjproxy/grpc/server/action/resource/CallResourceAction.java:122-225`: physical resource creation, reflection, and savepoints.
- `ojp-server/src/main/java/org/openjproxy/grpc/server/action/transaction/CommandExecutionHelper.java:58-83`: admission ownership.
- `ojp-server/src/main/java/org/openjproxy/grpc/server/SessionManagerImpl.java:255-284`: termination rollback.
- `ojp-jdbc-driver/src/main/java/org/openjproxy/grpc/client/MultinodeConnectionManager.java:915-968` and `MultinodeStatementService.java:939-1000`: affinity and failure behavior.

`Session.hasActiveTransaction()` currently approximates transaction activity as `!primaryConnection.getAutoCommit()`. That is a transaction **mode**, not proof that the database currently has a transaction open. This approximation must be revisited when logical manual-commit mode can exist without a physical lease.

OJP's [dropped eager-close experiment](eager-close-resultset-analysis.md) is directly relevant. It found compatibility and timing problems with terminating a session when rows were consumed. This proposal must **detach a safe physical lease without terminating the logical session**, and must not equate end-of-row-stream with end-of-resource lifetime.

## 3. What to borrow from PgBouncer

PgBouncer session pooling retains a backend for a client's connected lifetime. Transaction pooling assigns a backend during a transaction and releases it afterward. Statement pooling is more restrictive: multi-statement transactions are disallowed. These are compatibility contracts, not interchangeable performance switches [E1, E2].

PgBouncer can inspect PostgreSQL's `ReadyForQuery` transaction status: idle, in transaction, or failed transaction. Its server processing marks a backend ready only for idle status and accounts for outstanding protocol work [E3]. OJP operates through JDBC across several databases; there is **no equivalent portable JDBC transaction-status event**.

The useful model is:

```text
Logical session A: transaction 1 -> idle -> transaction 2 -> idle
Physical pool:    lease X ------> free --> lease Y ------> free
Logical session B:                may use X while A is idle
```

Retain a physical connection during explicit transactions, including application think time inside them. For auto-commit operations, release only once JDBC statement completion and all required resource lifetimes permit it. JDBC defines auto-commit completion in terms of statement completion; returning from `executeQuery()` alone does not establish it [E4].

PgBouncer transaction mode deliberately excludes session features such as `LISTEN`, session-level advisory locks, session-preserving temporary tables, and holdable cursors. Protocol-level prepared statements can be tracked/reprepared with nonzero `max_prepared_statements`; SQL `PREPARE` is a different feature and is not transparently supported [E1, E2].

Also, PgBouncer's default `server_reset_query` (`DISCARD ALL`) is **not run in transaction mode** unless separately configured. Its transaction contract prohibits session-dependent usage; it does not reset every backend after every transaction [E2]. Do not describe JDBC request boundaries as a universal equivalent of `DISCARD ALL`.

## 4. Compatibility contract before implementation

### Two modes, not a silent default change

Keep **session mode** for existing applications. Add **transaction mode** only for explicitly opted-in pools/workloads. The server should own policy and enforce allowed configurations; client preferences must not override a deployment's restrictions. The effective mode must participate in pool identity so incompatible reset/lifecycle policies do not share one pool accidentally.

The first transaction-mode profile should require transaction control through the dedicated OJP/JDBC APIs, no connection-local SQL state crossing transaction boundaries, and no XA or unpooled passthrough. Clients must still close virtual connections promptly; do not introduce application-side pooling or encourage long-lived idle virtual sessions.

| Feature | Safe first-stage behavior |
| --- | --- |
| Explicit JDBC transactions | Keep one lease through full commit/rollback; never switch while active or failed |
| Auto-commit DML with no dependent resources | Release after execution completes and cleanup succeeds |
| Plain, forward-only result sets | Pin until explicit resource close initially, including metadata/post-read operations |
| Generated keys, DML result sets, multiple results | Pin statement/result chain until consumed and closed or safely detached |
| Holdable/updatable/scrollable cursors | Pin conservatively; reject unsupported combinations rather than silently downgrade |
| LOB streams/locators, arrays, savepoints | Pin while live; savepoints are transaction-scoped and invalidated on full completion |
| Raw prepared/callable statements | Pin until close initially; later virtualize supported prepared statements |
| JDBC catalog/schema/isolation/other settings | Persist supported logical values and replay in a defined order on borrow |
| SQL `SET`, temporary tables, advisory locks, package state, session variables | Session mode unless an explicitly certified policy supports them |
| Raw SQL transaction commands, opaque procedures/functions, vendor `unwrap` operations | Outside the initial safe profile; require session mode or a certified adapter |
| XA/prepared distributed transactions | Excluded; retain XA registry/backend lifecycle |

A general SQL parser cannot prove that arbitrary SQL has no session effects: even a `SELECT` can call a state-changing function. Document the opt-in application's obligations and audit its workload. Reject known unsupported commands where reliable, but do not claim that keyword filtering enforces the entire contract. Unknown resource/reflection operations should pin before execution or fail explicitly in strict mode.

For workloads that cannot make these promises, the correct answer is session mode, not guessing when to release. Resource pinning protects object lifetimes; it does not automatically detect hidden SQL session dependencies. If unexpected session state cannot be reset with confidence, discard the physical connection. Do not share a transaction-mode pool between mutually untrusted stateful workloads based only on their promises.

## 5. Proposed ownership and state machine

### Logical session versus physical lease

Retain the existing logical session UUID, owner endpoint, identity, activity/expiry, client accounting, and supported connection settings. Introduce one centrally owned lease lifecycle containing the pool/proxy connection, role (primary/replica), generation, transaction state, dependent resource references, and admission permit.

Do not add a second physical connection pool. Continue borrowing from the configured `DataSource`; closing a healthy pooled proxy returns it to the existing pool. Keep transaction ownership in OJP core, not in the proposed [request interceptor API](REQUEST_LIFECYCLE_INTERCEPTOR_ANALYSIS.md).

```text
DETACHED -> ACQUIRING -> LEASED -> RELEASING -> DETACHED
                         |
                         +-> transaction/resource/session-state pins
Any state -> CLOSING -> CLOSED
Acquisition/reset/connection failure -> discard lease, report failure
```

Use separate facts for logical `autoCommit`, database transaction state, and resource pins. A completed transaction can still have a lease pinned by a holdable cursor; a manual-commit logical session can be detached between transactions.

### Transaction transitions

1. `setAutoCommit(false)` records logical manual-commit mode and preserves validation/error behavior. An initial implementation can still acquire immediately, as today; deferring the first acquisition is a separate optimization with setter-error timing implications.
2. The first database operation after a detach acquires a lease, starts its request boundary if certified, applies logical settings, and sets physical auto-commit false before SQL. Create a new server-owned transaction generation/identity when transactional work resumes, even though the Java driver does not send `startTransaction` again after every commit.
3. `commit()` / full `rollback()` act on the current lease. After confirmed success, mark the transaction completed, invalidate savepoints, and release only if no resource or session-state pin remains. Leave logical `autoCommit=false`. Committing/rolling back a detached, idle manual-commit session should not borrow a connection merely to complete no work.
4. `setAutoCommit(true)` on an attached manual-commit lease delegates to JDBC exactly once, preserving the driver's implicit-commit semantics. Only successful completion permits logical state change and possible release. On a detached session, update logical mode without unnecessary acquisition.
5. Rollback to a savepoint is not full rollback. Statement errors, PostgreSQL failed transactions, cancellation, and RPC completion must not be mistaken for full transaction completion.
6. A commit failure may leave the outcome unknown. Do not retry the commit or replay writes automatically. Fail/invalidate the affected logical connection when state is uncertain; discard an unsafe lease. A cleanup failure **after confirmed commit** must be distinguished from commit failure so clients do not retry committed work blindly.

Server state, not a client-supplied `TRX_COMMITED` flag or transaction UUID, controls release. The existing termination rollback already demonstrates why client-reported state is insufficient.

### Every path needs the same lifecycle

Wire query/update execution, batches, generated keys, `fetchNextRows`, result-set metadata/navigation, `getMoreResults`, all `callResource` connection/resource methods, savepoints, inbound/outbound LOB streams, native SQL, warnings, validation, cancellation, and termination/timeout cleanup through shared ownership rules.

`ResultSetHelper` currently registers LOBs/arrays and can return a partial row stream; `ExecuteUpdateAction` can return a registered result set or generated-key statement. A unary or streaming gRPC response ending is not a release signal. Closing a logical resource must remove or tombstone its registry entry and decrement exactly one pin; closed objects remaining in a map must not prevent release forever.

For detached sessions, supported getters should use logical snapshots where semantically correct. Warnings/metadata that still require a live JDBC object pin it or require a documented detached representation. A cleanup task must not call an acquiring getter merely to terminate an idle detached session.

### Concurrency and cancellation

Concurrent maps and synchronized acquisition do not establish safe transaction ownership across whole RPCs. Serialize conflicting operations on a logical session, reserve acquisition under the session state lock, and validate that reservation after borrowing before publishing the lease. Avoid holding a global/session monitor while blocking on pool acquisition or streaming.

Associate every resource and cancellation target with a lease generation. A late cancel, fetch, close, or asynchronous termination must not touch the same physical connection after another client has borrowed it. Cancellation may need an out-of-band path, but release must await driver execution termination and stream/resource cleanup. Acquisition failure, cancellation during borrow, and concurrent close must all release the connection/permit exactly once.

### Admission, budgets, replicas, and affinity

Move the primary session-scoped permit's lifetime to the **physical lease**, without adding another hot-path queue or modifying slow-query slot borrowing/classification casually. Reuse an existing lease's permit for statements inside that lease; a detached logical session owns no database-capacity permit. Acquisition must cover settings/resource RPCs as well as SQL. Logical client counts remain logical counts, not borrowed-connection counts.

The current Hikari acquisition path has an exhaustion pre-check that can fail before waiting in the pool (`ConnectionAcquisitionManager.java:95-137`). Preserve and explicitly evaluate this with admission timeout behavior: do not promise that all excess logical clients will simply queue at Hikari. Any lease waiting/rejection policy must fit the existing admission model. Database-total budgets and pool maxima still cap physical connections; multiplexing does not increase those limits.

For read/write splitting, a manual-commit logical transaction must route to primary even when detached; checking only physical auto-commit would misroute it to a replica. Initially allow one role lease at a time, pin all explicit transactions to primary, and reject or retain session mode for combinations requiring simultaneous primary/replica resources until their ownership is specified.

Keep logical sessions on their owning OJP endpoint initially. Different physical connections from that endpoint's pool can serve successive transactions. This is not cross-server session migration or transaction failover. Changing endpoint affinity later requires transporting logical settings/descriptors and handling epoch, expiry, recovery, and uncertain outcomes across all clients.

### Protocol and client rollout

The existing dedicated transaction/query/update RPCs and resource UUIDs provide the main transport surfaces; no new RPC is inherently needed just to borrow/return a server-side lease. Do not clear `sessionUUID` on commit: that would lose the logical settings/resource registry and endpoint binding.

Negotiate the effective mode and supported capabilities explicitly during connection setup before exposing new semantics. Existing request properties may carry opt-in preferences, but the current response must also communicate acceptance or rejection; choose an additive response capability representation rather than assuming all legacy clients interpret a property identically. Keep old-client/default sessions in session mode unless their required transaction/resource behavior is certified. Update shared contracts and every enabled client together when negotiation or detached-resource representations change.

Cover the Java JDBC driver and each enabled non-Java client's public API. Native driver begin/end hooks are internal server behavior, not a new support-level claim for those clients. Preserve dedicated RPC use and explicit virtual-connection close semantics.

## 6. What JDBC beginRequest/endRequest actually promise

These methods were introduced in JDBC 4.3 / Java 9; OJP's Java 11 driver runtime and Java 25 development/server runtime can use them without lowering any targets [E4].

The JDBC contract is deliberately limited:

- A request is an independent unit of work with no unreproducible connection-local state dependencies across boundaries. **One request may include multiple transactions.**
- Both methods are default **no-ops**. Successful calls do not prove useful support; there is no standard `supportsRequestBoundaries()` capability.
- Calls are not nested. Repeated begin without end, or repeated/unpaired end, is defined as no-op behavior, not an ownership counter.
- Vendors may detect open-transaction dependencies. Throwing `SQLException` for an active transaction is recommended, not required.
- Pool managers can call begin before handing out the underlying connection and end on return. Logical-handle `PooledConnection` pooling has a documented case where these extra calls are unnecessary.

Consequently, **transaction != request != RPC**. Do not bracket every OJP gRPC action: an explicit transaction spans several actions, and a cursor/LOB can outlive them. An application request depending on local state across multiple transactions cannot safely be split just because commit succeeded.

The OJP JDBC `Connection` implementation has no explicit begin/end overrides at this baseline; it inherits the no-op defaults. Server-side driver hooks and exposing meaningful application-level request boundaries are separate features. If client overrides are added later, they must honor the non-nesting/no-op contract and must not silently close the virtual connection, commit an active transaction, or permit client-side pooling.

Request hooks can also be evaluated **independently of transaction multiplexing**: bracket today's physical borrow/return lifecycle to improve certified driver cleanup or continuity, without shortening connection occupancy. A future explicit application-request mode could retain a lease across several transactions inside one independent request and release after `endRequest()` plus resource cleanup. That could support more intra-request local state than strict transaction mode, but requires application cooperation, a client/server request contract, and the same sanitation/failure guarantees. It is not a reason to treat every commit as an application request boundary.

### Driver evidence and limits

This is **source/documentation inspection**, not an executed compatibility matrix. OJP loads external driver JARs, so capabilities must be tied to deployed driver, database, pool-wrapper, and configuration versions.

| Driver | Inspected evidence | Potential benefit / limitation |
| --- | --- | --- |
| PostgreSQL pgjdbc 42.7.8 | `PgConnection` / `BaseConnection` do not override either method [E6] | Inherits JDBC no-op behavior; do not expect session reset or transaction detection |
| MySQL Connector/J inspected upstream revision | `ConnectionImpl` / `JdbcConnection` do not override either method [E7] | No request-boundary reset demonstrated; vendor reset APIs are a separate investigation |
| MariaDB Connector/J inspected upstream revision | `Connection` directly implements JDBC Connection without these overrides; has a separate `reset()` with configuration/version conditions [E8] | Do not confuse `reset()` / `useResetConnection` with begin/end; certify any vendor reset separately |
| H2 2.4.240 | `JdbcConnection` has no begin/end overrides [E9] | Useful fast lifecycle test target, but no driver request-reset benefit demonstrated |
| Microsoft SQL Server inspected upstream revision | `SQLServerConnection43` forwards to internal methods; begin snapshots selected settings; end rolls back manual-commit work, restores tracked settings/warnings, and closes tracked statements [E10] | Concrete cleanup benefit; dangerous before resources/transaction finish; not evidence that arbitrary SQL `SET`, temporary tables, or session context are all reset |
| Oracle | Oracle documents explicit boundaries for JDBC replay/continuity; service `RESET_STATE` and database pooling features are additional, configured mechanisms [E11] | Promising for continuity and configured cleanup, not a blanket property of ordinary `endRequest()`; test exact driver/service/pool combinations |
| IBM Db2 JCC | No version-specific implementation/reset contract verified here | Treat as uncertified; confirm vendor contract and run real JCC tests before enabling hooks/reset assumptions |

SQL Server illustrates why end must follow completed work: it can roll back and close statements, not merely emit a harmless annotation. Oracle replay can change outage behavior **inside its driver**; it must not be combined with independent OJP write retries under the assumption that both are safe.

### Pool integration is an additional compatibility layer

OJP's Hikari provider pins **HikariCP 7.0.2**, while the DBCP provider pins **DBCP2 2.14.0**.

HikariCP already has `com.zaxxer.hikari.enableRequestBoundaries`, a JVM-wide, opt-in **unsupported/secret property**. Its 7.0.2 acquisition invokes begin on the underlying connection; recycle invokes end. The inspected implementation logs SQLExceptions and still hands out/recycles the connection. Proxy close closes statements, rolls back dirty manual-commit work, resets tracked JDBC state, and then recycles [E5].

This flag is useful for an experiment, but **not sufficient as OJP's correctness mechanism**: request-boundary failures do not necessarily quarantine the connection, it is not a stable per-pool API, and boundaries follow borrow/return, which currently means session lifetime. Turning it on alone does not implement transaction multiplexing.

DBCP2 2.14.0's `DelegatingConnection` does not override begin/end [E12]. Calling them on that wrapper can therefore hit JDBC defaults without reaching the driver. Its return lifecycle performs configured rollback, warnings cleanup, and auto-commit restoration [E12]; none establishes a universal SQL-session reset.

Choose **one owner** for driver boundaries. A core/provider lease adapter must avoid overlapping with pool-owned begin/end or application boundaries. Do not call directly on an unwrapped physical connection and then close it: that bypasses proxy accounting. Physical access, cached JDBC settings, invalidation, and final proxy return must remain provider-controlled.

## 7. Suggested lease cleanup contract

The existing `ConnectionPoolProvider` exposes creation, resizing, statistics, and datasource shutdown, but no per-connection invalidation/request-boundary capability (`ojp-datasource-api/src/main/java/org/openjproxy/datasource/ConnectionPoolProvider.java:48-176`).

First prototype provider-specific lifecycle handling for built-in Hikari/DBCP. Only extract the smallest SPI extension demonstrated necessary: capability/ownership information and a reliable way to **discard a borrowed connection without recycling it**. Unsupported third-party providers should remain in session mode until certified. A default discard method must not be a success-shaped `connection.close()` fallback.

For a certified transaction lease:

```text
borrow pool proxy + acquire/transfer admission permit
begin driver request, if supported and owned here
apply supported logical settings; execute transaction/operation
confirm completion; await all physical-dependent resources/streams
close remaining lease-owned JDBC objects; invalidate dependent handles
rollback unfinished work on abort, never implicitly commit cleanup
end driver request, if begun and safe to end
restore/verify certified baseline; return proxy to pool
release permit; detach lease while preserving logical session
```

The exact ordering of end versus pool state reset is driver/provider-specific. Begin must see the intended clean pool baseline, **before** session settings are applied, or end may restore the previous client's state. SQL Server's snapshot behavior makes this important. End may itself change settings that a wrapper caches; validate that proxy return does not reintroduce stale state. Pool-owned and OJP-owned cleanup must agree on this ordering.

Portable cleanup includes completing or rolling back work, closing owned resources, restoring supported JDBC attributes, and clearing appropriate warnings. It does **not** prove that SQL changed no hidden session state. A vendor-certified reset can supplement it; otherwise restrict the workload or discard suspect connections. For example, PostgreSQL `DISCARD ALL` has transaction and prepared-plan implications; executing it blindly can desynchronize driver caches and is not a generic multi-database solution.

Distinguish **unsupported** hooks from **failed** hooks. Establish capabilities outside the hot path using a versioned allowlist plus real behavioral tests, including wrapper forwarding; reflection/method presence alone is insufficient. If an enabled begin, end, reset, or resource close fails, log/measure the phase, preserve the original SQL error with cleanup failures attached, quarantine/discard the lease, and surface the correct result or error. Never recycle on the assumption that a failed cleanup probably worked.

## 8. Prepared statements and detached results

Conservative pinning provides a safe prototype but can erase much of the benefit when applications keep statements open across transactions. The next useful step is a logical prepared-statement descriptor: SQL, creation overload/options, supported statement settings, parameter/type information, batch contents, and a stable logical UUID. Recreate its physical statement on the current lease, bind parameters, execute, and keep physical result dependencies pinned.

Preserve parameter metadata and validation timing deliberately. Non-replayable stream parameters require bounded buffering or pinning; never replay a consumed stream silently. Batches assembled across calls, callable out parameters, statement warnings, generated keys, and `getMoreResults()` require explicit lifetimes. Raw SQL `PREPARE` is not automatically virtualized by such descriptors.

Keep any physical statement cache **per physical connection**, respecting driver reset behavior. Stable logical UUIDs must not point at stale JDBC objects after release. PgBouncer's protocol prepared-statement mapping is the conceptual precedent, not reusable code for JDBC's richer resource model.

Fully detaching result sets would require bounded materialization/spooling and snapshot metadata, warnings, navigation, and LOB content. That is a separate project with memory, latency, and disk-pressure costs. Start by pinning; do not repeat the dropped eager-session-close approach.

## 9. Experiments and acceptance gates

No performance improvement or driver certification is claimed yet. Run Java build/test tasks on **Java 25**, server JVM timezone **UTC**, through public client APIs against a real OJP server. Use CSV fixtures with one or more connection configurations, running each scenario for every row. H2 provides fast lifecycle coverage; PostgreSQL provides a practical transaction-pooling target; SQL Server provides a positive hook implementation. Test both built-in pools, then Oracle/Db2 with available vendor drivers.

| Experiment | Required observable outcome |
| --- | --- |
| Pool size 1, two logical connections | A completes and closes dependent resources but stays logically open; B executes before A closes; active leases return to 0 during idle time |
| Two sequential manual-commit transactions | Logical auto-commit stays false; transaction generations differ; both commits persist data and both rollbacks discard their own work |
| Open transaction/failed transaction/savepoint | B cannot use A's lease before full completion; savepoint rollback does not release; stale savepoints fail |
| Repeated auto-commit operations | Physical borrow/return occurs safely without ending the virtual connection |
| Resource lifetime matrix | Metadata after EOF, generated keys, DML result sets, multiple results, holdable cursors, and LOB/array streams remain valid for documented lifetimes |
| Logical settings across forced reassignment | Catalog/schema/isolation/etc. preserve supported logical values; another session observes only its baseline/settings |
| Hidden SQL state and hooks | Verify actual effect, not merely no exception; no-op drivers are classified correctly; unsupported state is rejected, pinned by policy, or reset/discarded |
| Fault injection | Borrow/begin/end/reset/close failures never recycle unsafe connections; connection/permit counts return to baseline; confirmed commit is distinguishable from uncertain outcome |
| Protocol/client compatibility | Mode acceptance/rejection is explicit; legacy/default clients retain session semantics; each enabled client preserves transaction/resource/close behavior |
| Races | Cancel/fetch/close/timeout racing borrow/commit/release never affect another borrower; permits and physical leases are released once |
| Multinode/replica/XA | Logical owner affinity remains intact; manual transactions use primary; XA and session-mode behavior are unchanged |

Use fake-driver unit tests to assert ordering, no double boundaries, failure mapping, and generation checks; these supplement, not replace, real-server tests. Inspect deployed wrappers and driver JARs as well as upstream source.

Benchmark session mode, transaction mode without hooks, and transaction mode with certified hooks at identical pool caps and workloads. Vary client think time, transaction duration, resource retention, statement reuse, result size, and primary/replica use. Measure throughput, p50/p95/p99 latency, admission wait/rejections, physical lease duration, active/idle connections, reset duration, preparation/cache churn, memory, and fairness.

As a sizing intuition, average borrowed connections are approximately transaction arrival rate multiplied by **lease duration**, including in-transaction think time, dependent resources, and cleanup. This is not a capacity guarantee; burstiness, pinning, and queue limits still matter. Reset cost or re-preparation can outweigh saved idle occupancy.

Minimum correctness gates are zero cross-session state/resource leakage in the certified matrix, no uncompleted transaction reuse, no double permit release, no write replay after uncertain outcomes, and unchanged default-mode behavior. Require a measured reduction in idle lease occupancy for the pool-size-1 scenario. Agree workload-specific latency/throughput thresholds before enabling beyond a prototype rather than inventing a universal speedup.

## 10. Phased implementation and unresolved decisions

1. **Specify and instrument:** Define opt-in workload contract, server-owned mode/pool identity, state machine, transaction generations, pin reasons, provider discard guarantees, and lease/admission metrics. Audit every physical getter/resource surface.
2. **Restricted non-XA prototype:** Preserve logical session/endpoint identity; release after safe transaction completion/resource close; replay supported logical settings. Keep raw resources pinned and default mode unchanged. Validate with H2/PostgreSQL and both pools.
3. **Certified request hooks:** Add single-owner driver/provider integration and fault handling; compare no-op and SQL Server implementations. Evaluate Oracle continuity/reset/DRCP only with exact service and driver configuration.
4. **Practical compatibility:** Virtualize reusable prepared statements and supported batch/settings operations; expand resource coverage based on observed workloads. Only then consider bounded detached results or more complex replica ownership.

Before implementation approval, decide the exact permitted workload profile, strict rejection versus explicit session-pinning behavior, third-party provider certification, cleanup-error response representation after confirmed commit, and which JDBC settings can be replayed with acceptable validation timing. Cross-server logical-session migration and XA multiplexing are separate designs.

**Bottom line:** The largest gain comes from ending safe physical leases between transactions, not from calling begin/end more often. JDBC request boundaries are valuable where vendors actually implement them, but correct reuse still requires transaction ownership, resource tracking, logical-state replay, proven sanitation, and safe pool return.

## External evidence

Sources were inspected on 2026-11-01. GitHub sources were read via the GitHub API because direct documentation-host DNS access was unavailable. Oracle links are vendor documentation identified through indexed search; no deployed Oracle or Db2 driver/service behavior was verified.

- **E1:** [PgBouncer feature/compatibility map](https://github.com/pgbouncer/pgbouncer.github.io/blob/82d260153797e4624f74345cd2bbb5f3e2bb015b/features.md).
- **E2:** [PgBouncer configuration: pool modes, prepared statements, and reset queries](https://github.com/pgbouncer/pgbouncer/blob/bb60c8c94bd6e9fb48f6fcf62cc1ec649b3ab6c6/doc/config.md).
- **E3:** [PgBouncer backend transaction-status processing](https://github.com/pgbouncer/pgbouncer/blob/bb60c8c94bd6e9fb48f6fcf62cc1ec649b3ab6c6/src/server.c), particularly `PqMsg_ReadyForQuery`.
- **E4:** [Java 25 JDBC Connection contract](https://github.com/openjdk/jdk/blob/jdk-25-ga/src/java.sql/share/classes/java/sql/Connection.java), `setAutoCommit`, `beginRequest`, and `endRequest`.
- **E5:** HikariCP 7.0.2 [borrow/recycle hooks](https://github.com/brettwooldridge/HikariCP/blob/HikariCP-7.0.2/src/main/java/com/zaxxer/hikari/pool/HikariPool.java), [proxy cleanup](https://github.com/brettwooldridge/HikariCP/blob/HikariCP-7.0.2/src/main/java/com/zaxxer/hikari/pool/ProxyConnection.java), and [unsupported property documentation](https://github.com/brettwooldridge/HikariCP/blob/HikariCP-7.0.2/README.md).
- **E6:** pgjdbc 42.7.8 [PgConnection](https://github.com/pgjdbc/pgjdbc/blob/REL42.7.8/pgjdbc/src/main/java/org/postgresql/jdbc/PgConnection.java) and [BaseConnection](https://github.com/pgjdbc/pgjdbc/blob/REL42.7.8/pgjdbc/src/main/java/org/postgresql/core/BaseConnection.java).
- **E7:** MySQL Connector/J [ConnectionImpl](https://github.com/mysql/mysql-connector-j/blob/fac9201148ebe3f84a5754cb86ebbacaf1cdce7f/src/main/user-impl/java/com/mysql/cj/jdbc/ConnectionImpl.java) and [JdbcConnection](https://github.com/mysql/mysql-connector-j/blob/fac9201148ebe3f84a5754cb86ebbacaf1cdce7f/src/main/user-api/java/com/mysql/cj/jdbc/JdbcConnection.java).
- **E8:** MariaDB Connector/J [Connection and separate reset implementation](https://github.com/mariadb-corporation/mariadb-connector-j/blob/52164c41c361dcee2513e258bfaa8c546c103ae5/src/main/java/org/mariadb/jdbc/Connection.java).
- **E9:** H2 2.4.240 [JdbcConnection](https://github.com/h2database/h2database/blob/version-2.4.240/h2/src/main/org/h2/jdbc/JdbcConnection.java).
- **E10:** Microsoft JDBC [SQLServerConnection43 forwarding](https://github.com/microsoft/mssql-jdbc/blob/891ac309c6a79467a5673baa5fa5f9c5b7df4a9f/src/main/java/com/microsoft/sqlserver/jdbc/SQLServerConnection43.java) and [request snapshot/cleanup implementation](https://github.com/microsoft/mssql-jdbc/blob/891ac309c6a79467a5673baa5fa5f9c5b7df4a9f/src/main/java/com/microsoft/sqlserver/jdbc/SQLServerConnection.java).
- **E11:** Oracle [Application Continuity for Java](https://docs.oracle.com/en/database/oracle/oracle-database/21/jjdbc/application-continuity.html) and [Application Connectivity / Reset Database Session State](https://docs.oracle.com/en/database/oracle/oracle-database/23/nfcoa/application_connectivity.html). Treat continuity, request boundaries, configured reset, and database-side pooling as distinct capabilities.
- **E12:** DBCP2 2.14.0 [DelegatingConnection](https://github.com/apache/commons-dbcp/blob/rel/commons-dbcp-2.14.0/src/main/java/org/apache/commons/dbcp2/DelegatingConnection.java) and [PoolableConnectionFactory return cleanup](https://github.com/apache/commons-dbcp/blob/rel/commons-dbcp-2.14.0/src/main/java/org/apache/commons/dbcp2/PoolableConnectionFactory.java).
