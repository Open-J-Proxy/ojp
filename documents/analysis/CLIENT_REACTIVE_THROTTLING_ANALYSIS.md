# OJP Client-Side Throttling — Design Notes

## Why Client-Side Throttling?

When the OJP server's admission control is under pressure — specifically when requests
wait longer than the configured `connectionTimeout` and result in an **admission timeout**
— the server is already struggling. Every new request that arrives at that moment makes
the situation worse: it extends the wait queue, competes with requests already waiting,
and can cascade into more timeouts.

Client-side throttling prevents this from happening in the first place. Instead of letting
every client thread fire requests until the server starts rejecting them, each client
limits its own concurrent in-flight count to its fair share of the server's real capacity.
Shorter queues mean lower admission-wait latency, fewer timeouts, and no thundering-herd
spikes when load increases suddenly.

**Without client throttling (problem):** 80 threads across 8 app servers hit one OJP node
at once (example node configured with a 40-slot pool) → the pool is overwhelmed → many requests queue or timeout → the
server's situation gets worse, not better.

**With client throttling (solution):** each app server caps itself at `ceil(40/8)×1×0.9 ≈ 4`
concurrent requests → at most ~32 in-flight across the cluster → the pool stays within
capacity, admission timeouts stop, and throughput is maintained.

## Simple Flow (with examples)

```mermaid
flowchart TD
    A["Connect to OJP server"] --> B["Driver receives SessionInfo: maxAdmission, clientCount, observedPeak"]
    B --> C["Compute local limit (fair share with 10% safety margin)"]
    C --> D{"Before each SQL call, is inFlight < limit?"}
    D -- Yes --> E[Send request to server]
    D -- No --> F["Reject locally: SQLTransientException"]
    E --> G{"Did server return RESOURCE_EXHAUSTED?"}
    G -- No --> H["Release slot and continue"]
    G -- Yes --> I["Halve reactive limit (AIMD decrease)"]
    I --> F
```

**Example 1 (normal):**
- Server says `maxAdmission=40`, `clientCount=8`, `numServers=2`
- Limit = `ceil(40/8) × 2 × 0.9 = 9`
- Each client sends up to 9 concurrent requests; pressure stays controlled.

**Example 2 (overload signal):**
- A request gets `RESOURCE_EXHAUSTED` from server.
- Driver halves reactive limit (for example `40 → 20`).
- Next bursts are rejected in the client first, so fewer requests hit an overloaded server.

---

## Glossary

| Acronym / Term | Full name | Meaning in this document |
|---|---|---|
| **AIMD** | Additive Increase, Multiplicative Decrease | Congestion-control algorithm borrowed from TCP. When overload is detected (admission timeout), the limit drops sharply (multiplicative decrease). As successful operations accumulate, the limit grows back one step at a time (additive increase). Prevents thundering-herd bursts while recovering capacity gradually. |
| **CAS** | Compare-And-Swap | A CPU-level atomic instruction used by `AtomicInteger.incrementAndGet()`. Reads a value, computes the new value, and writes it back only if the original value is unchanged — all in one atomic step. Allows safe concurrent updates without locks. |
| **connHash** | Connection hash | A hash of the JDBC URL + username + password that uniquely identifies a datasource/credential pair. All JDBC connections with the same URL and credentials share the same `connHash`. |
| **clientUUID** | Client universally unique identifier | A random identifier generated once per JVM process by the OJP driver. Used by the server to count distinct application instances (JVMs) connected to a given `connHash`, as opposed to counting raw connection objects. |
| **JDBC** | Java Database Connectivity | The standard Java API for connecting to relational databases. OJP implements this API so applications can use it as a drop-in driver. |
| **JVM** | Java Virtual Machine | The runtime that executes Java bytecode. One JVM = one application process. Each JVM has one `clientUUID`. |
| **OLTP** | Online Transaction Processing | Database workloads characterised by many short, fast queries (select/insert/update), as opposed to long analytical queries. The default SQS parameters are tuned for OLTP. |
| **OJP** | Open J Proxy | This project. A JDBC Type 3 proxy driver that forwards JDBC calls over gRPC to a central server which owns the real database connection pools. |
| **SQS** | Slow Query Segregation | An OJP server feature that routes long-running SQL queries to a dedicated "slow lane" pool, preventing them from starving fast queries waiting for connection slots. |
| **TCP CWND** | TCP Congestion Window | TCP's built-in mechanism for avoiding network overload: grow the send window aggressively until packet loss occurs (slow start), then halve it (multiplicative decrease), then grow by one segment per round-trip (additive increase). `observedPeak` follows the same pattern applied to OJP admission slots instead of bytes. |
| **gRPC** | Google Remote Procedure Call | The RPC framework used for communication between the OJP driver and server. Runs over HTTP/2. |

