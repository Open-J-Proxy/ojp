# Capacity-Aware Connection Acquisition

**Date:** 2026-11-14

**Status:** Analysis only; no runtime, configuration, or SPI changes

**Source baseline:** OJP `003a6f90411fc62d2f8031705bb716b94009ea3c`

## Decision summary

**Yes: OJP can avoid failing solely because a cold or recovering pool needs
more than 250 ms to supply a connection.** The best fit is **bounded,
capacity-aware acquisition grace after admission**, not unlimited retries and
not temporarily changing the shared pool timeout.

Keep the admission gate responsible for overload waiting. After admission,
allow a short borrow failure to be retried while the pool has observed room
to grow, within one fixed acquisition budget. Let the pool create and reserve
connections; OJP must not open connections outside it or increase its maximum.

This is possible with HikariCP, DBCP2, and OJP's Commons Pool 2 XA provider,
but their timeout semantics are different. In particular, **DBCP2 and Commons
Pool 2 already allow synchronous creation to take longer than their borrow
wait timeout**. A generic retry policy cannot turn their borrow timeout into
a hard wall-clock deadline.

**SPI answer:** A limited implementation for the built-in providers does not
require a breaking SPI change. Existing statistics and acquisition operations
are enough for best-effort checks. For reliable, portable behavior across
custom implementations, recommend an **optional, typed acquisition capability**
covering state, timeout classification, and deadline semantics. Do not make
new methods mandatory or silently assume that unknown providers support it.

**Confidence: High** in the source-level diagnosis and provider feasibility;
**Medium** in the best grace duration and achievable end-to-end latency.
Those depend on database/driver behavior, concurrent ramp-up, and admission
coverage, and require the experiments below. This report does not claim that
those experiments have been run.

## 1. What OJP does today

