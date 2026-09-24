# Analysis: Eager ResultSet/Connection Close Attempt (Dropped)

## Summary

This analysis evaluates an attempt to optimize query lifecycle by eagerly closing resources as soon as a `ResultSet` was fully consumed.

That approach has been **dropped** due to side effects that can break normal JDBC flows, especially when a client reuses the same `Connection` for multiple queries.

## Why this was attempted

The main motivation was performance and efficiency:

- remove the extra client→server roundtrip for explicit `close()` calls at the end of connection usage,
- free server-side resources sooner,
- reduce time that pooled connections stay occupied after the last row is read.

## What was attempted

The implementation explored eager close paths after full `ResultSet` consumption under guarded conditions (for example: no LOB/binary stream usage, non-transactional, non-XA paths).

## Why it was dropped

Even with guards, the behavior introduces correctness and lifecycle risks:

1. **Breaks connection reuse expectations**
   - A common JDBC pattern is: execute query A, consume rows, then execute query B on the same `Connection`.
   - If connection/session are closed eagerly, query B must reacquire resources instead of reusing the same session.

2. **Can increase queuing/pool contention**
   - If each follow-up query must re-enter acquisition, requests can queue again for a pooled connection.
   - This can increase latency variance and reduce throughput under concurrent workloads.

3. **Race-prone lifecycle with async/client timing**
   - Client-side behavior after reading the last row may still include metadata calls, warning checks, `getMoreResults()`, or deferred resource operations.
   - Early termination creates timing-dependent failures and intermittent behavior.

4. **LOB and binary stream safety complexity**
   - LOB/binary streams can outlive row iteration timing.
   - Aggressive early close can invalidate subsequent stream reads or related calls.

5. **Compatibility risk with real-world JDBC usage**
   - Existing applications rely on standard JDBC lifecycle semantics (`Connection.close()` as the definitive end of connection scope).
   - Diverging from that default can produce hard-to-debug regressions.

## Decision

The eager close approach that closes connection/session as part of result set completion is not being pursued.

Current recommendation:

- keep connection/session lifecycle tied to normal JDBC close semantics,
- prefer explicit `close()` and existing timeout/cleanup policies,
- only revisit cursor-level optimizations if they are proven safe and fully compatible.

## Additional considerations for future exploration

If optimization is revisited later, suggested constraints are:

- opt-in feature flag (default off),
- strict compatibility tests for multi-statement same-connection flows,
- dedicated LOB/binary stream safety matrix,
- deterministic behavior guarantees for metadata/navigation/post-read APIs,
- clear observability to detect regression (queueing, retries, SQL errors, pool wait time).