---

## Current Design: Three Configurable Modes

All three modes use the same `AtomicInteger` fail-fast counter in the driver (no blocking,
no Semaphore) and AIMD step-limited increase. They differ only in where the limit comes from.

### Protocol additions to `SessionInfo`

```proto
int32 clientCount   = 9;   // distinct JVMs (clientUUID) connected to this connHash on this node
int32 maxAdmission  = 10;  // per-node HikariCP pool size (confirmed: SlotManager.totalSlots = actualPoolSize)
int32 observedPeak  = 11;  // adaptive effective capacity (0 = no failure observed yet)
```

`clusterHealth` already carries the UP/DOWN node list; `numOjpServers` is derived from it.

---

### Proactive Mode

Throttles from the very first `connect()` using the fair-share formula:

```java
int effectiveAdmission = (observedPeak > 0) ? observedPeak : maxAdmission;
int rawLimit = (int) Math.ceil((double) effectiveAdmission / clientCount) * numOjpServers;
int limit = Math.max(1, (int)(rawLimit * 0.9));  // 10% safety headroom
```

**Why ceiling division + 10% headroom:**
Floor division permanently wastes capacity (`floor(20/7)=2` leaves 6 slots idle).
Ceiling slightly over-allocates (`ceil(20/7)=3`, total=21 vs capacity=20), so the 10%
reduction absorbs one stale `clientCount` error.

**Why 10%, not more:** Absorbs exactly one missed client join/leave cycle at typical
client counts without meaningfully reducing steady-state throughput.

**Concurrency control — `AtomicInteger` counter:**

```java
AtomicInteger inFlight = new AtomicInteger(0);
volatile int limit;  // single volatile write on every SessionInfo response

// Acquire (non-blocking, fail-fast):
if (inFlight.incrementAndGet() > limit) {
    inFlight.decrementAndGet();
    throw new SQLException("Client throttle limit exceeded: " + connHash);
}
// Release (always in finally):
inFlight.decrementAndGet();
```

Zero overhead on the happy path. Resizing is one volatile write.

**AIMD step-limited increase:**
- Decrease: apply immediately (fast overload response).
- Increase: `limit = min(newLimit, currentLimit + 1)` per `SessionInfo` update.

Rationale: when 4 out of 8 clients disconnect simultaneously, every remaining client
would burst to the new higher limit at once. Step-limited increase prevents this spike.
Under normal query load, `SessionInfo` arrives every few ms; convergence takes seconds.

**In-transaction bypass:**
When `autoCommit == false`, subsequent statements on that connection skip the `inFlight`
check. Without this, a thread holding an open transaction can block waiting for a permit
while other threads consume all permits, causing the server's transaction timeout to fire
before the thread ever sends its next statement (deadlock-by-timeout).

**Cross-node `clientCount` caveat (v1 documented limitation):**
Each node counts only its own connected clients. In a 2-node cluster where App1 connects
to Node A and App2 to Node B, both nodes report `clientCount = 1`, so both clients
compute `(10/1)*2 = 20` permits — against a real cluster capacity of 20. The formula
over-allocates. The server's own `SlotManager` is the final safety gate. Fix deferred
to v2 (cross-node count sharing).

---

### Reactive Mode (`observedPeak`)

