# Capacity-Aware Connection Acquisition

**Date:** 2026-11-14 | **Status:** Implemented for built-in pooled acquisitions
**Source baseline:** OJP `003a6f90411fc62d2f8031705bb716b94009ea3c`

## Summary

OJP now retries a recognized capacity-related borrow timeout while the pool
may still provide a connection and the request's original deadline has time
left. The pool remains responsible for creating connections and enforcing its
maximum; shared pool timeouts are not changed.

Use one end-to-end timeout for admission and acquisition. For example, with a
5-second admission timeout, if admission takes 1 second, pool acquisition has
at most 4 seconds left. Retries and their pauses use that remaining time; they
do not start a fresh timeout. With slow-query segregation enabled, the existing
fast- or slow-lane timeout remains in effect.

**Provider differences matter:** Hikari's configured 1 ms timeout is clamped
to 250 ms by OJP. DBCP2 and Commons Pool 2 may create a connection
synchronously, so creation can outlast their borrow wait setting.

**Implementation:** Built-in HikariCP, DBCP2, and Commons Pool 2 XA acquisitions
use bounded retries. Existing custom XA providers remain compatible through a
default SPI method; providers without supported pool statistics retain their
legacy non-XA behavior.

**Confidence:** High for the source-level behavior; medium for the right retry
budget and latency, which depend on database drivers and need measurement.

## Current behavior

