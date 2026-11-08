# Connection.abort() Analysis Summary

**Date:** 2026-11-07

**Status:** Analysis only; no runtime changes or implementation commitment

**Source baseline:** `b1dcba780d466eedf261c4b61d022d475d87572b`

## Main finding

OJP's `Connection.abort(Executor)` is currently unsupported. It checks whether
the connection is usable, then throws `SQLFeatureNotSupportedException`. It
does not run the executor, send an RPC, cancel SQL, or close anything.

The useful opportunity is an emergency stop for one logical connection and its
owned database resources. The hard part is not the Java method: it is safe
physical termination across server-side pools, concurrent requests, lazy
session creation, primary/replica connections, multinode routing, and XA.

**Confidence:** High for current behavior, based on source inspection; medium
for the proposed architecture, because actual database-driver abort behavior
and pool-wrapper interactions require integration experiments.

## Implementation choices

| Approach | Main advantage | Main disadvantage | Recommendation |
|---|---|---|---|
| Keep unsupported | Honest behavior; no termination risk | Applications have no connection-level emergency stop | Keep until real semantics are available |
| Submit existing `close()`/`terminateSession()` | Small change; reuses cleanup plumbing | Graceful rollback/close can block; pooled close can recycle instead of destroy | Not a sufficient `abort()` implementation |
| Dedicated abort RPC + backend `Connection.abort()` | Closest to JDBC semantics; targeted hard stop | Requires lifecycle fencing, driver verification, and pool bookkeeping | Preferred core |
| Pool-aware abort and per-connection invalidation | Prevents damaged connection reuse | Eviction APIs often close rather than abort; adapters need ownership information | Required companion for pooled mode |
| Cancel active statements, then escalate to abort | Can release SQL locks before physical teardown | Cancel is driver-dependent; connection must still end closed | Optional bounded optimization |
| Cancel only this connection's gRPC calls | Frees client waiters without breaking shared channels | RPC cancellation alone does not prove database SQL stopped | Companion, not replacement |
| Database-native session termination | Possible escape hatch for a stuck/unsupported driver | Privileged, vendor-specific, dangerous if target identity is stale | Optional last resort |
| Lease-based orphan cleanup | Handles lost clients and failed delivery | Delayed; cannot fulfill immediate termination alone | Later reliability enhancement |

These are building blocks, not eight interchangeable implementations. The
recommended combination is a dedicated control RPC, local lifecycle fencing,
per-call cancellation, backend abort, and pool-specific invalidation.

## Useful applications

| Idea | Pros | Cons / guardrail |
|---|---|---|
| Request-deadline watchdog | Limits how long callers wait on abandoned work | Abort only a connection owned by that request; transaction outcome may be unknown |
| Circuit-breaker escalation | Removes a poisoned session after repeated failures | Do not count deliberate aborts as database outages or abort an entire datasource |
| Tenant emergency stop | Stops a selected runaway workload | Administrative bulk targeting needs separate authorization and auditing |
| Graceful deployment drain, then abort stragglers | Gives normal work time to finish | Can interrupt transactions; XA recovery must remain possible |
| Primary/replica cleanup | Stops both owned connections and cursor/LOB activity | Do not allocate an unused primary just to abort it |
| Chaos testing | Exercises ambiguous commits, pool leaks, and race conditions | Must be isolated from production and use non-sensitive diagnostics |
| Abort diagnostics receipt | Distinguishes accepted cleanup from verified destruction | Additional state/retention; not a change to JDBC's `void` return type |

## Recommended direction

Start with a non-XA design that marks the client closed before normal return,
prevents late responses from resurrecting it, and fences server work by a
per-logical-connection identity. Use the caller's executor locally; create a
separate server executor rather than trying to serialize the caller's executor.
Keep control capacity available even when SQL admission is saturated.

For pooled mode, stop the owned physical connection and prevent it from being
reborrowed; verify both independently. Do not close the shared gRPC channel,
invalidate every connection with the same `connHash`, or silently reconnect
the aborted JDBC object.

Treat XA as a separate recovery-aware milestone. A prepared transaction must
never be reset or recycled, and abort must never pretend it committed or
rolled back. Keeping XA abort explicitly unsupported initially is safer than
applying non-XA cleanup to it.

The [full analysis](./CONNECTION_ABORT_IMPLEMENTATION_ANALYSIS.md) contains
source evidence, the JDBC contract, implementation tradeoffs, failure cases,
compatibility decisions, and a proposed acceptance-test matrix.