Instead of using the static configured pool size, the server tracks the actual peak
in-flight count just before an admission timeout occurred, and sends it as `observedPeak`.

**How it works (TCP CWND analogy — shrink on loss, grow slowly on clean delivery):**

Server changes in `SlotManager`:
```java
// On wait-timeout: snap observedPeak down to current active count, with 10% floor
int currentActive = activeFastOperations.get() + activeSlowOperations.get();
int floor = Math.max(1, (int)(totalSlots * 0.1));
observedPeak.updateAndGet(prev -> Math.max(floor, Math.min(prev, currentActive)));

// AIMD recovery: every totalSlots*2 successful releases, increment by 1
if (successCount.incrementAndGet() % (totalSlots * 2) == 0) {
    observedPeak.updateAndGet(prev -> Math.min(totalSlots, prev + 1));
}
```

Initialized to `totalSlots`. Sent as `observedPeak` in `SessionInfo`.
Driver uses it in the proactive formula in place of `maxAdmission`.

**Key risks and mitigations:**

| Risk | Mitigation |
|---|---|
| "False floor": single slow query fires timeout at low in-flight count → `observedPeak` collapses | 10% floor (`max(1, totalSlots * 0.1)`) |
| Recovery too slow → server underutilized | K configurable via `ojp.server.admissionControl.observedPeakRecoveryFactor`; default `totalSlots × 2` |
| Recovery too fast → burst risk | AIMD additive increase (+1 per cycle) is inherently slow |
| SQS interaction: slow-lane timeout ≠ total overload | Use `activeSlow + activeFast` total, not per-lane counts |
| Concurrent timeout races updating `observedPeak` | Pre-snapshot `currentActive` before CAS; `updateAndGet` handles the loop |
| Only semaphore wait-timeout is a clean signal | Queue-depth rejections should **not** update `observedPeak` (fires before wait, may reflect low `currentActive` unrelated to capacity) |

---

### Reactive throttle — per-client concurrency budget over time

The table below traces how the per-client effective concurrency limit evolves through an
overload episode and subsequent recovery. Assumptions: **6 clients (C1–C6)**, pool of
**60 total slots** on one OJP server, **COMBINED mode** (effective limit =
`min(proactiveLimit, reactiveLimit)`).

Derived constants:
- `proactiveLimit` = `ceil(60 / 6) × 0.9` = **9** per client (fixed from first `connect()`)
- `reactiveLimit` starts at **∞** (uninitialised, `Integer.MAX_VALUE`) — no failure seen yet
- Soft floor = `proactiveLimit / reactiveFloorDivisor` = `9 / 4` = **2** (minimum reactive can reach)
- Decrease factor = **0.5** (halving, default)
- Overload cooldown = **200 ms** (only one halving per burst window per client)
- Recovery threshold = `max(8, reactiveLimit)` successes per **+1** step (autonomous, grows with the limit)

> **Reading the numbers:** each cell shows the **effective limit** for that client at that
> moment, i.e. `min(proactiveLimit=9, reactiveLimit)`. When reactive is ∞ the effective
> limit equals the proactive limit (9). When reactive has been set by an overload event it
> can be below 9, and that is the binding constraint.

