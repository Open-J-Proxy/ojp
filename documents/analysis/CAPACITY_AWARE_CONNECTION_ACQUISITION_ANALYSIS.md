# Capacity-Aware Connection Acquisition

**Date:** 2026-11-14 | **Status:** Analysis only; no runtime, configuration, or SPI changes
**Source baseline:** OJP `003a6f90411fc62d2f8031705bb716b94009ea3c`

## Summary

OJP can avoid failing a request just because a pool is still creating
connections. After a capacity-related borrow timeout, retry only while the
pool may still provide a connection and the request's original deadline has
time left. Keep the pool in charge of creating connections and enforcing its
maximum; do not extend a shared pool timeout or retry forever.

Use one end-to-end timeout for admission and acquisition. For example, with a
5-second connection timeout, if admission takes 1 second, pool acquisition
has at most 4 seconds left. Retries and their pauses use that remaining time;
they do not start a fresh timeout.

**Provider differences matter:** Hikari's configured 1 ms timeout is clamped
to 250 ms by OJP. DBCP2 and Commons Pool 2 may create a connection
synchronously, so creation can outlast their borrow wait setting.

**Recommendation:** A built-in-only change can use the current provider
interfaces. For portable custom-provider support, add an optional typed
acquisition capability rather than changing existing SPI requirements.

**Confidence:** High for the source-level behavior; medium for the right retry
budget and latency, which depend on database drivers and need measurement.

## Current behavior

