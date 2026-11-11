# Transaction Multiplexing: Decision Summary

**Date:** 2026-11-01

**Status:** Architectural analysis, not an implemented feature.

**Full analysis:** [Transaction Multiplexing and JDBC Request Boundaries](TRANSACTION_MULTIPLEXING_ANALYSIS.md).

## Recommended direction

**Yes, OJP could add PgBouncer-style transaction pooling, but not transparently for every JDBC workload.** Keep the logical client session alive while returning its physical database connection between completed transactions, provided no connection-dependent resource or session state still requires it.

Keep current session mode as the default. Start with an opt-in, restricted **non-XA** transaction mode, preserve endpoint affinity, and reuse the existing server-side pool. Do not add client-side pooling or execute two clients' transactions concurrently on one physical connection.

**Confidence: High** in the current lifecycle gap and JDBC API semantics, supported by repository/upstream source. **Medium** in implementation feasibility/performance until the proposed real-driver experiments pass; Oracle and Db2 behavior remains configuration/version-specific and uncertified.

## What changes in OJP

OJP already borrows connections lazily in some paths, but caches them on the session. Commit and rollback complete the transaction **without returning the connection**; ordinary primary-session admission permits also last until session termination.

The design must separate logical session state from physical lease ownership:

| Concern | Required change |
| --- | --- |
| Transactions | Keep one lease through full completion; preserve logical manual-commit mode while detached between transactions |
| Resources | Pin cursors, generated keys, multiple results, LOBs, arrays, and raw statements until their safe lifetime ends |
| Reusable prepared statements | Later store logical descriptors and recreate physical statements per lease; existing JDBC objects cannot migrate |
| Connection settings | Persist/replay a supported logical subset; do not assume arbitrary SQL session state is reproducible |
| Admission control | Couple capacity permits to leases, not idle logical sessions, preserving existing backpressure and pool budgets |
| Concurrency/failures | Serialize ownership transitions; generation-fence stale cancellation/resource calls; discard unsafe leases |
| Multinode/replicas | Keep the logical session on its owner endpoint; manual transactions remain primary-bound even while detached |

This is not the previously dropped eager-session-close optimization. Resource completion must not terminate the virtual connection, invalidate post-read metadata, or break follow-up transactions.

## Can beginRequest/endRequest improve reuse?

**Sometimes, as an enhancement, not a correctness shortcut.** JDBC defines them as optional vendor-specific hints with default no-op implementations. A request may include multiple transactions; neither method portably resets SQL session state or identifies transaction completion.

Inspected PostgreSQL, MySQL, MariaDB, and H2 connection implementations do not supply useful begin/end overrides. SQL Server provides concrete snapshot/restore, rollback, and statement cleanup behavior. Oracle documents continuity boundaries and separately configured reset/database-pooling capabilities. No Db2 JCC reset contract was verified. The full analysis pins source revisions and distinguishes source findings from runtime certification.

HikariCP 7.0.2 already offers an unsupported JVM-wide request-boundary flag, but logs hook failures and can still reuse the connection. DBCP2 2.14.0's connection wrapper does not forward these methods through explicit overrides. Neither is a safe blanket switch for OJP.

Choose one boundary owner at physical lease borrow/return, not per gRPC call. Begin before applying session settings; end only after transaction and dependent resources finish. Validate driver/pool ordering and quarantine on cleanup failure. Retain explicit rollback, baseline restoration, and vendor-certified sanitation; successful no-op calls prove nothing.

Hooks could improve today's session-mode cleanup/continuity independently, without reducing physical occupancy. A future explicit request mode could retain one lease across multiple transactions inside an application request, but would require additional client cooperation and capability negotiation.

## Implementation sequence and decision gates

1. Define the opt-in stateless workload contract, lease state machine, pin reasons, logical settings, provider discard guarantees, and admission ownership.
2. Prototype non-XA transaction release with conservative resource pinning, H2/PostgreSQL, and both built-in pools; leave default behavior unchanged.
3. Certify request hooks with real driver tests, including SQL Server and available Oracle configurations, and measure their incremental value.
4. Virtualize reusable prepared statements and expand compatibility only where lifecycle tests justify it.

The decisive experiment uses a physical pool of size one: session A completes a transaction and closes dependent resources but stays logically open; session B must then execute before A closes. Also verify repeated manual transactions, state isolation, deferred resources, unknown commit outcomes, hook/reset failures, cancellation races, permits, replicas, and unchanged XA/default behavior through public APIs against a real server.

Multiplexing can reduce idle physical occupancy, but extra acquisition, reset, re-preparation, and resource retention can outweigh that gain. Benchmark identical pool caps and agree workload-specific performance thresholds before rollout. Stateful SQL, opaque routines, vendor extensions, and unsupported resource combinations should use session mode unless explicitly certified.