| Moment | C1 reactive | C2 reactive | C3 reactive | C4 reactive | C5 reactive | C6 reactive | Effective cap per client | Total cap | What happened |
|---|---|---|---|---|---|---|---|---|---|
| **Healthy** | ∞ | ∞ | ∞ | ∞ | ∞ | ∞ | 9 (proactive governs) | **54** | No failure observed yet. `reactiveLimit = MAX_VALUE`. Effective limit = `min(9, ∞)` = **9** for every client. |
| **First overload wave** *(C1, C2 hit server rejection)* | 4 | 4 | ∞ | ∞ | ∞ | ∞ | C1/C2: **4**, C3–C6: 9 | **44** | C1 & C2 call `notifyServerOverload()`. `reactiveLimit` was unset → seeded at `max(floor, floor(9×0.5))` = `max(2, 4)` = **4**. Cooldown (200 ms) prevents a burst of simultaneous rejections from causing multiple halvings. C3–C6 not yet affected. |
| **Overload continues** *(C3–C6 also hit; C1/C2 halve again)* | 2 | 2 | 4 | 4 | 4 | 4 | C1/C2: **2**, C3–C6: **4** | **20** | C1 & C2: `max(floor, floor(4×0.5))` = `max(2, 2)` = **2** — soft floor reached. C3–C6: first overload, seeded at **4**. |
| **Still overloaded** *(all clients halve again)* | 2 | 2 | 2 | 2 | 2 | 2 | **2** | **12** | C3–C6: `max(2, floor(4×0.5))` = **2** — all hit the soft floor. C1 & C2 are already there. **No further decrease is possible** regardless of additional rejections. |
| **Overload clears — recovery begins** | 3 | 3 | 3 | 3 | 3 | 3 | **3** | **18** | Server resumes accepting requests. Each successful `release()` feeds the autonomous recovery counter. After `max(8, 2)` = **8** successes the reactive limit grows by +1. All clients reach **3**. |
| **Recovery mid-point** | 5 | 5 | 5 | 5 | 5 | 5 | **5** | **30** | Additive +1 per `max(8, reactiveLimit)` successes continues. The threshold grows with the limit, deliberately slowing recovery to prevent a new burst. |
| **Full recovery** | 9 | 9 | 9 | 9 | 9 | 9 | **9** | **54** | `reactiveLimit` has climbed back to **9** = `proactiveLimit`. The driver caps it there (code: `if (rl < cap) { next = rl + 1; }`). Effective limit = `min(9, 9)` = 9 — same number as Healthy but now reactive is the explicit value, not ∞. |

> **Why does it recover all the way to 9 and not get stuck?**
> Two independent additive-increase channels operate simultaneously:
> 1. **Driver autonomous recovery** — every successful `release()` increments a counter;
>    after `max(8, reactiveLimit)` successes the reactive limit grows by +1, bounded by
>    `proactiveLimit`. No new `connect()` needed.
> 2. **SessionInfo updates** — the server's own `observedPeak` also recovers via AIMD
>    (+1 per `totalSlots×2` releases). Each fresh `SessionInfo` triggers
>    `updateFromSessionInfo()`, which applies the same AIMD +1 rule to `reactiveLimit`.
>
> Both channels are capped at `proactiveLimit` (9), so 9 is both the ceiling and the
> eventual steady state — but it is reached by additive steps, not by a reset.

**Key observations from the table:**

- **"Healthy" ≠ "Full recovery" internally**: both show an effective cap of 9, but the
  source is different. Healthy = `reactiveLimit` is ∞, proactive governs. Full recovery =
  `reactiveLimit` is explicitly 9, matching proactive.
- **Asymmetric speed**: descent is fast (one RESOURCE_EXHAUSTED per client → immediate halving);
  recovery is deliberately slow (one +1 per ≈ 8–9 successful completions, threshold grows).
- **Soft floor prevents collapse**: no client ever drops below `proactiveLimit / 4` (= 2).
  Total cluster capacity never falls below 12 / 54 ≈ 22 %.
- **Cooldown prevents pile-on**: a burst of N simultaneous rejections on one client counts
  as one halving event, not N.
- **Independent per-client limits**: each JVM adjusts independently. Clients that were
  luckier in timing (C3–C6) preserve higher limits longer, giving the server breathing room.



### Combined Mode (Recommended)

```java
int effectiveLimit = Math.min(proactiveLimit, reactiveLimit);
```

Proactive ensures fair share from day 1. Reactive tightens the limit when the DB
is actually struggling below its configured pool size. Neither alone is sufficient.

---

### Resolved Decisions

**Q1 — Default configuration:** `reactive` mode on by default.
Property `ojp.jdbc.clientThrottle.mode` defaults to `reactive`.
Options: `off`, `proactive`, `reactive`, `combined`. For most workloads `reactive`
delivers the most adaptive performance; switch to `combined` or `proactive` for
workloads that cannot tolerate any bursts.