| Area | Finding |
|---|---|
| Timeout setup | [`ConnectAction`](../../ojp-server/src/main/java/org/openjproxy/grpc/server/action/connection/ConnectAction.java#L227-L265) passes 1 ms to `PoolConfig` and uses the configured connection timeout for admission. |
| Hikari | [`HikariConnectionPoolProvider`](../../ojp-datasource-hikari/src/main/java/org/openjproxy/datasource/hikari/HikariConnectionPoolProvider.java#L85-L99) clamps the timeout to 250 ms. HikariCP 7.0.2's default is 30 seconds; 250 ms is its usual minimum. |
| DBCP2 | [`DbcpConnectionPoolProvider`](../../ojp-datasource-dbcp/src/main/java/org/openjproxy/datasource/dbcp/DbcpConnectionPoolProvider.java#L69-L77) passes the configured wait to DBCP2. Commons Pool 2 can create synchronously before waiting, so connection creation is not bounded by that wait. |
| Non-XA manager | [`ConnectionAcquisitionManager`](../../ojp-server/src/main/java/org/openjproxy/grpc/server/ConnectionAcquisitionManager.java#L97-L181) makes one borrow attempt and wraps caught SQL exceptions as transient connection failures. |
| Other paths | [`Session`](../../ojp-server/src/main/java/org/openjproxy/grpc/server/Session.java#L228-L290) also borrows primary and replica connections directly. XA borrows eagerly through [`HandleXAConnectionWithPoolingAction`](../../ojp-server/src/main/java/org/openjproxy/grpc/server/action/connection/HandleXAConnectionWithPoolingAction.java#L321-L409), without the non-XA session-permit transfer. |
| Admission | [`AdmissionControlManager`](../../ojp-server/src/main/java/org/openjproxy/grpc/server/AdmissionControlManager.java#L222-L266) limits logical work; a permit does not reserve a physical connection. |

Pool statistics are snapshots, not reservations. `total < max` means a retry
may be worthwhile, not that the pool can create a connection for this caller.
Use the effective runtime maximum, which may be lower than the configured pool
maximum after database-budget or multinode resizing.

## Provider behavior

| Provider | What the wait setting means | Retry implication |
|---|---|---|
| HikariCP 7.0.2 | A borrow waits for an available entry; a background creator grows the pool. OJP clamps its configured timeout to 250 ms. | Use normal datasource borrows. Do not depend on Hikari internals or start another attempt if less than the 250 ms floor remains. |
| DBCP2 2.14.0 | Commons Pool 2 may reserve room and call the connection factory synchronously before waiting. | A slow creation can succeed after the wait setting. After a failed borrow, bounded retries may help when other creators occupy capacity. |
| Commons Pool 2 XA 2.13.x | `borrowObject()` can synchronously create an XA backend session. | Keep retries inside the XA provider/wrapper so session tracking and return/invalidate behavior remain intact. |

DBCP/XA `NoSuchElementException` wrappers do not by themselves distinguish a
capacity wait timeout from creation, activation, or validation failure. Classify
the original provider error before generic wrapping. Do not retry authentication,
configuration, validation, shutdown, or interruption errors as capacity
timeouts.

Pool wait settings are not universal login deadlines. Vendor connect and
validation timeouts may be needed. A gRPC deadline or thread interrupt alone
cannot reliably stop an arbitrary JDBC driver call.

## Recommended policy

1. Start a monotonic end-to-end deadline **before admission**. Bound it by the
   configured connection timeout and any shorter RPC deadline. After admission,
   acquisition gets only the time remaining. For a 5-second timeout and a
   1-second admission wait, the acquisition budget is 4 seconds.
2. Keep the same admission reservation through retries. Transfer it only after
   successful session creation; release it once on terminal failure. Apply the
   same ownership check to lazy primary/replica and eager XA paths rather than
   assuming they all use the non-XA helper.
3. On a classified capacity timeout, check cancellation, remaining time, pool
   lifecycle, idle count, total, and effective maximum. Retry if an idle
   connection may have appeared or if total is below the current maximum. If
   the pool is full with no idle connections, fail as exhausted. Unknown or
   unavailable statistics must not be treated as zero.
4. Pace retries. Prefer a provider-native timed wait where available; otherwise
   use a modest bounded delay (for example, about 25 ms with jitter), capped by
   the time remaining. Do not retry every 1 ms: that can create a CPU-heavy loop
   without making connections arrive sooner. Keep Hikari retry gaps short
   enough not to interrupt its background growth.
5. Charge every attempt and pause to the same deadline. For synchronous
   DBCP/XA creation, check the deadline again when the call returns. If the
   request was cancelled or expired, safely return the late result instead of
   publishing a session.
6. Preserve the final provider cause, SQLState, vendor code, and chained
   exceptions. Report one terminal exhaustion per failed request, not one per
   retry. Track total acquisition time and retry attempts separately.

Use `System.nanoTime()` for elapsed time. Pool counts are advisory and can race
with borrowers, creators, and resizers. Never exceed a pool's applied maximum,
force-create outside the pool, mutate a shared timeout during a borrow, or
replay SQL. Do not retry XA `start`, `prepare`, `commit`, or `recover` as if they
were connection borrows.

## SPI impact

The existing [`ConnectionPoolProvider`](../../ojp-datasource-api/src/main/java/org/openjproxy/datasource/ConnectionPoolProvider.java#L114-L130)
and [`XAConnectionPoolProvider`](../../ojp-xa-pool-commons/src/main/java/org/openjproxy/xa/pool/spi/XAConnectionPoolProvider.java#L153-L172)
allow optional statistics. Built-in providers report pool totals and maxima,
so a limited implementation does not need a breaking SPI change.

The current SPI does not define whether totals include in-flight creation,
whether a maximum is current or unbounded, how to classify timeout errors, or
whether a provider honors deadlines or cancellation. For portable support, add
an optional capability that describes pool state, typed failure categories,
per-call remaining-time behavior, and ownership of late results. Keep legacy
providers working; report the feature as unsupported, or reject them when a
deployment explicitly requires the capability.

## Implementation and acceptance checks

Implement in phases: first inventory acquisition paths and preserve original
failure categories; then add bounded retries for built-in providers; finally
measure driver behavior and consider the optional capability. Keep pool maxima
and shared timeout settings unchanged.

At minimum, test:

- A cold Hikari pool where a 250 ms borrow fails but growth completes within
  the remaining end-to-end timeout.
- A 5-second overall timeout with 1 second spent in admission; acquisition
  must not receive a new 5-second budget.
- DBCP and XA synchronous creation taking longer than the configured wait.
- A full pool, a creation already in progress, and a concurrent pool resize;
  retries stay bounded and never exceed the applied maximum.
- Unreachable database, authentication failure, cancellation, and a late
  connection result; causes and permit ownership remain correct.
- A custom provider without useful statistics or deadline support; behavior
  follows the documented legacy or unsupported path.
- Successful retries and terminal failures; retry metrics do not inflate
  exhaustion counts.

Measure elapsed time, attempt count, physical connections, permit balance,
terminal cause, and acquisition/exhaustion metrics. Do not claim a hard
end-to-end deadline unless the specific driver and provider have demonstrated
that they honor it.

## References

- HikariCP 7.0.2: [HikariConfig](https://github.com/brettwooldridge/HikariCP/blob/HikariCP-7.0.2/src/main/java/com/zaxxer/hikari/HikariConfig.java), [HikariPool](https://github.com/brettwooldridge/HikariCP/blob/HikariCP-7.0.2/src/main/java/com/zaxxer/hikari/pool/HikariPool.java), and [PoolBase](https://github.com/brettwooldridge/HikariCP/blob/HikariCP-7.0.2/src/main/java/com/zaxxer/hikari/pool/PoolBase.java).
- DBCP2 2.14.0: [BasicDataSource](https://github.com/apache/commons-dbcp/blob/rel/commons-dbcp-2.14.0/src/main/java/org/apache/commons/dbcp2/BasicDataSource.java), [PoolingDataSource](https://github.com/apache/commons-dbcp/blob/rel/commons-dbcp-2.14.0/src/main/java/org/apache/commons/dbcp2/PoolingDataSource.java), and [dependency POM](https://github.com/apache/commons-dbcp/blob/rel/commons-dbcp-2.14.0/pom.xml).
- Commons Pool 2.13.0 and 2.13.1: [GenericObjectPool 2.13.1](https://github.com/apache/commons-pool/blob/rel/commons-pool-2.13.1/src/main/java/org/apache/commons/pool2/impl/GenericObjectPool.java).
- Related OJP analyses: [database connection budgets](./DATABASE_TOTAL_CONNECTION_BUDGET_ANALYSIS.md) and [admission control](./ALWAYS_ON_ADMISSION_CONTROL_SEMAPHORE_ANALYSIS.md).