| Area | Source and observed behavior |
|---|---|
| Timeout constant | [`CommonConstants`](../../ojp-grpc-commons/src/main/java/org/openjproxy/constants/CommonConstants.java#L82-L91) defines `FAIL_FAST_POOL_CONNECTION_TIMEOUT_MS = 1L`; normal server connection timeout defaults to 10 seconds. |
| Non-XA creation | [`ConnectAction`](../../ojp-server/src/main/java/org/openjproxy/grpc/server/action/connection/ConnectAction.java#L227-L265) passes 1 ms to `PoolConfig`, retains the datasource timeout for admission, and resizes admission capacity alongside database-budget pool resizing. |
| Hikari translation | [`HikariConnectionPoolProvider`](../../ojp-datasource-hikari/src/main/java/org/openjproxy/datasource/hikari/HikariConnectionPoolProvider.java#L85-L99) explicitly applies `Math.max(configuredTimeout, 250)` before calling Hikari. |
| DBCP translation | [`DbcpConnectionPoolProvider`](../../ojp-datasource-dbcp/src/main/java/org/openjproxy/datasource/dbcp/DbcpConnectionPoolProvider.java#L69-L77) maps 1 ms to `BasicDataSource.setMaxWait(Duration)`, without a 250 ms clamp. |
| Acquisition manager | [`ConnectionAcquisitionManager`](../../ojp-server/src/main/java/org/openjproxy/grpc/server/ConnectionAcquisitionManager.java#L97-L181) has a Hikari-only full-pool pre-check, then one `DataSource.getConnection()` call. No capacity-aware retry exists. Any caught `SQLException` is finally wrapped as `SQLTransientConnectionException`. |
| Primary session admission | [`SessionConnectionHelper`](../../ojp-server/src/main/java/org/openjproxy/grpc/server/action/streaming/SessionConnectionHelper.java#L218-L266) claims the current operation slot or acquires a session permit before the primary borrow; the permit transfers to session termination, or releases on acquisition failure. |
| Gate ownership | [`AdmissionControlManager`](../../ojp-server/src/main/java/org/openjproxy/grpc/server/AdmissionControlManager.java#L222-L266) implements slot-to-session transfer and session admission. A permit is a concurrency reservation, not a ready physical connection. |
| Other non-XA paths | [`Session`](../../ojp-server/src/main/java/org/openjproxy/grpc/server/Session.java#L228-L290) directly calls `getConnection()` for lazy primary and replica acquisition, bypassing the acquisition manager. A session can hold both primary and replica connections. |
| XA configuration | [`HandleXAConnectionWithPoolingAction`](../../ojp-server/src/main/java/org/openjproxy/grpc/server/action/connection/HandleXAConnectionWithPoolingAction.java#L245-L246) also sets `xa.connectionTimeoutMs` to 1 ms. |
| XA allocation | [`HandleXAConnectionWithPoolingAction`](../../ojp-server/src/main/java/org/openjproxy/grpc/server/action/connection/HandleXAConnectionWithPoolingAction.java#L321-L409) borrows eagerly at connect through `XAConnectionPoolProvider.borrowSession`; this method does not acquire/transfer the non-XA session permit. |
| XA pool behavior | [`CommonsPool2XADataSource`](../../ojp-xa-pool-commons/src/main/java/org/openjproxy/xa/pool/commons/CommonsPool2XADataSource.java#L167-L220) uses `pool.borrowObject()` and wraps `NoSuchElementException` as an exhaustion `SQLException`. Its [configuration](../../ojp-xa-pool-commons/src/main/java/org/openjproxy/xa/pool/commons/CommonsPool2XADataSource.java#L760-L780) enables blocking and sets the configured max wait. |
| Physical XA creation | [`BackendSessionFactory`](../../ojp-xa-pool-commons/src/main/java/org/openjproxy/xa/pool/commons/BackendSessionFactory.java#L85-L109) synchronously obtains the vendor `XAConnection` and opens the backend session. |

### Two corrections to the initial premise

1. **250 ms is HikariCP's usual minimum accepted connection timeout, not its
   default.** HikariCP 7.0.2 defaults to 30 seconds. Its setter rejects positive
   values below the minimum; OJP's provider performs the clamp. See the pinned
   [HikariConfig source](https://github.com/brettwooldridge/HikariCP/blob/HikariCP-7.0.2/src/main/java/com/zaxxer/hikari/HikariConfig.java#L175-L201).
   Hikari also has an internal configurable floor; relying on that internal
   setting to lower the timeout is not the recommended solution.
2. **An admission permit does not guarantee an immediately available connection
   or even a successful new connection.** It limits logical work. Creation can
   be in progress, validation can retire a connection, the database can refuse
   login, and some acquisition paths do not share the same session gate.

There is also a legacy direct-Hikari configuration helper:
[`ConnectionPoolConfigurer.configureHikariPool`](../../ojp-server/src/main/java/org/openjproxy/grpc/server/pool/ConnectionPoolConfigurer.java#L76-L86)
passes 1 ms directly to Hikari's setter. No production call site was found in
the inspected server main sources beyond its overload. It must not be treated
as evidence that Hikari accepts 1 ms; review it if that path is reused, without
folding an unrelated legacy fix into this analysis PR.

The existing [admission summary](./ADMISSION_CONTROL_BACKPRESSURE_SUMMARY.md)
describes the intended fail-fast architecture. The source inventory above
qualifies its broad statement that admission is always before pooled work:
do not assume identical session-permit coverage for eager XA connects or
lazy replica acquisition.

## 2. Provider feasibility and limits

The declared versions are HikariCP **7.0.2**, DBCP2 **2.14.0**, and Commons
Pool 2 **2.13.1** in the XA module. DBCP2's upstream POM declares Commons
Pool **2.13.0**. Maven dependency mediation can select another Pool 2 version
in a combined runtime; verify the packaged dependency tree before implementation.
Both 2.13.0 and 2.13.1 borrow/create implementations were inspected.

| Provider | Cold growth behavior | Capacity check | Safe implementation route |
|---|---|---|---|
| HikariCP | Borrow waits for an entry while a background creator supplies connections. A 250 ms borrow can expire while login is still running. | Public MXBean total/idle counts and datasource maximum; counts do not expose a reservation for each in-flight creation. | Repeat ordinary `DataSource.getConnection()` within bounded grace; keep timeout fixed. No direct use of internal `HikariPool` is needed. |
| DBCP2 | Pool 2 first tries to create when no idle object exists and capacity is available. Creation/activation/validation run synchronously and can exceed 1 ms. Initial `BasicDataSource` initialization is another synchronous phase. | Existing OJP stats expose active + idle and runtime max; creation reservations are not included in that sum. | Native growth already handles a slow uncontended create. For contention, use bounded retries of normal `getConnection()`, or a provider-owned per-call borrow adapter preserving DBCP wrappers. |
| Commons Pool 2 XA | Like DBCP, `borrowObject()` synchronously invokes the factory when capacity is available. Other creators can occupy the remaining reservations while a borrower waits. | Existing XA stats expose active + idle, waiters, and runtime max, but not exact creation headroom. | Add bounded acquisition handling inside the XA provider/wrapper; `borrowObject(Duration)` supports per-call queue-wait duration without shared mutation. Retain XA session tracking and cleanup. |

### HikariCP: why the symptom is plausible

The pinned [HikariPool borrow implementation](https://github.com/brettwooldridge/HikariCP/blob/HikariCP-7.0.2/src/main/java/com/zaxxer/hikari/pool/HikariPool.java#L135-L195)
waits on the connection bag. Its
[creator](https://github.com/brettwooldridge/HikariCP/blob/HikariCP-7.0.2/src/main/java/com/zaxxer/hikari/pool/HikariPool.java#L729-L795)
creates while total is below maximum and idle/demand conditions warrant it.
Ordinary borrows already trigger pool growth; a second growth API is unnecessary.

Example: max is 20, total is 5, idle is 0. An admitted request asks for a
connection; physical creation takes 800 ms. A single 250 ms borrow fails even
though the pool has room. Keeping bounded demand alive can allow a subsequent
borrow to receive that connection. Concurrent ramp-up may require several
creation intervals, not just one database login interval.

OJP constructs `new HikariDataSource(config)`, which initializes synchronously.
Hikari's [initialization check](https://github.com/brettwooldridge/HikariCP/blob/HikariCP-7.0.2/src/main/java/com/zaxxer/hikari/pool/HikariPool.java#L567-L600)
can create an initial connection before returning. Thus "cold" here includes
growth beyond initial idle supply and replenishment after eviction; it does
not necessarily mean the very first pool constructor is subject to 250 ms.
Constructor failures must not be mistaken for borrow timeouts.

### DBCP / Pool 2: the same setting means something different

See the pinned Pool 2
[borrow](https://github.com/apache/commons-pool/blob/rel/commons-pool-2.13.1/src/main/java/org/apache/commons/pool2/impl/GenericObjectPool.java#L281-L366)
and [create](https://github.com/apache/commons-pool/blob/rel/commons-pool-2.13.1/src/main/java/org/apache/commons/pool2/impl/GenericObjectPool.java#L500-L588)
implementations, and the corresponding
[2.13.0 implementation](https://github.com/apache/commons-pool/blob/rel/commons-pool-2.13.0/src/main/java/org/apache/commons/pool2/impl/GenericObjectPool.java).
Pool 2 attempts creation before waiting for an idle object. It reserves capacity
with internal creation counters and then calls `factory.makeObject()` directly.
The borrow duration does not interrupt that factory call or bound activation
and validation. A healthy connection that takes 800 ms to create can therefore
be returned successfully despite a 1 ms max wait.

Concurrent borrowers can still fail: the publicly reported total may be 0
while all creation slots are reserved. A caller that cannot reserve a slot
may consume its short wait budget before another creator completes. Public
`total < max` is therefore useful as a **reason to allow grace**, not proof
that a particular request can start another connection.

DBCP's
[PoolingDataSource](https://github.com/apache/commons-dbcp/blob/rel/commons-dbcp-2.14.0/src/main/java/org/apache/commons/dbcp2/PoolingDataSource.java#L132-L154)
wraps `NoSuchElementException` in `SQLException`; the exception class alone
does not distinguish waiting timeout from newly created object activation or
validation failure. Likewise OJP's XA wrapper currently labels all such
exceptions as exhaustion. Classification must happen before these generic
wrappers erase the distinction; SQLState `08001` alone is insufficient.

`BasicDataSource.getConnectionPool()` is public in 2.14.0, but directly borrowing
and returning its raw `PoolableConnection` would bypass the normal datasource
guard wrapper. A timed adapter must retain the datasource's logical-close and
return-to-pool semantics, not merely use the exposed internal pool.

**Common limitation:** pool wait budgets are not universal login deadlines.
Hikari can also exceed a nominal borrow budget during validation or suspension;
synchronous startup is separate. Its
[PoolBase](https://github.com/brettwooldridge/HikariCP/blob/HikariCP-7.0.2/src/main/java/com/zaxxer/hikari/pool/PoolBase.java#L635-L650)
attempts to set datasource login timeout, subject to driver support. For DBCP
and XA, configure and verify vendor connect/login and validation timeouts.
`Connection.setNetworkTimeout()` is not a timeout for opening a not-yet-existing
connection. A gRPC deadline or thread interrupt alone cannot reliably terminate
an arbitrary JDBC driver call.

## 3. Alternatives

| Option | Advantages | Problems | Verdict |
|---|---|---|---|
| Increase pool timeout globally after the gate | Small change; native waiting and Hikari growth continue uninterrupted. | Waits even at full capacity; can restore the hidden saturation queue, especially on paths without matching admission. Does not bound synchronous creation in DBCP/XA. | Reasonable mitigation only after admission coverage is proven; not the best match for conditional grace. |
| Retry short borrows while observed growth capacity exists | Keeps fail-fast saturation; works with current public acquisition APIs; preserves native pool ownership. | Sample races, Hikari's timeout floor, DBCP/XA exception classification, and retry pacing need explicit handling. | **Recommended policy**, with a finite deadline and provider-aware implementation. |
| Choose a longer per-call borrow before the first attempt when below max | Avoids expected Hikari timeout and repeated calls; Pool 2 supports per-call duration. | Capacity can disappear after the snapshot; HikariDataSource has no public timed overload. A long native call can wait after the pool fills. | Useful provider optimization, not a uniform portable solution. |
| Temporarily set a longer shared timeout, borrow, then restore | Appears simple. | Concurrent borrowers observe one another's settings; restoring can overwrite another update. Locking serializes acquisition and still affects external borrowers. | **Reject.** |
| Prewarm / raise minimum idle | Reduces startup misses and latency. | Extra connections, database-budget consumption, startup cost; no guarantee after eviction or later expansion. `minIdle` is a target, not an instantaneous readiness barrier. | Complement, not correctness fix. |
| Force-create with `addObject()` or direct JDBC login | Can explicitly populate a pool. | May block; races with native creators; direct JDBC bypasses caps, validation, wrapping, metrics, and XA lifecycle. Hikari has no equivalent supported force-grow API. | Reject as the generic acquisition mechanism. |
| Retry forever whenever `total < max` | Avoids a finite cold-start cutoff. | Database outage can keep total below max forever; holds admission permits and ties up work indefinitely. | **Reject.** |
| Wrap every borrow in a future with an OJP timeout | Caller can stop waiting. | Driver task may keep running; late connections can leak or create ghost sessions. Needs bounded workers and explicit late-result cleanup; interrupts are not reliable cancellation. | Not a first-line solution; cannot promise hard cancellation without driver support. |

## 4. Recommended policy

Treat the user's capacity condition as **eligibility to continue acquiring**,
not a promise never to fail. Finite configuration, authentication, network,
shutdown, cancellation, and deadline failures remain valid even below max.

### Budget and admission

Use a distinct server-owned **physical acquisition grace budget**. Do not
reinterpret existing `connectionTimeout` admission settings or allow each
retry to restart the budget. A proposed starting value is **10 seconds**,
subject to measurement; this is not an implemented default.

At admission entry, track elapsed time and the RPC's remaining deadline. At
first physical borrow, set one monotonic deadline to the earliest of:

- acquisition start plus the physical acquisition grace;
- the request's remaining overall deadline;
- any separately defined overall connection-establishment budget.

Admission waiting has already consumed wall-clock time. A 10-second admission
wait plus a fresh 10-second acquisition grace must not be presented as a
10-second end-to-end guarantee. Fast/slow lane admission policy stays unchanged.

Hold the **same** connection/session admission reservation through all borrow
attempts. Do not reacquire a slot per retry, release and requeue between attempts,
or replay SQL. Transfer the reservation only on successful session creation;
release it exactly once on terminal failure. For lazy primary/replica and
eager XA allocation, first establish the appropriate physical-pool reservation
and ownership; do not assume the non-XA helper already covers these paths.

### Before surfacing a short borrow timeout

1. Borrow through the provider's normal logical connection/session path.
2. On failure, classify the **original provider error**, before the acquisition
   manager or XA wrapper turns it into a generic transient/exhaustion error.
   Only identified capacity/wait timeouts are eligible. Permanent authentication,
   configuration, validation/activation, shutdown, and interruption errors do
   not receive automatic capacity retries. Preserve SQLState, vendor code, cause,
   and chained exceptions in the terminal response.
3. Check cancellation and the fixed deadline. Then obtain fresh pool state:
   idle, established total, **effective runtime maximum**, and lifecycle state
   when available. Missing, malformed, unsupported, or failed statistics must
   not be interpreted as zero connections. Diagnose explicitly and retain the
   documented legacy policy for unsupported providers.
4. If a usable idle connection is observed, permit another short attempt within
   budget, even if total now equals max: a connection may have appeared just
   after the timeout. If idle is zero and total is below effective max, continue
   with bounded grace. If idle is zero and total is at/above max, surface exhaustion.
5. Pace repeated attempts. Do not spin at DBCP/XA's 1 ms interval; use a modest
   capped delay with jitter (for example, 10-50 ms) or a provider-native timed
   wait, always charged to the same budget. Keep Hikari demand gaps small because
   its creator checks whether there are waiters or a minimum-idle shortfall.
6. On success, recheck cancellation/session ownership before publication.
   Close/return a connection that arrived after abandonment; never publish a
   late session. On deadline, report acquisition timeout with elapsed time,
   attempt count, and last pool state, without hiding the original failure.

Use `System.nanoTime()` for OJP elapsed budgets. Do not depend on public
statistics being an atomic snapshot. Counts are hints; the pool must retain
atomic reservation and maximum enforcement.

For Hikari's public datasource API, each attempt uses the fixed configured
timeout, normally 250 ms here. Do not start another attempt when the remaining
grace is shorter than that nominal wait. This avoids knowingly overspending the
budget but may end grace slightly early; validation can still overrun. Do not
cast the MXBean to Hikari's internal pool to access its timed method.

For DBCP/XA synchronous creation, check the deadline after return as well.
Returning late does not undo driver work; return/close the unused result safely.
Document this as a **bounded OJP retry policy with driver-dependent call
duration**, not a proven hard acquisition deadline.

### Capacity and resizing races

Compare against the pool's **current applied maximum**, not the original
client maximum. OJP's multinode allocation and database connection budget can
reduce that maximum. The built-in provider statistics already return their
runtime cap. Neither grace nor warming may expand it.

`total < max` does not count all creations in progress and does not guarantee
database capacity. Conversely, after a shrink total may temporarily exceed
max while existing leases drain. Stop growth grace in that state. Recheck
after each timeout, but acknowledge that a short attempt already underway
cannot be atomically canceled when a resizer changes the cap.

An in-flight creation can finish after another borrower reaches the deadline.
Pool-native ownership keeps it managed; OJP must not attach it to the expired
request. Prepared XA sessions remain leased under the existing transaction
lifecycle and must not be reclaimed to make acquisition succeed.

### Error and metric ownership

The current acquisition manager records exhaustion and logs an error on every
failed call. Retrying that method unchanged would inflate exhaustion counts and
produce alarming errors for ultimately successful acquisitions. Separate the
attempt operation from final reporting: record total acquisition duration once,
short-attempt timeout/retry counts separately, and terminal exhaustion only when
the request actually fails. Include an acquisition phase distinct from admission.

For XA, retry **only backend session acquisition before any XAResource operation
has started**. Never retry `start`, `prepare`, `commit`, or `recover` as though
they were borrows. The registry has additional provider borrows at
[`xaStart`](../../ojp-xa-pool-commons/src/main/java/org/openjproxy/xa/pool/XATransactionRegistry.java#L270-L296)
and [`xaRecover`](../../ojp-xa-pool-commons/src/main/java/org/openjproxy/xa/pool/XATransactionRegistry.java#L598-L630).
The server's normal
[`XaRecoverAction`](../../ojp-server/src/main/java/org/openjproxy/grpc/server/action/xa/XaRecoverAction.java#L50-L71)
uses the session's existing XAResource, rather than that extra registry borrow;
do not insert an unnecessary borrow into recovery.

## 5. SPI impact and custom providers

### What is possible without changing interfaces?

The existing non-XA
[`ConnectionPoolProvider`](../../ojp-datasource-api/src/main/java/org/openjproxy/datasource/ConnectionPoolProvider.java#L114-L130)
and XA
[`XAConnectionPoolProvider`](../../ojp-xa-pool-commons/src/main/java/org/openjproxy/xa/pool/spi/XAConnectionPoolProvider.java#L153-L172)
already expose optional statistics. Built-in providers expose `totalConnections`
and `maxPoolSize`; acquisition is `DataSource.getConnection()` for non-XA and
`borrowSession(Object)` for XA.

Therefore a built-in-only first implementation can use provider adapters or
provider-owned wrappers without changing public method signatures. It needs
the correct provider instance associated with each datasource, not whichever
provider currently wins default selection. It also needs access to policy and
deadline context on every actual acquisition path.

However, the present maps say statistics **may** exist and permit empty maps.
They are a diagnostic contract, not an exact headroom/cancellation contract.
The SPI does not standardize:

- whether total includes in-flight creation reservations;
- whether the cap is effective, unbounded, or stale;
- a per-call wait/deadline operation or timeout granularity;
- which exceptions mean capacity timeout rather than failed login/validation;
- whether synchronous creation honors deadlines or supports cancellation.

A custom provider might supply no statistics, implement negative maximum as
unbounded, use seconds rather than milliseconds internally, or ignore interrupts.
Do not infer built-in semantics from a classname, SQLState, or missing map entry.

### Recommended additive contract

If capacity-aware grace is to be a supported portable OJP feature, add an
**optional capability interface**, with a non-XA connection acquisition operation
and an XA backend-session equivalent. Keep the existing SPIs and ServiceLoader
registrations valid. Avoid a new mandatory abstract method and avoid changing the
meaning of `PoolConfig.connectionTimeoutMs` for existing plugins.

The capability should define, in types rather than string-map conventions:

| Contract element | Required meaning |
|---|---|
| Pool state | Established total, idle, effective maximum or explicitly unbounded/unknown, lifecycle state, and optional creation-reservation count. Snapshot is advisory, never a reservation guarantee. |
| Acquisition request | Remaining monotonic budget/cancellation context and permitted wait policy; no shared timeout mutation. |
| Failure category | Capacity wait timeout, closed/unavailable pool, validation/creation failure, interruption/cancellation; retain the original exception and chains. |
| Timing capability | Distinguish per-call queue wait, synchronous driver creation, and enforceable deadline support. Report minimum timeout granularity, including Hikari's floor. |
| Result ownership | Normal pool-managed logical `Connection` or `XABackendSession`; provider/server responsibilities for abandoned late results must be explicit. |

Use shared timing/state concepts if useful, but do not merge non-XA and XA result
types or bypass XA return/invalidate lifecycle. A provider without the optional
capability continues with documented legacy acquisition and gets an explicit
"capacity-aware acquisition unsupported" diagnostic when that feature is requested.
For a strict deployment requiring that feature on every pool, reject unsupported
providers at configuration time rather than silently claiming protection.

An alternative is additive default SPI methods advertising support as false and
throwing an explicit unsupported-operation error for the new acquisition method.
That is also compatible if designed carefully. The separate capability interface
is preferable because it does not imply that every existing provider's diagnostic
stats or old borrow operation meets the stronger acquisition contract.

**Bottom line:** No breaking SPI migration is needed. No SPI change at all is
needed for a limited built-in fix. An optional extension is recommended for
portable custom-provider semantics, not because a comparison of two counts
requires a new interface.

## 6. Implementation phases and acceptance evidence

This section proposes future work only.

1. **Establish ownership and classification.** Inventory all non-XA primary,
   replica, XA eager-connect, registry, and recovery acquisitions. Prove each
   intended guarded path has pool-specific permit ownership. Preserve original
   timeout/failure categories before generic wrapping; unknown errors fail normally.
2. **Implement bounded grace for built-ins.** Keep shared timeouts and pool maxima
   unchanged. Start with normal Hikari borrows and native DBCP/XA acquisition
   semantics; centralize deadline/accounting logic without bypassing wrappers.
   Add per-call Pool 2 waits only where the correct result lifecycle is preserved.
3. **Certify drivers and tune.** Measure ramp-up at configured maxima, validate
   connect/login/validation bounds, and adjust grace based on observations.
   Add warming only where its connection-budget and startup cost is acceptable.
4. **Expose the optional capability.** Document migration and certification for
   custom providers; retain legacy behavior and explicit unsupported diagnostics.

Use focused pool tests plus real OJP-server/client integration scenarios. Record
actual attempt count, elapsed time, terminal SQLState/cause, physical connections,
permit balance, and acquisition/exhaustion metrics.

| Scenario | Required outcome |
|---|---|
| Hikari max 2, minIdle 0; delay connection creation 800 ms after pool initialization | Original single 250 ms borrow fails; bounded grace succeeds within a 2-second test budget. Separate startup from the acquisition being tested. |
| DBCP and XA, empty pool with free capacity, 800 ms synchronous creation, maxWait 1 ms | Confirm native borrow can succeed after 800 ms without an OJP retry. Do not assert that maxWait bounds factory duration. |
| Concurrent cold burst greater than initial idle supply; delayed creation | Eligible admitted callers succeed within suitable finite grace; physical connections never exceed the applied cap and retry rate stays bounded. |
| Full pool, idle 0, all capacity leased | Terminal pool exhaustion stays short; no new grace loop solely because connections may eventually be returned. Admission timeout remains distinct. |
| Connection becomes idle just after the short timeout | A fresh idle observation permits another bounded attempt; success is not falsely rejected solely because total equals max. |
| Creations in progress occupy all internal reservations while reported total is below max | No external force-create, no overshoot, and a finite terminal deadline. Capacity metrics are not treated as reservations. |
| Database unreachable or rejecting authentication | No infinite below-max retries; permanent failure is preserved, driver calls respect certified bounds, and all acquired permits release. |
| Validation/activation error versus true wait timeout | Only the identified wait-timeout case retries; DBCP/XA `NoSuchElementException` wrappers do not collapse the distinction. |
| Grow/shrink during acquisition, including multinode/database budget caps | Read effective maximum, never enlarge it to rescue a request; allow old leases to drain without new growth grace above the reduced cap. |
| Cancellation during borrow or session creation, including a late result | No ghost session or leaked logical connection/backend session; permit releases exactly once and late results return safely. |
| Lazy replica-only session followed by a primary write; eager XA connect | Coverage reaches the actual acquisitions with correct pool-specific ownership; existing read/write and XA semantics stay unchanged. |
| XA prepared transaction and recovery under pressure | Acquisition retries do not replay XAResource operations or reclaim prepared branches; recovery retains its existing session/resource path. |
| Custom provider with empty stats, unknown cap, or no deadline capability | Legacy policy or explicit configuration rejection as documented; no invented zero-total headroom or false deadline guarantee. |
| Warm success / grace success / terminal failure telemetry | Normal warm path incurs no repeated stats polling; successful grace is not counted as terminal exhaustion; elapsed acquisition covers all attempts. |

Also verify the shipped Maven dependency tree and driver versions. A hard
end-to-end deadline claim is acceptable only for certified driver/provider
combinations that demonstrably honor it; the retry budget alone is not evidence.

## 7. References

Repository links above are relative to the recorded OJP baseline. Upstream
behavior was checked against release-tagged sources, not just current API docs:

- [HikariCP 7.0.2 HikariDataSource](https://github.com/brettwooldridge/HikariCP/blob/HikariCP-7.0.2/src/main/java/com/zaxxer/hikari/HikariDataSource.java),
  HikariConfig, HikariPool, and PoolBase linked above.
- [DBCP2 2.14.0 BasicDataSource](https://github.com/apache/commons-dbcp/blob/rel/commons-dbcp-2.14.0/src/main/java/org/apache/commons/dbcp2/BasicDataSource.java)
  for initialization and pool access, and PoolingDataSource linked above.
- [DBCP2 2.14.0 dependency POM](https://github.com/apache/commons-dbcp/blob/rel/commons-dbcp-2.14.0/pom.xml)
  for the declared Pool 2 version.
- Pool 2 2.13.0 and 2.13.1 GenericObjectPool sources linked above.
- [OJP database total connection budget analysis](./DATABASE_TOTAL_CONNECTION_BUDGET_ANALYSIS.md)
  and [always-on admission analysis](./ALWAYS_ON_ADMISSION_CONTROL_SEMAPHORE_ANALYSIS.md)
  for related design rationale, qualified by current implementation evidence.