**Q2 — `clientCount` tracking:** Yes, track distinct `clientUUID` values per `connHash`.
Server maintains `ConcurrentHashMap<connHash, Map<clientUUID, refCount>>` updated on every
session create/terminate. Overhead is acceptable.

**Q3 — `observedPeak = 0` sentinel:** `0 = uninitialised` is sufficient. When the server
sends `0`, the driver treats reactive limit as unconstrained (`Integer.MAX_VALUE`).

**Q4 — Reactive-only fairness:** Reactive-only mode is valid for operators who only care
about not overloading the DB and do not need fairness between clients. No-fairness caveat
is documented. Reactive mode (default) delivers the most adaptive performance; combined
mode adds static fairness on top for workloads that cannot tolerate any bursts.

---

## Summary

| Dimension | Proactive | Reactive (`observedPeak`) | Combined |
|---|---|---|---|
| Server changes | `clientCount` + `maxAdmission` in `SessionInfo` | `observedPeak` + server AIMD in `SlotManager` | Both |
| Activation | First `connect()` | After first admission timeout | First `connect()`, adapts on timeout |
| Limit source | Static config | Observed runtime capacity | `min(proactive, reactive)` |
| Adapts to DB degradation | No | Yes | Yes |
| Fairness between clients | Guaranteed | Not guaranteed | Guaranteed |
| Collapse risk | Low | Mitigated by 10% floor | Low |
| Implementation complexity | Medium | Medium | Medium–High |
| Recommended | Yes | Yes (adaptive environments) | Yes (best overall) |

---

## Using Client Throttling Together with Slow Query Segregation (SQS)

Both features address database protection but at different layers:
- **SQS** (server-side) isolates long-running queries into a dedicated slow lane so they
  cannot starve fast queries waiting for pool slots.
- **Client throttling** (driver-side) limits how many concurrent requests each application
  instance sends to the server in the first place.

They are complementary and work well together. The key interactions are described below.

### How the two features interact

**`observedPeak` and SQS classification modes:**

`observedPeak` drops when any lane's admission semaphore times out
(`activeFastOperations + activeSlowOperations` total). This total is correct and independent
of which lane fired the timeout.

With the new `RELATIVE_FAST_BASELINE` mode (the default), the slow-query threshold is set
dynamically at `slowMultiplier × fast-lane baseline` (default 5×). During startup and
workload transitions, the `minSamples` window (default 20 samples) must fill before
classification begins. Until then, **all** queries compete for fast slots. If the fast lane
becomes temporarily saturated during that warm-up window, an admission timeout will cause
`observedPeak` to snap down — even though the server is not truly overloaded.

With `ABSOLUTE_THRESHOLD` mode, the threshold is a fixed millisecond value
(`slowQueryThresholdMs`, default 1000 ms). This produces stable, predictable classification
and a more stable `observedPeak` signal.

**`maxAdmission` and SQS slot split:**

When SQS is inactive (`slowSlotPercentage = 0`), `maxAdmission = SlotManager.totalSlots` =
full HikariCP pool size.
When SQS is active (`slowSlotPercentage > 0`, default 20%), fast queries compete only for the
fast-lane slots (80% of the pool). `ConnectAction` therefore sends
`maxAdmission = SlotManager.fastSlots` so the client's proactive limit is based on the actual
fast-lane capacity rather than the full pool. Without this correction the 90% safety margin
(proactive formula uses `0.9 × maxAdmission / clientCount`) could exceed the fast slot quota,
causing immediate admission timeouts.

> **Bug fixed (2026-05):** An earlier version incorrectly sent `maxAdmission = totalSlots`
> regardless of the SQS split, causing `proactiveLimit` to exceed `fastSlots` and triggering
> repeated fast-lane timeouts. `ConnectAction` now sends `fastSlots` when `slowSlots > 0`.