| Area | Finding |
|---|---|
| Timeout setup | [`ConnectAction`](../../ojp-server/src/main/java/org/openjproxy/grpc/server/action/connection/ConnectAction.java#L227-L265) passes 1 ms to `PoolConfig` and uses the configured connection timeout for admission. |
| Hikari | [`HikariConnectionPoolProvider`](../../ojp-datasource-hikari/src/main/java/org/openjproxy/datasource/hikari/HikariConnectionPoolProvider.java#L85-L99) clamps the timeout to 250 ms. HikariCP 7.0.2's default is 30 seconds; 250 ms is its usual minimum. |
| DBCP2 | [`DbcpConnectionPoolProvider`](../../ojp-datasource-dbcp/src/main/java/org/openjproxy/datasource/dbcp/DbcpConnectionPoolProvider.java#L69-L77) passes the configured wait to DBCP2. Commons Pool 2 can create synchronously before waiting, so connection creation is not bounded by that wait. |
| Non-XA manager | [`ConnectionAcquisitionManager`](../../ojp-server/src/main/java/org/openjproxy/grpc/server/ConnectionAcquisitionManager.java) retries recognized capacity timeouts only when provider statistics show idle capacity or room to grow. |
| Other paths | Lazy primary and replica borrows in [`Session`](../../ojp-server/src/main/java/org/openjproxy/grpc/server/Session.java#L228-L290) now use the same acquisition manager. XA borrows eagerly through [`HandleXAConnectionWithPoolingAction`](../../ojp-server/src/main/java/org/openjproxy/grpc/server/action/connection/HandleXAConnectionWithPoolingAction.java#L321-L409), without the non-XA session-permit transfer. |
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

## Implemented policy

1. Start a monotonic end-to-end deadline **before admission**, using the timeout
   for the selected lane and any shorter RPC deadline. After admission,
   acquisition gets only the time remaining. For a 5-second timeout and a
   1-second admission wait, the acquisition budget is 4 seconds.
2. Keep the same non-XA admission reservation through retries. Transfer it only
   after successful session creation; release it once on terminal failure.
   Lazy primary/replica borrows use the same deadline. XA uses its pool's own
   cap; it does not acquire or transfer the non-XA session permit.
3. On a classified capacity timeout, check cancellation, remaining time, pool
   lifecycle, idle count, total, and effective maximum. Retry if an idle
   connection may have appeared or if total is below the current maximum. If
   the pool is full with no idle connections, fail as exhausted. Unknown or
   unavailable statistics must not be treated as zero.
4. Pace retries with a randomized 20–50 ms delay, capped by the remaining time.
   Do not retry every 1 ms: that can create a CPU-heavy loop without making
   connections arrive sooner. For Hikari, do not start another borrow when
   less than its configured borrow timeout remains.
5. Charge every attempt and pause to the same deadline. For synchronous
   DBCP/XA creation, check the deadline again when the call returns. If the
   request was cancelled or expired, safely return the late result instead of
   publishing a session.
6. Preserve the final provider cause, SQLState, vendor code, and chained
   exceptions. Report one terminal exhaustion per failed request, not one per
   retry. Record total acquisition time and log retry attempts.

Use `System.nanoTime()` for elapsed time. Pool counts are advisory and can race
with borrowers, creators, and resizers. Never exceed a pool's applied maximum,
force-create outside the pool, mutate a shared timeout during a borrow, or
replay SQL. Do not retry XA `start`, `prepare`, `commit`, or `recover` as if they
were connection borrows.

## SPI impact

The existing [`ConnectionPoolProvider`](../../ojp-datasource-api/src/main/java/org/openjproxy/datasource/ConnectionPoolProvider.java#L114-L130)
provides optional statistics. Built-in providers report pool totals and
maxima, so non-XA retry does not require an SPI change. The XA provider SPI
adds a default per-call budget overload; existing implementations continue to
work through the prior method.

Providers without usable statistics, recognized timeout errors, or per-call
deadline support cannot provide the same guarantee. Non-XA acquisition keeps
legacy behavior when statistics are unavailable. A custom XA provider using
the default overload also keeps its original borrow behavior.

## Implementation and acceptance checks

The implementation applies the deadline to admission-scoped non-XA primary
and replica acquisition, eager pooled XA session borrowing, and the Commons
Pool 2 XA provider. It leaves XA transaction operations and direct, unpooled
JDBC connections unchanged. Pool maxima and shared timeout settings are
unchanged.

Focused checks cover:

- Hikari retries after a capacity timeout while the pool has growth capacity.
- A 300 ms deadline with 250 ms already consumed; no second Hikari borrow starts.
- Full-pool exhaustion and permanent errors; no retry occurs.
- A connection returned after the deadline; it is closed rather than published.
- XA acquisition at the pool maximum; no connection is created beyond the cap.

Driver creation calls may still outlast the remaining deadline. Late
connections are returned or closed, but this policy is not a hard interruption
of arbitrary JDBC driver code.

## References

- HikariCP 7.0.2: [HikariConfig](https://github.com/brettwooldridge/HikariCP/blob/HikariCP-7.0.2/src/main/java/com/zaxxer/hikari/HikariConfig.java), [HikariPool](https://github.com/brettwooldridge/HikariCP/blob/HikariCP-7.0.2/src/main/java/com/zaxxer/hikari/pool/HikariPool.java), and [PoolBase](https://github.com/brettwooldridge/HikariCP/blob/HikariCP-7.0.2/src/main/java/com/zaxxer/hikari/pool/PoolBase.java).
- DBCP2 2.14.0: [BasicDataSource](https://github.com/apache/commons-dbcp/blob/rel/commons-dbcp-2.14.0/src/main/java/org/apache/commons/dbcp2/BasicDataSource.java), [PoolingDataSource](https://github.com/apache/commons-dbcp/blob/rel/commons-dbcp-2.14.0/src/main/java/org/apache/commons/dbcp2/PoolingDataSource.java), and [dependency POM](https://github.com/apache/commons-dbcp/blob/rel/commons-dbcp-2.14.0/pom.xml).
- Commons Pool 2.13.0 and 2.13.1: [GenericObjectPool 2.13.1](https://github.com/apache/commons-pool/blob/rel/commons-pool-2.13.1/src/main/java/org/apache/commons/pool2/impl/GenericObjectPool.java).
- Related OJP analyses: [database connection budgets](./DATABASE_TOTAL_CONNECTION_BUDGET_ANALYSIS.md) and [admission control](./ALWAYS_ON_ADMISSION_CONTROL_SEMAPHORE_ANALYSIS.md).