A second fix in the same release corrected `ClientThrottleManager.updateFromSessionInfo()`:
it now skips the update (instead of resetting `reactiveLimit` to `MAX_VALUE`) when
`maxAdmission = 0` in the incoming `SessionInfo`. `executeUpdate` and `executeQuery`
responses carry a minimal `SessionInfo` with `maxAdmission = 0`; the previous reset silently
undid every `notifyServerOverload()` adjustment on the first successful SQL response.

### Recommendations when running both features together

**1. Use `combined` throttle mode (default) — always correct with or without SQS.**
`effectiveLimit = min(proactiveLimit, reactiveLimit)` ensures both fairness between clients
and adaptive protection when the DB actually degrades.

**2. Prefer `RELATIVE_FAST_BASELINE` mode for dynamic workloads.**
It adapts to workload shape without manual tuning. The default parameters
(`slowMultiplier=5.0`, `recoveryMultiplier=3.0`, `minSamples=20`, `baselinePercentile=50`,
`baselineRefreshIntervalSeconds=10`) are well-calibrated for typical OLTP workloads.
The 10% floor on `observedPeak` (from `MIN_OBSERVED_PEAK_RATIO`) prevents the client
budget from collapsing to zero during the warm-up window.

**3. Use `ABSOLUTE_THRESHOLD` mode for stable, well-characterised workloads.**
If you know your SLA boundary (e.g., anything over 500 ms is slow), `ABSOLUTE_THRESHOLD`
gives a stable classification signal that keeps `observedPeak` from fluctuating during
load transitions. Set `ojp.server.slowQuerySegregation.slowQueryThresholdMs` accordingly.

**4. On startup, expect a brief conservative period.**
Under `RELATIVE_FAST_BASELINE`, the baseline does not exist until `minSamples` operations
have completed. During those first N queries, everything runs in the fast lane. If you have
a large initial burst, `observedPeak` may momentarily dip. It will recover via AIMD
(+1 per `totalSlots × 2` releases). The 10% floor ensures clients are never throttled
to zero.

**5. `slowSlotPercentage` tuning.**
A higher slow-slot percentage (e.g., 30%) reduces the risk of fast-lane saturation but
slightly under-utilises the pool when all queries are fast. For typical mixed OLTP+analytics
workloads, 20% (default) is appropriate. With client throttling active, each client already
limits its total in-flight count, so a 20% reservation provides ample isolation.

**Example configuration — mixed workload with both features:**

```properties
# Slow Query Segregation
ojp.server.slowQuerySegregation.enabled=true
ojp.server.slowQuerySegregation.classificationMode=RELATIVE_FAST_BASELINE
ojp.server.slowQuerySegregation.slowMultiplier=5.0
ojp.server.slowQuerySegregation.recoveryMultiplier=3.0
ojp.server.slowQuerySegregation.minSamples=20
ojp.server.slowQuerySegregation.baselinePercentile=50
ojp.server.slowQuerySegregation.baselineRefreshIntervalSeconds=10

# Client throttling (driver-side)
ojp.jdbc.clientThrottle.mode=reactive   # default — no change needed
```

**Example configuration — predictable workload (analytics batch + OLTP):**

```properties
ojp.server.slowQuerySegregation.enabled=true
ojp.server.slowQuerySegregation.classificationMode=ABSOLUTE_THRESHOLD
ojp.server.slowQuerySegregation.slowQueryThresholdMs=800
ojp.server.slowQuerySegregation.slowSlotPercentage=30   # larger slow lane for analytics

# Use combined when the workload cannot tolerate any bursts
ojp.jdbc.clientThrottle.mode=combined
```

---

## Dropped Approaches

### Purely client-reactive (no server changes)
Driver observes `RESOURCE_EXHAUSTED` / `ServerOverloadException` and activates a local
semaphore after N consecutive rejections.

**Why dropped:** Incomplete server state view, hard deactivation (requires probe logic or
fixed cooldown), flapping between throttled/unthrottled states, no fairness between clients.
Server already sends the signals but the driver cannot infer the right limit from them.
Superseded by the server-cooperative approach which sends the limit explicitly.

### `java.util.concurrent.Semaphore` for concurrency control
**Why dropped:** No `setPermits()` — resizing requires draining and re-injecting permits.
Complex, race-prone, high overhead. Replaced by `AtomicInteger` counter + `volatile int limit`.

### Floor division (`maxAdmission / clientCount`)
**Why dropped:** Permanently wastes capacity. `floor(20/7) = 2`, 6 slots idle even
at full load. Replaced by ceiling division + 10% safety headroom.

### gRPC built-in controls
Three native gRPC-Java mechanisms were considered:

| Mechanism | Why dropped |
|---|---|
| `NettyServerBuilder.maxConcurrentCallsPerConnection(N)` | Caps concurrent HTTP/2 streams per TCP connection (global), not per `connHash`/datasource. No per-resource fairness, no AIMD, client receives an abrupt `RST_STREAM` rather than a structured `SQLException`. |
| HTTP/2 flow control (transport byte windows) | Controls bytes in flight, not request count. Not applicable for "max N concurrent SQL executions per datasource". |
| `ClientInterceptor` wrapping every `newCall` | This is exactly what `ClientThrottleManager` does — gRPC provides the hook but not the pre-built implementation. There is no off-the-shelf gRPC interceptor that is informed by server-side `SessionInfo` signals (`maxAdmission`, `observedPeak`, `clientCount`). |

All three lack the ability to scope the limit to a specific `connHash` and to incorporate the server's actual admission capacity into the client-side budget. The `ClientThrottleManager` approach was chosen because it uses the gRPC-recommended interceptor pattern while adding per-datasource, server-cooperative logic.

---

## 2026-05 Update — Reactive Throttle Hardening, Total-Slot Advertising, Per-Response Updates, Lane-Aware Overload

Following stress testing that revealed the reactive limit collapsing to 1 at near-peak open-loop load (and getting stuck there because additive recovery was only fed by connect responses), the following changes were applied. They are additive and backwards-compatible.

### 1. Reactive throttle hardening (driver)

`ClientThrottleManager` no longer collapses to 1 from a burst of `RESOURCE_EXHAUSTED` errors.

- **Cooldown:** `notifyServerOverload()` now coalesces signals received within `overloadCooldownMs` (default 200 ms) of the last effective halving. A burst of N simultaneous rejections produces one halving event, not N.
- **Soft floor:** `reactiveLimit` is bounded below by `max(1, proactiveLimit / reactiveFloorDivisor)` (default divisor 4 → floor ≈ 25 % of proactive). It cannot be driven to 1 except in degenerate single-slot configurations.
- **Configurable decrease factor:** the AIMD multiplicative decrease defaults to `0.75` (gentler than halving), and can be reduced to `0.5` or lower for more aggressive back-off.
- **Autonomous additive recovery:** on every successful `release()`, the manager increments a counter; after `recoverySuccessThreshold` successes (default `max(8, reactiveLimit)`) the reactive limit grows by +1, bounded by the proactive cap. This removes the prior dependency on connect responses for recovery — execute traffic alone is now enough.
- `updateFromSessionInfo()` also respects the floor and caps `reactiveLimit` at `proactiveLimit`.

Configuration keys (all optional, sane defaults):

```properties
ojp.jdbc.clientThrottle.overloadCooldownMs=200
ojp.jdbc.clientThrottle.reactiveFloorDivisor=4
ojp.jdbc.clientThrottle.reactiveDecreaseFactor=0.75
# 0 = auto: max(8, reactiveLimit) successes per +1 recovery step
ojp.jdbc.clientThrottle.recoverySuccessThreshold=0
```

### 2. `maxAdmission` advertised as `totalSlots`, not `fastSlots`

`SlotManager.getEffectiveMaxAdmission()` now returns `totalSlots` (the full admission-control pool) regardless of SQS being enabled. Rationale:

- Lane borrowing means a fast query can land on a slow slot and vice versa, so the realistic concurrency ceiling is the total pool size.
- The previous behaviour understated capacity by ~20–30 % under default SQS configuration and contributed to throughput cliffs at near-peak load.
- Cross-lane contamination is now addressed separately by lane-tagged overload notifications (see §4) instead of by shrinking the advertised budget.

Implication: the proactive cap (`ceil(maxAdmission/clientCount) × numServers × 0.9`) is correspondingly larger. Operators who want to retain the old behaviour can do so by reducing `slowSlotPercentage` or by capping client pool sizes downstream.

### 3. Throttle data on every response (not only connect)

Previously, only `ConnectAction` populated `maxAdmission`, `observedPeak`, and `clientCount` on the outgoing `SessionInfo`. Execute and transaction responses sent an empty placeholder, and the driver-side `updateFromSessionInfo()` returned early — meaning the only source of additive recovery was new connections. Under sustained execute traffic the reactive limit could never recover from a single overload episode.

Now the server stamps these three fields on:

- `StartTransactionAction`, `CommitTransactionAction`, `RollbackTransactionAction`
- `ExecuteUpdateAction` (every `OpResult`)
- `ResultSetHelper.handleResultSet` (every result-set chunk)

These flow back through the driver's `Connection.setSession()` / `ResultSet.nextWithSessionUpdate()` paths and feed `ClientThrottleManager.updateFromSessionInfo()`. The driver's existing AIMD discipline (additive +1 per update, multiplicative on overload) is preserved, but now reads fresh data on every response. Combined with §1's autonomous recovery, this provides two independent recovery channels.

Two helpers were added in `SessionInfoUtils`:

- `newBuilderFrom(SessionInfo, ActionContext)` — drop-in replacement for the single-arg version, stamps throttle data.
- `enrichWithThrottle(SessionInfo, ActionContext)` — returns a copy of the input with throttle data added; used by call-sites that don't build via the builder helper.

### 4. Lane-aware overload (cross-lane contamination fix)

A slow-lane `RESOURCE_EXHAUSTED` would previously halve the same client-side `reactiveLimit` that throttles fast-lane traffic. In a workload dominated by short OLTP statements (e.g. 90 % OLTP / 10 % OLAP), occasional slow-lane saturation would penalise the fast lane and shrink steady-state OLTP throughput. The same applied to admission-queue-depth fail-fast rejections, which are transient burst signals rather than saturation events.

Fix:

- `ServerOverloadException` now carries a `Lane` ({`FAST`, `SLOW`, `QUEUE`, `UNKNOWN`}) set by `AdmissionControlManager` based on which semaphore timed out / which fail-fast branch fired.
- `GrpcExceptionHandler.sendServerOverload(...)` attaches the lane to the gRPC trailer metadata key `ojp-overload-lane` on `Status.RESOURCE_EXHAUSTED`.
- The driver (`Statement`, `PreparedStatement`, `ResultSet`) parses the trailer and routes through a new lane-aware overload entry point: `ClientThrottleManager.notifyServerOverload(OverloadLane)`.
- The policy applied per lane:

| Lane     | Driver behaviour                                                                |
|----------|---------------------------------------------------------------------------------|
| FAST     | Apply AIMD decrease (with cooldown + soft floor from §1).                       |
| SLOW     | **Suppressed**: do not change the reactive limit. The slow lane is decoupled.   |
| QUEUE    | **Suppressed**: transient burst signal, not saturation.                         |
| UNKNOWN  | Treat as `FAST` for safety (preserves legacy behaviour when trailer is absent). |

This is backwards-compatible: drivers connecting to older servers see no trailer and treat every overload as `UNKNOWN` ⇒ `FAST`. Servers receiving requests from older drivers still produce the trailer; the older drivers simply ignore it.

#### Why not split the reactive limit per lane?

A full per-lane reactive limit on the driver would require classifying each outgoing query as fast or slow client-side, which the driver doesn't reliably know (classification is server-side). The lane-aware suppression above achieves the same practical result for fast-dominated workloads without the complexity. A future iteration may introduce per-lane reactive limits with server-driven classification hints; until then, the suppression policy is the right default.

